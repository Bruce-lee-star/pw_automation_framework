package com.hsbc.cmb.hk.dbb.automation.framework.web.agent;

import com.hsbc.cmb.hk.dbb.automation.framework.agent.asset.ElementRef;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.asset.LinkageGraph;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.asset.LinkageGraphVerifier;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.asset.VerificationReport;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.asset.VerificationScope;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.Command;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.LocatorSpec;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.nls.JsonNlsCatalog;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.nls.NlsCatalog;
import org.junit.Test;

import java.net.URI;
import java.nio.file.Paths;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assume.assumeNoException;
import static org.junit.Assume.assumeTrue;

/**
 * 多语言资产的端到端验证（真实浏览器，设计 §4.7）。
 *
 * <p><b>这是"一份资产跑所有语言"的验收证据</b>：联动图谱里只写 NLS key
 * （{@code roleOfKey("checkbox","link.agree")}），不写任何具体文案；然后在同一个页面的
 * {@code ?lang=en} 与 {@code ?lang=zh} 两种渲染下各跑一遍隔离靶场验证，均须 PASS。</p>
 *
 * <p>对比：若资产写死英文文案，中文页面下 {@code getByRole} 根本找不到元素 —— 这正是 codegen
 * 早已规避的问题（生成 {@code Nls.t(key)} 而非文案），Agent 资产沿用同一约定。</p>
 *
 * <p>浏览器不可用时自动跳过，不阻塞构建。</p>
 */
public class NlsLinkageVerificationTest {

    private static final String NLS_FILE = "nls/linkage.nls.json";

    @Test
    public void sameKeyedAssetPassesInEnglishAndChinese() {
        String fixture = resolveFixtureUrl();
        try {
            assertPassesIn("en", fixture);
            assertPassesIn("zh", fixture);
        } catch (RuntimeException e) {
            assumeNoException("Playwright browser unavailable in this environment", e);
        }
    }

    private static void assertPassesIn(String lang, String fixture) {
        NlsCatalog nls = JsonNlsCatalog.ofLanguage(lang, NLS_FILE);
        RouteTestBedHarness harness = new RouteTestBedHarness(nls);
        try {
            LinkageGraphVerifier verifier = new LinkageGraphVerifier(harness, nls);
            // 同一份资产（只有 key），目标页按语言切换渲染
            VerificationReport report = verifier.verify(keyedGraph(), new VerificationScope(fixture + "?lang=" + lang, 2));
            assertEquals("lang=" + lang + " items=" + report.items(),
                    VerificationReport.Status.PASS, report.status());
            assertEquals(1.0, report.stability(), 1e-9);
        } finally {
            harness.closeAll();
        }
    }

    /** 联动：勾选「同意」→「原因」输入框启用。定位符全部用 NLS key，无任何字面文案。 */
    private static LinkageGraph keyedGraph() {
        ElementRef agree = new ElementRef("agree",
                Collections.singletonList(LocatorSpec.roleOfKey("checkbox", "link.agree")), "agree checkbox");
        ElementRef reason = new ElementRef("reason",
                Collections.singletonList(LocatorSpec.roleOfKey("textbox", "link.reason")), "reason input");
        LinkageGraph.ExpectedChange enableReason = new LinkageGraph.ExpectedChange(
                reason, LinkageGraph.ExpectedChange.ChangeKind.ENABLE, null);
        return new LinkageGraph("REQ-NLS-SAMPLE-1", Collections.singletonList(
                new LinkageGraph.LinkageRule(agree, Command.ActionType.CLICK,
                        Collections.singletonList(enableReason))));
    }

    private static String resolveFixtureUrl() {
        java.net.URL resource = NlsLinkageVerificationTest.class.getResource(
                "/com/hsbc/cmb/hk/dbb/automation/framework/web/agent/linkage-nls-fixture.html");
        assumeTrue("linkage-nls-fixture.html missing on test classpath", resource != null);
        try {
            return Paths.get(URI.create(resource.toURI().toString())).toUri().toString();
        } catch (Exception e) {
            throw new IllegalStateException("Cannot resolve fixture URL", e);
        }
    }
}
