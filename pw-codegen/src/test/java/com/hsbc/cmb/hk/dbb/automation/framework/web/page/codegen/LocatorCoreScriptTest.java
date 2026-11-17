package com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * 脚本装配（{@code locator-core.js} + 两端模板）的结构契约。
 *
 * <p>拼接失败的表现是「脚本静默不执行、拾取与录制全无反应」，比逻辑错误更难定位，
 * 因此这里把「占位符已替换、核心函数只出现一次、关键入口存在」固化成断言。
 */
public class LocatorCoreScriptTest {

    private static final String[] INJECT_MARKERS = {
            "function roleOf(",
            "function descendantAnchorsOf(",
            "function bestAnchorSeg(",
            "function anchorSegsOf(",
            "function attrAnchorPriority(",
            "window.addEventListener('click'",
            "window.addEventListener('contextmenu'",
            "window.__CODEGEN__"
    };

    private static final String[] CDP_MARKERS = {
            "function roleOf(",
            "function descendantAnchorsOf(",
            "function factsOf(",
            "function ancestorChain("
    };

    @Test
    public void injectScriptIsFullyAssembled() {
        String js = LocatorCoreScript.inject();
        assertFalse("占位符必须被替换", js.contains(LocatorCoreScript.PLACEHOLDER));
        for (String marker : INJECT_MARKERS) {
            assertTrue("注入脚本缺少：" + marker, js.contains(marker));
        }
        assertEquals("核心函数不得重复注入", 1, count(js, "function roleOf("));
    }

    @Test
    public void cdpScriptIsFullyAssembled() {
        String js = LocatorCoreScript.cdp();
        assertFalse("占位符必须被替换", js.contains(LocatorCoreScript.PLACEHOLDER));
        assertTrue("CDP 脚本必须是自包含函数表达式", js.trim().startsWith("function"));
        for (String marker : CDP_MARKERS) {
            assertTrue("CDP 脚本缺少：" + marker, js.contains(marker));
        }
        assertEquals("核心函数不得重复注入", 1, count(js, "function roleOf("));
    }

    /** 核心只保留一份：两端产物都必须包含同一个「判据函数」的实现，不允许任一侧私自另存。 */
    @Test
    public void bothSidesShareTheSameCore() {
        String inject = LocatorCoreScript.inject();
        String cdp = LocatorCoreScript.cdp();
        assertTrue(inject.contains("function isStateAnchorSeg("));
        assertTrue(cdp.contains("function isStateAnchorSeg("));
        assertTrue(inject.contains("var RAND_RE ="));
        assertTrue(cdp.contains("var RAND_RE ="));
    }

    @Test
    public void assembleFailsFastOnMissingPlaceholder() {
        expectIse(() -> LocatorCoreScript.assemble("t", "no placeholder here", "x", new String[]{}));
    }

    @Test
    public void assembleFailsFastOnDuplicatePlaceholder() {
        String tpl = "a" + LocatorCoreScript.PLACEHOLDER + "b" + LocatorCoreScript.PLACEHOLDER;
        expectIse(() -> LocatorCoreScript.assemble("t", tpl, "x", new String[]{}));
    }

    @Test
    public void assembleFailsFastWhenMarkerMissing() {
        String tpl = "a" + LocatorCoreScript.PLACEHOLDER + "b";
        expectIse(() -> LocatorCoreScript.assemble("t", tpl, "core", new String[]{"function roleOf("}));
    }

    private static void expectIse(Runnable action) {
        try {
            action.run();
            fail("expected IllegalStateException");
        } catch (IllegalStateException expected) {
            // 预期：装配失败必须显式抛出，绝不能静默产出空脚本
        }
    }

    private static int count(String haystack, String needle) {
        int n = 0;
        int i = haystack.indexOf(needle);
        while (i >= 0) {
            n++;
            i = haystack.indexOf(needle, i + needle.length());
        }
        return n;
    }
}
