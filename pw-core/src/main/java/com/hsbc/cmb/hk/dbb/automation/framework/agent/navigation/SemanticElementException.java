package com.hsbc.cmb.hk.dbb.automation.framework.agent.navigation;

import java.util.List;
import java.util.Objects;

/**
 * 业务描述无法被<b>唯一、可信</b>地映射到页面元素时抛出的语义化异常。
 *
 * <p>设计上刻意 fail-closed：零定位输入的最大风险不是「找不到」，而是「悄悄点错了」。
 * 因此无候选、分数过低、或多候选难分伯仲时都直接失败，并把候选清单附在异常里，
 * 供业务侧补录文案 / 提供更精确描述，而不是让框架猜一个继续跑。</p>
 */
public final class SemanticElementException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public enum Reason { NOT_FOUND, AMBIGUOUS }

    private final Reason reason;
    private final String businessText;
    private final List<SemanticMatch.Candidate> candidates;

    private SemanticElementException(Reason reason, String businessText, String message,
                                     List<SemanticMatch.Candidate> candidates) {
        super(message);
        this.reason = reason;
        this.businessText = businessText;
        this.candidates = List.copyOf(Objects.requireNonNull(candidates, "candidates"));
    }

    public static SemanticElementException notFound(String businessText,
                                                     List<SemanticMatch.Candidate> candidates) {
        return new SemanticElementException(Reason.NOT_FOUND, businessText,
                "No visible element matches [" + businessText + "]; candidates=" + format(candidates),
                candidates);
    }

    public static SemanticElementException ambiguous(String businessText,
                                                      SemanticMatch top, SemanticMatch second,
                                                      List<SemanticMatch.Candidate> candidates) {
        return new SemanticElementException(Reason.AMBIGUOUS, businessText,
                "Ambiguous business description [" + businessText + "]: "
                        + top.describe() + " vs " + second.describe()
                        + "; refine the description or provide a more specific menu label."
                        + " candidates=" + format(candidates),
                candidates);
    }

    public Reason reason() { return reason; }

    public String businessText() { return businessText; }

    public List<SemanticMatch.Candidate> candidates() { return candidates; }

    private static String format(List<SemanticMatch.Candidate> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return "[]";
        }
        StringBuilder sb = new StringBuilder("[");
        int limit = Math.min(candidates.size(), 5);
        for (int i = 0; i < limit; i++) {
            if (i > 0) {
                sb.append(", ");
            }
            SemanticMatch.Candidate c = candidates.get(i);
            sb.append(c.label()).append('(').append(c.role()).append(',').append(c.score()).append(')');
        }
        if (candidates.size() > limit) {
            sb.append(",...");
        }
        return sb.append(']').toString();
    }
}
