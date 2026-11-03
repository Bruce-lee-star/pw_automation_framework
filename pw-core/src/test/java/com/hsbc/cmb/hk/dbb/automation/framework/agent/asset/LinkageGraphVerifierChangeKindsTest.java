package com.hsbc.cmb.hk.dbb.automation.framework.agent.asset;

import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.BrowserCommandChannel;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.Command;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.CommandResult;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.LocatorSpec;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.Snapshot;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.harness.IsolationHarness;

import org.junit.Test;

import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 验证驱动「状态变化」各分支经 {@link SnapshotVerifier} 复用后的语义等价（无浏览器，确定性）。
 *
 * <p>覆盖 {@code LinkageGraphVerifier} 重构后改走 {@code SnapshotVerifier} 的字段级断言分支：
 * APPEAR / DISAPPEAR / ENABLE / DISABLE / REQUIRED / TEXT_CHANGED（字面），
 * 确保去重其内部精确比较后仍与重构前行为一致。</p>
 */
public class LinkageGraphVerifierChangeKindsTest {

    private static final String TARGET_REF = LocatorSpec.role("button", "Submit").canonical();

    private static LinkageGraph graph(LinkageGraph.ExpectedChange.ChangeKind kind, String expectedText) {
        ElementRef target = new ElementRef("t",
                Collections.singletonList(LocatorSpec.role("button", "Submit")), "submit");
        LinkageGraph.ExpectedChange exp = new LinkageGraph.ExpectedChange(target, kind, expectedText);
        LinkageGraph.LinkageRule rule = new LinkageGraph.LinkageRule(
                target, Command.ActionType.CLICK, Collections.singletonList(exp));
        return new LinkageGraph("REQ-CHANGE", Collections.singletonList(rule));
    }

    private static BrowserCommandChannel channelWith(boolean visible, boolean enabled, String text) {
        Snapshot.ElementState el = new Snapshot.ElementState(TARGET_REF, "button", "Submit", visible, enabled, text);
        Snapshot snap = new Snapshot("page", Collections.singletonList(el));
        return new BrowserCommandChannel() {
            @Override public Snapshot snapshot() { return snap; }
            @Override public CommandResult execute(Command command) { return CommandResult.ok(snap); }
        };
    }

    private static final class StubHarness implements IsolationHarness {
        private final BrowserCommandChannel channel;
        StubHarness(BrowserCommandChannel channel) { this.channel = channel; }
        @Override public BrowserCommandChannel acquire(String targetUrl) { return channel; }
        @Override public void release(BrowserCommandChannel c) { /* no-op */ }
    }

    private static boolean passed(LinkageGraph graph, BrowserCommandChannel channel) {
        VerificationReport report = new LinkageGraphVerifier(new StubHarness(channel)).verify(graph, new VerificationScope("page", 1));
        return report.items().get(0).passed();
    }

    @Test
    public void appear_whenVisible_passes() {
        assertTrue(passed(graph(LinkageGraph.ExpectedChange.ChangeKind.APPEAR, null),
                channelWith(true, true, "Submit")));
    }

    @Test
    public void appear_whenHidden_fails() {
        assertFalse(passed(graph(LinkageGraph.ExpectedChange.ChangeKind.APPEAR, null),
                channelWith(false, true, "Submit")));
    }

    @Test
    public void disappear_whenHidden_passes() {
        assertTrue(passed(graph(LinkageGraph.ExpectedChange.ChangeKind.DISAPPEAR, null),
                channelWith(false, true, "Submit")));
    }

    @Test
    public void disappear_whenVisible_fails() {
        assertFalse(passed(graph(LinkageGraph.ExpectedChange.ChangeKind.DISAPPEAR, null),
                channelWith(true, true, "Submit")));
    }

    @Test
    public void disable_whenDisabled_passes() {
        assertTrue(passed(graph(LinkageGraph.ExpectedChange.ChangeKind.DISABLE, null),
                channelWith(true, false, "Submit")));
    }

    @Test
    public void disable_whenEnabled_fails() {
        assertFalse(passed(graph(LinkageGraph.ExpectedChange.ChangeKind.DISABLE, null),
                channelWith(true, true, "Submit")));
    }

    @Test
    public void required_whenEnabled_passes() {
        assertTrue(passed(graph(LinkageGraph.ExpectedChange.ChangeKind.REQUIRED, null),
                channelWith(true, true, "Submit")));
    }

    @Test
    public void textChanged_literalMatch_passes() {
        assertTrue(passed(graph(LinkageGraph.ExpectedChange.ChangeKind.TEXT_CHANGED, "Submit"),
                channelWith(true, true, "Submit")));
    }

    @Test
    public void textChanged_literalMismatch_fails() {
        assertFalse(passed(graph(LinkageGraph.ExpectedChange.ChangeKind.TEXT_CHANGED, "Wrong"),
                channelWith(true, true, "Submit")));
    }
}
