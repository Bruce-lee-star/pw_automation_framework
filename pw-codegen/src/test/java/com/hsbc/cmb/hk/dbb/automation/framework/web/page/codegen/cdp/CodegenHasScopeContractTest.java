package com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen.cdp;

import org.junit.Test;

import java.io.InputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * {@code :has()} 作用域的数据契约：注入脚本与 CDP 自包含函数是两份独立维护的 JS，
 * 任一端漏采 {@code childAnchors}，「结构+文案都相同」的双胞胎就又退回绝对 XPath。
 *
 * <p>同时锁住「产物不使用 Playwright 专有伪类」：原生 CSS 不认 {@code :visible}，
 * 一旦有人再把它加回生成侧，这里直接红。
 */
public class CodegenHasScopeContractTest {

    @Test
    public void injectedFactsCollectDescendantAnchors() throws Exception {
        String js = resource("codegen/codegen-inject.js");
        assertTrue("注入脚本必须采集后代锚点", js.contains("function descendantAnchorsOf("));
        assertTrue("祖先事实必须带 childAnchors", js.contains("childAnchors: descendantAnchorsOf("));
        assertTrue("生成侧不得使用 Playwright 专有 :visible", !js.contains(":visible"));
    }

    @Test
    public void cdpFactsCollectTheSameAnchors() throws Exception {
        Field f = CodegenCdpNodeExtractor.class.getDeclaredField("SELF_CONTAINED_GET_INFO_FN");
        f.setAccessible(true);
        String js = (String) f.get(null);

        assertTrue("CDP 函数必须采集后代锚点", js.contains("function descendantAnchorsOf("));
        assertTrue("CDP 祖先事实必须带 childAnchors", js.contains("childAnchors: descendantAnchorsOf("));
        assertTrue("生成侧不得使用 Playwright 专有 :visible", !js.contains(":visible"));
    }

    /**
     * <b>视觉隐藏 ≠ 可排除</b>：{@code class="accessibleHidden"} / {@code .sr-only} / {@code .visually-hidden}
     * 只是看不见（clip 裁零、1px 尺寸），元素<b>仍在可访问性树里</b>——屏幕阅读器与 Playwright
     * {@code getByRole} 都能命中，而且往往是唯一的可访问名来源（图标按钮的隐藏文字标签）。
     *
     * <p>因此采集侧<b>不得</b>把它们当作「隐藏副本」回传：一旦回传，生成侧就会产出
     * {@code :not(.accessibleHidden)}，等于把无障碍内容判成垃圾、并让 {@code @RoleElement} 无从生成。
     * 只有「渲染树剔除 ⇒ 同时 AX 树剔除」的形态（{@code display:none} / {@code hidden} 属性 /
     * {@code aria-hidden="true"}）才可以作为排除依据，而这三者已由 Java 侧固定条件覆盖，
     * 不需要页面侧采集。
     *
     * <p>本用例是<b>防回退护栏</b>：两端只要再出现 {@code hiddenPeerMarks} 采集就红。
     */
    @Test
    public void visualOnlyHiddenClassesAreNotCollectedAsExclusions() throws Exception {
        String inject = resource("codegen/codegen-inject.js");
        assertFalse("注入脚本不得采集 class 型视觉隐藏标记", inject.contains("hiddenPeerMarks"));
        assertTrue("可见性判定必须写明 AX 树语义边界", inject.contains("仍在可访问性树里"));

        Field f = CodegenCdpNodeExtractor.class.getDeclaredField("SELF_CONTAINED_GET_INFO_FN");
        f.setAccessible(true);
        String cdp = (String) f.get(null);
        assertFalse("CDP 函数不得采集 class 型视觉隐藏标记", cdp.contains("hiddenPeerMarks"));
    }

    /**
     * 深度：两端都必须支持「无锚点层用 {@code :nth-of-type} 补位」，且补位有上限。
     *
     * <p>历史上任一层无锚点即整条链作废，复杂页面（中间是纯布局 div）因此一条祖先候选都产不出，
     * 只能退到绝对 XPath。
     */
    @Test
    public void bothSidesSupportBoundedFillerSegments() throws Exception {
        String inject = resource("codegen/codegen-inject.js");
        assertTrue(inject.contains("function fallbackSeg("));
        assertTrue(inject.contains("MAX_FILLER_LAYERS"));
        assertTrue("可见性必须判祖先 display:none", inject.contains("pcs.display === 'none'"));

        Field f = CodegenCdpNodeExtractor.class.getDeclaredField("SELF_CONTAINED_GET_INFO_FN");
        f.setAccessible(true);
        String cdp = (String) f.get(null);
        assertTrue(cdp.contains("function fallbackSeg("));
        assertTrue(cdp.contains("MAX_FILLER_LAYERS"));
        assertTrue("CDP 可见性必须判祖先 display:none", cdp.contains("pcs.display === 'none'"));
    }

    private static String resource(String name) throws Exception {
        try (InputStream in = CodegenHasScopeContractTest.class.getClassLoader()
                .getResourceAsStream(name)) {
            assertNotNull("注入脚本必须在测试 classpath 上: " + name, in);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
