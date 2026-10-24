---
id: DOC-INDEX-0001
title: 文档总索引
type: index
status: active
owner: team-automation
created: 2026-10-22
updated: 2026-10-22
applies_to: repo
tags: [index]
supersedes: []
superseded_by: null
---

# 文档总索引

> 本仓库所有文档的唯一入口。找不到想看的文档时，先查这里；这里没有，说明该文档**尚未写**。
>
> 新增文档**必须**在此登记——登记本身就是一条规则，漏登记等于文档不存在。

## 按意图查找

| 你想做的事 | 去哪看 |
|---|---|
| 第一次跑通这个框架 | [快速开始（根 README）](../README.md#快速开始) |
| 了解某个模块能做什么 | [模块说明](#模块文档) |
| 查某个配置项的默认值 | `docs/reference/`（配置参考） |
| 了解某个技术决策为什么这样定 | `docs/adr/` |
| 理解一个机制怎么运作 | [架构与技术方案](#架构与技术方案) |
| 完成一个具体任务 | `docs/howto/` |
| 系统出问题了怎么办 | `docs/runbook/` |
| 了解怎么写文档 | [文档规范与模板库](standards/README.md) |

## 快速开始

- **[根 README](../README.md)** —— 技术栈版本、模块拓扑、配置体系、安全基线。
- **[文档规范与模板库](standards/README.md)** —— 文档怎么写、放哪里、怎么评审。
- **[模板库](templates/README.md)** —— 可直接复制的文档骨架。

## 模块文档

| 模块 | 说明文档 |
|---|---|
| `pw-core` | 见根 README 模块表 |
| `pw-web-ui` | 见根 README 模块表 |
| `pw-api-core` | 见根 README 模块表 |
| `pw-route` | 见根 README 模块表 |
| `pw-codegen` | 见根 README 模块表 |
| `pw-reports-v2` | [pw-reports-v2/README.md](../pw-reports-v2/README.md) |

## 架构与技术方案

| 文档 | 主题 |
|---|---|
| [Java Playwright 1.62 网络 API 分层使用指南](architecture/Java-Playwright-1.62-网络API分层指南.md) | 请求时序 × API 分层、线程铁律 |
| [Playwright 导航对全量 API 监控的影响与生命周期设计](architecture/Playwright导航对全量API监控的影响与生命周期设计.md) | 导航事件与监控 |
| [Playwright 全量 API 监控与 waitForCondition 方案](architecture/Playwright全量API监控与waitForCondition方案.md) | 监控与条件等待 |
| [Playwright Monitor / Mock / Delay / Modify 修复方案](architecture/Playwright_Monitor_Mock_Delay_Modify_修复方案.md) | 路由拦截能力修复 |
| [SSO 并发与会话隔离设计](architecture/SSO并发与会话隔离设计.md) | 并发与会话 |
| [后台 API 重放准入规则](architecture/后台API重放准入规则.md) | 重放准入 |
| [自建 Serenity 兼容报告方案](architecture/自建Serenity兼容报告方案.md) | 报告实现方案 |

## 框架与报告 / 用例层的契约

| 文档 | 主题 |
|---|---|
| [FrameworkListener SPI](report-alignment/A_frameworklistener_spi.md) | 框架侧扩展点契约 |
| [Scenario Outline 规范](report-alignment/B_scenario_outline.md) | 用例层写法规范 |

## 规范与模板

| 文档 | 主题 |
|---|---|
| [S1 文档结构与命名](standards/01-doc-structure-and-naming.md) | 放哪里、叫什么名 |
| [S2 写作风格与内容](standards/02-writing-style.md) | 怎么写 |
| [S3 元数据与生命周期](standards/03-metadata-and-lifecycle.md) | 状态、版本、废弃 |
| [S4 质量门禁与自动检查](standards/04-quality-gates.md) | 怎么自动检查 |
| [S5 评审清单与责任分工](standards/05-review-checklist.md) | 谁审、审什么 |

## 维护本文档

- 新增/删除/改名文档时，**同一次提交**更新本索引；
- `status: deprecated` 的文档保留在表中但标注「已废弃」；
- 索引由 `tools/check-doc-standard.ps1` 校验完整性（规则 W10）。
