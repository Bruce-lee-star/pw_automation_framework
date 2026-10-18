# 设计稿 B：数据驱动 ScenarioOutline 一等模型 + 原生步骤栈嵌套（对齐 Serenity）

> 背景：⑥ CSV/JUnit-XML 已完成。本报告对齐 Serenity 的第三项缺口。
> 目标：把 Scenario Outline 的 Examples 行**聚合为一个 outcome + examples 数组**（Serenity 形态），并补齐“原生步骤栈嵌套”裂隙，使报告与 Serenity 折叠展示一致。

---

## 1. 现状（已核实）

- `TestOutcome` 无 outline 概念。Scenario Outline 的 Examples 行在 Cucumber 下展开为多个同名 `TestCase`：
  - `OutcomeIdFactory.baseId(uri, line, name)` 中 `line` = outline **定义行**（所有示例行相同）→ baseId 相同 → `ReportBus.startCase` 追加 `-2/-3` 后缀（既有注释已明示）。
  - 结果：**N 个独立 `TestOutcome`，无 outline 分组、无示例数据表**。
- 渲染：`index.ftl` / `serenity-summary.ftl` 逐 outcome 平铺，无 outline 折叠。
- 原生步骤嵌套：`TestStep.children` 已支持；`@Step` 调 `@Step` 经 bus `CURRENT` 句柄已归层；但 Cucumber 步栈（`PwReportPlugin.stepStack`）与 `@Step` 树是否在所有引擎 / 线程边界统一为**单一树**，需核对（见 §5）。

## 2. 目标（对齐 Serenity 模型）

- Serenity 对 Scenario Outline 输出**一个** `TestOutcome`（`scenarioOutline: true`），内嵌 `examples` 数组（每行 = 一个子结果），报告折叠展示 Examples 表 + 行内展开。
- 本框架对齐：同 outline 的 Examples 行**聚合为 1 个 outcome + examples 数组**，`index/summary/per-case` 渲染 outline 折叠视图；步骤树统一为单一嵌套树。

## 3. 设计

### B1 模型扩展（additive，不破坏既有 JSON 契约）

`TestOutcome` 新增字段（均 nullable，旧 JSON 缺省 = 非 outline）：

| 字段 | 类型 | 含义 |
|---|---|---|
| `scenarioOutline` | `boolean` | 是否 outline（默认 false） |
| `exampleHeaders` | `List<String>` | 示例表头，如 `[user, password, error]` |
| `exampleValues` | `List<String>` | 本行取值，与 headers 对齐 |
| `exampleRowNumber` | `int` | 从 1 起；-1 = 非 outline |
| `outlineKey` | `String` | `feature + "/" + scenarioName` 规范化，仅用于聚合（是否序列化可定） |
| `examples` | `List<TestOutcome>` | 仅聚合父 outcome 持有；每行一个精简子 outcome（name/result/duration/startTime/endTime/exampleValues/testFailure*） |

`TestStep` 不变（`children` 已支持任意深度嵌套）。

### B2 探测 outline + 取示例数据（PwReportPlugin）

- **判定 outline**：Cucumber 7 下 `TestCase.getLine()` 对 outline 返回**定义行**（同行 → 冲突后缀），故“baseId 相同且多实例”即 outline 行。稳健起见同时用 Cucumber API 确认：`TestCase` 是否源自 `ScenarioOutline`（实现前 spike `io.cucumber` 7.34.4 的精确形态，如 `getScenarioOutline()` / `getScenarioDefinition().getKeyword()`）。
- **表头**：从 `ScenarioOutline.getExamples()` 的 header cells 取。
- **本行值**：从每行 `TestCase` 的 step arguments 取（`PickleStepTestStep.getDefinitionArgument()` / `getStep().getArgument()`）。
- 在 `onCaseStarted` 记录 `outlineKey = featureBaseName(uri) + "/" + name`；在 `onCaseFinished` 把 `exampleHeaders / exampleValues / exampleRowNumber` 填入该 outcome。

### B3 聚合（sink 端，保真相源不变量）

新增 `OutlineAggregator.aggregate(List<TestOutcome>)`（在 `LocalReportSink.finishAll` 调用，展示层聚合）：

- 按 `outlineKey` 分组；单组 **>1 条** → 合成 1 个 outline 父 outcome：
  - `name` = outline 名；`scenarioOutline = true`；`exampleHeaders` = 公共表头；`examples` = 每行一个子 outcome（含 `exampleValues / result / duration / startTime / endTime / 失败信息`）。
  - 父 `result` = 任一子失败则 `FAILURE`，否则 `SUCCESS`（与 Serenity 聚合语义一致）。
  - 父 outcome 不单独持有步骤树（步骤下放到各 example 子 outcome）。
- 单组 = 1 条（普通 scenario）→ 原样保留（`scenarioOutline = false`）。
- **幂等 + 不破坏实时保**：per-case JSON 仍逐行写（`LocalReportSink.writeOutcomeJson` 不变）；聚合只作用于 `summary / index / CSV / JUnit-XML` 展示，与 ⑥ 已落地的 writer 衔接。
- `OutcomeIdFactory` 的 `-2/-3` 后缀逻辑保留为兜底（聚合失败时仍可区分）。

### B4 渲染

- `serenity-summary.json`：保留逐 outcome 列表（兼容既有消费者）；outline 父 outcome 额外携带 `scenarioOutline / exampleHeaders / examples`（additive）。
- `index.ftl`：`scenarioOutline` 渲染为一行可展开（outline 名 + 示例数 + 聚合结果），点击展开 Examples 表（或跳转父 HTML）。
- `test-outcome.ftl`（per-case HTML）：若是 outline，渲染 Examples 表 + 选中行步骤细节（Serenity 风格）。以“不破坏现有单 case 页”为前提，outline 页为新增形态。
- CSV / JUnit-XML（⑥ 已实现）：outline 父行写聚合结果；`examples` 明细可另列或可选展开（R4）。

### B5 原生步骤栈嵌套核对与补全

- 验证：① Cucumber 步（`PwReportPlugin.stepStack`）与 `@Step`（bus `CURRENT`）是否在同一步树下归层；② background 步 / hook 步是否不污染树；③ 并行线程（`ConcurrentContextExecutor`）下 `CURRENT` 线程本地是否隔离。
- 若发现裂隙：统一为“单一步树”——Cucumber 步由 `stepStack` 管理父链，`@Step` 由 bus `CURRENT` 管理子链，`PwReportPlugin.stepStarted` 时把 `CURRENT` 步设为该 Cucumber 步的子根（现有机制，核对是否覆盖 hook/background/并行边界），以最小改动收敛。

## 4. Schema 契约影响

- `TestOutcome` 新增 6 字段（additive；旧 JSON 缺省 = 非 outline，`SchemaContractTest` 与 `SnapshotGateTest` 需更新基线）。
- `serenity-summary.json`：每个 outcome 可选携带 outline 字段；聚合父 outcome 含 `examples` 数组（子结构复用 `TestOutcome` 子集）。
- per-case JSON **不变**（仍逐行）；聚合在 summary/index 层完成。

## 5. Serenity 语义对齐点

- Serenity：Scenario Outline → 一个 outcome + examples 表 + 每行独立子结果；报告折叠展示。
- 本框架对齐为：聚合父 outcome（`scenarioOutline=true` + `examples`），index/summary/outline HTML 折叠展示。
- 原生步骤嵌套：Serenity 的 `@Step` 天然嵌套为树；本框架已部分具备，补齐裂隙即对齐。

## 6. 风险 / 待确认

- **R1**：Cucumber 7 取 Examples 表头 / 本行值 API 的确切形态（io.cucumber 7.34.4）→ **实现前 spike 确认**。
- **R2**：聚合放 sink 端（展示聚合）还是 bus 端（数据聚合）？推荐 **sink 端**（真相源 per-case 不变量保住、实时保不破），与 §3.B3 一致。
- **R3**：per-case HTML 对 outline 的展示形态（Serenity 单页 Examples 表 + 行内展开）；需 UX 确认，但本次以“不破坏现有单 case 页”为前提。
- **R4**：CSV / JUnit-XML 是否展开示例行（影响 CI 解析）；默认父行聚合 + examples 字段，展开为可选项。

## 7. 落地顺序（确认后）

1. `TestOutcome` 加 6 字段 + getter/setter（additive）。
2. `PwReportPlugin` 探测 outline + 填示例数据（spike R1 后）。
3. 新增 `OutlineAggregator` 并在 `LocalReportSink.finishAll` 调用。
4. `index.ftl` / `test-outcome.ftl` outline 折叠渲染；CSV/JUnit-XML 对接聚合结果。
5. `SchemaContractTest` / `SnapshotGateTest` 重新冻结基线（`-Dpw.report.test.write-snapshot=true`）。
6. 步骤嵌套核对（B5）+ 最小补全。
7. 全护盾（`pw-reports-v2` 156+）零回归。

## 7. 落地记录（2026-10-17）

- **R1 spike 结论**：Cucumber 7.34.4 `io.cucumber.plugin.event.TestCase` 有 `getKeyword()`（返回 "Scenario Outline"）→ 干净探测；`getLine()` 对 outline 各行相同（共享 outline 定义行，这正是既有 baseId 需 `-2/-3` 后缀的原因）。**示例表头 / 本行取值无插件事件 API**（需解析 Gherkin 文档，较脆），故本轮**不采集** `exampleHeaders/exampleValues`（缺省 null，omit），父 outcome 的 `examples` 已含各行（每行结果 / duration / 跳转链接），达成「一个 outline → 一个 outcome + 每行结果」的核心 Serenity 对齐。表头/值增强列为后续可选（需引入 Gherkin 解析）。
- **R2 一致性口径**：`LocalReportSink.finishAll` 在 RunFinished 全量快照上聚合一次——index / serenity-summary(.html/.json) / CSV / JUnit-XML / history 用聚合口径（父 = 1 个 outcome，任一行 FAILURE/ERROR 即父 FAILURE，duration = 行求和）；而 rows 的 per-case JSON/HTML 与 monitor-failures 仍用原始口径（保 real-time 产物 + 父页面跳转目标 + rows 上的 monitor 失败不丢）。聚合规则：仅 `scenarioOutline==true && outlineKey!=null` 按 outlineKey 分组；>1 行生成父（`examples` 挂各行，按 exampleRowNumber 排序）；恰 1 行退化为普通用例；非 outline 行原样。
- **R3 additive**：`TestOutcome` 加 6 个 boxed 可空字段（`scenarioOutline/exampleHeaders/exampleValues/exampleRowNumber/outlineKey/examples`），默认 null → NON_NULL 序列化省略，故 `SchemaContractTest`（只序列化 `full()`，未设这些字段）与 `SnapshotGateTest`（`full()` 无 outline，模板 outline 块被 `scenarioOutline` 守卫）**零影响**；schema 已同步声明 6 个可选字段（DR-1）。
- 改动清单：`TestOutcome`(+6 字段/访问器)、`RecordingSession`(outline 字段 + `markOutline` + `toOutcome` 投影)、`ReportBus.markOutline`、`PwReportPlugin.onCaseStarted`(keyword 探测 + 行计数)、新增 `OutlineAggregator`、`LocalReportSink.finishAll`(聚合口径)、`SummaryHtmlRenderer.scenarioRow`(+isOutline/exampleCount)、`index.ftl`/`serenity-summary.ftl`/`test-outcome.ftl`(outline 展示)、`pw-reports-schema.json`(+6 可选字段)、新增 `OutlineAggregatorTest`(5)。
- 验证：`pw-reports-v2` 161 例 0 失败（含 SchemaContract 3 / SnapshotGate 2 / OutlineAggregator 5 / CSV/JUnit/Index/Summary 渲染测试）；`pw-web-ui -am` 全量 389 例 0 失败（下游对着改后 reporting 编译并零回归）。
