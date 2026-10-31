---
id: DOC-MOD-ROUTE-0001
title: pw-route 模块说明
type: module
status: active
owner: team-automation
created: 2026-10-22
updated: 2026-10-22
applies_to: pw-route
tags: [module, route, network]
supersedes: []
superseded_by: null
---

# pw-route

> 基于 Playwright 路由拦截的四能力网络治理层：观测断言 / 伪造 / 请求改写 / 延迟，附请求响应采集回放。

## 1. 定位

`pw-route` 把"控制网络行为"变成**声明式 DSL**。一条规则表达"拦截这个接口、断言这个响应"，或"拦截这个请求、改一个字段再放行"；规则的注册与撤销由框架按 scenario 生命周期自动管理。

## 2. 在架构中的位置

```text
     pw-route
         |
      pw-core
```

| 项 | 内容 |
|---|---|
| 被谁依赖 | 无框架内上层模块（`test-automation` 直接使用；`pw-web-ui` 通过 `ApiCaptureContext` 桥接） |
| 依赖谁 | `pw-core` |
| 包名 | `com.hsbc.cmb.hk.dbb.automation.framework.route` |
| 分层约束 | `RouteArchitectureTest`（ArchUnit） |

### 主要子包

| 子包 | 职责 |
|---|---|
| `dsl` | **`RouteDsl` 流式 DSL**、`ApiSpec`（规则规格 + Builder）、`RouteCapability`（能力枚举与优先级）、`BodyOp` |
| （根包） | `RouteEngine`（门面）、`RouteRuntime` / Impl、`RouteConfig`、`RouteCapturePolicy`、`RouteRuntimeFactory` |
| `binding` | `RouteBinder` 把 `ApiSpec` 绑到 Playwright route，含注册失败降级 |
| `claim` | `RouteClaim` / `ClaimRegistry` / `PendingGuard`：在途请求 claim 与挂起保护 |
| `monitor` | `CaptureSink`、`MonitorSink`、`CapturedApiCall`、`CapturedBody`、`JsonPathAssertor`、`CaptureLimits`、`BodyStore` |
| `dispatch` | `RouteDispatcher`：一次请求内 DELAY→MODIFY→MOCK→MONITOR 的统一时序编排 |
| `modify` | `RequestBodyModifier`：请求体 JsonPath / form 改写 |
| `exec` | `GuardedRunner`（同步 fetch 有界执行）、`RouteDelayScheduler`、`RouteRetireExecutor` |
| `diag` | `HangWatchdog`、`OriginResolver` / `RequestOrigin`、`CoverageScope` |
| `lifecycle` | `RouteLifecycleImpl`、`RouteAssertionProbeImpl`、`ApiCaptureQueryImpl`（对 core SPI 的实现） |

## 3. 引入

```xml
<dependency>
    <groupId>com.hsbc.cmb.hk.dbb.automation</groupId>
    <artifactId>pw-route</artifactId>
    <version>${framework.version}</version>
</dependency>
```

**前置要求**：JDK 21+；需要 Playwright `BrowserContext` 或 `Page` 句柄。

## 4. 最小可用示例

```java
import com.hsbc.cmb.hk.dbb.automation.framework.route.dsl.RouteDsl;
import com.hsbc.cmb.hk.dbb.automation.framework.route.monitor.CapturedApiCall;
import com.microsoft.playwright.BrowserContext;
import java.util.List;

BrowserContext context = /* 已有的 Playwright context */;

// MONITOR：断言真实响应（fail-open，不影响业务请求）
AutoCloseable monitor = RouteDsl.on(context)
        .api("/api/users/**")
        .onlyApi()
        .monitor()
        .expectStatus(200)
        .expectJsonPath("$.code", 0)
        .timeout(30)          // 单位：秒
        .register();

// MOCK：静态伪造响应
RouteDsl.on(context)
        .api("/api/login")
        .onlyApi()
        .matchMethod("POST")
        .mock()
        .status(200)
        .body("{\"token\":\"t\"}")
        .contentType("application/json")
        .register();

// MODIFY_REQUEST：改请求头后放行
RouteDsl.on(context)
        .api("/api/user")
        .modifyRequest()
        .setRequestHeader("X-Env", "mock")
        .register();

// DELAY：延迟后放行（单位：秒）
RouteDsl.on(context).api("/api/pay/**").delay(3).done().start();

// 采集消费：dump 为消费式（第二次返回空），peek 为非消费式
List<CapturedApiCall> calls = RouteDsl.dumpCaptured(context);
List<CapturedApiCall> peek = RouteDsl.peekCaptured(context);

// 精确注销 / 清理
monitor.close();
RouteDsl.clearRules(context); // 只清规则，保留 runtime 与 Context
RouteDsl.resetAll();          // 关闭全部运行时
```

**预期结果**：被拦截的接口按声明行为响应；`dumpCaptured` 返回采集到的调用记录。

> **单位注意**：`MonitorDsl.timeout(long)` 与 `CapabilityDsl.delay(long)` 的单位是**秒**（内部 ×1000 转毫秒）。`expectStatus` / `expectJsonPath` 只存在于 `MonitorDsl` 段。

## 5. 核心 API 速查

| 类型 | 作用 | 何时使用 |
|---|---|---|
| `RouteDsl` | 主入口：流式构建四种能力规则并注册 | 声明网络行为 |
| `RouteEngine` | 运行时门面：按 context 取/关 runtime、清规则、采集查询 | 需要精细控制时 |
| `RouteRuntime` | per-context 运行时契约 | 自定义集成 |
| `RouteConfig` | 运行时不可变配置（挂起额度 / IO 并发 / 采集上限） | 调整运行时行为 |
| `RouteCapturePolicy` | 采集策略的唯一解析点，构造期**冻结**快照 | 了解采集边界 |
| `CapturedApiCall` | 采集结果记录 | 断言与回放 |
| `RouteCapability` | 四种能力的优先级 / 时序 / terminal 语义唯一来源 | 理解能力交互 |

## 6. 配置

| 键 | 默认值 | 说明 |
|---|---|---|
| `route.capture.all.type` | `false` | `false` 只采 MONITOR；`true` 连 DELAY/MODIFY/MOCK 一并采 |
| `route.capture.inline.max.bytes` | `262144` | 响应体内联阈值（256 KiB） |
| `route.capture.gzip.max.bytes` | `8388608` | 文本型 gzip 上限（8 MiB），超出落盘 |
| `route.monitor.assert.body.max.bytes` | `2097152` | MONITOR 响应体**断言**上限（2 MiB），超限降级为仅校验 status/headers |

> **取值优先级例外**：以上四键为**环境变量 > 系统属性 > 配置文件 > 默认值**（由 `ConfigReader.ENV_FIRST_PRECEDENCE` 声明），与其余键（系统属性优先）语义不同。

## 7. 边界与非目标

- **不负责**发起真实业务请求——只拦截、观测、伪造。
- **不负责**页面交互——属 `pw-web-ui`。
- **不跨 scenario 保留规则**——Web 层每 case 重建 Context，V2 挂点会关闭整个 per-context 运行时；需保留 Context 只清规则时用 `clearRules(context)`。
- **不做**非 HTTP 协议拦截——仅覆盖 Playwright route 能力范围。

## 8. 测试与验证

| 类型 | 位置 |
|---|---|
| 单元测试 | `pw-route/src/test/java/`（71 个，含 `RouteArchitectureTest`、`RouteCapturePolicyTest`、`MockFidelityTest`） |

```bash
# 在仓库根目录执行
mvn -pl pw-route -am test
```

## 9. 延伸阅读

- [Playwright Monitor / Mock / Delay / Modify 修复方案](../docs/architecture/Playwright_Monitor_Mock_Delay_Modify_修复方案.md)
- [Playwright 全量 API 监控与 waitForCondition 方案](../docs/architecture/Playwright全量API监控与waitForCondition方案.md)
- [根 README 模块表](../README.md#模块)
