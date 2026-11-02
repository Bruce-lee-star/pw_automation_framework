package com.hsbc.cmb.hk.dbb.automation.framework.agent.analysis;

import com.hsbc.cmb.hk.dbb.automation.framework.agent.analysis.engine.AssetDraftBundle;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.analysis.engine.ElementInventoryEntry;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.analysis.engine.RuleBasedUnderstandingEngine;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.analysis.observation.ObservedElement;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.analysis.observation.PageObservation;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.analysis.requirement.RequirementContext;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.analysis.requirement.RequirementEntry;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.analysis.requirement.RequirementId;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.analysis.validation.DeterministicAssetValidator;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.analysis.validation.ValidationReport;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.asset.ElementRef;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.LocatorSpec;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.nls.JsonNlsCatalog;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.nls.NlsCatalog;
import org.junit.Test;

import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 多语言环境下的资产生产（设计 §4.7）。
 *
 * <p>核心断言：观测到的是<b>当前语言的文案</b>，但落库的资产主键与定位符是 <b>NLS key</b>
 * —— 这样同一份资产可在 en/zh 下复用，而不是"每换语言重产一次".
 * 全部零浏览器、零 LLM。</p>
 */
public class NlsAssetProductionTest {

    private static final String FILE = "nls/login.nls.json";

    private static ObservedElement username(String localizedName) {
        return new ObservedElement("textbox", localizedName, localizedName, true, true,
                List.of(LocatorSpec.role("textbox", localizedName)), Map.of());
    }

    private static PageObservation observation(String localizedName) {
        return new PageObservation("https://bank.example/login", "login-page",
                List.of(username(localizedName)), "aria-snapshot");
    }

    private static RequirementContext requirement() {
        RequirementEntry req = new RequirementEntry(new RequirementId("REQ-LOGIN-1"),
                "用户可用用户名登录", List.of(), List.of(), "login.md");
        return new RequirementContext(List.of(req), "f");
    }

    @Test
    public void engineTurnsObservedTextIntoNlsKey() {
        NlsCatalog zh = JsonNlsCatalog.ofLanguage("zh", FILE);
        AssetDraftBundle bundle = new RuleBasedUnderstandingEngine(zh)
                .analyze(requirement(), observation("用户名"));

        ElementRef ref = bundle.inventory().entries().get(0).ref();
        // 资产主键用 key（跨语言稳定），而不是中文文案
        assertEquals("textbox:login.username", ref.refId());
        // 首选定位符是「角色 + key」，与 codegen 的 role=textbox[name=Nls.t("login.username")] 同义
        LocatorSpec primary = ref.primaryLocator();
        assertTrue(primary.usesNlsKey());
        assertEquals("login.username", primary.nameKey());
        assertEquals("role=textbox;nameKey=login.username", primary.canonical());
        // 字面文案候选保留为单语言回退
        assertTrue(ref.locatorCandidates().stream().anyMatch(s -> !s.usesNlsKey() && "用户名".equals(s.name())));
    }

    @Test
    public void sameKeyIsProducedFromEitherLanguage() {
        // 同一元素在 en / zh 下观测到不同文案，但必须产出同一个 key 与同一个 refId
        ElementRef zhRef = new RuleBasedUnderstandingEngine(JsonNlsCatalog.ofLanguage("zh", FILE))
                .analyze(requirement(), observation("用户名"))
                .inventory().entries().get(0).ref();
        ElementRef enRef = new RuleBasedUnderstandingEngine(JsonNlsCatalog.ofLanguage("en", FILE))
                .analyze(requirement(), observation("Username"))
                .inventory().entries().get(0).ref();

        assertEquals(zhRef.refId(), enRef.refId());
        assertEquals(zhRef.primaryLocator().canonical(), enRef.primaryLocator().canonical());
    }

    @Test
    public void withoutNlsCatalogFallsBackToLiteralText() {
        AssetDraftBundle bundle = new RuleBasedUnderstandingEngine()
                .analyze(requirement(), observation("用户名"));
        LocatorSpec primary = bundle.inventory().entries().get(0).ref().primaryLocator();
        assertFalse(primary.usesNlsKey());
        assertEquals("用户名", primary.name());
    }

    @Test
    public void validatorWarnsWhenLocalizedTextIsUsedForLocating() {
        // 资产是用字面文案产的（未接 NLS）→ 在多语言环境里校验时必须给出"切语言即失效"的告警
        AssetDraftBundle literalBundle = new RuleBasedUnderstandingEngine()
                .analyze(requirement(), observation("用户名"));

        ValidationReport report = new DeterministicAssetValidator(JsonNlsCatalog.ofLanguage("zh", FILE))
                .validate(literalBundle);

        assertTrue(report.passed());   // 只是告警，不阻断落库
        assertTrue(report.issues().stream().anyMatch(i ->
                i.severity() == ValidationReport.Issue.Severity.WARNING
                        && i.message().contains("login.username")
                        && i.message().contains("survives language switch")));
    }

    @Test
    public void keyedAssetProducesNoSuchWarning() {
        AssetDraftBundle keyedBundle = new RuleBasedUnderstandingEngine(JsonNlsCatalog.ofLanguage("zh", FILE))
                .analyze(requirement(), observation("用户名"));

        ValidationReport report = new DeterministicAssetValidator(JsonNlsCatalog.ofLanguage("zh", FILE))
                .validate(keyedBundle);

        assertTrue(report.passed());
        assertTrue(report.issues().stream().noneMatch(i -> i.message().contains("survives language switch")));
    }
}
