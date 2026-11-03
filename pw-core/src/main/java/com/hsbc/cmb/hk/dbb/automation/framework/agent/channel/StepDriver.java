package com.hsbc.cmb.hk.dbb.automation.framework.agent.channel;

import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.scope.ParamScopePolicy;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.scope.ParamScopeViolationException;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 确定性步骤执行编排（v3.7，pw-core，浏览器无关）。
 *
 * <p>把已接好参数范围门禁的 {@link BrowserCommandChannel} 边界真正用起来：
 * 给定一个 {@link StepPlan} 与一个命令通道实现，先跑治理预检
 * {@link ParamScopePolicy#validatePlan(StepPlan)}（副作用动作须声明 scope），
 * 再逐步以「step 覆盖 plan」的最终 scope 调用 {@code channel.execute(step, resolvedScope)}——
 * 门禁在该边界 fail-closed（未声明 / 越界参数不执行该步）。</p>
 *
 * <p>本类只负责<b>编排 + 门禁接线</b>，不含联动断言 / 快照比对等业务逻辑（属更大专项）；
 * 门禁失败（{@link ParamScopeViolationException}）fail-fast 向上传播，不静默吞。</p>
 */
public final class StepDriver {

    private StepDriver() {
    }

    /**
     * 执行整份 StepPlan：先治理预检，再逐步过门禁边界执行。
     *
     * @return 每步的 {@link CommandResult}（与 steps 顺序一致；门禁失败会在对应步前抛异常，后续步不执行）
     * @throws ParamScopeViolationException 任一步参数越界，或治理预检不通过（fail-fast）
     * @throws NullPointerException       plan / channel 为 null
     */
    public static List<CommandResult> execute(StepPlan plan, BrowserCommandChannel channel) {
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(channel, "channel");
        ParamScopePolicy.validatePlan(plan); // 治理预检（副作用动作须声明 scope）
        List<CommandResult> results = new ArrayList<>(plan.steps().size());
        for (int i = 0; i < plan.steps().size(); i++) {
            Command step = plan.steps().get(i);
            // 门禁边界：channel.execute(Command, ParamScope) 默认方法先 ParamScopeGate.check 再 execute(Command)
            results.add(channel.execute(step, plan.resolvedScope(i)));
        }
        return results;
    }
}
