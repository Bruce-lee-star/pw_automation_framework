# codegen 覆盖度扩展任务（评审 §07 未覆盖场景，用户选定 A+B）

> 来源：`pw-codegen-独立复审-44e32ed5.html` §07 覆盖度矩阵。
> 用户选择范围 A+B：hover 菜单、文件下载、拖拽、滚动加载、刷新/beforeunload。
> 暂缓 C：负断言、显式等待、参数化（需录制交互模式变更）；有条件项（自绘下拉/同标签页导航/上传/滑块/多语言）。

## 实现策略

统一走"元素动作"路径：注入脚本上报新 type → bridge 创建 Pick(actionType=新类型) → `actionForPick` 生成产物动词。
页面级动作（滚动、刷新、beforeunload）无元素绑定，走独立的 SessionAction 或 TODO 行。

## 任务清单

### A. 值得做

- [x] C01 hover 菜单：inject `mouseover` → `hover` type → Pick → `hover()` 产物（注意 `hover` type 已被悬浮预览占用，用 `hoverAction`）

  实现：hlFlush 换元素时上报 type=hoverAction（带 info）；actionForPick 产出 `hover()`；生成期做「hover 紧跟同元素 click → click 覆盖 hover」去重。测试 `hoverActionGeneratesHover` + `hoverFollowedByClickOnSameElementIsDeduplicated`。
  - 注入侧：`mouseover` 捕获，去重（同一元素短时间只报一次）
  - Java 侧：actionType `hoverAction` → `actionForPick` 产出 `target + "hover()"`
  - 测试：actionForPick + 端到端 golden

- [x] C02 文件下载：inject `download` 事件 → `download` type → 生成 `waitForDownload(() -> 触发)` 或独立 `waitForDownload()` + TODO

  实现：Java 侧 `page.onDownload` 收集 DownloadOp（属 page 会话），assembleMultiPage 按会话分组后 buildSession 绑定到最近元素动作。新增 ActionKind.DOWNLOAD + SessionAction.downloadWithTrigger/download，StepClassGenerator 渲染 `waitForDownload(() -> field.action())` 并按需 import Download；无触发降级独立 + TODO。测试 `downloadBindsToTriggerAndEmitsWaitForDownload`。
  注：Playwright 需 `acceptDownloads=true`（测试环境默认开启），产物假定 context 已配置。

### B. 需真实浏览器验证

- [~] C03 拖拽/拖放（**降级实现**）：inject `dragstart`/`drop` → `drag` 页面级事件（带源/目标选择器摘要）→ 产物 TODO 注释（含选择器），不生成完整 `dragTo()`
  - 注入侧：dragstart 暂存源 info，drop 上报源+目标（已实现）
  - Java 侧：PageActionOp 收集；buildSession 产出 DRAG unresolved（带 src/dst 选择器）（已实现）
  - 限制：源/目标未进字段注册表（需两次独立 CDP 拾取 + 生成期配对），故完整 `source.dragTo(target)` 未落地；产物以 TODO 显式提示，绝不静默丢事件
  - 测试：`dragActionEmitsTodoWithSelectors`
  - 如需完整 dragTo：后续单独做「双端点拾取 + 生成期配对」专项

- [x] C04 滚动加载/虚拟列表：inject `scroll`（200ms 节流累计 dx/dy）→ PageActionOp → `page.scrollBy(dx,dy)` + 等待 TODO
  - BasePage 新增 `scrollBy(int,int)`（page.mouse().wheel，不经 recorder 避免回放二次录制）
  - 新增 ActionKind.SCROLL；测试 `scrollActionEmitsScrollByWithWaitTodo`

- [x] C05 刷新：inject `pagehide` → `reload` 页面级事件 → `page.refresh()` 产物
  - 新增 ActionKind.RELOAD；测试 `reloadActionEmitsRefresh`
  - 注：beforeunload 确认弹窗仍走既有 dialog 通道（acceptAlert），两者独立

## 进度日志

- 建立任务列表：2026-11-18，用户选定 A+B 范围
- A 完成：C01 hover（含同元素去重）、C02 下载（onDownload 收集 + waitForDownload 绑定）
- B 完成：C04 滚动（scrollBy + 等待 TODO）、C05 刷新（refresh）；C03 拖拽降级为 TODO 提示（完整 dragTo 需双端点拾取，列为后续专项）
- 验证：全模块 `mvn test` BUILD SUCCESS（末模块 375 tests/0 failures）；注入脚本 node --check 通过
- 架构改动：assembleMultiPage/buildSession/generateMultiPage 新增 downloadOps + pageActions 参数链；新增 ActionKind DOWNLOAD/SCROLL/RELOAD/DRAG；BasePage 新增 scrollBy
