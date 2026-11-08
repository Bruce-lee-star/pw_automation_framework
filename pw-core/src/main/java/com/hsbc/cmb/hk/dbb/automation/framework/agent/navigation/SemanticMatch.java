package com.hsbc.cmb.hk.dbb.automation.framework.agent.navigation;

import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.LocatorSpec;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.observation.ObservedElement;

import java.util.List;
import java.util.Objects;

/**
 * 业务描述 → 页面元素的解析结果（可解释、可审计）。
 *
 * <p>不同于传统「选择器」：这里保留了被选中元素的语义视图（role / 可访问名 / NLS key）、
 * 命中分数以及当时的候选清单，使「为什么点了这个元素」能写进日志与报告，而不是一条
 * 事后无法对账的 XPath。{@code candidates} 为当次观察的前若干名，与 {@link #score()} 同源。</p>
 *
 * @param element    命中的观察元素
 * @param locator    该元素的首选定位符（Role-First 梯队）
 * @param score      语义匹配分数，见 {@link SemanticElementResolver}
 * @param candidates 同次观察的候选清单（含命中项，按分数降序）
 */
public record SemanticMatch(ObservedElement element, LocatorSpec locator, int score,
                            List<Candidate> candidates) {

    public SemanticMatch {
        Objects.requireNonNull(element, "element");
        Objects.requireNonNull(locator, "locator");
        candidates = candidates == null ? List.of() : List.copyOf(candidates);
    }

    /** 简短诊断串，供结构化日志与失败明细使用（不含敏感值）。 */
    public String describe() {
        return "label=[" + label() + "] role=" + element().role()
                + " score=" + score + " locator=[" + locator().canonical()
                + "] candidates=" + candidates.size();
    }

    /** 元素的可读标签：可访问名 → 可见文本 → NLS key。 */
    public String label() {
        if (element().name() != null && !element().name().isBlank()) {
            return element().name();
        }
        if (element().text() != null && !element().text().isBlank()) {
            return element().text();
        }
        return element().nameKey() == null ? "" : element().nameKey();
    }

    /** 单个候选的可审计投影（不含 DOM 属性原值，避免日志泄露）。 */
    public record Candidate(String label, String role, int score, String locator) {
        public Candidate {
            label = label == null ? "" : label;
            role = role == null ? "" : role;
            locator = locator == null ? "" : locator;
        }
    }
}
