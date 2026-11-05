package com.hsbc.cmb.hk.dbb.automation.framework.agent.intent;

import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.StepPlan;

/**
 * 意图翻译 SPI（设计 §4.3 / §9 P1 / §10.4「pw-core：IntentTranslator SPI」）。
 *
 * <p><b>契约</b>：把<b>结构化的最小化上下文</b>（{@link RecordedSkeleton}）翻译为
 * {@link StepPlan} 的 <b>draft 建议</b>。产出只是不可信建议，必须经
 * {@link IntentPlanValidator#validate(StepPlan)} 的 schema / 策略 / 可执行性校验后才可用；
 * 执行由独立的 {@code StepDriver} 承担（R6：AI 只产建议，执行器独立校验权限与动作）。</p>
 *
 * <p><b>AI 无直接浏览器调用能力</b>：本接口签名不含 {@code BrowserCommandChannel}、
 * {@code Page} 或任何浏览器句柄，只有纯数据入参 / 出参；实现不得在翻译期触碰浏览器。</p>
 *
 * <p><b>组装方式（治理决策）</b>：采用「纯 interface + 构造器手动注入」，与
 * {@code PageObservationProvider} / {@code AssetRepository} 一致，<b>刻意不做
 * ServiceLoader 自动发现</b> —— 自动发现会让一个未经审查的 LLM 实现在 classpath 上
 * 被静默启用，绕过 §4.6/§9 要求的供应商、数据驻留、留存条款与凭据管理审查。
 * 默认确定性实现为 {@link RuleBasedIntentTranslator}（零 LLM、零网络）。</p>
 *
 * <p><b>确定性要求</b>：同一份 {@link RecordedSkeleton#normalized()} 必须产出
 * 相同 {@link StepPlan#planDigest()}，以便与模型建议做等价性比较
 * （{@link PlanEquivalence}，不以自然语言输出作为唯一验收真值）。</p>
 */
public interface IntentTranslator {

    /**
     * 翻译录制骨架为 StepPlan draft 建议。
     *
     * @param skeleton 结构化最小化上下文（不得为 {@code null}）
     * @return draft 计划（调用方仍须过 {@link IntentPlanValidator}）
     * @throws IntentTranslationException 输入不合规 / 未批准 origin / 越权动作 / 参数 schema 违反
     */
    StepPlan translate(RecordedSkeleton skeleton) throws IntentTranslationException;

    /** 实现标识（用于审计与等价性报告归因，不含敏感值）。 */
    default String id() {
        return getClass().getSimpleName();
    }
}
