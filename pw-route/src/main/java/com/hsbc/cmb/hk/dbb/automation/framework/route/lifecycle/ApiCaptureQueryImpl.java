package com.hsbc.cmb.hk.dbb.automation.framework.route.lifecycle;

import com.hsbc.cmb.hk.dbb.automation.framework.common.route.ApiCaptureQuery;
import com.hsbc.cmb.hk.dbb.automation.framework.common.route.ApiCaptureQueryRegistry;
import com.hsbc.cmb.hk.dbb.automation.framework.common.route.CapturedApiCallView;
import com.hsbc.cmb.hk.dbb.automation.framework.route.RouteEngine;
import com.hsbc.cmb.hk.dbb.automation.framework.route.monitor.CaptureSink;
import com.microsoft.playwright.BrowserContext;

import java.util.List;
import java.util.function.Predicate;

/**
 * {@link ApiCaptureQuery} 的 route 侧实现。
 *
 * <p><b>隔离维度 = BrowserContext</b>：SPI 传进来的 {@code Object context} 转成 {@code BrowserContext} 后，
 * 查询落到该 context 自己的采集 sink（{@code RouteEngine} 的 per-context runtime）。因此并行执行
 * 互不污染；context 关闭后 runtime 被移除，查询自然返回空。
 *
 * <p>本类在静态块中<b>自注册</b>到 {@link ApiCaptureQueryRegistry}，供 pw-web-ui 的业务门面
 * （{@code ApiCaptureContext.getCurrent()}）直接取用，无需依赖 pw-route。
 */
public final class ApiCaptureQueryImpl implements ApiCaptureQuery {

    static {
        // 幂等：注册后 Registry 不必再走反射实例化；pw-route 不在 classpath 时本类也不会被加载。
        ApiCaptureQueryRegistry.register(new ApiCaptureQueryImpl());
    }

    /** null-safe 转型：非 BrowserContext（含 null）一律按"无该 context"处理。 */
    private static BrowserContext asContext(Object context) {
        return context instanceof BrowserContext bc ? bc : null;
    }

    @Override
    public List<CapturedApiCallView> getAllApiCalls(Object context) {
        BrowserContext ctx = asContext(context);
        return ctx == null ? List.of()
                : RouteEngine.allCapturedApis(ctx).stream().map(CaptureSink::toView).toList();
    }

    @Override
    public List<CapturedApiCallView> getAllLastApiCalls(Object context) {
        BrowserContext ctx = asContext(context);
        return ctx == null ? List.of()
                : RouteEngine.lastCapturedPerPattern(ctx).stream().map(CaptureSink::toView).toList();
    }

    @Override
    public CapturedApiCallView getLastApiCall(Object context, String endpoint) {
        BrowserContext ctx = asContext(context);
        return ctx == null ? null : CaptureSink.toView(RouteEngine.lastCapturedApi(ctx, endpoint));
    }

    @Override
    public List<CapturedApiCallView> getCallsByUrl(Object context, String url) {
        BrowserContext ctx = asContext(context);
        return ctx == null ? List.of()
                : RouteEngine.capturedApisByUrl(ctx, url).stream().map(CaptureSink::toView).toList();
    }

    @Override
    public List<CapturedApiCallView> getCallsByChannel(Object context, String channel) {
        BrowserContext ctx = asContext(context);
        return ctx == null ? List.of()
                : RouteEngine.capturedApisByChannel(ctx, channel).stream().map(CaptureSink::toView).toList();
    }

    @Override
    public CapturedApiCallView waitForApi(Object context, Predicate<CapturedApiCallView> predicate, long timeoutMs) {
        return waitForApi(context, predicate, timeoutMs, 0L);
    }

    @Override
    public long capturedApiSequence(Object context) {
        BrowserContext ctx = asContext(context);
        return ctx == null ? -1L : RouteEngine.capturedApiSequence(ctx);
    }

    @Override
    public CapturedApiCallView waitForApi(Object context, Predicate<CapturedApiCallView> predicate,
                                          long timeoutMs, long minCaptureSeq) {
        BrowserContext ctx = asContext(context);
        if (ctx == null || predicate == null) {
            return null;
        }
        // 视图层谓词 → DTO 层谓词：按 View 判定，但轮询仍复用 sink 的非消费式等待。
        return CaptureSink.toView(RouteEngine.waitForCapturedApi(ctx,
                dto -> predicate.test(CaptureSink.toView(dto)), timeoutMs, minCaptureSeq));
    }

    @Override
    public void resetCapturedApis(Object context) {
        BrowserContext ctx = asContext(context);
        if (ctx != null) {
            RouteEngine.resetCapturedApis(ctx);
        }
    }
}
