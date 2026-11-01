# Automation WebUI Framework for DBB

企业级 Web UI / API 自动化测试框架：**Playwright** 驱动浏览器、**Serenity BDD + Cucumber** 组织用例与报告，
多模块 Maven 工程，内置声明式网络拦截（monitor / mock / modify）、日志脱敏、并发执行与分层架构守卫。

> 本文件中的**技术栈版本**由 `tools/check-doc-drift.ps1`（CI：`.github/workflows/doc-drift-check.yml`）
> 与根 `pom.xml` 的 `<properties>` 逐项比对，**漂移即失败**。升级依赖后请同步本表，或执行
> `pwsh tools/check-doc-drift.ps1 --fix` 自动对齐。

## 技术栈

| 组件 | 版本 |
|---|---|
| JDK | 21 |
| 构建 | Maven（多模块） |
| Playwright for Java | 1.62.0 |
| Serenity BDD | 4.3.4 |
| Cucumber | 7.34.9 |
| JUnit | 4.13.2 |
| Logback | 1.5.34 |
| Typesafe Config | 1.4.5 |
| Gson | 2.14.0 |
| JsonPath（Jayway） | 2.9.0 |
| Jackson | 2.22.1 |
| json-smart | 2.5.2 |
| FreeMarker | 2.3.34 |
| JaCoCo | 0.8.12 |
| Checkstyle（plugin） | 3.6.0 |
| SpotBugs | 4.9.8.5 |

## 模块

| 模块 | 说明 |
|---|---|
| `core` | 基础层：`framework.common`（配置 / 安全脱敏 / 异步池 / 关闭协调 / route SPI）+ 顶层工具与重试；**被所有模块依赖** |
| `reporting` | 报告层：`framework.common.reporting`（`SerenityReporter`）+ 顶层 `report`（汇总报告 `SummaryReportGenerator`、趋势、trace 索引、ZIP 打包） |
| `api` | API / HTTP 测试层：`framework.api`（连接池、幂等重试、JSONPath / JSON Schema 校验、出口脱敏） |
| `web` | Web / UI 层：`framework.web`（PageObject / RoleElement、并发 Context 执行器、浏览器生命周期、截图 / trace）；**不含 `route`** |
| `pw-codegen-v3` | 元素拾取与代码生成（唯一受支持版本）：RolePicker、NLS 名称翻译、`RoleElementPageGenerator` |
| `route` | 网络拦截层：`RouteDsl` 声明式 **monitor / mock / modify / delay**，并发安全分发、按目的撤销与响应采集 |
| `test-automation` | 测试工程：features、step definitions、`CucumberTestRunnerIT`；聚合全部框架模块 |

依赖方向：`pw-reports-v2` / `pw-api-core` / `pw-route` → `pw-core`；`pw-web-ui` → `pw-core` + `pw-reports-v2`；`pw-codegen-v3` → `pw-web-ui`；`test-automation` 聚合。
分层与依赖规则由 ArchUnit 守卫（`test-automation` 的 `ArchitectureTest` / `LayeringArchTest`）。

## 快速开始

前置：JDK 21、Maven 3.9+、Playwright 浏览器。

```bash
# 1) 安装 Playwright 浏览器（首次；Playwright Java CLI，识别 PLAYWRIGHT_BROWSERS_PATH）
mvn exec:java -Dexec.mainClass=com.microsoft.playwright.CLI -Dexec.args="install chromium"

# 2) 构建 + 全量校验（CI 形状：含 SpotBugs / Checkstyle / 汇总报告）
mvn -B verify -DskipITs

# 3) 仅跑测试工程（自动带上各框架模块）
mvn -pl test-automation -am test
```

### 常用开关

| 场景 | 用法 |
|---|---|
| 引擎级并行（默认串行） | `-Pparallel -Dparallelism=4 -DmaxPoolSize=8` |
| 环境选择 | 由 Gherkin 步骤 `logon DBB "<env>"` 经 `resolveEnv` 运行时解析（旧 `-Psit1/2/3` Maven profile 已移除） |
| 标签过滤 | `-Dtags="not @skip"`（默认即 `not @skip`，按需收窄为 `@smoke` / `@regression` 等） |
| 依赖漏洞门禁 | `-Psca`（OSV CVE 门禁所需） |

## 配置体系

取值优先级：**系统属性 > 环境变量 > `-P` profile 的 `environment-*.conf` > `serenity.conf` / `serenity.properties` > 代码默认值**。

> 例外：route 采集三键（`route.capture.all.type` / `route.capture.inline.max.bytes` / `route.capture.gzip.max.bytes`）
> 为 **环境变量 > 系统属性 > 配置文件 > 默认值**，经 `ConfigRegistry` 的**声明式层序**
> （`ConfigReader.ENV_FIRST_PRECEDENCE`）声明；其余键仍为系统属性优先，语义不变。

配置键的**唯一事实来源**是 `core` 的
`com.hsbc.cmb.hk.dbb.automation.framework.common.config.ConfigRegistry`（当前 **218** 个键）：
Web / API / Monitor 三侧配置类（`WebFrameworkConfig` / `ApiFrameworkConfig` / `MonitorConfig`）均为
**引用它的门面**，不再各自持有 key / 默认值字面量。新增配置键必须在此登记，并由三重守卫把关：

- **重复键**：类加载期 fail-fast（`ConfigRegistry.validateUniqueKeys()`）；
- **默认值不漂移**：golden 快照以迁移前基线逐键逐字比对；
- **门面不得私藏定义**：`ConfigUnificationTest#facadesMustDelegateToRegistry`。

## 安全基线

- **凭据一律环境变量注入**（如 `DBB_TEST_PASSWORD`、`DBB_OTP_URL_*`、`CONFIG_MASTER_KEY`），禁止入库；
- 日志出口强制脱敏（`SanitizingPrintStream` / `SanitizingThrowableConverter`）；**API 请求 / 响应日志默认关闭**；
- 日志按天/大小压缩滚动（`logs/framework.log`，保留 7 天、总量上限 1 GB），并通过 MDC 关联场景；
- 截图默认「**仅失败步、非全页**」；报告推送前有凭据扫描门禁。

日志级别由 `logging.root.level` 唯一控制，支持 `TRACE`、`DEBUG`、`INFO`、`WARN`、`ERROR` 和 `OFF`。
例如设置 `-Dlogging.root.level=DEBUG`、环境变量 `LOGGING_ROOT_LEVEL=DEBUG`，或在框架配置文件中设置
`logging.root.level = DEBUG`，可开启 DEBUG 及以上级别的框架诊断日志；TRACE 同理。级别在框架启动时生效。
DEBUG 启动时会打印注册表中的框架配置生效值及其来源；密码、令牌等敏感值会脱敏。
报告中的浏览器动作录制使用独立开关 `framework.recording.enabled`（默认开启），与日志级别互不影响；
旧配置 `framework.logging=VERBOSE|TRACE|QUIET` 仍作为兼容回退。

> 说明：框架自身不依赖 Spring；Spring Boot 仅用于 `route-demo-service` 演示后端。

## 文档

| 位置 | 内容 |
|---|---|
| [文档总索引](docs/README.md) | **文档总索引** —— 全仓库文档的唯一入口 |
| [文档规范与模板库](docs/standards/README.md) | **文档规范与模板库** —— 目录约定、写作风格、元数据、门禁规则、评审清单 |
| [文档模板](docs/templates/README.md) | **文档模板** —— ADR、技术方案、模块 README、教程、操作指南、参考手册、Runbook、对齐规范 |
| [架构评审文档集](docs/architecture/Java-Playwright-1.62-网络API分层指南.md) | 总体分层与依赖治理、各模块评审、CI / 工程可维护性、问题修复任务清单。**注**：编号 01~13 的完整评审集不在本仓库（自 CT2-22 起未随代码落盘），如需追溯请查 git 历史 |

> **文档门禁**：`tools/check-doc-drift.ps1`（版本漂移、引用完整性、模块拓扑）与
> `tools/check_doc_standard.py`（元数据、目录类型、内链、占位符残留）均由 CI
> `.github/workflows/doc-drift-check.yml` 执行。文档改动与实现改动须在**同一个 PR** 内提交。
