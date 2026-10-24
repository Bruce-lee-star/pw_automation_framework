---
id: DOC-MOD-API-0001
title: pw-api-core 模块说明
type: module
status: active
owner: team-automation
created: 2026-10-22
updated: 2026-10-22
applies_to: pw-api-core
tags: [module, api, http]
supersedes: []
superseded_by: null
---

# pw-api-core

> 数据驱动 API 测试层：Entity + Endpoint 配置模型 + HTTP 请求执行 + 内建 JsonPath / JSON Schema 断言。

## 1. 定位

`pw-api-core` 提供与 Web 层完全解耦的 API 测试能力。用例通过 **Entity（实体）+ Endpoint（端点）** 两个配置维度组织数据，断言直接内建在步骤对象上，不需要额外的断言库或 JSON 处理样板。

## 2. 在架构中的位置

```text
     pw-api-core
         |
      pw-core
```

| 项 | 内容 |
|---|---|
| 被谁依赖 | 无框架内上层模块（`test-automation` 直接使用） |
| 依赖谁 | `pw-core` |
| 包名 | `com.hsbc.cmb.hk.dbb.automation.framework.api` |
| 分层约束 | 不得依赖 `pw-web-ui` / `pw-route` |

### 主要子包

| 子包 | 职责 |
|---|---|
| `core.step` | `BaseStep`（API 操作主入口）、`BaseStepFactory`、`ApiTestContext` |
| `core.entity` | `Entity` 统一请求/响应载体、`EntityBuilder` |
| `core.endpoint` | `EndpointProvider` 按 endpointName + method 解析端点配置 |
| `core.schema` | `JsonSchemaValidator` 响应 Schema 校验 |
| `core.services` | `TestServices`——`BaseStep` 的唯一创建入口（thread-scoped） |
| `client` / `client.rest` | `ApiJob`、`RestJobProvider`（各 HTTP 动词发起点）、`AbstractRestJob`（重试循环） |
| `config` | `ApiFrameworkConfig`（API 域配置门面，代理 `ConfigKeys.API_*`） |
| `assembler` | 请求头装配器 |
| `security` / `logging` | `SecurityAudit`、`SanitizingPrintStream`、`ApiLogSanitizer` |
| `utility` | `FileReader`、`JsonUtils`、`EnvironmentUtils` |

## 3. 引入

```xml
<dependency>
    <groupId>com.hsbc.cmb.hk.dbb.automation</groupId>
    <artifactId>pw-api-core</artifactId>
    <version>${framework.version}</version>
</dependency>
```

**前置要求**：JDK 21+。无浏览器依赖。

## 4. 最小可用示例

```java
import com.hsbc.cmb.hk.dbb.automation.framework.api.core.services.TestServices;
import com.hsbc.cmb.hk.dbb.automation.framework.api.core.step.BaseStep;
import com.hsbc.cmb.hk.dbb.automation.framework.api.config.ApiFrameworkConfig;

// BaseStep 构造为 package-private，唯一创建入口是 TestServices
BaseStep step = TestServices.initialize()
        .withEntity("payments")
        .withEnv("test")
        .baseStep();

// 发起请求（getFullUrl 继承自 RestJobProvider）
step.getFullUrl("/api/v1/payments");

// 断言
step.verifyResponseStatusCode(200);
step.verifyResponseJsonPath("data.status", "OK");
step.verifyJsonArrayLength("data.items", 3);
step.verifyResponseHeader("Content-Type", "application/json");
step.verifyResponseBodyContains("\"code\":0");
step.verifyResponseMatchesSchema("schemas/payment.json"); // classpath 资源

// 配置读取
int connTimeout = ApiFrameworkConfig.getConnectionTimeout(); // 默认 30000
int retries = ApiFrameworkConfig.getRetryCount();            // 默认 3

// 线程收尾：释放 ThreadLocal 引用
TestServices.clear();
```

**预期结果**：请求发出后各断言方法对响应做校验，任一不匹配即抛断言错误；`TestServices.clear()` 释放本线程的步骤对象。

## 5. 核心 API 速查

| 类型 | 作用 | 何时使用 |
|---|---|---|
| `TestServices` | API 测试服务入口，`initialize()...baseStep()` 链式创建 | 创建步骤对象（**唯一入口**） |
| `BaseStep` | 请求发起 + 断言语义主入口 | 编写 API 步骤定义 |
| `RestJobProvider` | 各 HTTP 动词发起点（`getFullUrl` / `postFullUrl` / `putFullUrl` / `patchFullUrl` / `deleteFullUrl`） | 发起不同方法的请求 |
| `EndpointProvider` | 按 endpointName + method 解析端点配置 | 端点来自配置而非硬编码 |
| `ApiFrameworkConfig` | API 域配置门面 | 读取 `http.*` / `api.*` / `test.retry.*` |
| `JsonSchemaValidator` | 响应 JSON Schema 校验 | 校验响应结构契约 |
| `Entity` / `EntityBuilder` | 统一请求/响应载体 | 组织多实体数据 |

## 6. 配置

| 键 | 默认值 | 说明 |
|---|---|---|
| `http.connection.timeout` | `30000` | 连接超时（毫秒） |
| `http.socket.timeout` | `30000` | 读取超时（毫秒） |
| `http.socket.timeout.fallback` | `15000` | 读取超时回退值 |
| `http.connection.pool.max-total` | `200` | 连接池总上限 |
| `http.connection.pool.max-per-route` | `20` | 单路由连接上限 |
| `http.ssl.relax-validation` | `false` | 是否放宽 SSL 校验（**仅限本地调试**） |
| `json.fail-on-unknown-properties` | `false` | 反序列化时遇到未知字段是否失败 |
| `json.accept-single-value-as-array` | `true` | 单值是否可当数组处理 |
| `api.base.uri.default` | `http://localhost` | 默认 base URI |
| `test.retry.count` | `3` | 失败重试次数 |
| `test.retry.delay` | `1000` | 重试间隔（毫秒） |
| `api.request.response.logging.enabled` | `false` | **默认关闭**的请求/响应日志 |
| `framework.output-directory` | `target/site/serenity` | 报告输出目录 |

> 另有本模块自有的配置**路径片段**常量（`domain/enums/ConfigKeys`）：`api.base.uri`、`end-points`、`payload`、`schemas`、`query-params`、`path-params`、`headers` 等。

## 7. 边界与非目标

- **不负责**浏览器——纯 HTTP 层，与 `pw-web-ui` 无依赖。
- **不负责**网络拦截——`mock` / `modify` / `delay` 属 `pw-route`。
- **不负责**编排——只提供"发请求 + 断言"的能力，编排由 `test-automation` 的步骤定义完成。
- **不允许** `new BaseStep(...)`——构造为 package-private，必须经 `TestServices`。

## 8. 测试与验证

| 类型 | 位置 |
|---|---|
| 单元测试 | `pw-api-core/src/test/java/`（`BaseStepJsonPathTest`、`BaseStepSchemaTest`、`AbstractRestJobRetryLoopTest` 等） |

```bash
# 在仓库根目录执行
mvn -pl pw-api-core -am test
```

## 9. 延伸阅读

- [根 README 模块表](../README.md#模块)
- [文档总索引](../docs/README.md)
