package com.hsbc.cmb.hk.dbb.automation.framework.agent.analysis;

import com.hsbc.cmb.hk.dbb.automation.framework.agent.analysis.engine.AssetDraftBundle;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.analysis.engine.ElementInventoryEntry;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.analysis.engine.RuleBasedUnderstandingEngine;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.analysis.observation.ObservedElement;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.analysis.observation.PageObservation;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.analysis.requirement.RequirementContext;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.analysis.requirement.RequirementEntry;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.analysis.requirement.RequirementId;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.asset.ElementRef;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.LocatorSpec;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.nls.JsonNlsCatalog;
import org.junit.Test;

import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * 真实业务 NLS 格式（{@code NLS_footer.json} 这类）流经「理解引擎」后的跨语言稳定性。
 *
 * <p>与 {@link NlsAssetProductionTest} 的区别：这里用<b>带地区的语言标签</b>（en-US/zh-CN/zh-HK）
 * 和<b>英文短语 key</b>（{@code "Privacy and Security footer"}），断言三语言产出的资产主键
 * 与首选定位符完全一致 —— 即"一份资产跑所有语言"，是用户多语言资产链路成立的关键。</p>
 */
public class NlsFooterAssetProductionTest {

    private static final String FILE = "nls/footer-format.nls.json";

    private static ObservedElement footerLink(String localizedName) {
        return new ObservedElement("link", localizedName, localizedName, true, true,
                List.of(LocatorSpec.role("link", localizedName)), Map.of());
    }

    private static PageObservation observation(String localizedName) {
        return new PageObservation("https://bank.example/footer", "footer-page",
                List.of(footerLink(localizedName)), "aria-snapshot");
    }

    private static RequirementContext requirement() {
        RequirementEntry req = new RequirementEntry(new RequirementId("REQ-FOOTER-1"),
                "页脚提供隐私与保安等链接", List.of(), List.of(), "footer.md");
        return new RequirementContext(List.of(req), "f");
    }

    @Test
    public void sameKeyedAssetAcrossRegionTaggedLanguages() {
        ElementRef enRef = analyze("en-US", "Privacy and Security");
        ElementRef zhRef = analyze("zh-CN", "私隐与保安");
        ElementRef hkRef = analyze("zh-HK", "私隱與保安");

        // 三语言产出的资产主键完全一致（key 跨语言稳定）
        assertEquals("link:Privacy and Security footer", enRef.refId());
        assertEquals(enRef.refId(), zhRef.refId());
        assertEquals(enRef.refId(), hkRef.refId());

        // 首选定位符是「角色 + key」，与 codegen 的 role=link[name=Nls.t("...")] 同义
        LocatorSpec primary = enRef.primaryLocator();
        assertTrue(primary.usesNlsKey());
        assertEquals("Privacy and Security footer", primary.nameKey());
        assertEquals("role=link;nameKey=Privacy and Security footer", primary.canonical());
    }

    private static ElementRef analyze(String lang, String localizedName) {
        AssetDraftBundle bundle = new RuleBasedUnderstandingEngine(JsonNlsCatalog.ofLanguage(lang, FILE))
                .analyze(requirement(), observation(localizedName));
        ElementInventoryEntry entry = bundle.inventory().entries().get(0);
        return entry.ref();
    }
}
