package com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen.locator.probe;

import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.LocatorSpec;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.nls.JsonNlsCatalog;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.nls.NlsCatalog;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen.locator.candidate.CandidateEvidence;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen.locator.candidate.CandidateEvidenceCollector;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen.locator.candidate.CandidateIntake;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen.locator.candidate.LocatorCandidate;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen.locator.candidate.StructuralObservation;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Path;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeNoException;

/**
 * 真实浏览器取证：{@link PlaywrightEvidenceProbe} 在页面上观测到的，必须就是证据里写的（设计 §4.4 / P3）。
 *
 * <p>浏览器不可用时自动跳过，不阻塞构建。</p>
 */
public class PlaywrightEvidenceProbeTest {

    private static final String HTML = """
            <!doctype html>
            <html lang="en"><body>
              <button>Log on</button>
              <button>Submit</button>
              <button>Submit</button>
              <button style="display:none">Hidden</button>
              <button disabled>Disabled</button>
              <span role="button" aria-label="Welcome, Alice">&#8250;</span>
              <button data-i18n="login.cancel" aria-label="Cancel">Cancel</button>
            </body></html>
            """;

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private Playwright playwright;
    private Browser browser;
    private BrowserContext context;
    private Page page;

    @Before
    public void setUp() {
        try {
            playwright = Playwright.create();
            browser = playwright.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true));
            context = browser.newContext();
            page = context.newPage();
            page.setContent(HTML);
        } catch (RuntimeException e) {
            closeQuietly();
            assumeNoException("Playwright browser unavailable in this environment", e);
        }
    }

    @After
    public void tearDown() {
        closeQuietly();
    }

    private static LocatorCandidate button(String name) {
        return LocatorCandidate.of("login.submit", LocatorSpec.role("button", name))
                .withExpectedRole("button");
    }

    private StructuralObservation observe(LocatorCandidate candidate, NlsCatalog nls) {
        return new PlaywrightEvidenceProbe(page, nls).observe(candidate);
    }

    @Test
    public void unique_role_name_hit_is_fully_observed() {
        StructuralObservation obs = observe(button("Log on"), null);

        assertEquals(1, obs.matches());
        assertTrue("可见", obs.visible());
        assertTrue("可操作", obs.enabled());
        assertEquals("Log on", obs.actualName());
        assertTrue("要留下定位锚点便于审计：" + obs.detail(), obs.detail().contains("tag=button"));
    }

    @Test
    public void duplicate_names_report_all_hits_instead_of_picking_one() {
        StructuralObservation obs = observe(button("Submit"), null);

        assertEquals("重复文案必须如实报 2 个，不得 first() 凑一个", 2, obs.matches());
        assertFalse("多元素时不做状态判定", obs.visible());
        assertTrue(obs.detail().contains("命中 2 个"));
    }

    @Test
    public void absent_name_reports_zero_matches() {
        StructuralObservation obs = observe(button("No Such Button"), null);

        assertEquals(0, obs.matches());
        assertFalse(obs.visible());
        assertTrue(obs.detail().contains("无匹配元素"));
    }

    @Test
    public void hidden_element_never_counts_as_a_valid_hit() {
        StructuralObservation obs = observe(button("Hidden"), null);

        // ARIA 定位天然排除不在无障碍树里的节点：display:none 的按钮往往连命中都算不上，
        // 这比"命中了再说不可见"更早失败，两条路径都不允许它通过。
        assertFalse("不可见元素不得被当成有效命中", obs.matches() == 1 && obs.visible());
    }

    @Test
    public void disabled_element_is_observed_but_not_actionable() {
        StructuralObservation obs = observe(button("Disabled"), null);

        assertEquals(1, obs.matches());
        assertTrue(obs.visible());
        assertFalse("disabled 元素不可操作", obs.enabled());

        CandidateEvidence evidence = CandidateEvidenceCollector.collect(
                button("Disabled"), obs, "点击后出现欢迎页", true, null);
        assertFalse("证据要如实反映不可操作", evidence.enabled());
    }

    @Test
    public void template_name_matches_value_injected_by_page() {
        LocatorCandidate candidate = button("Welcome, {{user}}");

        StructuralObservation obs = observe(candidate, null);
        assertEquals(1, obs.matches());
        assertEquals("Welcome, Alice", obs.actualName());

        CandidateEvidence evidence = CandidateEvidenceCollector.collect(
                candidate, obs, "登录后显示欢迎语", true, null);
        assertTrue("模板值由页面注入，须按正则比较：" + evidence.detail(), evidence.textMatched());
    }

    @Test
    public void nls_key_is_resolved_before_comparison() {
        NlsCatalog nls = JsonNlsCatalog.ofLanguage("en-US", "nls/login.nls.json");
        LocatorCandidate candidate = LocatorCandidate.of(
                        "login.submit", LocatorSpec.roleOfKey("button", "login.submit"))
                .withExpectedRole("button");

        StructuralObservation obs = observe(candidate, nls);

        assertEquals("key 应解析为当前语言文案后再定位", "Log on", obs.expectedName());
        assertEquals(1, obs.matches());
        assertEquals("Log on", obs.actualName());
        CandidateEvidence evidence = CandidateEvidenceCollector.collect(
                candidate, obs, "点击后出现欢迎页", true, null);
        assertTrue("跨语言资产：存 key 也要能验过：" + evidence.detail(), evidence.textMatched());
    }

    @Test
    public void data_i18n_attribute_is_captured_for_traceability() {
        StructuralObservation obs = observe(button("Cancel"), null);

        assertEquals(1, obs.matches());
        assertTrue("埋点属性要进证据，便于回溯 key：" + obs.detail(),
                obs.detail().contains("data-i18n=login.cancel"));
    }

    @Test
    public void invalid_selector_is_reported_as_unobserved_not_as_failure() throws Exception {
        LocatorCandidate broken = LocatorCandidate.of(
                "login.submit", LocatorSpec.css("button[["));

        StructuralObservation obs = observe(broken, null);

        assertFalse("非法选择器属于'没看清'，不是'看错了'", obs.observed());
        assertTrue(obs.detail().contains("定位失败"));
    }

    @Test
    public void real_page_intake_closes_the_loop() throws Exception {
        Path lib = tmp.getRoot().toPath().resolve("locators").resolve("candidates.json");

        CandidateIntake.IntakeResult r = CandidateIntake.run(
                lib, button("Log on"), new PlaywrightEvidenceProbe(page),
                "点击 Log on 后出现欢迎页标题", true, "trace-real-1");

        assertTrue("真实页面取证应通过：" + (r.validation() == null ? r.note() : r.validation().summary()),
                r.validated());
        assertNotNull(r.evidence());
        assertTrue(r.evidence().detail().contains("trace-real-1"));
    }

    private void closeQuietly() {
        for (AutoCloseable c : new AutoCloseable[]{page, context, browser, playwright}) {
            if (c == null) {
                continue;
            }
            try {
                c.close();
            } catch (Exception ignored) {
                // 关闭期异常忽略：资源可能已失效
            }
        }
        page = null;
        context = null;
        browser = null;
        playwright = null;
    }
}
