---
id: DOC-STD-0003
title: S3 — 元数据与文档生命周期
type: standard
status: active
owner: team-automation
created: 2026-10-22
updated: 2026-10-22
applies_to: repo
tags: [standards, metadata]
supersedes: []
superseded_by: null
---
# S3 — 元数据与文档生命周期

> 适用范围：所有**新增**的 `.md` 文档（存量文档在下次修改时补齐，见 §4）。

## 1. 元数据头（front matter）

每篇文档顶部**必须**包含以下 YAML 块。字段顺序固定，便于人工 diff。

```yaml
---
id: DOC-ARCH-0007
title: 路由拦截的并发安全模型
type: architecture          # 文档类型，取值见 §2
status: draft               # 文档状态，取值见 §3
owner: team-automation      # 责任团队或责任人
created: 2026-10-22         # 首次创建日期 YYYY-MM-DD
updated: 2026-10-22         # 最后一次实质性修改日期
applies_to: pw-route        # 适用模块/版本范围；全仓库写 "repo"
tags: [route, concurrency]
supersedes: []              # 取代的其他文档 id
superseded_by: null         # 被谁取代；被取代时填写
---
```

### 1.1 字段定义

| 字段 | 必填 | 类型 | 规则 |
|---|---|---|---|
| `id` | 是 | string | 全局唯一，建议前缀 `DOC-<类型>-<4位序号>`，**编号不复用** |
| `title` | 是 | string | 文档标题，与 H1 一致（可省略 H1 之外的副标题差异） |
| `type` | 是 | enum | 见 §2 |
| `status` | 是 | enum | 见 §3 |
| `owner` | 是 | string | 团队名或责任人；无人认领写 `unassigned` 并**必须**开 issue 认领 |
| `created` | 是 | date | 首次创建日期；**不随修改更新** |
| `updated` | 是 | date | 实质性内容修改时更新；仅修错别字可不更新 |
| `applies_to` | 是 | string | 适用模块（`pw-core` 等）或版本区间；全仓库写 `repo` |
| `tags` | 否 | list | 用于检索，优先复用既有标签词表 |
| `supersedes` | 否 | list | 取代的文档 id |
| `superseded_by` | 否 | string/null | 被取代时填写新文档 id |

### 1.2 禁止字段

不引入 `keywords`、`author_email`、`last_reviewed_by` 等会造成个人信息与状态分裂的字段。需要记录评审历史时用 git 提交历史——那才是唯一可信来源。

## 2. 文档类型（`type`）

| 取值 | 含义 | 目录 | 模板 |
|---|---|---|---|
| `index` | 目录索引 | 任意目录的 `README.md` | — |
| `standard` | 规范 | `docs/standards/` | — |
| `template` | 模板 | `docs/templates/` | `../templates/template-*.md` |
| `adr` | 架构决策记录 | `docs/adr/` | `../templates/template-adr.md` |
| `architecture` | 技术方案/分层指南 | `docs/architecture/` | `../templates/template-design-doc.md` |
| `usage` | 教程 | `docs/usage/` | `../templates/template-tutorial.md` |
| `howto` | 任务型指南 | `docs/howto/` | `../templates/template-howto.md` |
| `reference` | 查阅手册 | `docs/reference/` | `../templates/template-reference.md` |
| `runbook` | 故障处置 | `docs/runbook/` | `../templates/template-runbook.md` |
| `module` | 模块说明 | `<module>/README.md` | `../templates/template-module-readme.md` |
| `alignment` | 跨仓库契约 | `docs/report-alignment/` | `../templates/template-alignment.md` |

`type` **必须**与所在目录一致。类型与目录冲突是门禁可拦截的硬错误。

## 3. 文档状态机

```
   draft ──评审通过──> active ──被取代/过时──> deprecated
     │                    │
     │                    └──内容大改──> draft（重新评审）
     └──长期无人推进──> abandoned
```

| 状态 | 含义 | 读者应如何对待 |
|---|---|---|
| `draft` | 编写中，可能不准确 | 仅供参考，不要照抄 |
| `active` | 当前有效、已评审 | 正常使用 |
| `deprecated` | 已被取代或不再适用 | **不要使用**，看 `superseded_by` |
| `abandoned` | 起草后放弃 | 无效内容，仅存档 |

### 3.1 状态迁移规则

- `draft → active`：需一名评审人确认**技术准确性** + 一名确认**可读性**（同一人可兼任，需在 PR 中说明）。
- `active → deprecated`：**必须**同时填写 `superseded_by`（指向新文档），或明确写明废弃原因与替代方案。
- `deprecated` 文档**不删除**，保留供追溯，并在文件顶部加一行醒目提示：

  ```markdown
  > ⚠️ **本文已废弃**（2026-10-22）：请改用 [新文档](新文档.md)。
  ```

- 任何 `active` 文档超过 **6 个月**未 `updated` 时，评审人应在 PR 或 issue 中复核一次是否仍准确。

## 4. 存量文档迁移

存量文档（`docs/architecture/`、`docs/report-alignment/` 等）**不要求一次性补齐**。规则：

1. **被修改时**：同一次提交里补齐元数据头，并把文件名调整为 kebab-case（若会破坏大量外部链接，可保留文件名、仅在 `id` 中体现）。
2. **新增时**：必须一次到位。
3. 迁移进度用 issue 跟踪，不在本规范中维护清单。

### 4.1 `docs/architecture/` 已有文档的处理建议

该目录下的中文文件名是历史资产。规范建议（**需 PR 讨论后执行**，不在本规范强制）：

| 现状 | 建议 |
|---|---|
| `Java-Playwright-1.62-网络API分层指南.md` | 保持文件名，正文补元数据；标题改为「Playwright 1.62 网络 API 分层使用指南」 |
| `SSO并发与会话隔离设计.md` | 保持文件名，补元数据 `type: architecture` |
| `自建Serenity兼容报告方案.md` | 保持文件名，补元数据；`pw-reports-v2/README.md` 作为其摘要入口 |
| `docs/architecture/*.html` | 属展示型资产，不强制元数据；建议在同名 md 中链接之 |

## 5. 版本与适用性

- 文档**不独立版本化**，随仓库版本走。用 `applies_to` 表达适用范围。
- 涉及行为随版本变化的文档，正文**必须**标注版本门槛：

  > 本行为基于 Playwright **1.62**；1.60 及更早版本无 `waitForRequestFinished`。

- 依赖升级后，**必须**检查受影响文档：见 S4 §5 的"版本影响面"清单。

## 6. 复审与废弃触发

| 触发事件 | 必做动作 |
|---|---|
| 升级 Playwright / Serenity / Cucumber | 复核 `docs/architecture/` 与 `docs/reference/` 中涉及该 API 的文档 |
| 新增/重命名 Maven 模块 | 更新根 README 模块表、所有指向该模块的链接 |
| 新增配置键 | 更新 `docs/reference/configuration.md` |
| 报告字段变更 | 更新 `docs/report-alignment/` 全部文档 |
| 门禁规则变更 | 更新 S4 并同步 `tools/check-doc-standard.ps1` |

## 7. 元数据自检清单

- [ ] YAML 头位于文件第一行，紧接 `---`
- [ ] 字段齐全，顺序符合 §1
- [ ] `id` 全局唯一且编号未复用
- [ ] `type` 与所在目录匹配
- [ ] `status` 反映真实状态
- [ ] `owner` 不是 `unassigned`（若是，已有认领 issue 链接）
- [ ] `updated` 与最后一次实质修改日期一致
- [ ] 被取代的文档填写了 `superseded_by` 且正文顶部有废弃提示
