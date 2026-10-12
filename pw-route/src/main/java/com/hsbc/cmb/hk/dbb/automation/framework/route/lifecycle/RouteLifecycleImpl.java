package com.hsbc.cmb.hk.dbb.automation.framework.route.lifecycle;

import com.hsbc.cmb.hk.dbb.automation.framework.common.route.CaptureContext;
import com.hsbc.cmb.hk.dbb.automation.framework.common.route.RouteLifecycle;
import com.hsbc.cmb.hk.dbb.automation.framework.common.route.RouteLifecycleRegistry;
import com.hsbc.cmb.hk.dbb.automation.framework.route.RouteEngine;
import com.microsoft.playwright.BrowserContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Route 的生命周期 SPI 实现 —— 挂到核心层 {@link RouteLifecycleRegistry} 的追加位。
 * <b>2026-09-30 桥接模块删除后，主代码不再注册 primary</b>（仅测试可临时注入），
 * 本实现即唯一注册者，Composite 直接驱动（primary 恒空时语义不变）。
 *
 * <p>职责边界（采集管道由 {@code RouteRuntime} 内部自持、不向本接口查询面暴露捕获句柄；无全局规则表，
 * 只实现「引擎收尾」）：
 * <ul>
 *   <li>{@link #stopContextEngine(Object)} / {@link #clearContext(Object)} → 关闭指定 context 的 Route 运行时
 *       （幂等；context 关闭事件本也会触发，这里提供显式生命周期挂点）；</li>
 *   <li>{@link #stopAllContextEngines()} / {@link #shutdownRouteEngine()} / {@link #drainForSuiteTeardown()}
 *       → 关闭全部 Route 运行时（套件收尾）；</li>
 *   <li>采集 / 注册表 / 脱敏：本接口不暴露对应状态 → no-op 或原样返回（采集发生在 RouteRuntime 内部）。</li>
 * </ul>
 *
 * <p>注册时机：本类静态块自注册；由 {@link RouteEngine} 静态块在业务首次使用路由时触发加载。
 */
public class RouteLifecycleImpl implements RouteLifecycle {

    private static final Logger LOGGER = LoggerFactory.getLogger(RouteLifecycleImpl.class);

    static {
        RouteLifecycleRegistry.registerAdditional(new RouteLifecycleImpl());
        LOGGER.debug("[Route] RouteLifecycleImpl registered as additional lifecycle");
    }

    @Override
    public void stopContextEngine(Object ctx) {
        if (ctx instanceof BrowserContext bc) {
            RouteEngine.shutdown(bc);
        }
    }

    /**
     * 带「Context 正被主动关闭」意图的停止（T8-5，{@code EngineControl} 默认方法的覆写）。
     *
     * <p>传 {@code true} 时 runtime 跳过逐条 unroute —— 规则随 {@code context.close()} 原生释放。
     * 2026-09-30 收口后每 case 重建并销毁 Context，本路径即收尾常态（FIX_PLAN §10.3 C-4）。
     */
    @Override
    public void stopContextEngine(Object ctx, boolean contextBeingClosed) {
        if (ctx instanceof BrowserContext bc) {
            RouteEngine.shutdown(bc, contextBeingClosed);
        }
    }

    /**
     * 清规则（<b>保留</b> runtime 与 Context）—— 2026-09-30 改为【档 B 纯内存解绑】。
     *
     * <p>原实现等价于 {@code stopContextEngine}（拆 runtime + 逐条 unroute）。而 web 的
     * {@code PlaywrightManager.clearCurrentThreadRouteState()} 在<b>每个 scenario 收尾</b>都调本方法
     * ⇒ 每用例 N 次 {@code setNetworkInterceptionPatterns}，正是 30s 卡死 / 信道污染 / 下一用例
     * bind 挂死的引信（FIX_PLAN §4.1）。现改为 {@link RouteEngine#detachRules}：规则表内存清空，
     * 驱动侧 handler 常驻（命中即 fail-open），<b>零协议调用</b>。</p>
     *
     * <p>Context 真的要关闭时，由 {@link #stopContextEngine(Object, boolean)}（{@code true}）随
     * {@code context.close()} 原生释放 —— 同样不 unroute。</p>
     */
    @Override
    public void clearContext(Object ctx) {
        if (ctx instanceof BrowserContext bc) {
            RouteEngine.detachRules(bc);
        }
    }

    @Override
    public void stopAllContextEngines() {
        RouteEngine.shutdownAll();
    }

    @Override
    public void shutdownRouteEngine() {
        RouteEngine.shutdownAll();
    }

    @Override
    public void drainForSuiteTeardown() {
        // 无跨用例共享线程池：套件收尾即关闭全部 per-context 运行时（幂等）
        RouteEngine.shutdownAll();
    }

    /**
     * V2 对「连接是否已不可靠」的回答（2026-09-29 政策落地：让 web 的用例起点恢复真正覆盖 V2）。
     *
     * <p>V2 唯一的"协议往返不可靠"来源，是<b>驱动信道被判定不可继续</b>：存在未收尾的在途协议调用
     * （{@code GuardedDriverCallImpl.handleTimeout} 在"先摘后判"后仍未确认收尾时<b>不新建线程</b>、
     * 只标记信道不可用）。对外 API 里没有"只复位连接"的手段，故 web 侧必须重建 {@code Playwright}
     * 实例（= 换 Node 驱动进程 = 换 Connection）；该重建经 {@code stopAllContextEngines()} →
     * {@code RouteEngine.shutdownAll()} 连带复位框架驱动信道。</p>
     */
    @Override
    public boolean isConnectionUnresponsive(Object ctx) {
        return !RouteEngine.isDriverChannelUsable();
    }

    @Override
    public String sanitizeUrl(String url) {
        // 查询型：Composite 由 primary（pw-route）优先处理；独立使用 V2 时原样返回
        return url;
    }

    // ── V2 无对应状态：no-op / null（保持 Composite 降级语义）──

    @Override
    public void resetCaptureCurrent() {
        // no-op
    }

    @Override
    public void stopCapture() {
        // no-op
    }

    @Override
    public CaptureContext getCurrentCapture() {
        return null;
    }

    @Override
    public CaptureContext resolveFailureCapture() {
        return null;
    }

    @Override
    public void setMonitorScenario(String name) {
        // no-op
    }

    @Override
    public void clearMonitorScenario() {
        // no-op
    }

    @Override
    public void clearMonitorFeature() {
        // no-op
    }

    @Override
    public void resetAll() {
        // no-op（V2 规则随 runtime，无全局注册表）
    }

    @Override
    public void clearAll() {
        // no-op
    }

    @Override
    public void clearDispatchedRoutes() {
        // no-op（V2 claim 表随 runtime 关闭自动落定）
    }

    @Override
    public void stopCaptureFor(Object context) {
        // 【收尾清理】用例收尾（PlaywrightListener.testFinished / context 关闭）会调用本方法：
        // 原为 no-op，导致"同一 Context 跨 scenario 复用"时上一条用例的采集数据残留给下一条。
        // 现按 context 清空采集数据（幂等）；context 无关的入参一律忽略。
        if (context instanceof com.microsoft.playwright.BrowserContext bc) {
            com.hsbc.cmb.hk.dbb.automation.framework.route.RouteEngine.resetCapturedApis(bc);
        }
    }

    @Override
    public void drainMainThreadActions(Object context) {
        // 方案 B（2026-10-11）：main 业务 seam —— step 边界执行 DELAY 到点的协议动作。
        // 只 drain 当前线程（worker）的 context —— 绝不跨 worker drain（每个 worker 各自
        // 一条 Connection，跨线程写别的 Connection 仍是并发写）。
        if (context instanceof com.microsoft.playwright.BrowserContext bc) {
            com.hsbc.cmb.hk.dbb.automation.framework.route.RouteEngine.drainMainThreadActions(bc);
        }
    }

    @Override
    public boolean hasActiveDelayRoutes(Object context) {
        if (context instanceof com.microsoft.playwright.BrowserContext bc) {
            return com.hsbc.cmb.hk.dbb.automation.framework.route.RouteEngine.hasActiveDelayRoutes(bc);
        }
        return false;
    }
}
