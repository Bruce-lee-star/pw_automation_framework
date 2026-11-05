package com.hsbc.cmb.hk.dbb.automation.framework.agent.intent;

import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.StepPlan;

import java.util.Objects;

/**
 * 「模型建议」回读入口：把 IDE 助手（Copilot / 同类）返回的文本当成<b>不可信输入</b>处理
 * （设计 §4.3 / §10.4 R6：AI 只产不可信建议，执行器独立校验）。
 *
 * <p><b>为什么需要它</b>：提示词（{@link SkeletonPromptBundle}）要求模型只回 JSON，
 * 但实际输出常被 Markdown 代码围栏、前后说明文字包裹，并可能臆造动作/参数键/定位符。
 * 本类负责三步收口，任何一步失败都<b>不静默容忍</b>：</p>
 * <ol>
 *   <li><b>提取并解析</b>：{@link #parseSuggestion} 从混杂文本中截取第一个 {@code {} 到最后一个
 *       {@code }}，交 {@link StepPlan#parse} 做结构 fail-closed（未知字段/动作、
 *       按动作的参数键 schema、NAVIGATE 无 target 等）；</li>
 *   <li><b>统一门禁</b>：{@link IntentPlanValidator#inspect} schema/策略/可执行性；</li>
 *   <li><b>结构化对账</b>：{@link PlanEquivalence#compare} 与基线计划比对，
 *       不以自然语言描述或输出文本顺序为真值。</li>
 * </ol>
 *
 * <p><b>框架不调用 LLM</b>：本类只处理「用户粘贴回来的文本」，无网络、无凭据、无供应商配置。</p>
 *
 * <p>无状态、线程安全。</p>
 */
public final class SuggestionIntake {

    /**
     * 回读结论。
     *
     * @param planReport 建议自身的门禁报告（schema/策略/可执行性）
     * @param comparison 与基线计划的结构化对账
     */
    public record Result(IntentPlanValidator.Report planReport, PlanEquivalence.Report comparison) {

        public Result {
            Objects.requireNonNull(planReport, "planReport");
            Objects.requireNonNull(comparison, "comparison");
        }

        /** 建议是否通过统一门禁（可执行）。 */
        public boolean accepted() {
            return planReport.accepted();
        }

        /** 建议是否与基线结构等价（{@code planDigest} 一致且双方均过门禁）。 */
        public boolean equivalent() {
            return comparison.equivalent();
        }

        /** 可直接采信 = 既合法又与基线等价。 */
        public boolean ok() {
            return accepted() && equivalent();
        }

        /** 单行/多行诊断（只含违规与差异描述，不含参数值）。 */
        public String summary() {
            StringBuilder sb = new StringBuilder("accepted=").append(accepted())
                    .append(", equivalent=").append(equivalent());
            for (String v : planReport.violations()) {
                sb.append("\n  violation: ").append(v);
            }
            for (String d : comparison.divergences()) {
                sb.append("\n  divergence: ").append(d);
            }
            return sb.toString();
        }
    }

    private SuggestionIntake() {
    }

    /**
     * 从模型输出文本中提取 StepPlan。
     *
     * <p>容忍代码围栏与前后说明文字（截取首个 {@code {} 至最后一个 {@code }}），
     * 但<b>不容忍</b>内容本身不合规。</p>
     *
     * @throws IntentTranslationException {@link IntentTranslationException.Reason#INVALID_INPUT}
     *                                    —— 不含 JSON 对象，或未通过 {@link StepPlan#parse} 的 fail-closed 校验
     */
    public static StepPlan parseSuggestion(String modelOutput) {
        if (modelOutput == null || modelOutput.isBlank()) {
            throw reject("suggestion text is blank");
        }
        int start = modelOutput.indexOf('{');
        int end = modelOutput.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw reject("suggestion contains no JSON object");
        }
        try {
            return StepPlan.parse(modelOutput.substring(start, end + 1));
        } catch (RuntimeException e) {
            // 不回显原文（可能含业务数据），只带解析器的结构诊断
            throw reject("suggestion is not a valid StepPlan: " + e.getMessage());
        }
    }

    /** 以给定基线计划对账一条建议（两者都不会被执行，仅校验与比较）。 */
    public static Result check(StepPlan baseline, StepPlan suggested) {
        Objects.requireNonNull(baseline, "baseline");
        Objects.requireNonNull(suggested, "suggested");
        return new Result(IntentPlanValidator.inspect(suggested),
                PlanEquivalence.compare(baseline, suggested));
    }

    /**
     * 一体化入口：用确定性基线翻译器产出基线，再对账模型建议文本。
     *
     * @param skeleton        录制骨架（内部先 {@link RecordedSkeleton#normalized()}）
     * @param baselineTranslator 确定性基线（通常是 {@code RuleBasedIntentTranslator}）
     * @param suggestionOutput   模型返回的原文（可含围栏/说明文字）
     */
    public static Result check(RecordedSkeleton skeleton,
                               IntentTranslator baselineTranslator,
                               String suggestionOutput) {
        Objects.requireNonNull(skeleton, "skeleton");
        Objects.requireNonNull(baselineTranslator, "baselineTranslator");
        StepPlan baseline = baselineTranslator.translate(skeleton.normalized());
        return check(baseline, parseSuggestion(suggestionOutput));
    }

    private static IntentTranslationException reject(String message) {
        return new IntentTranslationException(IntentTranslationException.Reason.INVALID_INPUT, message);
    }
}
