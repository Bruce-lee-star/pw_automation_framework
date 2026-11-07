package com.hsbc.cmb.hk.dbb.automation.framework.web.agent;

import com.hsbc.cmb.hk.dbb.automation.framework.agent.asset.ElementRef;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.asset.LinkageGraph;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.asset.LinkageGraphVerifier;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.asset.VerificationReport;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.asset.VerificationScope;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.BrowserCommandChannel;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.Command;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.CommandResult;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.LocatorSpec;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.Snapshot;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.harness.IsolationHarness;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * 验证驱动编排逻辑测试（无浏览器，确定性）。
 *
 * <p>用桩 {@link BrowserCommandChannel} / {@link IsolationHarness} 驱动 {@link LinkageGraphVerifier}，
 * 验证其"跨 repeat 次统计稳定性 → PASS/FAIL/FLAKY"的核心编排逻辑正确，不依赖 Playwright 环境。</p>
 */
public class LinkageGraphVerifierLogicTest {

    /** 一条规则：勾选（role=checkbox, name=I agree to the terms）后 reason 应 ENABLE。 */
    private static LinkageGraph singleEnableRuleGraph() {
        // 角色优先：refId 为业务语义 id，locatorCandidates 第一梯队为 Role 定位。
        ElementRef trigger = new ElementRef("agree",
                Collections.singletonList(LocatorSpec.role("checkbox", "I agree to the terms")),
                "agree checkbox");
        ElementRef target = new ElementRef("reason",
                Collections.singletonList(LocatorSpec.role("textbox", "Reason")),
                "reason input");
        LinkageGraph.ExpectedChange exp = new LinkageGraph.ExpectedChange(
                target, LinkageGraph.ExpectedChange.ChangeKind.ENABLE, null);
        LinkageGraph.LinkageRule rule = new LinkageGraph.LinkageRule(
                trigger, Command.ActionType.CLICK, Collections.singletonList(exp));
        return new LinkageGraph("REQ-LINK-1", Collections.singletonList(rule));
    }

    /** 桩通道：按调用次序返回预设的 #reason enabled 状态序列。 */
    private static final class SequenceChannel implements BrowserCommandChannel {
        private int call;
        private final List<Boolean> reasonEnabled;
        final List<Command> executed = new ArrayList<>();

        SequenceChannel(List<Boolean> reasonEnabled) {
            this.reasonEnabled = reasonEnabled;
        }

        @Override
        public Snapshot snapshot() {
            // 验证阶段重新采集的快照必须反映「最近一次 execute 的动作结果」，
            // 与 execute() 产出的序列状态保持一致（D3 稳定语义：settle 后重新取快照）。
            int idx = call <= 0 ? 0 : (call - 1) % reasonEnabled.size();
            return snapshotFor(reasonEnabled.get(idx));
        }

        @Override
        public CommandResult execute(Command command) {
            boolean enabled = reasonEnabled.get(call % reasonEnabled.size());
            call++;
            executed.add(command);
            return CommandResult.ok(snapshotFor(enabled));
        }

        private Snapshot snapshotFor(boolean enabled) {
            // ref 与图谱目标候选 canonical 严格一致（role=textbox;name=Reason）。
            String ref = LocatorSpec.role("textbox", "Reason").canonical();
            Snapshot.ElementState reason = new Snapshot.ElementState(
                    ref, "textbox", "Reason", true, enabled, "Reason");
            return new Snapshot("page", Collections.singletonList(reason));
        }
    }

    private static final class StubHarness implements IsolationHarness {
        private final BrowserCommandChannel channel;
        StubHarness(BrowserCommandChannel channel) { this.channel = channel; }
        @Override public BrowserCommandChannel acquire(String targetUrl) { return channel; }
        @Override public void release(BrowserCommandChannel c) { /* no-op for stub */ }
    }

    @Test
    public void allRunsPass_yieldsPassAndFullStability() {
        SequenceChannel ch = new SequenceChannel(java.util.Arrays.asList(true, true, true));
        LinkageGraphVerifier verifier = new LinkageGraphVerifier(new StubHarness(ch));
        VerificationReport report = verifier.verify(singleEnableRuleGraph(), new VerificationScope("page", 3));

        assertEquals(VerificationReport.Status.PASS, report.status());
        assertEquals(1.0, report.stability(), 1e-9);
        assertTrue(report.items().get(0).passed());
    }

    @Test
    public void allRunsFail_yieldsFail() {
        SequenceChannel ch = new SequenceChannel(java.util.Arrays.asList(false, false, false));
        LinkageGraphVerifier verifier = new LinkageGraphVerifier(new StubHarness(ch));
        VerificationReport report = verifier.verify(singleEnableRuleGraph(), new VerificationScope("page", 3));

        assertEquals(VerificationReport.Status.FAIL, report.status());
        assertEquals(0.0, report.stability(), 1e-9);
    }

    @Test
    public void flakyRuns_yieldsFlaky() {
        // 2/3 命中 → 稳定性 0.667，落在 [0.5,1.0) → FLAKY
        SequenceChannel ch = new SequenceChannel(java.util.Arrays.asList(true, false, true));
        LinkageGraphVerifier verifier = new LinkageGraphVerifier(new StubHarness(ch));
        VerificationReport report = verifier.verify(singleEnableRuleGraph(), new VerificationScope("page", 3));

        assertEquals(VerificationReport.Status.FLAKY, report.status());
        assertEquals(2.0 / 3.0, report.stability(), 1e-9);
    }
}
