---
id: DOC-MOD-REPORTS-0001
title: pw-reports-v2 模块说明
type: module
status: active
owner: team-automation
created: 2026-10-22
updated: 2026-10-22
applies_to: pw-reports-v2
tags: [module, reporting, serenity]
supersedes: []
superseded_by: null
---

# pw-reports-v2

自建 Serenity 兼容报告模块。**零 `serenity-*` 依赖**，镜像 Serenity 4.3.4 的
JSON schema 与报告形态（DR-1）。步骤注解为框架自有 `@Step`/`@Steps`
（`…report.annotation`，无供应商锁定），存量业务代码把 import 切到框架注解即可迁入。

- 坐标：`com.hsbc.cmb.dbb.hk.automation:pw-reports-v2:1.0.1`
- Java 21 · Cucumber 7.x · Jackson · FreeMarker · ByteBuddy · SLF4J
- 产物目录：`target/site/serenity/`（与 Serenity 一致，历史趋势沿用同一约定）

> **为什么叫 v2**：主工程 `pw_automation_framework` 内**已存在** `artifactId=pw-reports`
> 的模块（包 `…framework.common.reporting`，封装 `Serenity.recordReportData` +
> `SummaryReportGenerator`(ftlh) + 监控失败报告）。两者职责不同（本模块是**替代实现**，
> 那个是**Serenity 调用封装**），故本模块独立命名为 `pw-reports-v2` 并存，不覆盖、不冲突。
> 迁移完成后两者可共存（老报告仍能生成），确认无回退需求后再决定是否下线旧模块。

## 1. 接入（Maven）

```xml
<dependency>
    <groupId>com.hsbc.cmb.dbb.hk.automation</groupId>
    <artifactId>pw-reports-v2</artifactId>
    <version>1.0.1</version>
</dependency>
```

## 2. 注册路径（两条，按测试运行器选）

### 2.1 Cucumber 事件插件（步骤树 / 用例 / run 生命周期）

| 运行器 | 注册方式 |
|---|---|
| JUnit Platform（cucumber-junit-platform-engine） | classpath 根 `cucumber.properties` 写 `cucumber.plugin=com.hsbc.cmb.hk.dbb.automation.framework.report.cucumber.PwReportPlugin`；或 `@CucumberOptions(plugin = …PwReportPlugin)` |
| JUnit 4（`@RunWith(Cucumber.class)`，**存量框架即此路径**） | SPI 不生效，必须 `@CucumberOptions(plugin = {"…PwReportPlugin"})` 显式声明 |

> SPI 文件 `META-INF/services/io.cucumber.plugin.EventListener` 已随 jar 提供，
> JUnit Platform 引擎下无需任何配置即自动加载。

### 2.2 ObjectFactory（glue 实例化 + `@Steps` 注入）

SPI 文件 `META-INF/services/io.cucumber.core.backend.ObjectFactory` 已随 jar 提供
（`PwReportObjectFactory`）。

**与 serenity-cucumber 共存窗口**：两者都注册 ObjectFactory SPI 时，Cucumber 报
"multiple object factories" 歧义，需在 `cucumber.properties` 显式二选一：

```properties
cucumber.object-factory=com.hsbc.cmb.hk.dbb.automation.framework.report.step.PwReportObjectFactory
```

- 迁入期想**双写对比**：分别跑两个 JVM（各自 object-factory），比对 `target/site/serenity/` 产物；
- 完全切干净后：删除 serenity-cucumber 依赖，SPI 唯一，属性可删。

不复制 Serenity 的 `@Managed`/webdriver 注入（有意）——页面对象走框架自有
`PageObjectFactory` + `@AutoBrowser`，不经本 DI。

## 3. `@Step`/`@Steps` 契约（框架自有注解）

注解为框架自有 `com.hsbc...report.annotation.Step` / `Steps`（**唯一提供者**，
无 `net.serenitybdd.*` 同 FQN 副本、无 split-brain）。存量业务代码把
`import net.serenitybdd.annotations.Step/Steps` 换成框架注解 import 即可。

- `@Steps` 字段：`PwStepInjector` 注入 ByteBuddy 代理（共享语义：`@Steps(shared=true)`
  同类型字段在**当前线程**共享实例）；
- `@Step` 方法：代理拦截 → 入步骤树，记录描述/时长/结果/嵌套/截图/证据；
- 降级（不静默失败）：`@NoStepInterception`、final 类/方法 → 原始实例直通
  （字段可用、无拦截）；接口/抽象类 → null；
- kill-switch：`pw.report.enabled=false` 时 dispatcher 直通，测试行为与无本模块一致。

## 4. 配置（`pw.report.*`，系统属性 > 环境变量 > 默认值）

环境变量名 = 属性名大写、`.`/`-` → `_`（`pw.report.enabled` → `PW_REPORT_ENABLED`）。

**零配置即可跑。** 只有前 4 项需要你决策，各管一件事：

| 属性 | 默认 | 说明 |
|---|---|---|
| `pw.report.enabled` | `true` | kill-switch；false = 总线整体静默 |
| `pw.report.output` | `target/site/serenity` | 产物根目录 |
| `pw.report.screenshot` | `FOR_FAILURES` | `BEFORE_AND_AFTER_EACH_STEP` / `AFTER_EACH_STEP` / `FOR_FAILURES` / `DISABLED`（含历史别名 `ON_FAILURE` / `AFTER_FAILING_STEP` / `OFF` / `NONE`） |
| `pw.report.push.url` | 空 | **非空即启用推送**（无需单独的 enabled 开关） |

> **CI 产物落地：`pw.report.output` 支持工程目录之外的绝对路径**（等价环境变量 `PW_REPORT_OUTPUT`）。
> CI 每次都是全新工作区，产物若留在工程目录内会随代码检出一起丢失，故推荐显式指向外部持久目录：
> `-Dpw.report.output=D:\ci-artifacts\serenity`（Windows）或 `PW_REPORT_OUTPUT=/opt/ci/serenity`（Linux，环境变量形式无需改动任何 pom）。
> 目录不存在时框架自动创建；不指定时仍为相对路径 `target/site/serenity`（按进程工作目录解析）。

以下 3 项通常不用动，只在特殊场景覆盖：

| 属性 | 默认 | 说明 |
|---|---|---|
| `pw.report.limits` | `50,2048,500` | 三合一限额档位 `证据行数,单行字节,API条数`；缺项沿用默认，非法值整体回退并告警 |
| `pw.report.scrub.extra-patterns` | 空 | 逗号分隔正则，追加脱敏规则；非法正则跳过不炸 |

**设计取舍**：刻意不做「开关与参数分离」（没有 `x.enabled` + `x.url` 二连——写了 url
就等于开了），也不做「同族旋钮平铺」（三个 `max-*` 收成一个 `limits` 档位——实践中
它们总是同向调整，分开只会诱导逐项微调）。**旧键被忽略而非报错**，所以从旧版配置
升级不会崩：`push.enabled` / `history.enabled` / `screenshot.mode` / `screenshot.max-bytes`
/ `screenshot.mask-inputs` / `max-evidence-lines` / `max-evidence-bytes` / `max-api-operations`
均已收敛，迁移对照见下。

| 旧键 | 新写法 |
|---|---|
| `pw.report.push.enabled=true` + `pw.report.push.url=X` | `pw.report.push.url=X` |
| `pw.report.history.enabled` / `pw.report.history.max-runs` | 已移除（历史趋势累积功能下线，报告只代表本次运行） |
| `pw.report.screenshot.mode=X` | `pw.report.screenshot=X` |
| `pw.report.screenshot.mask-inputs` / `screenshot.max-bytes` | 已移除（无消费点的死配置） |
| `max-evidence-lines=50,max-evidence-bytes=2048,max-api-operations=500` | `pw.report.limits=50,2048,500` |

**脱敏（ScrubGate，fail-open）**：敏感头（Authorization/Cookie/Set-Cookie 等
按名匹配）值整体打码；URL query 参数按名打码（绝对 URL 与无 scheme 请求行
双形态，对已脱敏内容幂等）；请求体按 key 名打码（密码/token/secret 等）；
超长行截断（`…[TRUNCATED]`）。任何脱敏异常只记日志不阻断采集。

## 5. 产物布局

```
target/site/serenity/
├── index.html                      # 总览页（汇总 + feature 下钻）
├── feature-*.html                  # 每个 feature 一页
├── monitor-failures.html           # API 监控失败独立报告（无失败不落文件）
├── email-report.html               # 邮件用单页自包含报告
├── serenity-summary.json           # 汇总 JSON
├── serenity-summary.csv            # 汇总 CSV
├── <outcomeId>.html                # per-case 展示页
├── <outcomeId>.json                # per-case 真相源（镜像 Serenity schema）
└── assets/report.css               # 静态资产
```

- **JSON 是真相源**：`CaseFinished` 即实时落盘（CI 超时被杀不丢已完成用例）；
  HTML 在 `RunFinished` 统一渲染，单项失败隔离（失败降级最小页，不影响其余
  用例与测试本身）。
- **API 监控失败通道**：`ReportBus.recordApiMonitorFailure(handle, line)`（跨线程安全，
  必经 ScrubGate，用例级、≤200 条）→ per-case JSON `apiMonitorFailures` 字段
  （additive-only）→ `monitor-failures.html`（feature 分组，无 owner 维度；
  文件存在即有失败，CI 收集逻辑与旧版零改动）。
- HTML 模板（FreeMarker）位于 `src/main/resources/templates/`，视觉对齐 Serenity
  报告形态；`SchemaContractTest` 保证 JSON ⊆ schema，`SnapshotGateTest` 冻结 HTML
  结构防漂移。

## 6. 构建与门禁

```bash
mvn clean verify          # 全量：137 个测试（含 11 类门禁）
```

门禁测试（改动脱敏/映射/总线/拦截/渲染/schema/迁移门禁后必跑）：

| 门禁 | 测试 | 守什么 |
|---|---|---|
| 脱敏 | `ScrubGateTest` | 头/query/body/截断规则不回归 |
| 映射 | `CucumberMappingTest` | Status 全枚举映射、outcomeId 生成与冲突后缀 |
| 并发 | `BusConcurrencyTest` | 总线多线程安全、finishRun 幂等、per-thread 共享缓存隔离 |
| 拦截 | `StepInterceptionCoverageTest` | @Step 端到端（树形/嵌套/失败/final/降级/shared 语义/kill-switch） |
| 快照 | `SnapshotGateTest` | HTML 结构冻结；模板有意变更后重新冻结：`mvn test -Dtest=SnapshotGateTest -Dpw.report.test.write-snapshot=true` |
| 契约 | `SchemaContractTest` | JSON 路径 ⊆ schema；schema 演进只许 additive；reader 容忍未知字段 |
| 迁移 | `ConsistencyGateTest` | 双跑四一致门禁（coverage/result/step-tree/summary） |
| 接缝 | `NetworkEvidenceBridgeTest` | 网络证据入步骤证据 + token 脱敏 |
| 接缝 | `QueuedNetworkEvidenceBridgeTest` | 跨线程归位 / 队列溢出丢最旧 / 无场景静默降级 / 并发入队不丢 |
| 稳定 | `OutcomeIdTest` | outcomeId 确定性、slug 规则、冲突后缀 |
| 事件链 | `PluginEventChainTest` | **插件入口端到端**：hook 不入树、@Before 失败→用例根因、@After 失败→用例级 warning（不翻结论）、AMBIGUOUS→FAILURE+附注、SKIPPED 入树而 UNUSED 不留幽灵步、场景间句柄/stack 不串台、finishRun 幂等 |
| 配置 | `ConfigParseTest` | 4 项决策默认值 / 冗余开关由值推导 / `limits` 档位解析与非法整体回退 / 旧键忽略不报错 |
| 拦截缺口 | `UnproxyableStepWarningTest` | final/static/private `@Step` 的 warn 清单（final 与 static/private 分开措辞、父类链扫描） |
| 推送 | `HttpPushSinkTest` | 真实 HTTP 报文（JDK HttpServer，非 mock）/ CaseStarted 不推 / 队列满 dropped / HTTP 5xx failed / 端点不可达 worker 存活 / close 停推 |

> ByteBuddy 两大 API 陷阱（拦截失效的根因，已由测试固化）：
> `ElementMatchers.anyOf(Iterable)` 是**值相等**匹配器而非或组合——匹配器集合
> 必须用 `.or()` 链；`MethodDelegation` 目标方法必须加 `@RuntimeType`，否则
> 带参/带返回值的方法无法绑定，代理创建整体失败。

> **共享语义**：`@Steps(shared=true)` 令同类型步骤库在**当前线程**共享一个代理实例
> （per-thread，每 Scenario 一个 StepFactory 语义），在 `ReportBus.finishCase`
> 场景结束时回收，线程池复用安全。

> **reader 容错**：全部 JSON 模型 POJO（`TestOutcome`/`TestStep`/`FailureCause`/`UserStory`）
> 均标注 `@JsonIgnoreProperties(ignoreUnknown = true)`——未来 additive-only 新增字段后，
> 旧消费方读旧 POJO 不抛 `UnrecognizedPropertyException`（反向门禁
> `SchemaContractTest.readerToleratesUnknownFields` 固化）。`index.html` footer 的
> `reportVersion` 由用例数据驱动（非模板字面量），schema 演进不会两处漂移。

## 7. 迁移支撑能力（Tier2）

模块内已备好迁移 Phase 0–3 所需的**可独立 adopt 能力**，框架侧只需接线、不改本模块：

### 7.1 双跑四一致门禁（Phase 0）

`migration/ReportConsistencyGate` 比对 pw-reports 与 Serenity 基线的四个维度，
产出 `GateReport`（`failed()` 为真则阻断切换）：

| 维度 | 判据 | 级别 |
|---|---|---|
| COVERAGE | 两目录用例集合（按 `feature::name` 联结）对称差 | WARN |
| RESULT | 同例 `result` 不一致 | **ERROR** |
| STEP_TREE | 步骤树扁平描述序列不一致（两侧均有明细时） | WARN |
| SUMMARY | `count` / `failures` 计数不一致 | **ERROR** |

```java
GateReport report = ReportConsistencyGate.compare(pwOutputDir, serenityBaselineDir);
if (report.failed()) {
    throw new IllegalStateException("四一致门禁失败: " + report.errors());
}
```

`PwReportSource` 兼容 Serenity 汇总的两种结构（顶层 `outcomes[]` 与
`features[].scenarios[]`）。注意 Serenity 汇总通常不含步骤级明细，故 STEP_TREE
在基线侧不可比时自动跳过（不影响 result/summary 比对）。

### 7.2 网络证据接缝（Phase 1）

`integration/NetworkEvidenceBridge` 是框架侧 Playwright route 监控 /
`RouteEvidenceRegistry` 的接入点：采集到 `NetworkCall` 后交给 bridge，
由总线统一脱敏后汇入当前步骤证据与 `apiOperations`：

```java
NetworkEvidenceBridge bridge = new DefaultNetworkEvidenceBridge(ReportBus.getDefault());
// 异步回调中：先 captureHandle 再回传，避免跨线程踩上下文
bridge.record(capturedHandle, NetworkCall.builder()
        .method("GET").url(rawUrl).status(200).durationMs(7)
        .requestHeaders(headers).build());
```

#### 跨线程形态：`QueuedNetworkEvidenceBridge`（route 内核的真实接线用这个）

框架的 `RouteEvidenceSink.record(...)` 发生在 **Playwright 事件线程**，那条线程与
Cucumber 测试主线程**没有 ThreadLocal 关联**——在那里 `captureHandle()` 拿到的是
`NOOP`，证据会被静默丢弃。故必须「**任意线程入队 + 主线程 flush 带句柄归位**」：

```java
// ① 场景装配期（主线程）创建并捕获句柄——此刻 current 步骤上下文正确
QueuedNetworkEvidenceBridge bridge = new QueuedNetworkEvidenceBridge(ReportBus.getDefault());
StepHandle handle = bridge.capture();

// ② route 内核命中处（Playwright 事件线程）——只入队，绝不碰总线
//    签名与框架 RouteEvidenceSink.record(String,String,String) 一致，可直接作其实现
bridge.record("MOCK", url, detail);

// ③ 场景收尾（主线程）——批量写入报告
bridge.flush(handle);
```

设计要点：**队列是实例字段**（非 static 全局单队列），故并行场景之间、线程池复用的
场景之间都不串证据；队列上限 500 条超限丢最旧并 WARN；`flush` 时若句柄为 `NOOP`
（纯 JUnit 单测 / 收尾钩子漏调）整批丢弃并静默返回，不抛错不积压；`discard()`
用于场景被中断时兜底清理残留。脱敏统一由总线入口 `ScrubGate` 做，调用方不自管口径。

## 8. 已知限制

1. **截图捕获**由业务侧在步骤内调用本模块 `ReportBus.attachScreenshot(StepHandle, byte[])`，
   模块自己写 `step-NNN.png` 到 `<outcome>/screenshots/` 并登记到步骤记录；不驱动 Playwright。
2. **并行执行**：插件为 `ConcurrentEventListener`、总线线程安全，但同一 JVM 内
   outcomeId 冲突后缀按写入序分配——并行场景下 index 顺序不保证确定。
3. **JUnit 4** 路径 SPI 不生效（Cucumber 限制），必须 `@CucumberOptions` 显式声明。
4. **push sink** 只做 best-effort HTTP POST，无重试/断点续传；失败仅记日志。
5. **final 类/方法**无法拦截（JVM 限制），走原始实例直通——步骤树上不可见，
   属预期降级而非缺陷。
6. schema 演进约定：只加字段不删不改（additive-only），破坏性变更升
   `reportVersion` 并保留旧版读取。
7. **迁移未完成项**（需动框架目录 `pw_automation_framework`，本模块不含）：
   - `RouteEvidenceSink` 的 pw-reports-v2 实现注册（接线点已就位，签名同
     `QueuedNetworkEvidenceBridge.record(String,String,String)`，见 §7.2）；
   - ObjectFactory SPI coexistence 三步过渡 + 卸 serenity-cucumber 依赖；
   - Serenity 基线产物采集与真实双跑——**必须在消费方执行**：框架仓库零 `.feature`
     文件、无 Cucumber 入口（它是框架库不是 runner 工程），双跑门禁需消费方路径。
   模块侧接缝与门禁已备好（见 §7），待消费方给出路径即可跑 Phase 0 双跑。
8. **工作区目录名仍为 `pw-reports/`**（artifactId 已是 `pw-reports-v2`）：目录重命名
   被系统句柄占用未成功。落地到框架时模块目录请用 `pw-reports-v2/`，与既有
   `pw-reports`（Serenity 调用封装）并存。

## 9. 边界与非目标

- **不驱动浏览器**——截图捕获由业务侧在步骤内调用 `ReportBus.attachScreenshot(StepHandle, byte[])`；
  本模块不依赖 Playwright，也不触发页面操作。
- **不负责用例编排**——只消费 Cucumber 事件与 `@Step` 拦截，不决定执行顺序。
- **不追求替换 Serenity 的全部能力**——镜像的是其 JSON schema 与报告**形态**，
  不实现 Serenity 的全部特性；迁移以"双跑一致"为准绳逐步推进。
- **不承担**框架目录改造——见 §8 第 7 条，模块只提供接缝，框架侧改造在主工程完成。

## 10. 延伸阅读

- [自建 Serenity 兼容报告方案](../docs/architecture/自建Serenity兼容报告方案.md)
- [FrameworkListener SPI 对齐规范](../docs/report-alignment/A_frameworklistener_spi.md)
- [Scenario Outline 规范](../docs/report-alignment/B_scenario_outline.md)
- [根 README 模块表](../README.md#模块)
