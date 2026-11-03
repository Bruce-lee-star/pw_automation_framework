package com.hsbc.cmb.hk.dbb.automation.framework.agent.observation;

import java.util.Collections;
import java.util.List;

/**
 * 页面观测包（设计 §4.6 ② 页面观测 / R1/R7 数据最小化）。
 *
 * <p>只读、脱敏后的页面视图：元素清单 + 受限网络摘要。由 {@link PageObservationProvider}
 * 在隔离靶场采集（pw-web-ui 提供 Playwright 实现），本模块不依赖任何浏览器类型。</p>
 *
 * @param url            观测页面 URL（仅作溯源，不发送正文）
 * @param pageRef        页面指纹（如路由 + 版本）
 * @param elements       观测到的元素列表（白名单字段）
 * @param networkSummary 受限网络摘要（可选，仅记录已批准的目的地/状态码，不含敏感内容）
 */
public record PageObservation(String url, String pageRef,
                              List<ObservedElement> elements, String networkSummary) {

    public PageObservation {
        elements = elements == null ? Collections.emptyList() : List.copyOf(elements);
    }

    public List<ObservedElement> elements() {
        return elements;
    }
}
