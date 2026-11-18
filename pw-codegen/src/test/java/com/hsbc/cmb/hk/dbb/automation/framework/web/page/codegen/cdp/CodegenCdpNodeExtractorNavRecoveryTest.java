package com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen.cdp;

import com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen.locator.LocatorStrategy;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen.model.CodegenEntry;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen.nls.NlsReverseLookup;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen.score.LocatorCounter;
import org.junit.Test;

import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * 「点击即导航元素」拾取兜底契约。
 *
 * <p>Cancel 这类按钮点击后立即跳页，拾取引擎回查时旧 DOM 已销毁（selector/xpath 全落空），
 * 必须用点击捕获阶段同步采集的 {@code info} 直接构造条目，否则元素丢失、代码不生成。
 */
public class CodegenCdpNodeExtractorNavRecoveryTest {

    /** 用点击前快照能重建完整条目：role/name/nlsKey 都在，策略为 role 且标记待复核。 */
    @Test
    public void recoversNavButtonFromClickInfo() {
        CodegenCdpNodeExtractor extractor = new CodegenCdpNodeExtractor((m, p) -> null,
                new NlsReverseLookup(new String[0]), counter());

        Map<String, Object> info = cancelInfo();
        CodegenEntry entry = extractor.extractFromClickInfo(info, null, List.of());

        assertNotNull("导航按钮不得丢失", entry);
        assertEquals("button", entry.role);
        assertEquals("Cancel", entry.name);
        assertTrue("CSS 候选链必须基于点击时采集的 data-i18n 锚点",
                entry.cssSelector.contains("reset_password_10"));
        assertEquals("无 nls 文件配置时走纯 ROLE 策略",
                LocatorStrategy.ROLE, entry.strategy);
        assertEquals("导航后无法引擎复核，标记待生成期复核",
                CodegenEntry.ROLE_HITS_UNVERIFIED, entry.roleHits);
    }

    /** 空 info 不得产出半成品条目。 */
    @Test
    public void nullInfoReturnsNull() {
        CodegenCdpNodeExtractor extractor = new CodegenCdpNodeExtractor((m, p) -> null,
                new NlsReverseLookup(new String[0]), counter());
        assertEquals(null, extractor.extractFromClickInfo(null, null, List.of()));
        assertEquals(null, extractor.extractFromClickInfo(Map.of(), null, List.of()));
    }

    private static Map<String, Object> cancelInfo() {
        return Map.of(
                "role", "button",
                "name", "Cancel",
                "tag", "button",
                "cssSelector", "button:has(span[data-i18n=\"reset_password_10\"])",
                "xpath", "/html/body/div[3]/form/div[2]/button[1]",
                "nth", 1,
                "className", "btn btn-secondary",
                "attrs", Map.of("data-i18n", "reset_password_10"),
                "childAnchors", List.of("[data-i18n=\"reset_password_10\"]")
        );
    }

    private static LocatorCounter counter() {
        return new LocatorCounter() {
            @Override
            public int count(String cssSelector) {
                return 1;
            }

            @Override
            public int countByRole(String role, String name) {
                return 1;
            }
        };
    }
}
