package com.hsbc.cmb.hk.dbb.automation.framework.agent.observation;

/**
 * 页面观测 SPI（设计 §3.1.3 / §4.6 ②）。
 *
 * <p>在隔离靶场采集<b>脱敏、只读</b>的 {@link PageObservation}（白名单字段，R1/R7）。
 * 真实实现位于 pw-web-ui（基于 Playwright，优先读取元素 {@code data-i18n} 作为跨语言稳定的 NLS key）；
 * 本模块仅定义契约，不含浏览器依赖。LLM 仅消费本观测，永不持有浏览器原语（R6）。</p>
 */
public interface PageObservationProvider {

    /**
     * 采集页面观测。
     * @param pageRef 目标页面引用（路由/指纹）
     * @return 脱敏观测包
     */
    PageObservation observe(String pageRef);
}
