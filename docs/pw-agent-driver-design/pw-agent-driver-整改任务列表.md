# pw Agent Driver 整改任务列表

> 依据：`pw-agent-driver-完成度评审.html`（出具 2026-11-06，基线 HEAD `b1492a4b`）+
> 设计稿 `pw-agent-driver-design.html` v3.21 §11 +
> 2026-11-06 对当前工作区的独立代码复核。
>
> **本文件基线**：HEAD `39223c3d`（DEV-V6），2026-11-06。复核范围：全仓 `src/main` / `src/test` Java、
> `META-INF/services`、根 pom、设计稿 §11。**本文件的每条结论都给可复核的位置或搜索口径。**

> **更新（2026-11-07）**：本文件已于 commit `fda59584` 提交入 git。本次更新把 **P0 五项（T0–T4）全部标记为已完成**，
> 并补列各任务落地提交；基线已从 `39223c3d` 前进至 `b9393aea`（T3）。P1（T5–T9）尚待起，未改依赖图。

---

## 一、对评审文档的判定

**总判定：方向成立、取证扎实，可作为整改输入，但不能原样执行。**

四个 P0 阻断结论（R9 审计断线、路径 C 断裂、D3 稳定语义缺失、R1/R2/R6 门禁缺失）
经逐条复核**全部成立**，且评审「组件就绪 ≠ 链路就绪」的区分是本报告最有价值的判断。
问题集中在三类：**可证伪的事实错误**、**基线过期**、**任务列表自身的依赖与修法错误**。

### 1.1 复核成立的结论（择要）

| 评审项 | 复核证据 |
|---|---|
| I-01 R9 审计断线 | `AgentTrace` 全仓仅 7 个 java 文件：pw-reports `report/agent/{AgentTrace,AgentTraceStore,AgentTraceWriter}` + 4 测试；`StepDriver.java`（57 行）、`PlaywrightChannel.java`、`StepPlanRunner.java` 零写入；无 `AgentTraceSink` SPI |
| I-02 路径 C 断裂 | `FeatureGlueGenerator.java:84` `generate(FeatureGluePlan)`，`FeatureGluePlan` 是该文件 `:601` 的嵌套类；`pw-codegen/src/main` 下 `StepPlan` 仅 1 处 Javadoc 提及、零 import；`CodegenFileWriter.java:85` 走 `Files.writeString(Path)`；`AssetRepository` 零命中 |
| I-03 D3 缺失 | `StepDriver.java:36-56` 只有 execute + 幂等重试；断言在 `StepVerifier.java:50-61` → `SnapshotVerifier.verify`；`pw-core/.../agent` 主+测试目录 `settle` 各 0 命中；settle 只存在于 `PageNavigation` / `ElementOperationSupport` |
| I-04 R1/R2/R6 | `StepPlan.java` 无 credential/secret 校验，`StepPlanTest.java:105` 明文 `name=password` + `value=y` 为合法 fixture；`BrowserCommandChannel.java:11` 只有 R2 注释、无契约门禁测试；`IntentTranslatorContractTest` 是反射护栏且仅覆盖 `agent.intent`，无 ArchUnit 覆盖 `agent.*` 零浏览器依赖 |
| I-05 快照平铺 | `Snapshot.java:21` `Map<String,ElementState> byRef`；`ElementState`（`:61-67`）仅 6 字段、无 children/depth；全仓 `snapshotDigest\|snapshotHash` 0 命中 |
| I-06 / I-08 / I-10 | 执行层未接 Route/SessionStore/ConcurrencyGate/MonitorSink；无 p50/p95 采集；`StepPlan`+`VerifiableStepPlan`、`StepPlanRunner`+`AssetStepPlanExecutor` 并存，无生产 `AgentDriver` 门面 |
| §11 LlmGateway | 生产实现 0（仅测试 `StubLlmGateway`）；`LlmProtocolTest.java:89-91` 断言 `resolveOrNull()` 为 null |
| P3 | `CandidateValidator.java:32-46` 五规则全跑不短路、无证据 fail-closed；`AgentDriverDslAcceptanceTest` 8 个 `@Test` |

### 1.2 必须勘误的 8 处（代码可证伪，D 类任务）

| # | 评审原文 | 复核事实 | 影响 |
|---|---|---|---|
| D1 | 「8 个模块全部 SUCCESS」(§01) | 根 pom 只有 **7 个 `<module>`**；文档表格也只列 7 行 | 计数错误（疑把 reactor 根工程算入） |
| D2 | 「无任何管线命令」(§02 #18 / I-02) | 已有 `BindingCodegenPipeline.java:55` `run(plan, featureDir, glueDir)` = generate → outputs → `writeAllGenerated` 三段；类注释 `:22-23` 明写「编译与冒烟不在这里做」 | 结论（缺 checkstyle/编译/冒烟/落库）不变，但证据须改为「有 3 段落盘编排，缺 4 段」 |
| D3 | 「`SkeletonCli` 两子命令」(§11.2 / §11.4 / T10) | `SkeletonCli.java:67-69` 还有 `CMD_IMPACT="impact"`，`:106-107` 有 switch 分支，另含 `-h/--help/help` → **3 子命令 + help**；`SkeletonCliTest` 11 处 `impact` 用例 | 直接推翻 T10「两个 tool」的设计前提 |
| D4 | 「全仓无 `ApprovalGrant` 与 `StepPlan` 引用关系 / `planDigest` 没有审批对象消费」(§02 #13 / I-07 / T7) | `ApprovalGrant.java:30` **存在**，`:36` 有 `planDigest` 字段、`:172-181` `covers()` 做摘要比对且信息含「被批准的内容已变更」，被 `ApprovalPolicy` / `CandidateLibrary` / `CandidateCli` 消费。只是它校验 **LocatorCandidate** 的 `planDigestOf()`，与 `StepPlan.planDigest()` **两套互不相通** | 真正缺口是「StepPlan 执行链 `AssetStepPlanExecutor.run` 无摘要校验」；T7 修法须改 |
| D5 | 「框架内部仍有 21 个文件用 `evaluate`」(I-04 / §02 #24) | src/main 实测 **17 文件 / 46 处**；严格按 Playwright `page\|locator\|frame.evaluate` 或脚本 `document.evaluate` 计是 **13 文件**。差额来自自研求值（`ConditionEvaluator`、`FieldReplacer`）与审批方法名（`ApprovalPolicy`、`CandidateLibrary`） | 数字须订正；且 R2 门禁不能按「全仓禁 evaluate」设计，否则误伤 13 处合法用法 |
| D6 | 「`RouteRuntimeImpl` 生产侧无拒绝逻辑，默认还是 WARN」(§02 #10) | `RouteRuntimeImpl.java` 全文 redirect/Location/abort **0 命中**；WARN 是 `RedirectValidationPolicy.java:55-58` 的策略默认值（仅靶场），`RouteTestBedHarness.java:65` 仅 strict 才注册 `page.route` 并 abort | 结论成立，但把「缺席」与「默认值」混为一谈，须拆分表述 |
| D7 | §02 #6「无跨域 Frame 标记」 | `SnapshotEnvelope.java:21` 有 `frameId`、`:30-31` 有 `Status.UNSUPPORTED`（注释含跨域 Frame / Shadow DOM） | 应为「有 envelope 级状态，缺 per-node 归属标记」，T5 是扩展不是从零 |
| D8 | 条目计数三处矛盾 | 表格 25 行实际：已落地 **11**、部分 **4**、缺口 **9**、口径变更 **1**；§02 note 写「12/4/8/1」；masthead 写「17/5/5/1」(=28)。按表格折算应为 `11+2.4+0.5=13.9 → 55.6%`，非 59.6% | 72.5%（六阶段等权 `(90+95+60+50+95+45)/6`）算术正确、不受影响；但另两个数须统一口径并说明 25 与 28 的关系 |

### 1.3 基线过期（评审出具后又发生了两次提交）

- 评审声明基线 `HEAD b1492a4b`；当前 HEAD 为 **`39223c3d`**（`835ed34c` 门禁功能 + `39223c3d` 补模板）。
- 因此以下表述已失效：§01「+90 例差额来自未提交的 LocatorHealth 改动」、§06.2「LocatorHealth 门禁/基线/HTML 报表已成型（未提交）」、§09「未提交项包含 LocatorHealth 十余类与 `locator-health-baseline.json`」。
- `locator-health-baseline.json` 属**运行时产物**，本就不应纳入版本控制（已清理）。
- 上述「仍未提交」项（`agent/impact` 主 11 类 + 测试 3 类、`SkeletonCli*`、`CurrentScenario*`、docs 资源）**已于 T0 定型并提交**（`f4566f5a` `153db525` `09b1241f` `fda59584`，见 §二 P0 表 T0 行）；提交前仍属未评审代码质量（见 §四）。
- 后续补充（2026-11-07）：基线从 `39223c3d` 再前进至 `b9393aea`，期间 T0–T4 全部完成（见 §二 P0 表与顶部更新说明）。

### 1.4 一个评审后引入、评审未覆盖的真实缺陷（已修）

`835ed34c` 提交了 `LocatorHealthHtmlRenderer.java`（`FREEMARKER.getTemplate("locator-health.ftl")`）
与两个会真实调用 `write()` 的测试，**却漏提交 `pw-reports/src/main/resources/templates/locator-health.ftl`**。
工作区因文件存在而全绿，但 HEAD 全新检出会 `TemplateNotFoundException`。
已由 `39223c3d` 补齐（123 行），并在 HEAD 中复核存在。
这恰好印证评审 §01「HEAD 提交基线未单独复跑」与 §10「未评审未提交代码质量」两项保留意见的必要性。

---

## 二、整改任务列表

**优先级定义**：P0 = 进生产流水线前必须闭环；P1 = 可靠性/治理硬缺口；P2 = 规模化与可维护性演进；D = 文档勘误（不占代码估时）。
估时为单人投入，验收标准均为可判定条件。

### P0 · 阻断（已全部完成 ✅，5 项）

| ID | 任务 | 关键动作 | 依赖 | 估时 | 验收标准 | 状态 / 落地提交 |
|---|---|---|---|---|---|---|
| **T0** | 工作区增量提交 + **HEAD 全新检出回归** | 把 `agent/impact`(11+3)、`SkeletonCli*`、`CurrentScenario*` 定型并提交；从 HEAD 全新 checkout 跑 7 模块全量回归 | — | 0.5d | `git status --short` 无本 feature 残留；全新检出全量回归 0 失败 0 错误；产出模块级例数清单 | ✅ 已落地（`f4566f5a` `153db525` `09b1241f` `fda59584`：WIP 按逻辑单元分 4 提交，含 driver 设计文档；HEAD 全新检出 7 模块全量回归 `BUILD SUCCESS`） |
| **T1** | R9 审计接线（I-01） | `AgentTraceSink` 接口（pw-core）+ pw-reports SPI 实现；`StepDriver.recordTrace` 经 `AgentTraceRegistry` 写 trace；无实现时 no-op | T0 | 3d | 一次 N 步计划产出 N 条 trace；报告可见 Agent 区块；脱敏后无凭据原文 | ✅ 已落地（`efd8dd6c` 接线 `AgentTraceSink` SPI + `9f902afb` 最小审计字段与跨数据出口 canary） |
| **T2** | D3 稳定语义（I-03） | 在 `StepVerifier.verify` **之前**加静默窗口（配置键，默认 500ms）+ 导航感知 + 超时兜底；`Verify` 阶段统一收口，不放进 `StepDriver` | T0 | 2d | 含 200ms 延迟渲染的靶场页面连跑 30 次全绿；配置关闭时行为与现状逐字一致 | ✅ 已落地（`56098073` 静默窗口 + 导航感知；`ed69d3c1` 修复批量执行逐步 settle + 重快照，消除中间态错位回归） |
| **T3** | 路径 C 闭环（I-02） | 新增 `StepPlanCodegenAdapter`（`StepPlan` → `FeatureGluePlan` + 配套 Steps 类）；**复用** `BindingCodegenPipeline` 3 段落盘，叠加冲突守卫 → Glue/Steps 一致性校验 → `AssetRepository.save(DRAFT)`；单命令入口 + 退出码 0/1/2；编译/冒烟标记委托 CI 真实执行（不伪造编译结论，守 `BindingCodegenPipeline` 既有铁律） | T0，**不依赖 T6** | 5d | 给定已验证 `StepPlan`，一条命令产出四层产物并落库；退出码 0/1/2 有契约测试 | ✅ 已落地（`b9393aea`：pw-codegen 新增 `StepPlanCodegenAdapter`/`StepPlanCodegenPipeline`/`StepPlanCodegenCli`；pw-core 新增 `AssetType.BINDING` 与 `StepPlanActionSink/Sinks` 执行接缝；契约测试覆盖生成+落库 / 手写冲突→2 / 空计划→1；pw-core+pw-codegen 全量 350 测试 0 回归） |
| **T4** | R1/R2/R6 门禁（I-04） | 凭据原文扫描测试；`BrowserCommandChannel` 契约面测试（禁 `evaluate`/`run_code_unsafe` **仅约束契约面与 `agent.*` 包**，不误伤 13 处框架内部合法用法）；ArchUnit 断言 `agent.*` 零浏览器类型依赖 | T0 | 2d | 三条红线各有失败用例，注入破坏后构建红；现有 17 个含 `evaluate` 的文件不需改动 | ✅ 已落地（`3ba6b0db`：凭据原文 / 契约面禁 `evaluate` / `agent.*` 零浏览器依赖 三条红线均有失败护栏，注入破坏构建红） |

### P1 · 重要（5 项）

| ID | 任务 | 关键动作 | 依赖 | 估时 | 验收标准 | 状态 / 落地提交 |
|---|---|---|---|---|---|---|
| **T5** | 执行层接线 / 消除孤岛（I-06，评审未单列） | 引入 `AgentRuntimeContext` 承载 Route / SessionStore / ConcurrencyGate / MonitorSink，静态工具类改实例方法 | T1 之后（SPI 已解耦） | 4d | 同一进程内执行链与业务链路由/并发语义一致；ArchUnit 断言依赖方向；全量回归零回归 | 待起 |
| **T6** | 快照树 + 指纹（I-05） | `ElementState` 补 children/depth；把 `SnapshotEnvelope` 已有的 `frameId` 下沉为 per-node 标记（UNSUPPORTED 保留信封级，per-node 字段已预留）；`snapshotDigest` 排除时间戳/序号，落到 `CommandResult` | T0 | 3d | 同页面两次快照 digest 相同；改一个属性后 digest 变化；有界性（深度/节点数上限）有测试 | ✅ 已落地（`93e7dfea`：ElementState 加 depth/children/frameId/unsupported；AriaSnapshotParser 按缩进建树 + per-node frameId；Snapshot 落树并扁平化 byRef；snapshotDigest SHA-256 排除信封元数据；CommandResult 透传；契约测试覆盖同页同指纹/改属性变化/截断差异/树结构/平铺兼容；pw-core+pw-web-ui 全量 571 测试 0 回归） |
| **T7** | 计划级幂等 | 基于 `snapshotDigest` 跳过已通过步骤；失败输出 diff | **T6** | 2d | 重跑同一计划跳过 N-1 步，仅跑变更步 | 待起（T6 已就绪，可起） |
| **T8** | 审批绑定（I-07，**修法已修正**） | 明确 `LocatorCandidate.planDigestOf()` 与 `StepPlan.planDigest()` 两套摘要的语义边界与映射；在 `AssetStepPlanExecutor.run` 前置校验批准摘要（**不是新建 `ApprovalGrant`**） | T0 | 2d | 篡改计划后执行被拒，错误信息含两侧 digest；定位器回注域原有 `ApprovalGrant.covers()` 行为零回归 | 待起 |
| **T9** | 非功能基线（I-08） | 单步 / 10 步 / 100 步 p50/p95 采集；并发上限；canary 生产策略；失败恢复目标 | T1–T3 之后（否则测旧链路） | 3d | 一页基线数据；阈值与告警项写入设计稿；采集开关默认关 | 待起 |

### P2 · 演进（4 项）

| ID | 任务 | 关键动作 | 依赖 | 估时 | 验收标准 |
|---|---|---|---|---|---|
| **T10** | 统一门面（I-10，评审 T9 前半） | `AgentDriver` 门面内部路由 `StepPlan`/`VerifiableStepPlan` 两套入口 | T5 | 3d | 调用方只依赖一个入口；两套执行语义无重复实现 |
| **T11** | 定位表示映射契约（I-11，评审缺任务） | `LocatorSpec`（`role=button;name=…`）↔ codegen `getByRole(AriaRole.X, …)` 映射器 + 单测 + 契约测试 | T3 | 2d | 同一元素在两侧映射一致；任一侧漂移构建红 |
| **T12** | 治理面（I-12，评审缺明确任务） | Jenkins 报告 + 邮件/IM 摘要 + 每周定位器健康复核调度；审批 UI / 工单对接另立排期 | T1、T9 | 3d | 每周自动产出定位器健康摘要；CLI 不再是唯一交互面 |
| **T13** | 入口协议桥 MCP（原 T10，**前提已修正**） | 把 `prompt` / `check` / **`impact`** 三个子命令封成 stdio MCP server；**先明确 `impact` 是否对外暴露**；框架仍零 LLM / 零凭据 / 零出网 | **T0**（接口定型），联调等 T3 产物契约 | 2d | IDE 内一次会话拿到可采信 `StepPlan`、手工复制 0 次；`LlmProtocolTest` 仍绿；`LlmGateways.resolveOrNull()` 仍为 null |

### D 类 · 文档勘误（不占代码估时）

| ID | 任务 | 验收标准 |
|---|---|---|
| **D1** | 刷新评审文档基线为 `39223c3d`，移除「LocatorHealth 未提交」「+90 例来自未提交改动」「含 `locator-health-baseline.json`」等过期表述 | 全文无失效的「未提交」断言；基线 HEAD 与日期一致 |
| **D2** | 落实 §1.2 的 D1–D8 全部勘误（模块数 7、管线、3 子命令、`ApprovalGrant`、evaluate 计数、R3 表述、Frame 标记、三处计数统一） | 条目计数与矩阵逐行可复核；折算公式与表格一致 |
| **D3** | 设计稿 §11.3 把 R9 从「✅ 运行期 + 测试」降级为「组件就绪、执行链未接线」，§11.2 自评同步（I-09） | 设计稿自评可被代码验证，不再以组件存在代替链路验收 |
| **D4** | 评审 §08 任务列表按本文件替换（依赖、修法、T0 前置、T13 三 tool） | 依赖关系无环、无跨 digest 域的伪依赖；I-01…I-12 每项都有任务号承接 |

---

## 三、依赖关系（修正评审原图的三处错误）

```
T0 ──┬─> T1 ──> T5 ──> T10
     ├─> T2
     ├─> T3 ──────────> T11 ──┐
     │    └─(联调)─> T13      │
     ├─> T4                   │
     ├─> T6 ──> T7            │
     ├─> T8                   │
     └─> T9 (需 T1–T3 先落地) ──> T12
```

**相对评审原图的修正**：

1. **解除「T3 依赖 T5（快照 digest）」**：路径 C 产物落库需要的是 `planDigest()` / 产物内容哈希，
   T6 的 `snapshotDigest` 是执行期幂等用的，**两者不同域**。原依赖会把 5d 的关键路径无谓推迟 3d。
2. **T1 不再隐含依赖 I-06 / `AgentRuntimeContext`**：改用 SPI 接线，审计链可独立闭环；
   执行层实例化重构降为 T5，在 T1 之后做。
3. **T13 增加 T0 前置**：`SkeletonCli` 与 `agent/impact` 尚未提交定型，协议桥不应建在漂移接口上。

其余：T1/T2/T3/T4/T6/T8 可与 T0 完成后并行；T9 必须在 T1–T3 之后，否则测的是旧链路；
T10 与 T3 可并行开发，联调需等 T3 产物契约定型。

---

## 四、本次未做的事（如实披露）

- 未修评审 HTML 文档本身（位于工作区外 `C:\Users\12745\Doubao\...`），仅产出勘误清单 D1–D4。
- 未对 `b1492a4b` 单独复跑 1959 例；当前结论基于工作区复核与 `39223c3d` 的静态取证。
- 未做性能基线测量（T9 才有真实值）。
- 已随 T0 提交 `agent/impact` 11 类 + `CurrentScenario` 2 类（见 §1.3），但其代码质量**未做独立设计评审**，仅按文件计入 T0。
- 本任务列表已于 `fda59584` 提交 git；本次（2026-11-07）更新把 P0 五项（T0–T4）标记为已完成，并补列落地提交（见顶部更新说明与 §二 P0 表）。
- P0 已全绿，下一步为 P1（T5–T9），相互无依赖、可任意起（见 §二 P1 表与 §三依赖图）。
