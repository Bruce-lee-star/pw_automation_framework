package com.hsbc.cmb.hk.dbb.automation.framework.agent.intent;

import java.util.Map;
import java.util.Objects;

/**
 * 录制骨架的单步（结构化动作，设计 §4.3「录制骨架 → StepPlan」的最小化上下文）。
 *
 * <p><b>数据最小化（R1/R7）</b>：只携带动作名、目标 canonical 定位串与少量参数，
 * 不含原始页面 HTML、截图、Cookie、凭据或自然语言描述。{@code params} 的值仍可能含业务值，
 * 调用方须在生成骨架时按 §4.6 脱敏（不得把 secret/PII 原文写入）。</p>
 *
 * @param action  动作名（字符串形式，翻译时按 {@link com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.Command.ActionType} 校验）
 * @param target  目标元素 canonical 定位串（{@code LocatorSpec} 规范串）；NAVIGATE 等无目标动作传 {@code null}
 * @param params  动作参数（如 NAVIGATE 的 {@code url}、FILL 的 {@code value}）；不可为 {@code null}
 */
public record RecordedStep(String action, String target, Map<String, String> params) {

    public RecordedStep {
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(params, "params");
        params = Map.copyOf(params);
    }

    /** 无参数步骤的便捷构造。 */
    public static RecordedStep of(String action, String target) {
        return new RecordedStep(action, target, Map.of());
    }

    /** 单参数步骤的便捷构造。 */
    public static RecordedStep of(String action, String target, String paramKey, String paramValue) {
        return new RecordedStep(action, target, Map.of(paramKey, paramValue));
    }
}
