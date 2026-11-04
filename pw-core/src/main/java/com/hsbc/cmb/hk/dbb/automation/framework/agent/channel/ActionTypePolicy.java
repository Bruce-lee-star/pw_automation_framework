package com.hsbc.cmb.hk.dbb.automation.framework.agent.channel;

import java.time.Duration;
import java.util.Objects;

/**
 * 逐动作策略元数据（设计 §4.1 / §9 P0 第 3 项，单一事实来源，v3.10）。
 *
 * <p>P0 第 3 项要求「逐动作定义副作用、超时、失败和重试策略，不将所有操作视为幂等」。
 * 本类把每个 {@link Command.ActionType} 的治理与执行策略固化成不可变 {@link ActionPolicy}，
 * 取代原先散落于 {@link com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.scope.ParamScopePolicy}
 * 的硬编码 switch，并供 {@link StepDriver} 在编排层应用有限重试。</p>
 *
 * <p>策略语义：
 * <ul>
 *   <li>{@code sideEffecting}：是否为写入 / 状态改变型原语（与参数范围治理预检挂钩，需声明取值域）；
 *       CLICK / WAIT / ASSERT_* 为非副作用。</li>
 *   <li>{@code idempotent}：重复执行是否安全；<b>只有幂等动作才允许有限重试</b>，
 *       写操作（CLICK / FILL / SELECT）一律 {@code false}——避免 trace 重放导致二次提交。</li>
 *   <li>{@code defaultTimeout}：该动作的推荐执行时限（元数据）；<b>运行期真正施加需通道契约配合</b>
 *       （Playwright 调用不可中断），本刀仅在策略层建模，接线留待通道实现升级。</li>
 *   <li>{@code maxRetries}：步骤失败后额外重试次数（仅当 {@code idempotent}）；0 = 不重试。</li>
 * </ul>
 * 本类不引入失败类型区分（瞬时 vs 业务），统一以<b>幂等性</b>为重试唯一闸门（后续可扩展）。</p>
 */
public final class ActionTypePolicy {

    private ActionTypePolicy() {
    }

    private static final int SEC = 1_000;

    /** 逐动作固化策略（不可变）。 */
    public record ActionPolicy(
            boolean sideEffecting,
            boolean idempotent,
            Duration defaultTimeout,
            int maxRetries) {
    }

    public static ActionPolicy of(Command.ActionType action) {
        Objects.requireNonNull(action, "action");
        switch (action) {
            case NAVIGATE:       return new ActionPolicy(true,  true,  Duration.ofMillis(30 * SEC), 1);
            case CLICK:          return new ActionPolicy(false, false, Duration.ofMillis(10 * SEC), 0);
            case FILL:           return new ActionPolicy(true,  false, Duration.ofMillis(10 * SEC), 0);
            case SELECT:         return new ActionPolicy(true,  false, Duration.ofMillis(10 * SEC), 0);
            case CHECK:          return new ActionPolicy(true,  true,  Duration.ofMillis(10 * SEC), 1);
            case UNCHECK:        return new ActionPolicy(true,  true,  Duration.ofMillis(10 * SEC), 1);
            case WAIT:           return new ActionPolicy(false, true,  Duration.ofMillis(15 * SEC), 1);
            case ASSERT_VISIBLE: return new ActionPolicy(false, true,  Duration.ofMillis(10 * SEC), 0);
            case ASSERT_TEXT:    return new ActionPolicy(false, true,  Duration.ofMillis(10 * SEC), 0);
            default:             throw new AssertionError("unmapped action: " + action);
        }
    }
}
