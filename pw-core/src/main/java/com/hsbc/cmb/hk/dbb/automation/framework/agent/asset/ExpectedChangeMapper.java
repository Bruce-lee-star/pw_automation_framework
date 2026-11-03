package com.hsbc.cmb.hk.dbb.automation.framework.agent.asset;

import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.SnapshotExpectation;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.nls.NlsCatalog;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.nls.NlsText;

/**
 * 联动「变化态」期望 → 快照「绝对态」字段断言 的共享映射（单一事实来源）。
 *
 * <p>被 {@link LinkageGraphVerifier}（变化态驱动）与 pw-agent-analysis 的
 * {@code StepPlanProducer}（把联动图谱转成「可执行 + 可验证」StepPlan）共用，
 * 避免两处维护字段比较语义。无法转化为有效约束（如 TEXT_CHANGED 既无 key 也无字面文案）
 * 时返回 {@code null}，由调用方决定跳过。</p>
 */
public final class ExpectedChangeMapper {

    private ExpectedChangeMapper() {
    }

    /** ExpectedChange（变化语义）→ SnapshotExpectation（绝对态语义）；无可用约束返回 null。 */
    public static SnapshotExpectation toExpectation(LinkageGraph.ExpectedChange exp, String ref, NlsCatalog nls) {
        SnapshotExpectation.Builder b = SnapshotExpectation.forRef(ref);
        switch (exp.kind()) {
            case APPEAR:    b.visible(true); break;
            case DISAPPEAR: b.visible(false); break;
            case ENABLE:    b.enabled(true); break;
            case DISABLE:   b.enabled(false); break;
            case REQUIRED:  b.enabled(true); break;   // 简化：真实实现需校验必填属性
            case TEXT_CHANGED:
                if (exp.expectedTextKey() != null && nls != null) {
                    String raw = nls.value(exp.expectedTextKey());
                    if (NlsText.isTemplate(raw)) {
                        b.textMatches(NlsText.pattern(raw).pattern());
                    } else {
                        b.text(nls.visibleText(exp.expectedTextKey()));
                    }
                } else if (exp.expectedText() != null) {
                    b.text(exp.expectedText());
                } else {
                    return null;
                }
                break;
            default:        return null;
        }
        return b.build();
    }
}
