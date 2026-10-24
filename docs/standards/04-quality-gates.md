---
id: DOC-STD-0004
title: S4 — 质量门禁与自动检查
type: standard
status: active
owner: team-automation
created: 2026-10-22
updated: 2026-10-22
applies_to: repo
tags: [standards, ci, gates]
supersedes: []
superseded_by: null
---
# S4 — 质量门禁与自动检查

> 适用范围：文档检查项的规则定义、CI 接线方式、豁免机制。
> 目标：把规范里**可机器判定**的部分自动化，把需要判断的部分交给人。

## 1. 现状与缺口

仓库已有门禁：

| 门禁 | 实现 | 覆盖 |
|---|---|---|
| README 版本漂移 | `tools/check-doc-drift.ps1` / `.sh` | README 技术栈表 vs `pom.xml` `<properties>` |
| 文档引用完整性 | 同上 | `pom.xml` / `README.md` 中引用的 `docs/**.md` 是否存在 |
| 文档区间引用 | 同上 | `docs/<dir>/NN~MM` 区间内文档是否齐全 |
| 模块拓扑 | 同上 | 根 `pom.xml` 每个 `<module>` 是否能解析到含 `pom.xml` 的目录 |
| 反空转守卫 | 同上 | 无任何引用时告警而非静默通过 |

工作流：`.github/workflows/doc-drift-check.yml`（触发路径含 `docs/**`、`README.md`、`pom.xml`、检查脚本本身）。
**Jenkins 侧目前无文档步骤**（`Jenkinsfile` 仅有构建/测试/报告归档），这是已知缺口。

缺口：

1. 无元数据校验——新建文档缺 `status`/`owner` 不会被发现；
2. 无"模块必须有 README"检查——`pw-reports-v2` 有 README，但无检查保证新增模块也写；
3. 无文档内链检查——`docs/architecture/a.md` 指向 `docs/architecture/b.md`，b 被删后无人发现；
4. 无目录索引检查——`docs/README.md` 不存在，读者无入口；
5. 无"占位符残留"检查——模板复制后忘填 `{{TITLE}}` 会被发布。

## 2. 门禁分级

| 级别 | 判定 | CI 行为 | 适用范围 |
|---|---|---|---|
| **G1 阻断** | 规则被明确违反 | 构建失败 | 新增文档、CI 触发的文档变更 |
| **G2 告警** | 规则可能违反，需人工确认 | 输出 WARN，不失败 | 存量文档、启发式判断 |
| **G3 人工** | 需要专业判断 | PR 评审 | 全部文档 |

## 3. 规则清单

### G1（阻断）

| 编号 | 规则 | 检查方式 |
|---|---|---|
| R1 | 新增 `.md`（`docs/` 下，排除 `docs/templates/`）必须含元数据头 | 解析首行 `---` 与必需字段 |
| R2 | 元数据必需字段齐全且 `id` 全局唯一 | 字段存在性 + 全仓 `id` 查重 |
| R3 | `type` 与所在目录匹配（§2 映射表） | 目录 → 允许的 type 集合 |
| R4 | `status` 取值合法 | 枚举校验 |
| R5 | `date` 字段为 `YYYY-MM-DD` 且不晚于今天 | 格式 + 日期比较 |
| R6 | 文档内部相对链接（`.md` / `.svg` / `.png`）目标存在 | 解析链接并检查磁盘 |
| R7 | `docs/templates/` 之外的文档不得残留占位符 | 正则 `\{\{[A-Z][A-Z0-9_]*\}\}` 扫描（仅匹配大写占位符，避开规范正文中的说明性写法） |
| R8 | 每个对外模块目录必须有 `README.md` | 目录遍历 + 文件存在性 |
| R9 | 根 `README.md` 必须存在且非空 | 文件存在性 |
| R10 | `docs/README.md`（文档总索引）必须存在 | 文件存在性 |
| R11 | `superseded_by` 非空时，`status` 必须为 `deprecated` | 字段一致性 |
| R12 | `status: deprecated` 时正文必须含废弃提示 | 关键词扫描（`已废弃` / `已替代`） |

### G2（告警）

| 编号 | 规则 | 告警条件 |
|---|---|---|
| W1 | 元数据 `updated` 超过 6 个月未更新 | 时间差 > 180 天 |
| W2 | `owner` 为 `unassigned` | 字段值匹配 |
| W3 | 文档缺少 H1 或 H1 与 `title` 不一致 | 文本比对 |
| W4 | 标题层级跳级 | H1→H3、H2→H4 等 |
| W5 | 代码块缺少语言标注 | 裸 ``` 检测 |
| W6 | 文档超过 1500 行 | 规模超限，提示拆分 |
| W7 | 存在指向仓库外绝对路径的链接 | 正则扫描 `C:\` / `D:\` / `/Users/` |
| W8 | 疑似真实凭据（password/token/secret 后跟字面量） | 正则扫描 |
| W9 | 存在指向不存在模块的引用 | 与根 `pom.xml` 的 `<module>` 比对 |
| W10 | `docs/README.md` 未收录某个 `active` 文档 | 索引 vs 文件集差集 |

### G3（人工评审）

| 编号 | 规则 |
|---|---|
| M1 | 文档与实现一致（代码改了，文档没改 = 缺陷） |
| M2 | 技术准确性（示例真的能跑、结论真的成立） |
| M3 | 可读性（新人能读懂吗？术语一致吗？） |
| M4 | 无重复的事实来源（同一张表写了两遍 = 缺陷） |
| M5 | 无敏感信息泄露 |

## 4. 实现方式

### 4.1 脚本

新增 `tools/check_doc_standard.py`（跨平台，CI 与本地同一份实现），规则实现遵循既有 `check-doc-drift.ps1` 的风格：

- **单一实现**：用 Python 而非 PowerShell，避免 Windows / Linux 两份脚本产生行为差异；
- **纯标准库**：只依赖 `os` / `re` / `datetime`，无需安装任何依赖；
- **ASCII 注释与中文提示分离**：脚本内的规则说明用英文，避免编码问题；面向读者的规则说明在本规范文档中；
- **退出码**：`0` 通过，`1` 有 G1 失败，`2` 环境错误；
- **`--strict`**：只报 G1 失败（CI 用）；**`--quiet`**：只输出失败项；**`--fix`**：对可自动修复项生效（补 `updated` 等）。

调用方式：

```bash
# 在仓库根目录执行；退出码 0 表示 G1 全部通过
python3 tools/check_doc_standard.py

# 仅输出 G1 失败项（CI 用）
python3 tools/check_doc_standard.py --strict
```

### 4.2 存量豁免（只减不增）

新规则对**存量**文档不追溯阻断。豁免清单在 `docs/standards/legacy-baseline.txt`：

- 命中清单的文档，R1（元数据头）降级为 WARN；
- 清单条目**只能移除，不能新增**——新增需走 PR 评审（对齐仓库既有的 `checkstyle-baseline.xml` 祖父化范式）；
- 文档被修改时（S3 §4），应从清单移除并补齐元数据。

### 4.3 CI 接线

在 `.github/workflows/doc-drift-check.yml` 中新增一个 step（与既有 drift check 同 job）：

```yaml
      - name: Verify docs metadata and structure
        run: python3 tools/check_doc_standard.py --strict
```

并把 `tools/check_doc_standard.py` 加入该工作流的 `paths` 触发列表——**检查脚本自身变更必须能触发检查**，否则规则被改坏时不会有人发现。

Jenkins 侧在 `Jenkinsfile` 的 `Run tests` 之后、报告归档之前增加：

```groovy
stage('Doc Standard Check') {
  steps {
    sh 'python3 tools/check_doc_standard.py --strict'
  }
}
```

### 4.4 豁免机制

确需违反 G1 规则时：

1. 在文档元数据中加入 `gate_exemptions: [R7, R8]`，并附 `gate_exemption_reason: "..."`；
2. 脚本读取该字段并输出 `EXEMPT` 而非 `FAIL`；
3. 豁免**必须有理由**，理由写给半年后的自己看。

豁免只针对 G1。G2 永远只是告警，G3 永远由人判断。

## 5. 版本影响面清单

依赖升级后**必须**按此清单复核文档（对应 S3 §6）：

| 升级项 | 需复核的文档 |
|---|---|
| `playwright.version` | `docs/architecture/` 全部、`docs/reference/api-playwright.md` |
| `serenity.version` | `docs/architecture/` 报告方案、`docs/report-alignment/` 全部 |
| `cucumber.version` | `docs/usage/`、`docs/howto/` 中的 Gherkin 语法片段 |
| `junit.version` | `docs/howto/` 中的测试写法 |
| 新增/重命名模块 | 根 README 模块表、`docs/reference/module-map.md`、所有链接 |
| 新增配置键 | `docs/reference/configuration.md` |
| 报告字段变更 | `docs/report-alignment/` 全部 |

## 6. 文档质量度量

不追求"文档数量"，追求"读者能自助完成"。建议观测：

| 指标 | 采集方式 | 目标 |
|---|---|---|
| 文档搜索无结果率 | 检索日志 | 越低越好 |
| issue 中"文档没写"类占比 | issue 标签统计 | 逐月下降 |
| 新人首次跑通用例耗时 | 手动观察 | < 15 分钟 |
| G2 告警总数 | CI 日志 | 逐月下降 |

这些指标用于**发现文档缺陷**，不用于考核个人。

## 7. 门禁落地顺序（建议）

1. 先落 R1–R5（元数据），**只对新增文件生效**，存量给宽限期；
2. 再落 R6（内链）、R9–R10（README/索引存在）；
3. 再落 R7（占位符）、R8（模块 README）、R11–R12（废弃一致性）；
4. 存量文档按 S3 §4 在被修改时逐步收敛；
5. W 系列告警长期保留，用于发现新问题。

每一步上线后至少观察两个迭代，确认无误报再启用下一批。
