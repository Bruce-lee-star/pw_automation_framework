package com.hsbc.cmb.hk.dbb.automation.framework.agent.asset;

import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.BrowserCommandChannel;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.Command;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.CommandResult;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.LocatorSpec;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.Snapshot;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.harness.IsolationHarness;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.nls.JsonNlsCatalog;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.nls.NlsCatalog;
import org.junit.Test;

import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * 多语言资产的验证编排（无浏览器，确定性；设计 §4.7）。
 *
 * <p>证明同一份资产（定位符只含 NLS key）在不同语言下都被正确"落地"为当轮语言的定位：
 * 验证器把 {@code role=checkbox;nameKey=link.agree} 解析成 {@code role=checkbox;name=我同意条款}
 * 后再查快照。桩通道只认本地化后的 canonical，因此 PASS 即代表解析链路正确。</p>
 */
public class LinkageGraphNlsVerifierTest {

    private static final String FILE = "nls/agent.nls.json";

    /** 一条规则：勾选「同意」(checkbox) → 「原因」(textbox) 启用，且文案变为本地化的原因标签。 */
    private static LinkageGraph keyedGraph() {
        ElementRef trigger = new ElementRef("agree",
                Collections.singletonList(LocatorSpec.roleOfKey("checkbox", "link.agree")), "agree checkbox");
        ElementRef target = new ElementRef("reason",
                Collections.singletonList(LocatorSpec.roleOfKey("textbox", "link.reason")), "reason input");
        LinkageGraph.ExpectedChange exp = new LinkageGraph.ExpectedChange(
                target, LinkageGraph.ExpectedChange.ChangeKind.ENABLE, null);
        LinkageGraph.LinkageRule rule = new LinkageGraph.LinkageRule(
                trigger, Command.ActionType.CLICK, Collections.singletonList(exp));
        return new LinkageGraph("REQ-NLS-1", Collections.singletonList(rule));
    }

    /**
     * 桩通道：只按「本地化后的 canonical」建快照，并记录收到的触发目标。
     * 若验证器没把 key 解析成本地文案，触发目标就查不到 → 测试失败。
     */
    private static final class LocalizedStubChannel implements BrowserCommandChannel {
        private final String localizedAgree;
        private final String localizedReason;
        final List<Command> executed = new java.util.ArrayList<>();

        LocalizedStubChannel(String localizedAgree, String localizedReason) {
            this.localizedAgree = localizedAgree;
            this.localizedReason = localizedReason;
        }

        @Override
        public Snapshot snapshot() {
            return snapshotFor(true);
        }

        @Override
        public CommandResult execute(Command command) {
            executed.add(command);
            return CommandResult.ok(snapshotFor(true));
        }

        private Snapshot snapshotFor(boolean enabled) {
            Snapshot.ElementState reason = new Snapshot.ElementState(
                    LocatorSpec.role("textbox", localizedReason).canonical(),
                    "textbox", localizedReason, true, enabled, localizedReason);
            Snapshot.ElementState agree = new Snapshot.ElementState(
                    LocatorSpec.role("checkbox", localizedAgree).canonical(),
                    "checkbox", localizedAgree, true, true, localizedAgree);
            return new Snapshot("page", List.of(reason, agree));
        }
    }

    private static final class StubHarness implements IsolationHarness {
        private final BrowserCommandChannel channel;
        StubHarness(BrowserCommandChannel channel) { this.channel = channel; }
        @Override public BrowserCommandChannel acquire(String targetUrl) { return channel; }
        @Override public void release(BrowserCommandChannel c) { /* stub */ }
    }

    @Test
    public void sameKeyedAssetPassesInChinese() {
        assertPasses("zh", "我同意条款", "原因");
    }

    @Test
    public void sameKeyedAssetPassesInEnglish() {
        assertPasses("en", "I agree to the terms", "Reason");
    }

    private static void assertPasses(String lang, String localizedAgree, String localizedReason) {
        NlsCatalog nls = JsonNlsCatalog.ofLanguage(lang, FILE);
        LocalizedStubChannel channel = new LocalizedStubChannel(localizedAgree, localizedReason);
        LinkageGraphVerifier verifier = new LinkageGraphVerifier(new StubHarness(channel), nls);

        VerificationReport report = verifier.verify(keyedGraph(), new VerificationScope("page", 2));

        assertEquals(lang + " report", VerificationReport.Status.PASS, report.status());
        assertEquals(1.0, report.stability(), 1e-9);
        // 触发命令拿到的必须是本地化后的定位符（而不是 key）；executed[0] 是每轮开头的 NAVIGATE
        LocatorSpec sent = channel.executed.get(1).target();
        assertEquals("role=checkbox;name=" + localizedAgree, sent.canonical());
    }

    @Test
    public void textChangeAssertionUsesNlsKey() {
        NlsCatalog nls = JsonNlsCatalog.ofLanguage("zh", FILE);
        ElementRef banner = new ElementRef("banner",
                Collections.singletonList(LocatorSpec.roleOfKey("heading", "html.title")), "title");
        LinkageGraph graph = new LinkageGraph("REQ-NLS-2", Collections.singletonList(
                new LinkageGraph.LinkageRule(banner, Command.ActionType.CLICK,
                        Collections.singletonList(LinkageGraph.ExpectedChange.textChanged(banner, "html.title")))));

        // 快照文本是页面归一化后的可见文本（无标签）；断言侧按 key 解析后应命中
        BrowserCommandChannel channel = new BrowserCommandChannel() {
            @Override public Snapshot snapshot() { return snapshotWithTitle(); }
            @Override public CommandResult execute(Command command) { return CommandResult.ok(snapshotWithTitle()); }
            private Snapshot snapshotWithTitle() {
                return new Snapshot("page", Collections.singletonList(new Snapshot.ElementState(
                        LocatorSpec.role("heading", "开户 申请").canonical(),
                        "heading", "开户 申请", true, true, "开户 申请")));
            }
        };

        VerificationReport report = new LinkageGraphVerifier(new StubHarness(channel), nls)
                .verify(graph, new VerificationScope("page", 1));

        assertEquals(VerificationReport.Status.PASS, report.status());
        assertTrue(report.items().get(0).passed());
    }
}
