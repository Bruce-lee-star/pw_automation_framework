# CDP Element Capture 改造方案

## 目标

把 RoleElementPicker 的元素捕获层从"页面内 JS 注入 + click 监听"切换为"CDP Overlay inspect mode"，
保留注入式面板 UI 与全部 Java 业务逻辑（去重 / step 累积 / 代码生成 / nls 反查）。

---

## 一、新组件总览

```
┌─────────────────────────────────────────────────────────────┐
│  不变层（保留现有代码，零改动）                               │
│  ├─ RolePickerPanelController      （面板主循环）           │
│  ├─ RolePickerCommandEngine        （start/stop/done 命令） │
│  ├─ RolePickerCodeAssembler        （Page/Step/Assert 生成）│
│  ├─ RoleElementPageGenerator       （代码模板渲染）         │
│  ├─ RolePickerNlsCache            （nls 反查表）           │
│  ├─ RolePickerSessionState         （CTX_* 状态 Map）      │
│  └─ javaPickBySig / sealedSteps    （Java 权威态）         │
├─────────────────────────────────────────────────────────────┤
│  新增层（本方案）                                             │
│  ├─ RolePickerCdpSession           （CDP session 生命周期） │
│  ├─ RolePickerCdpNodeExtractor     （CDP node → RoleEntry） │
│  └─ RolePickerCaptureDispatcher    （CDP 事件 → 现有链路）  │
├─────────────────────────────────────────────────────────────┤
│  修改层（渐进替换，保留 fallback）                            │
│  ├─ RoleElementPicker.start()     增加 CDP 模式分支        │
│  ├─ RoleElementPicker.stop()      增加 CDP 清理            │
│  ├─ RolePickerScriptInjector      CDP 模式下不注入拾取脚本  │
│  └─ RolePickerPanelController      启动时选择捕获模式       │
└─────────────────────────────────────────────────────────────┘
```

---

## 二、新增类 1：RolePickerCdpSession

### 职责
- 每个 Page 持有一个 CDPSession（`context.newCDPSession(page)`）
- 管理 `Overlay.setInspectMode` 的开/关
- 监听 `Overlay.inspectNodeRequested` 事件
- 导航后自动重设 inspect mode
- 全部方法线程安全（CDP session 回调跑在 Playwright 事件线程）

### 关键字段

```java
final class RolePickerCdpSession {
    private final Page page;
    private final CDPSession cdp;
    private final AtomicBoolean inspectActive = new AtomicBoolean(false);
    private final RolePickerCaptureDispatcher dispatcher;
    private static final long CDP_CMD_TIMEOUT_MS = 5000;
}
```

### 方法清单

#### `static RolePickerCdpSession attach(Page page, RolePickerCaptureDispatcher d)`
- `context.newCDPSession(page)` 打开 session
- `DOM.enable` / `CSS.enable` / `Overlay.enable` / `Accessibility.enable`
- 注册 `Overlay.inspectNodeRequested` 监听器
- 注册 `Page.frameNavigated` 监听器 → 导航后如果 inspectActive 则重设
- 返回 session 实例

#### `void startInspect()`
- 设 `inspectActive = true`
- 发 `Overlay.setInspectMode`：
```json
{
  "mode": "searchForNode",
  "highlightConfig": {
    "showInfo": true,
    "showRulers": false,
    "showExtensionLines": false,
    "displayAsMaterial": false,
    "contentColor": "rgba(66, 153, 225, 0.35)",
    "borderColor": "rgba(66, 153, 225, 0.85)",
    "shapeColor": "rgba(66, 153, 225, 0.15)"
  }
}
```

#### `void stopInspect()`
- 设 `inspectActive = false`
- 发 `Overlay.setInspectMode`：`{"mode": "none"}`

#### `void detach()`
- `stopInspect()`
- 移除所有事件监听
- `cdp.close()`（Playwright Java CDPSession 有 close 方法）

#### 内部：`onInspectNodeRequested(JsonNode params)`
```
1. backendNodeId = params.get("backendNodeId").asInt()
2. 先 stopInspect()（临时关闭，避免连点多个节点时每次都触发）
3. 调 dispatcher.onCdpNodePicked(page, backendNodeId)
4. 如果 inspectActive 仍为 true（用户没按停止），重新 startInspect()
   —— 实现"点一个 → 高亮消失一下 → 再点下一个"的 Inspector 式节奏
```

---

## 三、新增类 2：RolePickerCdpNodeExtractor

### 职责
把 CDP `backendNodeId` 转成 `RoleEntry`。

### 方法：`static RoleEntry extract(CDPSession cdp, Page page, int backendNodeId)`

#### 步骤 1：解析节点引用
```java
JsonNode resolved = cdp.send("DOM.resolveNode",
    Map.of("backendNodeId", backendNodeId));
int nodeId = resolved.get("node").get("nodeId").asInt();
String objectId = resolved.get("object").get("objectId").asText();
```

#### 步骤 2：取 HTML 属性
```java
JsonNode attrs = cdp.send("DOM.getAttributes",
    Map.of("nodeId", nodeId)).get("attributes");
// attrs 是扁平数组: ["id","loginBtn","name","username",...]
Map<String,String> attrMap = flatArrayToMap(attrs);
```

#### 步骤 3：取 a11y role / name
```java
// 用 getPartialAXTree 只取该节点子树，避免整树遍历
JsonNode axTree = cdp.send("Accessibility.getPartialAXTree",
    Map.of("backendNodeId", backendNodeId));
// 在 axTree.nodes 里找 backendDOMNodeId == 我们的 backendNodeId 的节点
String role = findRole(axTree, backendNodeId);
String name = findName(axTree, backendNodeId);   // 可能为 null
```

#### 步骤 4：计算定位策略（与现有 `LOCATOR_IDENTITY_STRATEGIES` 对齐）

按优先级从 attrMap + role + name 推断：

| 优先级 | 策略 | 判断条件 |
|---|---|---|
| 1 | `i18n` | name 命中 nls 反查表 → `i18n:<key>` |
| 2 | `id` | attrMap 含 `id` 且非空 |
| 3 | `testid` | attrMap 含 `data-testid` / `data-test` / `data-qa` |
| 4 | `css` | 用 `DOM.querySelector` 或自构造 CSS path |
| 5 | `text` | role ∈ {button, link, tab, menuitem} 且 name 非空 |
| 6 | `placeholder` | attrMap 含 `placeholder` |
| 7 | `label` | 关联 `<label for=...>`（用 `DOM.getNodeForLocation` + 反向查） |
| 8 | `title` | attrMap 含 `title` |
| 9 | `altText` | attrMap 含 `alt` |
| fallback | `css` | 构造 CSS 路径 |

#### 步骤 5：frame path 计算
```java
// 通过 CDP 拿 frameId
JsonNode box = cdp.send("DOM.getBoxModel", Map.of("backendNodeId", backendNodeId));
String frameId = box.get("frameId").asText();

// 用 Playwright 的 Frame 列表反查 framePath
List<String> framePath = RolePickerFramePath.resolveByFrameId(page, frameId);
```

#### 步骤 6：list / index / count
```java
// 如果 role 是 listbox/option/list，用 CSS path 在 frame 里 count 匹配数
String cssPath = computeCssPath(cdp, nodeId);
int count = countMatches(page, framePath, cssPath);
int index = computeIndexWithinMatches(cdp, nodeId, cssPath);
```

#### 步骤 7：组装 RoleEntry
```java
RoleEntry e = new RoleEntry();
e.setRole(role);
e.setName(name != null ? name : "");
e.setStrategy(resolvedStrategy);
e.setSigKey(buildSigKey(role, name, attrMap));
e.setLocatorValue(locatorValue);  // i18n:user_name / #loginBtn / text=Submit
e.setFramePath(framePath);
e.setList(count > 1);
e.setCount(count);
e.setIndex(index);
e.setPageClass(currentPageClass);  // 从 RolePickerPageTracker 取
return e;
```

---

## 四、新增类 3：RolePickerCaptureDispatcher

### 职责
桥接 CDP 事件回调 → 现有 Java 权威态链路。

### 方法

#### `void onCdpNodePicked(Page page, int backendNodeId)`
```
1. RoleEntry e = RolePickerCdpNodeExtractor.extract(cdp, page, backendNodeId)
2. 如果 e == null 或 e.getSigKey() 空 → log.warn 丢弃
3. 同步到 RolePickerContext.javaPickBySig：
   - 按 sigKey 去重
   - 若已存在 → 追加 pickNos（新一轮序号）
   - 若新元素 → 加入 map
4. 同步到 ctx.roundPicks（本轮累积）
5. 触发面板刷新（fillCode soft 模式）
6. 不做任何 page.evaluate —— 所有数据来自 CDP
```

### 关键设计：面板自身点击的过滤

CDP inspect mode 下，用户点面板按钮也会触发 `inspectNodeRequested`。处理方式：

```java
void onInspectNodeRequested(JsonNode params) {
    int backendNodeId = params.get("backendNodeId").asInt();

    // 检查该节点是否在面板 DOM 子树内
    if (isInsidePanel(backendNodeId)) {
        // 面板点击：忽略，不拾取，保持 inspect mode
        return;
    }
    // ... 正常拾取流程
}

boolean isInsidePanel(int backendNodeId) {
    // 用 DOM.getOuterHTML 或 DOM.describeNode 拿到 nodeName/attributes
    // 检查是否有 id="__role-panel" 的祖先
    // 实现：DOM.requestNode → 沿 parentNodeId 链上溯到根，途中遇到 __role-panel 即 true
}
```

---

## 五、修改现有类

### 5.1 RoleElementPicker.java

#### 新增配置开关
```java
private static final boolean CDP_CAPTURE_ENABLED =
    Boolean.getBoolean("dbb.codegen.cdp-capture.enabled");  // 默认 false，灰度开启
```

#### 修改 `start(Page page, String nlsReverseJson)`

```java
public static void start(Page page, String nlsReverseJson) {
    if (isCiRun()) return;
    registerContextInitScripts(page.context(), nlsReverseJson);

    if (CDP_CAPTURE_ENABLED) {
        // === CDP 模式 ===
        RolePickerCdpSession session = RolePickerCdpSession.attach(
            page, new RolePickerCaptureDispatcher(/* ctx refs */));
        RolePickerSessionState.CTX_CDP_SESSIONS.put(page, session);
        session.startInspect();
    } else {
        // === 旧 JS 注入模式（保留作 fallback） ===
        pickerEval(page, pickStartScript, startArgs);
        RolePickerScriptInjector.registerFrameInjection(page, nlsReverseJson);
    }
    // ... 诊断日志不变
}
```

#### 修改 `stop(Page page)`

```java
public static void stop(Page page) {
    if (isCiRun()) return;
    RolePickerCdpSession session = RolePickerSessionState.CTX_CDP_SESSIONS.remove(page);
    if (session != null) {
        session.detach();
    }
    pickerEval(page, STOP_SESSION_CLEANUP_JS + STOP_SCRIPT);
}
```

#### 修改 `cleanupContext(BrowserContext ctx)`

```java
public static void cleanupContext(BrowserContext ctx) {
    // 遍历 CTX_CDP_SESSIONS，detach 所有属于该 ctx 的 session
    RolePickerSessionState.CTX_CDP_SESSIONS.entrySet().removeIf(e -> {
        if (e.getKey().context() == ctx) {
            e.getValue().detach();
            return true;
        }
        return false;
    });
    // ... 原有清理逻辑不变
}
```

### 5.2 RolePickerSessionState.java

新增：
```java
static final Map<Page, RolePickerCdpSession> CTX_CDP_SESSIONS =
    Collections.synchronizedMap(new WeakHashMap<>());
```

### 5.3 RolePickerScriptInjector.java

不改文件，但 `registerFrameInjection` 和 `frameInjectOnce` 在 CDP 模式下**不被调用**（由 start() 的分支决定）。

---

## 六、CDP 命令完整序列

### 开始拾取
```
1. context.newCDPSession(page)           → cdp
2. cdp.send("DOM.enable")
3. cdp.send("CSS.enable")
4. cdp.send("Overlay.enable")
5. cdp.send("Accessibility.enable")
6. cdp.on("Overlay.inspectNodeRequested", handler)
7. cdp.send("Overlay.setInspectMode", {mode:"searchForNode", highlightConfig:{...}})
```

### 用户点击一个页面元素
```
[CDP event] Overlay.inspectNodeRequested {backendNodeId: 1234}
  │
  ├─ 1. cdp.send("Overlay.setInspectMode", {mode:"none"})    // 暂停
  ├─ 2. 检查 backendNodeId 是否在面板内 → 是则重新 startInspect() 返回
  ├─ 3. cdp.send("DOM.resolveNode", {backendNodeId:1234})    → nodeId, objectId
  ├─ 4. cdp.send("DOM.getAttributes", {nodeId:X})            → attrMap
  ├─ 5. cdp.send("Accessibility.getPartialAXTree", {backendNodeId:1234}) → role/name
  ├─ 6. cdp.send("DOM.getBoxModel", {backendNodeId:1234})    → frameId
  ├─ 7. extract → RoleEntry
  ├─ 8. 写入 javaPickBySig + roundPicks
  ├─ 9. fillCode(soft) 回填面板
  └─ 10. cdp.send("Overlay.setInspectMode", {mode:"searchForNode"})  // 恢复
```

### 停止拾取
```
1. cdp.send("Overlay.setInspectMode", {mode:"none"})
2. cdp.detach()
3. （现有 cmdStop 逻辑不变：读快照 → 生成代码 → 回填）
```

---

## 七、线程模型

| 线程 | 职责 | 注意事项 |
|---|---|---|
| 测试主线程 | `openPanel()` 阻塞等待面板命令 | 不直接碰 CDP |
| Playwright 事件线程 | `Overlay.inspectNodeRequested` 回调 | 回调里做 extract + 写 javaPickBySig（已 synchronized） |
| CDP 命令线程 | `cdp.send()` 同步阻塞等待响应 | Playwright 内部排队，天然串行 |

**锁策略**：
- `javaPickBySig` 已有 `synchronized` 块，CDP 回调里复用同一把锁
- CDP session 本身不需要额外锁（Playwright 保证 send 串行）
- `inspectActive` 用 `AtomicBoolean`（事件线程 ↔ 主线程可见性）

---

## 八、降级策略

### CDP 不可用时自动回退 JS 注入

```java
public static void start(Page page, String nlsReverseJson) {
    if (isCiRun()) return;
    registerContextInitScripts(page.context(), nlsReverseJson);

    if (CDP_CAPTURE_ENABLED) {
        try {
            RolePickerCdpSession session = RolePickerCdpSession.attach(page, dispatcher);
            session.startInspect();
            RolePickerSessionState.CTX_CDP_SESSIONS.put(page, session);
            log.info("[picker] CDP capture mode active");
            return;
        } catch (Exception e) {
            log.warn("[picker] CDP attach failed, falling back to JS injection: {}", e.getMessage());
        }
    }
    // === fallback: 原有 JS 注入路径 ===
    pickerEval(page, pickStartScript, startArgs);
    RolePickerScriptInjector.registerFrameInjection(page, nlsReverseJson);
}
```

降级触发条件：
- `context.newCDPSession()` 抛异常
- `Overlay.setInspectMode` 命令超时
- Chromium 版本不支持 `searchForNode` 模式（极旧版本）

---

## 九、配置项

| 配置 key | 默认值 | 说明 |
|---|---|---|
| `dbb.codegen.cdp-capture.enabled` | `false` | CDP 捕获开关（灰度） |
| `dbb.codegen.cdp.cmd.timeout.ms` | `5000` | 单条 CDP 命令超时 |
| `dbb.codegen.cdp.highlight.color` | 蓝色 | hover 高亮色 |

---

## 十、不改的部分（零回归保证）

以下完全不动：
- `RolePickerCommandEngine.cmdStart/cmdStop/cmdRefreshCode` 的业务逻辑
- `RolePickerCodeAssembler` 的 Page/Step/Assert 代码生成
- `RoleElementPageGenerator` 的模板渲染
- `RolePickerNlsCache` 的 nls 反查
- `RolePickerPanelController` 的主循环 / 弹窗跟随 / 多页管理
- post 阶段的报告发布
- CI 门控 `isCiRun()`

**CDP 只替换"用户点击页面 → 得到 RoleEntry"这一段**。RoleEntry 进了 `javaPickBySig` 之后的所有链路完全不变。

---

## 十一、迁移步骤

### Phase 1：CDP 捕获层（1 周）
1. 新建 `RolePickerCdpSession` + `RolePickerCdpNodeExtractor` + `RolePickerCaptureDispatcher`
2. 在 `start()` 加 CDP 分支（开关默认 false）
3. 写单元测试：mock CDP 事件 → 验证 RoleEntry 字段正确性
4. 手动验证：headed 浏览器开 `-Ddbb.codegen.cdp-capture.enabled=true`，点几个元素

### Phase 2：跨 frame / 跨源（3 天）
1. `DOM.getBoxModel` 拿 frameId → `RolePickerFramePath.resolveByFrameId`
2. 验证跨源 iframe 内点击能正确拾取（当前 JS 模式做不到）
3. 导航后 `Page.frameNavigated` 回调重设 inspect mode

### Phase 3：面板点击过滤 + 联调（2 天）
1. `isInsidePanel` 祖先链检查
2. 端到端：openPanel → 点开始 → 点页面元素 → 停止 → 代码生成
3. 对比 CDP 模式 vs JS 模式生成的代码一致性

### Phase 4：默认开启（1 天）
1. 开关默认改为 true
2. JS 注入路径保留作 fallback，不删
3. 全量回归 pw-codegen 模块测试

---

## 十二、风险与缓解

| 风险 | 缓解 |
|---|---|
| CDP `Overlay.setInspectMode` 与 Playwright 自身 overlay 冲突 | 用独立 CDPSession（`context.newCDPSession`），不影响 Playwright 主连接 |
| 导航后 inspect mode 丢失 | 监听 `Page.frameNavigated`，导航完成后自动重发 `setInspectMode` |
| `Accessibility.getPartialAXTree` 返回格式与 Chromium 版本有关 | 加版本探测 + fallback 到 `DOM.describeNode` 推断 role |
| 面板点击误触发拾取 | `isInsidePanel` 祖先链过滤 |
| CDP 回调在事件线程阻塞 | extract 操作控制在 500ms 内（单节点查询），不做重活 |
| 弹窗（popup）需要新 CDP session | `RolePickerPanelController.registerPopupFollow` 里对新 page 也 attach 一个 session |

---

## 十三、文件清单

### 新建
```
pw-codegen/src/main/java/.../scan/cdp/
  ├─ RolePickerCdpSession.java        （CDP session 生命周期）
  ├─ RolePickerCdpNodeExtractor.java  （CDP node → RoleEntry）
  ├─ RolePickerCaptureDispatcher.java  （事件桥接 javaPickBySig）
  └─ RolePickerCdpConfig.java         （配置读取）
```

### 修改
```
pw-codegen/src/main/java/.../scan/RoleElementPicker.java      start/stop 加 CDP 分支
pw-codegen/src/main/java/.../scan/RolePickerSessionState.java  加 CTX_CDP_SESSIONS Map
```

### 不改
```
RolePickerCommandEngine / RolePickerCodeAssembler /
RoleElementPageGenerator / RolePickerNlsCache /
RolePickerPanelController / RolePickerScriptInjector（仅在 CDP 模式下不被调用）
```
