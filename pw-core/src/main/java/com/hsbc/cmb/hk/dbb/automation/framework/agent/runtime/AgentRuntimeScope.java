package com.hsbc.cmb.hk.dbb.automation.framework.agent.runtime;

import java.util.Objects;

/**
 * 一次授权执行的不可变作用域信息（T5）：随生命周期钩子传给四个运行时角色。
 *
 * <p>只承载 pw-core 可表达的标识，<b>不含</b>任何 Playwright / BrowserContext / 业务会话对象——
 * 后者由下游适配器按 {@link #sessionKey()} / {@link #targetUrl()} 自行解析，保证 core 零泄漏。</p>
 *
 * @param planId     计划标识（{@code StepPlan.planId()}，仅用于诊断，可为 {@code null}）
 * @param targetUrl  本次执行目标（资产 pageRef / 靶场 URL）
 * @param sessionKey 登录会话身份（{@code env + "_" + username}）；无登录态为 {@code null}
 */
public record AgentRuntimeScope(String planId, String targetUrl, String sessionKey) {

    public AgentRuntimeScope {
        Objects.requireNonNull(targetUrl, "targetUrl");
    }

    /** 便捷工厂：无计划标识、无登录会话。 */
    public static AgentRuntimeScope of(String targetUrl) {
        return new AgentRuntimeScope(null, targetUrl, null);
    }

    /** 便捷工厂：带登录会话身份。 */
    public static AgentRuntimeScope of(String planId, String targetUrl, String sessionKey) {
        return new AgentRuntimeScope(planId, targetUrl, sessionKey);
    }
}
