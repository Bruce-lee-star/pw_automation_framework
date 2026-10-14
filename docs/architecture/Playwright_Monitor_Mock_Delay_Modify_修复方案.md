# Route 层现状盘点与剩余短板修复方案（v2，基于代码实测）

> 基线：
>
> `pw-route`
>
>  模块当前主干（2026-10-11 后）
> 方法：通读 
>
> `RouteRuntimeImpl`
>
>  / 
>
> `RouteDispatcher`
>
>  / 
>
> `MonitorSink`
>
>  / 
>
> `CaptureSink`
>
>  / 
>
> `RouteDelayScheduler`
>
>  真实代码，不依赖外部评审假设。
> 本文档
>
> **不**
>
> 重述已经完成的设计（O1 单泵、轮询 
>
> `existingResponse`
>
> 、mainActions 队列、目的退役不 unroute、I-1 不变式等），只列
>
> **当前代码里仍然存在、需要决策或动手修的点**
>
> 。



***

## 一、当前架构一句话

每个 BrowserContext 一份 `RouteRuntimeImpl`；事件线程（Playwright dispatch）做 claim + pattern/matcher 匹配 + 编排 DELAY→MODIFY→MOCK→放行；响应侧**不订阅** `context.onResponse`，由 `RouteDelayScheduler`（2 线程 daemon）递归轮询 `request.existingResponse()` 本地字段；body 是协议调用，轮询线程只读 `status/headers`（本地字段）并持有 `Response` 句柄，body 读取延迟到业务线程 drain 时执行；MOCK intercept 的 `route.fetch()` 在调用线程就地执行（O1 单泵，禁止派 IO 池）；目的退役只清内存规则表，驱动绑定统一在 `close()` flush。



***

## 二、当前线程模型（实测）



| 线程                               | 职责                                                                                                              | 是否允许 Playwright 协议调用                                              |
| -------------------------------- | --------------------------------------------------------------------------------------------------------------- | ----------------------------------------------------------------- |
| Playwright dispatch（事件线程）        | route handler 入口：claim、matcher、recordObservation、编排链                                                            | 仅 `sendMessageAsync`（resume/fulfill/fallback），**禁止**同步 fetch/body |
| RouteDelayScheduler（2 daemon 线程） | DELAY 到点回调 + 响应轮询（25→100ms 退避）                                                                                  | **零协议**；只调 `existingResponse()`（本地字段）+ 把动作投到 main 队列或直发           |
| 业务主线程（test/click/navigate/drain） | UI 操作、`drainSettledAssertionFailures` / `dumpCapturedApis` / `waitForCapturedApiCall` 里读 body、drain mainActions | ✅ 唯一允许同步 `body()`/`fetch()` 的线程                                   |
| sweeper（per-runtime 1 daemon）    | `claims.sweep()` 超龄兜底                                                                                           | 经 `claims.closeAll()`（sendMessageAsync）                           |
| `GuardedRunner`（原 `BoundedOps`） | 保留，承载 `claims.sweep` / `route.fetch` 的有界预算（调用线程就地执行，不换线程）                                               | ✅ 已落地（见 § 三.4）                                                     |
| `RouteIoExecutor`                  | **已删除（2026-10-14）**：O1 后 IO 池空转（fetch 已就地、DELAY 直接投 `RouteDelayScheduler`），生产零调用方，连同 `ioThreads`/`ioQueueCapacity` 死配置一并移除 | ✅ 已落地（见 § 三.4）                                                     |



***

## 三、剩余短板（按优先级）

### P0-1 DELAY 默认交付仍是 "直发"，main 队列路径默认关闭

**代码现状**：`RouteDispatcher.delayDeliveryMainQueue = false`（默认）。DELAY 到点后回调在 `route-v2-delay-N` 线程直接 `afterDelay.run()` → 最终 `route.resume()` / `fulfillWithBody()`（`sendMessageAsync`）。代码注释自己承认："Connection 写入无锁 ⇒ 直发存在理论并发窗口，但 E2E 三连绿实证低概率"。

**问题**：



* `sendMessageAsync` 内部仍碰 `++lastId` / `callbacks.put`（HashMap）/ `transport.send`，与业务主线程的下一次 UI 调用并发写。

* 低概率 ≠ 零；run5 已经爆过一次级联污染。

* main 队列路径（`setDelayDeliveryMainQueue(true)`）已经写好，但要求业务侧把 `page.waitForFunction` 换成 `RouteDsl.waitForUiFunction`，否则 main 阻塞在等待里无人 drain → 15s 超时（E2E run-b 实证）。

**建议**：



1. 决策：是否把 `delayDeliveryMainQueue` 默认翻转为 `true`？翻转后必须同步把所有业务等待入口切到 `waitForUiFunction`（或封装一个 `waitForMainThreadDrain` 工具）。

2. 如果不翻转，至少在 DELAY 到点回调里加一次 "事件线程序号" 检查：若 main 此刻正在执行 UI 动作（可通过 `Thread.holdsLock` / 一个 main-in-flight AtomicBoolean 判断），则降级入队；否则直发。

3. 加指标：`directedDeliveries` vs `queuedDeliveries` 计数，每次 E2E 后输出，量化 "直发" 占比。



***

### P0-2 3xx redirect body 没有短路，靠异常兜底

**代码现状**：`MonitorSink.settleBodyAssertionNow` → `takeBodySnapshot` → `response.body()`。3xx response 调 body 会抛 `PlaywrightException: Response body is unavailable for redirect responses`，被 catch 后记为 `inconclusive`。CaptureSink 同路径。

**问题**：



* 异常路径有 CDP 往返成本（虽然最终是服务端拒绝）。

* 日志里 `inconclusiveBodyReads` 计数会把 "3xx 本来就没 body" 和 "句柄被回收" 混在一起，无法区分。

**建议**：



```
// takeBodySnapshot / settleBodyAssertionNow 入口加一道短路
int status = response.status();
if (status >= 300 && status < 400) {
    return new BodySnapshot(null, names, contentType,
        "redirect response (status=" + status + "): body not available", null, true);
}
```



* BodyResult 级别补一个 `REDIRECT_NOT_AVAILABLE`，与 `CONTEXT_CLOSED` / `HANDLE_RECLAIMED` 分开计数。

* 这是纯加分支，不改线程模型。



***

### P0-3 CaptureSink / MonitorSink 对 `navigate away` 后的 body 读取仍有竞态

**代码现状**：`onResponseForSpec` 里 `exchange.holdResponse(response)`，业务线程在 `settlePendingBodyAssertions` / `drainSettledFailures` / `settleFailure` 里 `takeHeldResponse()` 再调 `body()`。如果业务线程在两次 drain 之间用户点了导航，句柄被回收，`body()` 抛 `No resource with given identifier` → inconclusive。

**这是已经识别但未根除的点**：inconclusive 计数已经存在，不判失败。但用户场景（shot4 里 `locale/update` 那个 pattern）确实踩过。

**建议**：



1. 在 `holdResponse` 之后，业务线程 take 之前，先做一次 `response.status()` 轻探（本地字段，不发协议）；如果抛异常就直接标 `CONTEXT_CLOSED`，不进 `body()`。

2. 把 `inconclusiveBodyReads` 再拆两个子计数：`handleReclaimedAfterNavigate` vs `bodyReadProtocolError`，便于在监控报告里区分 "框架竞态" 和 "真网络错误"。

3. 不需要改架构，只是诊断精度提升。



***

### P1-4 RouteIoExecutor / BoundedOps 实际承担什么？

**代码现状（2026-09 核查时）**：`RouteRuntimeImpl` 构造时 `new RouteIoExecutor(tag, config.ioThreads(), config.ioQueueCapacity())`，metrics 里输出 `ioPool=[core/peak/grown/shrunk/rejected/active]`。但 O1 重构后，MOCK intercept 的 fetch 已经在调用线程就地 `mockTask.run()`，DELAY 任务投的是 `runtime.io().trySubmit(...)`，进去后又只做 `RouteDelayScheduler.delay(...)`（O (1)），真正的 resume 发生在 scheduler 回调里。

**问题**：IO 池现在主要承载 "包一层再丢给 scheduler" 的中转，这层中转的价值存疑。线程数、队列容量、指标都是为 "真并发 fetch" 时代留的，现在空转。

**结论（2026-10-14 已落地）**：`RouteIoExecutor` 已整体删除 —— 生产代码零 `trySubmit` 调用方，仅测试残留（模拟 IO 池饱和的 `io().close()` / `io().trySubmit`）随删除一并移除；`ioThreads` / `ioQueueCapacity` 配置项、`RouteMetrics.ioRejected` 字段同步删除。`DELAY` 现已直接投 `RouteDelayScheduler.delay()`（不经过 io 池）。`BoundedOps` 已重命名为 `GuardedRunner`（见 P1-6），继续承载 `claims.sweep` / `route.fetch` 的有界预算，且在调用线程就地执行（不换线程）。

* 这是清理项，不是 bug；不影响正确性。



***

### P1-5 HangWatchdog 是临时 diag，需移除

**代码现状**：`RouteRuntimeImpl` 构造时 `new HangWatchdog("ctx@" + ...).arm()`，close 时 disarm。注释明确写 "临时诊断看门狗（与 web HangWatchdog 同源，待去除）"。

**建议**：跟 web 侧一起移除，或改为 opt-in（系统属性 `-Droute.hangwatchdog=true` 才 arm）。生产环境不应常驻一个 daemon 做堆栈 dump。



***

### P1-6 BoundedOps 命名与语义（已落地：`GuardedRunner`）

**代码现状**：原 `BoundedOps.tryRun(name, callable)` 是 "获取额度 + 执行"；历史上还有 `tryRunVoid`。命名 "Ops" 暗示并发限制，但实际语义是 "带 PendingGuard 额度的协议调用预算"。

**结论（2026-10-14 已落地）**：已按建议重命名为 `GuardedRunner`（方法保留预算获取 + 执行语义，在调用线程就地执行、不换线程）。这是可读性项，不改行为。



***

### P1-7 日志瘦身（残留 driver stack）

**代码现状**：大部分 catch 已经用 `t.toString()` 或 `e.getMessage()`，不再打完整 stack。但 `LOGGER.warn("[Route] dispatch failed ...", ..., t.toString())` 这种写法在 `t.toString()` 是 `PlaywrightException: coreBundle.js:12345 ...` 时仍会把整段 JS stack 带出来。

**建议**：



* 统一一个 `DriverErrors.shortMessage(Throwable t)`：提取 `PlaywrightException` 的第一行 + `cause` 类名，截 240 字符。

* DEBUG 级别才打完整 stack。

* 在 `RouteDispatcher.dispatch` 的 catch、`MonitorSink.settleBodyAssertionNow` 的 catch、`CaptureSink` 的对应路径统一换用。



***

### P2-8 3xx/404/503 的断言语义

**代码现状**：`MonitorSink` 对 404/503 没有特殊处理，只要 `existingResponse()` 返回非 null 就进 status 断言。这是对的（404 也算完成）。但用户配置 `expectStatus=302` 时，如果服务端实际返回 200，会按 status mismatch 报失败 —— 这是预期行为，不是 bug。

**建议**：在 `ApiCallResult` 里把 `redirectChain` 显式化（目前 redirect 链靠 `request.redirectedFrom()` 本地字段，没有进 CapturedExchange），便于业务断言 "这个请求最终经过了 N 跳 redirect"。这是增强项。



***

### P2-9 线程命名历史包袱

**代码现状**：sweeper 线程名是 `route-v2-sweep-N`，delay scheduler 是 `route-v2-delay-N`。`pw-route-v2` 目录已合并回 `pw-route`，命名里的 v2 是历史遗留。

**建议**：改成 `route-sweep-N` / `route-delay-N`。ArchUnit 守卫测试如果断言了线程名前缀要同步改。纯清理。



***

## 四、不建议做的事（对照外部评审里被代码否决的提案）



| 外部评审建议                                        | 当前代码结论                                                                                                     |
| --------------------------------------------- | ---------------------------------------------------------------------------------------------------------- |
| 引入 Network Worker Pool 承担所有 RPC               | **否决**。Connection 非线程安全，派 IO 池就是 run5 爆过的级联污染。fetch 已就地。                                                   |
| 重建 `ApiMonitorSession` per-scenario           | **否决**。生命周期已经是 `RouteRuntimeImpl` per-context + `register()` 返回 AutoCloseable + `close()` flush。再包一层是重复抽象。 |
| 在 `onRequestFinished` 里读 body                 | **否决**。根本没订阅 onRequestFinished；改用 `existingResponse()` 轮询。                                                 |
| "Resume 收敛到 owning thread"                    | **部分采纳**（mainActions 队列），但默认关闭，见 § 三.1。                                                                    |
| 用 Request channel GUID 做配对 key                | **不需要**。配对键是 `ApiSpec` 实例（dispatch 时传入），比 GUID 更直接；redirect 链由 `existingResponse()` 本地持有，不跨时长存。            |
| 订阅 `context.onResponse`                       | **明确否决**。该订阅是 `Object doesn't exist: response@` 竞态主因。                                                      |
| "route () 返回 Disposable 用 try-with-resources" | 现状是 `register()` 返回 `AutoCloseable` lambda，做了令牌化摘除；等价。                                                     |



***

## 五、落地优先级



| 阶段  | 项                                                         | 改动量    |
| --- | --------------------------------------------------------- | ------ |
| 本周  | P0-1 DELAY 交付模式决策 + 指标；P0-2 3xx 短路；P0-3 inconclusive 拆子计数 | 小，纯加分支 |
| 下迭代 | P1-4 已删除 `RouteIoExecutor`（2026-10-14）；P1-5 HangWatchdog opt-in；P1-7 日志瘦身           | 中，清理   |
| 按需  | P1-6 重命名；P2-8 redirectChain；P2-9 线程名                      | 小，重构   |



***

## 六、验证口径

每个 P0 改动落地后跑：



1. `pw-route` 现有单测（164+ 用例）全绿。

2. testbed E2E（feature + glue）三连跑：`delay` / `intercept` / `monitor` 三个 scenario 各跑一次。

3. thread dump 检查：`route-v2-delay-N` 线程上**没有** `response.body()` / `route.fetch()` 帧；main 线程上**没有**长时间停在 `sendMessageAsync`（DELAY 直发模式下）。

4. 监控报告里 `inconclusiveBodyReads` 拆子计数后，3xx 场景下 `redirectNotAvailable` 计数正确增长。