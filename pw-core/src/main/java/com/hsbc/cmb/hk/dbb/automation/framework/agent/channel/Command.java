package com.hsbc.cmb.hk.dbb.automation.framework.agent.channel;

import java.util.Collections;
import java.util.Map;

/**
 * 类型化命令（设计 §4.1 原语集）。
 *
 * <p>仅包含受控原语，<b>不含脚本执行原语</b>（R2）。{@code target} 为 Role-First 定位符
 * （{@link LocatorSpec}），对应快照中的元素稳定引用；{@link #targetRef()} 返回其 canonical 串。</p>
 */
public final class Command {

    /** 受控原语类型（对齐 MCP 工具集，但收敛为企业级契约）。 */
    public enum ActionType {
        NAVIGATE, CLICK, FILL, SELECT, CHECK, UNCHECK, WAIT, ASSERT_VISIBLE, ASSERT_TEXT
    }

    private final ActionType action;
    private final LocatorSpec target;          // 角色优先定位符（NULL 仅用于 NAVIGATE）
    private final Map<String, String> params;

    public Command(ActionType action, LocatorSpec target, Map<String, String> params) {
        this.action = action;
        this.target = target;
        this.params = params == null ? Collections.emptyMap() : params;
    }

    public ActionType action() { return action; }
    public LocatorSpec target() { return target; }
    /** canonical 定位串（快照索引键 / 便捷入口）。 */
    public String targetRef() { return target == null ? "" : target.canonical(); }
    public Map<String, String> params() { return params; }

    // —— 便捷工厂（覆盖联动验证常见触发） ——
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
