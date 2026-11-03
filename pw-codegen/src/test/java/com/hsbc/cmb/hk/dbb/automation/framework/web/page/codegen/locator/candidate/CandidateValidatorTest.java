package com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen.locator.candidate;

import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.LocatorSpec;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * {@link CandidateValidator} 契约测试：五条规则各自的失败面 + fail-closed 默认值。
 */
public class CandidateValidatorTest {

    private final CandidateValidator validator = new CandidateValidator();

    private static LocatorCandidate buttonCandidate() {
        return LocatorCandidate.of("login.submit", LocatorSpec.role("button", "Submit"))
                .withExpectedRole("button")
                .withSource("recorder");
    }

    private static CandidateEvidence.Builder goodEvidence() {
        return CandidateEvidence.builder()
                .matches(1)
                .visible(true)
                .enabled(true)
                .textMatched(true)
                .businessAssertion("提交后出现成功提示")
                .assertionPassed(true)
                .detail("trace#42");
    }

    @Test
    public void all_rules_pass_means_validated_not_production() {
        CandidateValidator.Validation v = validator.validate(buttonCandidate(), goodEvidence().build());

        assertTrue(v.passed());
        assertEquals("五条规则都要有结论", 5, v.findings().size());
        assertTrue(v.summary().contains("PASS"));
    }

    @Test
    public void semantic_mismatch_between_expected_role_and_css_is_rejected() {
        LocatorCandidate cssCandidate = LocatorCandidate
                .of("login.submit", LocatorSpec.css("div:nth-child(3) > button"))
                .withExpectedRole("button");

        CandidateValidator.Validation v = validator.validate(cssCandidate, goodEvidence().build());

        assertFalse(v.passed());
        assertTrue(failureDetail(v, CandidateValidator.RULE_TARGET_SEMANTICS).contains("不得用结构定位顶替语义定位"));
    }

    @Test
    public void role_mismatch_is_rejected() {
        LocatorCandidate link = LocatorCandidate.of("login.submit", LocatorSpec.role("link", "Submit"))
                .withExpectedRole("button");

        assertFalse(validator.validate(link, goodEvidence().build()).passed());
    }

    @Test
    public void text_mismatch_is_rejected() {
        CandidateValidator.Validation v = validator.validate(buttonCandidate(),
                goodEvidence().textMatched(false).build());

        assertFalse(v.passed());
        assertTrue(failureDetail(v, CandidateValidator.RULE_TARGET_SEMANTICS).contains("可访问名/文案"));
    }

    @Test
    public void zero_or_multiple_matches_are_rejected() {
        CandidateValidator.Validation zero = validator.validate(buttonCandidate(),
                goodEvidence().matches(0).build());
        CandidateValidator.Validation two = validator.validate(buttonCandidate(),
                goodEvidence().matches(2).build());

        assertFalse(zero.passed());
        assertTrue(failureDetail(zero, CandidateValidator.RULE_UNIQUENESS).contains("命中 0 个元素"));
        assertFalse(two.passed());
        assertTrue(failureDetail(two, CandidateValidator.RULE_UNIQUENESS).contains("命中 2 个元素"));
    }

    @Test
    public void missing_match_count_is_fail_closed() {
        CandidateValidator.Validation v = validator.validate(buttonCandidate(),
                CandidateEvidence.builder().visible(true).enabled(true).textMatched(true)
                        .businessAssertion("ok").assertionPassed(true).build());

        assertFalse(v.passed());
        assertTrue(failureDetail(v, CandidateValidator.RULE_UNIQUENESS).contains("未观测命中数"));
    }

    @Test
    public void invisible_or_disabled_elements_are_rejected() {
        CandidateValidator.Validation invisible = validator.validate(buttonCandidate(),
                goodEvidence().visible(false).build());
        CandidateValidator.Validation disabled = validator.validate(buttonCandidate(),
                goodEvidence().enabled(false).build());

        assertFalse(invisible.passed());
        assertTrue(failureDetail(invisible, CandidateValidator.RULE_VISIBILITY).contains("不可见"));
        assertFalse(disabled.passed());
        assertTrue(failureDetail(disabled, CandidateValidator.RULE_ACTIONABILITY).contains("不可操作"));
    }

    @Test
    public void business_assertion_is_mandatory() {
        CandidateValidator.Validation missing = validator.validate(buttonCandidate(),
                CandidateEvidence.builder().matches(1).visible(true).enabled(true).textMatched(true).build());
        CandidateValidator.Validation notRun = validator.validate(buttonCandidate(),
                CandidateEvidence.builder().matches(1).visible(true).enabled(true).textMatched(true)
                        .businessAssertion("提交后出现成功提示").build());
        CandidateValidator.Validation failed = validator.validate(buttonCandidate(),
                goodEvidence().assertionPassed(false).build());

        assertFalse(missing.passed());
        assertTrue(failureDetail(missing, CandidateValidator.RULE_BUSINESS_ASSERTION).contains("未提供业务断言"));
        assertFalse(notRun.passed());
        assertTrue(failureDetail(notRun, CandidateValidator.RULE_BUSINESS_ASSERTION).contains("未执行"));
        assertFalse(failed.passed());
        assertTrue(failureDetail(failed, CandidateValidator.RULE_BUSINESS_ASSERTION).contains("失败"));
    }

    @Test
    public void all_failures_are_reported_together() {
        CandidateValidator.Validation v = validator.validate(buttonCandidate(),
                CandidateEvidence.builder().matches(3).visible(false).enabled(false).textMatched(false).build());

        assertEquals("五条规则同时失败（含未提供业务断言）", 5, v.failures().size());
        assertTrue(v.summary().startsWith("FAIL"));
    }

    @Test
    public void missing_evidence_is_rejected_outright() {
        try {
            validator.validate(buttonCandidate(), null);
            fail("无证据不得验证");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("evidence"));
        }
    }

    private static String failureDetail(CandidateValidator.Validation v, String ruleId) {
        return v.failures().stream()
                .filter(f -> f.ruleId().equals(ruleId))
                .map(CandidateValidator.Finding::detail)
                .findFirst()
                .orElseThrow(() -> new AssertionError("没有该规则的失败结论: " + ruleId));
    }
}
