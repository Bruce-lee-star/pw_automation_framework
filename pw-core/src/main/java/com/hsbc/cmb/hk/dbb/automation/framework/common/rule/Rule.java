package com.hsbc.cmb.hk.dbb.automation.framework.common.rule;

/**
 * 可注册规则,由 {@link RuleRegistry} 统一管理生命周期。
 *
 * <p>生命周期约定:
 * <ol>
 *   <li>通过 {@link RuleRegistry#register(Rule)} 注册,返回幂等 {@link AutoCloseable} 句柄(取消令牌)。</li>
 *   <li>当 {@link #matches(Object)} 命中时执行 {@link #apply(Object)}。</li>
 *   <li>命中且 {@link #oneShot()} 为 true 时,在"全轮评估结束后"由注册表统一自关(注销),
 *       避免"命中瞬间移除"破坏同轮其他规则的评估。</li>
 *   <li>{@link #oneShot()} 为 false 的规则可匹配多次,需调用方显式关闭句柄或 {@link RuleRegistry#unregister(String)}。</li>
 * </ol>
 *
 * @param <C> 规则评估上下文类型
 */
public interface Rule<C> {

    /** 规则唯一标识,用于注册/注销/去重。 */
    String name();

    /** 判断当前上下文是否命中本规则。实现须无副作用。 */
    boolean matches(C context);

    /** 命中后的动作。 */
    void apply(C context);

    /** 是否一次性:命中且全轮评估结束后自动注销。默认 true。 */
    default boolean oneShot() {
        return true;
    }

    /** 匹配策略。默认 {@link MatchStrategy#ALL}(广播全轮)。 */
    default MatchStrategy strategy() {
        return MatchStrategy.ALL;
    }
}
