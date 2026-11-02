---
id: DOC-MOD-CODEGEN-0003
title: pw-codegen-v3 模块说明
type: module
status: active
owner: team-automation
created: 2026-11-01
updated: 2026-11-01
applies_to: pw-codegen-v3
tags: [module, codegen, playwright]
supersedes: []
superseded_by: null
---

# pw-codegen-v3

> 唯一受支持的元素拾取、录制与 Page Object 代码生成模块。

## 1. 定位

`pw-codegen-v3` 基于 Playwright 与 CDP 实现元素拾取、交互记录和 Java 代码生成。V1/V2 已退役，不再作为构建模块或扩展入口。

## 2. 在架构中的位置

```text
pw-core <- pw-web-ui <- pw-codegen-v3
```

| 项 | 内容 |
|---|---|
| 被谁依赖 | 测试工程通过 `pw-codegen-v3` 使用代码生成入口 |
| 依赖谁 | `pw-web-ui`、Playwright |
| 包名 | `com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen` |
| 分层约束 | 通过 `RoleCodegenBridge` SPI 与 `pw-web-ui` 解耦 |

完整模块图见根 [README](../README.md)。

## 3. 引入

```xml
<dependency>
    <groupId>com.hsbc.cmb.dbb.hk.automation</groupId>
    <artifactId>pw-codegen-v3</artifactId>
    <version>${framework.version}</version>
</dependency>
```

**前置要求**：JDK 21+、Playwright 浏览器，以及本地可用的 Chrome/Chromium。

## 4. 最小可用示例

在 Playwright 场景中将当前 `Page` 交给 Inspector；用户结束拾取后模块生成对应的页面代码：

```java
CodegenLauncher.inspect(page);
```

Inspector 会阻塞当前调用，直到用户关闭拾取面板。

## 5. 核心 API 速查

| 方法 / 类型 | 作用 | 何时使用 |
|---|---|---|
| `CodegenLauncher.inspect(Page)` | 打开 Inspector 并开始元素拾取 | 需要从正在运行的页面生成 Page Object 时 |
| `CodegenLauncher.inspect(Page, String...)` | 使用指定 NLS 资源启动拾取 | 页面文案需要通过 NLS 映射时 |

## 6. 配置

| 配置键 | 默认值 | 说明 |
|---|---|---|
| `pw.codegen.log` | `WARN` | 控制 codegen 日志级别；支持 `INFO`、`DEBUG` 和 `OFF` |

## 7. 边界与非目标

- **不负责**：浏览器与测试用例生命周期管理，由 `pw-web-ui` 提供。
- **不负责**：保留或维护 V1/V2 实现；当前唯一模块为 V3。

## 8. 测试与验证

```powershell
mvn -o -pl pw-codegen-v3 test
```

浏览器交互测试需要本地 Playwright 浏览器和可启动的 Chrome/Chromium 环境。
