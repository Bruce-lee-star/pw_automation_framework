package com.hsbc.cmb.hk.dbb.automation.framework.web.page.recording;

import com.hsbc.cmb.hk.dbb.automation.framework.common.route.RouteLifecycle;
import com.hsbc.cmb.hk.dbb.automation.framework.common.route.RouteLifecycleRegistry;
import com.hsbc.cmb.hk.dbb.automation.framework.web.exceptions.BrowserException;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.PlaywrightManager;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

/**
 * framework-internal：DELAY 运行期 guard 专用薄代理（§8.4 零容忍）。
 *
 * <p>与 {@link RecordingPageProxy}（录制装饰，含同款 guard）互补：录制开启时业务 Page 经
 * {@code RecordingPageProxy} 包装，guard 已生效；但<b>录制关闭</b>时业务 Page 不再经该装饰，
 * 原 guard 会漏掉。本类在录制关闭路径仍然对原生 {@code waitForFunction} 注入 guard，使
 * 「DELAY 场景禁用原生 waitForFunction」的约束在两条路径一致生效。
 *
 * <p>设计铁律（与 {@link RecordingPageProxy} 对齐，保证零行为变更 + 身份可归一）：
 * <ul>
 *   <li>仅拦截 {@code waitForFunction}（其余方法 100% 委托真实 Page）；</li>
 *   <li>{@code onXxx} 监听注册直接委托（不包装回调对象，避免干扰 Playwright 事件）；</li>
 *   <li>{@code equals/hashCode/toString} 等 {@link Object} 方法直接委托真实对象；</li>
 *   <li>暴露 {@code target()} 访问器，供核心层 {@code PageRefs.unwrap} 做页面身份归一（路由 PAGE 级规则按同一性筛选）；</li>
 *   <li>测试替身（mock）/ null 入参返回裸 Page，零开销。</li>
 * </ul>
 *
 * <p><b>边界</b>：本代理不做录制，也不拒绝 {@code close()}（受管 Page 生命周期仍由框架托管，
 * 但 close 委托真实对象以保持与裸 Page 完全一致的行为，避免引入新的生命周期限制）。</p>
 */
public final class DelayGuardPageProxy {

    private static final Logger LOGGER = LoggerFactory.getLogger(DelayGuardPageProxy.class);

    private DelayGuardPageProxy() {
    }

    /**
     * 包装受管 Page，仅注入 DELAY guard（不录制）。测试替身或入参为空时返回裸 Page。
     */
    public static Page wrap(Page real) {
        if (real == null || PlaywrightManager.getProvider().isTestDouble()) {
            return real;
        }
        return (Page) Proxy.newProxyInstance(
                DelayGuardPageProxy.class.getClassLoader(),
                new Class<?>[]{Page.class},
                new DelayGuardHandler(real));
    }

    // ==================== Handler ====================

    private static final class DelayGuardHandler implements InvocationHandler {
        private final Object target;

        DelayGuardHandler(Object target) {
            this.target = target;
        }

        /**
         * 被委托的真实对象（访问器约定，见核心层 {@code PageRefs}）。
         *
         * <p><b>必须保留</b>：路由 PAGE 级规则按对象同一性筛选适用页面，若无法从本代理取回真实
         * Page，则「同一页面的代理」会被判为不适用 → MOCK / MODIFY / DELAY 静默失效。</p>
         */
        public Object target() {
            return target;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            // Object 方法直接委托真实对象，不做 guard
            if (method.getDeclaringClass() == Object.class) {
                return method.invoke(target, args);
            }
            // 运行期 guard（§8.4 零容忍）：原生 waitForFunction 阻塞 main 业务线程，使已入队 main 的
            // DELAY 交付（resume/fulfill）无法被 drain ⇒ 延迟响应卡死（E2E run-b 实证 15s 超时）。
            // 当前 context 仍有生效 DELAY 规则时，原生 waitForFunction 一律拒绝，强制走
            // RouteDsl.waitForUiFunction（内部循环 drain + 短窗等待）。框架自身的 waitForUiFunction
            // 已对真实 Page 调用 waitForFunction（绕过本代理），不会被本 guard 误伤。
            if ("waitForFunction".equals(method.getName())) {
                BrowserContext ctx = contextOf(target);
                if (ctx != null) {
                    RouteLifecycle lc = RouteLifecycleRegistry.get();
                    if (lc != null && lc.hasActiveDelayRoutes(ctx)) {
                        throw new BrowserException(
                                "Native page.waitForFunction is forbidden while DELAY routes are armed for this context "
                                        + "(it blocks the main thread so delayed resume/fulfill can never drain -> 15s timeout, "
                                        + "violating SSO/route design S8.4 zero-tolerance). Use RouteDsl.waitForUiFunction(...) instead, "
                                        + "which drains the main action queue in a loop before each short wait window.");
                    }
                }
            }
            // 其余方法 100% 委托真实对象（零行为变更）
            try {
                return method.invoke(target, args);
            } catch (InvocationTargetException e) {
                throw e.getCause();
            } catch (IllegalAccessException e) {
                throw new RuntimeException("Failed to invoke target method: " + method.getName(), e);
            }
        }

        /**
         * 从被包装的真实对象取回其 {@link BrowserContext}（运行期 guard 用）。
         *
         * <p>{@code waitForFunction} 仅存在于 {@link Page} 上：Page 直接取 context。取不到
         * （测试替身 / 非受管对象）返回 null ⇒ guard 跳过（fail-open），不干扰正常路径。</p>
         */
        private static BrowserContext contextOf(Object target) {
            try {
                if (target instanceof Page p) {
                    return p.context();
                }
            } catch (RuntimeException e) {
                LOGGER.debug("[DelayGuardPageProxy] cannot resolve context for waitForFunction guard: {}", e.toString());
            }
            return null;
        }
    }
}
