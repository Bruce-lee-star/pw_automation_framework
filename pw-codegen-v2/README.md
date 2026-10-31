---
id: DOC-MOD-CODEGEN-0001
title: pw-codegen-v2 模块说明
type: module
status: active
owner: team-automation
created: 2026-10-22
updated: 2026-10-22
applies_to: pw-codegen
tags: [module, codegen, page-object]
supersedes: []
superseded_by: null
---

# pw-codegen-v2

> 浏览器内"点点看就生成 Page Object"的交互式代码生成器。

## 1. 定位

`pw-codegen` 让 Page Object 的编写从"手写定位器"变成"在页面上点一下"。拾取器在真实浏览器里渲染可访问性树，用户点选元素后直接产出带 `@RoleFile` / `@RoleElement` 注解的 Page 类源码、步骤类与断言类。

## 2. 在架构中的位置

```text
        pw-codegen
             |
          pw-web-ui
```

| 项 | 内容 |
|---|---|
| 被谁依赖 | 无（可选模块，使用侧按需引入） |
| 依赖谁 | `pw-web-ui` |
| 包名 | `com.hsbc.cmb.hk.dbb.automation.framework.web.page.scan` |
| 分层约束 | `CodegenDecouplingArchTest` 保证 `pw-web-ui` 对本模块**零编译依赖**（经 `RoleCodegenBridge` SPI 解耦） |

> **注意包名**：本模块包名带 `framework.web` 前缀，但**不在** `pw-web-ui` 模块内。包名相似不代表依赖方向——`pw-web-ui` 通过 `RoleCodegenBridge` SPI 与之解耦，由 ArchUnit 守护。

### 主要子包

| 子包 | 职责 |
|---|---|
| `scan` | 交互式拾取器 `RoleElementPicker`；生成器 `RoleElementPageGenerator` / `RoleElementStepGenerator` / `RoleElementAssertionGenerator`；面板控制器与脚本注入 |
| `scan.model` | `RoleEntry`、`PickerAction`、`PickerResult`、`PickSnapshot`、`PickMode`、`PageOp`、`StepRec` |
| `scan.nls` | `NlsNameTranslator`：可访问名 ↔ NLS key 互译 |

外部契约：`META-INF/services/com.hsbc.cmb.hk.dbb.automation.framework.web.codegen.spi.RoleCodegenBridge`，本模块实现 `RoleElementPickerBridge`，运行时注入 Web 模块。

## 3. 引入

```xml
<dependency>
    <groupId>com.hsbc.cmb.hk.dbb.automation</groupId>
    <artifactId>pw-codegen-v2</artifactId>
    <version>${framework.version}</version>
</dependency>
```

**前置要求**：

- JDK 21+
- Playwright 浏览器二进制
- **必须 headed 浏览器**（拾取需要用户可见地点击）
- 系统属性 `-Ddbb.codegen.enabled=true`

## 4. 最小可用示例

```java
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.scan.RoleElementPicker;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.scan.RoleElementPageGenerator;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.scan.RoleEntry;
import com.microsoft.playwright.Page;
import java.util.List;

// 前提：-Ddbb.codegen.enabled=true，headed 浏览器，页面已导航到目标页

// A) 拾取 → 直接写文件（outputDir 为源码根，如 src/test/java）
RoleElementPicker.pickAndWrite(
        page,
        "src/test/java",
        "com.hsbc.cmb.hk.dbb.tests.pages",
        "LoginPage",
        "nls/login.nls.json");

// B) 只取元素（用户点选后按 ESC 结束），自行处理
List<RoleEntry> entries = RoleElementPicker.pick(page);

// C) 整页 a11y tree 生成（无需人工交互）
String code = RoleElementPageGenerator.generate(page, "com.x.pages", "LoginPage");
RoleElementPageGenerator.write(page, "src/test/java", "com.x.pages", "LoginPage");
```

**预期结果**：`pickAndWrite` 在浏览器中打开拾取面板，用户点选后于 `src/test/java` 下生成 Page 类源码；`generate` 直接返回源码字符串。

> **已知限制**：整页扫描模式（`SCAN_PAGE` / `SCAN_REGION`）已移除，拾取目前**只有手动（MANUAL）模式**。

## 5. 核心 API 速查

| 类型 | 作用 | 何时使用 |
|---|---|---|
| `RoleElementPicker` | 交互式元素拾取入口（`pick` / `pickAndWrite` / `pickAndDump` / `pickAndGenerate`） | 人工点选采集元素 |
| `RoleElementPageGenerator` | 从 `Page` 或 `RoleEntry` 列表生成 Page 类源码（`generate` / `write` / `dump` / `collectFromPage`） | 生成 Page Object |
| `RoleElementStepGenerator` | 由拾取轨迹生成 Cucumber 步骤类（`generate` / `generateMulti` / `generatePerPage`） | 生成步骤定义 |
| `RoleElementAssertionGenerator` | 生成断言类（`renderClass` / `assertStatement`） | 生成断言代码 |
| `NlsNameTranslator` | 可访问名 ↔ NLS key 双向翻译 | 多语言元素名 |
| `RoleElementPickerBridge` | `RoleCodegenBridge` SPI 实现 | Web 侧集成入口 |

## 6. 配置

本模块**不新增** `ConfigRegistry` 条目。

| 键 | 默认值 | 来源 |
|---|---|---|
| `picker.wait.timeout.ms` | `1800000`（30 分钟；≤0 表示不超时） | `ConfigRegistry.PICKER_WAIT_TIMEOUT_MS` |
| `dbb.codegen.enabled` | `false` | 系统属性，`RoleElementPicker.isCodegenEnabled()` 读取 |
| `codegen.picker.eval.timeout.ms` | `10000` | 模块内常量 |
| `codegen.picker.eval.softTimeout.ms` | `2000` | 模块内常量 |
| `codegen.overwrite.force` | `false` | 生成物覆盖开关 |

## 7. 边界与非目标

- **不负责**运行时——只在开发期使用，不应出现在 CI 流水线中。
- **不负责**断言正确性——生成的是骨架，语义需人工确认。
- **不支持**整页自动扫描——见"已知限制"。
- **不承担** Web 侧依赖——保持 SPI 解耦，不得让 `pw-web-ui` 反向依赖本模块。

## 8. 测试与验证

| 类型 | 位置 |
|---|---|
| 单元测试 | `pw-codegen/src/test/java/`（17 个，含 `RolePickerContractTest`、`RoleElementPageGeneratorNlsKeyTest`） |

```bash
# 在仓库根目录执行
mvn -pl pw-codegen -am test
```

## 9. 延伸阅读

- [根 README 模块表](../README.md#模块)
- [文档总索引](../docs/README.md)
