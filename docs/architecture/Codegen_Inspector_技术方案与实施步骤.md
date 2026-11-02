# Codegen Inspector 技术方案与实施步骤

> 基于 Playwright 1.62.1 源码逆向：
> - `packages/injected/src/roleUtils.ts` + `selectorGenerator.ts`（role/name 算法 + 定位器生成）
> - `packages/playwright-core/src/server/launchApp.ts`（Inspector 窗口启动方式）
>
> 源码位置：`C:\Users\12745\Downloads\playwright-1.62.1`

---

## 一、架构总览

```
Java 进程
│
├─ 测试浏览器（用户操作被测页）
│   ├─ BrowserContext
│   │   └─ Page = 被测页
│   │       ├─ CDP Overlay.setInspectMode(searchForNode)  ← 拾取
│   │       └─ addInitScript(codegen-inject.js)            ← role 算法 + recorder
│   │
│   └─ 面板浏览器（独立 Chrome --app= 窗口）
│       └─ launchPersistentContext("--app=file:///.../panel.html")
│           ├─ 无地址栏、无标签页 → 看起来就是桌面应用
│           ├─ Monaco Editor（CDN 加载）
│           ├─ 元素列表
│           └─ 按钮（Start/Stop/Close）
│
└─ 通信：exposeBinding + evaluate（不需要 WebSocket）
```

**核心原则：**
1. 面板用 Chrome `--app=` 模式启动（和 Playwright Inspector 完全一样的做法）
2. role/name 算法直接移植 Playwright `roleUtils.ts` + `selectorGenerator.ts`
3. 纯 Java/Maven 单工程，不需要 Node.js/Electron

---

## 二、为什么不用 Electron

Playwright Inspector 自己就不是 Electron。看 `launchApp.ts` 第 44-49 行：

```typescript
args.push(
    '--app=data:text/html,',
    `--window-size=${width},${height}`,
    '--test-type=',
);
context = await browserType.launchPersistentContext('', { args });
```

就是 Chrome `--app=` 模式：无地址栏、无标签页栏、无书签栏，看起来就是独立桌面应用。

| | Electron | Chrome --app= 模式 |
|---|---|---|
| 额外工程 | Node.js + npm + Vite 构建 | **不需要**，一个 HTML 文件 |
| 额外二进制 | ~200MB Electron | **0**（Playwright 已有 Chromium） |
| 窗口外观 | 独立 OS 窗口 | **一样**（无工具栏 Chrome） |
| Monaco Editor | npm 安装 | **CDN 加载** |
| 通信 | WebSocket | **exposeBinding**（同进程） |
| 打包 | 要打包 Electron | **打 JAR 就行**，HTML 放 resources |
| 和 pause 一致？ | 不是 | **就是 pause 自己的做法** |

---

## 三、面板启动方式

```java
// 面板 = 独立 Chrome 窗口（app 模式，无工具栏）
BrowserContext panelCtx = playwright.chromium().launchPersistentContext(
    "",
    new LaunchPersistentContextOptions()
        .setArgs(List.of(
            "--app=file:///" + panelHtmlPath.replace("\\", "/"),
            "--window-size=1280,800",
            "--window-position=100,100"
        ))
        .setHeadless(false)
);
Page panel = panelCtx.pages().get(0);
```

### 通信方式

```java
// 面板按钮 → Java：exposeBinding
panel.exposeBinding("__codegenCmd", (source, args) -> {
    String cmd = args.get(0).toString();
    switch (cmd) {
        case "start"  -> startRecording();
        case "stop"   -> stopRecording();
        case "close"  -> closePanel();
    }
    return null;
});

// Java → 面板：推送拾取结果
panel.evaluate("entry => window.__onPicked(entry)", pickedEntry);

// Java → 面板：推送录制步骤
panel.evaluate("action => window.__onAction(action)", action);

// Java → 面板：推送生成的代码
panel.evaluate("code => window.__onCode(code)", codeResult);
```

---

## 四、role/name 定位器生成（核心）

### 4.1 直接移植 Playwright 源码

不自己写算法。直接把以下两个文件的逻辑移植到 `codegen-inject.js`：

| 源文件 | 行数 | 作用 |
|---|---|---|
| `packages/injected/src/roleUtils.ts` | 1336 | role + accessible name 完整算法 |
| `packages/injected/src/selectorGenerator.ts` | 597 | 定位器打分选择 |

### 4.2 打分优先级（源码抄录）

| 分数 | 策略 | 对应 Playwright API |
|---|---|---|
| 1 | data-testid | `getByTestId()` |
| 2 | data-test-id / data-test | `getByTestId()` |
| 100 | role[name=子串] | `getByRole(role, {name: "子串"})` |
| 105 | role[name=精确] | `getByRole(role, {name: "完整文本"})` |
| 120 | placeholder | `getByPlaceholder()` |
| 140 | label 子串 | `getByLabel()` |
| 160 | alt 子串 | `getByAltText()` |
| 180 | text 子串 | `getByText()` |
| 200 | title | `getByTitle()` |
| 250 | text 正则 | `getByText(Pattern)` |
| 500 | CSS #id | `locator("#id")` |
| 510 | role 无 name | `getByRole(role)` |
| 530 | CSS tag | `locator("button")` |
| 10000 | nth(n) | `.nth(n)` |
| 10000000 | CSS fallback | 绝对路径 |

**选择规则：从分数最低的候选开始试，第一个唯一匹配目标元素的就用。**

### 4.3 role 判定

```
explicitRole = element.getAttribute("role")
  ├─ 有合法 role → 用之
  └─ 无 → 按 HTML 标签映射
       ├─ <button> → button
       ├─ <a href> → link
       ├─ <input type=checkbox> → checkbox
       ├─ <input type=text> → textbox
       ├─ <select> → combobox
       ├─ <h1>~<h6> → heading
       └─ ...（完整表见 roleUtils.ts 第 89-217 行）
```

### 4.4 accessible name 计算优先级

```
1. aria-label 属性
2. aria-labelledby 引用元素文本
3. <label for="id"> 关联文本
4. 包裹的 <label> 文本
5. <input type=button/submit/reset> 的 value
6. <img alt>
7. <fieldset> → <legend> 文本
8. name from content（button/link/heading 等允许从子内容算 name）
9. title 属性（最后兜底）
```

### 4.5 拾取流程

```
1. CDP Overlay.inspectNodeRequested → backendNodeId
2. DOM.resolveNode → objectId
3. Runtime.callFunctionOn(objectId, "__CODEGEN__.generateLocator(this)")
   → 返回 {selector, score, strategy}
4. NLS 反查 name → 如果命中，替换为 Nls.t("key")
5. panel.evaluate("entry => window.__onPicked(entry)", entry)
```

---

## 五、recorder 注入脚本

纯 CDP 无法捕获用户在 input 里输入了什么，所以录制必须注入脚本。

```javascript
// codegen-inject.js（通过 context.addInitScript 注入到被测页）
window.__CODEGEN__ = {
  // 从 roleUtils.ts 移植
  getAriaRole(el) { ... },
  getAccessibleName(el) { ... },

  // 从 selectorGenerator.ts 移植
  generateLocator(el) { ... },

  // recorder：监听真实操作
  recorder: {
    start() {
      document.addEventListener('click', e => {
        window.dispatchEvent(new CustomEvent('__codegen_event', {
          detail: { type: 'click', target: e.target }
        }));
      });
      document.addEventListener('input', e => {
        window.dispatchEvent(new CustomEvent('__codegen_event', {
          detail: { type: 'fill', target: e.target, value: e.target.value }
        }));
      });
    },
    stop() { ... }
  }
};
```

Java 侧通过 `page.onConsoleMessage` 或 `exposeBinding` 接收事件。

---

## 六、面板 UI（panel.html）

```html
<!DOCTYPE html>
<html>
<head>
  <meta charset="utf-8">
  <link rel="stylesheet" href="https://cdn.jsdelivr.net/npm/@mui/material@5.14.0/umd...">
  <script src="https://cdn.jsdelivr.net/npm/monaco-editor@0.45.0/min/vs/loader.js"></script>
</head>
<body>
  <!-- 工具栏 -->
  <button id="btn-start">Start</button>
  <button id="btn-stop">Stop</button>
  <button id="btn-close">Close</button>

  <!-- 元素列表 -->
  <table id="elements-table">
    <thead><tr><th>#</th><th>名称</th><th>定位器</th><th>分数</th><th></th></tr></thead>
    <tbody></tbody>
  </table>

  <!-- 代码区 -->
  <div id="code-tabs">
    <button data-tab="page">Page</button>
    <button data-tab="step">Step</button>
    <button data-tab="assert">Assert</button>
  </div>
  <div id="monaco-container" style="height:400px"></div>

  <script>
    // Monaco Editor 初始化
    require.config({ paths: { vs: 'https://cdn.jsdelivr.net/npm/monaco-editor@0.45.0/min/vs' }});
    require(['vs/editor/editor.main'], () => {
      window.editor = monaco.editor.create(document.getElementById('monaco-container'), {
        language: 'java',
        readOnly: true
      });
    });

    // Java → 面板：推送拾取结果
    window.__onPicked = (entry) => {
      // 追加到元素表格
    };

    // Java → 面板：推送代码
    window.__onCode = (code) => {
      window.editor.setValue(code.pageClass);
    };

    // 面板 → Java：按钮点击
    document.getElementById('btn-start').onclick = () => window.__codegenCmd('start');
    document.getElementById('btn-stop').onclick = () => window.__codegenCmd('stop');
    document.getElementById('btn-close').onclick = () => window.__codegenCmd('close');
  </script>
</body>
</html>
```

---

## 七、NLS 反查

```
拾取到元素 → getAccessibleName() 得到 "登录"
  ↓
Java 侧加载 NLS JSON bundle（zh-CN.json）
  ↓
遍历 NLS key-value：
  ├─ value === "登录" → 命中 "button_login"
  │   → locator 改为 getByRole(BUTTON, name=Nls.t("button_login"))
  └─ 不命中 → 保持 getByRole(BUTTON, name="登录")
```

特殊情况：
- name 含占位符 `{{xxx}}` → 生成 `Pattern.compile(...)`
- name 含 HTML 标签 → `asFlatString()` 已处理

---

## 八、项目结构

```
pw-codegen-v3/                                          ← 全新模块，与 v2 彻底隔离
├── src/main/java/.../web/page/codegen/
│   ├── CodegenLauncher.java          // 入口：会话编排 + 命令路由 + 点击高亮交互（阻塞至 Close）
│   ├── cdp/
│   │   ├── CodegenCdpSession.java    // CDP Overlay 拾取（启用 DOM/Accessibility/Runtime/Overlay）
│   │   └── CodegenCdpNodeExtractor.java  // 节点反查（role/name/cssSelector + name 文本回退）
│   ├── inject/
│   │   └── CodegenScriptInjector.java    // addInitScript 注入 codegen-inject.js
│   ├── locator/
│   │   └── LocatorStrategy.java      // 策略常量（TESTID/ROLE_WITH_NAME/ROLE/CSS）
│   ├── nls/
│   │   └── NlsReverseLookup.java     // NLS 反查（value→key）
│   ├── codegen/
│   │   └── PageClassGenerator.java   // Page 类生成（最小可用；Step/Assert 待续）
│   ├── panel/
│   │   ├── CodegenPanel.java         // Chrome --app= 面板窗口 + exposeBinding/evaluate 通信
│   │   └── PanelResource.java        // 面板资源解析（开发态直引 / jar 内导出临时文件）
│   ├── model/
│   │   └── CodegenEntry.java         // 拾取结果 DTO（同时是面板 JSON 载荷）
│   └── quiet/
│       └── CodegenQuiet.java         // best-effort 链路静默忽略
│
└── src/main/resources/codegen/
    ├── panel.html                    // 面板 UI（flex 响应式，窗口可 OS 拖拽缩放）
    ├── panel.js                      // 面板逻辑（Monaco CDN + 离线降级 + 行点击高亮交互）
    ├── panel.css                     // 面板样式
    └── codegen-inject.js            // 注入被测页：role/name 算法子集 + recorder
```

> 注：`StepClassGenerator` / `AssertClassGenerator` / `CodegenRecorder`（独立类）本轮未落地，
> recorder 逻辑暂内嵌于 `codegen-inject.js`，Step/Assert 生成按计划（D13/D14）后续迭代。

---

## 九、弹窗与导航

| 场景 | 处理 |
|---|---|
| 新弹窗/新标签页 | `context.onPage()` → 新 page attach CDP + 注入 recorder |
| 弹窗关闭 | `page.onClose()` → detach，切回父页 |
| 同页导航 | addInitScript 自动重挂；CDP Overlay 重设 |
| 跨源 iframe | **拾取 + 录制均支持**：recorder 经 context 级 `exposeBinding` + `addInitScript`（Chromium 的 `Page.addScriptToEvaluateOnNewDocument` / `Runtime.addBinding` 对所有 frame 生效，含跨源 iframe），故跨源 iframe 内 click/fill 也会触发并匹配已拾取字段；**唯一缺口已闭环 = frame 穿透选择器**：元素位于 iframe 内时，`CodegenCdpNodeExtractor` 沿父 frame 上行（`DOM.getFrameOwner`）记录 `framePath`，生成 `@Element("frame=<iframe选择器> :: <内层 css>")`，由框架 `LocatorFactory.bySelector` 解析为 `page.frameLocator(...).locator(...)`，保证产物可运行（见第十三节）。 |

---

## 十、实施步骤

### 第 1 周：骨架 + CDP 拾取

| 天 | 任务 | 产出 |
|---|---|---|
| D1 | panel.html 基础布局（按钮 + 表格 + Monaco CDN） | Chrome --app= 窗口能打开 |
| D2 | exposeBinding + evaluate 双向通信 | 按钮点击 → Java 收到命令 |
| D3 | CDP Overlay attach + inspectNodeRequested | 点击页面元素，Java 收到 backendNodeId |
| D4 | DOM.resolveNode + Runtime.callFunctionOn | 能拿到元素信息 |
| D5 | 面板元素表格实时刷新 | 拾取结果推送到 UI |

### 第 2 周：定位器算法

| 天 | 任务 | 产出 |
|---|---|---|
| D6 | 移植 roleUtils.ts 核心函数到 codegen-inject.js | getAriaRole + getAccessibleName 可用 |
| D7 | 移植 selectorGenerator.ts 打分逻辑 | generateLocator(element) 返回最优定位器 |
| D8 | 唯一性校验 + 父级嵌套降级 | 多匹配自动加 parent >>> child |
| D9 | NLS 反查模块 | name 命中 NLS → 替换为 Nls.t(key) |
| D10 | 面板列表显示策略/分数/来源 | UI 展示完整拾取信息 |

### 第 3 周：录制 + 代码生成 + 闭环

| 天 | 任务 | 产出 |
|---|---|---|
| D11 | recorder 注入脚本（click/fill/navigate） | 用户操作页面，步骤实时显示 |
| D12 | Page 类代码生成 | 每个元素 → @RoleElement 注解字段 |
| D13 | Step 类代码生成 | 录制的动作 → 方法 |
| D14 | Assert 类代码生成 | 断言代码 |
| D15 | 弹窗跟随 + 关闭清理 + 打包验证 | 完整闭环 |

---

## 十一、关键决策

| 决策 | 结论 | 原因 |
|---|---|---|
| 面板技术栈 | Chrome --app= 模式 | 和 Playwright Inspector 完全一样 |
| 是否需要 Electron | 不需要 | --app= 模式已经是独立窗口 |
| 是否需要 Node.js 工程 | 不需要 | 面板就是一个 HTML 文件 |
| role/name 算法来源 | ~~移植 roleUtils.ts~~ → 改为「AX 语义 + Playwright 引擎复核」 | 整份移植成本高且需跟随版本；AX 是浏览器实际暴露的语义，边缘分歧由引擎复核兜住 |
| 定位器生成 | 移植 selectorGenerator.ts | 和 Inspector 窗口逐字一致 |
| CDP Overlay | searchForNode 模式 | Chrome 原生处理点击拦截 |
| recorder 注入 | addInitScript | 录制 fill 值必须注入 |
| 通信方式 | exposeBinding + evaluate | 同进程，不需要 WebSocket |
| 验证兜底 | 不需要 | 用 Playwright 自己的算法 |
| role/name 权威来源 | 浏览器 AX 树（`Accessibility.getPartialAXTree`） | 页面侧 `roleOf` 只是标签推断，与 `getByRole` 不等价（label/div/span 会落到无语义角色） |
| role 唯一性校验 | 真实 Playwright 引擎 `getByRole(role,name,exact).count()` 复核，非唯一即回退 CSS | 与 `page.pause` 产物一致；AX 与 roleUtils 在边缘元素上存在已知分歧 |
| 复核"不可判定"（引擎抛异常） | 放行（视为唯一），`count` 同样异常返回 1 | 环境抖动时宁可保留 role 也不误杀；此放行策略是**有意为之**，不是遗漏 |
| 计数为 0 | 视为唯一 | 页级计数只看主 frame，跨 frame 元素恒 0；与「子 frame 元素跳过复核、直接走 CSS」配套使用 |
| 选择器反查前置条件 | `DOM.enable` + `DOM.getDocument`（幂等预热，失败自愈重试） | 缺文档树时 `DOM.requestNode` 恒返回 0，整条选择器路径会静默失效 |
| 拾取可观测性 | 每次拾取输出 `pick profile`（阶段耗时 + css/role 计数次数），超 `codegen.pick.slow.ms` 升 WARN | 性能问题必须可定位到"哪一段、哪类计数"，否则只能靠猜 |

---

## 十二、当前落地状态（首轮交付，2026-10-26）

全新模块 `pw-codegen-v3` 已搭好骨架并编译通过（取代 v2 的 setContent 注入面板 + evaluate 通信方案）。

### 已落地

| 能力 | 实现 | 对应原痛点 |
|---|---|---|
| 面板 = Chrome `--app=` 独立窗口 | `CodegenPanel.open` 用 `launchPersistentContext(... --app=file:///panel.html)` | 不再是嵌在被测页/注入式 DOM 的脆弱面板 |
| 可拖拽改变大小 | 窗口本身 OS 可拖拽缩放 + 面板内部 flex 响应式填满（无 `setViewportSize` 固定尺寸） | **痛点②：固定大小不能拖拽** |
| 列表项点击高亮被测页元素 | 行点击 → 桥命令 `highlight` → Java 经 `Locator.highlight()` + `scrollIntoViewIfNeeded()` | **痛点③：面板无交互（类 page.pause）** |
| name 不再恒为 null | 抽取层在可访问名缺失时回退元素文本（单行、≤120 字符护栏），见 `CodegenCdpNodeExtractor` + `codegen-inject.js#nameOf` | **痛点①：name=null** |
| 通信 | `exposeBinding`（面板→Java）+ `evaluate`（Java→面板），无 WebSocket | 干净、无 v2 的 setContent 注入坑 |
| 拾取 | CDP `Overlay.setInspectMode(searchForNode)` + `DOM.resolveNode` + `Runtime.callFunctionOn` | 与 page.pause 同源 |
| NLS 反查 | `NlsReverseLookup`，命中即 `Nls.t(key)` | — |
| 类名由 URL 派生 | 调用方**不传 `pageClassName`**：`CodegenTarget` 由被测页当前 URL 派生页面类名（`…/portalserver/logon ⇒ LogonPage`），并同源派生 `LogonSteps`（`<页面包>.steps`）与 `LogonAssertions`（`<页面包>.assertions`）。Stop 时**重新读一次 URL**，拾取过程中发生导航也能对应"实际被拾取的页面" | 避免各人随手命名导致等价重复类；三 Tab 名字天然一致 |
| 三类产物共用模板 | Page / Step / Assert 全部走 FreeMarker（`codegen/templates/page-class.ftl`、`step-class.ftl`、`assert-class.ftl`，与框架报告侧 pw-reports 共用引擎 `2.3.34`），统一入口 `CodegenTemplates.render`（UTF-8 + 行尾归一化 LF，跨 OS 字节一致保障 CI 漂移校验） | 改产物格式只改模板，不碰 Java |
| 三 Tab 共用一份字段规划 | `CodegenFieldPlanner` 是**唯一命名与定位来源**：去重 → List 分组 → 命名 → 定位注解 → 动作/断言表达式，产出 `Plan`（含 `CodegenFieldSpec` 的 `fieldName/verb/action/assertion`）供三个生成器消费 ⇒ Tab1 的 `public PageElement signIn;` 与 Tab2 的 `loginPage.signIn.click()` 一定对得上 | 消除"各 Tab 各自命名导致产物拼不成可运行代码" |
| Page 类生成 | `PageClassGenerator`：类骨架 + 字段循环 + import 条件全在 `page-class.ftl`；**输出契约与 V2 严格对齐**（`@RoleElement` + `PageElement`/`PageElementList` 字段 + `extends AbstractManagedPage` + 类级 `@RoleFile`）+ `<generated:fields>` 标记块（预留增量合并 F-15） | — |
| Step 类生成 | `StepClassGenerator`：包名 `<页面包>.steps`，持有 `PageObjectFactory.getPage(…)` 页面字段，每字段一个 `@Step public void <verb><Field>()`（textbox/combobox→`fill("")`、checkbox/radio→`check()`、其余→`click()`；一组元素取 `get(0)`；占位 `fill("")` 自带 TODO） | 与 v2 `RoleElementStepGenerator` 形态对齐 |
| Assert 类生成 | `AssertClassGenerator`：包名 `<页面包>.assertions`，每字段一个 `@Step public void assert<Field><Text\|Checked\|Visible>()`；**hamcrest 风格 `assertThat(actual, is(expected))`**，断言失败时由 JUnit 打印 expected/actual 两段文本、一眼定位字符差异。派生规则：有可读 name 的元素断言其 `getText()`（输入框/下拉取 `getValue()`）等于拾取时记录的名字；checkbox/radio 断言 `isChecked()`；无 name 的容器退化断言 `isVisible()` | 与 v2 `RoleElementAssertionGenerator` 的 `assertThat(..., equalTo(...))` 同风格 |
| 定位器选择策略 | ① **无 NLS 或反查不到 key ⇒ 一律 `name=` 定位**（不产 `key=`，保证产物无需 NLS 表即可运行）；② **role 可定位**（role 非 generic 且有可读 name）⇒ `@RoleElement(role=…, name=/key=)`；③ **role 定位不了**（generic / 无 name）⇒ 回退 `@Element("<cssSelector>")`（CSS 与 XPath 统一走 `@Element.value`）| — |
| List（一组元素） | 注入脚本返回元素"同级序号 `index`"，`sigKey` 挂 `#index`，生成器按 **base-sig（剔除 `#N`）分组**：组内序号互异且 ≥2 条 ⇒ 合成**一个** `PageElementList`（运行期 `x.get(i)` 取单项）；同元素重复点选（序号相同）不成组。**自动识别覆盖不到的场景**（不同容器里同 role+name ⇒ sigKey 相同、序号互不异）由面板「标记为一组」显式合成，见下行 | — |
| 面板「标记为一组」 | 每次拾取分配唯一 `pickId`（不能用 sigKey：不同容器的同 role+name 元素 sigKey 完全相同，会互相覆盖）；列表行带复选框，勾选 ≥2 项点「标记为一组」⇒ 回传 `group <ids…>` ⇒ Java 把这些条目重写为「同一 base-sig + 互异序号」⇒ 生成器合成**一个** `PageElementList`；面板回执给行加 `[组]` 标记；成员 role/name 不一致时 WARN 提示"列表定位器取自首个成员，请确认能匹配全部项" | — |
| NLS（语言前置） | NLS 文件是 **两级结构** `{ "zh": { key: value }, "en": {...} }`，必须**先用 `NLSUtils.setLanguage("zh")` 告知框架当前语言**才能反查。`NlsReverseLookup` 经 `NLSUtils.rawTables()` 取语言表、用 `NLSUtils.visibleText()` 归一化（剥离 `<b>/<img>` 标签与 `&nbsp;` 实体，匹配浏览器可见文本）。传了 nls 但没设语言 ⇒ `inspect` **直接拒绝启动**并提示；反查未命中回落 `name=` | 面板语言下拉（切换后 `reload`） |
| Monaco 代码区 | CDN 加载，**离线自动降级**为 `<pre>` | — |

### 待续（按文档 D6–D15）

> 截至 2026-10-27，D6–D15 中的**打分定位器算法、录制器独立化、操作序列、多弹窗/新标签页/跨源 iframe 拾取与录制跟随**均已完成（见第十三节）。最后闭环的缺口是 **frame 穿透选择器**：元素位于（跨源）iframe 内时，生成的选择器必须串成 `frameLocator` 链才能被框架 `LocatorFactory.bySelector` 解析定位，否则 `page.locator(css)` 在主页面找不到 iframe 内的元素。该缺口已在第十三节 ③ 补完。

- ~~完整移植 `selectorGenerator.ts`（打分选择 + 唯一性校验 + 父级嵌套降级）~~ → 已完成：`CodegenScorer`（纯 Java）按官方打分优先级选最优定位器，`page.locator(css).count()` 做唯一性校验，不唯一则逐级父级嵌套降级（深度上限 4）。
- ~~`CodegenRecorder` 独立化（按真实操作序列编排 Step 方法调用顺序）~~ → 已完成：`CodegenRecorder` 保序记录 click/fill，Stop 时按录制序列重排 entries，Step/Assert 方法体按真实操作先后生成；面板「Recorded Steps」区实时展示操作序列。
- ~~多弹窗/新标签页/跨源 iframe 的 attach 与跟随（D15）~~ → 已完成（拾取 + 录制维度）：`context.onPage` 自动挂载新弹窗/新标签页的 CDP 会话并切换活动页；跨源 iframe 经**自包含抽取函数**实现拾取，且 recorder 经 context 级 `exposeBinding` + `addInitScript`（Chromium 对页面下所有 frame 生效，含跨源 iframe）实现录制，跨源 iframe 内的 click/fill 同样触发并匹配已拾取字段；frame 穿透选择器见第十三节 ③。

### 调用方式

```java
// 在测试/工具入口处调用（阻塞至面板 Close）

// ① 只传 page：无 NLS ⇒ 一律 name 定位；类名由 URL 派生（如 /portalserver/logon → LogonPage）
CodegenLauncher.inspect(inspectedPage);

// ② page + NLS（可变参数，支持多个，不用 new String[]）；类名同样由 URL 派生
NLSUtils.setLanguage("zh");                    // 必须先告知框架当前语言
CodegenLauncher.inspect(inspectedPage, "nls/login.nls.json", "nls/common.nls.json");
```

> **为何 `inspect` 只接收 `page` + NLS 文件**：包名与类名都不接收传入。包名恒为
> `CodegenTarget.DEFAULT_PACKAGE`（`com.example.pages`）；类名、步骤类名、断言类名一律由被测页当前 URL
> 派生（见 `CodegenTarget`），不接受调用方指定——同一页面多次拾取得到同一个类名，产物可 diff、可评审，
> 且三个 Tab 的类名天然一致。`inspect(page, nls...)` 在两个字符串实参时也不会与任何"类名/包名重载"撞参
> （根本不存在这类重载）。

## 十三、第二批交付（2026-10-27）：打分 / 序列 / 多窗口 / 跨源 iframe

在首轮交付（第十二节）基础上，按用户要求把 D6–D15 中剩余的三块硬骨头逐项落地。三项均已在 `pw-codegen-v3` 实现并通过模块单测（46 例 0 失败；需要真实浏览器的端到端场景依赖人工实测）。

### ① 官方打分定位器算法（D6/D7/D8）

- **事实收集与打分解耦**：`codegen-inject.js` 的 `getInfo` 改为返回**富事实**（role/name/id/testid/placeholder/alt/title/text/label + 父级链 6 层），Java 侧 `CodegenCdpNodeExtractor` 组装 `CodegenFacts` 后委托 `CodegenScorer`。
- **纯 Java 打分器**（`CodegenScorer`，`LocatorCounter` 注入 `page.locator(css).count()`，可脱离浏览器单测）按 `selectorGenerator.ts` 优先级选最优：testid=1 → role+name=105 → id=500 → placeholder=120/alt=160/title=200 → CSS 链=530。
- **唯一性校验 + 父级嵌套降级**：选定 CSS 后经 `count()` 校验唯一性，不唯一则逐级父级 CSS 嵌套（空格后代组合子），深度上限 4，仍不唯一则保留最长链并 WARN。
- 新增 `CodegenScorerTest`（8 例）覆盖各档位优先级、NLS 复用、嵌套降级、深度护栏。

### ② 录制器独立化 + 操作序列（D11/D13/D14）

- 抽出 `CodegenRecorder`：保序、首次出现去重地记录 click/fill 动作（`orderedFieldKeys()` 输出真实操作序列）。
- 注入脚本每个 click/fill 事件附带被操作元素富事实；`CodegenLauncher` 匹配到已拾取字段后保序记录；Stop 时按录制序列重排 entries，使 Step/Assert 方法体按真实操作先后生成（`orderBySequence` + `CodegenSequenceTest` 3 例）。
- **面板「Recorded Steps」区**：每次操作按真实顺序渲染为有序列表项（`click`/`fill`），进入 inspect 时自动清空上一轮。

### ③ 多弹窗 / 新标签页 / 跨源 iframe 跟随（D15，拾取 + 录制维度）

- `startSession` 改为**每页一个 CDP 会话 + 抽取器**，活动页随用户操作切换：`context.onPage` 自动挂载新弹窗/新标签页的 CDP 会话（注入已由 `context.addInitScript` 覆盖后续新建页），inspect 进行中则切换活动页；弹窗关闭经 `page.onClose` 切回父页并（若仍在 inspect）回退到主页面。
- `__codegenEvent` 改为 **context 级**绑定 ⇒ 所有页面（含弹窗、含跨源 iframe）可用；`Pick` 记录所属页，高亮/计数落在正确页。
- **跨源 iframe 拾取**：抽取函数改为**自包含**（不再依赖 `window.__CODEGEN__`，见 `CodegenCdpNodeExtractor#SELF_CONTAINED_GET_INFO_FN`），直接在元素所属 frame 执行上下文求值，跨源隔离不再阻断拾取。
- **跨源 iframe 录制**：recorder 的 `exposeBinding` + `addInitScript` 均为 context 级，底层走 Chromium `Page.addScriptToEvaluateOnNewDocument` / `Runtime.addBinding`，对页面下**所有 frame（含跨源 iframe）**生效，故跨源 iframe 内的 click/fill 同样触发 `window.__codegenEvent`，并由 `CodegenLauncher.matchFieldKey` 匹配到已拾取字段、保序记录。这一步此前被误判为「受限」，实测 Chromium 的 document-creation 钩子不在进程隔离边界内，跨源 iframe 内 recorder 照常运行。
- **frame 穿透选择器（闭环关键）**：元素若位于 iframe 内，`CodegenCdpNodeExtractor` 在抽取时记录其所属 frame 路径（`CodegenEntry.framePath`，外层 → 内层 each frame 的 `<iframe>` 宿主选择器），生成阶段（`CodegenFieldPlanner`）据此输出 `@Element("frame=<iframe选择器> :: … :: <内层 css>")`；框架 `LocatorFactory.bySelector` 解析该前缀为 `page.frameLocator(<iframe选择器>).frameLocator(…).locator(<内层 css>)`，从而**定位跨源 iframe 内的元素**。role 可定位元素位于 iframe 内时，因 role 无法跨 frame，统一退化为 `@Element("frame=… :: <css>")`。面板高亮（`CodegenLauncher.highlight`）同样尊重 `framePath` 用 `page.frameLocator(…).locator(…)` 下钻。

**验证**：
- codegen：`mvn -o -pl pw-codegen-v3 test -Dcve.gate.skip=true -Dspotbugs.skip=true` → 含 `PageClassGeneratorTest`（frame 穿透生成 3 例）全绿。
- 框架：`LocatorFactoryTest` 新增 5 例覆盖 `frame=… :: …` 前缀解析（单帧 / 嵌套帧 / 忽略当前 frame 根 / 畸形抛异常 / 非 frame 选择器不受影响）。

**仍待人工实测（需真实浏览器 + HSBC SIT 内网）**：弹窗/新标签页的 CDP 跟随、跨源 iframe 拾取与录制、frame 穿透高亮，均无法在当前无浏览器环境单测，需在浏览器里点击触发验证（代码路径已由单测覆盖选择器生成与解析两端）。

---

**Stop 后三个 Tab 同时产出**（面板按 Tab 各自缓存，互不冲掉）：

```java
// com.example.pages.LogonPage            （Tab1）
// com.example.pages.steps.LogonSteps     （Tab2）
// com.example.pages.assertions.LogonAssertions（Tab3）
```

---

## 十四、第三批交付（2026-10-29）：拾取链路性能与正确性加固

### 14.1 实测基线（本机 chromium headless，合成页，warm）

单次命令成本：

| 命令 | 耗时 |
| --- | --- |
| `page.locator(css).count()` | 2.7 / 3.6 / 6.2 ms（800 / 2k / 4k 节点） |
| `page.getByRole(role,name).count()` | 11.4 / 44.6 / **93.3 ms**（同上，超线性） |
| 单条 CDP（getFrameTree / describeNode / getPartialAXTree …） | 0.8~1.4 ms |

一次 `extract` 的耗时构成（改造前）：

| 场景 | 总计 | 其中 `countByRole` | 其中 `count(css)` |
| --- | --- | --- | --- |
| role 可定位元素（800 / 2k / 4k） | 18.2 / 52.6 / 105.6 ms | **67% / 85% / 88%** | 1 次 |
| CSS 链元素（800 / 2k） | 11.8 / 17.6 ms | 0 | **3 次**（53~62%） |
| iframe 内元素 | 21.9 ms | 1 次（必然 0 命中，纯浪费） | 1 次 |

### 14.2 三个根因与处置

| # | 根因 | 处置 | 效果 |
| --- | --- | --- | --- |
| R1 | **选择器反查路径整体失效**：`DOM.enable` 只订阅事件、不推文档树，`DOM.requestNode` 恒返回 `nodeId=0` ⇒ 每次拾取白跑一遍注定失败的探测，再退回坐标 hit-test；坐标法看不见跨源 iframe 内部 ⇒ **跨 frame 拾取实际不可达** | `CdpFrameLocator` 改用 `DOM.describeNode(objectId)` 直取 `backendNodeId`（少一次往返、不依赖节点表），并在失败时 `DOM.getDocument` 自愈一次（`ensureDocument()`，幂等）；`enablePickChannel` 预热一次 | 选择器路径恢复（回归单测 `nodeResolutionSelfHealsByPrimingDocument` / `documentIsPrimedOnlyOnce`），拾取少 5ms（首帧 27ms）的无效开销 |
| R2 | **页级 role 复核对子 frame 元素恒 0 命中**：`page.getByRole` 只在主 frame 搜索 | `ElementRef` 增加 `FrameScope`（MAIN/SUB/UNKNOWN，由定位阶段随引用带出）；抽取时子 frame 元素**跳过**复核，直接产出 CSS（与"复核后必然回退"结果一致） | iframe 拾取 21.9 → 11.9 ms（−46%），大页面按 role 计数成本等比例下降（数十毫秒） |
| R3 | **同一个选择器被 count 2~3 次**：`uniqueCss` 已计数一次，调用方 `isUnique` 再数一次；兜底 CSS 还在 role 判定前 eager 计算 | `CodegenScorer.uniqueCss` 返回 `Unique(css,count)`，调用方直接复用最后计数；首选候选结果缓存给循环与兜底分支复用；`isUnique` 删除 | 每个候选恒定少 1~2 次往返：CSS 链元素 3 次 → **1 次**，17.6 → 10.7 ms（−39%，2k） |
| R4 | 主 frame 元素每次拾取重复读 `Page.getFrameTree`（定位阶段 + 抽取阶段各一次） | `FrameScope=MAIN` 时抽取阶段不再读树；`SUB` 时读一次并复用给穿透路径 | 每次拾取少一条 CDP 往返 |
| R5 | 慢在哪一层不可观测 | 抽取器内置 `pick profile`（resolve/facts/ax/score/role/tail + cssCounts/roleCounts），常态 DEBUG、超过 `codegen.pick.slow.ms`（默认 300ms）WARN | 现场可直接定位"慢在 role 复核还是 CSS 计数" |

### 14.3 关键决策

1. **不做「完整移植 roleUtils.ts」**：维持「AX 语义候选 + Playwright 引擎复核 + CSS 回退」三态闭环（正确性与 `page.pause` 对齐），性能问题用"跳过无意义复核 + 去重复计数 + 可观测"解决。
2. **`count=0` 视为唯一（历史语义）保留**：改为按元素所属 frame 计数会动产物选择结果，属独立评审项；当前通过"子 frame 元素直接走 CSS、并在报告中标注"规避最坏情况。→ **第四批已改**：计数改为按元素自身 frame（+shadow 链）作用域，见 §15.2。
3. **role 复核仍在关键路径（同步）**：真实形态页面（4k 节点、少量按钮）复核约 13ms，占总 28ms；把复核移出关键路径需要面板支持"条目二次修订"（新增 `__onPickedRevised` + 重新生成），属交互语义变更，需另行确认后再做。→ **第四批已落地**：复核后置到生成前，见 §15.1。
4. **临时基准不进仓库**：性能数据用一次性基准测（本机会话内跑完即删），仓库只保留行为契约单测（计数次数、frame 归属、自愈重试、子 frame 跳过）。

### 14.4 验证

- `mvn -o -pl pw-codegen-v3 clean test "-Dcve.gate.skip=true" "-Dcheckstyle.skip=true" "-Dspotbugs.skip=true"` → **121 例（+10）0 失败**，1 例既有跳过。
- `mvn -o -pl pw-codegen-v3 verify -DskipTests "-Dcve.gate.skip=true" "-Dspotbugs.skip=true"` → Checkstyle 通过、BUILD SUCCESS。
- 改造后同场景复测：CSS 链元素 3 次计数 → 1 次；iframe 拾取 role 计数 1 → 0、耗时 −46%；`elementBySelector` 返回 `ElementRef[… scope=MAIN]`（改造前恒 `null`）。

### 14.5 已知遗留（后续独立评审项）

无：本节原列的两项（**facts 精简**、**`objectId` 直传**）已随第四批落地，见 §15.5。

---

## 十五、第四批交付（2026-10-29）：role 复核后置 + frame 作用域批量计数

14.5 里挂起的两项已落地，拾取响应不再随 DOM 规模增长。

### 15.1 role 复核：拾取只给候选、生成前统一复核

**行为固定，不提供任何配置开关**（没有 `codegen.role.verify.mode` 之类系统属性）：`extract` 只给 AX 语义的
role 候选，一次 `getByRole(...).count()` 都不发；`requiresDeferredRoleVerification(entry)` 告知调用方该条目
是否欠一次复核，调用方在**任何产物输出前**必须调 `countRoleMatches(entry)`：`1` 命中保留 role，
`0` / `>1` 用 `RoleRevision.toCss` 回退 CSS，`-1`（不可判定）放行。

| 环节 | 行为 |
| --- | --- |
| 拾取（点击响应内） | 0 次 `getByRole(...).count()`，除 AX 语义外零额外成本 |
| 生成前（`pushGeneratedCode` / Stop） | 逐条 `countRoleMatches`，命中数决定是否回退 CSS |
| 单个元素 | 必须**恰好 1** 命中（严格模式下多命中即 violation） |
| 一组元素（`PageElementList`） | 命中数必须**等于组内拾取数**，多/少都说明组被污染，整组一起回退（见 §15.6） |
| 复核不可判定（CDP/页面异常） | WARN + 保留 role —— 环境抖动不该把可靠产物退化成结构 CSS |
| 子 frame 元素 | 直接走穿透 CSS：页级 `getByRole` 对它们恒 0 命中，role 无从校验，也不产生欠账 |

**正确性如何保证**（关键设计）：`Pick` 上挂 `rolePending` 标记；`pushGeneratedCode`（实时增量生成）与 Stop 生成都必须先走
`verifyPendingRoles`：`hits != 1` 时通过 `RoleRevision.toCss` 把条目回退 CSS、推面板 `__onPickedRevised`
**就地更新该行**（不新增行）并重生成。因此**产物与"拾取时同步复核"逐字一致**，代价只是代码 Tab 晚几十毫秒刷新。

线程与一致性：复核在 `codegen-live-gen` 单线程执行；拾取行由拾取执行器推送。两者都会 `evaluate` 同一面板页，
故 `CodegenPanel.safeEval` 统一加 `PUSH_LOCK` 串行化（Playwright `Page` 不宜并发调用）。`Pick.entry` 改为 `volatile`。

### 15.2 frame/shadow 作用域批量计数（`CdpLocatorCounter`）

- CSS 计数改由 CDP 在<b>元素所属 frame 的隔离世界</b>里一次 `Runtime.evaluate` 完成（`CdpFrameLocator#countSelectors`）：
  候选链（5~20 个）合并成 **1 次往返**；作用域 = `frame + shadow 宿主链`，与生成物
  （`frame=… :: <css>` / `host >>> <css>`）的解析口径一致。XPath 候选按 `document.evaluate` 文档作用域统计。
- 修复"iframe 内元素唯一性从未校验"：`page.locator` 对它们恒 0 命中，而 `0` 被当作唯一；现按 frame 计数（实测页级 0 / 按帧 1）。
- 缓存生命周期 = 一次拾取（`forFrame` 返回新实例），页面变化不会读到陈旧计数；CDP 不可判定时回退 Playwright 页级计数，
  仍失败则视为唯一（fail-soft，与历史一致）。

**实测（本机 chromium headless，按钮密集合成页，含 1 个 srcdoc iframe）**：

| DOM 规模 | 拾取即复核（改造前基线） | 现实现（拾取只取 AX） | 生成前复核成本 |
| --- | --- | --- | --- |
| ~830 节点 | 20.5 ms | **11.7 ms** | 11.0 ms |
| ~4k 节点 | 68.5 ms | **8.7 ms** | 50.4 ms |
| ~8k 节点 | 126.7 ms | **9.7 ms** | 92.6 ms |

一致性校验（同一轮基准内断言）：`#save`/`#note`/`button.btn-link`/`#list > div` 的 CDP 批量计数与 Playwright 页级计数
**逐一相等**（1/1、1/1、400/400、2000/2000、4000/4000）；"拾取即复核"与"生成前复核"两条路径产出的 `cssSelector` 完全相同；
iframe 内元素 `page=0 / frameScoped=1`（唯一性从"未校验"变为可校验）。

### 15.3 新增单测（32 例，全部纯单测无浏览器）

- `CdpFrameLocatorTest`（+4）：批量计数只发一次求值、失败返回空 Map、frameId 未知不发请求、表达式覆盖 shadow 链与 XPath。
- `CodegenCdpNodeExtractorRoleDeferralTest`（+6）：拾取零复核且标记欠账、子 frame 不给 role 策略、`countRoleMatches`
  的 1/0/多命中/不可判定、`objectId` 直传省掉 `DOM.resolveNode`、失效 `objectId` 自动回退 `DOM.resolveNode`。
- `RoleVerificationPolicyTest`（+4）：单元素恰好 1、组内命中数须等于拾取数（多/少都拒绝）、不可判定放行、
  `expected<=0` 退化为单元素语义。
- `CodegenScorerTest`（+11）：候选链一次性预热且不重复；祖先候选必须整条替换 base、不得产出
  `body` 挂在 `div` 下的不可能层级（§15.8 回归）；属性组合优先于结构路径（§15.9）；
  单 class 不唯一时用双 class 组合；有稳定 id 祖先时优先 `#id <base>` 而非整条结构路径；
  **`data-i18n` 等语义属性优先于 class/结构路径**、`data-i18n + class` 组合、`data-i18n` 随机值退部分匹配、
  身份类 `aria-labelledby` 独立成定位器、**状态类 `aria-modal` 只进组合不作独立定位器**、
  `CodegenFacts` 正确解析 `attrs` 字段。
- `PageClassGeneratorTest`（+2）：组只能退化为唯一 CSS 时产物带 "matches only one element of the group" 提示；
  组用 role 定位器时无该提示。
- `RoleRevisionTest`（+4）：修订后 `sigKey/framePath/shadowHosts/nlsKey` 保持不变、空 CSS 退 `html`、`null` 容忍、不就地修改原条目。
- `CodegenScorerTest`（+5）：候选链一次性预热且不重复；祖先候选必须整条替换 base、不得产出
  `body` 挂在 `div` 下的不可能层级（§15.8 回归）；属性组合优先于结构路径（§15.9）；
  单 class 不唯一时用双 class 组合；有稳定 id 祖先时优先 `#id <base>` 而非整条结构路径。
- `CodegenCdpNodeExtractorFramePathTest`（+1）：`forFrame` 收到的是元素自身 frameId。
- `CodegenCdpNodeExtractorAxTest`（改写 3 例）：0 命中/多命中的角色候选在拾取阶段保留 `ROLE`、由 `countRoleMatches`
  给出回退依据；唯一命中保留 role。
- `NlsReverseLookupTest`（+5）：同一可见文案命中多个 key 时，显式 key（`data-i18n`）优先于文案反查的"先到先得"；
  显式 key 非法（缺失 / 其实是 value）时回落文案反查、不报错；NLS 值含 HTML（可见文本是元素名超集）或元素名是值超集时，
  用 `containsString` 兜底反查到 key；单字符名不参与兜底避免误命中。
- `AssertClassGeneratorTest`（+3）：有 NLS key 且可见文本精确相等 ⇒ 经 `NLSUtils` 取文本、`equalTo` 断言（语言无关）；
  NLS 值含 HTML（可见文本是元素名超集）⇒ `containsString` 断言 NLS 值包含元素文本；RoleElement 的 checkbox 仍 `isChecked()`。

### 15.4 验证

- `mvn -o -pl pw-codegen-v3 clean test "-Dcve.gate.skip=true" "-Dcheckstyle.skip=true" "-Dspotbugs.skip=true"` → **161 例 0 失败**（1 例既有跳过）。
- `mvn -o -pl pw-codegen-v3 verify -DskipTests "-Dcve.gate.skip=true" "-Dspotbugs.skip=true"` → Checkstyle 通过、BUILD SUCCESS。
- **仍待人工实测（需真实浏览器 + SIT 页面）**：面板二次修订的观感（琥珀色左边框 + `⟵ 复核后` 角标 + 悬停原因）
  与"组被污染时整组回退"的现场表现。行为无开关，同一批元素只需如实观察一次，不再需要"两种模式对跑 A/B"。

### 15.5 14.5 两项遗留的落地方式

- **facts 精简**（`SELF_CONTAINED_GET_INFO_FN` 与 `codegen-inject.js` 同步）：祖先链只回传
  `{"cssSelector": "<从该祖先到目标的完整路径>"}`，不再为每层祖先重复计算
  `accessibleName` / `absXPath` / `labelTextOf` / `shadowHosts`（每层 × 多轮 DOM 遍历）。
  祖先项只有 `cssSelector` 被消费；Java 侧 `CodegenFacts.factFrom` 对缺失字段保持默认值，仍兼容旧载荷。
- **`objectId` 直传**：`ElementRef` 增加 `objectId` 分量（`CdpFrameLocator#probe` 把 `Runtime.evaluate` 命中的
  `RemoteObject` 一并带出），`extract` 直接 `Runtime.callFunctionOn`，省掉一次 `DOM.resolveNode`。
  该 id 只当**加速提示**：随执行上下文销毁而失效，失效时记 DEBUG 并回退 `DOM.resolveNode`，语义与不传完全一致。

### 15.6 严格模式语义：单个 vs 一组（`RoleVerificationPolicy`）

Playwright 严格模式只有两种合法形态，复核必须分别对待（一律按"恰好 1 个"判定会误杀组）：

| 形态 | 运行期用法 | 期望命中数 |
| --- | --- | --- |
| 单个字段 `PageElement` | `locator().click()` / `fill()` | **恰好 1** |
| 一组 `PageElementList` | `locator().nth(i)` / `allLocators()` | **等于组内拾取数** |

- **组判定与字段规划同口径**：直接复用 `CodegenFieldPlanner.listGroupBaseSigs`（同 base-sig + 序号互异 ≥2），
  为此把 `baseSigOf` / `listGroupBaseSigs` 提升为 `public`。两处各写一套判定必然漂移。
- **"少命中 / 多命中"都算组被污染**：
  - 少 → 组内有元素不被该 role/name 命中（典型：不可见元素被 `getByRole` 的 `includeHidden=false` 排除，
    或它根本不是这个 role）⇒ 后续 `get(i)` 错位；
  - 多 → 页面还有用户没拾取的同类元素 ⇒ `get(i)` 取到的不是用户看到的那个。
- **整组一起回退**（绝不逐条）：字段规划只取组内首条注解，逐个回退会生成"首条 role + 其余 CSS"的自相矛盾产物。
  组内若已有非 role 策略条目（上一轮已回退），同样整组统一回退（`hasNonRoleMember`）。
- **产物显式提示组退化**：组只能退化为组内首条的唯一 CSS 时，字段上补
  `TODO(review): group could not use a role locator … matches only one element of the group`。
  严格模式不会因此报错，但 `PageElementList` 实际只剩 1 个元素，必须让评审看见。
- 判定收敛为纯函数 `RoleVerificationPolicy.keepRole(hits, expected)`：`hits < 0`（不可判定）放行，
  `expected <= 0` 退化为单元素语义。

### 15.7 面板「二次修订」观感

role → CSS 的修订发生在生成前（可能晚于拾取行渲染几十到几百毫秒），面板必须让人看出"这一行变过"，
且不能出现"面板说 role、代码 Tab 里是 CSS"：

- **修订先于行渲染**：`__onPickedRevised` 只记 `entries[pickId]`，`__onPicked` 渲染时优先取该修订版
  （修掉此前"修订被静默丢弃 → 行永远显示 role"的缺陷）。
- **已渲染**：就地更新同一行（不新增行），加**琥珀色左边框** + Locator 列 `⟵ 复核后` 角标，
  悬停显示原因（`hits=2, expected exactly 1 hit` / `group picked=3`），对应 `#elements-table tbody tr.revised`。
  修订原因由 `CodegenPanel.onPickedRevised(pickId, entry, reason)` 随载荷下发。
- **组污染时多条同时变**：整组回退会让组内每一行都出现该标记，与"这组不可信"的语义一致。

### 15.8 缺陷修复：CSS 嵌套降级产出「不可能层级」（2026-10-29）

**现象**（真实产物）：生成的 `@Element` 里有

```
html:nth-of-type(1) > body:nth-of-type(1) > … > div:nth-of-type(7) body:nth-of-type(1) > … > div:nth-of-type(2) [class*='title']
```

运行期定位不到元素（`body` 不可能是 `div` 的子元素）。

**根因**：页面侧 `cssSel` 是「上溯最多 6 层」的**窗口**，`ancestorChain` 回传的 `cssSel(祖先)` 同样是窗口；
`CodegenScorer#uniqueCss` 把它当 base 的**前缀**拼接（`ancestor + " " + base`）⇒ 两个窗口大段重叠，
且拼接后层级连续性是假的。

**修法**（两层一起改，缺一不可）：

1. 页面侧（`SELF_CONTAINED_GET_INFO_FN` 与 `codegen-inject.js` 同步）：新增 `segOf`（单层段：`#id` 或
   `tag:nth-of-type(k)`），`ancestorChain` 回传「**从该祖先到目标的完整路径**」——段链用 ` > ` 连接、
   由近及远逐级加长；深度超过上限时追加一条 `documentElement` 起的绝对路径兜底
   （逐级 `:nth-of-type` 精确 ⇒ 必然唯一）。
2. Java 侧 `uniqueCss`：祖先候选**整条替换** base（不再拼接）；`MAX_NEST_DEPTH` 4 → 8，
   最多尝试 `MAX_NEST_DEPTH + 1` 条（最后一条即绝对路径兜底）。

**为什么"整条替换"是唯一正确解**：base 与祖先窗口的起点各不相同（base 可能从 `#id` 或 6 层窗口顶开始，
祖先窗口从更上面开始），任何"左侧拼接"都要先算出 base 覆盖了哪几层祖先——那正是 `cssSel` 窗口语义无法
提供的信息。改为"由目标向根逐级重建完整路径"，从构造上消除重叠。

**回归护栏**：`CodegenScorerTest.ancestorCandidateReplacesBaseInsteadOfBeingPrefixed` —— 断言产物**等于**
祖先给出的完整路径，且不含 `body:nth-of-type(1) [class*='title']` 这类拼接痕迹；
`nestingStopsAtMaxDepth` 改为断言"最多尝试 `MAX_NEST_DEPTH + 1` 条、返回最后一条"。

### 15.9 定位器优先级：属性 > 组合 > id 前缀 > 结构路径

> 用户要求：**有属性就该用属性定位**，`html:nth-of-type(1) > body … > div:nth-of-type(7)` 这种长结构路径是
> **最差情况**，不能当常态。

原优先级只有「单属性/单 class → 结构路径」两档，单属性一旦命中多个就直接掉进结构路径，于是产物又长又脆。

**新增三档（都在结构路径之前）**：

| 档位 | 分数 | 形态 | 说明 |
| --- | --- | --- | --- |
| 单属性 / 单 class token | 508 / 515 / 525 | `[data-i18n="title_username_page"]`、`[aria-labelledby="title-id"]`、`[name="user"]`、`[class*="title"]` | 业务自写的 `data-*` 与身份类 `aria-*`（aria-label/labelledby/describedby/roledescription…）最稳；已唯一就止步，**不过度约束** |
| **属性组合（AND）** | 527 | `[data-i18n="x"][class*="title"]`、`input[name="user"][type="text"]` | 单条件不唯一时的首选；只组合稳定值，最多 6 组 |
| **id 祖先前缀** | — | `#newPasswordForm [class*="title"]` | 最近的稳定 id 祖先做前缀（后代组合子，最多 2 个） |
| 结构路径 | 530 | `… > div:nth-of-type(7) > …` | **最后手段**：由近及远逐级加长到唯一即止 |

**语义属性（整族 `data-*` + 整族 `aria-*`）的采集与消费**：

- 页面侧（两处同步）新增 `semanticAttrsOf`：回传元素上所有 `data-*`（跳过已单独处理的 `data-testid`）
  **+ 整族 `aria-*`**（`aria-label` / `aria-labelledby` / `aria-describedby` / `aria-modal` / `aria-hidden` /
  `aria-expanded` …），限长 120、最多 10 个，`value` 已 trim，随机值仍交由 Java 侧 `SelectorStability` 判定精确/部分匹配。
  `id` / `class` / `style` 等由其它字段单独处理，不在此收集。
- Java 侧 `CodegenFacts.Fact` 新增 `attrs`（属性名 → 值），`factFrom` 经 `mapOf` 解析，缺失时退回空映射（兼容旧载荷）。
- `CodegenScorer` 把语义属性按用途分两档：
  - **身份类**（可作独立定位器，分数 508，仅次于 `#id`）：`data-*`、`aria-label`、`aria-labelledby`、
    `aria-describedby`、`aria-roledescription`、`aria-placeholder`。随机值退到 `^=` / `$=` / `*=` 部分匹配。
  - **状态类**（只进组合，不生成独立候选）：`aria-hidden` / `aria-modal` / `aria-expanded` / `aria-selected` /
    `aria-checked` / `aria-pressed` / `aria-disabled` / `aria-invalid` …（见 `ARIA_STATE_ONLY`）。
    这类值通常是共享的布尔/枚举状态，单独从不唯一，但 `role="dialog"[aria-modal="true"]` 这类组合辨识度极高。
  - 两者都优先进入 `addCombos` 的 `exact` 列表（身份类 / 状态类各最多取 2 个，互不挤占）。

实现要点：

- 组合分数（527）刻意排在**单个**属性/class 之后：单属性已唯一时再叠条件等于多绑一个属性，页面改样式就断。
- `uniqueCss` 降级改为两阶段：① 稳定 id 祖先前缀 `#id <base>`；② 完整祖先路径逐级加长（≤ `MAX_NEST_DEPTH + 1` 条）。
- 页面侧 `ancestorChain` 每项补 `id` 字段（仅用于阶段 ①，不额外做 DOM 遍历）。
- 四者都不成立（无稳定属性、祖先无 id、路径不唯一）才落到长结构路径——这是真·最差情况，产物长是不可避免的。

### 15.10 NLS 最精确 key：优先采纳元素自带的 `data-i18n` + `<img>` 处理

**背景**：NLS 反查只靠可见文案 → key，当多个 key 的值相同（如 `login.title` 与 `signin.title` 都是 "Log on"），
只能"文件顺序先到先得"，选到的 key 未必是开发者给该元素的那个（见 §15.5 的 value→key 一对多冲突）。

**最精确 key**：元素身上的 `data-i18n` 属性**字面就是 NLS key**（开发显式绑定的），无歧义。

- `NlsReverseLookup` 新增 `lookup(name, explicitKey)`：仅当 `explicitKey` 确存在于当前语言包（`knownKeys` 集合，
  随语言切换重建）时才采纳；否则（属性缺失、或它其实是 value 而非 key）回落到 `lookup(name)` 的文案反查，保持既有行为。
- `CodegenCdpNodeExtractor` 拾取时先取 `self.attrs.get("data-i18n")` 作为 `explicitKey` 传入：

```453:455:pw-codegen-v3/src/main/java/com/hsbc/cmb/hk/dbb/automation/framework/web/page/codegen/cdp/CodegenCdpNodeExtractor.java
String dataI18n = (self.attrs != null) ? self.attrs.get("data-i18n") : null;
String nlsKey = nls.lookup(name, dataI18n);
```

- 这样「Log on 命中两个 key」的场景：若该元素带 `data-i18n="signin.title"`，就精确选 `signin.title`，不再是先到先得。

**`<img>` 是否支持**：**支持，且同时走 Role 与属性两条路**。

- **Role 路径**：`AriaRoles` 把 AX 的 `image` 别名成 `IMG`，且 `IMG` 不在 `NOT_LOCATABLE` ⇒ `isLocatable("img")` = true，
  故 `<img>` 会生成 `@RoleElement(role=AriaRole.IMG, name=<alt/aria-label>)`，name 再反查成 key。
- **属性路径**：`[data-i18n=…]`、`[aria-label=…]`，以及**非多语言**下的 `[alt=…]`（多语言时 `alt` 随语言变被禁用，改走 role+key）。
  带 `data-i18n` 的 img 同样享受 §15.10 的"最精确 key"逻辑。
- 注意：`<img>` 的 `src` 当前**不**作为定位依据（URL 通常含版本哈希/随机路径，不稳定），仍以 alt/aria-label/data-i18n 为准。

### 15.11 断言：文本类经 NLSUtils 做 `equalTo`/`containsString`；checkbox/radio 用 `isChecked`

**动机**：原断言对"有可读 name 的元素"直接用字面文案 `assertThat(page.f.getText(), is("Sign in"))`——
多语言下把文案写死，且 NLS 值常内嵌 HTML（`Hello <b>World</b>`），元素的可见名只是其中一段，字面比对毫无意义。

**规则**（`AssertClassGenerator`，与拾取共用同一批 `CodegenEntry`）：

- **RoleElement 的 checkbox / radio** ⇒ 断言勾选态 `assertThat(page.f.isChecked(), is(true))`（无"字符"语义，布尔足够）。
- **其余文本类元素（无论 role 还是 CSS 定位）**：
  - 有 NLS key（拾取阶段反查得到）⇒ 运行期经「绑定的 bundle」取文本断言，**语言无关**。
    `bind(files)` 只做一次、抽成生成类的 `private static final NLSUtils.NlsBundle NLS = NLSUtils.bind(...)` 字段，
    避免每个断言重复 bind；每条断言只调生成类的私有方法 `getKey(key)`（内部 `NLS.get(key)` 取当前语言原始值后
    再 `NLSUtils.visibleText` 剥 HTML/实体/归一化，返回处理过后的可见文本）。
    - 生成期比对「NLS 去标签值」与该元素可见名：相等 ⇒ `equalTo`（精确）；
    - 不等（值含 HTML，元素是其中一段，或名字是值的超集）⇒ `containsString`，且断言方向为「**NLS 值包含元素可见文本**」：
      `assertThat(visibleText(key), containsString(page.f.getText()))`。
  - 无 NLS key、但有 name ⇒ 退回字面文案 `assertThat(page.f.getText(), is("name"))`（保持既有行为，单语言场景仍可用）。
  - 无 name、无 key（容器/装饰节点）⇒ 退化 `isVisible()`。
- **取值型 role**（textbox/searchbox/spinbutton/combobox/listbox）取 `getValue()` 而非 `getText()`，其余取 `getText()`。

**与捕获期反查的呼应**：§15.10 的反查在 NLS 值含 HTML 时也会"精确查不到 key"，靠 `containsString` 兜底把元素名反查到 key；
抓到 key 后，断言阶段再用 `containsString` 把同一份 NLS 值拿来比对——两段逻辑一致，形成闭环：
**HTML 包裹的文本既能在抓取时定位到 key，也能在断言时正确比对**。

**产物契约**：仅当真的产出 NLS 断言时，生成的 Assert 类才引入
`import com.hsbc.cmb.hk.dbb.automation.framework.web.utils.NLSUtils;` 与
`import static org.hamcrest.Matchers.containsString;` / `equalTo;`，并生成 `NLS` 字段与私有 `visibleText(String key)` 方法
（其余仍只用 `is`）。

