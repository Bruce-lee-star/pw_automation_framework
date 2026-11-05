package com.hsbc.cmb.hk.dbb.automation.framework.agent.channel;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 按 {@link Command.ActionType} 的<b>参数 schema</b>（必填键 / 允许键 / 是否需 target）
 * —— 通道协议侧的<b>唯一事实来源</b>（设计 §4.3「按 ActionType 区分的参数键/类型 schema」）。
 *
 * <p><b>为什么在 channel 包</b>：{@link StepPlan#parse(String)} 与意图侧的门禁
 * （{@code framework.agent.intent.IntentActionSchema}）都要用同一张表。表放这里，
 * 依赖方向是 intent → channel（单向），channel 不反向依赖 intent，避免包级循环。</p>
 *
 * <p><b>与意图侧的分工</b>：
 * <ul>
 *   <li>{@code ActionParamSchema}（协议层）：只回答「这个动作允许/需要哪些参数键、要不要 target」，
 *       覆盖<b>全部</b> {@link Command.ActionType}（含框架内部产生的
 *       {@link Command.ActionType#SWITCH_TO_POPUP}，否则解析内部计划会被误杀）；</li>
 *   <li>{@code IntentActionSchema}（意图层）：在其上再加「哪些动作<b>允许由外部输入产生</b>」
 *       的授权白名单（禁 {@code SWITCH_TO_POPUP}），并给出带 {@code Reason} 的语义化异常。</li>
 * </ul>
 * 两层共用本表，故「生成策略」与「校验策略」不会漂移。</p>
 *
 * <p>不可变、线程安全。</p>
 */
public final class ActionParamSchema {

    /** 单动作 schema：必填参数键 + 允许参数键 + 是否要求 target。 */
    public record Spec(Set<String> requiredParams, Set<String> allowedParams, boolean requiresTarget) {
        public Spec {
            requiredParams = Collections.unmodifiableSet(Set.copyOf(requiredParams));
            allowedParams = Collections.unmodifiableSet(Set.copyOf(allowedParams));
        }
    }

    private static final Map<Command.ActionType, Spec> SPECS = buildSpecs();

    private ActionParamSchema() {
    }

    private static Map<Command.ActionType, Spec> buildSpecs() {
        Map<Command.ActionType, Spec> m = new EnumMap<>(Command.ActionType.class);
        m.put(Command.ActionType.NAVIGATE, new Spec(Set.of("url"), Set.of("url"), false));
        m.put(Command.ActionType.FILL, new Spec(Set.of("value"), Set.of("value"), true));
        m.put(Command.ActionType.SELECT, new Spec(Set.of("option"), Set.of("option"), true));
        m.put(Command.ActionType.WAIT, new Spec(Set.of(), Set.of("ms"), true));
        for (Command.ActionType a : new Command.ActionType[]{
                Command.ActionType.CLICK, Command.ActionType.CHECK, Command.ActionType.UNCHECK,
                Command.ActionType.ASSERT_VISIBLE, Command.ActionType.ASSERT_TEXT}) {
            m.put(a, new Spec(Set.of(), Set.of(), true));
        }
        // 上下文切换：无 target、无参数（由 PlaywrightChannel 的 onPopup 内部产生，不对业务/外部输入开放）
        m.put(Command.ActionType.SWITCH_TO_POPUP, new Spec(Set.of(), Set.of(), false));
        return Collections.unmodifiableMap(m);
    }

    /**
     * 取动作的参数 schema（覆盖全部 {@link Command.ActionType}）。
     *
     * @throws NullPointerException action 为 {@code null}
     * @throws IllegalStateException 枚举新增但未登记 schema（防止静默放行）
     */
    public static Spec specOf(Command.ActionType action) {
        Objects.requireNonNull(action, "action");
        Spec spec = SPECS.get(action);
        if (spec == null) {
            throw new IllegalStateException("ActionType 缺少参数 schema（新增动作须在此登记）: " + action);
        }
        return spec;
    }
}
