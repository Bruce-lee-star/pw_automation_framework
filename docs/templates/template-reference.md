---
id: {{ID}}
title: {{TITLE}}
type: reference
status: draft
owner: {{OWNER}}
created: {{DATE}}
updated: {{DATE}}
applies_to: {{APPLIES_TO}}
tags: [reference]
supersedes: []
superseded_by: null
---

# {{TITLE}}

> {{一句话说明本参考手册覆盖什么、什么时候该查它。}}
>
> **事实来源**：<唯一来源，如 `pw-core/src/main/java/.../ConfigRegistry.java`> —— 本文档为该来源的**呈现**，来源变更时必须同步本文。

## 1. {{分类一}}

### 1.1 `{{配置键 / 参数名}}`

| 属性 | 值 |
|---|---|
| 类型 | `string` / `int` / `boolean` / `duration` |
| 默认值 | `{{actual_default}}` |
| 生效范围 | <哪些模块会读它> |
| 必填 | 是 / 否 |

**说明**：<这个键控制什么行为>

**示例**：

```properties
{{key}}={{value}}
```

**注意**：

## 2. {{分类二}}

<!--
参考手册的条目模板：每条都包含"是什么/默认值/怎么用"。
条目多时用表格总览 + 详情小节的两段式结构。
-->

### 2.1 {{条目名}}

| 属性 | 值 |
|---|---|
| 类型 | |
| 默认值 | |
| 必填 | |

**说明**：

**示例**：

## 3. {{命令行参数 / 环境变量}}

| 名称 | 类型 | 默认值 | 说明 |
|---|---|---|---|
| `-D{{flag}}` | `boolean` | `false` | |

## 4. 完整清单

<!--
若存在机器可读的全量清单，在此链接而非复制内容。
-->

- 全量配置键：`pw-core/src/main/java/com/hsbc/cmb/hk/dbb/automation/framework/common/config/ConfigRegistry.java`
- 键数量校验：注册表类加载期静态校验 + golden 快照

## 5. 相关文档

- <链接>
