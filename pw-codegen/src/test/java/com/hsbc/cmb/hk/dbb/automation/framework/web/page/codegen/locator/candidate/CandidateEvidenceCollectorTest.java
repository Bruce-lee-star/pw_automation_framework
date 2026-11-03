package com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen.locator.candidate;

import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.LocatorSpec;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * 证据组装规则：文案必须<b>真的比一遍</b>，缺证据一律判不一致（fail-closed）。
 */
public class CandidateEvidenceCollectorTest {

    private static LocatorCandidate button(String name) {
        return LocatorCandidate.of("login.submit", LocatorSpec.role("button", name))
                .withExpectedRole("button");
    }

    private static StructuralObservation observed(String expected, String actual) {
        return new StructuralObservation(1, true, true, expected, actual, "tag=button");
    }

    @Test
    public void exact_name_match_passes() {
        CandidateEvidence e = CandidateEvidenceCollector.collect(
                button("Log on"), observed("Log on", "Log on"), "点击后出现欢迎页", true, null);

        assertTrue("文案一致", e.textMatched());
        assertEquals(1, e.matches());
        assertTrue(e.visible());
        assertTrue(e.enabled());
        assertEquals(Boolean.TRUE, e.assertionPassed());
        assertTrue(e.detail().contains("文案一致"));
    }

    @Test
    public void name_mismatch_fails_even_if_unique_hit() {
        // 唯一命中但文案不同：说明命中的是别的元素，这正是 testid/css 复用最容易踩的坑
        CandidateEvidence e = CandidateEvidenceCollector.collect(
                button("Log on"), observed("Log on", "Log in"), "点击后出现欢迎页", true, null);

        assertFalse("唯一命中不等于文案一致", e.textMatched());
        assertTrue(e.detail().contains("不一致"));
    }

    @Test
    public void entities_and_whitespace_are_normalized() {
        CandidateEvidence e = CandidateEvidenceCollector.collect(
                button("Privacy & Security"),
                observed("Privacy &amp; Security", "  Privacy   &  Security "),
                "打开隐私页", true, null);

        assertTrue("实体与多余空白不应造成假不一致", e.textMatched());
    }

    @Test
    public void template_expected_name_matches_any_injected_value() {
        CandidateEvidence e = CandidateEvidenceCollector.collect(
                button("Welcome, {{user}}"),
                observed("Welcome, {{user}}", "Welcome, Alice"),
                "登录后显示欢迎语", true, null);

        assertTrue("模板值由页面注入，须按正则匹配", e.textMatched());
    }

    @Test
    public void template_does_not_match_when_shape_differs() {
        CandidateEvidence e = CandidateEvidenceCollector.collect(
                button("Welcome, {{user}}"),
                observed("Welcome, {{user}}", "Sign in"),
                "登录后显示欢迎语", true, null);

        assertFalse(e.textMatched());
    }

    @Test
    public void missing_expected_name_is_not_a_pass() {
        // role=button 不带 name：没有可比对的语义锚点，按 fail-closed 判不一致
        CandidateEvidence e = CandidateEvidenceCollector.collect(
                LocatorCandidate.of("login.submit", LocatorSpec.role("button", null)),
                observed(null, "Log on"), "点击后出现欢迎页", true, null);

        assertFalse("定位符未声明期望名时不得默认通过", e.textMatched());
        assertTrue(e.detail().contains("未声明"));
    }

    @Test
    public void unobserved_probe_yields_fail_closed_evidence() {
        CandidateEvidence e = CandidateEvidenceCollector.collect(
                button("Log on"), StructuralObservation.unobserved("页面未加载"),
                "点击后出现欢迎页", true, null);

        assertEquals(-1, e.matches());
        assertFalse(e.textMatched());
        assertFalse(e.visible());
        assertTrue("要保留探针失败原因便于排查", e.detail().contains("页面未加载"));
    }

    @Test
    public void null_observation_is_treated_as_unobserved() {
        CandidateEvidence e = CandidateEvidenceCollector.collect(
                button("Log on"), null, "点击后出现欢迎页", true, null);

        assertEquals(-1, e.matches());
        assertTrue(e.detail().contains("探针未返回观测结果"));
    }

    @Test
    public void assertion_not_run_stays_null_for_validator_to_reject() {
        CandidateEvidence e = CandidateEvidenceCollector.collect(
                button("Log on"), observed("Log on", "Log on"), "点击后出现欢迎页", null, "trace-7");

        assertNull("未执行不得伪造为通过", e.assertionPassed());
        assertTrue("trace 引用要进证据", e.detail().contains("trace=trace-7"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void null_candidate_is_rejected() {
        CandidateEvidenceCollector.collect(null, observed("a", "a"), "断言", true, null);
    }
}
