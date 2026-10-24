---
id: DOC-STD-0000
title: 文档规范与模板库
type: standard
status: active
owner: team-automation
created: 2026-10-22
updated: 2026-10-22
applies_to: repo
tags: [standards, index]
supersedes: []
superseded_by: null
---

# 文档规范与模板库

> 本目录是 `pw_automation_framework` 仓库文档的**规范性来源**（Normative Source）。
> 规范用词含义：
> - **必须 / MUST**：强制要求，违反即视为文档缺陷，可由 CI 门禁拦截；
> - **应当 / SHOULD**：强烈建议，偏离需在 PR 中说明理由；
> - **可以 / MAY**：可选实践。

## 1. 为什么需要这份规范

本仓库同时存在四类文档载体，其质量落差一直很大：

| 载体 | 现状 | 主要问题 |
|---|---|---|
| `README.md` | 顶层门面，质量高 | 版本表需人工同步（已有 `tools/check-doc-drift.ps1` 兜底） |
| `docs/architecture/*.md` | 技术方案深度好 | 缺元数据、缺状态标记、无法判断是否已被实现 |
| `docs/usage/index.html` | 单文件 1300+ 行使用指南 | 无版本概念、无目录约定、难以增量维护 |
| `docs/report-alignment/*.md` | 规范+示例合一 | 规范与示例混排，读者难以区分"要求"和"举例" |

对应的三个结构性问题：

1. **新人不知道写什么、写在哪**：缺少按文档类型划分的目录约定与模板。
2. **读者不知道能不能信**：文档没有状态、版本、负责人，无法区分"设计中"和"已落地"。
3. **规范不落地**：写下来的要求没有可执行的检查，依赖人工评审记住。

本规范把这三件事分别落到 **目录约定**、**元数据规范**、**质量门禁** 上。

## 2. 规范文档清单

| 编号 | 文档 | 解决的问题 |
|---|---|---|
| S1 | [01-doc-structure-and-naming.md](01-doc-structure-and-naming.md) | 文档放哪里、叫什么名、什么该写成本仓库的文档 |
| S2 | [02-writing-style.md](02-writing-style.md) | 中文写作风格、术语一致性、代码示例要求、图表规范 |
| S3 | [03-metadata-and-lifecycle.md](03-metadata-and-lifecycle.md) | 元数据字段、文档状态机、版本与废弃策略 |
| S4 | [04-quality-gates.md](04-quality-gates.md) | 可自动化的检查项、CI 接线方式、豁免机制 |
| S5 | [05-review-checklist.md](05-review-checklist.md) | 人工评审清单、PR 文档责任人 |

配套产出：

- [../templates/README.md](../templates/README.md) —— **模板库**，按文档类型提供可直接复制的骨架。
- `tools/check-doc-standard.ps1` —— 元数据与规范的自动检查（CI 接线见 S4）。

## 3. 快速上手（3 分钟）

### 3.1 我要写一份技术方案

1. 复制 `templates/template-design-doc.md` 到 `docs/architecture/<主题短横线命名>.md`；
2. 填写 S3 规定的元数据头（`status` 必填）；
3. 按模板的章节骨架填写内容，**不要删章节**——不适用写「本文不涉及」并说明理由；
4. PR 描述里写明「本文档对应实现 PR：#xxx」，由评审人核对文档与代码一致性。

### 3.2 我要给某个模块写说明

复制 `templates/template-module-readme.md` 到 `<module>/README.md`，按 S1 的"模块文档最小信息集"填写。

### 3.3 我要记录一个技术决策

复制 `templates/template-adr.md` 到 `docs/adr/ADR-<序号>-<短标题>.md`。ADR 一旦 Accepted **不可修改结论**，只能新增 supersede 关系的 ADR。

### 3.4 我要写一篇教程或操作指南

先判断读者属于哪一类（S2 的 Divio 四分类），选择 `templates/template-tutorial.md` 或 `templates/template-howto.md`。**教程不解释原理，操作指南不重复安装步骤。**

## 4. 与既有资产的关系

本规范**不废弃**任何既有文档。迁移是增量的：

- `docs/architecture/*.md`、`docs/report-alignment/*.md` —— 存量文档**不强制**补元数据，但在**下次修改该文件时**必须补齐（见 S3 第 4 节"存量文档迁移"）。
- `docs/usage/index.html` —— 属于单文件 HTML 载体，规范对其不做强制约束；新增使用文档应优先采用 `docs/usage/<主题>.md` + 目录页的形态。
- `pw-reports-v2/README.md` —— 视同模块 README，按 `template-module-readme.md` 补齐缺失章节即可。

## 5. 规范变更

规范本身受本规范约束：

- 规范文件位于 `docs/standards/`，编号前缀 `NN-`，**编号一旦分配即不可复用**（删除后留空号）。
- 修改规范需在同一个 PR 中更新本索引表与受影响模板。
- 规范变更需要至少一名框架维护者之外的评审人确认。

## 6. 术语约定

| 术语 | 含义 |
|---|---|
| 框架（framework） | `pw_automation_framework` 仓库，Java 21 + Maven 多模块 |
| 测试工程（test project） | `test-automation` 仓库，承载 features 与 step definitions |
| 文档单元（doc unit） | 一篇可独立评审的文档，对应一个文件 |
| 元数据头（front matter） | 文件顶部的 YAML 块，字段定义见 S3 |
| 门禁（gate） | 违反即构建失败的自动检查 |
