# Playwright 导航对全量 API 监控的影响与生命周期设计

## 摘要

结论是：把“页面当前收集的所有请求”当作一个可轮询的普通集合，再凭 `Request.existingResponse()` 判断是否完成，在导航边界必然不稳定。`existingResponse()` 只在响应已经到达时立即返回 `Response`，否则返回 `null`，且 `Request.response()` 在错误导致无响应时也可返回 `null`；它解决的是非阻塞读取，而不是请求完成、body 已落盘、归属已确定或导航已结束的问题[1][2]。`onRequest/onResponse/onRequestFinished/onRequestFailed` 的序列、Page 归属、route 作用域与 popup/Service Worker 的可观测性必须同时建模：Page route 优先于 context route，但两者都不能拦截被 Service Worker 拦截的请求；官方因此建议拦截需求禁用 Service Worker[3]。下载事件在下载开始即发出，而 path 直到完成才可用；popup 初始请求早于可拿到 Page 句柄的时刻，所以只能由 context 级 route 覆盖[4][6]。`APIRequestContext` 与浏览器是两条不同调用路径：context/page 的 `request()` 共享 cookie jar，却不会再次进入发起 Page 的 `page.route()`；`route.fetch()` 则仍属于 route 链并会进入匹配 handler[5][8]。本方案应采用 owner、epoch、request/redirect/response/finish 五个状态、只读快照版本号和显式 awaiter，不使用裸 `Map`、请求 URL 计数器或 `Thread.sleep`。以下版本边界默认面向 Playwright Java 1.59+；`Request.existingResponse()` 自 1.59 起提供[1]。

## 1. 全量监控的对象不是响应集合，而是有版本的请求状态机

**`existingResponse()` 只能回答“此刻是否有响应”，不能回答请求是否已结束、body 是否完整、快照是否过期。** `Request.existingResponse()` 的官方定义是：若响应已收到立即返回 `Response`，否则返回 `null`；它不等候响应[1]。这与 `Request.response()` 的关键差异是同步/阻塞语义，而不是响应完整性。成功请求的常规事件序列为 `request → response → requestfinished`，而 `requestfinished` 表示 body 下载完成后成功结束[4]。因此“存在 response”与“可用于全量断言”之间存在明显缺口：header 和 status 可见时 body 未必完成；重定向中间响应可见时，终态响应可能是另一个 `Request`；导航主资源失败时可能永远没有 `Response`。

设计上，每个请求必须独立建模，不能把 `Request`/`Response` 对象本身当作可长期随意持有的可变值。推荐状态为：`CREATED`（已见 `onRequest`）→ `RESPONSE_HEADERS`（已见 `onResponse`）→ `FINISHED` 或 `FAILED`；导航请求增加 `NAVIGATION_IN_FLIGHT`；待 route 处理的请求增加 `ROUTED`/`HANDLER_RUNNING`。重定向链不改变终态判断：服务器返回 3xx 后浏览器会创建新 `Request`，二者由 `redirectedFrom()`/`redirectedTo()` 连接；一条链可能由多个独立请求对象组成[2]。

```text
onRequest
  │  ownerPage/pageId、frameId、navigationEpoch、requestId
  ▼
CREATED ──────── route handler ──────▶ ROUTED/HANDLER_RUNNING
  │                                            │
  │ onResponse                                 │ abort/fulfill/resume/fetch
  ▼                                            ▼
RESPONSE_HEADERS ── redirectedTo≠null ──▶ CREATED（下一跳，chainId 相同）
  │                                            │
  ├─ onRequestFinished ──▶ FINISHED（body 已完成）
  └─ onRequestFailed   ──▶ FAILED（failure 原因）

导航状态：
IDLE ──▶ NAVIGATION_STARTED ──▶ DOCUMENT_COMMITTING ──▶ LOAD_SETTLING
   ▲                                                     │
   └────────── ROLLBACK/FAILED/ABORTED ◀─────────────────┘
每个 Page 同时只允许一个主导航 epoch；同 Page 的新 epoch 会作废旧 epoch 的“完成集合”。
```

状态迁移必须由 Page 的事件派发线程或单个锁串行化。Playwright Java 文档说明，`Page.waitForCondition()` 等待期间仍会派发所有 Playwright 事件[7]，所以监听器可以在轮询检查之间修改同一 store；若检查读取多个字段，必须读取某个不可变版本号对应的快照，而不是在多次方法调用间保持对象引用。

**`Request`/`Response` 是只读观测对象，不是长期数据契约。** 应尽早把稳定元数据复制到不可变值对象：规范化 URL、method、resourceType、requestId/redirectedRequestId、ownerPageId、frameId、navigationEpoch、redirectionChainId、请求时间、状态枚举、status、精简 headers、body sha-256、body size、source（`BROWSER`/`ROUTE_FETCH`/`CONTEXT_API`）、`fromServiceWorker`。`Request.frame()` 可能抛异常，尤其当请求来自 Service Worker 或导航请求先于 frame 创建；应先检查 `request.serviceWorker()` 和 `request.isNavigationRequest()`[2]。失败文本、body、`Response.frame()` 在错误分支上也可能抛或返回空，所以复制过程必须捕获并标记 `readError`，不能让单个请求污染整个轮询条件。

## 2. 路由层级决定“看见什么”，导航拓扑决定“归到哪一页”

**Page route 适合测试意图，context route 才是跨 Page、popup、初始请求和导航竞态的安全层。** `Page.route(url, Consumer<Route>)` 的 handler 在其匹配时拦截 Page 请求；`BrowserContext.route(url, Consumer<Route>)` 拦截该 context 中任意 Page 的请求，且 Page route 优先[3]。这意味着若测试只关心“当前被测页的某个接口”，Page route 表达力强；若目标是全量监控，Page route 会在 popup、更早创建的 page、尚未绑定 handler 的新 page，以及不同 Page 间路由移除不一致时出现盲窗。

多 route 执行顺序不是“先注册先执行”：官方 Java 文档说明，多个匹配 route 按注册顺序的逆序运行，最后注册者可以先决定结果；`route.resume()` 会立即发往网络且不再调用其他匹配 handler，而 `route.fallback()` 会把控制权交给链中更早的 handler[5]。旧 Playwright 语言绑定曾用 `Route.continue()`，Java 当前公开契约应写 `route.resume()`/`route.fallback()`；不应在 Java 代码里臆造 `route.continue_()`、`route.continue()`、`Route.ContinueOptions` 等名称。

| 层级 | Java 注册形态 | 可见请求 | 适用职责 | 不可承担的职责 |
|---|---|---|---|---|
| Context | `context.route(url, handler)` | 任意 context 内 Page 的匹配请求；SW 已拦截者除外 | 全量采集、全局断言、popup/新页兜底 | 把 SW 私有 fetch 视作完整覆盖 |
| Page | `page.route(url, handler)` | 当前 Page 匹配请求，优先于 context | 单页用例、局部 mock | 初始 popup、跨页生命周期的一致性 |
| Route handler | `route.fetch(...)` | 当前被 route 请求及其所发起重定向 | 真实网络采集后修改 fulfill | 等同于普通后台 fetch |
| Background | `context.request().fetch(...)` | 不经过浏览器 route/SW，独立 HTTP 客户端 | 后台重取、环境准备 | 复用 page referer、SW、page route 策略 |

`BrowserContext.route()` 启用后会禁用 HTTP cache[3]。这不是“清空缓存”，而是拦截架构关闭了普通 cache 路径；由此产生的响应时序可能与生产浏览器不同。全量监控若同时需要测量缓存命中，必须单独用浏览器 DevTools/协议或 HAR 实验，不能把 route 模式的观察结果当作原生缓存证据。

**popup 的初始请求早于 Page 句柄，必须用 context 事件而不是先 `page.waitForPopup()` 再加 route。** 官方 Java Page 文档给出 `page.waitForPopup(Runnable)`：它先执行动作，再等待 popup Page，并在 Page 关闭前未触发 popup 事件时抛错[7]。这一时序意味着“点击后拿到 popup，再注册 handler”会漏掉 `window.open(url)` 的初始导航请求。正确顺序是在打开 popup 之前登记 `context.onPage(...)` 与 context route；context 的 `page` 事件在新 Page 创建时触发，且可能仍处于加载中[6]。

新 Page 从 context 继承路由和模拟设置[6]，但“继承”是创建时生效的 context 配置，不是把已发生的请求重新追溯。测试还需保存“打开者、打开动作、初始 navigation epoch、所有后续 Page”的关联，否则多个 popup 并发时，`/api/init` 这类通用 URL 无法可靠归属。

## 3. 生命周期随导航类型变化，不能用一个“页面稳定”条件统一封装

**整页导航、表单提交、链接提交和 `window.location` 的共同点是主文档请求驱动 Frame 导航，但相同 URL 不能标识一次导航。** `Request.isNavigationRequest()` 表示请求是否驱动 Frame 导航；某些导航请求在对应 Frame 创建前发出，因此没有可用 `frame()`[2]。点击链接、提交 GET/POST 表单或执行同步 `location.href` 都可能触发主文档请求；执行 `form.submit()`、触发 beforeunload 拦截、调用 `event.preventDefault()`、服务端返回 204/下载、DNS/TLS 失败，都可能使最终页面 URL、提交动作和主资源响应不一致。

Page 事件应记录每个 Frame 的 `FRAME_NAVIGATED`、文档提交、load 与 `page.onClose()`；每条请求记录 `(pageId, frameId, navigationEpoch, redirectChainId)`。这样 `submitForm()` 之前发生的 `/api/before-submit`、导航期间发生的 `/api/session` 和提交本身导致的 POST 能被区分。官方 Java 文档明确列出 `navigate()` 会在 SSL 错误、无效 URL、导航超时、服务器无响应或主资源加载失败时抛错；这不等价于“页面内所有 API 都失败”[7]。

**History API 路由没有浏览器主文档导航，是最容易把“URL 改变”误判为“页面改变”的场景。** `pushState`/`replaceState` 加 SPA render 时，通常没有新的主资源导航；监听器仍属于原 Page，Page route 和 context route 仍按当前 Page 生效。仅当 SPA 随后真正调用 `location.assign/href`、`<a>` 跳转或框架导航时，主 Frame 才再次导航。因此 SPA 的稳定 key 必须是“路由状态 + 应用定义的事务 ID + 请求终态版本”，不能只轮询 `page.url()`；`page.waitForURL()` 只等待主 Frame 导航到匹配 URL[7]。

**iframe 导航可复用或分离 Frame 对象，不能长期依赖 `Frame` 引用。** 当前官方资料能确认 Frame 的导航、detach、page 归属与请求 frame 的语义；它不能证明某一浏览器版本下 Frame 对象的稳定标识规则。实现应以 Playwright 当前 Frame 对象为当期 id、结合 `frame.page()`、parent frame 和导航链保存，不假设跨 detach/attach 复用。若 SPA 用 `<iframe>` 承载支付、验证码或文件预览，导航可能改变 frame 树，使之前记录的 `frameId` 在严格 equal 匹配下“消失”。

**下载不是普通 API 响应，更不能等到 `Response` body 完成再判定。** `Download` 事件在下载开始发出；`Download.page()` 返回所属 Page，`Download.url()` 返回触发 URL，`Download.failure()` 在需要时等待下载结束并报告错误，`Download.path()` 在成功下载完成前会抛，`Download.saveAs()` 在下载进行中调用会等待其完成[4]。因此监控 store 应建立 `downloadId → triggerRequestId → ownerPage → epoch`，下载开始即记入 IN_PROGRESS，failure 与 path/saveAs 完成才记入终态。Playwright 的超时语义取决于等待 API：下载等待本身会在 Page 关闭前未触发时抛，不能把这一超时解释成后台下载已经取消[7]。

**Service Worker 是全量监控的天花板，而不是一个可通过更多 route 修补的小盲点。** 官方说明 `BrowserContext.route()` 不会拦截已被 Service Worker 拦截的请求；这是已知限制，推荐做法是把 `serviceWorkers` 设为 `BLOCK`[3]。进一步，只有 Service Worker 拥有的资源请求可被 `browserContext.route()` 路由；由 SW fetch handler 完全在 worker 内处理的请求可能不触及外部网络，因而不可路由。Playwright 中文文档还提示：在有非空的 `request.serviceWorker()` 的 Request/Response 上调用 `frame()` 会抛[9]。因此，若产品依赖 SW，监控目标必须改为“可验证的页面可见结果 + SW build/版本 + 明确的录制/回放环境”，而不是声称捕获所有网络；本报告的完整模型不适用于 SW 保持生产模式时。

## 4. 重定向、失败与连续导航会使“最新一版快照”过期

**每次服务器重定向都会产生新的 Request，断言必须沿 `redirectedFrom`/`redirectedTo` 遍历。** `Request.redirectedTo()` 返回服务器重定向后浏览器发出的新 Request，无重定向时为 `null`[2]。在 route 层，匹配 handler 对每一跳都按 handler 优先级和匹配规则执行；不能用“最终 URL 命中一次”来代表整条链。中间 hop 可能是 HTTP 301/302，最终 hop 可能是 200，只有链尾或每个 hop 的策略显式定义了“哪个响应是用户关注对象”。

后台 `APIRequestContext.fetch()` 的重定向语义也不同：默认 `setMaxRedirects(20)`；超过限制会抛错；`setMaxRedirects(0)` 不跟随[10]。这里的“重定向”属于后台 HTTP 客户端，`Request.redirectedFrom()` 是浏览器请求链，两者不可混用。后台调用获得的是一个 `APIResponse`，不会自然生成前台 `Request`/`Response` 事件；必须明确标记为 `BACKGROUND_FETCH`。

**连续导航的失效模式是“旧请求迟到、新请求抢先、同一 URL 重复”，而不是请求丢失。** 若 Page A 触发 `/search?q=1`，紧接着 Page A 又触发 `/search?q=2`，两个请求可能并发；`/search?q=1` 可能在第二轮 `waitForCondition` 检查后才完成。若条件只看“存在两条 `/search` 成功响应”，就会在第一次检查时把旧结果误认为新事务已满足。正确 key 应是应用层 id、method+规范化 URL+body digest、requestId 与 epoch；当存在多个候选时，应等待 navigation epoch 进入允许终态且所有已见请求都达到 FINISHED/FAILED，而不是看到“至少一条”就返回。

导航取消或失败还可能没有 `Response`。`Request.failure()` 只在 `requestfailed` 报告失败时返回非 `null`，成功请求为 `null`[2]；错误包括超时、连接重置、DNS、TLS、客户端阻断、响应策略阻断等。`page.onRequestFailed()` 的官方 Java 文档明确给出超时等失败样例[7]。404/503 是 HTTP 成功响应，仍走 `requestfinished`，不能据此错误地把它们混入网络失败[6]。

**监听器不会被导航自动“清空”，但新导航会稀释其语义。** 官方没有承诺“每次整页导航后 `page.onX` 自动移除”；它们是对象级订阅。真正危险的是测试代码在导航前登记、导航后忘记取消，导致重复调用和重复计数。相反，若测试误把 `page.removeAllListeners()` 当成版本安全 API，也可能把业务框架的监听器一起移除；应保存返回的 `Disposable` 或具名 handler，调用 `dispose()`/`unroute(handler)`，必要时 `unrouteAll()`。

![popup 与 Service Worker 漏捕风险最高](https://one-agent-prod-1343551737.cos.ap-guangzhou.myqcloud.com/artifacts/0125/4c14ac651e3b4e8992d48adf574038cc/0QL5JwAUuMU/task-51db7ee0a91a1aca107b95e00579fd4b/.rendered/_assets/dr%3Aplaywright-nav-lifecycle/fig_risk.png?q-sign-algorithm=sha1&q-ak=AKIDDMTk0KZdUSL21fBYigcl3C8rMeiT5TdZ&q-sign-time=1791989853%3B1799765853&q-key-time=1791989853%3B1799765853&q-header-list=host&q-url-param-list=&q-signature=b29afdac00cd12dca321960d4861a8d2aad2b206)
*图 1：不同导航拓扑的相对风险；分值综合官方可见性边界、首请求时序和结果归属难度。数据来源：Playwright Java 官方 API 与 Network 文档 [1][3][4][6][8]*

## 5. 后台重取与 page route 是两条不同调用路径

**context/page 的 `request()` 与浏览器共享 cookie jar，但不共享浏览器的 route、Service Worker 和页面脚本环境。** 官方说明 `BrowserContext.request()` 与 `Page.request()` 返回同一关联 context 的实例，且由 context 创建的 `APIRequestContext` 发出的请求使用 context cookie：请求自动带出匹配 cookie，响应 `Set-Cookie` 会回写 context，从而影响后续 page 导航与 API 调用[8]。这是监控后台重取时的强契约。

`route.fetch()` 则是另一回事：它是 Playwright 的 route API，执行被路由的请求并返回 `APIResponse`；由于它处在 route 链中，匹配 handler 可以再次观察到该 fetch 及其重定向。官方“Modify responses”示例使用 `route.fetch()` 再 `route.fulfill(response, ...)`，正说明这不是普通脱离 route 的后台调用[8]。`Route.fetch()` 的 `FetchOptions` 提供 `setHeaders`、`setMaxRedirects`、`setMaxRetries`、`setMethod`、`setPostData`、`setTimeout`、`setUrl`[5]。

| 维度 | `route.fetch()` | `page.context().request().fetch(...)` |
|---|---|---|
| Route 重入 | 会；属于当前 route 链 | 不会进入发起 Page 的 `page.route()` |
| Service Worker | 仍受浏览器 SW 架构影响 | 不经过浏览器 SW |
| Cookie | 浏览器 context jar | 共享 context cookie jar[8] |
| 默认 max redirects | 由 `FetchOptions` 控制 | `RequestOptions` 默认 20[10] |
| 重试 | 可配置 | `setMaxRetries` 当前仅重试 `ECONNRESET`，非 HTTP 状态码[10] |
| 结果对象 | `APIResponse` | `APIResponse` |
| 推荐用途 | 采集真实响应后选择性 fulfill/改写 | 后台验证、环境准备、独立重放 |

**鉴权、referer 和 CSRF 不能自动假设被后台请求复制。** 官方直接证实的是 cookie jar 共享、请求带出匹配 cookie、响应 `Set-Cookie` 回写 context[8]；`RequestOptions.setHeader(name, value)` 的说明称 header 会作用于所取请求及其重定向[10]。官方没有在通用 Java 文档中承诺 `page.context().request().fetch()` 会自动设置与当前 Page 请求相同的 `Referer`、origin、CSRF token、浏览器指纹、TLS 会话、HSTS、Signed Exchange 或扩展注入 header。把它们当成“天然一致”是设计假设。后台重取应显式传递由前台捕获并经策略白名单裁剪的 header；若 CSRF token 绑定浏览器 cookie 与 DOM 中的 nonce，应先通过受控脚本读取，而不能无界复制全部 `Cookie`/`Authorization`/`X-*` header。

**幂等性是调用者的责任，不是 `maxRetries` 的保证。** `RequestOptions.setMaxRetries()` 当前只重试 `ECONNRESET`，不会因 409/429/500/503 自动重试[10]。但“网络层只重试这一种错误”不意味着业务安全：`POST /orders` 可能因超时在服务器已提交后重试失败网络，也可能在服务器未收到时安全重发。后台监控重取应默认只允许显式声明的 `GET`/幂等 method；写操作必须设置 `readOnlyReplay=true/false`、业务 idempotency-key 和人工审批。若后台任务已经改变订单状态，前台快照“没有重复”也毫无意义。

**后台任务再次进入 route 的边界是显式的。** `route.fetch()` 会被 route 链观察；若 handler 无条件再调用 `route.fetch()`，会形成无限递归或至少循环。必须用 `routingDepth`、`backgroundReplay=true` 或 header/属性标记打破循环。相反，`APIRequestContext.fetch()` 不经过 page route；若产品代码经 page route 添加了鉴权、加解密或限流头，后台重取可能绕过它们。不要让 handler 仅凭“请求 URL 相同”自动重放，必须同时具备前台终态、业务语义、写权限和 owner 存活策略。

## 6. `waitForCondition` 的正确用法是版本匹配，而不是反复扫描可变 store

**`page.waitForCondition(BooleanSupplier, WaitForConditionOptions)` 只保证轮询与事件派发并发，不保证快照原子性或跨检查字段一致。** 官方说明该方法会阻塞直到 supplier 返回 `true`，等待期间所有 Playwright 事件仍会派发；默认超时为 30000 毫秒，可通过 `BrowserContext.setDefaultTimeout()` 或 `Page.setDefaultTimeout()` 改变，传 0 禁用超时[7]。Java supplier 的多次调用发生在 Playwright 事件派发交错环境中，典型风险如下：

1. **误判**：条件先读请求数量 2，再读响应数量 2；两次读取之间新增请求，最终断言仍通过，但候选集合已改变。
2. **竞态**：`existingResponse()` 返回非 `null`，但 `requestfinished` 尚未到达；测试若立即读取 body 可能得到不完整结果或不同 API 的 body 读取异常。
3. **过期命中**：第二轮导航的请求在等待期间完成，满足“曾出现成功响应”，却不属于本轮提交。
4. **重复请求**：同一业务事务产生重试或重复埋点，按 URL 计数得到 2，而测试只期待 1。
5. **新标签页结果归属**：popup 请求完成并使全局 store 条件成立，但原始 Page 的断言失败。
6. **超时语义**：`waitForCondition` 抛超时表示 supplier 在超时前未返回 `true`，不代表所有请求已失败；它也不能等待 popup/下载结束后才开始。

正确接口是返回不可变 `ApiSnapshot`：

```java
public final class ApiSnapshot {
    private final long version;
    private final String ownerPageId;
    private final long navigationEpoch;
    private final ImmutableList<RequestRecord> created;
    private final ImmutableList<ResponseHead> responseHeaders;
    private final ImmutableList<FinishedRecord> finished;
    private final ImmutableList<FailedRecord> failed;
    private final ImmutableList<RedirectEdge> redirects;

    public ApiSnapshot withVersion(long version) { ... }
    public MatchResult match(Query query) { ... }
}
```

`ApiStore` 每次状态迁移只生成一个新 `ApiSnapshot` 并替换 `AtomicReference`；`waitForCondition` 的 supplier 调用 `store.current().match(query)`。不要返回 `List` 的实时视图，也不要在 supplier 内逐个调用 `existingResponse()` 后跨调用累计。轮询周期不是官方公开保证的稳定契约，代码不应依赖特定间隔；需要固定节奏时，应自己实现带 `Instant` 截止时间和退避的循环，但仍须在每次迭代读取 store 的新版本。

**稳定 key 必须覆盖“谁在哪一版本导航中发出哪个逻辑请求”。** 推荐候选键按优先级为：应用业务 idempotency key/transaction id > `(ownerPageId, navigationEpoch, redirectChainId, requestId)` > 规范化 `(method, url, canonicalQueryOrder, bodyDigest)` > `(frameId, resourceType, url)` > `url`。`requestId` 只能在同一 Playwright 会话内稳定，不可跨重试或跨 context 重放；`url` 永远不能单独作为 key，因为分页、轮询、CSRF nonce、埋点和连续导航会造成碰撞。若应用不提供业务 id，测试基础设施应注入 trace/transaction header 并校验其传递，而不是反向从 URL 反推业务语义。

```java
public boolean test(Supplier<ApiSnapshot> snapshot,
                    Predicate<ApiSnapshot> predicate,
                    long timeoutMillis) {
    long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
    long lastVersion = -1;
    for (;;) {
        ApiSnapshot s = snapshot.get();
        if (s.version() != lastVersion) {
            lastVersion = s.version();
            if (predicate.test(s)) {
                return true;
            }
        }
        if (System.nanoTime() >= deadline) {
            throw new AssertionError("API condition not satisfied within " + timeoutMillis + " ms");
        }
        Thread.onSpinWait();
    }
}
```

上例只是状态轮询骨架，不是官方 API；它比“在 supplier 中读可变集合”安全，但仍需替换成实际调度器、中断策略和日志采样。`Page.waitForCondition()` 更适合委托给 `store.current()::matches`，让 Playwright 管理事件派发和超时；不要在 supplier 内同步阻塞等待别的后台任务。

## 7. 前台、后台与聚合快照应形成三层版本模型

**前台快照记录浏览器实际执行，后台快照记录测试基础设施的二次调用，聚合快照只合并同一次逻辑事务。** `Source.BROWSER` 快照的每条记录应有 `eventIndex`、`requestStartedNanos`、`responseHeadersNanos`、`finishedNanos`、`failedNanos`。`Source.ROUTE_FETCH` 与 `Source.CONTEXT_API` 快照使用独立 `backendAttemptId`，记录调用方法、参数摘要、实际请求 header（脱敏后）、重定向链、状态码、响应摘要和 body hash。后台 body 应从 `APIResponse.body()` 读取；前台 response body 从 `Response.body()`/`text()` 读取，且只能在 `requestfinished` 或 `Response.finished()` 之后安全假定完整。

聚合不是把两个列表合并排序。关系必须是 `backendAttempt.backendCauseRequestId = browserRequest.id` 或 `backendAttempt.transactionId = browserTransaction.id`。后台调用若没有前台 cause，标记为 `ORPHAN_REPLAY`，默认禁止写操作；浏览器请求若没有后台重取，并不自动异常。一个前台请求可触发多个后台 attempt，但只有满足幂等策略和唯一 attempt id 的 attempt 才进入“正式重放集合”。

| 字段类别 | 前台快照 | 后台快照 | 是否可跨系统比较 |
|---|---|---|---|
| 身份 | requestId、redirectChainId | attemptId、causeRequestId | 仅 cause 关系可比较 |
| 归属 | pageId、frameId、navigationEpoch | ownerPageId、intent | 必须显式映射 |
| URL/Method | 浏览器实际值 | 实际请求值 | 可语义比较，不代表一致 |
| Body | 完整 body hash | 完整 body hash | hash 可比较 |
| Cookie/鉴权 | 浏览器 cookie/headers | context cookie + 显式 header | 不能假定一致 |
| 重定向 | `Request.redirectedFrom/To` | `APIResponse.redirectChain` 或客户端链 | 仅按 hop 语义比较 |
| 终态 | FINISHED/FAILED | SUCCESS/THROW/MAX_REDIRECTS | 需映射错误语义 |
| Body 可用性 | 晚于 headers，建议等 finished | 调用返回后立即可读取 | 时序不同 |

**不可变快照必须采用防御性复制与字段裁剪。** 请求/响应 header 可能包含 `Authorization`、会话 cookie、`Set-Cookie`、CSRF token、内部追踪信息；直接序列化会造成测试日志泄露。`Request.allHeaders()` 返回包含所有相关 header 的 map，而 `Request.headers()` 不包含安全相关 header[2]。复制时建立 header allowlist，记录 header 数量、字节长度与 hash；对敏感值保存掩码摘要而非原文。body 保存 `byte[]` 前先限制大小，大文件只保留 hash、截断预览和来源路径。这样既满足“不可变”，也避免一个大型 HAR 让轮询条件持续复制内存。

**前台快照丢失可能是业务失败，也可能是架构边界。** 若 SW 已拦截，前台请求不可见；若下载由浏览器特殊路径处理，可能没有普通 `Response`；若 popup 在 route 注册前打开，初始请求缺失；若后台 API 调用独立发出，它不会成为前台 `Request`。监控报告应明确输出 `coverageScope`：`BROWSER_ONLY`、`INCLUDES_ROUTE_FETCH`、`INCLUDES_BACKGROUND_REPLAY`、`SERVICE_WORKER_DISABLED`、`SERVICE_WORKER_PRESENT_INCOMPLETE`。未标注覆盖范围的“全量 API 监控”没有可审计意义。

## 8. 路由与 store 的实现骨架必须显式处理取消、失败和清理

**推荐实现是 context 级采集、Page 级策略、epoch 级 owner、不可变版本和具名清理。** 以下代码按 1.59+ 官方接口设计；`AtomicReference`、`ImmutableList`、JSON 库等不属于 Playwright，可按项目依赖替换。

```java
public final class ApiMonitor implements AutoCloseable {
    private final BrowserContext context;
    private final Disposable contextRoute;
    private final ConcurrentMap<String, PageScope> scopes = new ConcurrentHashMap<>();
    private final AtomicReference<GlobalApiSnapshot> snapshot = new AtomicReference<>(GlobalApiSnapshot.empty());

    public ApiMonitor(BrowserContext context) {
        this.context = Objects.requireNonNull(context);
        this.contextRoute = context.route("**", this::handle);
        context.onPage(this::attachPage);
        context.onRequest(this::onRequest);
        context.onResponse(this::onResponse);
        context.onRequestFinished(this::onFinished);
        context.onRequestFailed(this::onFailed);
    }

    private void attachPage(Page page) {
        PageScope scope = scopes.computeIfAbsent(pageGuid(page), k -> new PageScope(pageGuid(page)));
        page.onClose(p -> detach(pageGuid(p), page));
    }

    private void handle(Route route) {
        Request request = route.request();
        String owner = currentOwner(request).orElse(GLOBAL);
        RouteAttempt attempt = new RouteAttempt(request, owner);
        beginRoute(owner, attempt);
        boolean background = isBackgroundReplay(request);
        try {
            if (background) {
                APIResponse real = route.fetch(new Route.FetchOptions()
                        .setMaxRedirects(5)
                        .setTimeout(10_000));
                finishRouteReplay(owner, attempt, real);
                route.fulfill(new Route.FulfillOptions()
                        .setStatus(real.status())
                        .setHeaders(redact(real.headers()))
                        .setBodyBytes(real.body()));
            } else {
                route.fallback();
            }
        } catch (PlaywrightException e) {
            failRoute(owner, attempt, e);
            throw e;
        }
    }

    private void onRequest(Request request) {
        String owner = currentOwner(request).orElse(GLOBAL);
        RequestRecord record = readRequestSafely(request, owner);
        PageScope scope = scopes.computeIfAbsent(owner, PageScope::new);
        scope.recordRequest(record);
        publish(scope.snapshot());
    }

    private void onResponse(Request request) {
        Response response = request.existingResponse();
        if (response == null) {
            return;
        }
        String owner = currentOwner(request).orElse(GLOBAL);
        PageScope scope = scopes.computeIfAbsent(owner, PageScope::new);
        scope.recordResponseHead(readHeadSafely(request, response));
        publish(scope.snapshot());
    }

    private void onFinished(Request request) {
        String owner = currentOwner(request).orElse(GLOBAL);
        PageScope scope = scopes.get(owner);
        if (scope == null) {
            return;
        }
        scope.recordFinished(readFinishedSafely(request));
        publish(scope.snapshot());
    }

    private void onFailed(Request request) {
        String owner = currentOwner(request).orElse(GLOBAL);
        PageScope scope = scopes.get(owner);
        if (scope == null) {
            return;
        }
        scope.recordFailed(new FailedRecord(request, safeUrl(request),
                request.failure(), clock.nanoTime()));
        publish(scope.snapshot());
    }

    private void publish(GlobalApiSnapshot next) {
        snapshot.set(next.withVersion(next.version() + 1));
    }

    @Override
    public void close() {
        contextRoute.dispose();
        scopes.clear();
    }
}
```

`route.fetch()` 返回的 `APIResponse` 的 body 会在 context 关闭前保留，除非显式 `dispose()`[11]；若 store 已复制并哈希 body，应调用 `real.dispose()` 释放底层缓冲。前台 `Response.body()` 的可用性也应按 `requestfinished` 判断；`Response.finished()` 会等待该响应结束并总是返回 `null`，其返回签名是 `null|String`[12]，不应把它当作完成状态的业务值。

**导航安全断言应等待一个显式事务句柄，而不是“页面再次安静”。** 推荐在触发动作前创建 `NavigationTracker`，记录 `ownerPageId`、预期 frame、事务 id、允许的新 epoch 和 pending 请求集合；动作包装在 `Page.waitForResponse()`/`waitForURL()`/`waitForDownload()`/`waitForPopup()` 中；动作返回后等待 store 的新版本满足“所有属于该事务且已见请求达到 FINISHED/FAILED，且 epoch 不是 ABORTED”。

```java
ApiTransaction txn = monitor.beginTransaction(page, "submit-order");
Response main = page.waitForResponse(
        "**/api/orders",
        () -> page.locator("#submit").click(),
        new Page.WaitForResponseOptions().setTimeout(10_000));

// main只代表首个匹配的响应，不代表全部请求已结束
txn.expectMethod("POST").expectUrlSuffix("/api/orders").expectTerminalStatusIn(200, 201);
page.waitForCondition(
        () -> monitor.current()
                .forOwner(page)
                .forTransaction(txn.id())
                .allSeenRequestsTerminated()
                .and(it -> it.hasUniqueRequest(txn.expectedKey())),
        new Page.WaitForConditionOptions().setTimeout(10_000));

ApiSnapshot finalState = monitor.current();
assertThat(finalState.requestCount(txn.expectedKey())).isEqualTo(1);
```

`Page.waitForResponse()` 的官方 Java 签名接受 URL/predicate、options 和 `Runnable` 回调，返回首个匹配响应[7]。这适合捕获触发动作的关键响应，但不能替代事务级聚合。若点击同时触发 `/orders` 和埋点，应在 transaction 中显式声明两者；若产品允许重复提交，则把“唯一性”从硬断言改为业务约束。

**后台 fetch 必须绑定 owner 生命期和写权限。** 最安全的模式不是在 handler 中随意 `context.request().fetch()`，而是：

```java
BackgroundReplayPlan plan = BackgroundReplayPlan.builder()
        .causeRequestId(record.id())
        .transactionId(txn.id())
        .ownerPageId(pageGuid(page))
        .navigationEpoch(epoch)
        .method(record.method())
        .url(record.url())
        .headers(redact(record.requestHeaders()))
        .body(record.requestBody())
        .maxRedirects(5)
        .timeoutMillis(10_000)
        .writable(false)
        .build();
if (!plan.writable() && isStateChanging(record.method(), record.url())) {
    throw new IllegalStateException("Refusing non-idempotent background replay: " + plan);
}
```

执行结果作为 `BACKGROUND` 记录写入 store，不冒充前台 `Request`。后台调用超时由 `RequestOptions.setTimeout()` 控制，默认 30000 毫秒，0 表示禁用超时[10]；后台异常写入 replay error，不反向把前台 `Request` 标成失败。

## 9. 高风险场景必须按“首请求可见性—对象生命周期—结果归属—终态—清理”验收

**单靠“点击后能读到某个响应”无法通过导航并发测试。** 下表给出针对每种场景的最小验收动作；任何一项不能重复稳定通过，就不应声称实现全量监控。

| 风险场景 | 必须观察 | 可信快照 | 可能丢失/误判 | 核心验收动作 | 推荐断言 |
|---|---|---|---|---|---|
| 整页链接/表单 GET | document request、frame 请求、重定向链 | response headers→finished | 只抓 URL 会漏 body 与链 | 连续点击同一链接；模拟 302；提交后取消 | transaction 内唯一 POST；链尾/链中状态分别断言 |
| `location.assign`/表单同步提交 | request、可能先发的 API、主文档响应 | response 到达后可信 | 主文档失败导致无终态 Response | DNS 失败、服务器不可达、500 | `FAILED`；不把主资源 failure 外推为 API failure |
| History SPA 无导航 | 当前 Page 请求 | 完成即可信 | 把 `pushState` 当导航；旧 epoch 误命中 | 快速切换路由；replaceState；取消 fetch | 路由事务 id+终态；禁止只断言 URL |
| iframe 表单/导航 | frame 请求、parent/page 关系 | Frame 当前对象可见时 | Frame detach 后 owner 失效 | 动态创建、删除、再创建 iframe | 使用 frame id+parent+epoch，不长期持有裸 Frame |
| 文件下载 | trigger request、Download 事件、failure、保存完成 | download record 完成 | 普通 Response body 语义缺失 | 下载中途取消；失败；重复下载 | downloadId 唯一；path/saveAs 终态；不混同 APIResponse |
| `target=_blank`/popup | context page、初始文档、后续请求 | popup Page 内的对象 | 先 waitForPopup 后加 route 漏首请求 | 并发打开两个 popup；关闭原 Page | context route/page事件先于动作；初始 URL 归 popup |
| Service Worker | SW 请求、来自 SW 的响应标记 | SW 外请求可信 | SW 内资源全量不可见 | 启用/禁用 SW；MSW；SW 版本更新 | 明确 incomplete coverage；禁用 SW 或按 SW 层验收 |
| 服务端重定向 | 每跳 Request、headers、最终 Response | 链连接后可信 | 只记终态；循环重定向 | 301/302/307；超过 max redirects | 遍历 redirectedFrom，记录 hop 与最终 status |
| 导航取消/abort/beforeunload | request→failed 序列 | failure record | 把没有 Response 当 bug | 拦截 beforeunload；模拟连接中断 | failure 非空；事务显式 ABORTED |
| 多个连续导航 | 每页 epoch、pending/finished 集合 | epoch 关闭后才可信 | 上一轮迟到请求命中新一轮 | 快速 back-to-back 导航、前进后退 | 按 epoch 关闭 pending；最终状态严格单调 |
| `goBack`/`goForward` | 主资源与新逻辑页状态 | 主响应可信，页面缓存语义待验 | bfcache 行为随浏览器变化 | Chromium/Firefox/WebKit 各测 | 断言实际 URL+关键应用状态；不假设缓存复现 |
| newPage/`window.open` | context.onPage、初始导航 | 初始 Page/Frame 可见后 | 拿不到 Page 前无法 page.route | 不同 target 名、无 opener | context handler 命中；popup/page 双重关联 |
| 后台 route.fetch | route 链、重定向、响应、body | replay record 完整 | 无限循环、SW 差异 | 在 handler 中重放 GET；模拟 3xx | routingDepth 限流；body hash 一致；response status 映射 |
| 后台 context.request | cookie、Set-Cookie、重定向、超时 | APIResponse 独立可信 | referer/CSRF/token 非官方保证 | 登录态 GET；POST 重放；网络重置 | 显式 header 白名单；写操作禁止；retry 仅网络错误 |
| 多个 route 匹配 | handler 调用顺序 | 最终 fulfill/resume 结果 | 误用 continue/resume 破坏链 | Page+context 同 URL；多次注册 | Page 优先；fallback 顺序；resume 立即出链 |

官方文档没有直接证明所有浏览器对每个历史导航的内部缓存和 Frame 对象回收细节，也没有证明 `context.request().fetch()` 会复制页面原始 `Referer`/CSRF 头；这些项必须在目标 Chromium、Firefox、WebKit 和具体浏览器版本上做实际抓包验收。图 1 中的“风险级别”是工程优先级，不是官方 API 返回值。

## 10. 最终设计决策应拒绝伪全量，换取可证明的边界和结果正确性

**全量监控的核心不是多订阅几个事件，而是承认并审计不可见性。** Service Worker、下载、popup 初始请求、路由 cache 关闭、后台 API 调用和重定向 hop 共同决定覆盖率。产品若依赖 Service Worker，全量浏览器网络监控不能声称无盲区；此时应把 SW 版本、请求来源、回退策略、HAR/协议采集和页面结果分开审计。

**推荐路由架构是 context 采集 + Page 策略，而不是 Page-only 的伪全局监控。** context route 覆盖任意 Page、popup 初始请求和全 context 生命周期；Page route 负责与当前测试意图相关的选择性 mock；后台 replay 使用独立、限深、显式 header 且默认只读的 `route.fetch()`。所有 route handler 必须最终调用 `route.resume()`、`route.fulfill()`、`route.abort(...)` 或 `route.fallback()` 之一；未决 route 会 stall 匹配请求[3][5]。

**推荐导航架构是 owner/epoch/transaction 三层关闭。** owner 决定 Page 归属；epoch 决定导航版本；transaction 决定业务事务和唯一候选。每次状态迁移生成不可变快照版本；`waitForCondition` 读取版本号，条件表达“本事务所有已见请求达到终态且唯一”，而不是“集合大小等于 N”。超时说明条件未满足，不是请求全部失败；测试报告必须保留最后版本、pending 请求、failure、重定向链、SW 标记和后台 replay。

**推荐生命周期清理是具名 disposable 与导航 epoch 关闭，而不是依赖页面导航自动清空。** context route 用 `Disposable.dispose()` 或 `BrowserContext.unroute()`/`unrouteAll()` 移除；Page 监听器在 Page 关闭和测试结束时成对移除；后台 replay 在 owner page/context 关闭或 epoch 超时时取消；后台 `APIResponse.body()` 已复制后显式 `dispose()`[11]。若测试并发共享 context，所有 mutable matcher state 必须按 `ownerPageId+epoch+transactionId` 隔离，不能依赖测试名称。

**对报告中的未证实内容应保持克制。** 本报告将官方 Java API 文档与官方多语言概念文档的交叉内容列为 A 级证据；版本差异通过 1.59 release notes 与接口 `@since` 核对。`Request.existingResponse()` 自 1.59 起存在[1]；1.59 release notes 同时列有 `Request.existingResponse()` 和 `Response.httpVersion()`，但旧 1.36–1.39 源码镜像页不能可靠证明当前主干的方法签名，因此不作为 Java 当前公开 API 的权威依据。以下事项仍属 C 级设计假设，需要针对实际 Playwright 版本与浏览器抓包确认：浏览器内部 `Request`/`Response` 对象何时被底层回收；`context.request().fetch()` 是否设置与页面相同的 `Referer`、CSRF token、TLS session ticket、HSTS、扩展 header；bfcache、Frame 复用和 Service Worker 更新的跨浏览器细节。

## 引用来源

[1] https://www.playwright.dev/java/docs/api/class-request
> “Request.existingResponse: Returns the Response object if the response has already been received, null otherwise.”

[2] https://www.playwright.dev/java/docs/api/class-request
> “When the server responds with a redirect, Playwright creates a new Request object.”

[3] https://www.playwright.dev/java/docs/api/class-browsercontext
> “BrowserContext.route() will not intercept requests intercepted by Service Worker.”

[4] https://www.playwright.dev/java/docs/api/class-download
> “Download event is emitted once the download starts. Download path becomes available once download completes.”

[5] https://www.playwright.dev/java/docs/api/class-route
> “Use Route.resume() to immediately send the request to the network, other matching handlers won't be invoked in that case.”

[6] https://www.playwright.dev/java/docs/api/class-browsercontext
> “HTTP Error responses, such as 404 or 503, are still successful responses from HTTP standpoint, so request will complete with requestfinished.”

[7] https://www.playwright.dev/java/docs/api/class-page
> “The method will block until the condition returns true. All Playwright events will be dispatched while the method is waiting for the condition.”

[8] https://www.playwright.dev/docs/network
> “To modify responses use APIRequestContext to get the original response and then pass the response to route.fulfill().”

[9] http://playwright.net.cn/docs/service-workers
> “如果在具有非空 request.serviceWorker() 的 Request/Response 上调用 request.frame() 或 response.frame()，将会抛出异常。”

[10] https://www.playwright.dev/java/docs/api/class-requestoptions
> “Maximum number of times network errors should be retried. Currently only ECONNRESET error is retried.”

[11] https://www.playwright.dev/java/docs/api/class-apiresponse
> “dispose: Disposes the body of this response. If not called then the body will stay in memory until the context closes.”

[12] https://playwright.tw/java/docs/api/class-response
> “finished: Waits for this response to finish, returns always null.”

[13] https://github.com/microsoft/playwright-java/releases/tag/v1.59.0
> “Method request.existingResponse() returns the response without waiting.”

[14] https://www.playwright.dev/java/docs/release-notes
> “Request.existingResponse() returns the response without waiting.”

[15] https://www.playwright.dev/java/docs/api/class-apirequestcontext
> “Each Playwright browser context has an associated APIRequestContext, accessible via BrowserContext.request() or Page.request().”
