package com.hsbc.cmb.hk.dbb.automation.framework.route;

import com.hsbc.cmb.hk.dbb.automation.framework.route.binding.CapturingGuardedDriverCall;
import com.hsbc.cmb.hk.dbb.automation.framework.route.binding.GuardedDriverCallRegistry;
import com.hsbc.cmb.hk.dbb.automation.framework.route.binding.StubGuardedDriverCall;
import com.hsbc.cmb.hk.dbb.automation.framework.route.dsl.ApiSpec;
import com.hsbc.cmb.hk.dbb.automation.framework.route.dsl.RouteCapability;
import com.microsoft.playwright.BrowserContext;
import org.junit.After;
import org.junit.Test;
import org.mockito.Mockito;

import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 规则"按目的生灭"契约（2026-09-28/29 T2+；2026-10-10 语义定稿）。
 *
 * <p><b>模型</b>：规则的生命周期跟随<b>目的</b>，不再跟随<b>用例</b> —— 达成目的即退役该规则
 * （无论断言成功或失败）；<b>没匹配到</b>也不会永久悬挂：监控窗口到期即"清理结束"。</p>
 *
 * <p><b>2026-10-10 结构性竞态修复（语义定稿）</b>：目的退役<b>只做内存规则表移除</b>
 * （dispatch 即刻 fail-open 放行，观测语义不变），<b>绝不发起驱动 unroute</b>。
 * 驱动侧绑定统一由收尾 {@code close()} 在场景结束时 flush（Context 即将关闭、无活跃 UI 操作，
 * 无并发窗口）。理由：scenario 进行中的后台 unroute 与主线程 UI 触发并发泵同一条 Playwright 连接
 * （官方模型：一个 Playwright = 一条 Connection，客户端对象表/回调表非线程安全），实证驱动级异常
 * （"Cannot find parent object request@… to create route@…"）冒泡为页面未捕获错误 ——
 * 违反"route 是辅助设施、绝不中断 UI"的核心不变式。</p>
 *
 * <p><b>不变式保持</b>：退役计数照常累计（T1 可观测）；{@code unconfirmedRetirements} 恒 0
 * （无协议提交 ⇒ {@code isClean()} 恒 true）；撤销确证结论由收尾 {@code close()} 统一汇总。</p>
 */
public class RouteRuleRetirementTest {

    private BrowserContext ctx;
    private AutoCloseable nativeHandle;

    private BrowserContext mockContext() {
        ctx = mock(BrowserContext.class);
        nativeHandle = mock(AutoCloseable.class);
        when(ctx.route(anyString(), any(), any())).thenReturn(nativeHandle);
        Mockito.doAnswer(inv -> null).when(ctx).onClose(any());
        return ctx;
    }

    @After
    public void cleanup() {
        GuardedDriverCallRegistry.reset();
        if (ctx != null) {
            RouteEngine.shutdown(ctx);
        }
    }

    /** 场景 1：目的达成即退役（纯内存），不触发驱动 unroute；收尾 flush 才撤销绑定。 */
    @Test
    public void purposeMetRetiresRuleInMemoryWithoutDriverUnroute() throws Exception {
        GuardedDriverCallRegistry.setInstance(new StubGuardedDriverCall());
        RouteRuntime runtime = RouteEngine.runtimeOf(mockContext());
        runtime.register(ApiSpec.builder("leftmenu/permissionLeftMenuConfig", RouteCapability.MONITOR)
                .expectStatus(200).build());

        assertTrue(runtime.retireByPurpose("leftmenu/permissionLeftMenuConfig"));

        // 目的退役是纯内存操作：scenario 进行中不得发起驱动 unroute（结构性消除与主线程 UI 的连接竞态）
        verify(nativeHandle, Mockito.never()).close();
        assertEquals("目的退役不产生未确证（无协议提交）", 0, runtime.unconfirmedRetirements());
        assertTrue("无 unroute 提交 ⇒ isClean 恒 true", runtime.isClean());

        // 驱动绑定随 context.close() 原生释放（teardown 不再 flush unroute）
        RouteEngine.shutdown(ctx);
        verify(nativeHandle, Mockito.never()).close();
    }

    /** 场景 2：重复退役幂等（无 unroute 提交、无未确证），驱动绑定保留到收尾。 */
    @Test
    public void retirementIsIdempotentInMemory() throws Exception {
        GuardedDriverCallRegistry.setInstance(new StubGuardedDriverCall());
        RouteRuntime runtime = RouteEngine.runtimeOf(mockContext());
        runtime.register(ApiSpec.builder("/api/once", RouteCapability.MONITOR).expectStatus(200).build());

        assertTrue(runtime.retireByPurpose("/api/once"));
        assertTrue("重复退役必须幂等返回 true（无待撤状态）", runtime.retireByPurpose("/api/once"));

        verify(nativeHandle, Mockito.never()).close();
        assertEquals(0, runtime.unconfirmedRetirements());
        assertTrue(runtime.isClean());
    }

    /** 场景 3：目的退役零协议调用 ⇒ 结构性无"未确证"（Capturing 替身实证无 unroute 下发）。 */
    @Test
    public void purposeRetirementIsZeroProtocolCalls() {
        CapturingGuardedDriverCall capturing = new CapturingGuardedDriverCall();
        GuardedDriverCallRegistry.setInstance(capturing);
        RouteRuntime runtime = RouteEngine.runtimeOf(mockContext());
        runtime.register(ApiSpec.builder("notifications/streams", RouteCapability.MONITOR).build());

        runtime.retireByPurpose("notifications/streams");

        assertTrue("目的退役不得发起任何 unroute 驱动调用（竞态修复的核心）",
                capturing.calls().stream().noneMatch(c -> c.opName.startsWith("unroute:")));
        assertEquals("目的退役不提交 unroute ⇒ 无未确证", 0, runtime.unconfirmedRetirements());
        assertTrue("无 unroute 提交 ⇒ isClean 恒 true（结构性保证）", runtime.isClean());
    }

    /** 场景 4（2026-10-15 定稿）：teardown 不再逐条 unroute —— 剩余规则只做内存收尾，handler 随 context.close() 原生释放。 */
    @Test
    public void teardownSkipsUnrouteOfRemainingRules() throws Exception {
        GuardedDriverCallRegistry.setInstance(new StubGuardedDriverCall());
        RouteRuntime runtime = RouteEngine.runtimeOf(mockContext());
        runtime.register(ApiSpec.builder("/api/a", RouteCapability.MONITOR).expectStatus(200).build());
        runtime.register(ApiSpec.builder("/api/b", RouteCapability.MOCK).build());
        AutoCloseable handle = nativeHandle;

        RouteEngine.shutdown(ctx); // teardown 只做内存收尾，不发起 unroute

        verify(handle, Mockito.never()).close();
        assertTrue("teardown 只做内存收尾，runtime 必须已关闭", runtime.isClosed());
        assertFalse("无注册失败 ⇒ 不得降级", runtime.isDegraded());
        assertTrue("无 unroute 提交 ⇒ isClean 恒 true", runtime.isClean());
    }

    /** 场景 5（2026-10-10 语义定稿）：目的退役零协议调用 ⇒ 无"需自愈"状态（重同步路径随契约退役）。 */
    @Test
    public void purposeRetirementNeedsNoResync() throws Exception {
        GuardedDriverCallRegistry.setInstance(new StubGuardedDriverCall());
        RouteRuntime runtime = RouteEngine.runtimeOf(mockContext());
        runtime.register(ApiSpec.builder("/api/heal", RouteCapability.MONITOR).expectStatus(200).build());

        runtime.retireByPurpose("/api/heal");

        verify(nativeHandle, Mockito.never()).close();
        assertEquals("无 unroute 提交 ⇒ 无未确证", 0, runtime.unconfirmedRetirements());
        assertTrue(runtime.isClean());
        assertFalse("无协议提交 ⇒ 不得降级", runtime.isDegraded());
    }

    /** 场景 6（2026-10-10 语义定稿）：监控窗口到期仍未匹配 ⇒ 规则内存退役；驱动绑定随收尾 flush。 */
    @Test
    public void ruleIsRetiredWhenMonitorWindowExpiresWithoutAnyMatch() throws Exception {
        GuardedDriverCallRegistry.setInstance(new StubGuardedDriverCall());
        RouteRuntime runtime = RouteEngine.runtimeOf(mockContext());
        runtime.register(ApiSpec.builder("/api/never-matches", RouteCapability.MONITOR)
                .expectStatus(200).monitorTimeoutMs(300).build());

        awaitRetired(runtime); // 窗口到期必须退役（没匹配到也清理）
        verify(nativeHandle, Mockito.never()).close(); // 目的退役零驱动调用
        assertTrue(runtime.isClean());

        RouteEngine.shutdown(ctx);
        verify(nativeHandle, Mockito.never()).close(); // teardown 不再 flush unroute，句柄随 context.close() 释放
    }

    /**
     * 竞态守护（R-1）：同 pattern <b>重新注册</b>后，旧规则留下的"窗口到期任务"
     * <b>绝不得撤掉新规则</b> —— 旧触发者因令牌不一致直接作废，新规则保有自己的生命周期终点。
     */
    @Test
    public void staleDeadlineMustNotRetireReRegisteredRule() throws Exception {
        GuardedDriverCallRegistry.setInstance(new StubGuardedDriverCall());
        RouteRuntime runtime = RouteEngine.runtimeOf(mockContext());

        // 规则 A：带 300ms 监控窗口 ⇒ 会安排一次"到期退役"
        runtime.register(ApiSpec.builder("/api/reused", RouteCapability.MONITOR)
                .expectStatus(200).monitorTimeoutMs(300).build());
        // 规则 B：同 pattern 重新注册（新实例）且不设窗口 ⇒ 接管该 pattern 的生命周期
        runtime.register(ApiSpec.builder("/api/reused", RouteCapability.MONITOR)
                .expectStatus(200).monitorTimeoutMs(0).build());

        // 越过 A 的窗口：A 的到期任务必须被令牌检查拒绝（不得撤掉 B 的规则表条目/绑定）
        TimeUnit.MILLISECONDS.sleep(700);

        // 新语义下目的退役本就零驱动调用；令牌拒绝的观察点是"退役计数不被 A 抬高"——
        // A 的到期被拒绝 ⇒ retiredByPurpose 不得增长（B 的规则表条目保留，未退役）
        assertEquals("旧触发者的到期任务必须被令牌检查拒绝（不得退役新规则）",
                0, runtime.metrics().retiredByPurpose());
        Mockito.verify(nativeHandle, Mockito.never()).close();
    }





    /** 等待监控窗口到期完成规则退役（退役计数增长）。 */
    private static void awaitRetired(RouteRuntime runtime) throws InterruptedException {
        for (int i = 0; i < 250; i++) {
            if (runtime.metrics().retiredByPurpose() > 0) {
                return;
            }
            TimeUnit.MILLISECONDS.sleep(20);
        }
        assertEquals("监控窗口到期必须完成退役", 1, runtime.metrics().retiredByPurpose());
    }
}
