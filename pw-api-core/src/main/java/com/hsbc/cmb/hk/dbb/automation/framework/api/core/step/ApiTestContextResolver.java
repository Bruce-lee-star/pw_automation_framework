package com.hsbc.cmb.hk.dbb.automation.framework.api.core.step;

import com.hsbc.cmb.hk.dbb.automation.framework.common.cleanstate.StateResolver;
import com.hsbc.cmb.hk.dbb.automation.framework.core.context.ScenarioContext;

/**
 * {@link ApiTestContext} 的 {@link StateResolver} 实现——把 API 共享态（BaseStep）的复位与残留判定
 * 收口进框架统一的 {@link com.hsbc.cmb.hk.dbb.automation.framework.common.cleanstate.CleanStateRegistry}。
 *
 * <p>经 SPI 自动发现（{@code META-INF/services/...StateResolver} 声明），无需业务手动注册；
 * 由框架钩子在 scenario 边界统一调用：{@code @Before} 复位、{@code @After} 断言无残留。
 */
public class ApiTestContextResolver implements StateResolver {

    @Override
    public String name() {
        return "api.baseStep";
    }

    @Override
    public void reset() {
        ApiTestContext.clear();
    }

    @Override
    public boolean isDirty() {
        String scenarioId = ApiTestContext.baseStepScenarioId();
        if (scenarioId == null) {
            return false; //  无 BaseStep：干净
        }
        //  若当前场景上下文已绑定且不同于 BaseStep 所属场景 → 跨场景残留（泄漏）。
        //  正常流程下 @Before 已复位且当前场景戳一致，永不失真；仅真实跨场景泄漏时触发。
        return ScenarioContext.isBound() && !scenarioId.equals(ScenarioContext.currentScenarioId());
    }
}
