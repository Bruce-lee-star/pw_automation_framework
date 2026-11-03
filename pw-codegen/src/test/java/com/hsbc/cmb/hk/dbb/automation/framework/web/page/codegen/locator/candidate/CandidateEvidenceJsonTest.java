package com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen.locator.candidate;

import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.LocatorSpec;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * 证据 JSON 的往返契约：自动取证落盘后，审批人重读必须看到同样的结论（含"未观测"与"未执行"）。
 */
public class CandidateEvidenceJsonTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void write_then_read_preserves_evidence() throws Exception {
        CandidateEvidence original = CandidateEvidenceCollector.collect(
                LocatorCandidate.of("login.submit", LocatorSpec.role("button", "Log on")),
                new StructuralObservation(1, true, true, "Log on", "Log on", "tag=button"),
                "点击后出现欢迎页", true, "trace-9");

        Path file = tmp.getRoot().toPath().resolve("evidence").resolve("cand-0001.json");
        CandidateEvidenceJson.write(file, original);

        assertTrue(Files.isRegularFile(file));
        CandidateEvidence back = CandidateEvidenceJson.read(file);
        assertEquals(1, back.matches());
        assertTrue(back.visible());
        assertTrue(back.enabled());
        assertTrue(back.textMatched());
        assertEquals(Boolean.TRUE, back.assertionPassed());
        assertEquals("点击后出现欢迎页", back.businessAssertion());
        assertEquals(original.detail(), back.detail());
    }

    @Test
    public void unobserved_and_unrun_survive_the_round_trip() throws Exception {
        CandidateEvidence original = CandidateEvidenceCollector.collect(
                LocatorCandidate.of("login.submit", LocatorSpec.role("button", "Log on")),
                StructuralObservation.unobserved("页面未加载"),
                "点击后出现欢迎页", null, null);

        Path file = tmp.getRoot().toPath().resolve("cand-0002.json");
        CandidateEvidenceJson.write(file, original);

        CandidateEvidence back = CandidateEvidenceJson.read(file);
        assertEquals("未观测必须落成 -1，不能被写成 0", -1, back.matches());
        assertNull("未执行不得变成通过或失败", back.assertionPassed());
        assertTrue(back.detail().contains("页面未加载"));
    }
}
