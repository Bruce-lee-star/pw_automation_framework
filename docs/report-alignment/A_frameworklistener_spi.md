# 设计稿 A：FrameworkListener SPI 注册 → 报告生命周期桥接（对齐 Serenity 扩展点）

> 背景：⑥ CSV/JUnit-XML 已完成。本报告对齐 Serenity 的第二项缺口。
> 目标：`META-INF/services/...FrameworkListener` 落地一个**框架内置监听器**，把 web/Serenity 生命周期桥接进 `ReportBus`，使**非 Cucumber 运行也能产出 TestOutcome**；并防与 Cucumber 路（`PwReportPlugin`）重复计数。

---

## 1. 现状（已核实）

- `FrameworkListener`（pw-web-ui）已有 5 个 `default` 钩子：`beforeScenario / afterScenario / beforeStep / afterStep / onFailure`。
- `ListenerRegistry.discoverViaServiceLoader()` 经 JDK `ServiceLoader` 发现其实现；当前 pw-web-ui 的
  `META-INF/services/com.hsbc.cmb.hk.dbb.automation.framework.web.listener.FrameworkListener`
  **只有注释、零实现** → 无任何监听器被发现（SPI 文件“空转”）。
- 报告数据流入有**两路**：
  - **Cucumber 路**：`PwReportPlugin`（Cucumber `ConcurrentEventListener`）直连 `ReportBus.startCase / stepStarted / finishCase`，是**唯一产出 `TestOutcome` 的入口**，字段最全（tags / line / uri）。
  - **Serenity / 非 Cucumber 路**：`Recorder` / `PageRecorder`（pw-web-ui）经 `ReportingBridge` SPI（实现 `ReportingBridgeImpl` 在 pw-reports-v2）只 **attach** 截图 / 证据 / HTML / 下载；`ReportingBridge` 接口**没有 `startCase/finishCase`**，故**非 Cucumber 运行没有 outcome 驱动 → 产出 0 个 TestOutcome**，证据因 `captureHandle()` 为 NOOP 被丢弃。
- 结论：这与 Serenity 实质落差——Serenity 任何测试引擎都经 `StepEventBus` 产出 outcome。

## 2. 设计

### A1 新增 `ReportBusLifecycleListener`（放 **pw-web-ui**）

放置依据：pw-web-ui **已依赖** pw-reports-v2（`pom.xml` 26-29 行），可引用 `ReportBus`；而 pw-reports-v2 **不反向依赖** pw-web-ui，故不能放 reporting 模块（否则引用不到 `FrameworkListener`）。

- `implements FrameworkListener`，持有 `ReportBus bus = ReportBus.getDefault()`。
- `beforeScenario(name)`：若 `bus.getSource() != CUCUMBER` → `bus.startCase(baseIdFromName(name), feature, name, tags, uri)`。
  - **退化限制**：`FrameworkListener` 不携带 tags / feature / uri。处理：feature = `"generic"`（或自 name 解析），tags = 空，uri = null；outcomeId = `slug(name)`（剥离 FrameworkListener 注入的线程 ID + 时间戳后缀，见 R1）。
  - 说明：Cucumber 路仍由 `PwReportPlugin` 产出带全字段的 outcome（优先），本监听器只补足非 Cucumber 路。
- `afterScenario(name, failed)`：若 source ≠ CUCUMBER → `bus.finishCase(handle, failed ? FAILURE : SUCCESS, null, durationMs)`（duration 由监听器内部计时段得出）。
- `beforeStep(name)` / `afterStep(name)`：若 source ≠ CUCUMBER → `bus.stepStarted / stepFinished`（复用 bus 的 `CURRENT` 句柄自动嵌套，与既有 `@Step` 树一致）。
- `onFailure(name, cause)`：若 source ≠ CUCUMBER → 在当前 `CURRENT` 步/场景句柄 `bus.attachEvidence(cause)` 或记失败（与 `PwReportPlugin` 的 hook 失败口径对齐）。

### A2 防重复计数（运行级 source 模式）

- `ReportBus` 新增 `enum ReportSource { CUCUMBER, GENERIC }` + `setSource() / getSource()`，默认 `GENERIC`。
- `PwReportPlugin.onRunStarted` → `bus.setSource(CUCUMBER)`；`onRunFinished` → 复位 `GENERIC`。
- 监听器仅在 `source == GENERIC` 时驱动 outcome 生命周期；`CUCUMBER` 路下退化为 no-op（证据 enrich 仍走既有 `ReportingBridge`，不受影响）。
- Cucumber 与非 Cucumber 是不同 JVM / 不同 `ReportBus` 实例 → 运行级标志无跨 Run 串扰。

### A3 SPI 注册（让文件不再为空）

- 在 pw-web-ui 的 `META-INF/services/...FrameworkListener` 追加一行：
  `com.hsbc.cmb.hk.dbb.automation.framework.web.listener.ReportBusLifecycleListener`（保留原注释）。
- 业务可继续在下方追加自有监听器 FQN（SPI 契约不变）。

### A4 测试（pw-web-ui，纯 Mockito / 内存 bus）

- `ReportBusLifecycleListenerTest`：
  - GENERIC 源：`beforeScenario → afterScenario` 后 `bus` 恰好新增 1 个 `TestOutcome`，result 正确。
  - 设 `source = CUCUMBER`：同序列后 bus **不**新增 outcome（no-op 校验）。
  - 步骤嵌套：`beforeStep/afterStep` 在 `CURRENT` 句柄下归层（children 出现）。
  - `onFailure` 注入失败证据（assert 场景级 failure 双口径）。

## 3. Schema 契约影响

- **无新增 JSON 字段、无破坏既有契约**：仅 `ReportBus` 内部新增 `ReportSource` 枚举与 `setSource/getSource`（不序列化、不入库）。
- `FrameworkListener` 接口**不变**（已是稳定 5 钩子）。
- `ReportingBridge` 接口**不变**。
- per-case JSON / `serenity-summary.json` 结构不受影响（非 Cucumber 路产出的 outcome 复用既有 `TestOutcome` 形状）。

## 4. Serenity 语义对齐点

- Serenity：任何测试引擎（JUnit / Serenity / 纯 `@Step`）都经 `StepEventBus` 产出 outcome；插件/监听器可贡献证据。
- 本框架此前仅 Cucumber 经 `PwReportPlugin` 产出 outcome；现经 `FrameworkListener` 桥接，**非 Cucumber 引擎亦产出 outcome + 证据**，对齐 Serenity 的“全引擎产出”语义。
- 已知差异（additive-only 约束下可接受）：非 Cucumber outcome 缺 tags / feature / uri（`FrameworkListener` 不携带），Cucumber 路仍保完整字段。如需全字段，后续可引入富 Serenity-bus 监听器（另议，超出本次）。

## 5. 风险 / 待确认

- **R1**：非 Cucumber 运行的 `scenarioName` 含线程 ID + 时间戳后缀（FrameworkListener javadoc 明示）。实现时 `slug` 需剥离这些后缀，否则同一场景被拆成多行。建议：`beforeScenario` 时以去掉末尾 ` - <threadId> - <ts>` 的规范化名生成 outcomeId。
- **R2**：是否要同时开放“业务自定义监听器也能写 report 字段”？本次仅产出 outcome，不向业务暴露 report 写 API；若需，后续扩 `ReportingBridge` 或新增 `ReportContributor` SPI（另议，非本次范围）。
- **R3**：纯 JUnit5（无 Serenity bus 驱动）不会触发 `FrameworkListenerBridge` → 该窄场景仍无 outcome；本设计覆盖的是 **Serenity-bus 驱动的非 Cucumber 运行**（框架主用形态），与“对齐 Serenity”一致。

## 6. 落地顺序（确认后）

1. `ReportBus` 加 `ReportSource` + `setSource/getSource`。
2. `PwReportPlugin.onRunStarted/Finished` 设/复位 source。
3. 新增 `ReportBusLifecycleListener`（pw-web-ui）+ SPI 注册。
4. 新增 `ReportBusLifecycleListenerTest`。
5. 全护盾（`pw-web-ui` 389 / `pw-reports-v2` 22 / `pw-core` 10）零回归。

## 7. 落地记录（2026-10-17）

- **R3 决策修正**：原稿设想 GENERIC 路自动驱动 outcome。考虑到 `pw.report.enabled` 默认 true，且本监听器经主 SPI 文件（落在 pw-web-ui 单测 classpath）会被 `ListenerRegistry` 发现，若无条件驱动会在单测 / 非报告环境写出报告产物。故改为**显式开关** `pw.report.generic.bridge.enabled`（默认 false）门控：`shouldDrive()` = 总线启用 + source≠CUCUMBER + 开关开启。Cucumber 路与单测默认均 no-op；需在「非 Cucumber 的 Serenity-bus 报告运行」中显式开启此开关。
- 改动清单：`ReportBus`（新增 `ReportSource` 枚举 + `source` 字段 + `setSource/getSource`）、`PwReportPlugin`（onRunStarted 置 CUCUMBER / onRunFinished 复位 GENERIC）、新增 `ReportBusLifecycleListener`、`META-INF/services` 主文件补 1 行实现 FQN、新增 `ReportBusLifecycleListenerTest`（4 例：GENERIC 产出 1 outcome / 失败标记 + 证据 / CUCUMBER no-op / 步骤嵌套）。
- 验证：`pw-web-ui` 全量 389 例 0 失败；`pw-reports-v2` 22 / `pw-core` 10 全绿。业务可继续在 SPI 文件下方追加自有 `FrameworkListener` 实现 FQN。
