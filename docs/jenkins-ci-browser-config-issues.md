# Jenkins CI 浏览器 / 配置问题分析（框架底座）

> 场景：Jenkins（Windows agent）上的 `playwright-automation` 流水线，先装框架（`pw_automation_framework`），再 checkout 业务工程 `test-automation` 跑 `@test`。
> 本文档记录本次构建暴露的问题、根因与修复建议，供排障与后续底座改造使用。

## 1. 运行环境与流程

| 项 | 值 |
|----|----|
| 框架仓库 | `https://github.com/Bruce-lee-star/pw_automation_framework.git`，分支 `DEV-V5` |
| 业务仓库 | `https://github.com/Bruce-lee-star/test-automation.git`，分支 `DEV-V5`，checkout 到 `test-automation/` 子目录 |
| 安装阶段 | `mvn -q -B install -DskipTests -Dcheckstyle.skip=true -Dspotbugs.skip=true -Dcve.gate.skip=true` |
| 浏览器安装阶段 | 被跳过（`INSTALL_BROWSERS=false`） |
| 测试阶段 | `cd test-automation && mvn -q -B test -Dtest=CucumberTestRunnerIT -Dcucumber.filter.tags="@test" ...` |
| 关键前置 | 业务仓库**未上传** `pw.conf` / `pw.properties`（环境 URL 等配置缺失） |

## 2. 症状时间线

| 时间 | 事件 |
|------|------|
| 22:53:22 | 构建开始；框架安装通过 |
| 22:53:39 | test-automation checkout 完成；浏览器安装阶段 skipped |
| 22:53:53 | 3 个登录场景（dbb-1/2/3）在 452ms / 35ms / 16ms 内失败：`URL configuration is required but not found for environment: O63_SIT1` |
| 22:53:54 | `testbed - MONITOR` 场景开始（首个需要浏览器的场景） |
| 22:57:00 | HangWatchdog：主线程已运行 180028ms，栈停在 `PlaywrightInitializer.waitForDownloadProcess` |
| 22:58:53 | `Playwright chromium browser download timed out after 5 minutes - forcibly terminating`（第 1 次） |
| 23:00:59 | HangWatchdog：已运行 420236ms |
| 23:03:53 | 第 2 次 `download timed out after 5 minutes` |
| 23:04:05 | 启动失败：`Executable doesn't exist at ...\test-automation\0\chromium-1234\chrome-win64\chrome.exe` |

单场景因浏览器初始化被阻塞 **约 10 分 11 秒**。

## 3. 问题清单

| # | 严重度 | 归属 | 问题 | 证据 | 建议 |
|---|--------|------|------|------|------|
| 1 | 致命 | 流水线 | `PLAYWRIGHT_BROWSERS_PATH='0'` 写坏浏览器路径 | `Jenkinsfile:63`；日志 `effective playwright.browsers.path (layer=ENVIRONMENT)`；路径出现 `...\test-automation\0\chromium-1234\...` | 删除该 env（或设为 `.playwright/browsers` / 绝对路径）。`0` 是 Playwright **Node** 版哨兵值，Java 版按字面目录处理 |
| 2 | 致命 | 底座+流水线 | 运行时惰性下载浏览器，5 分钟超时×2，单场景卡 10 分钟且占 Browser 锁 | `ensureBrowsersInstalled→installBrowsers`，调用栈在 `withBrowserLock` 内 | CI 预装浏览器；提供"禁止运行期自动下载"开关 |
| 3 | 高 | 底座 | `PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD` 设为字符串 `"0"`，语义与意图相反（非空即真=跳过下载） | `PlaywrightInitializer.java:401` | 想启用下载应**不设置**该变量 |
| 4 | 高 | 底座 | 安装失败不 fail-fast，继续 launch，报错不可执行 | `ensureBrowsersInstalled` 仅 warn（`PlaywrightInitializer.java:276-277`） | 失败即抛明确异常，附带解析到的 browsers path 与配置键 |
| 5 | 高 | 底座 | 缺启动前预检（preflight） | 无 | 套件启动时校验浏览器可执行文件存在 |
| 6 | 高 | 配置/底座 | 默认有头模式（`headless=false`）跑 CI | 配置 `chromium_headed`；`playwright.browser.headless` 默认 `false` | CI 强制 `headless=true` |
| 7 | 高 | 底座 | 无跨场景熔断 | 后续场景会各自重试 5 分钟下载 | 首次失败后短路后续场景 |
| 8 | 高 | 底座 | 缺配置时无启动期诊断 | 3 个场景在 `BDDUtils.java:64` 抛异常；框架未告警"未加载到配置文件" | 启动期打印已加载配置源，缺失时 WARN/FAIL |
| 9 | 中 | 底座 | HangWatchdog 只诊断不熔断 | 180s/420s 采样后仍等满 5min×2 | 触发中止/降级 |
| 10 | 中 | 环境 | 控制台中文/制表符乱码（mojibake） | `?T?T...`、`?????` | `-Dfile.encoding=UTF-8`（含 Jenkins 侧），或日志避免非 ASCII 装饰字符 |
| 11 | 中 | 流水线 | 安装命令绕过框架代理注入 | `Jenkinsfile:101` 用原生 `mvn exec:java ... CLI`；框架自带 `HTTP(S)_PROXY` 注入 | 用框架安装入口或预装 |
| 12 | 待确认 | 底座 | 报告可能只走 LogSink | 日志仅见 `LogSink - [report] case FAILED` | 确认 HTML 产物是否真的落盘 |

## 4. 根因链（问题 1）

```
Jenkinsfile environment { PLAYWRIGHT_BROWSERS_PATH = '0' }
        │  （框架把 OS 环境变量视为配置层，键名归一化后覆盖 playwright.browsers.path）
        ▼
ConfigReader: effective playwright.browsers.path (layer=ENVIRONMENT) = "0"
        ▼
PlaywrightInitializer.initializePlaywrightPaths()  → System property PLAYWRIGHT_BROWSERS_PATH = "0"
PlaywrightInitializer.installBrowsers()            → 子进程 env PLAYWRIGHT_BROWSERS_PATH = "0"
        ▼
Playwright 解析 browsers 目录 = <cwd>/0   （默认本应为 .playwright/browsers）
        ▼
下载超时被 kill → 无 chrome.exe → launch 失败：
  Executable doesn't exist at ...\test-automation\0\chromium-1234\chrome-win64\chrome.exe
```

## 5. 修复优先级

1. **删除 `Jenkinsfile:63` 的 `PLAYWRIGHT_BROWSERS_PATH='0'`**（一行改动，先重跑验证）。
2. agent 预装浏览器到固定目录；CI 设 `headless=true`；关闭运行期自动下载。
3. 底座加固：preflight 校验、失败熔断、`PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD` 语义修复、配置缺失告警、控制台 UTF-8。

## 6. 验证方法

- 修正后确认日志出现 `browser downloaded successfully` 或直接命中已装浏览器，且路径不含 `\0\`。
- 浏览器可执行文件存在：`<browsers.path>/chromium-<rev>/chrome-win64/chrome.exe`。
- 首个 Web 场景不再触发下载（无 `downloading...` / 无 5 分钟超时）。
- 配置：补齐 `pw.conf` / `pw.properties` 后，`URL configuration is required ...` 消失。

## 附：报告 UI 待优化（本次后续处理）

- 「Execution steps」卡片宽度与上方标题卡、下方 Tab 卡**未对齐**。
- 失败信息在报告中**独立展示**（需与步骤结合展示）。
