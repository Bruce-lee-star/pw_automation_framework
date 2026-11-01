---
id: DOC-STD-0001
title: S1 — 文档结构与命名规范
type: standard
status: active
owner: team-automation
created: 2026-10-22
updated: 2026-10-22
applies_to: repo
tags: [standards, naming]
supersedes: []
superseded_by: null
---
# S1 — 文档结构与命名规范

> 适用范围：仓库内所有 `.md` 文档的新增与重构。存量文档在下次修改时适用。

## 1. 目录职责

```
pw_automation_framework/
├── README.md                    # 顶层门面：5 秒内让人知道这是什么、怎么跑起来
├── CHANGELOG.md                 # 面向使用者的版本变更记录（每次发版更新）
├── CONTRIBUTING.md              # 贡献指南：分支、提交、评审、门禁说明
├── docs/
│   ├── README.md                # 文档总索引（必须存在，是唯一的文档入口）
│   ├── standards/               # 【规范】如何写文档 —— 规范性来源
│   ├── templates/               # 【模板】文档骨架 —— 复制即用
│   ├── adr/                     # 【决策】架构决策记录，一决策一文件
│   ├── architecture/            # 【方案】跨模块技术方案与分层指南
│   ├── usage/                   # 【使用】面向使用者的教程与操作指南
│   ├── howto/                   # 【任务】面向已完成上手者的任务型指南
│   ├── reference/               # 【参考】配置项、API、CLI 参数的查阅手册
│   ├── runbook/                 # 【运维】故障处置手册
│   ├── report-alignment/        # 【对齐】框架与报告/用例层的一致性规范
│   └── template-design/         # 【设计稿】报告模板的静态视觉设计
└── <module>/README.md           # 每个 Maven 模块的说明（见 §4）
```

### 1.1 目录选择决策表

| 你要写的东西 | 放哪 | 说明 |
|---|---|---|
| 首次接触项目的上手教程 | `docs/usage/` | 目标：15 分钟内跑通第一个用例 |
| "如何把 X 做成 Y"的任务型步骤 | `docs/howto/` | 目标：读者已上手，要完成一个具体任务 |
| 某个配置项 / API / 参数的完整说明 | `docs/reference/` | 目标：查阅，不是学习 |
| 一个技术决策及其理由 | `docs/adr/` | 目标：留存"为什么这样做" |
| 一项跨模块技术方案 | `docs/architecture/` | 目标：解释系统如何运作、边界在哪 |
| 线上/流水线出问题时怎么办 | `docs/runbook/` | 目标：按步骤止损 |
| 报告或 UI 的视觉设计稿 | `docs/template-design/` | 目标：静态 demo，非规范 |
| 框架与报告/用例层的契约 | `docs/report-alignment/` | 目标：跨仓库的强约束 |

**冲突时的优先级**：当一篇文档可能属于两类时，**按读者意图归类**。读者想"学会"→ usage；读者想"做完一件事"→ howto；读者想"查一个值"→ reference。

## 2. 命名规范

### 2.1 文件名

- 一律使用 **小写 + 短横线（kebab-case）**，如 `route-dsl-guide.md`。
  - 禁止：下划线（`route_dsl_guide.md`）、驼峰（`RouteDslGuide.md`）、空格、中文文件名。
- ADR 例外：`ADR-<3位序号>-<短标题>.md`，如 `ADR-003-route-dsl-design.md`。序号**只增不减不复用**。
- 规范例外：`docs/standards/NN-<短标题>.md`。
- 既有 `docs/architecture/` 下的中文文件名**保留不改**（历史资产，重命名收益低于成本），但**新增文件必须遵守本节**。

### 2.2 标题

- 文档一级标题必须等于文件名去掉扩展名后的可读形式，读者在目录里看到什么、点进去第一行看到什么必须一致。
- 标题**不写序号前缀**（序号只存在于文件名），避免重排时改标题。

### 2.3 标识符与符号

- 引用代码符号一律用反引号包裹：`` `RouteDsl` ``、`` `ConfigRegistry` ``、`` `framework.web.route` ``。
- 引用配置键用其完整键名：`` `route.capture.all.type` ``。
- 引用文件用相对仓库根的路径：`pw-core/src/main/java/.../ConfigRegistry.java`。
- 引用模块用**目录名**（`core` / `web` / `api` / `route` / `codegen` / `reporting`），不用 Maven artifactId 的历史别名。

## 3. 文档与代码的对应关系

### 3.1 什么该写成本仓库的文档

| 内容 | 归属 |
|---|---|
| 框架的使用方式、配置、扩展点 | 本仓库 `docs/` |
| 业务用例（features、step definitions） | `test-automation` 仓库 |
| 某次具体故障的排查过程 | **不写**，转 `docs/runbook/` 只留可复用的处置步骤 |
| 会议纪要、头脑风暴 | **不写**本仓库；进 Wiki 或 issue |
| 个人草稿 | **不写**本仓库；进个人空间 |

### 3.2 单一事实来源

一条信息只在一个地方维护，其他地方**链接过去**，不要复制：

| 信息 | 唯一来源 | 其他文档如何引用 |
|---|---|---|
| 技术栈版本 | 根 `pom.xml` 的 `<properties>` | README 版本表（由 `tools/check-doc-drift.ps1` 校验） |
| 配置键清单 | `ConfigRegistry.java` | `docs/reference/` 引用类名与数量，不重复列举 |
| 模块依赖方向 | 根 `pom.xml` + `ArchitectureTest` | README 模块表只给一句话摘要 |
| 报告字段与对齐规则 | `docs/report-alignment/` | 其他文档链接之 |

违反此条是**最常见的文档漂移来源**——同一张版本表在 README、usage、reference 里各写一份，改一处忘三处。

## 4. 模块文档最小信息集

每个对外可用的 Maven 模块**必须**有 `README.md`（`pw-core` / `pw-web-ui` / `pw-api-core` / `pw-route` / `pw-codegen-v3` / `pw-reports-v2`）。缺少即视为文档缺陷，CI 门禁可拦截。

模块 README 必须包含以下章节，顺序不可调换：

1. **一句话定位** —— 这个模块解决什么问题；
2. **它在架构中的位置** —— 被谁依赖、依赖谁，指向根 README 的模块表；
3. **安装/引入** —— Maven 坐标；
4. **最小可用示例** —— 至少一段能编译运行的代码；
5. **核心 API 速查** —— 表格形式，方法名 + 一句话说明；
6. **配置项** —— 只列本模块独有的，全局配置指向 `docs/reference/configuration.md`；
7. **边界与非目标** —— 明确"这个模块不做什么"；
8. **延伸阅读** —— 链接到相关 architecture / howto 文档。

骨架见 `../templates/template-module-readme.md`。

## 5. 文档之间的引用

- **相对路径链接**，不使用绝对 URL（除外部站点外），保证本地可离线阅读、可被门禁校验存在性。
- 链接到仓库内文件时写**相对当前文件**的路径；门禁会解析并检查目标存在。
- 跨仓库引用（`test-automation`）写明仓库名 + 路径，**不要**写本地绝对路径。
- 一篇文档**不引用自己的目录索引**作为唯一导航手段——索引是入口，正文链接才是导航。

## 6. 图表与附件

- 图表统一放 `docs/<所属目录>/assets/`，引用写相对路径。
- 优先 **SVG**（可版本化、可 diff、清晰缩放）；位图仅在截图类场景使用 PNG。
- 每张图**必须**有紧邻的图注，解释"看图要点"——不要让读者自己猜结论。
- 图表正文引用使用 `![说明](assets/x.svg)`，说明文字本身要能独立成立（图片加载失败时读者仍能理解）。
- 现有 `docs/architecture/*.svg` 与 `docs/architecture/assets/` 的组织方式符合本节要求。

## 7. 提交与评审

- 文档改动**必须**与它描述的代码改动在**同一个 PR** 内提交，除非改动本身是纯文档修正。
- 纯文档 PR 标题前缀 `docs:`，参考 `3653e6ac` 的既有格式。
- 评审人职责见 [05-review-checklist.md](05-review-checklist.md)。

## 8. 检查清单（自检用）

- [ ] 文件放在正确的目录（对照 §1.1 决策表）
- [ ] 文件名是 kebab-case，标题与文件名一致
- [ ] 有一级标题，且不重复文档名
- [ ] 元数据头字段齐全（见 S3）
- [ ] 没有与其他文档重复的"事实"，重复的是链接
- [ ] 相对路径链接全部可达
- [ ] 代码示例可编译/可运行，命令可直接复制执行
- [ ] 至少一张表格或一段代码（能结构化就不用散文）
