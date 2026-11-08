package com.hsbc.cmb.hk.dbb.automation.framework.agent.channel;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.nls.NlsCatalog;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.nls.NlsText;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 浏览器无关的"定位符"描述（设计 §4.4 + §4.5 Role-First 定位梯队）。
 *
 * <p>Playwright 最稳健、官方首推的定位方式是 <b>Role 定位</b>（{@code page.getByRole(role, {name})}），
 * 它基于无障碍语义而非 DOM 结构 / CSS，能扛住重构；Playwright MCP 本身也是无障碍树驱动的。
 * 因此本框架把 <b>ROLE 作为第一梯队</b>，其余作为逐级回退：</p>
 *
 * <ol>
 *   <li>{@code ROLE}     —— {@code getByRole(role, {name})}（最强，语义稳定）</li>
 *   <li>{@code TEST_ID}  —— {@code getByTestId(...)}（显式测试契约）</li>
 *   <li>{@code ID}       —— {@code #id}（稳定 DOM id 兜底）</li>
 *   <li>{@code TEXT}     —— {@code getByText(...)}（文案兜底，易变）</li>
 *   <li>{@code CSS}      —— 任意 CSS（最脆，仅兜底）</li>
 * </ol>
 *
 * <p>canonical 字符串作为快照索引键与命令目标，可在 pw-core（无浏览器依赖）与实现层之间无损传递。</p>
 *
 * <p><b>多语言（§4.7）</b>：ROLE 梯队的可访问名有两种表达——字面文案 {@code name=} 与 NLS key
 * {@code nameKey=}。多语言环境下<b>一律用 key</b>（{@link #roleOfKey(String, String, int)}），
 * 执行期经 {@link #localize(NlsCatalog)} 解析为当前语言文案；这与 codegen 的
 * {@code role=button[name=Nls.t("login.submit")]} 完全同义，是"一份资产跑所有语言"的前提。</p>
 */
public final class LocatorSpec {

    /** 定位策略梯队（值越小越优先）。 */
    public enum Strategy { ROLE, TEST_ID, ID, TEXT, CSS }

    private final Strategy strategy;
    private final Map<String, String> attrs;   // role/name/nth/testId/id/text/css

    @JsonCreator
    private LocatorSpec(@JsonProperty("strategy") Strategy strategy,
                        @JsonProperty("attrs") Map<String, String> attrs) {
        this.strategy = strategy;
        this.attrs = attrs;
    }

    public Strategy strategy() { return strategy; }
    public String role() { return attrs.get("role"); }
    public String name() { return attrs.get("name"); }

    /**
     * NLS key（多语言环境下的<b>首选</b>角色名来源，设计 §4.7）。
     *
     * <p>与 codegen 的 {@code role=button[name=Nls.t("login.submit")]} 同义：资产里只存 key，
     * 运行期按<b>当前语言</b>解析成实际文案再做 {@code getByRole(role,{name})}。
     * 这样同一份资产可在任意语言下执行，不会因切语言全部失效。</p>
     */
    public String nameKey() { return attrs.get("nameKey"); }
    public String testId() { return attrs.get("testId"); }
    public String id() { return attrs.get("id"); }
    public String text() { return attrs.get("text"); }
    public String css() { return attrs.get("css"); }
    public int nth() {
        String n = attrs.get("nth");
        return n == null ? 0 : Integer.parseInt(n);
    }

    /** 规范化字符串，例如 {@code role=checkbox;name=I agree to the terms} 或 {@code testid=purpose}。 */
    public String canonical() {
        StringBuilder sb = new StringBuilder();
        switch (strategy) {
            case ROLE:     sb.append("role=").append(role()); break;
            case TEST_ID:  sb.append("testid=").append(testId()); break;
            case ID:       sb.append("id=").append(id()); break;
            case TEXT:     sb.append("text=").append(text()); break;
            case CSS:      sb.append("css=").append(css()); break;
            default:       throw new IllegalStateException("unknown strategy");
        }
        if (name() != null) {
            sb.append(";name=").append(name());
        }
        if (nameKey() != null) {
            sb.append(";nameKey=").append(nameKey());
        }
        if (nth() != 0) {
            sb.append(";nth=").append(nth());
        }
        return sb.toString();
    }

    // —— 梯队工厂 ——
    public static LocatorSpec role(String role, String name) { return role(role, name, 0); }
    public static LocatorSpec role(String role, String name, int nth) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("role", role);
        if (name != null) {
            m.put("name", name);
        }
        if (nth != 0) {
            m.put("nth", Integer.toString(nth));
        }
        return new LocatorSpec(Strategy.ROLE, m);
    }

    /**
     * 角色 + <b>NLS key</b> 定位（多语言首选，设计 §4.7）。
     *
     * <p>资产侧只保留 key，执行期由 {@link #localize(com.hsbc.cmb.hk.dbb.automation.framework.agent.nls.NlsCatalog)}
     * 解析为当前语言文案——跨语言稳定，语义等价于 codegen 的 {@code Nls.t(key)}。</p>
     */
    public static LocatorSpec roleOfKey(String role, String nameKey) { return roleOfKey(role, nameKey, 0); }

    public static LocatorSpec roleOfKey(String role, String nameKey, int nth) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("role", role);
        if (nameKey != null) {
            m.put("nameKey", nameKey);
        }
        if (nth != 0) {
            m.put("nth", Integer.toString(nth));
        }
        return new LocatorSpec(Strategy.ROLE, m);
    }
    public static LocatorSpec testId(String id) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("testId", id);
        return new LocatorSpec(Strategy.TEST_ID, m);
    }
    public static LocatorSpec id(String id) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("id", id);
        return new LocatorSpec(Strategy.ID, m);
    }
    public static LocatorSpec text(String text) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("text", text);
        return new LocatorSpec(Strategy.TEXT, m);
    }
    public static LocatorSpec css(String css) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("css", css);
        return new LocatorSpec(Strategy.CSS, m);
    }

    /** 从 canonical 字符串还原（快照索引 → 运行期定位）。 */
    public static LocatorSpec parse(String canonical) {
        Strategy s = null;
        Map<String, String> m = new LinkedHashMap<>();
        for (String part : canonical.split(";")) {
            int idx = part.indexOf('=');
            if (idx < 0) {
                continue;
            }
            String k = part.substring(0, idx);
            String v = part.substring(idx + 1);
            switch (k) {
                case "role":   s = Strategy.ROLE;    m.put("role", v); break;
                case "testid": s = Strategy.TEST_ID; m.put("testId", v); break;
                case "id":     s = Strategy.ID;      m.put("id", v); break;
                case "text":   s = Strategy.TEXT;    m.put("text", v); break;
                case "css":    s = Strategy.CSS;     m.put("css", v); break;
                default:       m.put(k, v);
            }
        }
        if (s == null) {
            throw new IllegalArgumentException("Cannot parse LocatorSpec: " + canonical);
        }
        return new LocatorSpec(s, m);
    }

    /** 是否为 NLS key 定位（多语言环境下的首选形态）。 */
    public boolean usesNlsKey() {
        return nameKey() != null;
    }

    /**
     * 解析为「当前语言可执行」的定位符：把 {@code nameKey} 换成该语言下的可见文本。
     *
     * <p>无 catalog、无 key，或该 key 的值是<b>模板</b>（{@code {{var}}}）时原样返回——
     * 模板无法静态求值（真实值由页面注入），须由实现层用正则匹配（见 {@code PlaywrightChannel}）。</p>
     */
    public LocatorSpec localize(NlsCatalog catalog) {
        if (catalog == null || nameKey() == null) {
            return this;
        }
        String raw = catalog.value(nameKey());
        if (NlsText.isTemplate(raw)) {
            return this;
        }
        return role(role(), catalog.visibleText(nameKey()), nth());
    }
}
