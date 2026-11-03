package com.hsbc.cmb.hk.dbb.automation.framework.agent.channel;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 类型化命令（设计 §4.1 原语集）。
 *
 * <p>仅包含受控原语，<b>不含脚本执行原语</b>（R2）。{@code target} 为 Role-First 定位符
 * （{@link LocatorSpec}），对应快照中的元素稳定引用；{@link #targetRef()} 返回其 canonical 串。</p>
 *
 * <p>{@code expectations} 内联该命令执行后的<b>绝对态期望</b>（闭环引擎的「执行后联动断言」），
 * 使「一步 = 动作 + 其期望后态」绑定在计划结构内，消除「计划 + 独立期望列表按索引对齐」的双轨耦合。
 * 无期望的命令（如纯导航）为空列表。</p>
 */
public final class Command {

    /** 受控原语类型（对齐 MCP 工具集，但收敛为企业级契约）。 */
    public enum ActionType {
        NAVIGATE, CLICK, FILL, SELECT, CHECK, UNCHECK, WAIT, ASSERT_VISIBLE, ASSERT_TEXT
    }

    private final ActionType action;
    private final LocatorSpec target;          // 角色优先定位符（NULL 仅用于 NAVIGATE）
    private final Map<String, String> params;
    private final List<SnapshotExpectation> expectations;

    /** 完整构造（含内联期望）。 */
    public Command(ActionType action, LocatorSpec target, Map<String, String> params,
                  List<SnapshotExpectation> expectations) {
        this.action = action;
        this.target = target;
        this.params = params == null ? Collections.emptyMap() : params;
        this.expectations = expectations == null ? List.of() : List.copyOf(expectations);
    }

    /** 兼容构造：未声明执行后期望。 */
    public Command(ActionType action, LocatorSpec target, Map<String, String> params) {
        this(action, target, params, List.of());
    }

    public ActionType action() { return action; }
    public LocatorSpec target() { return target; }
    /** canonical 定位串（快照索引键 / 便捷入口）。 */
    public String targetRef() { return target == null ? "" : target.canonical(); }
    public Map<String, String> params() { return params; }
    /** 该命令执行后的绝对态期望（可能为空）。 */
    public List<SnapshotExpectation> expectations() { return expectations; }

    /** 返回携带指定内联期望的新命令（不可变对象，原命令不变）。 */
    public Command withExpectations(List<SnapshotExpectation> expectations) {
        return new Command(action, target, params, expectations);
    }

    // —— 便捷工厂（覆盖联动验证常见触发，默认无内联期望） ——
    public static Command navigate(String url) {
        return new Command(ActionType.NAVIGATE, null, Collections.singletonMap("url", url));
    }
    public static Command click(LocatorSpec ref) {
        return new Command(ActionType.CLICK, ref, Collections.emptyMap());
    }
    public static Command fill(LocatorSpec ref, String value) {
        return new Command(ActionType.FILL, ref, Collections.singletonMap("value", value));
    }
    public static Command select(LocatorSpec ref, String option) {
        return new Command(ActionType.SELECT, ref, Collections.singletonMap("option", option));
    }
    public static Command assertVisible(LocatorSpec ref) {
        return new Command(ActionType.ASSERT_VISIBLE, ref, Collections.emptyMap());
    }
}
