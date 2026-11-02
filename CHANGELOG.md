---
id: DOC-REPO-0002
title: 变更日志
type: standard
status: active
owner: team-automation
created: 2026-10-22
updated: 2026-10-22
applies_to: repo
tags: [changelog]
supersedes: []
superseded_by: null
---

# 变更日志

> 本文件记录面向**使用者**的变更（新增能力、行为变化、破坏性变更、缺陷修复）。
> 内部重构（仅实现细节变化、无使用方影响）不在此记录——那属于 git 提交历史。
>
> **格式**：遵循 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)；版本号遵循 [语义化版本](https://semver.org/lang/zh-CN/)。
>
> **维护**：每次合入影响使用方的 PR 时，在 `Unreleased` 段追加一行。发版时把 `Unreleased` 折叠为新版本号并写日期。

## [Unreleased]

### 新增

- 文档规范与模板库：新增 `docs/standards/`（S1–S5 五篇规范）、`docs/templates/`（8 份可直接复制的模板）、`docs/README.md` 文档总索引。
- 新增文档规范门禁 `tools/check_doc_standard.py`，覆盖元数据完整性、`type` 与目录匹配、文档内链可达性、模板占位符残留、模块 README 存在性。
- 5 个框架模块补齐 README：`pw-core`、`pw-web-ui`、`pw-api-core`、`pw-route`、`pw-codegen-v3`；`pw-reports` README 补齐元数据头与「边界与非目标」章节。

### 变更

- 根 `README.md` 的文档段改为指向 `docs/README.md` 总索引，并说明两个文档门禁的分工。
- 修复 README 技术栈表中 Cucumber 版本与根 `pom.xml` 不一致的问题（7.31.0 → 7.34.9）。
- 架构图资源归位到 `docs/architecture/assets/`，与文档中的 `assets/` 引用路径对齐（此前存在真实断链）。

### 破坏性变更

- 无。

## 迁移说明

- **无强制迁移**。新增的文档门禁对存量文档采取「只减不增」祖父化策略：存量文档的元数据缺失降级为告警，豁免清单见 `docs/standards/legacy-baseline.txt`。
- 存量文档在**下次被修改时**需补齐元数据头，规则见 [S3 §4](docs/standards/03-metadata-and-lifecycle.md)。
- 存量文档中引用了不存在的 `docs/architecture/assets/*.svg` 路径已修正为真实位置。

## 版本历史

> 早期版本的完整变更记录可查 git 历史（`git log --oneline`）。自本文件建立起的版本在此逐条记录。
