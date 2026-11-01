# AI 自动录制脚本 + 独立 Inspector 窗口方案

> 历史方案：文中 `pw-codegen-v2` 设计已退役；当前唯一受支持实现为 `pw-codegen-v3`。本文保留作设计演进记录，不代表现行模块结构。
> 状态：已退役的设计稿（不代表当前实现状态）
> 关联：`docs/architecture/CDP捕获层改造方案.md`（Phase 0 基座）
> 模块：`pw-codegen-v2`（独立模块，不改动 `pw-core` / `pw-web-ui`）

## 1. 背景与诉求

用户想法（来自评审 codegen-v2 后的延展讨论）：

1. **AI 入口自动录制**：后续能否给 AI 一个入口，根据"梳理好的业务流"在页面上操作，自动录制出框架脚本。
2. **独立 Inspector 窗口**：Playwright 的 Inspector 用 Electron 独立窗口承载录制 UI；我们是否也能做一个独立的录制窗口，而不仅是在 AUT 页面内注入侧栏。

结论先说：**可行，且我们已具备约 70% 的"录制引擎"**。缺的两块是 **AI 编排层**（理解业务流→驱动浏览器→回读捕获→出码）与 **独立 UI 壳**（把面板从 AUT DOM 抽出来）。两者都可在 `pw-codegen-v2` 内加法实现，对原框架零侵入。

## 2. 已具备的资产（Phase 0 基座）

| 资产 | 位置 | 在方案中的角色 |
| --- | --- | --- |
| CDP 捕获层 | `pw-codegen-v2 …scan.RolePickerCdpSession/NodeExtractor/CaptureDispatcher` | 元素识别与 `page.pause` Inspector **同源**（CDP `Overlay.inspectMode`），AI/人点选目标一致 |
| NLS 分离 | `NlsNameTranslator` + `nls/*.json` | 页面元素 / 业务操作分离；拾取只挂 `nlsKey` 元数据、不重定向元素 |
| 生成器三件套 | `RoleElementPageGenerator` / `StepGenerator` / `AssertionGenerator` | 一次拾取/动作 → 干净 `@RoleElement` / 步骤方法 / 断言 |
| `RoleEntry` 模型 | `scan.RoleEntry` | 拾取与生成的统一中间表示（role/name/framePath/nlsKey…） |
| 脱敏能力 | `framework` 内 `SensitiveDataSanitizer` | 把页面文本喂给 LLM 前的隐私红线，可复用 |

关键意义：CDP `Overlay.inspectMode` 正是 `page.pause()` 的 Inspector 机制，因此 V2 启用 CDP 后，**"AI 点选的元素"与"人用 Inspector 点的元素"是同一目标**——一致性天然满足，这是整个方案成立的前提。

## 3. 架构总览（数据流）

```
                 ┌─────────────────────────────────────────────┐
                 │            录制会话 (RecordSession)          │  ← Phase 1 新增
                 │  start(ctx) / record(action) / generate()    │
                 └───────┬───────────────────────┬─────────────┘
                         │ 动作序列(人/AI)         │ 元素捕获
                         ▼                        ▼
        ┌────────────────────────┐      ┌────────────────────────┐
        │ 业务流规格 (输入)       │      │ CDP 捕获层 (Phase 0)    │
        │ feature/步骤/NLS键/PO   │      │ role/name/framePath/    │
        │ 或自然语言              │      │ nlsKey → RoleEntry      │
        └───────────┬────────────┘      └───────────┬────────────┘
                    │                                │
                    └───────────────┬────────────────┘
                                    ▼
                    ┌────────────────────────────────┐
                    │  生成器三件套 (Page/Step/Assert) │
                    └───────────────┬────────────────┘
                                    ▼
                    ┌────────────────────────────────┐
                    │  产物: @RoleElement / 步骤方法 / │
                    │  断言 / NLS键 / feature 步骤     │
                    └────────────────────────────────┘
```

AI 编排层（Phase 2）只是在"业务流规格→动作序列"之间插入一个 **LLM function-calling 闭环**：

```
业务流规格 ──▶ LLM 规划下一步 ──▶ 调 Playwright 执行 ──▶ 回读页面状态+CDP捕获
      ▲                                                          │
      └──────────────────── HITL 确认 / 抗飘移校正 ◀──────────────┘
```

## 4. 分阶段路线

### Phase 0 — 基座（已落地 ✅）
CDP 捕获对齐 Inspector ✓、NLS 分离 ✓、生成器 ✓、`RoleEntry` ✓。

### Phase 1 — 录制会话 API（已落地 ✅）
把"交互序列 → 代码"收口为 `pw-codegen-v2` 内**可复用服务**，先支持"人操作 + 自动出码"，跑通再谈 AI。

落地类（`scan.RoleRecordSession`）：
```java
class RoleRecordSession {
    static RoleRecordSession create(String packageName, String pageClassName, String stepClassName, String... nlsFiles);
    void recordClick(RoleEntry target);                   // 记录一次点击（click()）
    void recordType(RoleEntry target, String text);       // 记录输入（文本框→fill("...")）
    void recordHover(RoleEntry target);                   // 记录悬停（hover()）
    void recordRightClick(RoleEntry target);              // 记录右键（rightClick()）
    void recordAssertVisible(RoleEntry target);           // 记录可见性断言（isVisible()）
    GeneratedArtifact generate();                         // 调生成器三件套产出 page/step/assertion 三类代码
}
```
- 产物 `GeneratedArtifact` 聚合：`pageSource` / `stepSource` / `assertionSource`。
- 复用 `RoleElementPageGenerator` / `RoleElementStepGenerator` / `RoleElementAssertionGenerator` 现有生成链路（与拾取面板手动拾取逐字节等价），不重复造生成逻辑。
- 交互动作进步骤类、断言动作进断言类、所有目标元素去重进页面类字段。
- 单测 `RoleRecordSessionTest`（6 例）覆盖 click→click()、type→fill()、hover→hover()、rightClick→rightClick()、assert→isVisible()、去重、空会话/空目标拒错；V2 总计 64 例 0 失败。
- 已知边界：同一 `RoleEntry` 实例不应以不同操作类型重复记录（后写标志覆盖先写）；AI 录制每次拾取为独立实例，天然规避。checkbox/下拉/按键/拖拽等 final 字段语义留待 Phase 2 派生副本。

### Phase 2 — AI 编排层
LLM function-calling 闭环：业务流→动作→回读 CDP→出码；HITL（AI 提议、人确认）；复用 `SensitiveDataSanitizer` 做页面文本脱敏；用 role+name 语义定位禁用坐标/`nth` 以抗飘移。

### Phase 3 — 独立窗口
先做**分离 Chromium 窗口**（`window.open` / 独立 `newPage`）承载 React/Vite 录制面板 + 实时代码预览 + AI 对话，经现有 `RoleCodegenBridge` 命令桥 / CDP 与 AUT 通信（不注入 AUT DOM）。Electron 视分发需求再上。

## 5. 关键风险与对策

| 风险 | 对策 |
| --- | --- |
| AI 自主点击可靠性 / 抗飘移 | 强制 role+name 语义定位；歧义元素回问；HITL 确认 |
| LLM 每步成本 / 时延 | 动作缓存 + 批处理；只在决策点调用模型 |
| "业务流规格"必须机器可读 | 复用 feature / 步骤定义 / NLS 键 / 已有 PO 作为种子 |
| 页面文本喂 LLM 的隐私 | 复用 `SensitiveDataSanitizer` |
| 框架零侵入约束 | 全部落在 `pw-codegen-v2`；CDP 走 `context.newCDPSession`，不碰 `pw-core`/`pw-web-ui` |

## 6. 开放项（待决策）

1. **框架 pristine 回退**：早前在 `pw-core`（拾取 JS 上溯/拖拽/手势）与 `pw-web-ui`（`PageDebugControl` 共享调试态）的直接改动，目前是 V2 的"JS 注入兜底"。严格零侵入需在 V2 全量验证后还原这两处并解耦 `RolePickerPanelController` 对其调用。
2. **V1 参照模块**：`pw-codegen`（原始未改状态）当前作为参照恢复在工作树、未暂存；等 V2 真正完成（含 Phase 1~3）后删除。
3. **业务流输入格式**：feature 步骤 / NLS 键 / 已有 page object 三选一或组合，决定 Phase 2 编排的输入契约，需用户确认"梳理的业务"当前以何种形态存在。

## 7. 验收标准

- Phase 1：给定一组有序动作（人触发或单测模拟），`RoleRecordSession.generate()` 产出与现有生成器**逐字节等价**的 `@RoleElement` / 步骤 / 断言；新增单测覆盖"序列→代码"且复用既有 58 例不回归。
- Phase 2：可选 LLM 后端，离线（mock function-call）跑通"业务流→代码"闭环，不依赖真实模型即可验证编排数据流。
- Phase 3：独立窗口承载面板 + 代码预览，AUT DOM 无注入残留。
