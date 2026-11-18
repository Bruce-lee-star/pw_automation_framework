# pw-codegen 独立复审（基线 44e32ed5）问题任务列表

> 来源：`pw-codegen-独立复审-44e32ed5.html`。评审基线 44e32ed5，当前 HEAD 已前移，但 P0/P1 逻辑未被动过。
> 状态标记：`[ ]` 待办 / `[~]` 进行中 / `[x]` 已完成（含测试）。
> 说明：先建 T08 golden 网把"绿灯但产物错"变红灯，再逐个修 P0/P1；P2 随 T05/T06 收尾。

## 修复顺序（按评审 §09，调整后）

1. T08 端到端 golden 夹具（先建网）
2. T02 P0-1 + P0-3（切页动作 / 同页类新标签）
3. T01 P0-2 + P1-11（会话维度）
4. T03 P1-01（非 fill 事件兜底）
5. T05 P1-02..06（生成期收口）
6. T04 P1-07..09（dialog 绑定）
7. T06 P1-12..15（注入侧）
8. P1-10 / P1-16 / P2-01..06（零散收尾）

## 一、P0（3）

- [x] P0-1 切页触发器用 `spec.action` 而非本步 `action`（Launcher:2582）→ T02（改用本步 action）
- [x] P0-2 字段视图/断言期望值取全局末态，无会话维度（Launcher:2305-2317/2337/2605-2625）→ T01（断言 entry 取会话内末态 + specOf 按 sigKey + 会话内 fill 值）
- [x] P0-3 同 Page 类新标签 `toTarget.equals(target)` 不切页（Step:160）→ T02（总是生成 switchToPage + TODO）

## 二、P1（16）

- [x] P1-01 点输入框落到 `spec.action` 的 `fill(末值)`（Launcher:2876-2894 / Planner:327-330）→ T03（actionForPick：无值 click 产出 click()，空值 fill 产出 fill("") 占位）
- [x] P1-02 NLS 断言 `containsString` 方向反（Assert:145-148）→ T05（改为「页面文本 contains 词典文本」）
- [x] P1-03 `combobox/listbox` 误当取值型，div 自绘下拉 `getValue()` 恒空（Assert:128-131/160-164）→ T05（isValueRole 按 tag=select 区分原生/自绘）
- [~] P1-04 选择器 `"` 无条件换 `'`，含单引号属性值非法（Planner:767-771/701-711）→ 用户判定该场景（属性值内嵌引号）codegen 不会产生，已回退；保留 escapeJava 的 C0 补全
- [x] P1-05 跨 frame 内层 CSS 为空时整条 `continue` 静默丢（Planner:150-159 / Launcher:2572-2573）→ T05（产出 frame→body 占位 + TODO）
- [x] P1-06 `shadowHosts` 不进产物（Planner:113-127/160-180）→ T05（字段显式标注 shadow 链 TODO；框架不解析则标注不支持）
- [x] P1-07 dialog 只认 `click` 触发，press/select/定时器退化（Launcher:2534-2538）→ T04（放宽为任意元素动作触发）
- [x] P1-08 处理一个 dialog 后清空 `lastElement*`，切页 `continue` 不登记（Launcher:2546-2549/2579-2583）→ T04（不再清空；链式 dialog 追加新行不覆盖；kind 保护不替换 pageSwitch）
- [x] P1-09 dialog 目标取"该标签页首个 URL 目标"，导航换页后字段属旧页类（Launcher:2509-2513）→ T04（优先按触发元素的 URL 取目标）
- [x] P1-10 文本语义唯一性缓存键 = `role+name`，不含页面/URL（Launcher:2397-2417）→ 收尾（cacheKey 加 urlKey 维度）
- [x] P1-11 `lastFillPick` 跨会话扫描全表，同值/前缀即合并（Launcher:1699-1705/1324-1329）→ T01（lastFillPick 加 session 参数，只在本会话内查找）
- [x] P1-12 `input` 只取 `ev.target.value`，contenteditable 录不到（inject:384-391）→ T06（isContentEditable → innerText/textContent）
- [x] P1-13 `<select multiple>` 只上报首值+计数（inject:407-418 / Launcher:2594-2597）→ T06（载荷上报 values 数组 → Pick.selectedValues → selectByValues(...)；PageElement 新增多值 API）
- [x] P1-14 label 合成 click 抑制窗 500ms 吞真实点击（inject:326-336）→ T06（加 ev.detail===0 合成特征，不吞真实点击）
- [x] P1-15 跨源 iframe `location.href` 抛异常返回 `''`，元素归错页（inject:291-301 / Launcher:1333-1336）→ T06（顶层 URL 优先，拿不到回退空串交由 Java page.url() 兜底）
- [x] P1-16 `press:` 键名白名单 `[A-Za-z+]+` 丢方向键/数字/功能键（Launcher:2887-2893）→ 收尾（Java 侧白名单 `[A-Za-z0-9/+]+`；注入侧识别导航/功能键 + 非输入元素上的数字/符号；产物改用 `PressKey` 枚举重载）

## 三、P2（6）

- [x] P2-01 `Math.max(0,index)` 负序号静默归零（Planner:308-315）→ 防御性归零是唯一安全 fallback，加注释固化（真实录制 index 恒 >=0）
- [x] P2-02 `seen.add("Page")` 简单名混入 FQN 去重集合（Step:74-86）→ 改用 FQN "com.microsoft.playwright.Page" 判重
- [x] P2-03 `orderBySequence` 用 `out.contains` 去重 O(n²)（Launcher:2034-2048）→ 改 IdentityHashMap-backed Set，O(1)
- [x] P2-04 导出文件名净化但校验与 `public class` 同名（Exporter:99-107）→ 文件名以 public class 名为准（含中文）
- [x] P2-05 桥未就绪时每条命令各起 200ms 轮询，连点补发两次 start（panel:417-441）→ 当前状态机重构已用单例调度器 + outbox splice 解决，无需改动
- [x] P2-06 `escapeJava` 只处理 5 类字符，其余控制字符原样进字面量（Planner:749-764）→ T05（补全 C0 控制字符 / \\b / \\f / 行分隔符转义）

## 四、测试网（T08）

- [x] 夹具① 两会话勾选→取消（钉 P0-2 / P1-11）—— `CodegenLauncherModelTest.twoSessionsCheckThenUncheck_assertPerSessionState`
- [x] 夹具② 同页类新标签（钉 P0-3）—— `StepClassGeneratorTest.pageSwitchGeneratesBindingEvenForSameTarget`
- [x] 夹具③ press 触发 confirm + 链式 alert（钉 P1-07/08）—— `CodegenLauncherModelTest.pressTriggeredChainedDialogsBothBindTrigger`
- [x] 夹具④ 两次不同值提交切页（钉 P0-1）—— `StepClassGeneratorTest.twoSessionsDifferentFillValuesKeepTheirOwnTriggerAction`

## 五、评审 §07 覆盖度矩阵里未覆盖的录制场景（未纳入本轮 25 项修复）

> 这些需要「新事件通道 / 新产物语义 / 跨上下文状态」，工作量远大于逻辑修复，暂未处理：

- #21 定时器 / 异步触发的 dialog（无前置动作可绑）
- #23 文件下载（无 download 事件 / `waitForDownload` 产物）
- #24 hover 展开菜单（不监听 mouseover）
- #25 拖拽 / 拖放（无 drag 事件与产物动词）
- #28 滚动加载 / 虚拟列表（不监听 scroll）
- #29 刷新 / `beforeunload`（刷新不产生动作，弹窗无专门分支）
- #32 负断言（不存在 / 禁用 / 不可见）
- #33 显式等待策略（除 `waitForNewPage` 外不插等待）
- #34 参数化 / 数据驱动（值硬编码）
- 有条件项：#8 自绘下拉无"选择"语义、#12 同标签页导航仅注释、#22 上传需人工替换、#26 滑块、#35 多语言

## 进度日志

- 建立任务列表：2026-11-18
- 完成 P0-1 / P0-3（T02 切页）+ P0-2 / P1-11（T01 会话维度）：pw-codegen 406 tests，0 failures / 0 errors，BUILD SUCCESS
- P0 全部 3 项已修复；T08 夹具①已建（两会话勾选→取消端到端）
- 全部 25 项（3 P0 + 16 P1 + 6 P2）已处理；T08 四个夹具齐全
- T06 引入跨模块改动：PageElement 新增 selectByValues(String...)（pw-web-ui），pw-codegen 产物据此回放多选
- 全量回归：pw-codegen 419 tests / 0 failures / 0 errors / 18 skipped，BUILD SUCCESS（含 -am 上游模块）
- 用户反馈：回退 P1-04（属性值内嵌引号场景 codegen 不会产生），保留 escapeJava C0 补全；新增 PressKey 枚举 + press(PressKey...) 重载，press(String) 标记 @Deprecated，codegen 产物改用枚举；P1-16 注入侧补齐导航/功能键识别
- 全模块 `mvn test` BUILD SUCCESS；PressKeyTest 4 tests passed
