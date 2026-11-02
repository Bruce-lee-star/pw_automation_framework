package com.hsbc.cmb.hk.dbb.automation.framework.route.dsl;

import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 规则会话 —— 在一次场景 / 步骤内<b>连续注册</b>多条路由规则，结束时一次性精准注销。
 *
 * <p>设计动机：{@link RouteDsl#register()} / {@link RouteDsl#start()} 返回 {@link AutoCloseable} 句柄，
 * 供"只注销本批规则"的精准控制；但当规则生命周期 = 整个场景时，业务层被迫维护
 * {@code List<AutoCloseable>} 并在 teardown 中逐个关闭，否则静态分析会报
 * {@code OBL_UNSATISFIED_OBLIGATION}。会话把每条规则返回的句柄<b>自动汇总</b>，
 * 业务层用 try-with-resources 或显式 {@link #close()} 一次性精准清理，无需手工收集。</p>
 *
 * <p><b>遗忘关闭也安全</b>：即便业务层从未调用 {@link #close()}，所属 {@code BrowserContext} 关闭时
 * runtime 会整体回收全部规则（与直接在 {@code RouteDsl.on(ctx)...register()} 后忽略返回值同款兜底），
 * 会话本身不持有任何跨上下文资源，<b>不存在泄漏</b>。因此此处<b>不</b>再注册 {@code context.onClose}——
 * runtime 已在 context 关闭时整体回收，重复挂钩只会增加监听器。</p>
 *
 * <p>用法：
 * <pre>{@code
 * try (RouteSession session = RouteDsl.session(context)) {
 *     session.on().api("/api/users/**").onlyApi().monitor().expectStatus(200).register();
 *     session.on().api("/api/login").mock().status(200).body("{}").register();
 *     session.on().api("/api/pay/**").delay(3).done().start();
 *     // ... 业务步骤 ...
 * } // 退出作用域即精准注销本会话注册的整批规则
 * }</pre>
 *
 * <p>若业务层连 try-with-resources 都不想写，直接用 {@code RouteDsl.on(ctx)...register()} 即可 ——
 * 忽略返回值时不会泄漏（context 关闭即整体回收），但将无法"只精准注销本批"；
 * 需要该能力时 {@link RouteSession} 是更省心的入口。</p>
 */
public final class RouteSession implements AutoCloseable {

    private static final Logger LOGGER = LoggerFactory.getLogger(RouteSession.class);

    private final BrowserContext context;
    private final RouteDsl dsl;
    private final List<AutoCloseable> handles = new ArrayList<>();
    private boolean closed = false;

    RouteSession(BrowserContext context) {
        this.context = Objects.requireNonNull(context, "context");
        // 把每条规则返回的句柄汇集到本会话，使其可被统一关闭；
        // 不注册 context.onClose —— context 关闭时 runtime 已整体回收，无需重复挂钩。
        this.dsl = new RouteDsl(context, this.handles::add);
    }

    /**
     * 取会话内的 DSL 入口；其 {@code register()}/{@code start()} 返回的句柄会被会话自动收集。
     * 业务层可（也应）忽略返回值，由会话统一在 {@link #close()} 时注销。
     */
    public RouteDsl on() {
        return dsl;
    }

    /**
     * 一次性注销本会话注册的全部规则（幂等）。context 已关闭时各句柄为 no-op。
     *
     * <p>与 {@link RouteDsl#clear(BrowserContext)} 的区别：本方法只清<b>本会话</b>注册的这一批，
     * 不影响同一 context 上其它来源注册的规则；context 仍存活（不重建 runtime）。</p>
     */
    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        for (AutoCloseable h : handles) {
            try {
                h.close();
            } catch (Exception e) {
                LOGGER.trace("[Route] session handle close ignored: {}", e.toString());
            }
        }
        handles.clear();
    }
}
