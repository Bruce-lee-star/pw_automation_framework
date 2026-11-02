---
id: DOC-MOD-WEB-0001
title: pw-web-ui 模块说明
type: module
status: active
owner: team-automation
created: 2026-10-22
updated: 2026-10-22
applies_to: pw-web-ui
tags: [module, web, playwright]
supersedes: []
superseded_by: null
---

# pw-web-ui

> Web UI 自动化主引擎：Playwright 生命周期 + Page Object 门面 + Cucumber 事件桥 + 并发/会话/截图/trace 治理。

## 1. 定位

`pw-web-ui` 把 Playwright 的浏览器控制能力包装成可复用的测试基础设施：**Page Object 门面**隔离 Playwright 细节，**生命周期管理**保证并行执行下浏览器资源不泄漏，**Cucumber 事件桥**把 Gherkin 生命周期翻译成框架动作。

## 2. 在架构中的位置

```text
      pw-codegen-v3
             |
          pw-web-ui
          /      \
     pw-core   pw-reports-v2
```

| 项 | 内容 |
|---|---|
| 被谁依赖 | `pw-codegen-v3`（依赖本模块的 `PageObject` 模型） |
| 依赖谁 | `pw-core`、`pw-reports-v2` |
| 包名 | `com.hsbc.cmb.hk.dbb.automation.framework.web` |
| 分层约束 | `CodegenDecouplingArchTest` 保证本模块对 `pw-codegen-v3` **零编译依赖**（经 `RoleCodegenBridge` SPI 解耦） |

### 主要子包

| 子包 | 职责 |
|---|---|
| `core` | `FrameworkCore` 生命周期门面、`FrameworkState`、各 Provider 接口 |
| `page.engine` | `BasePage` 门面：`PageNavigation` / `PageWaits` / `PageInteractions` / `CookieManager` / `PageViewport` |
| `page.element` | `PageElement` / `PageElementList` 元素模型；`ManagedBasePage` 受管页契约 |
| `page.factory` / `page.binding` | `PageObjectFactory` 实例工厂；role 定位绑定 |
| `lifecycle` | Playwright / Context / Page / 浏览器生命周期、并发执行器、截图、trace、锁 |
| `listener` | `PlaywrightListener`（Cucumber ↔ 框架事件桥）、`LifecycleOrchestrator`、失败截图 |
| `config` | `WebFrameworkConfig`（Web 域配置门面，代理 `ConfigRegistry`） |
| `concurrent` | 并发闸门与分区键（SSO 感知） |
| `junit` | `CucumberWithPw` JUnit4 Runner（零配置挂载框架插件） |
| `accessibility` | axe-core 可访问性扫描 |
| `cloud` | BrowserStack 本地 / 云端策略 |

## 3. 引入

```xml
<dependency>
    <groupId>com.hsbc.cmb.hk.dbb.automation</groupId>
    <artifactId>pw-web-ui</artifactId>
    <version>${framework.version}</version>
</dependency>
```

**前置要求**：

- JDK 21+
- Playwright 浏览器二进制已安装：`mvn exec:java -Dexec.mainClass=com.microsoft.playwright.CLI -Dexec.args="install chromium"`

## 4. 最小可用示例

```java
import com.hsbc.cmb.hk.dbb.automation.framework.web.core.FrameworkCore;
import com.hsbc.cmb.hk.dbb.automation.framework.web.config.WebFrameworkConfig;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.engine.BasePage;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.element.PageElement;
import com.microsoft.playwright.options.AriaRole;

// 读配置：WebFrameworkConfig 是 ConfigRegistry 的门面，不持有字面量
boolean headless = WebFrameworkConfig.PLAYWRIGHT_BROWSER_HEADLESS.getBooleanValue();
int timeout = WebFrameworkConfig.PLAYWRIGHT_BROWSER_TIMEOUT.getIntValue();

// 生命周期：beforeTest 内确保初始化，afterTest 释放
FrameworkCore core = FrameworkCore.getInstance();
core.beforeTest();

// Page Object：继承 BasePage 获得导航/等待/定位能力
public class LoginPage extends BasePage {
    public void loginAs(String user) {
        navigateTo("https://example.com/login");
        PageElement input = elementByRole(AriaRole.TEXTBOX, "username");
        input.fill(user);
        elementByRole(AriaRole.BUTTON, "Sign in").click();
    }
}

new LoginPage().loginAs("u001");
core.afterTest(false);
```

**预期结果**：浏览器启动、导航到登录页、填入用户名并点击登录，`afterTest(false)` 关闭浏览器。

## 5. 核心 API 速查

| 类型 | 作用 | 何时使用 |
|---|---|---|
| `FrameworkCore` | 框架生命周期门面（`beforeTest` / `afterTest` / 清理） | 任何 Web 场景的入口 |
| `CucumberWithPw` | JUnit4 Runner，构造时幂等挂载框架插件与 ObjectFactory | `@RunWith(CucumberWithPw.class)` |
| `BasePage` | Page Object 基类门面（导航、等待、定位、截图、frame/弹窗/下载） | 自定义 Page Object 的父类 |
| `PageElement` | 元素模型，封装 locator 与交互动作 | Page Object 中的字段 |
| `WebFrameworkConfig` | Web 域配置门面 | 读取 `playwright.*` / `framework.*` 配置 |
| `PlaywrightManager` | Playwright / Browser / Context / Page 的获取与清理入口 | 需要底层句柄时 |
| `PlaywrightListener` | Cucumber 生命周期事件适配器 | 自定义场景前后处理 |

## 6. 配置

Web 域全部键为 `ConfigRegistry.WEB_*`（约 110 项），由 `WebFrameworkConfig` 门面代理。代表性条目：

| 键 | 默认值 | 说明 |
|---|---|---|
| `playwright.browser.type` | `chromium` | 浏览器类型 |
| `playwright.browser.headless` | `false` | 是否无头运行 |
| `playwright.browser.timeout` | `30000` | 浏览器级超时（毫秒） |
| `playwright.browser.slowMo` | `500` | 每步延迟（毫秒），调试用 |
| `playwright.context.trace.enabled` | `true` | 是否录制 trace |
| `playwright.context.trace.retention.max.files` | `200` | trace 保留文件数上限 |
| `playwright.context.recordVideo.dir` | `target/videos` | 录像目录 |
| `playwright.page.timeout` | `15000` | 页面动作超时 |
| `playwright.page.load.state` | `DOMCONTENTLOADED` | 导航等待的 load state |
| `framework.playwright.concurrent.max` | **自适应**（CPU/2，钳制 [2,32]） | 并发上限；**运行期计算，非固定字面量** |
| `framework.playwright.concurrent.parallelism` | `4` | 并行度 |
| `framework.screenshot.strategy` | `AFTER_EACH_STEP` | 截图时机策略 |
| `framework.output.directory` | `target/reports` | 报告输出目录 |
| `logging.root.level` | `INFO` | 日志级别；DEBUG/TRACE 控制详细诊断日志 |
| `framework.recording.enabled` | `true` | 是否将浏览器动作写入测试报告（独立于日志级别） |

> 页面事件订阅默认取向：交互/生命周期类（`page` / `load` / `frameNavigated` / `popup`）为 `true`；诊断类（`console` / `pageError` / `requestFailed` / `crash`）为 `false`。

## 7. 边界与非目标

- **不负责**网络拦截——`monitor` / `mock` / `modify` / `delay` 在 `pw-route`；本模块只提供 `ApiCaptureContext` 桥接点。
- **不负责**代码生成——`pw-codegen-v3` 通过 `RoleCodegenBridge` SPI 与本模块解耦，本模块对其零编译依赖。
- **不负责**报告渲染——只上报事件，渲染在 `pw-reports-v2`。
- **不管理**用例业务逻辑——Gherkin 步骤在 `test-automation` 仓库。

## 8. 测试与验证

| 类型 | 位置 |
|---|---|
| 单元测试 | `pw-web-ui/src/test/java/`（78 个，含 `CucumberWithPwTest`、`PageElementTest`、`ConcurrencyGateTest`） |
| 架构守卫 | `CodegenDecouplingArchTest` |

```bash
# 在仓库根目录执行
mvn -pl pw-web-ui -am test
```

## 9. 延伸阅读

- [Playwright 1.62 网络 API 分层使用指南](../docs/architecture/Java-Playwright-1.62-网络API分层指南.md)
- [SSO 并发与会话隔离设计](../docs/architecture/SSO并发与会话隔离设计.md)
- [根 README 模块表](../README.md#模块)
