package com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.scenario;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.PlaywrightManager;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.context.CustomOptionsManager;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.serenity.PlaywrightSerenityBridge;

import com.hsbc.cmb.hk.dbb.automation.framework.common.route.DriverFailureSignal;
import com.hsbc.cmb.hk.dbb.automation.framework.core.context.TestContextHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Scenario / Feature 级生命周期编排（WEB-P1-1 Step 7）：从 {@code PlaywrightManager} 下沉。
 *
 * <p>承载 scenario 级清理的<b>顺序敏感</b>编排：清理动作分布在 Serenity 桥的前后两侧，
 * 顺序一旦颠倒会造成真实浏览器资源泄漏或登录态保留优化被静默绕过（详见方法注释）。
 *
 * @apiNote 包级私有：仅供 {@code framework.web.lifecycle} 同包协作，非对外 API。
 */
public final class ScenarioLifecycle {

    private static final Logger logger = LoggerFactory.getLogger(PlaywrightManager.class);

    private ScenarioLifecycle() {
    }

    /**
     * Scenario 级清理。
     *
     * <p>修复 A-1：先调用桥做 scenario 级 Page/Context 关闭（桥内部 synchronized 取
     * {@code CONTEXT_KEY}/{@code PAGE_KEY} 执行真正的 close）。
     *
     * <p>⚠️ <b>顺序不可变更</b>：禁止在桥前 remove page/context 引用，否则桥取到 null，
     * scenario 级 Context/Page 不被关闭（仅触发 onClose 钩子），造成真实浏览器资源泄漏。
     *
     * <p>{@code CustomOptionsManager} 的清理必须放到<b>桥之后</b>：桥会先读取 {@code customStorageStatePath}
     * 完成 storageState 回填，再执行 Context/Page 关闭；若在桥之前调用 {@code removeAllThreadLocals()} 将其清空，
     * 回填所需的 storageState 路径会被静默丢失。{@code currentConfigId} 与钩子快照不参与桥的决策，可在桥前安全清理。
     */
    public static void cleanupForScenario() {
        cleanupForScenario(false);
    }

    /**
     * 带用例结果的场景收尾（A5，2026-09-26）。
     *
     * <p>{@code scenarioFailed} 仅作为<b>结果语义</b>传入：2026-09-30 收口后，无论成败、是否承载
     * 登录态，桥都<b>销毁本 case 的 Context 与 Page</b>（scenario-scoped）；登录复用统一走
     * {@code storageState} 快照注入（凭证是快照而非活体），"保留活 Context 延续登录态"已废除。
     *
     * <p><b>F2 收口（2026-10-10）</b>：失败信号（{@link DriverFailureSignal}）在<b>场景边界统一清零</b>。
     * 原因：每 case 重建 Context 后，若失败 case 未注册 route（无 runtime），信号只能由
     * {@code RouteRuntimeImpl.close()} 消费的路径不存在 → 信号泄漏到下一 case，使其 route 规则
     * 全部 fail-fast 直通（mock/monitor 静默失效）。本公共出口（每 case 必达）保证
     * <b>无论本 case 是否有 route runtime，下一 case 都从干净信号开始</b>。
     *
     * @param scenarioFailed 本用例是否失败
     */
    public static void cleanupForScenario(boolean scenarioFailed) {
        TestContextHolder.get().remove(PlaywrightManager.CURRENT_CONFIG_ID_KEY);

        PlaywrightSerenityBridge.cleanupForScenario(scenarioFailed);

        // 桥已返回：Page/Context 已真正关闭，且桥已读完 customStorageStatePath，
        // 此时再清理当前线程的资源型与自定义选项 ThreadLocal 引用。
        CustomOptionsManager.removeAllThreadLocals();
        TestContextHolder.get().remove(PlaywrightManager.PAGE_KEY);
        TestContextHolder.get().remove(PlaywrightManager.CONTEXT_KEY);

        // F2 收口：失败信号在场景边界统一清零（幂等；route runtime close 内亦消费一次）。
        // 放在所有清理之后 —— 本场景的 route 收尾（可能触发 unroute 确证）已完成，不再需要信号；
        // 若放在开头，closeContext 阶段的 fail-fast 检查会失效（unroute 可能再次等待超时）。
        DriverFailureSignal.reset();
    }

    /**
     * Feature 级别的清理（委托给 {@link PlaywrightSerenityBridge}）。
     */
    public static void cleanupForFeature() {
        PlaywrightSerenityBridge.cleanupForFeature();
    }
}