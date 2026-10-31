---
id: DOC-REPO-0001
title: 贡献指南
type: standard
status: active
owner: team-automation
created: 2026-10-22
updated: 2026-10-22
applies_to: repo
tags: [contributing]
supersedes: []
superseded_by: null
---

# 贡献指南

> 本仓库是 **Playwright + Serenity BDD + Cucumber** 的企业级 Web UI / API 自动化测试框架。
> 本文说明如何提交代码与文档。**文档与代码同等重要**——没有文档的功能视为未完成。

## 1. 环境准备

| 依赖 | 版本 | 校验方式 |
|---|---|---|
| JDK | 21 | `java -version` |
| Maven | 3.9+ | `mvn -v` |
| Python | 3.9+（仅跑文档门禁需要） | `python3 --version` |

安装 Playwright 浏览器（首次）：

```bash
mvn exec:java -Dexec.mainClass=com.microsoft.playwright.CLI -Dexec.args="install chromium"
```

## 2. 分支与提交

- 分支命名：`<type>/<简述>`，`type` ∈ `feat` / `fix` / `docs` / `refactor` / `chore` / `test`。
- 提交信息采用 Conventional Commits 风格：`feat(web): 新增 SSO 会话隔离闸门`。
- 纯文档改动使用 `docs:` 前缀，例如 `docs(standards): 补充模块 README 最小信息集`。
- **一次提交只做一件事**——文档与实现放同一 PR，但不要混入无关重构。

## 3. 本地校验

提交前请在仓库根目录执行：

```bash
# 全量构建与校验（含 SpotBugs / Checkstyle / 汇总报告）
mvn -B verify -DskipITs

# 文档版本漂移（README 技术栈表 vs pom <properties>）
pwsh tools/check-doc-drift.ps1

# 文档规范门禁（元数据 / 目录类型 / 内链 / 占位符残留）
python3 tools/check_doc_standard.py --strict
```

`check_doc_standard.py` 会输出 `FAIL`（阻断）与 `WARN`（提示存量问题）两类结果。
`WARN` 数量多属正常——存量文档按 [S3 §4](docs/standards/03-metadata-and-lifecycle.md) 逐步收敛。

## 4. 代码规范

- **Checkstyle**（`checkstyle.xml`）与 **SpotBugs** 必须在本地通过。存量违规由 `checkstyle-baseline.xml` 祖父化，**基线只减不增**。
- 公共 API 需有 Javadoc，说明**契约与边界**，而非复述方法名。
- 禁用 `Thread.sleep`；等待一律走 `pw-web-ui` 的 `PageWaits` 或 Playwright 的自动等待。
- 任何日志输出必须经 `SanitizingPrintStream` / `SanitizingThrowableConverter`，禁止直接 `System.out.println` 明文敏感数据。

### 配置键变更

配置键的**唯一事实来源**是 `pw-core` 的 `ConfigRegistry`。新增键必须：

1. 在 `ConfigRegistry` 登记（重复键在类加载期 fail-fast）；
2. 由对应域的门面（`WebFrameworkConfig` / `ApiFrameworkConfig`）引用，**门面不得私藏字面量**；
3. 通过 golden 快照（默认值不漂移）与 `ConfigUnificationTest`（门面必须委派）；
4. 同步更新 [配置参考文档](docs/standards/03-metadata-and-lifecycle.md)。

### 分层依赖

分层由 ArchUnit 守卫（`ArchitectureTest` / `LayeringArchTest` / `RouteArchitectureTest`）。
依赖方向：`core` ← `reporting` / `api` ← `web`；`codegen` / `route` 独立。
**注意**：`pw-codegen` 的包名带 `framework.web` 前缀但**不在** `pw-web-ui` 内，两者通过 `RoleCodegenBridge` SPI 解耦。

## 5. 文档贡献

**代码没有文档等于没写。** 任何新功能、配置项、破坏性变更都必须附带文档。

| 你要写什么 | 去哪 | 用哪个模板 |
|---|---|---|
| 一个技术决策 | `docs/adr/` | [ADR 模板](docs/templates/template-adr.md) |
| 一项技术方案 | `docs/architecture/` | [技术方案模板](docs/templates/template-design-doc.md) |
| 模块说明 | `<module>/README.md` | [模块 README 模板](docs/templates/template-module-readme.md) |
| 上手教程 | `docs/usage/` | [教程模板](docs/templates/template-tutorial.md) |
| 任务指南 | `docs/howto/` | [操作指南模板](docs/templates/template-howto.md) |
| 配置/API 参考 | `docs/reference/` | [参考手册模板](docs/templates/template-reference.md) |
| 故障处置 | `docs/runbook/` | [Runbook 模板](docs/templates/template-runbook.md) |
| 跨仓库契约 | `docs/report-alignment/` | [对齐规范模板](docs/templates/template-alignment.md) |

完整规则见 [文档规范与模板库](docs/standards/README.md)：

- [S1 文档结构与命名](docs/standards/01-doc-structure-and-naming.md) —— 放哪里、叫什么名
- [S2 写作风格与内容](docs/standards/02-writing-style.md) —— 怎么写
- [S3 元数据与生命周期](docs/standards/03-metadata-and-lifecycle.md) —— 状态、版本、废弃
- [S4 质量门禁与自动检查](docs/standards/04-quality-gates.md) —— 怎么自动检查
- [S5 评审清单与责任分工](docs/standards/05-review-checklist.md) —— 谁审、审什么

### 硬性要求

1. **文档与实现在同一个 PR 内提交**；
2. 新文档**必须**带元数据头（`id` / `title` / `type` / `status` / `owner` / `created` / `updated` / `applies_to`）；
3. 新文档**必须**登记到 [文档总索引](docs/README.md)；
4. 代码示例**必须**能运行，且不含真实凭据；
5. 破坏性变更需先有迁移说明。

## 6. 评审

| 角色 | 关注点 | 是否必须 |
|---|---|---|
| 技术评审 | 与代码一致、技术结论成立 | **必须**（不能是作者本人） |
| 可读性评审 | 术语一致、表述清晰、链接可达 | 建议（作者可兼任但需说明） |
| 规范评审 | 符合 S1–S4 | 涉及规范变更时必须 |

评审意见请分级标注 `must` / `should` / `nit` / `question`，让作者能判断优先级。
详见 [S5 评审清单](docs/standards/05-review-checklist.md)。

## 7. 破坏性变更流程

1. 开 issue 说明影响面与迁移方案；
2. **先合入迁移文档**，再合入破坏性变更；
3. 更新 [CHANGELOG](CHANGELOG.md)（如已建立）与受影响文档的 `updated` 字段；
4. 通知使用方（`test-automation` 仓库维护者）。

## 8. 目录约定速查

| 路径 | 内容 |
|---|---|
| `pw-core` | 基础层：配置注册表、脱敏、断言、上下文、SPI 契约 |
| `pw-web-ui` | Web UI 层：Playwright 生命周期、Page Object、Cucumber 事件桥 |
| `pw-api-core` | API/HTTP 层：Entity + Endpoint、请求执行、断言 |
| `pw-route` | 网络拦截层：monitor / mock / modify / delay + 采集回放 |
| `pw-codegen` | 元素拾取与代码生成（SPI 解耦） |
| `pw-reports-v2` | 自建 Serenity 兼容报告 |
| `docs/` | 全部文档（见 [文档总索引](docs/README.md)） |
| `tools/` | 运维与门禁脚本 |

## 9. 常见问题

**Q：本地 `mvn verify` 失败但 CI 通过？**
检查是否漏了 `-DskipITs`（集成测试需浏览器环境，本地通常跳过）。

**Q：文档门禁报 `R1 missing YAML front matter`？**
新文档漏了元数据头。存量文档的豁免清单在 `docs/standards/legacy-baseline.txt`，只减不增。

**Q：文档门禁报 `R6 dangling link target`？**
链接指向了不存在的文件。改用真实路径，或确认目标是否被改名/删除。

**Q：能改 `docs/architecture/` 下的中文文件名吗？**
可以，但需同 PR 更新所有指向它的链接——内链门禁会验证。历史资产改名建议单独提交，便于评审。
