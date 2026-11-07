package com.hsbc.cmb.hk.dbb.automation.framework.agent.impact;

import java.util.List;
import java.util.Objects;

/**
 * 一条 CSS / XPath 选择器的分析结果：稳定锚点 + 随机值告警 + 稳定化建议。
 *
 * @param raw      原始选择器（已归一化空白）
 * @param kind     CSS / XPATH
 * @param anchors  稳定锚点（随机值已被剔除）
 * @param warnings 随机值告警（每条含取值与建议片段）
 * @param advice   汇总的稳定化建议（空串表示无需建议）
 */
public record SelectorFacts(String raw, Kind kind, List<SelectorAnchor> anchors,
                            List<String> warnings, String advice) {

    /** 选择器种类。 */
    public enum Kind {
        CSS, XPATH
    }

    /** 稳定性判定。 */
    public enum Stability {
        STABLE("稳定"),
        VOLATILE("含随机值"),
        ANCHORLESS("无稳定锚点");

        private final String label;

        Stability(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    public SelectorFacts {
        Objects.requireNonNull(raw, "raw");
        Objects.requireNonNull(kind, "kind");
        anchors = anchors == null ? List.of() : List.copyOf(anchors);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
        advice = advice == null ? "" : advice;
    }

    /** 是否有可用于「同一元素」判定的稳定锚点。 */
    public boolean hasAnchors() {
        return !anchors.isEmpty();
    }

    /** 稳定性：无随机值 = 稳定；有随机值但有稳定锚点 = 含随机值；全是随机值 = 无稳定锚点。 */
    public Stability stability() {
        if (warnings.isEmpty()) {
            return Stability.STABLE;
        }
        return anchors.isEmpty() ? Stability.ANCHORLESS : Stability.VOLATILE;
    }

    /** 报告用一行摘要（锚点 + 建议）。 */
    public String describe() {
        StringBuilder sb = new StringBuilder(128);
        sb.append(kind).append(' ').append(stability().label());
        if (!anchors.isEmpty()) {
            sb.append(" anchors=").append(anchors);
        }
        if (!advice.isEmpty()) {
            sb.append(" advice: ").append(advice);
        }
        return sb.toString();
    }
}
