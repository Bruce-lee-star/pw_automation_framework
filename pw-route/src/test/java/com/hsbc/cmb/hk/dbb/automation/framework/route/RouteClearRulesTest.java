package com.hsbc.cmb.hk.dbb.automation.framework.route;

import com.hsbc.cmb.hk.dbb.automation.framework.route.dsl.RouteDsl;
import com.microsoft.playwright.BrowserContext;
import org.junit.After;
import org.junit.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 验收：只清规则、保留 runtime 与 context 入口（{@code RouteEngine.clearRules} / {@code RouteDsl.clearRules}）。
 *
 * <p>四条断言（缺一即不成立）：
 * <ol>
 *   <li><b>规则确被退役</b>：内存规则表清空（驱动句柄不被关闭 —— 走内存撤销，不驱动 unroute）；</li>
 *   <li><b>runtime 未被关闭</b>且是<b>同一实例</b>（未重建 ⇒ 不丢线程池、不触发重新登录）；</li>
 *   <li><b>Context 未被触碰</b>（{@code ctx.close()} 从未被调用）；</li>
 *   <li><b>清规则后同一 runtime 可继续注册</b>（幂等且可复用）。</li>
 * </ol>
 */
public class RouteClearRulesTest {

    private final List<BrowserContext> contexts = new CopyOnWriteArrayList<>();
    private final List<AutoCloseable> driverHandles = new CopyOnWriteArrayList<>();

    private BrowserContext mockContext() {
        BrowserContext ctx = mock(BrowserContext.class);
        when(ctx.route(anyString(), any(), any())).thenAnswer(inv -> {
            AutoCloseable handle = mock(AutoCloseable.class);
            driverHandles.add(handle);
            return handle;
        });
        doAnswer(inv -> null).when(ctx).onClose(any());
        contexts.add(ctx);
        return ctx;
    }

    private static void registerMockRule(BrowserContext ctx, String pattern) {
        RouteDsl.on(ctx).api(pattern).mock().status(200).body("{}").register();
    }

    @After
    public void cleanup() {
        for (BrowserContext ctx : contexts) {
            RouteEngine.shutdown(ctx);
        }
        RouteEngine.shutdownAll();
    }

    @Test
    public void clearRulesRetiresEveryRuleButKeepsRuntimeAndContext() throws Exception {
        BrowserContext ctx = mockContext();
        registerMockRule(ctx, "/api/a");
        registerMockRule(ctx, "/api/b");
        RouteRuntime runtime = RouteEngine.runtimeOf(ctx);
        assertEquals("两条规则应产生两个驱动句柄", 2, driverHandles.size());

        int cleared = RouteEngine.clearRules(ctx);

        assertEquals("必须清空全部规则（规则表计数）", 2, cleared);
        assertEquals("runtime 必须保留（未被 shutdown）", 1, RouteEngine.activeRuntimes());
        assertSame("必须是同一 runtime 实例（未重建）", runtime, RouteEngine.runtimeOf(ctx));
        assertFalse("runtime 不得被关闭", runtime.isClosed());
        verify(ctx, never()).close();
        // 2026-10-15 语义：clearRules 与 teardown 均不驱动 unroute —— 驱动绑定随 context.close()
        // 由 Playwright 原生释放（消除句柄已失效时 unroute 抛 Object doesn't exist 报错，且避免连接竞态）。
        for (AutoCloseable handle : driverHandles) {
            verify(handle, never()).close();
        }
        RouteEngine.shutdown(ctx); // teardown 只做内存收尾，不关闭驱动句柄
    }

    @Test
    public void clearRulesIsIdempotentAndKeepsRuntimeUsable() {
        BrowserContext ctx = mockContext();
        registerMockRule(ctx, "/api/a");

        assertEquals(1, RouteEngine.clearRules(ctx));
        assertEquals("幂等：规则表已空", 0, RouteEngine.clearRules(ctx));

        // 未被关闭 ⇒ 同 runtime 可继续注册（否则会抛 IllegalStateException）
        registerMockRule(ctx, "/api/c");
        assertEquals("清规则后仍可继续注册并被再次清理", 1, RouteEngine.clearRules(ctx));
        assertFalse(RouteEngine.runtimeOf(ctx).isClosed());
    }

    @Test
    public void clearRulesOnContextWithoutRuntimeIsZeroAndDoesNotCreateOne() {
        BrowserContext fresh = mockContext();
        int before = RouteEngine.activeRuntimes();

        assertEquals("无 runtime ⇒ 0", 0, RouteEngine.clearRules(fresh));
        assertEquals("不得为清规则而创建 runtime", before, RouteEngine.activeRuntimes());
    }

    @Test
    public void clearRulesAllKeepsEveryRuntime() {
        BrowserContext a = mockContext();
        registerMockRule(a, "/api/a");
        BrowserContext b = mockContext();
        registerMockRule(b, "/api/b");
        assertEquals(2, RouteEngine.activeRuntimes());

        assertEquals(2, RouteDsl.clearRulesAll());

        assertEquals("全部 runtime 都必须保留", 2, RouteEngine.activeRuntimes());
        assertFalse(RouteEngine.runtimeOf(a).isClosed());
        assertFalse(RouteEngine.runtimeOf(b).isClosed());
    }

    @Test
    public void dslClearRulesExposesEngineEntryAndRejectsForeignContextType() {
        BrowserContext ctx = mockContext();
        registerMockRule(ctx, "/api/a");

        assertEquals(1, RouteDsl.clearRules(ctx));

        try {
            RouteDsl.clearRules(new Object());
            fail("非 Page / BrowserContext 必须 fail-fast");
        } catch (IllegalArgumentException expected) {
            // 预期：fail-fast，避免静默误用
        }
    }
}
