package com.hsbc.cmb.hk.dbb.automation.framework.agent.intent;

import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.Command;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.StepPlan;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 计划等价性判定（设计 §9 P1 验收「相同规范化输入可比较出等价计划；不以自然语言输出作为唯一验收真值」）。
 *
 * <p>本类把此前只存在于测试中的私有 {@code assertIsomorphic} 提升为<b>生产级可复用入口</b>：
 * 比较的是<b>结构化 StepPlan 内容</b>（{@link StepPlan#planDigest()} 为主判据 + 逐分量差异明细），
 * <b>绝不</b>比较自然语言描述、planId、翻译器标识或输出文本顺序。</p>
 *
 * <p><b>为什么 digest 是主判据</b>：{@code planDigest()} 刻意排除 {@code planId}，
 * 覆盖 schemaVersion + 每步 action / targetRef / 排序参数 / 逐步与 plan 级 scope，
 * 因此「同内容不同 planId」判等价（录制骨架与模型建议天然会有不同 planId）。</p>
 *
 * <p><b>R6 约束</b>：{@link #forInput} 会比较双方是否都通过 {@link IntentPlanValidator}；
 * 任一方未过门禁即判不等价 —— 模型建议不能靠「内容碰巧一样」绕过 schema/策略/可执行性校验。</p>
 */
public final class PlanEquivalence {

    /** 等价报告（含逐分量差异，便于定位「模型幻觉」出在哪一步）。 */
    public record Report(boolean equivalent,
                         String leftDigest,
                         String rightDigest,
                         List<String> divergences,
                         boolean leftValid,
                         boolean rightValid,
                         List<String> leftViolations,
                         List<String> rightViolations) {
        public Report {
            divergences = List.copyOf(divergences);
            leftViolations = List.copyOf(leftViolations);
            rightViolations = List.copyOf(rightViolations);
        }
    }

    private PlanEquivalence() {
    }

    /** 便捷判据：仅当两计划内容指纹相同即等价（调用方需自行保证已过门禁）。 */
    public static boolean equivalent(StepPlan left, StepPlan right) {
        return left != null && right != null
                && left.planDigest().equals(right.planDigest());
    }

    /** 结构化比较两条计划，给出完整差异明细。 */
    public static Report compare(StepPlan left, StepPlan right) {
        List<String> divergences = new ArrayList<>();
        if (left == null || right == null) {
            return new Report(false, "", "", List.of("one or both plans are null"), false, false,
                    List.of(), List.of());
        }
        IntentPlanValidator.Report lv = IntentPlanValidator.inspect(left);
        IntentPlanValidator.Report rv = IntentPlanValidator.inspect(right);
        if (!lv.accepted()) {
            divergences.add("left plan rejected by IntentPlanValidator: " + String.join("; ", lv.violations()));
        }
        if (!rv.accepted()) {
            divergences.add("right plan rejected by IntentPlanValidator: " + String.join("; ", rv.violations()));
        }
        String ld = safeDigest(left);
        String rd = safeDigest(right);
        if (!ld.equals(rd)) {
            divergences.add("planDigest differs: " + ld + " vs " + rd);
        }
        if (left.schemaVersion() != right.schemaVersion()) {
            divergences.add("schemaVersion differs");
        }
        if (left.steps().size() != right.steps().size()) {
            divergences.add("step count differs: " + left.steps().size() + " vs " + right.steps().size());
        }
        int n = Math.min(left.steps().size(), right.steps().size());
        for (int i = 0; i < n; i++) {
            compareStep(left.steps().get(i), right.steps().get(i), i, divergences);
        }
        if (!left.scope().canonical().equals(right.scope().canonical())) {
            divergences.add("plan scope differs");
        }
        boolean eq = divergences.isEmpty() && lv.accepted() && rv.accepted() && ld.equals(rd);
        return new Report(eq, ld, rd, divergences, lv.accepted(), rv.accepted(),
                lv.violations(), rv.violations());
    }

    /**
     * 对<b>同一份规范化输入</b>分别经两条翻译路径（如确定性规则引擎 vs 模型建议）产出计划并对账。
     * 这是 §9 P1 等价性验收的直接入口：不以任一方的自然语言输出为真值。
     */
    public static Report forInput(RecordedSkeleton skeleton,
                                  IntentTranslator baseline,
                                  IntentTranslator suggested) {
        if (skeleton == null || baseline == null || suggested == null) {
            return new Report(false, "", "", List.of("skeleton/translators must not be null"),
                    false, false, List.of(), List.of());
        }
        RecordedSkeleton normalized = skeleton.normalized();
        return compare(baseline.translate(normalized), suggested.translate(normalized));
    }

    private static void compareStep(Command a, Command b, int index, List<String> divergences) {
        String at = "step[" + index + "]";
        if (a.action() != b.action()) {
            divergences.add(at + " action differs: " + a.action() + " vs " + b.action());
        }
        if (!a.targetRef().equals(b.targetRef())) {
            divergences.add(at + " target differs");
        }
        Map<String, String> ap = new TreeMap<>(a.params());
        Map<String, String> bp = new TreeMap<>(b.params());
        if (!ap.keySet().equals(bp.keySet())) {
            divergences.add(at + " param keys differ: " + ap.keySet() + " vs " + bp.keySet());
        } else {
            for (String k : ap.keySet()) {
                if (!java.util.Objects.equals(ap.get(k), bp.get(k))) {
                    divergences.add(at + " param '" + k + "' differs");
                }
            }
        }
    }

    private static String safeDigest(StepPlan plan) {
        try {
            return plan.planDigest();
        } catch (RuntimeException e) {
            return "<uncomputable>";
        }
    }
}
