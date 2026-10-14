# 后台 API 重放准入规则

> 适用场景：路由采集之外，为"拿到完整响应体 / 独立验证"而对已观测请求发起的**第二次 HTTP 调用**。
> **核心结论：后台重取必须遵循最小、受限、可追溯原则** —— 否则观测工具会变成攻击面、脏写源、
> 配额黑洞和稳定性风险。尤以写方法为甚：**POST / PUT / PATCH / DELETE 默认禁止自动重放。**

---

## 0. 本框架的当前状态（先读这一节）

| 事实 | 说明 |
|---|---|
| **当前没有后台重放能力** | 全仓唯一的 `fetch` 是 MOCK intercept 的 `RouteAction.fetchAndFulfill`，它是 **route 链内**取真实响应用于 `fulfill`，不是"重放业务请求去拿 body"。 |
| **"拿完整 body"已有正解** | 前台 `context.route` 同步取得 `Response` → `CaptureSink` 在业务线程（main）于句柄存活期**就地读取**并留存（`BodyStore` 内存/落盘分层）。轮询线程**零协议调用**（2026-10-11 定稿）。 |
| **因此后台重放只剩两类残余动机** | ① body 过大（已由 `BodyStore` 分层解决）；② 句柄在被读取前被驱动回收。后者的正解是**更早读取 / 延迟唤醒**，而不是重放。 |

**结论：本框架当前不构建后台重放。** 本文是"若将来确需开工"时的**准入约束**，
而非待办任务。开工前必须重读本文并通过 §7 的前置条件。

---

## 1. 为什么不能随意 fetch

### 1.1 重放会改变服务端状态

| 方法 | 典型操作 | 自动重放风险 |
|---|---|---|
| GET | 查询 | 缓存穿透、日志放大、接口限流 |
| **POST** | 创建订单 | **重复创建业务数据** |
| **PUT** | 全量更新 | **覆盖其他用户或并发操作的结果** |
| **PATCH** | 部分更新 | **重复扣减、状态错乱** |
| **DELETE** | 删除 | **误删生产资源** |
| HEAD | 检查资源 | 风险较低，仍受服务端限制 |
| OPTIONS | 预检 | 风险较低，可能暴露内部路径 |

真实链：页面提交一次 `POST /orders` → 后台采集再 `POST /orders` →
监控系统的"观测行为"直接生成了第二笔订单。

### 1.2 请求不是幂等的

HTTP 方法不等于幂等。即便 PUT 在理论上可重复，实际接口仍可能：生成新业务单号、增加余额、
发送短信、更新审计记录、触发消息队列、生成支付单、返回 409/412。
**因此不能用"PUT/PATCH/DELETE 天然可重放"作为策略依据。**

### 1.3 认证状态难以完全一致

后台 fetch 可能：使用旧 token、使用监控账户、缺少设备指纹、缺少动态签名、缺少会话上下文、
重新生成 CSRF、被风控判定异常。结果可能是 200 但内容不同、401/403、429、
触发账户封禁、返回兜底错误页，**或服务端实际执行了副作用**。

### 1.4 可能产生自循环

后台 fetch → 匹配全量 route → 再次采集 → 再次 fetch → 再次匹配。
必须打破循环：固定采集标记 header + 后台流量排除规则 + 独立请求上下文 + 域名白名单 + 明确停止条件。

---

## 2. 三层分类

### 第一层：禁止重放（只保存元数据）

非幂等方法（POST/PUT/PATCH/DELETE）；标记 `Idempotency-Key` 但未获业务确认的写操作；
金融交易、支付、扣款、退款；订单创建/发货/取消；短信、邮件、推送；账号注册、密码修改、权限变更；
文件删除；含文件上传；超过 body 上限；不属于白名单域名；命中敏感路径（`/internal/`、`/admin/`、`/payments/`）；
含敏感参数（身份证、银行卡、手机号）；**已重放过的请求**；
**需要会话/鉴权才能产出有效响应的接口**（见 §3.3，新增）。

### 第二层：只读重放

允许条件（全部满足）：GET/HEAD/显式声明可重放的查询接口 + 白名单域名 + 明确响应类型 +
明确参数类型 + 明确最大 body + 有超时 + 有频率限制 + 有最大并发限制。

### 第三层：采样重放

生产环境不宜全量重放：`100% 记录元数据，按域名/端点定比例重取`。
采样结果必须进快照：是否允许重放、是否命中采样、采样比例、重放原因、实际重放结果。

> **⚠ 采样与断言互斥**（新增，见 §6.2）：采样是随机的 ⇒
> `expectReplaySnapshot(true)` 本质**不可断言**（同一份测试会因运气 flaky）。
> 采样开启时后台快照只能进观察/报告，**不能进硬断言**；
> 只有 `expectReplaySnapshot(false)`（确定性）可断言。

---

## 3. 请求构造

### 3.1 不要原样复制全部 header

必须过滤：`host`、`content-length`、`connection`、`accept-encoding`（除非底层能解码）、
`cookie`（默认去除）、`authorization`（默认去除）、`x-csrf-token`、`x-forwarded-*`、
自定义签名 header、内部 tracing header。

可保留：`accept`、`content-type`、明确的业务只读 header、显式声明的透传 header。
并注入标记：`x-traffic-capture: replay`、`x-traffic-snapshot-id: <id>`。

### 3.2 body 限制

超过 `maxRequestBytes` 直接拒绝（`ReplayDecision.BODY_TOO_LARGE`），不抛向调用方。
上传请求默认不重放；即便业务要求，也应替换测试文件、使用预置资源、限制大小、明确测试目标、
**不与生产对象存储混用**。

### 3.3 ★ 禁止使用共享的 `BrowserContext.request()`（新增，最高优先级）

这是原策略最危险的一处模糊。策略原文写"独立 context **或** 独立 APIRequestContext"，
极易被实现成前者，而两者安全性完全不同：

| 形态 | cookie jar | `Set-Cookie` 影响 | 结论 |
|---|---|---|---|
| `BrowserContext.request()` / `Page.request()` | **与浏览器共享** | **回写 context，影响后续 page 导航与登录态** | ⛔ **禁止**用作重放 |
| `playwright.request()` 级独立 `APIRequestContext` | 独立 | 不回写被测 context | ✅ 唯一可接受 |

共享形态下一次重放的 `Set-Cookie` 就能把正在跑的用例踢下线。

**对应后果（见第一层新增项）**：既然鉴权 header 默认去除，
那么**需要会话才能产出有效响应的接口一律不重放** —— 剥掉凭据后回来的 401/403/兜底页
没有任何审计价值，却付出了全部副作用风险。

---

## 4. 实现约束

### 4.1 独立上下文 + 有界并发

- 必须用 §3.3 的独立 `APIRequestContext`；执行器必须有界（有界队列 + 拒绝即丢弃），
  **队列满必须快速失败，绝不阻塞 route 事件线程**。
- **绝不**在重放路径上新建线程池 —— 复用框架既有有界池（`AsyncPool`）与统一的 `AbortPolicy` 语义。
  理由：本框架刚删除一个空转 IO 线程池（`RouteIoExecutor`），新建池会原样重蹈"配置齐全但无人调用"的覆辙。
- `submitBackgroundReplay()` 抛异常也**不能**阻止 `route.resume()`。

### 4.2 ★ 驱动器前置条件（框架特有，新增）

- `DriverFailureSignal.reported()` 置位（驱动连接已不可信）⇒ **不得启动任何重放**，
  也不得经由被标脏的连接发起调用。
- 重放不得影响 `GuardedDriverCall` 的单线程收口与毒化重置判定。

### 4.3 后台快照不得冒充前台业务响应

重放结果固定标记 `CaptureStage.BACKGROUND_REPLAY`，
**绝不覆盖**前台 `CaptureStage.ROUTE_SYNC` 快照；聚合时只能按
`backendAttempt.causeRequestId = browserRequest.requestId` 建立关系，不能直接合并列表。

---

## 5. 采样与审计

### 5.1 必须保存的策略证据

策略版本、域名是否白名单、方法是否允许、路径是否命中黑名单、body 是否超限、
是否命中采样、采样比例、实际是否发起重放、重放使用的 header、是否去除 cookie、
是否去除 authorization、是否独立 context、重放耗时、重放结果、未重放原因。

否则无法回答："为什么生产环境有的 API 有后台 body，有的没有？"

### 5.2 决策枚举（替代 boolean + 抛异常的双机制）

原策略 §2.2 的 `canReplay` 返回 boolean、§4.1 又抛 `ReplayRejectedException`，机制自相矛盾。
统一用：

```java
public enum ReplayDecision {
    ALLOWED, METHOD_NOT_ALLOWED, HOST_NOT_ALLOWED, PATH_BLOCKED, BODY_TOO_LARGE,
    NOT_SAMPLED, REDIRECTED, STREAMING, SENSITIVE_DATA,
    CAPTURE_MARKER_DETECTED, POLICY_DISABLED, SESSION_REQUIRED, DRIVER_UNUSABLE
}
```

`ReplayDecision decide(RequestSnapshot)` 返回决策，调用方据此记审计 ——
天然满足"每次重放都有原因"的要求。

### 5.3 ★ 审计自身必须有界（新增）

每条快照记录十余项策略证据 ⇒ 元数据会随快照无限增长。必须：
沿用 `CaptureSink` 的 `maxCaptured` 驱逐口径、统一走 `SensitiveDataSanitizer` 掩码、
DEBUG 之外不打 body。

---

## 6. 与等待 API 的衔接

### 6.1 两个不同条件

```
captured.waitForRouteSnapshot(page, 10s).assertBusinessResponse();   // 前台
captured.waitForReplaySnapshot(page, 15s).assertCompleteBody();      // 后台
```

策略明确不重放 ⇒ 不应调用 `waitForReplaySnapshot()`，不应断言后台 body 一定存在，
测试应显式声明 `expectReplay=false`。**否则测试会因策略失败，而非产品缺陷失败。**

### 6.2 推荐 DSL（含不可断言约定）

```java
ApiCaptureSpec.builder()
        .url(url)
        .expectRouteSnapshot(true)
        .expectReplaySnapshot(false)   // 采样模式下唯一可断言的值
        .build();
```

### 6.3 与已落地的采集序号门槛配合

本框架已提供 `ApiCaptureContext.capturedApiSequence()` / `waitForApiSince(seq, pred, ms)`：
在触发动作前取序号、`+1` 作门槛，即可只匹配本次动作之后产生的采集，
消除"上一轮导航的迟到响应满足本轮等待"的过期命中。**后台重放若实现，必须沿用同一套序号门槛**，
不得另起一套宽松匹配。

---

## 7. 开工前置条件（缺一不可）

1. 有明确的、前台 route 采集**无法解决**的需求（目前未出现）。
2. §3.3 的独立 `APIRequestContext` 可获取且与被测 context 无 cookie 关联。
3. 策略、决策枚举、审计入口、配置文件全部就绪，**默认全关**（连读操作也默认 0%）。
4. 通过 §8 全部验收项。
5. 复杂度不超过"直接在前台延长读取窗口"这一替代方案的收益。

---

## 8. 验收清单

- [ ] 默认禁止重放 POST / PUT / PATCH / DELETE
- [ ] 写操作**不能**通过配置"一键全开"
- [ ] 后台 fetch 有域名白名单与路径黑名单
- [ ] 默认去除 cookie 与 authorization，且重放 header 可明确授权
- [ ] **不得使用共享 `BrowserContext.request()`**（§3.3）
- [ ] 请求体 / 响应体均有大小上限
- [ ] 后台请求有独立超时、最大并发；队列满立即丢弃且不阻塞 route
- [ ] `DriverFailureSignal` 置位时不发起重放（§4.2）
- [ ] 后台流量**不会**再次进入全量 route（自循环护栏）
- [ ] 每次重放都有原因（决策枚举）与策略版本
- [ ] 后台快照不覆盖前台业务快照
- [ ] 审计数据有界且已脱敏（§5.3）
- [ ] 测试显式声明是否期望后台重放；采样模式下后台快照不进硬断言
- [ ] 生产环境先保守采样，再逐步放开
- [ ] 重放失败计入**采集状态**，不当业务断言失败
- [ ] 后台重放不触发清理、支付、发送、删除等副作用

---

## 9. 最终结论

**不能随意 fetch。** 正确姿势是：

```
前台 route 采集浏览器真实响应（本框架已实现）
  + 不可变快照 / 采集序号门槛（本框架已实现）
  + 白名单 + 黑名单 + 采样策略
  + 独立、无 cookie 关联的 APIRequestContext
  + 受限 header / body / 并发 / 超时
  + 后台重取快照（标记 BACKGROUND_REPLAY）
  + 后台 body 审计
```

不能做的事：因为"要拿完整 body"就重放所有 API；因为接口是 PUT/DELETE 就认为可重放；
把后台响应替换浏览器真实响应；把后台失败当业务缺陷；让后台流量重新进入全量 route；
用同步 `future.get()` 等待后台 fetch 再放行浏览器请求。
