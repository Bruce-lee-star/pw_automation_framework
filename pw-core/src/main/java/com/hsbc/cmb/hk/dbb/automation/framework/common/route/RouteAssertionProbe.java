package com.hsbc.cmb.hk.dbb.automation.framework.common.route;

import java.util.List;

/**
 * Route 断言失败探针 SPI（核心层定义，web 侧消费，路由模块实现）。
 *
 * <p>对齐 {@link RouteLifecycle} 的解耦模式：pw-route 在类加载时实现并自注册到
 * {@link RouteAssertionRegistry}；web 侧（PlaywrightListener 的步骤/用例收尾）只依赖本接口
 * 调用 {@link #drainAndResolveFailures()}，编译期不触碰 route 模块，无循环依赖。
 *
 * <p>消费语义（幂等）：本方法一次性取走当前全部已定案的断言失败并清空；再次调用返回空列表。
 * 因此"步骤结束抛 AssertionError + testFinished 兜底标记"两条路径天然防重，无需额外的防重入标志。
 *
 * <p>定案定义（对齐 {@code MonitorSink}）：收到响应且（无 body 断言，或 body 断言已在调用线程就地
 * 评估完成）→ 定案；或超过 monitor 超时窗口仍未收到响应 → 定案为超时失败。未定案的请求
 * 留在观测队列继续等待（timeout 窗口语义），不会在步骤结束时被误判。
 */
public interface RouteAssertionProbe {

    /**
     * 取走全部已定案的断言失败（消费式，幂等）。
     *
     * @return 失败快照列表；无失败时为空列表（永不返回 null）
     */
    List<RouteAssertionFailure> drainAndResolveFailures();

    /**
     * 只取走<b>指定用例上下文</b>已定案的断言失败（消费式，幂等）。
     *
     * <p><b>并行隔离契约</b>：{@code context} 为当前 case 的 {@code BrowserContext} 实例
     * （core 不依赖 Playwright，故以 {@link Object} 传递）。实现必须只消费该 context 对应的
     * runtime，<b>不得遍历全部 runtime</b>——否则并行执行时 A case 会取走 B case 的失败，
     * 造成「无失败的用例被判红 / 真正失败的用例不判红」的串味。
     *
     * <p>默认实现委托无参版本，仅为兼容未升级的探针实现（此时退化为全局消费）；
     * 框架自带实现必须覆写本方法以提供真正的隔离。</p>
     *
     * @param context 当前 case 的 BrowserContext
     * @return 该上下文的失败快照列表；无失败时为空列表（永不返回 null）
     */
    default List<RouteAssertionFailure> drainAndResolveFailures(Object context) {
        return drainAndResolveFailures();
    }
}
