# Java Playwright 1.62 网络 API 分层使用指南

> 本文所有 API 签名与行为均基于 Playwright **1.62** 官方 Java 文档核实（浏览器基线：Chromium 151 / Firefox 153 / WebKit 26.5）。
> 网络 API 面在 1.62 无破坏性变更，分层模型可直接落地。

---

## 0. 总览：一次请求的时序 × API 分层

![一次成功请求的时序 × Playwright Java API 分层](assets/request-lifecycle-layers.svg)

读图要点：

- **时序固定**：一次成功请求固定触发 `request → response → requestfinished`；任一阶段失败则走 `requestfailed`（用 `request.failure()` 拿错误文本）。
- **两个反直觉点**：HTTP 4xx/5xx 仍走 `requestfinished`（不是 failed）；重定向请求也以 `requestfinished` 收尾，并对重定向目标发起新请求。
- **数据可用区间**：`response` 事件时 `status / headers / timing` 可用，**body 未齐**；`requestfinished` 时 `body / sizes / postData` 才安全可读。
- **三个执行线程**：测试线程（动作与等待）、Playwright 派发线程（`onXxx` 回调）、异步消费线程（body 解析在队列外完成）。

---

## 1. 地基：Java 的三条线程铁律

Playwright Java 的 `onXxx` 处理器**不在测试线程执行，而在 Playwright 内部的连接派发线程上回调**。由此产生三条铁律：

1. **handler 里禁做重活**（JSON 解析、DB/HTTP 外发）——会阻塞同 context 后续所有事件的派发；
2. **handler 里禁等待其他事件**（在 `onResponse` 里调 `waitForResponse` / `waitForNavigation` / 断言）——等的事件要靠同一条派发线程送达，直接死锁；
3. **handler 里禁无界阻塞**——流式/长轮询响应的 `body()` 可能永远不返回。

`playwright.close()` 会中断派发线程，消费者线程要有优雅退出（drain + timeout）。

---

## 2. L1 断言层：`waitForResponse(predicate, action)`

```java
Response resp = page.waitForResponse(
    r -> r.url().contains("/api/order/submit")
      && "POST".equals(r.request().method())
      && r.status() == 200,
    () -> page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("提交订单")).click()
);
```

- `action` 先执行再等待，把「click → 等响应」变成原子操作，消除两者之间的 race——这是它相对裸 `onResponse` 监听的核心价值；
- predicate **只读本地字段**（`url()/method()/status()/headers()`），不要在里面读 `body()`；
- 默认超时 30s，跟 `setDefaultTimeout()` 走。

### 关键补充：`waitForRequestFinished`

`waitForResponse` 在**响应头到达**时就解析（见时序图），此时 body 未齐。要断言 body 完整性（比如校验订单号回显），用 `page.waitForRequestFinished(predicate, action)`（v1.12）：

```java
Request fin = page.waitForRequestFinished(
    r -> r.url().contains("/api/order/submit"),
    () -> page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("提交")).click());
// 解析时 body 已完整下载，可安全读取
Response resp = fin.response();
if (resp != null) {
    String body = resp.text();
}
```

流式/SSE/长轮询场景：`waitForResponse` 本身不阻塞，但事后调 `body()` 会长时间阻塞——要么不读 body，要么改用 `requestfinished` + deadline 策略。

---

## 3. L2 观测层：context 四事件 + 异步消费者

`onRequestFinished` 是唯一「body 已完整」的安全读取点——「异步消费者」模式的落点：

```java
context.onRequestFinished(req -> {
    if (!policy.accept(req)) return;              // 域名/方法前置过滤，尽早 return
    Response resp = req.response();
    if (resp == null) return;                     // 防御性：失败请求无 response
    if (!queue.offer(new CaptureTask(req.url(), req.method(), resp))) {
        dropped.incrementAndGet();                // 背压：丢弃+计数，绝不阻塞派发线程
    }
});
context.onRequestFailed(req -> log.warn("failed {} {} : {}", req.method(), req.url(), req.failure()));
```

- `request.sizes()`（各段字节数）只在 finished 之后可用；1.62 新增的 `APIResponse.timing()` 可给 API 层响应做计时，监控面板直接用；
- tracing 的 HAR 是**事后文件**，适合排查，不适合实时采集/断言，别混用；
- 消费端务必有界（`ArrayBlockingQueue` + 固定线程池或虚拟线程），大 body 只留 hash/大小，防堆内驻留。

### 3.1 响应 body 的跨浏览器生命周期：为什么必须「立即读、自己存」

> 结论先行：官方契约对三个浏览器一致——**必须在导航前读 body**。Chromium 上「**导航后**仍可读」是其网络栈保留策略的副产品，不是 Playwright 的跨浏览器承诺；而**页面关闭后**再调 `body()`，三个浏览器都失败。

| | Chromium | Firefox | WebKit |
|---|---|---|---|
| driver 是否存 body | 否，懒加载 | 否，懒加载 | 否，懒加载 |
| 获取方式 | CDP `Network.getResponseBody` | juggler `Network.getResponseBody` | `Network.getResponseBody` |
| 导航后拿不到时 | 重取兜底（仅 GET + 静态安全类型），否则**静默返回空 Buffer** | `response.evicted` → 抛 `Response body for ... was evicted!` | 无兜底，协议错误 → 基类重写为 "navigated away" |
| 页面关闭后 | `raceWithPageClosure` 失败 | 同左 | 同左 |

源码位置（playwright-core，可自行核对）：

- driver 基类 `server/network.ts` 的 `Response.internalBody()`：body **无缓存**，每次 `body()` 重新走协议；协议失败时统一追加 **"Response body is not available for a response that was navigated away from. Read response.body() before triggering any navigation."**；3xx 重定向的 body 直接抛错；`body()` 整体被 `raceWithPageClosure` 包裹，页面一关就失败；
- Chromium `server/chromium/crNetworkManager.ts` 的 `createResponseBodyCallback`：CDP 网络日志导航后通常仍持有 body（延迟读在 Chromium 上「碰巧可行」的根源）；被回收时只对 `kRefetchSafeResourceTypes`（font/image/script/stylesheet 等）**且仅 GET** 走 `Network.loadNetworkResource` 重取，源码注释明说重取可能对服务端产生副作用。**POST/XHR 不在兜底范围——被逐出时静默返回空 Buffer，不报错**；
- Firefox `server/firefox/ffNetworkManager.ts`：`if (response.evicted) throw ... was evicted!`，逐出是显式行为，导航后延迟读必挂或迟早挂；
- WebKit `server/webkit/wkInterceptableRequest.ts`：无 evicted 标志、无兜底；仅有 `adoptRequestFromNewProcess` 在跨进程导航时重绑定 session/requestId（尽力兼容，但不构成承诺）。

工程结论：

1. **多浏览器项目不要写延迟读 body 的逻辑**——在 `onRequestFinished` 里立即读、落到自有存储（见 7.2 消费者模式）不是「最佳实践」而是「唯一可行」；
2. 只跑 Chromium 也要防两个暗坑：a) POST/XHR 类 body 被逐出时**静默返回空 Buffer**，采集端必须校验 `body.length` 或 content-length 对账，否则「采到了但其实是空」；b) 兜底重取会对服务端产生副作用，与 7.1 的「写操作禁放」风控原则冲突；
3. 「关闭页面后仍可查询」只对自己**已经读出来存下**的副本成立——`page.close()` 之后再调 `body()`，三个浏览器都失败。

### 3.2 `existingResponse()` vs `onRequestFinished`

先分清**契约**与**机制**：`onRequestFinished` 是"body 已完整到达"的**事件契约**（采集链路的正确性锚点）；`existingResponse()`（v1.59）是一个**非阻塞窥视工具**——用来**避免在派发线程里踩 `response()` 的阻塞坑**，**不是 `onRequestFinished` 事件的平替**。

| 场景 | 选型 | 理由 |
|---|---|---|
| 需要 body + 能在事件回调里就地消费 | `onRequestFinished` | 事件即在"body 已齐"窗口内，handler 里直接读其 `response()` 参数 |
| 要零竞态、业务线程安全消费、**不订阅网络事件** | `existingResponse()` 轮询 | 读请求**本地字段**、零协议往返、不触碰对象表；命中后趁句柄存活**就地快照** body 再交业务线程 |
| 仅要 `status / headers / timing`（不要 body） | 任一；`existingResponse()` 命中即读响应头 | 响应头在 `response` 阶段已齐 |
| 流式 / SSE / 长轮询 | 两者都不适合无界读 body | 用 deadline + 只读 hash/sizes，不读 body |

**为什么本框架用 `existingResponse()` 轮询而不是订阅 `onRequestFinished`**（采集链路认准 `onRequestFinished` 的"body 完整"契约，但用非阻塞窥视去实现它）：
1. **订阅任何响应侧事件都会把 `Object doesn't exist` / `Cannot find object to call` 的句柄回收竞态带回来**——这正是本框架删除 `onResponse` 订阅要消灭的东西。实测：连 `page.onXxx` 这类非 response 事件，只要目标对象在消息泵派发时已被回收，通用 `Connection.dispatch`（`objects.get(guid)==null`）一样抛错，属同一类敞口；
2. `onRequestFinished` 的 handler 跑在**派发线程**，在其内调 `response()` / `body()` 会阻塞同 context 的事件派发（死锁风险）；
3. `existingResponse()` 从**调度/轮询线程**调用：既不订阅（无事件⇒无回收竞态），也不在派发线程（无阻塞）。命中后**立即快照** body，即等价达成 `onRequestFinished` 的"body 完整"语义（`body()` 本就会阻塞到 body 收全）。

**三个语义边界（务必分清）**：
1. `existingResponse() != null` **≠** body 已齐可读——它仅表示"响应已到达、句柄已创建"；返回 `null` 表示响应尚未到达，不能据此判断 body 状态；
2. **派发线程（`onXxx`）里调 `response()` / `body()` 会阻塞同 context 事件派发**，严重时死锁（`response()` 本身要等派发线程把响应送达）——这正是 `existingResponse()` 存在的理由；
3. `existingResponse()` 命中后，`body()` 仍是**协议调用**，须**尽快就地读**：Chromium 仅在**导航离开但页面仍开着**时有宽限（CDP 日志保留；且重取兜底只限 GET 静态安全类型，JSON/XHR/POST 不在列），Firefox/WebKit 一回收就没了（`evicted` / 静默空）；而**页面一旦关闭，`body()` 三浏览器俱败**（`raceWithPageClosure`）。跨浏览器唯一可依赖的仍是"导航前读"（详见 §3.1）。

---

### 3.3 pull 轮询模式：契约等价的 pull 实现

> 结论先行：`existingResponse()` 轮询**可以**达成 `onRequestFinished` 的「body 完整」契约（`body()` 内部先等 body 收全再返回），且把阻塞点从消息泵搬走；但它**不能**消除句柄回收竞态——只能把竞态从「泵内不可定位的随机抛错」变成「轮询点可捕获、可分类的异常」。准确表述是**竞态可控**，不是无竞态。

#### 3.3.1 为什么「不订阅 ⇒ 无回收竞态」不成立

playwright-java `impl/Connection.java` 里两条抛错的真实位置：

```java
// ① dispatch()：事件到达时 guid 不在 objects 表——发生在你的 handler 被调用之前
ChannelOwner object = objects.get(message.guid);
if (object == null)
  throw new PlaywrightException("Cannot find object to call " + message.method + ": " + message.guid);
// ② getExistingObject()：任何 API 调用解析句柄时 guid 已被摘除
T result = (T) objects.get(guid);
if (result == null)
  throw new PlaywrightException("Object doesn't exist: " + guid);
```

playwright-core `server/dispatchers/dispatcher.ts`：

```typescript
_dispatchEvent(method, params) {
  if (this._disposed) { return; }
  this.connection.sendEvent(this, method, params);  // 无条件广播：无订阅握手、无 listener 计数
}
```

推论链断在三处：

1. **不订阅 ≠ 无事件**：驱动侧一视同仁地推，事件照达 Java 客户端、照走 `dispatch()`；guid 被 `__dispose__`（关页/关 context 级联清理、GC 回收）摘除后照抛；
2. **handler 的 try/catch 拦不住**：`dispatch()` 的查找+抛错在 handler 查找**之前**——push 模式下异常从泵线程上任意一个正在执行的 Playwright 调用处炸出，不可定位；
3. **轮询自己也走同一条路**：`request.existingResponse()` / `response.body()` 解析句柄走 `getExistingObject`，句柄被回收时抛的是同一个 "Object doesn't exist"。

#### 3.3.2 风险三分层

| | A：dispatch 层 | B：handler 层 | C：轮询点 |
|---|---|---|---|
| 机制 | 未知 guid 事件到达即抛 | handler 在泵线程内执行：调 `response()/body()` 阻塞泵、嵌套派发 | `existingResponse()/body()` 解析句柄抛 "Object doesn't exist" |
| 与订阅的关系 | **无关** | 仅订阅时存在 | 仅 pull 时存在 |
| push（订阅 onRequestFinished） | 暴露，handler 外层拦不住 | 暴露 | — |
| pull（existingResponse 轮询） | 仍暴露（事件流与订阅无关） | **彻底消除** | 新增，但抛点就在你的 try/catch 里 |
| 可否捕获 | 否 | 是（代价是阻塞已发生） | **是，可分类：丢失/3xx/超时/失败** |

结论：删订阅唯一买到的是 **B 的彻底消除**（handler 不存在了）；A 是消息泵 + 句柄生命周期的固有属性，订阅与否俱在；pull 把 A 换成可捕获的 C。对无人值守的采集链路，「永不崩、可恢复」比「理论上无竞态」值钱——这是 pull 模式的真实架构收益。

#### 3.3.3 落地两件套

**① 句柄来源闭合**。`existingResponse()` 是 `Request` 的实例方法——不订阅 `onRequest` 就没有句柄可轮询。推荐混合架构：`onRequest` 枚举（handler 极薄：白名单/方法分级 + offer 有界队列，**绝不碰 `response()/body()`**）+ 响应侧 `existingResponse` 轮询 + `body()` 快照。纯 pull 的 `page.waitForRequest` 一次性、只适合可预知请求，做不了全局枚举（附带好处：它的等待在你的调用栈内，dispatch 抛错炸在你的调用点，可 catch）。

**② pull 状态机 + 即快照**：

```java
// 唯一的订阅：枚举 + 入队，零阻塞
context.onRequest(req -> {
    if (!policy.allow(req.url(), req.method())) return;         // 白名单 + 写操作默认禁放
    if (!queue.offer(new Probe(req, req.url(), req.method())))  // 有界队列
        dropped.incrementAndGet();
});

// 轮询消费者
Probe probe;
while ((probe = queue.poll(200, MILLISECONDS)) != null) {
    try {
        Response resp = probe.request().existingResponse();     // null = 未到达
        if (resp == null) {
            if (probe.expired()) { lost.incrementAndGet(); continue; }
            queue.offer(probe); continue;                       // 下轮再窥
        }
        byte[] body = snapshotWithDeadline(resp, SNAPSHOT_TIMEOUT);
        if (body == null) { noBody.incrementAndGet(); continue; } // 3xx / 失败 / 流式超时
        store.save(probe, body);                                // 命中即快照进自有结构
    } catch (PlaywrightException e) {                           // "Object doesn't exist" 等
        lost.incrementAndGet();                                 // 句柄被回收：可捕获、可分类、可降级
    }
}

// body() 自身阻塞到收全、无内建超时——外包 deadline 防流式响应挂住轮询线程
static byte[] snapshotWithDeadline(Response resp, Duration d) {
    try {
        return CompletableFuture.supplyAsync(resp::body, bodyPool)
                 .get(d.toMillis(), TimeUnit.MILLISECONDS);
    } catch (TimeoutException | ExecutionException e) { return null; }
}
```

| `existingResponse()` | `body()` | 归类 |
|---|---|---|
| null | — | 未到达，带 deadline 重窥 |
| 抛 "Object doesn't exist" | — | 句柄被 GC/关页回收，丢失 |
| resp | 正常返回 | **命中，立即快照** |
| resp | 抛（3xx 重定向响应 body 不可读） | 正常无 body |
| resp | 不返回（流式/悬挂） | deadline 超时放弃 |
| resp | 抛（网络失败） | 失败，`request.failure()` 佐证 |

硬约束：服务端 GC（`maybeDisposeStaleDispatchers`，桶超 10000/100000 时最旧 10% 以 `gc` 原因回收）下**响应句柄不可懒持有**——「命中即把 buffer 拷进自有结构、丢弃句柄」是设计前提。它防的是 GC 回收，不是 dispatch 竞态，两回事。

> **本框架实证补丁（并发泵 Connection）**：上面的 `snapshotWithDeadline` 用独立池跑 `body()`，会与主线程的 page 动作**并发泵同一条 Connection**。本框架（pw-route）实测这会触发 Connection 竞态 → 级联 `Object doesn't exist`，故把 `existingResponse()` / `deliverResponse` / `body()` 读取**全部收口到业务线程 main drain 串行执行**（调度器只当节拍器、回调不碰连接对象）。若你的后台轮询与主线程动作并发时出现级联抛错，按此把**协议调用**改到单一线串行（JSON 解析等纯 CPU 活仍可池化）——这不改 pull 的契约，只改"谁来泵连接"。

#### 3.3.4 与 push 模式的选型

| 维度 | push（onRequestFinished） | pull（existingResponse 轮询） |
|---|---|---|
| 句柄回收竞态 | 泵内随机抛错，不可定位 | 轮询点可捕获、可分类 |
| 派发线程阻塞 | 有（handler 内调 `response()/body()`） | 无（handler 极薄，只入队） |
| 延迟 | 事件驱动，最低 | 轮询间隔（≥100ms 级） |
| 自建成本 | 低（契约现成） | 高（句柄来源 + 状态机 + deadline） |
| 适用 | 测试内、有人值守、断言场景 | 后台采集、无人值守、要求永不崩 |

> 一句话：`onRequestFinished` 是契约锚点，`existingResponse()` 轮询是同一契约的 pull 实现——不是平替，差别在**竞态的爆炸点位置与可捕获性**。

---

## 4. L3 控制层：`route()` 的完整武器库

Java 的命名和 JS 有差异：放行是 `route.resume()`（JS 叫 `continue`，Java 撞保留字），连同 `fulfill` / `abort` / `fallback` / `fetch` 构成全套：

| 方法 | 用途 |
|---|---|
| `resume(ResumeOptions)` | 放行，可改 header/method/url/postData |
| `fulfill(FulfillOptions)` | 完全伪造响应（status/headers/body/contentType） |
| `abort(errorCode)` | 模拟网络错误 |
| `fallback()` | 链式：不处理时显式交给下一个匹配的 handler |
| `fetch(apiRequestContext)` | 用 Playwright 独立网络栈代理请求，再 `fulfill(setResponse(...))` 改写 |

官方文档级细节：

- **优先级**：Page route 与 context route 同时命中时，**Page route 优先**；
- 两个官方警告：开启 route 会**禁用 http cache**；Service Worker 发起的请求拦截不到，需要拦截时设 `setServiceWorkers("block")`；
- `RouteOptions.setTimes(n)`（v1.15）可让路由只用 N 次（首次 mock、后续走真实流量）；
- 重放的正道是 `routeFromHAR`，**`setUpdate(false)` 防止把线上写操作重新录进去**——这条直接对应重复下单风险：

```java
context.routeFromHAR("har/orders.har",
    new BrowserContext.RouteFromHAROptions()
        .setNotFound("abort")
        .setUpdate(false));
```

- glob pattern 的精度就是安全边界：`**/api/**` 这种宽匹配配上 `fulfill` 伪造，误伤静态资源和埋点请求是迟早的事。

---

## 5. L4 独立请求层：`APIRequestContext`

```java
try (APIRequestContext api = playwright.request().newContext(
        new APIRequest.NewContextOptions()
            .setBaseURL("https://api.internal.example.com")
            .setTimeout(10_000)                                  // 独立于 page timeout
            .setExtraHTTPHeaders(Map.of("X-Client", "e2e")))) {
    APIResponse r = api.post("/v1/orders",
        RequestOptions.create()
            .setHeader("Idempotency-Key", UUID.randomUUID().toString())
            .setData(payload));
    assertThat(r).isOK();
    r.dispose();
}
```

- 数据准备、teardown、服务级断言走这里：不进浏览器网络栈、不吃 route、不产生事件、无 CORS/SW 干扰——**这就是「不伪装成浏览器流量」的正解**；
- 反面模式 `page.evaluate("fetch(...)")` 会把后台调用塞进 page 的网络栈：被 route 拦截、被 tracing 记录、还受 CSP 限制；
- 需要浏览器登录态时用 `context.request`（共享 context 的 cookie/storage state），或显式 `context.cookies()` → `setStorageState(...)` 搬运，不要手工拼 Cookie 头；
- 用后 `dispose()`；写操作带 `Idempotency-Key`。

---

## 6. L5 兜底轮询层

只在没有协议事件可依赖时用（等前端内部状态、localStorage、第三方 SDK 副作用）。可用 `page.waitForCondition`、断言库自动重试（`assertThat(locator).toHaveText(...)` 自带轮询），1.62 还新增了 `Locator.waitForFunction()`。

禁裸 `Thread.sleep` 循环；任何轮询都要带 deadline 和 ≥100ms 间隔。

---

## 7. 后台 fetch 采集/重放的风控管线

![后台 fetch 采集与重放风控管线](assets/network-risk-pipeline.svg)

### 7.1 路由侧骨架（入口守卫 + 方法分级 + 安全头保持）

```java
private static final String COLLECTOR_HEADER = "X-Collector";
private static final Set<String> WRITE_METHODS = Set.of("POST", "PUT", "PATCH", "DELETE");

context.route("**/api/**", route -> {
    Request req = route.request();
    if ("internal".equals(req.headerValue(COLLECTOR_HEADER))) { route.resume(); return; }  // 防循环采集
    if (!whitelist.allows(URI.create(req.url()).getHost()))     { route.abort(); return; } // 域名白名单
    if (WRITE_METHODS.contains(req.method()) && !writeReplayPolicy.allow(req)) {           // 写操作默认禁放
        audit.record("blocked-write", req.url());
        route.resume();
        return;
    }
    route.resume(new Route.ResumeOptions().setHeaders(safeHeaders(req)));                  // 只追加，不改写安全头
});

// 熔断 fail-open：采集子系统故障不能拖垮业务测试
if (breaker.tripped()) {
    context.unrouteAll();                 // 摘除全部路由，业务流量直通
    metrics.alert("network-collector-open");
}
```

### 7.2 消费侧骨架（有界队列 + 去重 + 审计）

```java
BlockingQueue<CaptureTask> queue = new ArrayBlockingQueue<>(1024);
ExecutorService consumers = Executors.newFixedThreadPool(4);
Map<String, Long> dedup = new ConcurrentHashMap<>();

consumers.submit(() -> {
    while (running) {
        CaptureTask task = queue.poll(2, TimeUnit.SECONDS);   // 独立超时，绝不死等
        if (task == null) continue;
        String key = task.method() + "|" + task.url() + "|" + task.bodyHash();
        Long seen = dedup.putIfAbsent(key, System.currentTimeMillis());
        if (seen != null) { metrics.inc("collector.dedup"); continue; }   // 防重复下单
        audit.record(task.traceId(), task);                   // 采集标识，全链路可关联
        sink.write(task);                                     // 外发/落库
    }
});
```

### 7.3 四类风险的防线映射

| 风险 | 防线 | 落点 |
|---|---|---|
| 循环采集 | 内部标识头 `X-Collector: internal` 快速放行；`route.fetch()` 走独立网络栈天然不递归 | handler 第一行 |
| 重复下单 | 写操作默认禁放；白名单 + `Idempotency-Key` + dry-run 三关；消费者按 `method\|url\|bodyHash` 去重（TTL） | route + consumer |
| 绕过 CSRF/认证 | `Cookie / Origin / Referer / X-CSRF-Token` 只追加不改写；跨 origin 的 `fulfill` 一律拒绝 | `ResumeOptions` |
| 拖垮业务测试 | 有界队列 + 丢弃即计数；采集与请求各自独立超时；熔断 `unrouteAll()` fail-open，半开探测恢复 | queue + breaker |

---

## 8. 1.62 版本备注

- 网络 API 面无破坏性变更：`onRequestFinished`（v1.12）、`route.fallback/fetch`（v1.29）、`routeFromHAR`（v1.23）均为现成能力；
- `Request.existingResponse()`（v1.59）：读请求本地字段拿到已到达响应，**零协议往返、不触碰对象表**，是"不订阅 response 事件也能安全观测 body"的关键 API；
- 1.62 与监控相关的新增：`APIResponse.timing()`（API 响应计时）、`Locator.waitForFunction()`；
- 其他 1.62 变更：WebP 截图、动作新增 `scroll` 选项、headless 模式剪贴板隔离；
- 浏览器基线：Chromium 151 / Firefox 153 / WebKit 26.5。

---

## 9. 反模式清单

- `page.evaluate("fetch(...)")` 把后台流量伪装成浏览器流量；
- handler 内做重活、等待其他事件、无界阻塞（见第 1 节三铁律）；
- 裸 `Thread.sleep` 轮询替代协议事件；
- `routeFromHAR` 开 `setUpdate(true)` 录线上写操作；
- 宽 glob pattern（`**/api/**`）直接配 `fulfill` 伪造；
- 采集队列无界，body 全量堆内驻留；
- 延迟读 body（把 `body()` 推到业务/IO 线程，句柄被回收 → 空 body / evicted 错误）；
- 派发线程（`onXxx`）里调 `response()` / `body()`（阻塞同 context 事件派发，严重时死锁）；
- 拿 `existingResponse() != null` 当"body 已齐"判据（null 仅表示响应未达，不表示 body 可读）。

---

## 10. 一句话总结

六层分层就是官方推荐用法的正确浓缩，只需三处精修：断言层补 `waitForRequestFinished`（body 完整性断言）、控制层补 `fallback/fetch` 和 `routeFromHAR(update=false)`、全局守 Java 派发线程三铁律（禁重活、禁等待、禁无界阻塞）。外加一条跨浏览器铁律：**响应 body 只在 `onRequestFinished`（或 `existingResponse()` 命中后趁句柄存活）立即读、自己存**，绝不延迟到业务线程再读。

---

## 附录：已核实的官方事实清单

| # | 事实 | 来源 |
|---|---|---|
| 1 | `onRequest / onResponse / onRequestFinished / onRequestFailed` 自 v1.12 起在 `BrowserContext` 与 `Page` 上可用 | API 文档 |
| 2 | 成功时序 `request → response → requestfinished`；HTTP 4xx/5xx 仍走 `requestfinished`；失败走 `requestfailed` + `request.failure()` | API 文档 |
| 3 | Java `Route` 方法为 `resume / fulfill / abort / fallback / fetch`（JS 为 `continue`，Java 因保留字改名） | Java Network 文档 |
| 4 | `route.fallback()` 支持多 handler 链式；`route.fetch()` + `fulfill(setResponse(...))` 代理改写 | Java Network 文档 |
| 5 | Page route 同时命中时优先于 BrowserContext route | BrowserContext API 文档 |
| 6 | 开启 route 禁用 http cache；SW 请求拦截不到，建议 `setServiceWorkers("block")` | BrowserContext API 文档 |
| 7 | `RouteOptions.setTimes(n)`（v1.15）限次使用路由 | BrowserContext API 文档 |
| 8 | `routeFromHAR` 自 v1.23，支持 `setNotFound` / `setUpdate` | BrowserContext API 文档 |
| 9 | `waitForRequestFinished(predicate, action)` 自 v1.12；`waitForResponse` 默认 30s 超时 | Page API 文档 |
| 10 | 1.62 新增 `APIResponse.timing()`、`Locator.waitForFunction()`、WebP 截图、`scroll` 选项 | Java Release Notes |
| 11 | `Request.existingResponse()`（v1.59）返回已到达响应的本地字段引用，**零协议往返、不触碰对象表**；与 `response()`（事件回调参数）语义不同 | Request API 文档 |
| 12 | 基类 `network.ts` 定义响应 body 的 **"navigated away"** 契约：导航离开后 body 不可用；各浏览器兜底差异见 §3.1 | playwright-core 源码 |
| 13 | 三浏览器 body 兜底差异：Chromium 重取（POST/XHR 静默空 `Buffer`）、Firefox `evicted` 抛错、WebKit 无兜底 | playwright-core 源码（`chromiumNetwork.ts` / `ffNetwork.ts` / `wkNetwork.ts`） |

---

## 更新记录

- **2026-10-15**：
  - 新增 §3.1「响应 body 的跨浏览器生命周期」与 §3.2「`existingResponse()` vs `onRequestFinished`」（三浏览器机制对比表、playwright-core 源码锚点、五场景决策表、三语义边界）；
  - §8 补 `Request.existingResponse()`（v1.59）备注；§9 反模式清单补 3 条；§10 一句话总结补跨浏览器铁律；附录事实表补 11–13 行；
  - 关联框架落地（pw-route）：MOCK/MODIFY 增加**请求终结所有权 + 转发状态保护**——`RouteClaim.markIssued()` + 受保护 `RouteAction.fallback(claim, route)`，抑制已发出业务请求后的重复 `fallback`，防异常路径重复发业务请求（见 `RouteDispatcher` / `ClaimRegistry` / 新增 `RouteClaimForwardingStateTest`）。
- **2026-10-15（同日修订）**：
  - §3.1 / §3.2 框架纠正：`existingResponse()` 明确定位为 v1.59 **非阻塞窥视工具**（用于避开派发线程 `response()` 阻塞坑），**非 `onRequestFinished` 平替**；官方对三浏览器一致的契约收敛为一句「**必须在导航前读 body**」——Chromium 上"导航/关页后仍读到 body"实测成立是 **Chromium 持久响应缓冲的副产品，不是 Playwright 跨浏览器承诺**（真实 Chrome 立即导航/关闭旧页面后已保存 body 仍可查）；§3.2 补"为什么不订阅 `onRequestFinished`"三因（回收竞态 + 派发线程阻塞 + 轮询线程两害皆无）。
  - 关联框架落地（pw-route）：`MonitorSink` 与 `CaptureSink` 的响应配对由 **FIFO 队首盲取改为按请求身份精确配对**——`onResponseForSpec` 增 `Request` 参、`RouteRuntimeImpl` 透传 `request`、`CapturedExchange` 持 `request()` 引用，杜绝同 pattern 并发多请求时"响应 B 结算请求 A"的串扰；2 参重载保留作向后兼容（退化为 FIFO）。验证：`CaptureSinkIdentityMatchingTest`(3) + `MonitorSinkIdentityMatchingTest`(3) 全绿，pw-route 338 tests 0 失败。
  - 关联框架落地（pw-route）：body 读取**提取为独立 framework-internal 类 `ResponseBodyReader`**（`monitor` 包，包级私有）——读一次 + 失败分类（`Result{bytes, failure}`）。**【同日再修订·删降级重试】**经文档 §3.1 复核推翻了此前"Chromium 持久缓冲降级重试"的前提：页面关闭后 `body()` 三浏览器俱败（`raceWithPageClosure`），Chromium 重取兜底**仅限 GET 静态安全类型**（font/image/script/stylesheet/document），**JSON/XHR/POST 不在列**——即重试救不回本框架的 JSON API body。故**移除整套降级重试**（`ResponseBodyReader` 的 retry + `chromiumBuffered` 标志 + `RouteRuntimeImpl.isChromiumContext` + `bodyRecoveredByRetry` 计数器），`ResponseBodyReader` 退化为"只读一次、失败即交回根由"。真正的 mitigation 是"**立即读、自己存**"（压缩到达→读取窗口），而非失败后重试。验证：`ResponseBodyReaderTest`(4，含"绝不重试 `verify(times(1)).body()`") + `CaptureSinkBodyReadFailureTest`(1，fail-open + 不重试) 全绿，pw-route 343 tests 0 失败。
- **2026-10-15（第三轮·文档扩写 + route-v2 收口）**：
  - 文档扩写：新增 **§3.3「pull 轮询模式」**（3.3.1 不订阅≠无回收竞态 / 3.3.2 风险三分层 A-B-C / 3.3.3 落地两件套 + **本框架"协议调用收口 main drain 串行"实证补丁** / 3.3.4 push-vs-pull 选型）与 **§11「可运行模块骨架」**；§3.1 升级为**源码锚定版**并**修正 Intro 自相矛盾**（"关页面后仍可读"→ 改为"导航后仍可读；页面关闭三浏览器俱败"）；§3.2 边界3 同步去"关页后可读"过度声称；§11 附并发泵 Connection 警示。
  - route-v2 收口：`pw-route-v2` 早已合并回 `pw-route`（pom 无该模块、无代码目录），本轮清理文档侧残留引用（README 模块表 + 修复方案文档的历史遗留说明）。
- **2026-10-16（第四轮·到达即读）**：
  - 落地"**main drain 到达即读**"：`RouteRuntimeImpl.drainMainThreadActions()` 收尾调用 `CaptureSink.materializeArrivedBodies()`——响应轮询在 drain 里检出到达、持句柄后，**同批 drain 即读 body**（业务线程、协议调用安全），不再延迟到下一次采集查询。填补了 `drainSettledAssertionFailures/register/close` 等**非查询型 drain** 检出到达却不 flush、body 被拖到下次查询（期间导航驱逐即丢）的缺口。
  - 澄清不可压缩的边界：body 读取受"必须业务线程、禁后台泵 Connection"约束，故"**网络到达→本次业务 drain**"窗口不可再压（要压＝ reintroduce 并发泵竞态）；本改动只保证"**一旦 drain 检出到达，同批内即读**"。采集查询 seam（dump/peek/waitFor 等）本就是 drain→flush 背靠背，此次把该保证推广到所有 drain。
  - 线程安全：首轮轮询在 dispatch 线程只做 `existingResponse()`（本地字段）+ `onResponseForSpec` 持句柄（零协议），body 一律在业务线程 drain 读——未引入任何跨线程泵连接。验证：新增 `RouteRuntimeArrivalMaterializeTest`(1：dispatch `never().body()` + drain `atLeastOnce().body()`)，pw-route 344 tests 0 失败。
  - **Monitor 也到达即读入快照**（用户拍板）：`BodySnapshot` 提取为顶层包级私有 record；`MonitorSink.materializeArrivedBodies()`（drain 收尾调用）对"有 body 断言、已到达、未结算"的 exchange 就地读 body 成快照存入 `CapturedExchange.prefetchedBody` 并释放句柄；`settleBodyAssertionNow` **优先消费预取快照**，无预取则回退现读持有句柄（首轮 dispatch 交付未及预取等边缘路径，行为与历史一致）。只读 body 不跑断言（断言仍在结算处），3xx/流式/失败由 `takeBodySnapshot` 归一、计数仍在结算处不重复。`settleFailure`/`drainSettledFailures` 的句柄补读有 `responseBodyPreview==null && held!=null` 双守卫，句柄被预取取走也不崩。验证：`MonitorSinkArrivalMaterializeTest`(2：到达即读+零二次读 / 回退仍通过)，pw-route 346 tests 0 失败。
