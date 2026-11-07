package com.hsbc.cmb.hk.dbb.automation.framework.agent.channel;

import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.scope.ParamScope;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.scope.ParamScopeGate;

import java.util.List;

/**
 * 受限浏览器命令通道契约（设计 §4.1 / §3.1.3）。
 *
 * <p>仅暴露<b>类型化原语</b>，<b>不提供 evaluate / run_code_unsafe 等通用脚本入口</b>（R2 红线）。
 * 契约定义在 pw-core；真正的 Playwright 驱动实现由 pw-web-ui 提供，并复用 RouteTestBed 隔离靶场。
 *
 * <p>本通道是"执行底座"：验证驱动（AssetVerifier）与确定性执行（StepDriver）都经它驱动浏览器，
 * 自身不感知 LLM，因此运行期零 LLM（R6）。</p>
 *
 * <p><b>参数范围门禁（v3.7）</b>：{@link #execute(Command, ParamScope)} / {@link #executeAll(List, ParamScope)}
 * 是带参门禁边界——执行每步前先经 {@link ParamScopeGate} 做 fail-closed 取值域核对（未声明/越界即拒、该步不执行）。
 * 既有 {@link #execute(Command)} / {@link #executeAll(List)} 调用方不受影响（向后兼容）。</p>
 */
public interface BrowserCommandChannel {

    /** 采集有界 a11y 感知快照（role/name/ref + 元素状态）。 */
    Snapshot snapshot();

    /**
     * 验证前静默窗口（D3 稳定语义，I-03）：等待当前页面稳定（网络/导航收敛），供 Verify 阶段断言前调用。
     *
     * <p>默认 no-op（无浏览器能力或无需 settle 的通道，行为与现状一致）；Playwright 实现覆写为
     * {@code waitForLoadState} + 导航感知 + 超时兜底。实现不得暴露 {@code evaluate}（R2 红线），
     * 且必须带超时上限（不无限等待）。本方法只做「稳定等待」，不构成断言本身。</p>
     *
     * @param windowMs 静默窗口下限（毫秒）；实现可在此之上叠加导航等待，但总时长受自身超时上限约束
     */
    default void settle(long windowMs) {
        // no-op by default
    }

    /** 执行一个类型化命令，返回结果（含执行后快照，供联动断言）。 */
    CommandResult execute(Command command);

    /** 便捷批量执行（用于联动序列的连续触发）。 */
    default List<CommandResult> executeAll(List<Command> commands) {
        List<CommandResult> results = new java.util.ArrayList<>(commands.size());
        for (Command c : commands) {
            results.add(execute(c));
        }
        return results;
    }

    /**
     * 执行一个类型化命令，并先用 {@link ParamScopeGate} 做参数范围门禁（fail-closed）。
     * scope 为 null 或空 = 无约束（门禁放行）；非空则未声明 / 越界参数被拒、该步不执行。
     * 既有的 {@link #execute(Command)} 调用链不变（向后兼容）。
     */
    default CommandResult execute(Command command, ParamScope scope) {
        ParamScopeGate.check(scope, command);
        return execute(command);
    }

    /** 便捷批量执行（带参数范围门禁）：每步执行前均过 {@link ParamScopeGate}。 */
    default List<CommandResult> executeAll(List<Command> commands, ParamScope scope) {
        List<CommandResult> results = new java.util.ArrayList<>(commands.size());
        for (Command c : commands) {
            results.add(execute(c, scope));
        }
        return results;
    }
}
