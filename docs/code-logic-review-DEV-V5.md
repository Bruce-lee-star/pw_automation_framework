# 框架代码逻辑评审（DEV-V5 / af9bdeb8）

**评审约束**：只读源码的控制流、并发原语与资源生命周期。**不参考**历史评审文档、`MEMORY.md`、`.workbuddy` 记忆，**不把代码注释中的"已修复/已加固/已评审"作为结论依据**（注释仅用于定位意图，结论一律由代码路径自行推导）。

**覆盖范围**：`pw-core` / `pw-web-ui` / `pw-route` / `pw-reports-v2` / `pw-codegen` 主源码，重点为生命周期、并发、证据链、报告落盘四条主线。

**规模基线**：主源码 423 文件；热点文件 `RouteDsl` 1342、`ConfigKeys` 1210、`ApiSpec` 1209、`WebFrameworkConfig` 1191、`PageElement` 1162、`RoleElementPageGenerator` 1109、`RoleElementPicker` 1105、`BasePage` 1020。

---

## 结论摘要

共 22 项。其中 **4 项 P0**（会造成**错误结论**：假失败 / 假绿 / 证据静默丢失 / 跨用例污染），**10 项 P1**（报表失真、可观测性失效、热路径损耗、静默丢配置），**8 项 P2**（一致性与边角正确性）。

最该先处理的三件事：

1. **驱动信道被做成了进程级单例**（`GuardedDriverCallImpl`）。它的"毒化重置"动作是 `shutdownNow()` 全局执行器 —— 一个用例的驱动超时会**打断其它并行用例的在途调用**，把别人的调用变成 null 或异常。这是当前最可能产出"错误结论"的机制。
2. **`DriverFailureSignal` 是进程级静态开关，却按"每 context 消费即清零"使用**。任一用例报告驱动故障，会让**所有**并行用例的路由响应轮询停摆 → 证据缺失 / 监控超时假红。
3. **trace 在 context 关闭路径上必然拿不到句柄**。`attachVideo` 有跨线程回落、`attachTrace` 没有；两条路径的不对称意味着"关闭时收尾的 trace"永远进不了报告。

---

## P0 — 会产出错误结论

### P0-1 驱动信道是进程级单线程，超时即全局 `shutdownNow`

**证据**
- `pw-route/.../binding/GuardedDriverCallImpl.java:76` `private static final AtomicReference<ExecutorService> DRIVER`，`:87` `Executors.newSingleThreadExecutor(tf)` —— 全进程仅 1 条 `route-driver-<epoch>` 线程。
- `:97-100` 所有 `guarded(...)` 调用 `DRIVER.get().submit(action)`。
- `:180-190` `handleTimeout` 直接 `current.shutdownNow()` + `awaitTermination(POISON_DRAIN_MS)`。
- `:200-211` 收尾成功则 `newDriverExecutor()` 并 CAS 替换；失败则 `UNUSABLE.set(true)`。
- `:243-249` `resetChannel()` 用 `DRIVER.getAndSet(fresh)` + `previous.shutdownNow()`。

**机制**（并行执行时）
1. 用例 A 的驱动调用超时 → `handleTimeout` 对**共享**执行器 `shutdownNow()`。
2. 用例 B 此刻正在 `future.get(boundMs)` 上等待 → 被中断 → `:129-136` 走 `InterruptedException` 分支：`FAIL_FAST` 抛 `IllegalStateException`（**B 用例被 A 的故障判失败**），否则返回 `null`（**B 静默降级**）。
3. 执行器随即被替换为新实例。若 A 的 native 调用其实没停（`drained=false` 时的情形），新旧两条线程会同时持有同一连接 —— 恰好是类设计要杜绝的"第二个消息泵"。
4. `resetChannel()` 同理：某用例换 `Playwright` 实例后调它，会把**其它用例**当前使用的驱动线程 `shutdownNow`。

**影响**：并行度越高越明显。表现为"随机某个用例报 driver call 失败 / 某用例接口证据全空"，且日志里指向的用例与真实故障用例不同 —— 排查方向会被直接带偏。

**方向**：`DRIVER` 的粒度必须与会话语义对齐（per-`Playwright` 实例 / per-context），或至少把"毒化"限制在租约内（引用计数 / epoch 校验：只有仍在用同一 epoch 的调用者受影响）。全局 `shutdownNow` 与"每用例独立 Playwright 实例"的模型自相矛盾。

---

### P0-2 `DriverFailureSignal` 全局置位、按 context 清零 → 并行用例互相毒化

**证据**
- `pw-core/.../common/route/DriverFailureSignal.java:25` `private static final AtomicBoolean FAILED`。
- `pw-route/.../RouteRuntimeImpl.java:198` 每轮响应轮询首行：`if (closed.get() || DriverFailureSignal.reported()) return;`
- `pw-route/.../RouteRuntimeImpl.java:711` `close()` 中 `DriverFailureSignal.reset();`

**机制**：UI 侧任一线程 `report()` 置位 → 全局为 true → **所有** runtime 的 `scheduleResponsePoll` 在下一拍直接返回、**不再重新调度**（该分支是 `return`，不是"跳过本轮"）。轮询链就此断裂，缺口只能由 sink 的 drain 超时路径兜底。哪个 runtime 先 `close()` 谁把它清掉，恢复时机不确定。

**影响**：一个用例遇到驱动抖动，其它并行用例的 MONITOR 断言与 CAPTURE 采集同时停摆 → 报告里出现"监控超时"与"无接口证据"，与被测应用无关。且因为轮询链是**断链**而非暂停，即使信号被清掉，那些用例也不会自动恢复。

**方向**：把信号改成 per-runtime（或 per-context 维度）状态；至少把"断链"改成"本轮跳过、下轮继续调度"，使恢复是可能的。

---

### P0-3 trace 在 context 关闭路径上必然拿不到句柄 → 静默不进报告

**证据**
- `pw-web-ui/.../lifecycle/CloseGuard.java:126` 每个关闭动作 `new Thread(...)` 执行。
- `pw-web-ui/.../context/ContextRegistryImpl.java:239` `cleanupContextScoped` 经 `CloseGuard.runBounded("context-close", ...)` 关闭 context。
- `pw-web-ui/.../trace/ScenarioTraceRecorder.java:190-191` `onContextClosing` 在该 worker 线程上执行导出 → `attachToReport`。
- `pw-reports-v2/.../integration/ReportingBridgeImpl.java:90-94`（及 `:103-107`）：`bus.captureHandle()` 为 NOOP 就 `return`。
- `pw-reports-v2/.../event/ReportBus.java:808` `attachTrace` 前置于 `if (... || handle.isNoop() || ...) return;` —— **没有** 跨线程回落。
- 对照：`ReportBus.java:733-738` `attachVideo` 在 `handle.isNoop()` 时回落 `activeOutcomeId()`。

**机制**：`captureHandle()` 依赖用例线程的 ThreadLocal。`CloseGuard` 的 worker 线程与场景线程不同，恒 NOOP → trace 既不落到 per-case 目录，也不会被登记到 outcome。文件留在 `traces/`，随后可能被 `TraceRetention.pruneIfDue()` 清掉。

**影响**：只要 trace 的收尾发生在 context 关闭阶段，"报告里有 Trace 卡片"这件事就不成立；而 video 因为补过回落所以正常 —— 现象会显得"trace 有时有有时没有"，很难归因。

**方向**：让 `attachTrace` 与 `attachVideo` 走同一套归属解析（显式 `outcomeId` 参数优先，其次 `activeOutcomeId()`，最后才 NOOP 丢弃）。更彻底的做法是：trace 导出时不依赖 `captureHandle()`，由调用方把 `scenarioId` 一路透传到 bridge（`exportChunk` 已经持有 `scenarioId`，只是没往上传）。

---

### P0-4 单飞登录守卫可让同一线程自锁一个完整超时窗口

**证据**
- `pw-web-ui/.../session/SessionStore.java:557-611` `acquireOrAwait`：`putIfAbsent` 未命中即 leader（`:562-567`）；命中且 `owner.isAlive()` 则 `existing.await(SINGLE_FLIGHT_TIMEOUT_MS)`（`:568, 582`）。
- `:662-668` 单参 `restoreSession` 的 leader 分支在"无可复用会话"时 **`return false`，不 `completeLoginGuard`、不 `remove`**。
- `:719-728` `wrapLeaderAction` 只在 `RuntimeException` 时 `completeLoginGuard(key,false)`；**正常返回但没有 `saveSession`** 的情况无兜底。
- `:196-199` `complete` 才 `countDown`。

**机制**：`LoginGuard.owner` 是创建时的线程。若 leader 路径正常返回却未落会话（登录前置条件不满足、业务侧吞掉登录动作、或提前 `return`），guard 会永久留在 `loginGuards` 里且 owner 就是当前线程。同一线程下一次进入 `acquireOrAwait`：`putIfAbsent` 拿到的是自己上次那个实例 → `owner.isAlive()==true`（自己）→ `await` **等自己倒计时** → 必然等满 `SINGLE_FLIGHT_TIMEOUT_MS` 才进 takeover 分支。

**影响**：同一用例内二次尝试登录（重试、切换身份失败后回退）会莫名卡住一个超时窗口（默认值见 `WebPlaywrightConfigKeys.PLAYWRIGHT_NO_LOGIN_SINGLE_FLIGHT_TIMEOUT_MS`）。日志上表现为"等待并发登录取会话超时"，但根本没有并发。

**方向**：①`owner` 判据加入"是否本线程"分支（`owner == Thread.currentThread()` 直接视为可夺取）；②`wrapLeaderAction` 用 `try/finally` 兜底 `completeLoginGuard(key, success)`，把"未显式完成即视为失败"变成硬约束。

---

## P1 — 报表失真 / 可观测性失效 / 热路径损耗

### P1-5 `ReportBus.startRun()` 复位不完整
`pw-reports-v2/.../event/ReportBus.java:145-151` 只 `sessions.clear()` + `runFinished.set(false)`。未重置进程级 `activeStep` / `activeOutcomeId`（并行时为"最近开始的用例"单值），未清 `sinkErrors`（`:83`），也未向 sink 派发 startRun 事件。同 JVM 内多次 run（rerun、多 feature 分轮）时，残留的活动用例指针会让**第一轮收尾之后**的跨线程事件（trace/video/证据 flush）挂到上一轮的 outcomeId 上；`getSinkErrorCount()`（`:919`）变成累计值，无法判断"本轮是否安全"。

### P1-6 `QueuedNetworkEvidenceBridge.record(NetworkCall)` 在事件线程恒丢证据
`pw-reports-v2/.../integration/QueuedNetworkEvidenceBridge.java:201-205` 用调用方线程的 `bus.captureHandle()` → `record(NOOP, call)`；而 `ReportBus.attachEvidence`（`:466-467`）与 `recordApiOperation`（`:487-488`）对 NOOP 直接 `return`。该重载若从路由事件/调度线程调用即**静默无产出**，且**不计入 dropped**（与 `pending` 队列的 `dropped` 计数口径不一致），排查时看不到任何信号。同文件 `:178` 的无参 `flush()` 有同样问题（好在当前只在主线程调用）。

### P1-7 `RouteDelayScheduler` 是全局 2 线程节拍器，且"饱和告警"是死信号
`pw-route/.../exec/RouteDelayScheduler.java:49-60` 固定 2 线程、`ScheduledThreadPoolExecutor` 队列无界。`:76-85` 用 `RejectedExecutionException` 计 `REJECTED`，但无界队列 + 未重写拒绝策略 ⇒ 该异常**只可能在 shutdown 后出现**，`rejectedCount()`（`:94`）在正常运行中永远为 0，"调度池饱和"这一诊断信号实际上无法触发。而上游 `RouteRuntimeImpl.java:209-215` 的响应轮询以 25→50→100ms 递推、每个在途观测请求每拍占一次调度 → 并行度高时 2 条线程成为**全进程**路由节拍上限，且入队无背压，堆积只体现在内存上。

### P1-8 `executeWithRetry` 的 `Supplier<Boolean>` 契约未实现，且两条重试路径异常处理不对称
`pw-web-ui/.../element/ElementOperationSupport.java:104-126`：首次 `action.get()`（`:109`）与重试 `action.get()`（`:115`）**都丢弃返回值** —— `false` 与 `true` 语义等价，所谓"带成功检查"不成立，失败只能靠抛异常。另：重试失败分支 `:117` catch `RuntimeException`，而 `executeSafely` 的同位置 `:56` 只 catch `PlaywrightException` → 非 Playwright 异常在 `executeSafely` 中会**绕过诊断收集与异常包装直接逃逸**，两条"同一模板"的行为不一致。附带的判据问题：`:175` 用 `m.contains("navigat")` 做导航/上下文丢失识别，子串过宽，任何含 navigate 的错误都会触发一次额外 DOM 等待 + 重试，放大真实失败的耗时。

### P1-9 `LocalReportSink` 跨 run 不重置，且在事件派发线程串行做全部渲染
`pw-reports-v2/.../sink/LocalReportSink.java:47` `writtenIds`、`:48-49` `errors` 都是实例级且从不 `clear()`。同 JVM 第二轮 run 若出现相同 `outcomeId`，`:74` 会判定"已写"直接跳过 → **per-case JSON（真相源）缺失**。`finishAll`（`:86-164`）在 `RunFinished` 的派发线程上同步完成全部 HTML/JSON/CSV/JUnit/资产渲染，无并发、无超时；套件越大，越可能被 CI 整体超时砍在"汇总产物尚未落盘"的窗口里（per-case JSON 有实时保，汇总没有）。

### P1-10 `getPage(cls, strategy)` 静默丢弃自定义供应商与钩子
`pw-web-ui/.../page/factory/PageObjectFactory.java:278-288`：策略与当前配置不同时新建 `Builder`，只搬 `lifecycle` 与 `lazy`，`customSuppliers` / `postCreateHooks` / `properties` 全部丢失。`newInstance`（`:383-387`）先查全局 `GLOBAL_SUPPLIERS` 再查 `config.getCustomSuppliers()`，所以 `register()` 路径不受影响，但**通过 `Builder.customSupplier()/postCreateHook()` 配置的调用点**在显式传 strategy 时会静默失去注入与钩子 —— 典型表现是 `AbstractManagedPage.getPage()` NPE 或钩子不执行。

### P1-11 `PageObjectFactory` 的只读 API 会写状态，且泄漏指标口径不一致
`:193-196` `threadInstances()` 用 `TestContextHolder.get().computeIfAbsent(...)` —— `hasInstance()`（`:580-589`）、`getInstanceCount()`（`:596-602`）、`getStatistics()`（`:609-630`）、`getRegisteredPageClasses()`（`:637-647`）这些**纯查询**都会为当前线程创建 TestContext（并在 `ThreadContextRegistry` 里注册一条）。同时 `getInstanceCount()` 只统计当前线程，而 `clearAll()`（`:543-549`）跨线程统计 → "实例数"这个泄漏观测指标本身不可信。

### P1-12 `requestScopedInstances` 的键在收尾时可能已改口径 → 条目永久滞留
`:463-469` `getCurrentRequestId()` 优先 `ScenarioContext.currentScenarioId()`，回落 `thread:<tid>`；`:511-518` `endRequestScope()` 重新调用该函数取键。若收尾时刻场景身份已解绑，键变成 `thread:<tid>`，写入时的 `scenario:<id>` 条目就再也删不掉 —— 静态 Map 随用例数单调增长，且持有整个 PageObject 图（含 Page/Context 引用）。

### P1-13 `AsyncPool.schedule*` 拒绝时返回 `null`，与"静默丢弃"并存
`pw-core/.../common/async/AsyncPool.java:386`、`:410` 在拒绝路径 `return null`。调用方普遍按"必有 future"使用，后续 `cancel()` 即 NPE。更微妙的是拒绝策略为 `DiscardOldestPolicy`（`:168`）时**不抛异常**，所以"被拒绝"有两种互斥表现（`null` 返回 / 静默丢别人的任务），调用方无法用同一套判据处理。

### P1-14 `activeThreadCount()` 用未 purge 的 `WeakHashMap.size()`
`pw-core/.../core/context/ThreadContextRegistry.java:90-92` 直接返回 `CONTEXTS.size()`。线程消亡后条目仍在，直到下一次对该 map 的操作触发 purge → 指标只增不减，且 `PageObjectFactory.clearAll()` 的日志（`:551-553`）引用的 `activeContextCount()` 同样偏大。

---

## P2 — 一致性 / 边角正确性

| # | 位置 | 问题 |
|---|---|---|
| P2-15 | `ContextRegistryImpl.java:246` | `cleanupContextScoped` 用**清理时**的 `b.isCodegenEnabled()` 门控 `RoleCodegenBridge.cleanupContext`。创建时启用、运行中关掉开关 ⇒ 清理被跳过 ⇒ `RolePickerSessionState` 的 9 张按 context 的静态 Map（`RolePickerSessionState.java:29-39`）残留。因果顺序应为"创建时记录是否启用"。 |
| P2-16 | `PlaywrightContextManager.java:444 / 449-450` | `closePage` 内先 `throw new BrowserException("Failed to close page: ...")`，外层 `catch (Exception e)` 再包一层 `new BrowserException("Failed to close page", e)` —— 同类两层，原始原因被埋一层。 |
| P2-17 | `PlaywrightContextManager.java:77 / 404` | `CONTEXT_VIDEOS` 是强引用键的 `ConcurrentHashMap`，仅 `closeContext` 的 `finally` 移除。未经 `closeContext` 的路径（进程被杀、异常提前返回）会永久持有已死 Context 与 Video 句柄。 |
| P2-18 | `PageContextState.java:73 附近` | `owner.page` / `owner.context` 为非 `volatile` 字段，事件线程与 CloseGuard worker 读取无可见性保证；`ensurePageValid()` 每次元素操作都调 `PlaywrightManager.getPage()` 做一致性比较，即使 page 有效 —— 热路径上每次元素操作多一条静态调用链。 |
| P2-19 | `AbstractManagedPage.java:68-70` → `RecordingPageProxy.java:74-85 / 91-117` | 录制开启时，**每次** `getPage()` 都 `Proxy.newProxyInstance` 新建代理；`wrapIfRecordable` 对每个返回的 Locator/Frame 也新建。一次 `locator(x).click()` 产生多次动态代理分配。代理应按底层 Page 身份缓存（弱引用键）。 |
| P2-20 | `ConsoleCapture.java:145 / 156 / 136 / 186` | tee 流 `write(int)` 与 `write(byte[],off,len)` 逐字节处理，配 `ThreadLocal<ByteArrayOutputStream>` 行缓冲后整体 `new String(..., UTF_8)` 解码 —— 逐写路径开销明显；且按行拼字节后整体解码，若一次 `write` 恰好切在多字节字符中间，该行会解码出替换字符。`uninstall()`（`:70`）只按 `installed` 标志复位，期间被第三方库再次 `System.setOut` 则 tee 已丢失而标志仍为 true。 |
| P2-21 | `ScenarioTraceRecorder.java:190-191 / 327 / 335` | `onContextClosing` 在 `!traceEnabled()` 时直接 `return` → 运行中关闭 trace 开关后 `tracing().stop()` 永不执行，context 关闭时 trace 残留未落盘。`traceFileName` 只用开始时间戳，`readableStamp` 用 UTC，与报告其它时间口径（本地时区）不一致。 |
| P2-22 | `SessionStore.java:351-353` vs `:378-388` | 过期处理两条路径不一致：`getStorageStateContent` 的过期分支只 `INVALIDATED_KEYS.add` **不删文件**；`evictIfExpired` 会删文件并 `META_CACHE.put(ABSENT_META)`。前者会让过期快照留在磁盘，下次冷启动再读一次再判过期。 |

---

## 已确认没有问题的点（避免重复排查）

以下几处我按控制流逐行核对后**未发现**先前担心的缺陷，列出来是为了缩小后续排查面：

- `PageEventMonitor.java:87-90` 与 `PageInteractionMonitor.java:77-80` 的 `REGISTERED_CONTEXTS` / `REGISTERED_PAGES`：虽无显式 `remove` 路径，但底层是 `newSetFromMap(new WeakHashMap<>())`（弱引用键），不会因每 case 重建 context 而无界滞留。
- `RolePickerSessionState.cleanupContext`（`:81-97`）确实覆盖了全部 9 张按 context 的 Map 与 3 张按 page 的 Map，`clearAll`（`:122-144`）也同步回收了归属其它类的缓存，无遗漏。
- `ReportBus.finishRun` 的幂等由 `runFinished.compareAndSet` 保证；`fanOut`（`:938-946`）逐 sink try/catch，单 sink 失败不影响其余。
- `RouteRuntimeImpl.close()`（`:689-726`）有 `closed.compareAndSet` 幂等，且 `closed` 置位早于 `drainMainThreadActions`，不会在收尾 drain 中重新入队形成自循环。
- `RouteClaim` 的 `toIoAwait` / `tryTerminal` CAS 状态机本身自洽（NEW→IO_AWAIT/HANDLED→RELEASED 无环）；仅 `isIssued()` 检查与 `route.fallback()` 之间仍有 TOCTOU 窗口（`RouteAction.fallback(claim, route)`），属"无法彻底消除、只能收窄"的一类，未计入上表。

---

## 建议的处理顺序

1. **P0-1 / P0-2**：这两个是同一个根因的两面 —— **进程级单例承载了 per-scenario 的故障语义**。建议合并处理：把驱动信道与故障信号下沉到 `Playwright` 实例（或 context）维度，或者在无法下沉时引入租约/epoch 校验，禁止跨用例的 `shutdownNow` 与跨用例的信号置位。
2. **P0-3**：改动面最小、收益最直接 —— 给 `attachTrace` 补上与 `attachVideo` 对称的归属回落，并让 `ScenarioTraceRecorder.exportChunk` 把已持有的 `scenarioId` 透传给 bridge。
3. **P0-4**：`SessionStore` 的两处兜底（owner 自线程判定 + `finally` 完成守卫），改动局限在单文件。
4. **P1-5 / P1-6 / P1-9**：报告可信度三连。建议一起做：`startRun` 完整复位、`record(NetworkCall)` 强制走队列并计 dropped、`LocalReportSink` 按 run 重置状态。
5. 其余 P1/P2 可按"是否影响结论"排序，逐条收。

---

## 修复实施记录（2026-10-24，DEV-V5 后续）

> 4 个 P0 已落地，并通过 `mvn install -Dspotbugs.skip=true`（全 8 模块 BUILD SUCCESS，测试全绿，checkstyle 0 violations）。**逐条记录真实落地情况、校验结论与同源限制**，并附"代码冗余 / 职责冗余"审查。

### P0-3 ✅ 已修复（最小、对称）

- **改动**：`ReportBus.attachTrace` 抽出 `attachTraceToOutcome(outcomeId, ...)` 并加 `handle.isNoop() → activeOutcomeId()` 兜底（与 `attachVideo`/`attachVideoToOutcome` 完全对称）；`ReportingBridgeImpl.attachTrace` 两个重载同样改为 NOOP 时回落 `bus.activeOutcomeId()` → `attachTraceToOutcome`。
- **验证**：`pw-reports-v2` 254 run / 0 failure / 0 error，含 `TraceBarRenderTest`、`SnapshotGateTest`。
- **同源限制**：`activeOutcomeId()` 是单个 `volatile` 字段（非 ThreadLocal / 非 per-thread）。**并行**执行时，兜底可能把 A 场景的 trace 归到当前"活动"用例 id（与 `attachVideo` 同源、已接受的同一限制）；串行/常规运行下正确。比"静默丢"严格更优。彻底修法（评审方向）是把 `scenarioId` 从 `exportChunk` 一路透传到 bridge，需改 `ReportingBridge` 接口签名（波及 pw-core），本次未做。

### P0-4 ✅ 已修复（单文件）

- **改动**：`SessionStore.acquireOrAwait` 的 takeover 条件加 `owner == Thread.currentThread()`；单参 `restoreSession` 的 leader 分支在无可复用会话时显式 `completeLoginGuard(sessionKey, false)`；`wrapLeaderAction` 用 `try/finally` 兜底 `completeLoginGuard`，把"未显式完成即视为失败"变成硬约束。
- **验证**：`SessionStoreSessionExpiryTest` 3/0 绿；`pw-web-ui` 安装 SUCCESS。
- **同源限制**：同 P0-3，是"串行正确、并行无新问题"的单点修复，未引入新的跨线程归属问题。

### P0-2 ⚠️ 部分修复（症状级）+ 对评审的重要更正

- **更正（评审偏活跃）**：核对主源码发现 `DriverFailureSignal.report()` **无任何生产调用方**——全仓仅 `RouteFailFastTest` 调用；`ScenarioLifecycle` 只 `reset()`，`RouteRuntimeImpl` 只 `reported()`。即评审所述"一个用例驱动抖动即时毒化所有并行用例"当前**并非活跃故障**，是**休眠的设计缺陷**（若日后接上 UI 上报才会触发）。
- **改动（防御）**：`RouteRuntimeImpl.scheduleResponsePoll` 在信号置位时由"断链 `return`"改为"跳过本轮、继续按节拍调度、待对应 runtime `close()` 的 `reset()` 后自愈"（受 `deadlineNs` 兜底，不会无限空转）。
- **验证**：`pw-route` 351/0 绿。
- **未做（建议另立任务）**：把信号下沉为 **per-context**（键用 `Object` 身份以遵守 pw-core "不引用 Playwright 类型"约束），并接上真实的 UI 失败上报（目前该 feature 可能根本没接线）。

### P0-1 ⚠️ 症状级缓解 + 根因未除（架构级）

- **改动**：`GuardedDriverCallImpl.guarded()` 增加 `CancellationException` catch——被其它调用 `shutdownNow()` 取消的 `future.get()` 不再逃逸成 `IllegalStateException`（假失败），而是降级（即使 `FAIL_FAST` 也降级而非抛）。
- **验证**：`pw-route` 351/0 绿（含现成的 `DriverChannelContainmentTest`）。
- **根因（评审方向，未做）**：`DRIVER` 是 `static` 单线程执行器，`guarded()` 接口不携带连接身份 → 无法 per-context 隔离。彻底修法需 per-`Playwright`/per-context 信道 + 改 `GuardedDriverCall.guarded` 接口及调用方（`PatternBinder`、`RouteAction.fetch`）。属**架构级重构、高风险**，建议作为专门任务 + 并行驱动超时集成测试，而非顺手改。

### P0-5 ✅ 彻底去兜底（trace/video 直接生成到 per-case，消除副本逻辑，2026-10-24）

- **用户要求**：trace 与 video 应**直接生成到 per-case 目录**，不应有"先落全局目录再搬/拷"的副本逻辑。
- **事实澄清**：正常路径本就已直接落 per-case——video 在 context 创建时 `setRecordVideoDir(videoTarget())`（2026-09-30 收口后 context 为 scenario-scoped，创建时句柄有效）；trace 在 scenario 线程上的 `onScenarioEnd` 经 `traceTarget()` 解析到 per-case。**唯一产生副本的**是 `onContextClosing`：它跑在 **CloseGuard 的 `close-bounded-*` 收尾线程**，该线程无 ThreadLocal 用例上下文（句柄恒 NOOP），于是 `traceTarget()/videoTarget()` 返回 null → trace 落到全局 `traces/`、video 落到配置 videoDir，事后由 `mountArtifact`（P0-3）搬/拷进 per-case。
- **改动**：`ReportingBridgeImpl.traceTarget()/videoTarget()` 在句柄 NOOP 时，**回落到 `ReportBus.activeOutcomeId()`** 解析 per-case 落点（新增 `ReportBus.traceTargetByOutcomeId(String)` / `videoTargetByOutcomeId(String)`，绕开 ThreadLocal 句柄）；`ScenarioTraceRecorder.resolveTraceTarget` 无需改动（它调用的 `traceTarget()` 桥现在总能解析 per-case）。结果：**trace/video 在任意线程（含 CloseGuard 收尾线程）都直接写进 per-case，永不搬/拷**。`mountArtifact` 仅保留为"真正无报告 / 无活动用例"时的证据安全网（落到全局 `traces/` 才搬）。
- **同源限制（已被 P0-6 消除）**：原 P0-5 沿用 `activeOutcomeId()` 单 volatile 语义——并行下取"最近开始的用例"（最佳努力）。该限制已由 P0-6（per-context 绑定）彻底消除。
- **验证**：`pw-reports-v2` 257/0 绿（新增 3 例：按 outcomeId 解析 per-case、bridge 活动期解析到 per-case）；`pw-core`/`pw-web-ui` 编译通过；已装本地仓。

### P0-6 ✅ per-context 绑定彻底正确化（消除并行下"取最近开始的用例"歧义，2026-10-24）

- **承接 P0-5 的残留**：P0-5 让 `traceTarget()/videoTarget()` 在 CloseGuard 收尾线程上**回落到 `activeOutcomeId()`**（单 volatile，并行下取"最近开始的用例"，仅最佳努力）。在并行执行下，关闭 context A 的收尾线程可能错误地把 trace/video 归属到"当前最近开始"的用例 B。P0-6 把归属解析从"全局最新活动用例"升级为**按 `BrowserContext` 精确绑定**。
- **机制（复用 `DownloadRegistry` 的 `ConcurrentHashMap<BrowserContext,…>` 范式）**：
  1. 新增 `ContextOutcomeBinding`（`framework.web.lifecycle`，线程安全 `ConcurrentHashMap<BrowserContext,String>`，`bind/outcomeId/unbind`；`BrowserContext` 未覆写 equals/hashCode → 按身份匹配，close 时 `unbind` 防泄漏）。
  2. `PlaywrightContextManager.createContext` 在 context 创建成功（场景线程、句柄有效）时，**绑定 `ContextOutcomeBinding.bind(context, ReportingBridgeRegistry.get().currentOutcomeId())`**；`closeContext` 在 `finally` 中 `unbind(context)`。
  3. `ScenarioTraceRecorder.onContextClosing(BrowserContext)` / `onScenarioEnd` 改用 **`ContextOutcomeBinding.outcomeId(context)` 优先**解析落点与文件标题/归属（再回落 `SCENARIO_ID` ThreadLocal / 全局 `activeOutcomeId` 最佳努力）；`exportChunk→attachToReport` 透传该 outcomeId，经新增的 `ReportingBridge.attachTrace(outcomeId, …)` / `attachVideo(outcomeId, …)` 精确挂载到 per-case。
  4. `PlaywrightContextManager.closeContext` 把 `ContextOutcomeBinding.outcomeId(context)` 传给 `attachVideosToReport`，video 挂载同样按 context 精确归属。
  5. `ReportingBridge` 接口新增 `currentOutcomeId()` 与按 outcomeId 直接挂载的 `attachVideo(outcomeId,Path)` / `attachTrace(outcomeId,Path)` / `attachTrace(outcomeId,Path,start,end,dur)` 默认方法（no-op 桥零改动）；`ReportingBridgeImpl` 覆写委托到 `ReportBus.activeOutcomeId()` / `attachVideoToOutcome` / `attachTraceToOutcome`。
- **语义收益**：在任何线程（含 CloseGuard 收尾线程）、任何并行度下，trace/video 都精确归属到"被关闭的 context 所属用例"的 per-case 目录——**不再是"最近开始的用例"最佳努力，也永不搬/拷**。
- **适用范围**：项目已无 feature 模式，每个 scenario 都会重建 context（context 与 scenario 一一对应），故 `createContext` 绑定的 outcomeId 与所关闭 context 始终一致，per-context 绑定在任意 scenario 级路径下完全正确（trace/video 均为 scenario 级别产物）。
- **验证**：`pw-reports-v2` 260/0 绿（新增 3 例：`ReportingBridgeImplOutcomeBindTest`——`currentOutcomeId` 返回活动用例 id、`attachTrace(outcomeId,…)` / `attachVideo(outcomeId,…)` 精确挂载 tracePath/videoPath）；`pw-web-ui` 新增 `ContextOutcomeBindingTest` 4 例（bind/get/unbind、distinct context 互不串扰、null/empty 忽略）全绿，主代码编译通过；`pw-core`+`pw-reports-v2` 已装本地仓。

### 代码冗余 / 职责冗余 审查（本轮逐区扫出）

1. **报告层"句柄→outcomeId 归属解析"职责跨两层重复（P0-3 区域）—— 已 Consolidate（2026-10-24）**：`ReportingBridgeImpl.attachTrace`×2、`attachVideo`×1 原本各自实现 `captureHandle → NOOP 则 activeOutcomeId` 回落，与 `ReportBus` 内部兜底重复。因 bus 的 `attachTrace/attachVideo` 在 P0-3 后已自带该兜底，bridge 改为直接 `bus.attachTrace(bus.captureHandle(), ...)` 透传，归属解析唯一收口在 `ReportBus`。验证：`pw-reports-v2` 254/0 绿。
2. **`attachTraceToOutcome` 与 `attachVideoToOutcome` 主体重复（P0-3 区域）—— 已 Consolidate（2026-10-24）**：两方法"建目录 + move/copy + 设字段"脚手架近乎一致，已抽私有 `mountArtifact(outcomeId, source, relativeTarget, moveWhenOutsideReport, onMounted)`，两方法改为委托。⚠️ **澄清**：二者并非"纯结构重复"——录像恒 `move`（清理 Playwright 临时文件），trace 仅报告**内** `move`、报告**外** `copy`（保留框架侧原件，契约由 `ReportBusTraceTargetTest.foreignTraceIsCopiedOrMoved` 锁定）。差异保留为 `mountArtifact` 的布尔策略参数，未强行统一。验证：`pw-reports-v2` 254/0 绿。
3. **"驱动信道健康"状态散落两处全局静态（P0-1/2 区域）**：`GuardedDriverCallImpl.UNUSABLE`（静态）与 `DriverFailureSignal.FAILED`（静态）都在表达"驱动/信道不健康"，语义重叠又不完全等价，无统一归属。评审也指出二者同源（进程级单例承载 per-scenario 故障语义）。**建议**：随 P0-1/2 的 per-context 下沉一并统一为单一"per-context 信道健康"状态。
4. **`GuardedDriverCallImpl` 内"拆旧执行器 + 建新执行器"逻辑重复（P0-1 区域）**：`handleTimeout` 成功路径与 `resetChannel()` 都做 `shutdownNow()` + 替换实例。可抽 `rebuildExecutor()` 收敛。⚠️ 触及毒化重置核心逻辑，重构需配套测试。

> 冗余处理进度（2026-10-24）：#1、#2 已 Consolidate（`pw-reports-v2` 254/0 绿、已装本地仓）；#4 为纯结构重复但触及毒化重置核心，待配套测试后再动；#3 须随 P0-1/2 架构下沉一并处理。是否继续落实 #3/#4，建议单独确认。
