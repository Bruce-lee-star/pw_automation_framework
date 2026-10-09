package com.hsbc.cmb.hk.dbb.automation.framework.web.route.core;

import com.hsbc.cmb.hk.dbb.automation.framework.common.route.ApiCaptureQuery;
import com.hsbc.cmb.hk.dbb.automation.framework.common.route.ApiCaptureQueryRegistry;
import com.hsbc.cmb.hk.dbb.automation.framework.common.route.CapturedApiCallView;
import com.hsbc.cmb.hk.dbb.automation.framework.web.core.RuntimeProvider;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.PlaywrightManager;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * {@link ApiCaptureContext} 门面契约（此前 0 覆盖）：6 个查询方法<b>逐一转发</b>给 pw-core 的 SPI
 * {@link ApiCaptureQuery}，并把<b>当前线程的 BrowserContext</b>作为隔离维度传下去；SPI 缺席时安全降级为空结果。
 *
 * <p><b>为什么这层要单测</b>：它是业务/测试代码的唯一读取入口（{@code ApiCaptureContext.getCurrent()}），
 * 真实链路（SPI → RouteEngine → per-context sink）在 SIT 才能跑；这里用<b>记录型桩 + 运行时对象源替身</b>
 * 把"转发语义 + 降级语义"钉在 JVM 内，SIT 走不到的降级分支由此覆盖。
 *
 * <p><b>测试卫生</b>：{@link ApiCaptureQueryRegistry} 与 {@code PlaywrightManager} 的 provider 都是
 * <b>进程级静态</b>，故 {@code @Before/@After} 双双复位，避免污染同 JVM 其它用例。
 */
public class ApiCaptureContextTest {

    private BrowserContext context;
    private final StubQuery stub = new StubQuery();

    @Before
    public void setUp() {
        ApiCaptureQueryRegistry.clear();
        context = mock(BrowserContext.class);
        // 正式单测接缝（WEB-P0-2）：替换运行时对象源 ⇒ context() 返回本用例 mock，无需真实浏览器
        PlaywrightManager.setProvider(new StubProvider(context));
    }

    @After
    public void tearDown() {
        ApiCaptureQueryRegistry.clear();
        PlaywrightManager.resetProvider();
    }

    @Test
    public void everyQueryIsDelegatedWithTheCurrentThreadContext() {
        ApiCaptureQueryRegistry.register(stub);
        ApiCaptureContext facade = ApiCaptureContext.getCurrent();
        Predicate<CapturedApiCallView> predicate = view -> true;

        assertSame("getAllApiCalls 必须原样透传 SPI 返回", stub.sentinelAll, facade.getAllApiCalls());
        assertSame("每次转发都必须带当前线程 Context", context, stub.lastContext);
        assertSame("getAllLastApiCalls 必须原样透传 SPI 返回", stub.sentinelLast, facade.getAllLastApiCalls());
        assertSame(context, stub.lastContext);

        assertNull("getLastApiCall 必须透传 SPI 的 null", facade.getLastApiCall("/api/login"));
        assertSame(context, stub.lastContext);
        assertEquals("endpoint 必须原样传递", "/api/login", stub.lastArg);

        assertSame(stub.sentinelByArg, facade.getCallsByUrl("https://host/api/x"));
        assertEquals("url 必须原样传递", "https://host/api/x", stub.lastArg);
        assertSame(stub.sentinelByArg, facade.getCallsByChannel("MOCK"));
        assertEquals("channel 必须原样传递（大小写不做加工）", "MOCK", stub.lastArg);
        assertSame(context, stub.lastContext);

        assertNull("waitForApi 透传 SPI 结果（桩返回 null）", facade.waitForApi(predicate, 123L));
        assertSame("predicate 必须原样传递", predicate, stub.lastPredicate);
        assertEquals("timeoutMs 必须原样传递", 123L, stub.lastTimeoutMs);
        assertSame(context, stub.lastContext);
    }

    @Test
    public void facadeDegradesToEmptyResultsWhenNoSpiIsAvailable() {
        // 未注册实现：pw-route 不在 classpath ⇒ Registry.get() 为 null ⇒ 全部降级（且不得依赖 Context 可用）
        ApiCaptureQueryRegistry.clear();
        ApiCaptureContext facade = ApiCaptureContext.getCurrent();

        assertTrue("无 SPI 时不得抛错，返回空列表", facade.getAllApiCalls().isEmpty());
        assertTrue(facade.getAllLastApiCalls().isEmpty());
        assertNull(facade.getLastApiCall("/api/login"));
        assertTrue(facade.getCallsByUrl("https://host/api/x").isEmpty());
        assertTrue(facade.getCallsByChannel("MOCK").isEmpty());
        assertNull(facade.waitForApi(view -> true, 10L));
    }

    @Test
    public void facadeIsAStatelessSingletonScopedByContext() {
        assertSame("门面为无状态单例（数据按 Context 维度存放）",
                ApiCaptureContext.getCurrent(), ApiCaptureContext.getCurrent());

        ApiCaptureQueryRegistry.register(stub);
        ApiCaptureContext.getCurrent().getAllApiCalls();
        assertSame("隔离维度必须是当前线程的 Context", context, stub.lastContext);
    }

    /** 记录型 SPI 桩：断言门面是否把调用与参数原样转发（返回哨兵对象以验证透传）。 */
    private static final class StubQuery implements ApiCaptureQuery {

        private final List<CapturedApiCallView> sentinelAll = new ArrayList<>();
        private final List<CapturedApiCallView> sentinelLast = new ArrayList<>();
        private final List<CapturedApiCallView> sentinelByArg = new ArrayList<>();

        private Object lastContext;
        private String lastArg;
        private Predicate<CapturedApiCallView> lastPredicate;
        private long lastTimeoutMs = -1L;

        @Override
        public List<CapturedApiCallView> getAllApiCalls(Object context) {
            this.lastContext = context;
            return sentinelAll;
        }

        @Override
        public List<CapturedApiCallView> getAllLastApiCalls(Object context) {
            this.lastContext = context;
            return sentinelLast;
        }

        @Override
        public CapturedApiCallView getLastApiCall(Object context, String endpoint) {
            this.lastContext = context;
            this.lastArg = endpoint;
            return null;
        }

        @Override
        public List<CapturedApiCallView> getCallsByUrl(Object context, String url) {
            this.lastContext = context;
            this.lastArg = url;
            return sentinelByArg;
        }

        @Override
        public List<CapturedApiCallView> getCallsByChannel(Object context, String channel) {
            this.lastContext = context;
            this.lastArg = channel;
            return sentinelByArg;
        }

        @Override
        public CapturedApiCallView waitForApi(Object context, Predicate<CapturedApiCallView> predicate,
                                              long timeoutMs) {
            this.lastContext = context;
            this.lastPredicate = predicate;
            this.lastTimeoutMs = timeoutMs;
            return null;
        }

        @Override
        public void resetCapturedApis(Object context) {
            this.lastContext = context;
        }
    }

    /** 运行时对象源替身：仅 Context 有值（其余获取路径本用例不触达）。 */
    private static final class StubProvider implements RuntimeProvider {

        private final BrowserContext context;

        private StubProvider(BrowserContext context) {
            this.context = context;
        }

        @Override
        public Playwright getPlaywright() {
            return null;
        }

        @Override
        public Browser getBrowser() {
            return null;
        }

        @Override
        public BrowserContext getContext() {
            return context;
        }

        @Override
        public Page getPage() {
            return null;
        }

        @Override
        public boolean isTestDouble() {
            return true;
        }
    }
}
