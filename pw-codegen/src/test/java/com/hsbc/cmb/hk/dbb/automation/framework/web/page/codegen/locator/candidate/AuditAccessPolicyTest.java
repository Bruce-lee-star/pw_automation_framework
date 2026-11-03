package com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen.locator.candidate;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * 审计读取侧鉴权决策逻辑（R9 访问控制 / fail-closed）。
 */
public class AuditAccessPolicyTest {

    @Test
    public void auditor_may_obtain_any_exposure() {
        assertAllowed(AuditReaderRole.AUDITOR, AuditExposure.FULL, AuditExposure.FULL);
        assertAllowed(AuditReaderRole.AUDITOR, AuditExposure.INTERNAL, AuditExposure.INTERNAL);
        assertAllowed(AuditReaderRole.AUDITOR, AuditExposure.SANITIZED, AuditExposure.SANITIZED);
    }

    @Test
    public void engineer_may_obtain_internal_or_sanitized_but_not_full() {
        assertAllowed(AuditReaderRole.ENGINEER, AuditExposure.INTERNAL, AuditExposure.INTERNAL);
        assertAllowed(AuditReaderRole.ENGINEER, AuditExposure.SANITIZED, AuditExposure.SANITIZED);
        assertDenied(AuditReaderRole.ENGINEER, AuditExposure.FULL);
    }

    @Test
    public void external_may_only_obtain_sanitized() {
        assertAllowed(AuditReaderRole.EXTERNAL, AuditExposure.SANITIZED, AuditExposure.SANITIZED);
        assertDenied(AuditReaderRole.EXTERNAL, AuditExposure.INTERNAL);
        assertDenied(AuditReaderRole.EXTERNAL, AuditExposure.FULL);
    }

    @Test
    public void null_role_is_treated_as_external_safe_default() {
        // 未断言身份 = 最小权限：只能 SANITIZED。
        assertAllowed((AuditReaderRole) null, AuditExposure.SANITIZED, AuditExposure.SANITIZED);
        assertDenied((AuditReaderRole) null, AuditExposure.INTERNAL);
        assertDenied((AuditReaderRole) null, AuditExposure.FULL);
    }

    @Test
    public void unknown_role_name_is_treated_as_external() {
        assertDeniedByName("bogus-role", AuditExposure.INTERNAL);
        assertDeniedByName("bogus-role", AuditExposure.FULL);
        assertAllowedByName("bogus-role", AuditExposure.SANITIZED, AuditExposure.SANITIZED);
    }

    @Test
    public void blank_or_null_role_name_parses_to_external() {
        assertEquals(AuditReaderRole.EXTERNAL, AuditReaderRole.parse(null));
        assertEquals(AuditReaderRole.EXTERNAL, AuditReaderRole.parse(""));
        assertEquals(AuditReaderRole.EXTERNAL, AuditReaderRole.parse("  "));
        assertEquals(AuditReaderRole.AUDITOR, AuditReaderRole.parse("auditor"));
        assertEquals(AuditReaderRole.ENGINEER, AuditReaderRole.parse("ENGINEER"));
    }

    @Test
    public void exposure_rank_ordering_is_sanitized_lt_internal_lt_full() {
        assertTrue(AuditExposure.SANITIZED.rank() < AuditExposure.INTERNAL.rank());
        assertTrue(AuditExposure.INTERNAL.rank() < AuditExposure.FULL.rank());
    }

    @Test
    public void denied_decision_carries_reason_and_no_exposure() {
        AuditAccessDecision d = AuditAccessPolicy.decide(AuditReaderRole.EXTERNAL, AuditExposure.FULL);
        assertFalse(d.allowed());
        assertTrue(d.reason().contains("EXTERNAL"));
        assertTrue(d.reason().contains("SANITIZED"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void null_requested_exposure_is_rejected() {
        AuditAccessPolicy.decide(AuditReaderRole.AUDITOR, null);
    }

    private static void assertAllowed(AuditReaderRole role, AuditExposure requested, AuditExposure expected) {
        AuditAccessDecision d = AuditAccessPolicy.decide(role, requested);
        assertTrue("应当允许 " + role + " 请求 " + requested, d.allowed());
        assertSame("生效级别应等于请求级别（不降级）", expected, d.exposure());
    }

    private static void assertDenied(AuditReaderRole role, AuditExposure requested) {
        AuditAccessDecision d = AuditAccessPolicy.decide(role, requested);
        assertFalse("应当拒绝 " + role + " 请求 " + requested, d.allowed());
    }

    private static void assertAllowedByName(String roleName, AuditExposure requested, AuditExposure expected) {
        AuditAccessDecision d = AuditAccessPolicy.decide(roleName, requested);
        assertTrue("应当允许 " + roleName + " 请求 " + requested, d.allowed());
        assertSame(expected, d.exposure());
    }

    private static void assertDeniedByName(String roleName, AuditExposure requested) {
        AuditAccessDecision d = AuditAccessPolicy.decide(roleName, requested);
        assertFalse("应当拒绝 " + roleName + " 请求 " + requested, d.allowed());
    }
}
