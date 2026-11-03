package com.hsbc.cmb.hk.dbb.automation.framework.agent.observation;

import java.util.List;

/**
 * 人工/录制页面观测提供方（确定性、零浏览器）。
 *
 * <p>用于测试与「录制骨架/手写资产」韧性降级路径（§4.6 韧性降级）：直接持有预设的
 * {@link PageObservation}，{@link #observe(String)} 忽略入参返回该观测。</p>
 */
public final class ManualPageObservationProvider implements PageObservationProvider {

    private final PageObservation observation;

    public ManualPageObservationProvider(PageObservation observation) {
        this.observation = observation;
    }

    public ManualPageObservationProvider(String url, String pageRef, List<ObservedElement> elements) {
        this(new PageObservation(url, pageRef, elements, "manual"));
    }

    @Override
    public PageObservation observe(String pageRef) {
        return observation;
    }
}
