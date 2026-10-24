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

### P1-5 `ReportBus.startRun()` 复位不完整 ✅ 已修复（2026-10-24）

- **原问题**：`ReportBus.startRun()` 只 `sessions.clear()` + `runFinished.set(false)`，未重置跨线程可见的 `activeStep` / `activeOutcomeId`（并行时为"最近开始的用例"单值），未清累计 `sinkErrors`，也未向 sink 派发 startRun 事件 → 上一轮收尾后的跨线程事件挂到上一轮 outcomeId、`getSinkErrorCount()` 变累计值。
- **改动**：`startRun()` 复位 `activeStep = null` / `activeOutcomeId = null` / `current.remove()`（本线程残留 ThreadLocal 防御）/ `sinkErrors.set(0)`；并遍历 `sinks` 调新增的 `ReportSink.onRunStarted()`（默认 no-op），由 sink 侧清进程级残留态（见 P1-9）。
- **验证**：`pw-reports-v2` 263/0 绿（新增 `ReportBusRunLifecycleTest.startRunResetsActiveOutcomeForOtherThreads`——模拟 CloseGuard 收尾线程读活动用例，证明 `startRun` 后归零）；`pw-reports-v2` 主代码编译通过。

### P1-6 `QueuedNetworkEvidenceBridge.record(NetworkCall)` 在事件线程恒丢证据 ✅ 已修复（2026-10-24）

- **原问题**：`record(NetworkCall)` 走 `bus.captureHandle()` → 路由事件/调度线程无 ThreadLocal 步骤上下文 → `captureHandle()` 恒 NOOP → `attachEvidence`/`recordApiOperation` 对 NOOP 早退**静默丢弃**，且不计入 `dropped`（与队列溢出口径不一致）。
- **改动**：`record(NetworkCall)` 改为经 `record(call.method(), call.url(), call.toDetailLine())` **入队**（与 `record(String,String,String)` 同路径），由主线程 `flush()` 用先前 `capture()` 的有效句柄写出；队列溢出仍按 `dropped` 口径上报。`NetworkCall` 新增 `toDetailLine()`（不含前置 `method url`），`toOneLine()` 复用，确保落盘格式与旧直接写入路径完全一致。显式句柄的 `record(StepHandle, NetworkCall)` 保持原直接写入语义（调用方已持有效句柄）。
- **验证**：`pw-reports-v2` 263/0 绿（新增 `QueuedNetworkEvidenceBridgeTest.recordNetworkCallEnqueuesWithoutHandle`——事件线程 `record(NetworkCall)` 后 `pendingCount()==1` 且 flush 后证据落报告、敏感 query 被 ScrubGate 遮蔽）；主代码编译通过。

### P1-7 `RouteDelayScheduler` 是全局 2 线程节拍器，且"饱和告警"是死信号 ✅ 已修复（2026-10-24）

- **原问题**：`RouteDelayScheduler` 用 `Executors.newScheduledThreadPool`（默认无界 `DelayedWorkQueue` + 默认 `AbortPolicy`）。无界队列下 `RejectedExecutionException` **只可能在 `shutdown()` 后触发** → 正常运营中 `rejectedCount()` **恒为 0**，"调度池饱和"诊断信号是死信号；且 2 线程是全进程路由节拍上限、入队无背压，堆积只体现在内存上。
- **改动**：在 `delay()` 入口加**准入阶段软上限**——`SCHED.getQueue().size() >= maxPending`（默认 1024，经 `setMaxPending` 可覆盖）即 fail-open 丢弃并计数（`REJECTED`）。这样：① 真实饱和时 `rejectedCount()` 可触发（信号"活"了）；② 对内存有界（不再无限堆积）；③ 被丢弃的延迟/轮询由 sweep 兜底，请求不悬挂。并新增 `backlog()` 主动观测（比 `rejectedCount()` 更前置，积压攀升即预示将触达上限）。`SCHED` 字段类型由 `ScheduledExecutorService` 改为 `ScheduledThreadPoolExecutor` 以读取 `getQueue()`。类注释相应更正（"饱和可观测"段落）。
- **验证**：`pw-route` 353/0 绿（新增 `RouteDelaySchedulerTest.schedulerSaturatesAndCountsRejections`——将软上限压到 8、突发 2000 个 50ms 微任务，断言 `rejectedCount()` 较基线增长且 `backlog()` 被钳制；日志实证 `#4…#1024` 拒绝计数随积压触发）。

### P1-8 `executeWithRetry` 的 `Supplier<Boolean>` 契约未实现，且两条重试路径异常处理不对称 ✅ 已修复（2026-10-24）

- **问题①（契约未实现）**：`executeWithRetry` 首次与重试的 `action.get()` 都丢弃返回值 → `false`/`true` 等价，"带成功检查"不成立。
- **改动①**：`executeWithRetry` 改为 **honors `Supplier<Boolean>`**——`action.get()` 返回 `false`/`null`（未抛异常）即按逻辑失败收口为 `ElementOperationException`（含 DOM 诊断 + 截图）；抽 `handleNavRetry` 承载导航重试，避免重复包裹。已核实**所有真实调用方都 `return true`**（`isVisible`/`exists`/`isChecked` 等 `waitFor*` 自包含 try-catch、不经 `executeWithRetry`），故该修正零误伤。
- **问题②（异常处理不对称）**：`executeSafely` 重试分支 `:56` 只 catch `PlaywrightException`，非 Playwright 异常绕过诊断收集与包装直接逃逸；而 `executeWithRetry` 首 catch 已 catch `RuntimeException`。
- **改动②**：`executeSafely` 重试分支改为 catch `RuntimeException`，与 `executeWithRetry` 对齐（非 Playwright 异常也收集 DOM 诊断并包装为 `ElementOperationException`）。
- **问题③（判据过宽）**：导航/上下文丢失判定用 `m.contains("navigat")` 子串过宽，任何含 navigate 的报文都会触发额外 DOM 等待 + 重试，放大真实失败耗时。该判定在 `ElementOperationSupport`/`PageElement`/`PageInteractions` 三处**同源重复**。
- **改动③**：三处统一收窄为 `m.contains("navigation") || m.contains("navigating")`（保留真实导航语义，去除宽子串），覆盖 `during navigation` / `navigating to` 等真实报文。
- **验证**：`pw-web-ui` 414/0 绿（新增 `ElementOperationSupportTest`——`falseReturnIsLogicalFailureNotSilentSuccess` 锁定契约、`trueReturnSucceeds` 锁正向路径、`isNavigationOrContextLoss_*` 锁定判据收窄与既有签名）。

### P1-9 `LocalReportSink` 跨 run 不重置，且在事件派发线程串行做全部渲染 ✅ 已修复（2026-10-24）

- **问题①（跨 run 不重置，已修）**：`LocalReportSink` 的 `writtenIds` / `errors` 实例级且从不 `clear()`，同 JVM 第二轮 run 出现相同 `outcomeId` 时被判"已写"跳过 → per-case JSON 真相源缺失。
- **改动①**：`ReportSink` 接口新增 `default void onRunStarted()`；`LocalReportSink` 覆写它清 `writtenIds.clear()` + `errors.set(0)`；`ReportBus.startRun()` 复位时向各 sink 派发 `onRunStarted()`（见 P1-5）。
- **问题②（串行渲染，已修）**：`finishAll`（原 `:86-164`）在 `RunFinished` 派发线程上**串行**完成全部 HTML/JSON/CSV/JUnit/资产渲染，无并发、无超时 → 大轮次墙钟耗时高，且极端情况下可能阻塞/挂死派发线程。
- **改动②**：重写为**分片 + 并行 + 有界超时**——各独立产物（per-case HTML ×N + summary JSON + index + features + email + monitor + CSV + JUnit + 资产）作为独立任务提交到有界固定线程池并行渲染；沿用既有**单步 try/catch 隔离**（失败计入 `errors`、severe 项升级 error 日志）；`invokeAll(tasks, timeout, SECONDS)` 在 `renderTimeoutSeconds()`（默认 300s，可经 `-Dpw.report.render.timeout.seconds` 覆盖）内 join，**正常规模在超时内全部完成（契约不变：finishAll 返回时报告已落盘），病理性超时则取消未启动任务并告警**（JSON 真相源已实时落盘不丢）。使用 **daemon 线程**，避免渲染线程阻止 JVM 退出。FreeMarker 各渲染器均用静态 `Configuration`（`getTemplate`/`process` 线程安全、`setSharedVariable` 仅静态块配置一次），并发安全。
- **验证**：`pw-reports-v2` 265/0 绿（基线 263 + 新增 `LocalReportSinkRenderTest` 2 例——`finishAllRendersAllArtifactsAfterParallelSharding` 锁死并行后仍产出 index/summary-json/CSV/JUnit/email/per-case HTML+JSON 全部产物；`finishAllToleratesZeroOutcomes` 零用例不抛、空 index/summary 仍生成；既有 `ReportBusRunLifecycleTest` 2 例无回归）。

### P1-10 `getPage(cls, strategy)` 静默丢弃自定义供应商与钩子 ✅ 已修复（2026-10-24）
`pw-web-ui/.../page/factory/PageObjectFactory.java:278-288`：策略与当前配置不同时新建 `Builder`，只搬 `lifecycle` 与 `lazy`，`customSuppliers` / `postCreateHooks` / `properties` 全部丢失。`newInstance`（`:383-387`）先查全局 `GLOBAL_SUPPLIERS` 再查 `config.getCustomSuppliers()`，所以 `register()` 路径不受影响，但**通过 `Builder.customSupplier()/postCreateHook()` 配置的调用点**在显式传 strategy 时会静默失去注入与钩子 —— 典型表现是 `AbstractManagedPage.getPage()` NPE 或钩子不执行。

- **改动**：临时重建分支改为把 `base`（`=currentConfig`）的 `customSuppliers` / `postCreateHooks` / `properties` 一并 `putAll` / `addAll` 到新 config，与当前配置对齐。
- **验证**：`pw-web-ui` 417/0 绿（新增 `PageObjectFactoryInternalTest.explicitStrategyPreservesCustomSupplierAndHook`——仅经 Builder 配 customSupplier + postCreateHook、显式传 SINGLETON，断言 supplier 被使用、hook 被执行；旧实现下两者均不生效）。

### P1-11 `PageObjectFactory` 的只读 API 会写状态，且泄漏指标口径不一致 ✅ 已修复（2026-10-24）
`:193-196` `threadInstances()` 用 `TestContextHolder.get().computeIfAbsent(...)` —— `hasInstance()`（`:580-589`）、`getInstanceCount()`（`:596-602`）、`getStatistics()`（`:609-630`）、`getRegisteredPageClasses()`（`:637-647`）这些**纯查询**都会为当前线程创建 TestContext（并在 `ThreadContextRegistry` 里注册一条）。同时 `getInstanceCount()` 只统计当前线程，而 `clearAll()`（`:543-549`）跨线程统计 → "实例数"这个泄漏观测指标本身不可信。

- **改动**：① 新增 `TestContextHolder.peek()`（非创建式，用例级优先、回退线程级，与 `get()` 同优先序）+ `ThreadContextRegistry.peek()`；只读路径改用 `threadInstancesView()`（基于 `peek()`，绝不创建）。② 新增 `TestContextHolder.forEachAllContext`（遍历线程键 + 用例级上下文）+ `ScenarioContext.forEachContext`（包级私有）；`getInstanceCount()` / `getStatistics()` / `getRegisteredPageClasses()` 改用 `threadIsolatedInstanceCount()` / `allThreadInstancesView()`（**跨所有已登记上下文**聚合），与 `clearAll()` 口径对齐；`clearAll()` 自身改用 `forEachAllContext` 清理（顺带覆盖用例级上下文下残留的线程隔离实例）。
- **验证**：`pw-web-ui` 417/0 绿（新增 `PageObjectFactoryInternalTest.readOnlyApisDoNotCreateContext`——`resetForCurrentThread` 后 `peek()` 为 null，连调四个只读 API 后 `peek()` 仍为 null，证明不再创建/登记上下文）。

### P1-12 `requestScopedInstances` 的键在收尾时可能已改口径 → 条目永久滞留 ✅ 已修复（2026-10-24）
`:463-469` `getCurrentRequestId()` 优先 `ScenarioContext.currentScenarioId()`，回落 `thread:<tid>`；`:511-518` `endRequestScope()` 重新调用该函数取键。若收尾时刻场景身份已解绑，键变成 `thread:<tid>`，写入时的 `scenario:<id>` 条目就再也删不掉 —— 静态 Map 随用例数单调增长，且持有整个 PageObject 图（含 Page/Context 引用）。

- **改动**：新增线程级 `ThreadLocal<String> CURRENT_REQUEST_SCOPE_KEY`，在 `beginRequestScope` 与 `resolveInstance` REQUEST_SCOPED 创建时**记录写入所用键**；`endRequestScope` 优先取该记录键删除（未记录才回退 `getCurrentRequestId()`），删除键恒与写入键一致，不受场景解绑影响；`clearAll()` 一并清理该 ThreadLocal 残留。
- **验证**：`pw-web-ui` 417/0 绿（新增 `PageObjectFactoryInternalTest.endRequestScopeRemovesEntryEvenIfScenarioUnbound`——`begin` 场景、REQUEST_SCOPED 创建后 `ScenarioContext.endCurrent()` 模拟场景解绑，再 `endRequestScope`，断言该类型已从 `getRegisteredPageClasses()` 移除；旧实现下 `scenario:<id>` 条目滞留）。

### P1-13 `AsyncPool.schedule*` 拒绝时返回 `null`，与"静默丢弃"并存（已缓解，契约保持，2026-10-24）
`pw-core/.../common/async/AsyncPool.java:386`、`:410` 在拒绝路径 `return null`。代码注释已明确：唯一生产调用方 `RouteMonitorSession` **已 null-check**，且拒绝语义经 `droppedOnRejectionCount` 计数 + ERROR 日志可观测。拒绝策略 `DiscardOldestPolicy`（`:168`）不抛异常时由 `SCHEDULER` 正常返回 future（任务被替换而非调用方侧 null），两种路径各自有确定行为。改动契约（返回假 future）反而会误导调用方以为任务已执行，故**保持 null 契约不改为上策**；若后续新增调用方，必须以 null-check 为前置条件（已在方法 javadoc 强调）。

### P1-14 `activeThreadCount()` 用未 purge 的 `WeakHashMap.size()`` ✅ 已修复（2026-10-24）
- **原问题**：`activeThreadCount()` 直接返回 `CONTEXTS.size()`，`WeakHashMap` 的 stale 条目 expunge 依赖 GC 时机与 size() 的触发，计数可能虚高；`PageObjectFactory.clearAll()` 的 `activeContextCount()` 同样偏大。
- **改动**：`activeThreadCount()` 改为在 `CONTEXTS` 锁内**显式遍历 entrySet**——强制触发 `WeakHashMap` 的 stale 弱键 expunge，保证任意 JDK 版本下都拿到"已回收线程上下文已被移除"的准确计数（仍 `synchronized` 保证与 `get()`/`remove()` 同锁，无并发漂移）。
- **验证**：`pw-core` 主代码编译通过（Checkstyle 0 violations）。

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

### P0-2 ✅ per-context 下沉（根治并行互毒化，2026-10-24）

- **更正（评审偏活跃）**：核对主源码发现 `DriverFailureSignal.report()` **无任何生产调用方**——全仓仅 `RouteFailFastTest` 调用；`ScenarioLifecycle` 只 `reset()`，`RouteRuntimeImpl` 只 `reported()`。即评审所述"一个用例驱动抖动即时毒化所有并行用例"此前是**休眠的设计缺陷**（若日后接上 UI 上报才会触发）。
- **根因（本轮已除）**：`DriverFailureSignal` 的 `FAILED` 是 `static AtomicBoolean` **JVM 级单值**——任一场景 `report()` 会令**所有**并行 runtime 的响应轮询/派发判 true → 断链毒化其它并行用例（与 P0-7"驱动信道进程级单例"同族）。
- **机制（per-context 键控）**：`FAILED` 改为 `ConcurrentHashMap<Object, AtomicBoolean>`（键 = `BrowserContext` 身份；pw-core 不引用 Playwright 类型，故用 `Object` 身份承载），新增 `report(Object)` / `reported(Object)` / `reset(Object)` / `resetAll()`；`RouteRuntimeImpl` 三处判定（`scheduleResponsePoll` / `recordObservation` / `dispatch`）与收尾清零（`close()`）均改用 `context` 作键 → 只有**上报信号那个 context 所属**的 runtime 受影响，其余并行用例的 MONITOR/CAPTURE 轮询与派发互不牵连。`ScenarioLifecycle` 场景边界清零改为 `resetAll()` 兜底清扫。
- **保留的防御（来自早期部分修复）**：`scheduleResponsePoll` 在信号置位时仍为"跳过本轮、继续按节拍调度、待对应 runtime `close()` 的 `reset(context)` 后自愈"（受 `deadlineNs` 兜底，不会无限空转）—— 与 per-context 键控叠加，P0-2 彻底闭环。
- **已知边界**：`report()` 仍**无生产调用方**（UI 失败上报 feature 未接线），但信号结构已按 per-context 预备好：未来接线时 UI 侧 `report(context)` 即可，无需再改 route 侧。
- **验证**：`pw-route` **352/0 绿**（新增 `RouteFailFastTest.failSignalIsolationAcrossContexts` 锁定"ctxA 上报不影响 ctxB"隔离语义）；`pw-core`+`pw-route` 已装本地仓；`pw-web-ui` 主代码编译通过。

### P0-1 ✅ 根因已除（per-context 信道，见 P0-7）

- **早期症状级缓解**：`GuardedDriverCallImpl.guarded()` 增加 `CancellationException` catch——被其它调用 `shutdownNow()` 取消的 `future.get()` 不再逃逸成 `IllegalStateException`（假失败），而是降级（即使 `FAIL_FAST` 也降级而非抛）。
- **根因（已由 P0-7 根除，2026-10-24）**：原 `DRIVER` 是 `static` 单线程执行器（进程级单例），`guarded()` 不携带连接身份 → 无法 per-context 隔离、一个连接的 `shutdownNow()` 会取消另一连接在途调用（即"跨用例信道毒化"）。P0-7 把 `GuardedDriverCallImpl` 全部信道状态改为**实例字段**，并经 `GuardedDriverCallRegistry.channelFor(BrowserContext)` 为每连接创建专属串行化线程，`PatternBinder` 走 `channelFor(context)` 生产调用 —— 根因彻底消除。
- **验证**：`pw-route` 351→352/0 绿（P0-7 + P0-2 合计）；详见 P0-7。

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

### P0-7 ✅ 驱动信道去"进程级单例"（per-context 实例化，2026-10-24）

- **根因（本轮处理）**：`GuardedDriverCallImpl` 把驱动线程执行器 `DRIVER` 及 `DRIVER_EPOCH` / `POISON_RESETS` / `CHANNEL_FAILURES` / `UNUSABLE` 等全部做成 **`static`**——即 JVM 级进程级单例信道。关键事实（`PlaywrightRuntimeState.playwrightInstances` 按 `threadId:configId` 键控）：**每个 worker 线程拥有独立 Playwright 连接**。于是该单例不仅"静态异味"，更在并行下把不同连接的协议调用不必要地串到一条全局线程，且一个连接的超时 `shutdownNow()` 会取消**另一个连接**的在途调用（即 `CancellationException` 注释里的"跨用例信道毒化"）。
- **机制**：
  1. `GuardedDriverCallImpl` 去除全部 static 信道状态，改为**实例字段**（每条驱动线程执行器 / 代际 / 计数 / 标记均 per-instance）；原静态兼容桥（`channelFailureCount` / `driverEpochForTest` / `poisonResetCount` / `resetChannel` / `resetChannelForTeardown`）保留为转发到"当前线程信道"的静态方法，调用方零改动。
  2. `GuardedDriverCallRegistry` 新增 `channelFor(BrowserContext)`（按连接缓存每实例信道，复用 `DownloadRegistry` 的 `ConcurrentHashMap<BrowserContext,…>` 范式）、`remove(BrowserContext)`（context 关闭时清退、释放 daemon 线程）、`currentChannel()`（ThreadLocal 当前线程信道）、`resetAllForTeardown()`；`instance()` / `setInstance` / `reset()` 兼容保留（注入优先）。
  3. `PatternBinder` 的 `route` / `unroute`（唯一生产调用点）改走 `channelFor(context)`——并行下每个用例（独立连接）拥有专属串行化线程，互不干扰。
  4. `RouteLifecycleImpl.isConnectionUnresponsive(ctx)` 改按 `channelFor((BrowserContext) ctx)` 判定（非 context 入参回落 `currentChannel()`）；`RouteEngine.isDriverChannelUsable()` / `driverChannelFailures()` / `resetDriverChannel()` / `shutdownAll` 全部经 `currentChannel()` / `resetAllForTeardown()` 定位本线程所属连接。
  5. `RouteRuntimeImpl.closeAfterContextClosed()` 调 `GuardedDriverCallRegistry.remove(context)` 对称清退。
- **语义收益**：彻底消除进程级单例；并行场景下每个用例的驱动协议调用**隔离串行**在自家连接信道上，某连接的超时 `shutdownNow()` 只取消**该连接**在途调用，根除"跨用例信道毒化"；性能上不同连接不再被不必要地串到一条全局线程。
- **正确性保证**：串行化粒度仍是"连接"（`route`/`unroute` 按 `BrowserContext` → 同连接串行），未引入连接级协议竞态；每连接一条串行化线程与 Playwright 客户端"单连接外部串行"要求一致。
- **验证**：`pw-route` **351/0 绿**（含 `GuardedDriverCallSingleThreadTest` 32 例串行化断言、`GuardedDriverCallRegistryTest`、`DriverChannelContainmentTest`、`RouteEngineChannelVerdictTest`、`RouteLifecycleChannelProbeTest`），`mvn -o -pl pw-route -am test` BUILD SUCCESS；主代码编译通过。

### 代码冗余 / 职责冗余 审查（本轮逐区扫出）

1. **报告层"句柄→outcomeId 归属解析"职责跨两层重复（P0-3 区域）—— 已 Consolidate（2026-10-24）**：`ReportingBridgeImpl.attachTrace`×2、`attachVideo`×1 原本各自实现 `captureHandle → NOOP 则 activeOutcomeId` 回落，与 `ReportBus` 内部兜底重复。因 bus 的 `attachTrace/attachVideo` 在 P0-3 后已自带该兜底，bridge 改为直接 `bus.attachTrace(bus.captureHandle(), ...)` 透传，归属解析唯一收口在 `ReportBus`。验证：`pw-reports-v2` 254/0 绿。
2. **`attachTraceToOutcome` 与 `attachVideoToOutcome` 主体重复（P0-3 区域）—— 已 Consolidate（2026-10-24）**：两方法"建目录 + move/copy + 设字段"脚手架近乎一致，已抽私有 `mountArtifact(outcomeId, source, relativeTarget, moveWhenOutsideReport, onMounted)`，两方法改为委托。⚠️ **澄清**：二者并非"纯结构重复"——录像恒 `move`（清理 Playwright 临时文件），trace 仅报告**内** `move`、报告**外** `copy`（保留框架侧原件，契约由 `ReportBusTraceTargetTest.foreignTraceIsCopiedOrMoved` 锁定）。差异保留为 `mountArtifact` 的布尔策略参数，未强行统一。验证：`pw-reports-v2` 254/0 绿。
3. **"驱动信道健康"状态散落两处全局静态（P0-1/2 区域）**：`GuardedDriverCallImpl.UNUSABLE`（静态）与 `DriverFailureSignal.FAILED`（静态）都在表达"驱动/信道不健康"，语义重叠又不完全等价，无统一归属。评审也指出二者同源（进程级单例承载 per-scenario 故障语义）。**建议**：随 P0-1/2 的 per-context 下沉一并统一为单一"per-context 信道健康"状态。
4. **`GuardedDriverCallImpl` 内"拆旧执行器 + 建新执行器"逻辑重复（P0-1 区域）**：`handleTimeout` 成功路径与 `resetChannel()` 都做 `shutdownNow()` + 替换实例。可抽 `rebuildExecutor()` 收敛。⚠️ 触及毒化重置核心逻辑，重构需配套测试。

> 冗余处理进度（2026-10-24）：#1、#2 已 Consolidate（`pw-reports-v2` 254/0 绿、已装本地仓）；#4 为纯结构重复但触及毒化重置核心，待配套测试后再动；#3 须随 P0-1/2 架构下沉一并处理。是否继续落实 #3/#4，建议单独确认。
