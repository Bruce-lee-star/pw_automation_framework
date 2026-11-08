package com.hsbc.cmb.hk.dbb.automation.framework.agent.execution;

/**
 * {@link StepPlanActionSink} 的框架级 SPI 供给方（零钩子自动装配 seam）。
 *
 * <p>生成的 BDD Steps 在<b>字段初始化期</b>就调用 {@link StepPlanActionSinks#requireCurrent()}，
 * 这个时点可能早于 Cucumber 的 {@code TestCaseStarted}、也早于任何业务 {@code @Before}。
 * 因此单靠生命周期监听器 {@code setCurrent} 并不够稳；本 SPI 让 {@code requireCurrent()}
 * 在未绑定时按线程惰性创建默认实现，业务侧无需任何钩子即可执行生成 case。</p>
 *
 * <p>实现由 Web 层经 {@code META-INF/services/} 注册。多个实现时取第一个可加载的；
 * 单个实现加载失败（含 {@code ServiceConfigurationError} 这类 {@link Error}）不得拖垮整个场景，
 * 由 {@link StepPlanActionSinks} 降级为清晰的未绑定异常。</p>
 *
 * <p>实现约定：{@link #create()} 必须<b>惰性</b>——不得在此刻获取 Page / 启动浏览器，
 * 因为登录与场景切换会重建 Context/Page；返回的 sink 应持有 {@code Supplier<Page>}。</p>
 */
@FunctionalInterface
public interface StepPlanActionSinkProvider {

    /** 为当前场景线程创建一个新的 sink 实例；不可返回 null。 */
    StepPlanActionSink create();
}
