package com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen.cdp;

import com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen.model.CodegenFacts;
import org.junit.Test;

import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * 名字收敛的装配层：{@link CodegenCdpNodeExtractor#resolvePickedName} 必须只改名字，
 * 其余事实（含 {@code childAnchors} / {@code statusText}）一字不丢。
 */
public class PickedNameResolutionTest {

    private static CodegenFacts.Fact fact(String name, String text, String statusText) {
        return new CodegenFacts.Fact("button", name, "button", "", "", "", "", "", "", "",
                text, "", -1, "#continue", List.of(), "", "", "", Map.of(), "", false,
                List.of("[data-i18n=\"continue\"]"), statusText);
    }

    @Test
    public void announcementTextIsStrippedAndOtherFactsSurvive() {
        CodegenFacts.Fact js = fact("Continue", "Loading, please wait. Continue", "Loading, please wait.");
        CodegenFacts.Fact ax = fact("Loading, please wait. Continue", js.text, js.statusText);

        CodegenFacts.Fact resolved = CodegenCdpNodeExtractor.resolvePickedName(ax, js, null);

        assertEquals("Continue", resolved.name);
        assertEquals("子代 data-i18n 锚点必须保留（NLS 反查显式 key 依赖它）",
                js.childAnchors, resolved.childAnchors);
        assertEquals(js.statusText, resolved.statusText);
        assertEquals("button", resolved.role);
        assertEquals("#continue", resolved.cssSelector);
    }

    @Test
    public void clickInducedLoadingFallsBackToPreClickName() {
        CodegenFacts.Fact js = fact("", "Loading, please wait.", "Loading, please wait.");
        CodegenFacts.Fact ax = fact("Loading, please wait.", js.text, js.statusText);
        PreClickSnapshot pre = PreClickSnapshot.of("Continue", "Loading, please wait. Continue");

        assertEquals("Continue", CodegenCdpNodeExtractor.resolvePickedName(ax, js, pre).name);
    }

    @Test
    public void noEvidenceReturnsTheSameInstance() {
        CodegenFacts.Fact js = fact("Continue", "Continue", "");
        CodegenFacts.Fact ax = fact("Continue", js.text, js.statusText);

        assertSame("无证据时必须原样返回，避免无谓拷贝与行为变化",
                ax, CodegenCdpNodeExtractor.resolvePickedName(ax, js, null));
        assertTrue(CodegenCdpNodeExtractor.resolvePickedName(null, js, null) == null);
    }
}
