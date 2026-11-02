---
id: DOC-MOD-CORE-0001
title: pw-core 模块说明
type: module
status: active
owner: team-automation
created: 2026-10-22
updated: 2026-10-22
applies_to: pw-core
tags: [module, core]
supersedes: []
superseded_by: null
---

# pw-core

> 所有业务模块的公共底座：配置键唯一注册表、脱敏、断言、per-scenario 上下文与跨模块 SPI 契约集。

## 1. 定位

`pw-core` 提供**不依赖任何业务域**的基础能力。`pw-web-ui` / `pw-api-core` / `pw-route` / `pw-reports` 全部依赖它，它自己不依赖任何其他框架模块——这保证底层不会反向依赖上层。

## 2. 在架构中的位置

```text
  pw-web-ui   pw-api-core   pw-route   pw-reports
        \          |            |           /
         \         |            |          /
          +--------+-----------+---------+
                   |
                 pw-core
```

| 项 | 内容 |
|---|---|
| 被谁依赖 | `pw-web-ui` / `pw-api-core` / `pw-route` / `pw-reports` / `pw-codegen`（间接） |
| 依赖谁 | 无框架内依赖（依赖版本由根 POM 管理） |
| 包名 | `com.hsbc.cmb.hk.dbb.automation.framework.{common,core}` |
| 分层约束 | ArchUnit 守卫 `core` 不得依赖任何业务域包 |

### 主要子包

| 子包 | 职责 |
|---|---|
| `common.config` | 配置键唯一注册表 `ConfigRegistry`、解析器 `ConfigReader`、`FrameworkFlags`、`MonitorConfig` |
| `common.security` | `SensitiveDataSanitizer` 脱敏、`ConfigCipher` 的 `ENC()` 密文解密 |
| `common.assertion` | `FrameworkAssertions` 统一断言、`SoftAssertions` 软断言聚合 |
| `common.result` | `TestResult` / `StepResult` 语义结果模型、`ResultReporters` 上报注册表 |
| `common.route` | Route 生命周期 SPI 契约（`RouteLifecycle` / `CaptureContext` / `EngineControl`） |
| `common.reporting` | 报告桥接契约（`ReportingBridge` / `ReportingBridgeRegistry`） |
| `common.rule` | 通用规则模型（`Rule` / `MatchStrategy` / `RuleRegistry`） |
| `common.async` | `AsyncPool` 共享线程池（带超时裁决与有界队列） |
| `core.context` | `TestContext` / `TestContextHolder` / `ContextKey` per-scenario 上下文 |
| `core.lifecycle` | `ShutdownCoordinator` 有序关闭协调 |

## 3. 引入

```xml
<dependency>
    <groupId>com.hsbc.cmb.hk.dbb.automation</groupId>
    <artifactId>pw-core</artifactId>
    <version>${framework.version}</version>
</dependency>
```

**前置要求**：JDK 21+。无浏览器依赖——`pw-core` 本身不引入 Playwright。

## 4. 最小可用示例

```java
import com.hsbc.cmb.hk.dbb.automation.framework.common.config.ConfigRegistry;
import com.hsbc.cmb.hk.dbb.automation.framework.common.config.ConfigReader;
import com.hsbc.cmb.hk.dbb.automation.framework.common.security.SensitiveDataSanitizer;
import com.hsbc.cmb.hk.dbb.automation.framework.core.context.ContextKey;
import com.hsbc.cmb.hk.dbb.automation.framework.core.context.TestContext;
import com.hsbc.cmb.hk.dbb.automation.framework.core.context.TestContextHolder;

// 读配置：键必须先在 ConfigRegistry 登记，未登记的键在类加载期就会 fail-fast
String timeout = ConfigReader.resolve(
        ConfigRegistry.WEB_PLAYWRIGHT_BROWSER_TIMEOUT.key(),
        ConfigRegistry.WEB_PLAYWRIGHT_BROWSER_TIMEOUT.defaultValue()); // "30000"

// 脱敏：统一出口，日志与报告共用
String safeUrl = SensitiveDataSanitizer.sanitizeUrl("https://h/token?password=x");

// per-scenario 上下文：替代散落的 static ThreadLocal，支撑并行执行
ContextKey<String> KEY = ContextKey.of("demo.key", String.class);
TestContext ctx = TestContextHolder.get();
ctx.set(KEY, "v1");
String v = ctx.get(KEY);
```

**预期结果**：`timeout` 为 `"30000"`（或环境变量/系统属性覆盖值）；`safeUrl` 中的凭据被替换为掩码；`v` 为 `"v1"`。

## 5. 核心 API 速查

| 类型 | 作用 | 何时使用 |
|---|---|---|
| `ConfigRegistry` | 全框架配置键唯一注册表（枚举，含 key / 默认值 / 描述） | 新增任何配置键时 |
| `ConfigReader` | 配置解析唯一实现，按声明层序取值 | 读取配置值 |
| `TestContext` / `TestContextHolder` | per-scenario 状态容器 | 跨步骤传递数据（替代 static ThreadLocal） |
| `ContextKey` | 类型安全的上下文键 | 定义 `TestContext` 的键 |
| `SensitiveDataSanitizer` | 敏感信息统一脱敏出口 | 记录任何可能含凭据的内容前 |
| `FrameworkAssertions` | 框架统一断言入口（抛 `FrameworkAssertionError`） | 框架内部断言 |
| `SoftAssertions` | 软断言聚合，一次收集多个失败 | 需要一次性报告多处失败 |
| `AsyncPool` | 带超时裁决的共享线程池 | 框架内异步任务 |
| `ShutdownCoordinator` | 有序关闭协调 | 资源清理编排 |

## 6. 配置

本模块**拥有** `ConfigRegistry` 注册表本身，其中以下族为本模块自有：

| 键 | 默认值 | 说明 |
|---|---|---|
| `async.core.threads` | `2` | 共享池核心线程数 |
| `async.max.threads` | `6` | 共享池最大线程数 |
| `async.queue.capacity` | `200` | 共享池有界队列容量 |
| `async.task.timeout.ms` | `30000` | 异步任务超时裁决 |
| `framework.security.secret.strict` | `true` | 严格模式：疑似凭据直接失败而非仅脱敏 |
| `monitor.db.store.enabled` | `false` | 是否把采集结果落库 |
| `monitor.body.read.max.wait.ms` | `30000` | 响应体读取最长等待 |
| `monitor.timeout.max.ms` | `300000` | 监控全局超时上限 |
| `monitor.file.store.dir` | `target/monitor-output` | 采集文件落盘目录 |

> **取值优先级**：系统属性 > 环境变量 > `-P` profile 的 `environment-*.conf` > `serenity.conf` / `serenity.properties` > 代码默认值。
> 例外：`route.capture.*` 三键为**环境变量优先**（由 `ConfigReader.ENV_FIRST_PRECEDENCE` 声明式声明）。
>
> 全量 216 个键见 `ConfigRegistry.java`；golden 快照逐键逐字比对默认值，任何漂移即失败。

## 7. 边界与非目标

- **不负责**任何业务域逻辑——配置门面（`WebFrameworkConfig` / `ApiFrameworkConfig`）只是 `ConfigRegistry` 的读取代理，不持有字面量。
- **不负责**浏览器操作——Playwright 相关能力在 `pw-web-ui`。
- **不负责**报告渲染——只定义 `ReportingBridge` 契约，实现在 `pw-reports`。
- **不持有**任何 `static` 可变状态（除 `ConfigRegistry` 枚举与只读注册表），以保证并行安全。

## 8. 测试与验证

| 类型 | 位置 |
|---|---|
| 单元测试 | `pw-core/src/test/java/`（`AsyncPoolTimeoutReaperTest`、`RuleRegistryTest`、`ShutdownCoordinatorTimeoutTest` 等） |
| 配置守卫 | golden 快照、`ConfigUnificationTest`（位于 `test-automation`） |

```bash
# 在仓库根目录执行
mvn -pl pw-core -am test
```

## 9. 延伸阅读

- [根 README 模块表](../../README.md#模块)
- [S1 文档结构与命名规范](../standards/01-doc-structure-and-naming.md)
- [架构评审文档集：Playwright API 分层](../architecture/Java-Playwright-1.62-网络API分层指南.md)
