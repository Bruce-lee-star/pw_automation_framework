package com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen.locator.candidate;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.asset.StepPlanApprovalBinding;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.Command;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.LocatorSpec;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.StepPlan;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.VerifiableStepPlan;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.scope.ParamScope;

import org.junit.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

/**
 * T8 验收「定位器回注域原有 {@link ApprovalGrant#covers} 行为零回归」+ 两套摘要语义边界锁定。
 *
 * <p>定位器回注域摘要（{@code locator-v1}，{@link ApprovalGrant#planDigestOf}）与
 * StepPlan 执行域摘要（{@code stepplan-v1}，{@link StepPlan#planDigest()}）覆盖域不同、
 * 互不派生。本测试证明：① {@code covers()} 的原有「摘要一致 / 被批准内容已变更」判定逐字不变；
 * ② 拿执行域摘要去回注定位器会被 {@code covers()} 拒绝（两套体系不可混用）；
 * ③ T8 引入的 {@link StepPlanApprovalBinding} 反算出的正是执行域摘要，不参与定位器域判定。</p>
 */
public class ApprovalDigestDomainBoundaryTest {

    private static final String ORIGIN = "https://uat.example.com";
    private static final String APP_VERSION = "APP-1.0";
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    /** 与生产流水线 / 执行器同一 Jackson 配置。 */
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .setVisibility(PropertyAccessor.FIELD, JsonAutoDetect.Visibility.ANY)
            .setVisibility(PropertyAccessor.GETTER, JsonAutoDetect.Visibility.NONE);

    private static LocatorCandidate candidate() {
        return LocatorCandidate.of("login.submit", LocatorSpec.role("button", "Log on"))
                .withExpectedRole("button");
    }

    /** 一个可计算摘要的合法 StepPlan（stepScopes 与 steps 等长）。 */
    private static StepPlan stepPlan(String buttonName) {
        Command click = new Command(Command.ActionType.CLICK,
                LocatorSpec.role("button", buttonName), Map.of());
        return new StepPlan(1, "plan-x", List.of(click), ParamScope.EMPTY,
                List.of(ParamScope.EMPTY));
    }

    /** T8 绑定类从落库载体 JSON 反算的执行域摘要。 */
    private static String executionDomainDigest(StepPlan plan) {
        try {
            String json = MAPPER.writeValueAsString(new VerifiableStepPlan(plan, ORIGIN));
            return StepPlanApprovalBinding.digestOf(json);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static ApprovalGrant grantFor(String digest) {
        return ApprovalGrant.issue("cand-1", RiskTier.MEDIUM, digest, ORIGIN, APP_VERSION,
                List.of("lead"), "CHG-1", NOW, 7);
    }

    @Test
    public void coversKeepsOriginalBehaviorForLocatorDomain() {
        String locatorDigest = ApprovalGrant.planDigestOf(candidate());
        ApprovalGrant grant = grantFor(locatorDigest);

        assertTrue("原有：摘要一致 + origin/版本一致 + 未过期 → 放行",
                grant.covers(ORIGIN, APP_VERSION, locatorDigest, NOW));
        assertFalse("原有：计划摘要不匹配 → 拒绝",
                grant.covers(ORIGIN, APP_VERSION, "0000000000000000", NOW));
        assertTrue("原有：不匹配原因须含『被批准的内容已变更』",
                grant.describeMismatch(ORIGIN, APP_VERSION, "0000000000000000", NOW)
                        .contains("被批准的内容已变更"));
        assertFalse("原有：过期 → 拒绝",
                grant.covers(ORIGIN, APP_VERSION, locatorDigest, NOW.plusSeconds(8 * 24 * 3600L)));
        assertFalse("原有：origin 不匹配 → 拒绝",
                grant.covers("https://prod.example.com", APP_VERSION, locatorDigest, NOW));
        assertFalse("原有：应用版本不匹配 → 拒绝",
                grant.covers(ORIGIN, "APP-2.0", locatorDigest, NOW));
    }

    @Test
    public void executionDomainDigestCannotSatisfyLocatorGrant() {
        String locatorDigest = ApprovalGrant.planDigestOf(candidate());
        String execDigest = executionDomainDigest(stepPlan("Log on"));
        assertNotEquals("两套摘要体系产出不同指纹", locatorDigest, execDigest);

        ApprovalGrant grant = grantFor(locatorDigest);
        assertFalse("拿 StepPlan 执行域摘要去回注定位器必须被拒",
                grant.covers(ORIGIN, APP_VERSION, execDigest, NOW));
        assertEquals("T8 未改变 grant 绑定的定位器域摘要", locatorDigest, grant.planDigest());
    }

    @Test
    public void stepPlanDigestRemainsStableAndContentBound() {
        StepPlan p1 = stepPlan("Log on");
        StepPlan p2 = stepPlan("Cancel");

        assertEquals("T8 绑定类反算的正是 StepPlan 执行域摘要",
                p1.planDigest(), executionDomainDigest(p1));
        assertNotEquals("执行域摘要随内容变化", p1.planDigest(), p2.planDigest());
        assertNotEquals("执行域摘要与定位器域摘要不相等",
                ApprovalGrant.planDigestOf(candidate()), p1.planDigest());
    }
}
