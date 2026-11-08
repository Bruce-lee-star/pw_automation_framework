package com.hsbc.cmb.hk.dbb.automation.framework.web.agent;

import com.hsbc.cmb.hk.dbb.automation.framework.agent.runtime.AgentConcurrencyControl;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.runtime.AgentMonitorControl;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.runtime.AgentRouteControl;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.runtime.AgentRuntimeContext;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.runtime.AgentRuntimeContextProvider;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.runtime.AgentRuntimeScope;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.runtime.AgentSessionControl;
import com.hsbc.cmb.hk.dbb.automation.framework.common.route.RouteLifecycle;
import com.hsbc.cmb.hk.dbb.automation.framework.common.route.RouteLifecycleRegistry;
import com.hsbc.cmb.hk.dbb.automation.framework.web.concurrent.ConcurrencyGate;
import com.hsbc.cmb.hk.dbb.automation.framework.web.concurrent.ConcurrencyPartitionKey;
import com.hsbc.cmb.hk.dbb.automation.framework.web.session.SessionManager;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.function.Supplier;

/**
 * <b>业务链 → Agent 执行链的角色适配器</b>（T5 / I-06，SPI 贡献者）。
 *
 * <p>把四个原本以<b>静态工具类</b>形式被业务链直接调用的组件，包装为
 * {@link AgentRuntimeContext} 所需的<b>实例角色方法</b>：执行链从此只经组合根实例编排，
 * 不再各自持有孤岛状态；而业务链的静态门面仍保留为稳定公开契约（不删、不改签名），
 * 由本适配器复用，故业务调用方零改动、行为零回归。</p>
 *
 * <table border="1">
 *   <caption>四槽位映射</caption>
 *   <tr><th>角色</th><th>业务链组件</th><th>接线语义</th></tr>
 *   <tr><td>Route</td><td>{@link RouteLifecycleRegistry}</td>
 *       <td>执行作用域内设置 / 清除 monitor scenario，使执行链触发的 API 监控失败归属到同一采集上下文</td></tr>
 *   <tr><td>Session</td><td>{@link SessionManager}</td>
 *       <td>按 sessionKey 只读共享会话归属；不创建/销毁会话（那是业务链场景边界的职责，避免重复销毁）</td></tr>
 *   <tr><td>Concurrency</td><td>{@link ConcurrencyGate}</td>
 *       <td>同一把 per-sessionKey 闸门：进入 acquire、退出 release，与业务链 SSO 互斥同源</td></tr>
 *   <tr><td>Monitor</td><td>{@link RouteLifecycleRegistry}（route 侧 resolveFailureCapture）</td>
 *       <td>退出时只读探测失败采集上下文，共享同一份监控归集状态，不复位不写出</td></tr>
 * </table>
 *
 * <p><b>零回归护栏</b>：① 四个适配器均为无状态实例（不持有线程态，状态全部留在业务链既有
 * ThreadLocal / 注册表中）；② {@code sessionKey == null} 时 Session / Concurrency 完全 no-op；
 * ③ route 模块不在 classpath 时 {@link RouteLifecycleRegistry#get()} 返回 {@code null}，
 * Route / Monitor 亦 no-op；④ 执行链默认构造显式使用 {@link AgentRuntimeContext#empty()}，
 * 只有生产组合根 {@link AssetVerificationWiring#fromConfig()} 才装配本 SPI 聚合结果。</p>
 *
 * <p><b>尽力而为探针的异常边界</b>：Route / Session / Monitor 三个槽位只做「共享状态归属」的
 * 只读 / 影子接线，其失败不得打断执行链，故统一 {@code catch (Throwable)}——须覆盖
 * {@link java.util.ServiceConfigurationError}（{@link Error} 子类而非 {@link LinkageError}），
 * 否则 SPI provider 加载失败会逃逸并使调用方类初始化永久失败。核心门禁
 * {@code ConcurrencyGate.acquire} 故意不吞异常（fail-closed，占不到闸门即拒绝执行）。
 *
 * <p><b>为什么 Route / Monitor 不需要依赖 pw-route</b>：{@link RouteLifecycleRegistry} 是
 * pw-core 的依赖倒置桥，route 模块以 {@code Class.forName} 懒加载自注册；故 pw-web-ui
 * 在编译期零 route 依赖即可共享同一注册表状态，依赖图不变。</p>
 */
public final class WebAgentRuntimeContextProvider implements AgentRuntimeContextProvider {

    private static final Logger LOGGER = LoggerFactory.getLogger(WebAgentRuntimeContextProvider.class);

    /** 会话闸门维度名：与业务链 {@code SessionStore.acquireSessionGate} 同源（profile 不进键）。 */
    static final String SESSION_KEY_DIMENSION = "sessionkey";

    private final Supplier<RouteLifecycle> lifecycleSupplier;

    /** SPI 默认构造：经 pw-core {@link RouteLifecycleRegistry} 懒加载取得业务链注册实例。 */
    public WebAgentRuntimeContextProvider() {
        this(WebAgentRuntimeContextProvider::lifecycleOrNull);
    }

    /** 测试 seam：包级可见，允许注入替身而不污染全局注册表。 */
    WebAgentRuntimeContextProvider(Supplier<RouteLifecycle> lifecycleSupplier) {
        this.lifecycleSupplier = lifecycleSupplier;
    }

    @Override
    public AgentRuntimeContext contribute() {
        return AgentRuntimeContext.of(
                new RouteAdapter(lifecycleSupplier), new SessionAdapter(),
                new ConcurrencyAdapter(), new MonitorAdapter(lifecycleSupplier));
    }

    /** Route：把执行作用域映射为 monitor 场景归属，成对设置 / 清除，净状态为零。 */
    private static final class RouteAdapter implements AgentRouteControl {
        private final Supplier<RouteLifecycle> lifecycleSupplier;

        RouteAdapter(Supplier<RouteLifecycle> lifecycleSupplier) {
            this.lifecycleSupplier = lifecycleSupplier;
        }

        @Override
        public void onScopeOpen(AgentRuntimeScope scope) {
            RouteLifecycle lifecycle = lifecycle();
            if (lifecycle == null || scope.planId() == null) {
                return;
            }
            try {
                lifecycle.setMonitorScenario(scope.planId());
            } catch (Throwable t) {
                LOGGER.debug("[agent-runtime] setMonitorScenario unavailable: {}", t.toString());
            }
        }

        @Override
        public void onScopeClose(AgentRuntimeScope scope, Throwable failure) {
            RouteLifecycle lifecycle = lifecycle();
            if (lifecycle == null || scope.planId() == null) {
                return;
            }
            try {
                lifecycle.clearMonitorScenario();
            } catch (Throwable t) {
                LOGGER.debug("[agent-runtime] clearMonitorScenario unavailable: {}", t.toString());
            }
        }

        private RouteLifecycle lifecycle() {
            try {
                return lifecycleSupplier.get();
            } catch (Throwable t) {
                LOGGER.debug("[agent-runtime] route lifecycle supplier unavailable: {}", t.toString());
                return null;
            }
        }
    }

    /** Session：只读共享会话归属（不创建、不销毁、不复位业务链会话状态）。 */
    private static final class SessionAdapter implements AgentSessionControl {
        @Override
        public void onScopeOpen(AgentRuntimeScope scope) {
            String sessionKey = scope.sessionKey();
            if (sessionKey == null || sessionKey.isBlank()) {
                return;
            }
            try {
                // 只读：让执行链与业务链在同一 sessionKey 归属下工作；无会话时仅 debug，不触发登录。
                if (SessionManager.getHomeUrl(sessionKey) == null) {
                    LOGGER.debug("[agent-runtime] no persisted session for key '{}' (execution chain only)",
                            sessionKey);
                }
            } catch (Throwable t) {
                LOGGER.debug("[agent-runtime] session lookup unavailable: {}", t.toString());
            }
        }
    }

    /** Concurrency：与业务链共用同一把 per-sessionKey 闸门；进入 / 退出严格配对，不泄漏许可。 */
    private static final class ConcurrencyAdapter implements AgentConcurrencyControl {
        @Override
        public void onScopeOpen(AgentRuntimeScope scope) {
            ConcurrencyPartitionKey key = keyOf(scope);
            if (key != null) {
                ConcurrencyGate.acquire(key);
            }
        }

        @Override
        public void onScopeClose(AgentRuntimeScope scope, Throwable failure) {
            ConcurrencyPartitionKey key = keyOf(scope);
            if (key != null) {
                ConcurrencyGate.release(key);
            }
        }

        private static ConcurrencyPartitionKey keyOf(AgentRuntimeScope scope) {
            String sessionKey = scope.sessionKey();
            if (sessionKey == null || sessionKey.isBlank()) {
                return null;
            }
            return ConcurrencyPartitionKey.of(Map.of(SESSION_KEY_DIMENSION, sessionKey));
        }
    }

    /** Monitor：只读探测同一份失败采集上下文（不 write、不 clear，零副作用可观测性）。 */
    private static final class MonitorAdapter implements AgentMonitorControl {
        private final Supplier<RouteLifecycle> lifecycleSupplier;

        MonitorAdapter(Supplier<RouteLifecycle> lifecycleSupplier) {
            this.lifecycleSupplier = lifecycleSupplier;
        }

        @Override
        public void onScopeClose(AgentRuntimeScope scope, Throwable failure) {
            RouteLifecycle lifecycle;
            try {
                lifecycle = lifecycleSupplier.get();
            } catch (Throwable t) {
                LOGGER.debug("[agent-runtime] route lifecycle supplier unavailable: {}", t.toString());
                return;
            }
            if (lifecycle == null) {
                return;
            }
            try {
                lifecycle.resolveFailureCapture();
            } catch (Throwable t) {
                LOGGER.debug("[agent-runtime] monitor capture probe unavailable: {}", t.toString());
            }
        }
    }

    /**
     * 取当前路由生命周期实现；route 模块缺席 / SPI 未就绪时返回 {@code null}。
     * {@code catch (Throwable)} 覆盖 ServiceConfigurationError 等 Error 子类，保证尽力而为接线不打断执行。
     */
    private static RouteLifecycle lifecycleOrNull() {
        try {
            return RouteLifecycleRegistry.get();
        } catch (Throwable t) {
            LOGGER.debug("[agent-runtime] route lifecycle unavailable: {}", t.toString());
            return null;
        }
    }
}
