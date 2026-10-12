package com.hsbc.cmb.hk.dbb.automation.framework.web.page.recording;

import com.microsoft.playwright.APIRequest;
import com.microsoft.playwright.APIResponse;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.ElementHandle;
import com.microsoft.playwright.Frame;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Request;
import com.microsoft.playwright.Response;
import com.hsbc.cmb.hk.dbb.automation.framework.common.route.RouteLifecycle;
import com.hsbc.cmb.hk.dbb.automation.framework.common.route.RouteLifecycleRegistry;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.PlaywrightManager;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.List;

import com.hsbc.cmb.hk.dbb.automation.framework.web.exceptions.BrowserException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * framework-internal：Playwright 原生操作录制装饰器（Layer A）。
 *
 * <p>JDK 动态代理实现 {@link Page}（及递归包装的 {@link Locator}/{@link Frame}/{@link ElementHandle}/
 * {@link Response}/{@link Request}/{@link APIResponse}/{@link APIRequest}），对原生操作
 * （navigate / click / fill / getByRole / locator(...) 等）在委托真实受管 Page 前刷新待报告 API 数据并录制动作，
 * 使业务 Page 经持有的装饰 Page 透明获得 Serenity 录制，无需继承庞大的 {@code BasePage} 基类。
 *
 * <p><b>边界</b>（见设计 15）：
 * <ul>
 *   <li>{@code onXxx} 监听注册直接委托、不包装回调对象（D3），避免干扰 Playwright 事件；</li>
 *   <li>数据类返回值（{@code BoundingBox}/{@code Cookie} 等）不包装（R3）；</li>
 *   <li>已是本代理（{@code RecordingHandler}）则跳过递归，防代理链过深（R2）；</li>
 *   <li>{@code equals/hashCode/toString} 等 {@link Object} 方法直接委托真实对象（R1）；</li>
 *   <li>录制关闭或入参为空时 {@link #wrap(Page)} 返回裸 Page，零开销（D4）。</li>
 *   <li>{@code close()}（含 {@code AutoCloseable.close()} 与 {@code close(CloseOptions)}）被语义化拒绝（D7）：
 *       受管 Page 生命周期由框架托管，业务不得经装饰 Page 关闭，否则破坏场景生命周期与并发隔离。</li>
 * </ul>
 *
 * <p>业务 Page 录制通过持有的装饰 Page 透明获得，录制失败不得影响业务（降级由 {@link SerenityRecorder} 处理）。
 */
public final class RecordingPageProxy {

    private static final Logger LOGGER = LoggerFactory.getLogger(RecordingPageProxy.class);

    /** 需要递归包装的 Playwright 接口集合（R3：仅接口，数据类不包装）。 */
    private static final List<Class<?>> RECORDABLE_INTERFACES = Arrays.asList(
            Page.class, Locator.class, Frame.class, ElementHandle.class,
            Response.class, Request.class, APIResponse.class, APIRequest.class);

    private static final int MAX_DETAIL_LEN = 256;

    private RecordingPageProxy() {
    }

    /**
     * 解开本装饰代理，返回底层真实 {@link Page}（非本代理时原样返回）。
     *
     * <p>供「按页面身份比较」的场景使用（路由 PAGE 级规则筛选、采集归属判定等）：
     * 装饰代理与原生 Page 指向同一页面，必须能被判定为同一页。
     */
    public static Page unwrap(Page page) {
        Object unwrapped =
                com.hsbc.cmb.hk.dbb.automation.framework.common.route.PageRefs.unwrap(page);
        return unwrapped instanceof Page real ? real : page;
    }

    /**
     * 包装受管 Page。录制关闭或入参为空时返回裸 Page（零开销，D4）。
     */
    public static Page wrap(Page real) {
        if (real == null || !SerenityRecorder.isEnabled()) {
            return real;
        }
        if (PlaywrightManager.getProvider().isTestDouble()) {
            return real; // D6：测试替身（mock）不包装，避免污染测试
        }
        return (Page) Proxy.newProxyInstance(
                RecordingPageProxy.class.getClassLoader(),
                new Class<?>[]{Page.class},
                new RecordingHandler(real));
    }

    /**
     * 业务 Page 统一包装 seam（§8.4）：按录制状态二选一，使 DELAY guard 在两条路径一致生效。
     *
     * <ul>
     *   <li>测试替身（mock）/ null → 裸 Page（零开销，D6）；</li>
     *   <li>录制开启 → {@link #wrap}（录制装饰 + DELAY guard）；</li>
     *   <li>录制关闭 → {@link DelayGuardPageProxy#wrap}（仅 DELAY guard，零录制）。</li>
     * </ul>
     *
     * <p>真实受管 Page 仍由 {@code PlaywrightManager.getPage()} 返回并存于 {@code PAGE_KEY}，
     * 本 seam 只在业务取页边界加一层薄代理，不改变框架内部收尾 / 页面身份逻辑
     * （{@code PageRefs.unwrap} 经两个代理的 {@code target()} 访问器正确归一）。</p>
     */
    public static Page wrapForBusiness(Page real) {
        if (real == null || PlaywrightManager.getProvider().isTestDouble()) {
            return real;
        }
        if (SerenityRecorder.isEnabled()) {
            return wrap(real);
        }
        return DelayGuardPageProxy.wrap(real);
    }

    /**
     * 对可录制返回值递归包装（R2）：null / 非已知接口 / 已是本代理 / 录制关闭 → 原样返回。
     */
    @SuppressWarnings("unchecked")
    public static <T> T wrapIfRecordable(Object ret, Class<T> iface) {
        if (ret == null || !SerenityRecorder.isEnabled()) {
            return (T) ret;
        }
        if (PlaywrightManager.getProvider().isTestDouble()) {
            return (T) ret; // D6：测试替身不包装
        }
        if (Proxy.isProxyClass(ret.getClass())) {
            try {
                if (Proxy.getInvocationHandler(ret) instanceof RecordingHandler) {
                    return (T) ret; // 已是本代理，跳过（R2 防链过深）
                }
            } catch (IllegalArgumentException e) {
                // 防御路径（理论上被 isProxyClass 前置守卫排除）：非 JDK 代理则继续按接口包装。
                // 按 D7-3「禁止静默吞异常」记录 WARN —— 该分支不可达，故不会产生日志噪声。
                LOGGER.warn("[RecordingPageProxy] Returning object is not a JDK proxy despite isProxyClass() check; "
                        + "wrapping by interface instead: {}", e.toString());
            }
        }
        if (iface != null && RECORDABLE_INTERFACES.contains(iface) && iface.isInstance(ret)) {
            return (T) Proxy.newProxyInstance(
                    RecordingPageProxy.class.getClassLoader(),
                    new Class<?>[]{iface},
                    new RecordingHandler(ret));
        }
        return (T) ret;
    }

    // ==================== Handler ====================

    private static final class RecordingHandler implements InvocationHandler {
        private final Object target;

        RecordingHandler(Object target) {
            this.target = target;
        }

        /**
         * 被委托的真实对象（访问器约定，见核心层 {@code PageRefs}）。
         *
         * <p><b>必须保留</b>：路由 PAGE 级规则按对象同一性筛选适用页面，若无法从本代理取回真实
         * Page，则「同一页面的代理」会被判为不适用 → MOCK / MODIFY / DELAY 静默失效。
         */
        public Object target() {
            return target;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            // R1: Object 方法直接委托真实对象，不做录制
            if (method.getDeclaringClass() == Object.class) {
                return method.invoke(target, args);
            }
            // D7: 受管 Page 生命周期由框架托管，禁止业务经装饰 Page 调用 close()（含 AutoCloseable.close() 与
            // close(CloseOptions)，以及 try-with-resources），否则会关闭框架受管 Page、破坏场景生命周期与并发隔离。
            // 语义化拒绝（不静默放行、不委托真实对象）并记录告警以便审计；线程安全（无共享可变状态）。
            if ("close".equals(method.getName())) {
                LOGGER.warn("[RecordingPageProxy] Business attempted to close the framework-managed Page ({}); request denied. "
                        + "Release the page/context via the framework close API instead (e.g. PlaywrightManager.closePage()/closeContext()).",
                        method);
                throw new BrowserException(
                        "The managed Page lifecycle is owned by the framework (PlaywrightManager / BasePage page and context close paths). "
                        + "Calling close() directly on a RecordingPageProxy-decorated Page is forbidden (including AutoCloseable.close() and "
                        + "close(CloseOptions), as well as try-with-resources). To release a page or context, use the framework-provided "
                        + "close API (e.g. PlaywrightManager.closePage()/closeContext()); do not close the managed Page directly, "
                        + "otherwise scenario lifecycle and concurrency isolation will break.");
            }
            // D3: onXxx 监听注册直接委托，不包装回调对象，避免干扰 Playwright 事件
            if (method.getName().startsWith("on")) {
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
            // Layer A 录制：刷新待报告 API 数据 + 录制动作，再委托真实对象（flush 在 op 前，与 SerenityRecorder.record 一致）
            SerenityRecorder.recordNative(method.getName(), summarize(args), null);
            Object result;
            try {
                result = method.invoke(target, args);
            } catch (InvocationTargetException e) {
                throw e.getCause();
            } catch (IllegalAccessException e) {
                throw new RuntimeException("Failed to invoke target method: " + method.getName(), e);
            }
            // 递归包装可录制的返回值
            return wrapIfRecordable(result, method.getReturnType());
        }

        /**
         * 从被包装的真实对象取回其 {@link BrowserContext}（运行期 guard 用）。
         *
         * <p>waitForFunction 仅存在于 {@link Page} / {@link Frame} 上：Page 直接取 context；Frame 经
         * {@code page().context()} 取。取不到（测试替身 / 非受管对象）返回 null ⇒ guard 跳过（fail-open），
         * 不干扰正常路径。</p>
         */
        private static BrowserContext contextOf(Object target) {
            try {
                if (target instanceof Page p) {
                    return p.context();
                }
                if (target instanceof Frame f) {
                    return f.page().context();
                }
            } catch (RuntimeException e) {
                LOGGER.debug("[RecordingPageProxy] cannot resolve context for waitForFunction guard: {}", e.toString());
            }
            return null;
        }

        private static Object summarize(Object[] args) {
            if (args == null || args.length == 0) {
                return "executed";
            }
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < args.length; i++) {
                if (i > 0) {
                    sb.append(", ");
                }
                sb.append(args[i]);
                if (sb.length() >= MAX_DETAIL_LEN) {
                    break;
                }
            }
            if (sb.length() > MAX_DETAIL_LEN) {
                sb.setLength(MAX_DETAIL_LEN);
                sb.append("...");
            }
            return sb.toString();
        }
    }
}
