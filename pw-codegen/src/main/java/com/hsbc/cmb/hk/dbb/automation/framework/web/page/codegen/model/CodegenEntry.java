package com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 一次拾取的完整结果（同时也是推送给面板的 JSON 载荷）。
 *
 * <p>字段命名即面板 JS 侧读取的键；{@link #toMap()} 供 {@code page.evaluate} 直接序列化。
 */
public final class CodegenEntry {

    public final String sigKey;
    public final String role;
    public final String name;
    public final String strategy;
    public final int score;
    public final String selector;
    public final String cssSelector;
    public final String nlsKey;
    /**
     * 在「同定位器」中的序号（0 起）。用于 List 识别：多个拾取共享同一 base-sig（剔除 {@code #index} 后）
     * 且序号互异时，判定为「一组元素」→ 生成 {@code PageElementList}。
     * 由注入脚本按"同级兄弟元素位置"计算；单元素定位恒为 {@code -1}。
     */
    public final int index;
    /**
     * 跨 frame 穿透路径（外层 → 内层，即 outermost-first）：每个元素是父 frame 文档内那个
     * {@code <iframe>} 元素的 CSS 选择器。元素位于主 frame 时为空列表。
     *
     * <p>这是支持<b>跨源 iframe</b> 录制/拾取可运行落地的关键：跨源 iframe 内的元素 CSS 只相对于
     * 其所属 frame 文档，必须串成 {@code frame=SELa :: SELb :: <内层 css>}，由框架
     * {@code LocatorFactory.bySelector} 解析为 {@code page.frameLocator(SELa).frameLocator(SELb).locator(<内层 css>)}。
     */
    public final java.util.List<String> framePath;
    /**
     * 元素所在的 open shadow 宿主链（outermost-first；主文档元素为空列表）。每层选择器相对其自身文档 /
     * 外层 shadow root 计算，与 {@link #cssSelector} 口径一致。
     *
     * <p>用于 CDP 侧按选择器反查元素（{@code document.querySelector} 不穿透 shadow 边界）；
     * 生成代码无需特殊处理——Playwright 的 CSS 引擎自动穿透 open shadow root。
     */
    public final java.util.List<String> shadowHosts;

    public CodegenEntry(String sigKey, String role, String name, String strategy,
                        int score, String selector, String cssSelector, String nlsKey, int index) {
        this(sigKey, role, name, strategy, score, selector, cssSelector, nlsKey, index,
                java.util.List.of(), java.util.List.of());
    }

    public CodegenEntry(String sigKey, String role, String name, String strategy,
                        int score, String selector, String cssSelector, String nlsKey, int index,
                        java.util.List<String> framePath) {
        this(sigKey, role, name, strategy, score, selector, cssSelector, nlsKey, index,
                framePath, java.util.List.of());
    }

    public CodegenEntry(String sigKey, String role, String name, String strategy,
                        int score, String selector, String cssSelector, String nlsKey, int index,
                        java.util.List<String> framePath, java.util.List<String> shadowHosts) {
        this.sigKey = sigKey;
        this.role = role;
        this.name = name;
        this.strategy = strategy;
        this.score = score;
        this.selector = selector;
        this.cssSelector = cssSelector;
        this.nlsKey = nlsKey;
        this.index = index;
        this.framePath = framePath == null ? java.util.List.of() : framePath;
        this.shadowHosts = shadowHosts == null ? java.util.List.of() : shadowHosts;
    }

    /** 转为面板 evaluate 可直接序列化的 Map（字段名即面板 JS 读取的键）。 */
    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("sigKey", sigKey);
        m.put("role", role);
        m.put("name", name == null ? "" : name);
        m.put("strategy", strategy);
        m.put("score", score);
        m.put("selector", selector);
        m.put("cssSelector", cssSelector);
        m.put("nlsKey", nlsKey == null ? "" : nlsKey);
        m.put("index", index);
        m.put("framePath", new ArrayList<>(framePath));
        m.put("shadowHosts", new ArrayList<>(shadowHosts));
        return m;
    }
}
