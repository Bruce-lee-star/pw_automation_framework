# 自建报告方案（零 Serenity 依赖 + Cucumber 集成）

> 目标：产出与 Serenity BDD 视觉一致的 HTML 报告（绿条顶栏、用例列表、步骤树、红 / 绿 / 黄徽章、聚合 dashboard），但
>
> **不引用任何 serenity-* Maven 构件**
>
> （包括 serenity-report-resources JAR）。
> 同时接入 Cucumber，用其 SPI 把 Gherkin 事件喂到我们自己的报告模型。



***

## 一、依赖矩阵（最终形态）



| 依赖                             | 用途                           | 是否允许                           |
| ------------------------------ | ---------------------------- | ------------------------------ |
| `jackson-databind`             | per-test JSON 序列化            | ✅                              |
| `freemarker`（Apache）           | HTML 模板渲染                    | ✅（独立 Apache 项目，不是 serenity 产物） |
| `cucumber-plugin`（io.cucumber） | Cucumber 事件 SPI              | ✅（最小 JAR，仅事件接口）                |
| `slf4j-api`                    | 日志                           | ✅                              |
| **serenity-core**              | WebDriver/Guice/StepEventBus | ❌                              |
| **serenity-junit**             | JUnit runner                 | ❌                              |
| **serenity-model**             | TestOutcome POJO             | ❌                              |
| **serenity-report-resources**  | 静态资源 JAR                     | ❌                              |
| **selenium-java**              | WebDriver                    | ❌                              |

净新增依赖 4 个，全部是通用基础库，没有任何 BDD 框架绑定。



***

## 二、目录结构



```
pw-reports/
├── pom.xml
└── src/main/
    ├── java/com/hsbc/.../reports/
    │   ├── model/
    │   │   ├── TestOutcome.java          # 我们自己的 POJO
    │   │   ├── TestStep.java
    │   │   ├── TestResult.java           # SUCCESS/FAILED/SKIPPED/PENDING/ABORTED
    │   │   ├── Story.java
    │   │   └── Tag.java
    │   ├── event/
    │   │   ├── ReportBus.java            # 进程内事件总线（同步派发）
    │   │   └── RecordingSession.java     # 一个用例 = 一个 session
    │   ├── render/
    │   │   ├── JsonWriter.java
    │   │   ├── HtmlRenderer.java         # FreeMarker
    │   │   ├── AggregateBuilder.java     # index.html + summary.json
    │   │   └── StaticAssets.java         # 拷 resources/assets/*
    │   └── cucumber/
    │       └── PwReportPlugin.java       # implements cucumber.plugin.EventListener
    └── resources/
        ├── templates/                    # 我们自己写的 .ftl（视觉对齐 serenity）
        │   ├── layout.ftl
        │   ├── test-detail.ftl
        │   └── dashboard.ftl
        └── assets/                       # 我们自己拷的 CSS/JS/图片（不来自 serenity JAR）
            ├── css/report.css
            ├── js/dashboard.js
            └── fonts/
```



***

## 三、视觉对齐策略（不拷 serenity 模板，自己写）

Serenity 报告的视觉语言很简单，自己写 3 个 .ftl 即可：



| Serenity 元素                                    | 我们自己实现                                       |
| ---------------------------------------------- | -------------------------------------------- |
| 顶部绿色 success bar / 红色 fail bar                 | `.banner-success` / `.banner-fail` CSS class |
| 用例列表表格（feature/scenario/duration/result badge） | Bootstrap table + badge                      |
| 步骤树（缩进 + 展开 / 折叠）                              | 一个 `<ul>` + 原生 JS toggle                     |
| 失败 stack 灰色块                                   | `<pre class="stack">`                        |
| 左侧 requirements 导航                             | 可选 v2 再做，v1 不做                               |
| 图表（pie/treemap）                                | v1 不做，v2 用 Chart.js CDN 或自画 SVG              |

**做法**：



1. 打开一个真 Serenity 报告的 HTML，view-source，看它的 CSS class 名和 DOM 结构。

2. 我们写自己的 `report.css`（\~200 行），模仿配色和排版，不抄 serenity 的 bootstrap 主题文件。

3. 顶部导航、面包屑、footer 都自己写。

**好处**：以后想改配色 / 换布局 / 加 Dark Mode，全是自己的代码，不受 serenity 升级绑架。



***

## 四、Cucumber 集成（核心）

### 4.1 Cucumber 事件模型

Cucumber 6+ 的 SPI 在 `cucumber-plugin` JAR：



```
package cucumber.plugin;
public interface EventListener {
    void setEventPublisher(EventPublisher publisher);
}
public interface ConcurrentEventListener extends EventListener {}
```

事件类型（`cucumber-plugin` 提供）：



* `TestCaseStarted`（URI/Line/Tags）

* `TestStepStarted`（PickleStep）

* `TestStepFinished`（Result：status + duration + error）

* `TestCaseFinished`（Result + duration）

* `TestRunFinished`（全局收尾）

### 4.2 我们的 Plugin



```
package com.hsbc.reports.cucumber;

import cucumber.plugin.EventListener;
import cucumber.plugin.EventPublisher;
import cucumber.plugin.event.*;

public class PwReportPlugin implements ConcurrentEventListener {
    private final ReportBus bus = new ReportBus("target/site/serenity");

    @Override
    public void setEventPublisher(EventPublisher publisher) {
        publisher.registerHandlerFor(TestCaseStarted.class, this::onCaseStarted);
        publisher.registerHandlerFor(TestStepStarted.class, this::onStepStarted);
        publisher.registerHandlerFor(TestStepFinished.class, this::onStepFinished);
        publisher.registerHandlerFor(TestCaseFinished.class, this::onCaseFinished);
        publisher.registerHandlerFor(TestRunFinished.class, e -> bus.finishRun());
    }

    private void onCaseStarted(TestCaseStarted e) {
        bus.startCase(e.getTestCase().getUri(), e.getTestCase().getName(),
                e.getTestCase().getTags());
    }
    // ... stepStarted/stepFinished/caseFinished 略
}
```

### 4.3 注册方式

`@RunWith(Cucumber.class)` 或 `junit-platform.properties`：



```
cucumber.plugin=com.hsbc.reports.cucumber.PwReportPlugin:target/site/serenity
```

或在 `@CucumberOptions`：



```
@CucumberOptions(plugin = {"com.hsbc.reports.cucumber.PwReportPlugin:target/site/serenity"})
```

### 4.4 与 Playwright/Route 证据的桥接

Cucumber 的 step 是 Gherkin 行（"当我登录"），我们在 step 里做的 Playwright 动作、route 拦截、API 采集是另一个维度。桥接方式：



* Cucumber step 开始 → `bus.startStep(stepName)`

* glue 代码里调 `RouteEvidenceRegistry.record(...)` 时，顺手 `bus.attachEvidence(text)` 把 route 证据挂到**当前正在执行的 step** 上

* Cucumber step 结束 → `bus.finishStep(duration, result, error)`

这样 HTML 报告里：



```
Scenario: 用户登录
  Given 用户在登录页                          ✅ 120ms
    └─ MONITOR: POST /login expectStatus=200 actual=200
  When 输入用户名和密码                        ✅ 340ms
  And 点击登录按钮                            ✅ 890ms
    └─ DELAY: delayed 500ms then resumed
    └─ MONITOR: POST /j_spring_security_check expectStatus=302 actual=302
  Then 跳转到主页                              ❌ 5100ms
    └─ expected: text "313131" visible
    └─ actual: timeout waiting locator
```



***

## 五、事件总线设计（不依赖 Cucumber 也能用）



```
public final class ReportBus {
    private final Path outputDir;
    private final Deque<RecordingSession> stack = new ArrayDeque<>();  // 支持 nested step

    public void startCase(URI feature, String name, List<Tag> tags) { ... }
    public void startStep(String description) { ... }
    public void attachEvidence(String line) { ... }   // route/API 证据
    public void finishStep(long durationMs, boolean ok, String error) { ... }
    public void finishCase(TestResult r, Throwable failure) { ... }
    public void finishRun() {                          // 聚合渲染
        renderPerTestHtml();
        renderIndexHtml();
        copyStaticAssets();
    }
}
```

**关键设计**：`ReportBus` 不绑定 Cucumber。Cucumber Plugin 只是它的一个驱动；如果将来直接用 JUnit 写测试，也可以从 `@Before/@After` 调同一个 bus。



***

## 六、POJO 设计（我们自己的，\~300 行）



```
public class TestOutcome {
    private String name;
    private String feature;
    private String description;
    private List<TestStep> steps = new ArrayList<>();
    private TestResult result;
    private long startTime;
    private long durationMs;
    private String failureMessage;
    private String failureStackTrace;
    private List<String> tags = new ArrayList<>();
}

public class TestStep {
    private String description;
    private long durationMs;
    private TestResult result;
    private List<TestStep> children = new ArrayList<>();   // nested
    private List<String> evidence = new ArrayList<>();       // route/API 证据行
    private String error;
}
```

**故意不做的**（serenity 里有但我们不需要）：



* screenshots / webdriver / sessionId

* manual / context / driver

* DataTable / DataSet（除非以后真要做参数化表格）

* release /iteration/ Jira issue 联动

* 多层 requirements 树



***

## 七、渲染流程



```
TestRunFinished 事件到达
    │
    ├── 1. 对每个 RecordingSession：
    │       ├── Jackson 序列化为 <case-name>.json
    │       └── FreeMarker 渲染 <case-name>.html（templates/test-detail.ftl）
    │
    ├── 2. 聚合：
    │       ├── 统计 pass/fail/skip 数
    │       ├── 按 feature 分组
    │       └── FreeMarker 渲染 index.html（templates/dashboard.ftl）
    │
    └── 3. 静态资源：
            └── 拷 resources/assets/* → target/site/serenity/assets/
```



***

## 七、补充能力：截图 / 实时事件上报 / Route 断言失败即时捕获

### 7.1 截图（Playwright `page.screenshot()`）

**触发时机**（可配置）：



| 时机                                 | 默认   | 说明                                            |
| ---------------------------------- | ---- | --------------------------------------------- |
| step 失败时                           | ✅ 强制 | 必拍，写到 step 失败证据里                              |
| 每个 step 结束后                        | ❌ 关  | 量大，按需开（`pw.report.screenshot.each-step=true`） |
| 手动调用 `report.takeScreenshot("注释")` | ✅    | glue 里显式触发                                    |
| scenario 失败时整页                     | ✅    | 兜底，即使 step hook 没接住                           |

**落盘布局**（对齐 serenity 约定）：



```
target/site/serenity/
├── <scenario-name>.html
└── <scenario-name>/
    ├── screenshots/
    │   ├── step-003-2024-01-15T102301.png      # step 序号 + 时间戳
    │   └── failure-step-005-2024-01-15T102333.png
    └── pages/
        └── failure-fullpage-005.png            # fullPage=true 的整页
```

**报告里怎么显示**：



* step 失败 → 展开 step 时显示截图缩略图（`<img src="screenshots/step-003.png" class="thumb">`），点击 lightbox 放大

* 截图字段挂在 `TestStep.screenshot`，HTML 模板里 `<#if step.screenshot?>` 才渲染

**实现要点**：



* 用一个独立线程池做截图 IO（`page.screenshot()` 本身是同步阻塞 CDP 调用，但写盘用 `CompletableFuture.runAsync`）

* 截图路径在 step 失败 hook 里拍，不要在 `@After` 拍（此时 page 可能已经关了）

* 大图自动缩放到宽 1280（Playwright `clip` 或写盘后 ImageIO 缩放），避免报告目录爆掉

### 7.2 实时事件上报（ReportSink 抽象）

**需求**：报告不只是跑完一次性出 HTML，还要能在执行过程中把事件流推到一个外部 collector（比如你们内部的测试观测平台 / Webhook / 日志系统）。

**设计：Sink 模式**



```
public interface ReportSink {
    void onCaseStarted(TestOutcome caseOutcome);
    void onStepStarted(TestStep step);
    void onStepFinished(TestStep step);
    void onCaseFinished(TestOutcome caseOutcome);
    void onRunFinished(ReportSummary summary);
}
```

`ReportBus` 持有 `List<ReportSink>`，每个事件 fan-out 到所有 sink。

**内置 3 个 Sink**：



| Sink            | 作用               | 实现                              |
| --------------- | ---------------- | ------------------------------- |
| `LocalHtmlSink` | 本地写 HTML/JSON    | 就是前面的 JsonWriter + HtmlRenderer |
| `HttpPushSink`  | 实时推到外部 collector | 异步 HTTP POST 到配置的 URL，事件包成 JSON |
| `LogSink`       | 控制台打印            | 等价 cucumber `pretty`，方便调试       |

**HttpPushSink 要点**：



* 用单独的线程池（`Executors.newSingleThreadExecutor`），**绝不能阻塞测试线程**

* 队列满时丢弃 + 打 warn（报告不能因为 collector 挂了影响测试）

* 配置：`pw.report.push.url` / `pw.report.push.token` / `pw.report.push.enabled=false`（默认关）

* 失败重试：不重试，事件丢失就丢（HTML 本地报告是真相源）

### 7.3 Route 断言失败即时抛错的报告捕获

**现状**：route 断言失败 → 立即抛 `RouteAssertionFailure` → Cucumber step 直接 fail → 后续 step 不跑。

**问题**：如果不截获这个异常，报告里 step 的 error 只会显示 "AssertionError: expected 200 got 500"，但 `RouteAssertionFailure` 里带的丰富上下文（pattern/url/expectStatus/actualStatus/bodyPreview/postDataPreview/headers）就丢了。

**设计：双层捕获**



```
Glue 方法
  └─ route.expect(...).assertPass()
       └─ 抛 RouteAssertionFailure
            │
            ├─ ① ReportBus 钩子（在 StepInterceptor 里 catch）：
            │     - 把 failure 序列化成结构化 evidence 行
            │     - 调用 bus.attachEvidence(formatFailure(f))
            │     - 标记当前 step FAILED，附带 failureMessage + failureStackTrace
            │     - （可选）自动拍一张失败截图
            │
            └─ ② 重新抛出原异常 → Cucumber 原生 fail-fast 行为不变
```

**实现位置**：一个全局的 Cucumber `TestCaseResultListener`（或我们自己的 StepInterceptor AOP）：



```
publisher.registerHandlerFor(TestStepFinished.class, e -> {
    if (e.getResult().getStatus() == Status.FAILED) {
        Throwable error = e.getResult().getError().orElse(null);
        if (error instanceof RouteAssertionFailure) {
            bus.attachEvidence(formatRouteFailure((RouteAssertionFailure) error));
            bus.takeFailureScreenshot();
        }
        bus.finishStep(...);  // error 字段带上完整 stack
    }
});
```

`formatRouteFailure(f)`**&#x20;输出示例**（挂在 step evidence 下）：



```
[ROUTE ASSERT FAILED]
  pattern: POST /api/login
  expectStatus: 200
  actualStatus: 500
  bodyAssertion: expected "success=true", body preview: "{"error":"db down"}"
  monitorTimeoutMs: 5000
  requestHeaders: Content-Type=application/json
  responseHeaders: X-Trace-Id=abc-123
```

**关键不变式**：



* 抛错行为不变（fail-fast 还是 fail-fast，后续 step 还是不跑）

* 报告里能看到完整的 route 失败上下文，不只是一行 message

* 不吞异常 ——catch 完必须 rethrow，否则 Cucumber 会认为 step 过了



***

## 八、分阶段落地

### Phase 1：裸 HTML 跑通（3-5 天）



* [ ] 建 `pw-reports` Maven 模块

* [ ] POJO：TestOutcome/TestStep/TestResult/Tag

* [ ] `ReportBus` + `ReportSink` 接口

* [ ] `LocalHtmlSink`：JsonWriter 写 per-test JSON

* [ ] 手写一个极简 `index.html` 字符串模板（pass/fail 表格）

* [ ] `PwReportPlugin` 接 Cucumber SPI

* [ ] **RouteAssertionFailure 结构化捕获**（7.3）：step 失败 hook 里把 route 上下文挂到 evidence

- **验收**：跑一个 Gherkin 用例，`target/site/serenity/` 下有 JSON 和极简 HTML，浏览器打开能看到结果；故意 fail 一个 route，evidence 区有完整 expected/actual

### Phase 2：对齐 serenity 视觉 + 截图（1 周）



* [ ] 写 `report.css`（\~200 行，绿 / 红 banner + badge + 步骤树）

* [ ] FreeMarker 模板：`test-detail.ftl`（步骤树 + stack + 截图缩略图）+ `dashboard.ftl`（聚合表）

* [ ] route 证据挂 step.children

* [ ] **截图（7.1）**：失败 step 自动拍、`<scenario>/screenshots/` 落盘、HTML 缩略图 lightbox

- **验收**：和真 serenity 报告并排，肉眼可接受的相似度；失败用例点开能看到失败截图

### Phase 3：实时事件上报 + 增强（按需）



* [ ] **HttpPushSink（7.2）**：异步推外部 collector，可开关

* [ ] 失败 stack 折叠 / 展开

* [ ] 标签过滤、按 feature 分组折叠

* [ ] 顶部饼图（Chart.js CDN 或自画 SVG）

* [ ] 可选：`pw.report.screenshot.each-step=true` 每步截图



***

## 九、风险



| 风险                                     | 缓解                                                                         |
| -------------------------------------- | -------------------------------------------------------------------------- |
| 自己写 CSS 调不出 serenity 那个味道              | Phase 2 花半天时间照抄配色（serenity 的 success 绿是 `#5fcc7a` 左右），排版直接用 bootstrap grid |
| Cucumber 事件时序（后台 / 钩子 / 参数化场景）复杂       | 先只支持最朴素的 scenario，再迭代 background/parametrize                               |
| RouteEvidenceRegistry 已经写了一份 JSON/HTML | 桥接时把它的 record 输出当 `evidence` 行喂给 bus，不重复造轮子                                |
| FreeMarker 增加学习成本                      | 模板就 3 个，每个 <100 行；不想用 FreeMarker 也可以直接 `String.format` + 字符串拼接             |



***

## 十、一句话结论

**完全可行，零 serenity 依赖**。新建 `pw-reports` 模块，只引 jackson/freemarker/cucumber-plugin/slf4j；用 Cucumber 的 `ConcurrentEventListener` SPI 接 Gherkin 事件；自己写 POJO + FreeMarker 模板 + 一个 `report.css` 复刻 serenity 视觉；route 证据作为 step 的 evidence 行挂上去。配套三件事：失败 step 自动截图（`<scenario>/screenshots/` 落盘 + HTML 缩略图）、`ReportSink` 抽象支持本地 HTML / 实时 HTTP 推送 / 控制台日志三路输出、RouteAssertionFailure 在 step 失败 hook 里被结构化捕获后再 rethrow（fail-fast 行为不变，但报告能看到完整 expected/actual/body 上下文）。Phase 1 一周出第一版 HTML。