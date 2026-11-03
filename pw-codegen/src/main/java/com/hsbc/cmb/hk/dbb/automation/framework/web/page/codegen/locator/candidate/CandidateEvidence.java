package com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen.locator.candidate;

/**
 * 候选定位器的<b>观测证据</b>（设计 §4.4：命中数、可见/可操作、目标语义、业务断言）。
 *
 * <p>证据与候选分离，是因为同一份候选在不同页面版本/不同语言下会得到不同证据；
 * 验证结论必须绑定"哪一次观测"，而不是写在候选本身里。
 *
 * <p><b>fail-closed 默认值</b>：{@code matches = -1} 表示"未观测"，{@code assertionPassed = null}
 * 表示"未提供业务断言"。两者都会被 {@link CandidateValidator} 判为不通过——缺证据不等于通过。
 */
public final class CandidateEvidence {

    private final int matches;
    private final boolean visible;
    private final boolean enabled;
    private final boolean textMatched;
    private final String businessAssertion;
    private final Boolean assertionPassed;
    private final String detail;

    private CandidateEvidence(Builder b) {
        this.matches = b.matches;
        this.visible = b.visible;
        this.enabled = b.enabled;
        this.textMatched = b.textMatched;
        this.businessAssertion = b.businessAssertion;
        this.assertionPassed = b.assertionPassed;
        this.detail = b.detail;
    }

    /** 命中元素数；{@code -1} 表示未观测。唯一性要求恰好 1。 */
    public int matches() {
        return matches;
    }

    public boolean visible() {
        return visible;
    }

    /** 可操作性（未 disabled、未被遮罩等）。 */
    public boolean enabled() {
        return enabled;
    }

    /** 可访问名/文案是否与目标语义一致。 */
    public boolean textMatched() {
        return textMatched;
    }

    /** 业务断言描述（如 "提交后出现成功提示"）；空表示未提供。 */
    public String businessAssertion() {
        return businessAssertion;
    }

    /** 业务断言结果；{@code null} 表示未执行。 */
    public Boolean assertionPassed() {
        return assertionPassed;
    }

    /** 证据细节（trace 引用、快照 id、命中元素摘录等）。 */
    public String detail() {
        return detail;
    }

    public static Builder builder() {
        return new Builder();
    }

    /** 证据构造器：全部字段显式设置，避免"忘了给命中数"被静默默认成 0/通过。 */
    public static final class Builder {
        private int matches = -1;
        private boolean visible;
        private boolean enabled;
        private boolean textMatched;
        private String businessAssertion;
        private Boolean assertionPassed;
        private String detail;

        private Builder() {}

        public Builder matches(int matches) {
            this.matches = matches;
            return this;
        }

        public Builder visible(boolean visible) {
            this.visible = visible;
            return this;
        }

        public Builder enabled(boolean enabled) {
            this.enabled = enabled;
            return this;
        }

        public Builder textMatched(boolean textMatched) {
            this.textMatched = textMatched;
            return this;
        }

        public Builder businessAssertion(String description) {
            this.businessAssertion = description;
            return this;
        }

        public Builder assertionPassed(boolean passed) {
            this.assertionPassed = passed;
            return this;
        }

        public Builder detail(String detail) {
            this.detail = detail;
            return this;
        }

        public CandidateEvidence build() {
            return new CandidateEvidence(this);
        }
    }
}
