package com.hsbc.cmb.hk.dbb.automation.framework.web.page.element;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 遮挡/可交互判定的契约测试（2026-10-10 改造：<b>由 Playwright 原生 actionability 报因得出结论</b>）。
 *
 * <p>旧实现用注入 JS 的 {@code document.elementFromPoint} 自己实现命中测试 —— 与浏览器真实的
 * pointer-events 判定可能不一致，且依赖选择器语义。现在改为 {@code locator.click(setTrial(true))}
 * （只判定不执行）并从 Playwright 的原生报因里归类，本测试钉住归类口径。</p>
 */
public class ElementDiagnosticsActionabilityTest {

    @Test
    public void obstruction_comesFromPlaywrightOwnReason() {
        String message = "TimeoutError: locator.click: Timeout 500ms exceeded.\n"
                + "Call log:\n"
                + "  - waiting for locator(\"[id='topnav.profileswitcher.dropdown']\")\n"
                + "  -   element is visible, enabled and stable\n"
                + "  -   scrolling into view if needed\n"
                + "  -   done scrolling\n"
                + "  -   <div class=\"mask-overlay\">…</div> intercepts pointer events\n"
                + "  - retrying click action\n";

        String verdict = ElementDiagnosticsCollector.classifyActionability(message);

        assertTrue("必须由 Playwright 原生报因得出遮挡结论：" + verdict, verdict.startsWith("Obstructed by "));
        assertTrue("应带拦截者描述：" + verdict, verdict.contains("mask-overlay"));
        assertFalse("不应残留 call log 前缀：" + verdict, verdict.startsWith("-"));
    }

    @Test
    public void reasons_areReportedAsIs() {
        assertEquals("Not actionable: not visible",
                ElementDiagnosticsCollector.classifyActionability("TimeoutError: element is not visible"));
        assertEquals("Not actionable: outside of the viewport",
                ElementDiagnosticsCollector.classifyActionability(
                        "TimeoutError: element is outside of the viewport"));
        assertEquals("Not actionable: not stable",
                ElementDiagnosticsCollector.classifyActionability("TimeoutError: element is not stable"));
    }

    @Test
    public void blankMessage_isExplicitNotSilent() {
        assertEquals("Not actionable (no reason reported)",
                ElementDiagnosticsCollector.classifyActionability("   "));
        assertEquals("Not actionable (no reason reported)",
                ElementDiagnosticsCollector.classifyActionability(null));
    }
}
