package com.hsbc.cmb.hk.dbb.automation.framework.common.reporting;

/**
 * 单个选择器的定位健康度（跨模块安全的数据载体）。
 *
 * <p>由采集模块（pw-web-ui 的 {@code LocatorHealthRecorder}）产出，经
 * {@link LocatorHealthReportSink} 交给报告模块渲染，避免报告模块反向依赖 web 模块。
 *
 * <p><b>不可变</b>，且全部为基本类型/字符串，便于 Freemarker 直接按属性访问（{@code e.uses}）。
 */
public class LocatorHealthEntry {

    private final String selector;
    private final String kind;
    private final long uses;
    private final long violations;
    private final int maxMatchCount;

    public LocatorHealthEntry(String selector, String kind, long uses, long violations, int maxMatchCount) {
        this.selector = selector == null ? "" : selector;
        this.kind = kind == null ? "" : kind;
        this.uses = uses;
        this.violations = violations;
        this.maxMatchCount = maxMatchCount;
    }

    /** 选择器原文（可能是 CSS / XPath / role 描述）。 */
    public String getSelector() {
        return selector;
    }

    /** 选择器类别：{@code role} / {@code xpath} / {@code css}。 */
    public String getKind() {
        return kind;
    }

    /** 本次运行的元素操作次数。 */
    public long getUses() {
        return uses;
    }

    /** 本次运行中 strict 违例（一次定位命中多个元素）的次数。 */
    public long getViolations() {
        return violations;
    }

    /** 违例时观察到的最大命中元素数；无违例为 0。 */
    public int getMaxMatchCount() {
        return maxMatchCount;
    }
}
