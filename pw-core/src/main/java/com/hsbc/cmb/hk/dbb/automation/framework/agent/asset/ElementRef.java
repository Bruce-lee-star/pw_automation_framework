package com.hsbc.cmb.hk.dbb.automation.framework.agent.asset;

import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.LocatorSpec;

import java.util.Collections;
import java.util.List;

/**
 * 元素引用（设计 §4.4 + §4.6 ①）。
 *
 * <p>{@code refId} 是<b>业务/需求语义稳定 id</b>（资产级主键，与需求映射），与运行期定位解耦；
 * {@code locatorCandidates} 是按 <b>Role-First 梯队</b>排序的定位候选
 * （{@link LocatorSpec.Strategy#ROLE} 优先，其次 testid / id / text / css），
 * 经验证/回退后才可信。{@code semanticRole} 回链业务用途。</p>
 */
public final class ElementRef {
    private final String refId;                           // 业务语义稳定 id（如 "amount_input"）
    private final List<LocatorSpec> locatorCandidates;     // Role-First 梯队，须经验证
    private final String semanticRole;                    // 业务语义（金额录入 / 提交 ...）

    public ElementRef(String refId, List<LocatorSpec> locatorCandidates, String semanticRole) {
        this.refId = refId;
        this.locatorCandidates = locatorCandidates == null ? Collections.emptyList() : locatorCandidates;
        this.semanticRole = semanticRole;
    }

    public String refId() { return refId; }
    public List<LocatorSpec> locatorCandidates() { return Collections.unmodifiableList(locatorCandidates); }
    public String semanticRole() { return semanticRole; }

    /** 第一梯队（角色优先）定位符；无候选时返回 NULL。 */
    public LocatorSpec primaryLocator() {
        return locatorCandidates.isEmpty() ? null : locatorCandidates.get(0);
    }
}
