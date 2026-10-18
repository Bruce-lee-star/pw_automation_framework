package com.hsbc.cmb.hk.dbb.automation.framework.web.listener;

import com.hsbc.cmb.hk.dbb.automation.framework.common.route.RouteAssertionFailure;
import com.hsbc.cmb.hk.dbb.automation.framework.common.route.RouteAssertionProbe;
import com.hsbc.cmb.hk.dbb.automation.framework.common.route.RouteAssertionRegistry;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * P0 端到端印证：Route MONITOR 断言失败必须透出为<b>用例失败</b>，不可静默判 PASS（假绿）。
 *
 * <p>链路：{@code MonitorSink} 结算失败（status/body/超时定案）→ {@code RouteAssertionProbeImpl}
 * 经 {@link RouteAssertionRegistry} 暴露 → 步骤结束时
 * {@link StepFailureAggregator#checkAndFailOnRouteAssertions()} 抛 {@link AssertionError}
 * （沿 适配器 → 框架 StepInterceptor → Cucumber → JUnit 传播，IDE 正确标红）；用例收尾
 * {@link StepFailureAggregator#checkAndMarkRouteAssertionFailures()} 兜底上报证据。
 *
 * <p>本测试在 web 模块内（不依赖 route 实现类，用 registry 注册桩探针）验证链路末端：
 * 消费式语义保证两条上报路径天然防重（第二次调用为空）、探针未注册时安全跳过。
 */
public class RouteAssertionFailureSurfacesToScenarioTest {

    /** 桩探针：首次调用返回一条失败，之后消费为空（复刻 v2 消费式语义）。 */
    private static final class FailingProbe implements RouteAssertionProbe {
        private boolean consumed;

        @Override
        public List<RouteAssertionFailure> drainAndResolveFailures() {
            if (consumed) {
                return List.of();
            }
            consumed = true;
            return List.of(new RouteAssertionFailure(
                    "/api/login", "POST", "https://host/api/login",
                    200, 500, List.of(), false, 60_000L, "", "", "", ""));
        }
    }

    @Test
    public void routeAssertionFailureFailsStepAndIsIdempotent() {
        RouteAssertionRegistry.register(new FailingProbe());
        try {
            // ① 步骤结束路径：必须抛 AssertionError
            AssertionError thrown = null;
            try {
                StepFailureAggregator.checkAndFailOnRouteAssertions();
            } catch (AssertionError e) {
                thrown = e;
            }
            assertTrue("Route 断言失败必须抛 AssertionError", thrown != null);
            assertFalse("失败明细不得为空", thrown.getMessage() == null || thrown.getMessage().isEmpty());
            assertTrue("明细必须含失败规则", thrown.getMessage().contains("/api/login"));

            // ② 消费式幂等：第二次调用（同一场景后续步骤收尾）不得再抛
            AssertionError second = null;
            try {
                StepFailureAggregator.checkAndFailOnRouteAssertions();
            } catch (AssertionError e) {
                second = e;
            }
            assertNull("消费式语义：失败取走后不得重复抛错", second);
        } finally {
            RouteAssertionRegistry.clear();
        }
    }

    @Test
    public void routeAssertionFailureEndOfScenarioReportsEvidenceWithoutThrowing() {
        RouteAssertionRegistry.register(new FailingProbe());
        try {
            // 用例收尾兜底：仅上报证据（不抛错），且经消费式语义清空前次失败
            StepFailureAggregator.checkAndMarkRouteAssertionFailures();
            // 同上消费式：第二次调用安全跳过（不得抛错）
            StepFailureAggregator.checkAndMarkRouteAssertionFailures();
        } finally {
            RouteAssertionRegistry.clear();
        }
    }

    @Test
    public void absentProbeSkipsSafely() {
        RouteAssertionRegistry.clear();
        // 未注册探针：两条路径都必须安全跳过（不抛）
        StepFailureAggregator.checkAndFailOnRouteAssertions();
        StepFailureAggregator.checkAndMarkRouteAssertionFailures();
    }
}
