---
id: {{ID}}
title: {{TITLE}}
type: module
status: draft
owner: {{OWNER}}
created: {{DATE}}
updated: {{DATE}}
applies_to: {{MODULE}}
tags: [module]
supersedes: []
superseded_by: null
---

# {{MODULE}}

> {{一句话：这个模块解决什么问题。30 字以内。}}

## 1. 定位

{{一段话说明模块职责与边界。}}

## 2. 在架构中的位置

```text
<依赖上游>  ──>  {{MODULE}}  ──>  <依赖下游>
```

| 项 | 内容 |
|---|---|
| 被谁依赖 | <模块名> |
| 依赖谁 | <模块名> |
| 包名 | `com.hsbc.cmb.hk.dbb.automation.framework.<pkg>` |
| 分层约束 | <由哪个 ArchUnit 测试守卫> |

> 完整依赖方向见根 `README.md` 的模块表。

## 3. 引入

```xml
<dependency>
    <groupId>{{GROUP_ID}}</groupId>
    <artifactId>{{ARTIFACT_ID}}</artifactId>
    <version>${framework.version}</version>
</dependency>
```

**前置要求**：

- JDK 21+
- <其他前置，如浏览器二进制、特定系统库>

## 4. 最小可用示例

<!--
必须是能编译运行的代码。示例要"刚好"体现核心用法，不要堆砌 API。
每段代码前用一句话说明它证明了什么。
-->

```java
// <说明这段代码证明了什么>
```

**预期结果**：

## 5. 核心 API 速查

<!-- 表格：方法 | 作用 | 何时用。方法签名用真实签名。 -->

| 方法 / 类型 | 作用 | 何时使用 |
|---|---|---|
|  |  |  |

## 6. 配置

<!-- 只列本模块独有配置。全局配置见 docs/reference/configuration.md。 -->

| 配置键 | 默认值 | 说明 |
|---|---|---|
|  |  |  |

## 7. 边界与非目标

<!-- 明确"这个模块不做什么"，避免误用。 -->

- **不负责**：
- **不负责**：

## 8. 测试与验证

| 类型 | 位置 |
|---|---|
| 单元测试 | `<path>` |
| 集成测试 | `<path>` |

```bash
# 在仓库根目录执行
mvn -pl {{MODULE_ARTIFACT}} -am test
```

## 9. 延伸阅读

- <链接到相关 architecture / howto 文档>
