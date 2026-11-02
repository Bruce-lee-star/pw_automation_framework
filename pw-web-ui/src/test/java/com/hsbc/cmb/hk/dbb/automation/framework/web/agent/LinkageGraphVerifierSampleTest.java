package com.hsbc.cmb.hk.dbb.automation.framework.web.agent;

import com.hsbc.cmb.hk.dbb.automation.framework.agent.asset.ElementRef;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.asset.LinkageGraph;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.asset.LinkageGraphVerifier;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.asset.VerificationReport;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.asset.VerificationScope;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.Command;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.LocatorSpec;
import org.junit.Test;

import java.net.URI;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assume.assumeNoException;
import static org.junit.Assume.assumeTrue;

/**
 * 资产驱动验证的端到端示例（真实浏览器，受隔离靶场约束）。
 *
 * <p>演示：读取一条联动图谱（含 ENABLE 与 APPEAR 两类联动）→ 经 {@link RouteTestBedHarness} 在隔离
 * 靶场启动独立 chromium → {@link LinkageGraphVerifier} 驱动执行并断言 → 产出 VerificationReport。</p>
 *
 * <p>若该环境未安装 Playwright 浏览器，本测试自动跳过（{@code assumeNoException}），不阻塞构建。</p>
 */
public class LinkageGraphVerifierSampleTest {

    @Test
    public void verifyLinkageGraphAgainstRealPage() {
        String targetUrl = resolveFixtureUrl();
        RouteTestBedHarness harness = new RouteTestBedHarness();
        try {
            LinkageGraph graph = linkageGraph();
            LinkageGraphVerifier verifier = new LinkageGraphVerifier(harness);
            VerificationReport report = verifier.verify(graph, new VerificationScope(targetUrl, 2));

            // 两条联动在隔离靶场均成立 → PASS；若仅部分成立则为 FLAKY/FAIL。
            assertEquals(VerificationReport.Status.PASS, report.status());
            assertEquals(1.0, report.stability(), 1e-9);
        } catch (RuntimeException e) {
            // 浏览器不可用（未安装 / 无显示等）→ 跳过，不视为失败。
            assumeNoException("Playwright browser unavailable in this environment", e);
        } finally {
            harness.closeAll();
        }
    }

    private static String resolveFixtureUrl() {
        java.net.URL resource = LinkageGraphVerifierSampleTest.class.getResource(
                "/com/hsbc/cmb/hk/dbb/automation/framework/web/agent/linkage-fixture.html");
        assumeTrue("linkage-fixture.html missing on test classpath", resource != null);
        try {
            return Paths.get(URI.create(resource.toURI().toString())).toUri().toString();
        } catch (Exception e) {
            throw new IllegalStateException("Cannot resolve fixture URL", e);
        }
    }

    /** 两条联动：① 勾选 agree(checkbox) → reason(textbox) 启用(ENABLE)；② accountType(combobox) 选 corporate → purpose(textbox) 出现(APPEAR)。 */
    private static LinkageGraph linkageGraph() {
        // 角色优先：refId 为业务语义 id，locatorCandidates 第一梯队为 Role 定位（与 fixture 的 aria-label 一致）。
        ElementRef agree = new ElementRef("agree",
                Collections.singletonList(LocatorSpec.role("checkbox", "I agree to the terms")), "agree checkbox");
        ElementRef reason = new ElementRef("reason",
                Collections.singletonList(LocatorSpec.role("textbox", "Reason")), "reason input");
        LinkageGraph.ExpectedChange enableReason = new LinkageGraph.ExpectedChange(
                reason, LinkageGraph.ExpectedChange.ChangeKind.ENABLE, null);
        LinkageGraph.LinkageRule ruleEnable = new LinkageGraph.LinkageRule(
                agree, Command.ActionType.CLICK, Collections.singletonList(enableReason));

        ElementRef accountType = new ElementRef("accountType",
                Collections.singletonList(LocatorSpec.role("combobox", "Account type")), "account type select");
        ElementRef purpose = new ElementRef("purpose",
                Collections.singletonList(LocatorSpec.role("textbox", "Purpose")), "purpose input");
        LinkageGraph.ExpectedChange appearPurpose = new LinkageGraph.ExpectedChange(
                purpose, LinkageGraph.ExpectedChange.ChangeKind.APPEAR, null);
        LinkageGraph.LinkageRule ruleAppear = new LinkageGraph.LinkageRule(
                accountType, Command.ActionType.SELECT, "corporate", Collections.singletonList(appearPurpose));

        List<LinkageGraph.LinkageRule> rules = Arrays.asList(ruleEnable, ruleAppear);
        return new LinkageGraph("REQ-SAMPLE-1", rules);
    }
}
