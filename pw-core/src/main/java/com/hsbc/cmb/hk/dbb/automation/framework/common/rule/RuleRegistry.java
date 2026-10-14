package com.hsbc.cmb.hk.dbb.automation.framework.common.rule;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 通用规则注册表。与 {@code common.cleanstate.CleanStateRegistry}(场景级状态清理)互补且隔离:
 * 本类面向"匹配即动作"的规则/拦截器语义,不参与场景生命周期清理,因此不影响现有清理功能。
 *
 * <p>设计要点:
 * <ul>
 *   <li><b>广播匹配</b>:{@link #matchAll(Object)} 跑遍所有已配置规则,逐条命中即执行,不短路,
 *       确保"所有配置规则均被满足"。</li>
 *   <li><b>延迟自关</b>:命中的 one-shot 规则在"全轮评估结束后"统一注销,而非命中瞬间移除,避免同轮逻辑错乱。</li>
 *   <li><b>幂等取消</b>:{@link #register(Rule)} 返回 {@link AutoCloseable} 句柄作为取消令牌;
 *       多次 {@code close()} 幂等无副作用,可配合 try-with-resources 安全使用。</li>
 *   <li><b>线程安全</b>:内部使用 {@link ConcurrentHashMap},注册/注销/匹配可并发。</li>
 * </ul>
 *
 * @param <C> 规则评估上下文类型
 */
public class RuleRegistry<C> {

    private final Map<String, Rule<C>> rules = new ConcurrentHashMap<>();

    /**
     * 注册规则。重复 {@link Rule#name()} 后者覆盖前者。
     *
     * @return 幂等取消令牌;调用 {@code close()} 即注销该规则(若已自关则为 no-op)
     */
    public AutoCloseable register(Rule<C> rule) {
        rules.put(rule.name(), rule);
        return () -> rules.remove(rule.name()); // 幂等:已移除时返回 null,无副作用
    }

    /** 注销指定规则。 */
    public void unregister(String name) {
        rules.remove(name);
    }

    /** 是否已注册。 */
    public boolean isRegistered(String name) {
        return rules.containsKey(name);
    }

    /**
     * 广播匹配:跑遍所有已配置规则,逐条命中即执行动作,不短路。
     * 全轮结束后统一注销命中的 one-shot 规则。
     *
     * @return 本轮回合命中的规则列表(按注册遍历顺序)
     */
    public List<Rule<C>> matchAll(C context) {
        List<Rule<C>> fired = new ArrayList<>();
        for (Rule<C> r : new ArrayList<>(rules.values())) { // 快照遍历,允许 apply 内重入注册
            if (r.matches(context)) {
                r.apply(context);
                fired.add(r);
            }
        }
        fired.forEach(r -> {
            if (r.oneShot()) {
                rules.remove(r.name());
            }
        });
        return fired;
    }

    /**
     * 短路匹配:命中首条即执行并停止。保留以兼容需"首个匹配"语义的调用方,不影响现有功能。
     */
    public Optional<Rule<C>> matchFirst(C context) {
        for (Rule<C> r : new ArrayList<>(rules.values())) {
            if (r.matches(context)) {
                r.apply(context);
                if (r.oneShot()) {
                rules.remove(r.name());
            }
                return Optional.of(r);
            }
        }
        return Optional.empty();
    }

    /** 当前注册规则数。 */
    public int size() {
        return rules.size();
    }

    /** 清空全部规则。 */
    public void clear() {
        rules.clear();
    }
}
