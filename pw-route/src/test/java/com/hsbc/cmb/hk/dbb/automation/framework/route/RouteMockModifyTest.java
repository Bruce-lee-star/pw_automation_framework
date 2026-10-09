package com.hsbc.cmb.hk.dbb.automation.framework.route;

import com.hsbc.cmb.hk.dbb.automation.framework.route.dsl.RouteDsl;
import com.microsoft.playwright.APIRequestContext;
import com.microsoft.playwright.APIResponse;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Frame;
import com.microsoft.playwright.Request;
import com.microsoft.playwright.Route;
import org.junit.After;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.function.Consumer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 第二批能力 dispatch 级验证（mock BrowserContext）：
 * <ul>
 *   <li>MOCK 静态体 + 字段替换（IO 线程替换后 fulfill 替换后 body）；</li>
 *   <li>MOCK intercept + 字段替换（快照 + context.request() 取真实响应 → 替换 → fulfill）；</li>
 *   <li>MODIFY body 级修改（IO 线程改 postData → resume 携带新 body）；</li>
 *   <li>mockBodyFromFile（classpath 资源读取）。</li>
 * </ul>
 */
public class RouteMockModifyTest {

    private BrowserContext ctx;

    /** runtime 所属 Context 的 APIRequestContext（intercept 取响应用），供用例 stub。 */
    private APIRequestContext lastApiCtx;

    private BrowserContext mockContext() {
        ctx = mock(BrowserContext.class);
        when(ctx.route(anyString(), any(), any())).thenReturn(mock(AutoCloseable.class));
        org.mockito.Mockito.doAnswer(inv -> null).when(ctx).onClose(any());
        // 方案 A：intercept 取响应走 runtime.request()（= context.request()），这里把 context 的 APIRequestContext 接上
        lastApiCtx = mock(APIRequestContext.class);
        when(ctx.request()).thenReturn(lastApiCtx);
        return ctx;
    }

    @After
    public void cleanup() {
        if (ctx != null) {
            RouteEngine.shutdown(ctx);
        }
    }

    private Request mockRequestWithBody(String url, String method, String postData, String contentType) {
        Request request = mock(Request.class);
        when(request.url()).thenReturn(url);
        when(request.method()).thenReturn(method);
        when(request.headers()).thenReturn(contentType == null ? Map.of() : Map.of("content-type", contentType));
        when(request.postData()).thenReturn(postData);
        when(request.resourceType()).thenReturn("xhr");
        when(request.isNavigationRequest()).thenReturn(false);
        Frame frame = mock(Frame.class);
        when(frame.parentFrame()).thenReturn(null);
        when(request.frame()).thenReturn(frame);
        return request;
    }

    private Route mockRoute(Request request) {
        Route route = mock(Route.class);
        when(route.request()).thenReturn(request);
        return route;
    }

    /** 注册规则并取出驱动 handler。 */
    private Consumer<Route> captureHandler() {
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Consumer<Route>> captor = ArgumentCaptor.forClass((Class) Consumer.class);
        verify(ctx).route(anyString(), captor.capture(), any());
        return captor.getValue();
    }

    @Test
    public void mockStaticBodyWithReplacementFulfillsReplacedBody() throws Exception {
        mockContext();
        RouteRuntime runtime = RouteEngine.runtimeOf(ctx);

        RouteDsl.on(ctx)
                .api("/api/user")
                .mock().status(200).contentType("application/json")
                .body("{\"code\":0,\"name\":\"old\"}")
                .replaceField("$.code", 1)
                .register();
        Consumer<Route> handler = captureHandler();

        Route route = mockRoute(mockRequestWithBody("https://host/api/user", "GET", null, null));
        handler.accept(route);
        runtime.io().close(); // 等待 IO 替换任务落定

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Route.FulfillOptions> captor = ArgumentCaptor.forClass((Class) Route.FulfillOptions.class);
        verify(route).fulfill(captor.capture());
        assertEquals("静态体必须被字段替换", (Object) "{\"code\":1,\"name\":\"old\"}", (Object) captor.getValue().body);
        assertEquals((long) 200L, (long) captor.getValue().status);
    }

    @Test
    public void mockInterceptWithReplacementFetchesThenReplaces() throws Exception {
        mockContext();
        RouteRuntime runtime = RouteEngine.runtimeOf(ctx);

        APIResponse apiResponse = mock(APIResponse.class);
        when(apiResponse.status()).thenReturn(200);
        when(apiResponse.headers()).thenReturn(Map.of("content-type", "application/json"));
        when(apiResponse.body()).thenReturn("{\"code\":0,\"name\":\"real\"}".getBytes(StandardCharsets.UTF_8));

        RouteDsl.on(ctx)
                .api("/api/profile")
                .mock().interceptResponse().mockReplaceField("$.code", 1)
                .register();
        Consumer<Route> handler = captureHandler();

        Request request = mockRequestWithBody("https://host/api/profile", "GET", null, null);
        Route route = mockRoute(request);
        // 方案 A：真实响应由 runtime 所属 Context 的 APIRequestContext 取（不再走 route.fetch）
        when(lastApiCtx.fetch(anyString(), any())).thenReturn(apiResponse);
        handler.accept(route);
        runtime.io().close();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Route.FulfillOptions> captor = ArgumentCaptor.forClass((Class) Route.FulfillOptions.class);
        verify(route).fulfill(captor.capture());
        assertEquals("真实响应必须被字段替换", (Object) "{\"code\":1,\"name\":\"real\"}", (Object) captor.getValue().body);
        assertEquals((long) 200L, (long) captor.getValue().status);
    }

    /**
     * 修复回归：真实响应头的键大小写不固定（{@code Content-Type} vs {@code content-type}），
     * 原实现直接 {@code headers().get("content-type")} ⇒ 取不到 ⇒ 字段替换静默失效。
     */
    @Test
    public void interceptWithMixedCaseContentTypeHeaderStillReplacesFields() throws Exception {
        mockContext();
        RouteRuntime runtime = RouteEngine.runtimeOf(ctx);

        APIResponse apiResponse = mock(APIResponse.class);
        when(apiResponse.status()).thenReturn(200);
        when(apiResponse.headers()).thenReturn(Map.of("Content-Type", "application/json"));
        when(apiResponse.body()).thenReturn("{\"code\":0,\"name\":\"real\"}".getBytes(StandardCharsets.UTF_8));

        RouteDsl.on(ctx).api("/api/case")
                .mock().interceptResponse().mockReplaceField("$.code", 7)
                .register();
        Consumer<Route> handler = captureHandler();

        Route route = mockRoute(mockRequestWithBody("https://host/api/case", "GET", null, null));
        when(lastApiCtx.fetch(anyString(), any())).thenReturn(apiResponse);
        handler.accept(route);
        runtime.io().close();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Route.FulfillOptions> captor = ArgumentCaptor.forClass((Class) Route.FulfillOptions.class);
        verify(route).fulfill(captor.capture());
        assertEquals("大小写不同的 content-type 头也必须让字段替换生效",
                (Object) "{\"code\":7,\"name\":\"real\"}", (Object) captor.getValue().body);
    }

    /**
     * 修复回归（E2E 实测场景）：拦截到的真实响应<b>没有 content-type</b> 时，
     * 必须按体嗅探 JSON 让替换生效 —— 原实现会判"非文本"并<b>静默保留原体</b>。
     */
    @Test
    public void interceptWithoutContentTypeSniffsJsonAndStillReplacesFields() throws Exception {
        mockContext();
        RouteRuntime runtime = RouteEngine.runtimeOf(ctx);

        APIResponse apiResponse = mock(APIResponse.class);
        when(apiResponse.status()).thenReturn(200);
        when(apiResponse.headers()).thenReturn(Map.of());
        when(apiResponse.body()).thenReturn("{\"enablePaymentPP\":\"Y\"}".getBytes(StandardCharsets.UTF_8));

        RouteDsl.on(ctx).api("/api/leftmenu")
                .mock().interceptResponse().mockReplaceField("$.enablePaymentPP", "X")
                .register();
        Consumer<Route> handler = captureHandler();

        Route route = mockRoute(mockRequestWithBody("https://host/api/leftmenu", "GET", null, null));
        when(lastApiCtx.fetch(anyString(), any())).thenReturn(apiResponse);
        handler.accept(route);
        runtime.io().close();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Route.FulfillOptions> captor = ArgumentCaptor.forClass((Class) Route.FulfillOptions.class);
        verify(route).fulfill(captor.capture());
        assertEquals("响应头无 content-type 时必须按体嗅探 JSON 让替换生效（不得静默保留原体）",
                (Object) "{\"enablePaymentPP\":\"X\"}", (Object) captor.getValue().body);
    }

    /**
     * <b>设计约束</b>：{@code interceptResponse()} 与静态 {@code status/body/headers} 互斥 ——
     * 拦截语义是"只改真实响应的字段"，不应同时接管状态/头/体（否则"到底发哪个"有歧义）。
     * 该组合必须在 {@code build()} 期 fail-fast（E2E 里曾误用 {@code interceptResponse().header(...)}）。
     */
    @Test
    public void interceptResponseRejectsStaticHeadersAtBuildTime() {
        mockContext();
        IllegalStateException expected = null;
        try {
            RouteDsl.on(ctx).api("/api/hdr")
                    .mock().interceptResponse()
                    .header("x-route-e2e", "pri")
                    .mockReplaceField("$.code", 9)
                    .register();
        } catch (IllegalArgumentException e) {
            expected = new IllegalStateException(e.getMessage());
        }
        assertNotNull("interceptResponse + static header 必须 fail-fast（不得静默接受）", expected);
        assertTrue("错误信息必须点明冲突原因，实际: " + expected.getMessage(),
                expected.getMessage().contains("interceptResponse"));
    }

    @Test
    public void modifyBodyResumesWithPostData() throws Exception {
        mockContext();
        RouteRuntime runtime = RouteEngine.runtimeOf(ctx);

        RouteDsl.on(ctx)
                .api("/api/login")
                .modifyRequest()
                .setRequestHeader("X-Env", "mock")
                .modifyRequestBody("$.password", "changed")
                .register();
        Consumer<Route> handler = captureHandler();

        Route route = mockRoute(mockRequestWithBody("https://host/api/login", "POST",
                "{\"user\":\"u\",\"password\":\"old\"}", "application/json"));
        handler.accept(route);
        runtime.io().close(); // 等待 body 修改任务落定

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Route.ResumeOptions> captor = ArgumentCaptor.forClass((Class) Route.ResumeOptions.class);
        verify(route).resume(captor.capture());
        Route.ResumeOptions options = captor.getValue();
        assertEquals("请求体必须被修改", (Object) "{\"user\":\"u\",\"password\":\"changed\"}", (Object) options.postData);
        assertEquals("header 修改必须同时生效", (Object) "mock", (Object) options.headers.get("X-Env"));
    }

    @Test
    public void modifyBodyOnNonJsonRequestFailsOpenWithOriginalBody() throws Exception {
        mockContext();
        RouteRuntime runtime = RouteEngine.runtimeOf(ctx);

        RouteDsl.on(ctx)
                .api("/api/bin")
                .modifyRequest().modifyRequestBody("$.a", "1")
                .register();
        Consumer<Route> handler = captureHandler();

        // 未知 content-type：明确拒绝修改，fail-open 原样 resume
        Route route = mockRoute(mockRequestWithBody("https://host/api/bin", "POST",
                "binary-data", "application/octet-stream"));
        handler.accept(route);
        runtime.io().close();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Route.ResumeOptions> captor = ArgumentCaptor.forClass((Class) Route.ResumeOptions.class);
        verify(route).resume(captor.capture());
        assertEquals("非适用类型必须原样 resume", (Object) "binary-data", (Object) captor.getValue().postData);
    }

    @Test
    public void mockBodyFromFileLoadsClasspathResource() throws Exception {
        mockContext();
        RouteRuntime runtime = RouteEngine.runtimeOf(ctx);

        RouteDsl.on(ctx)
                .api("/api/fromfile")
                .mock().mockBodyFromFile("mocks/login-template.json")
                .replaceField("$.token", "replaced")
                .status(200).contentType("application/json")
                .register();
        Consumer<Route> handler = captureHandler();

        Route route = mockRoute(mockRequestWithBody("https://host/api/fromfile", "GET", null, null));
        handler.accept(route);
        runtime.io().close();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Route.FulfillOptions> captor = ArgumentCaptor.forClass((Class) Route.FulfillOptions.class);
        verify(route).fulfill(captor.capture());
        String body = captor.getValue().body;
        assertTrue( "文件模板必须被字段替换", body.contains("replaced"));
        assertTrue( "文件内容必须被加载（未替换字段保留）", body.contains("\"user\":\"u\""));
    }

    @Test
    public void interceptConflictsWithStaticBodyAtBuildTime() {
        mockContext();
        boolean threw = false;
        try {
            RouteDsl.on(ctx)
                    .api("/api/x")
                    .mock().interceptResponse().body("{}")
                    .register();
        } catch (IllegalArgumentException expected) {
            threw = true;
        }
        assertTrue( "interceptResponse 与静态 body 必须互斥", threw);
    }
}
