package com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen.cdp;

import org.junit.Test;

import java.io.InputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * {@code :has()} 作用域的数据契约：注入脚本与 CDP 自包含函数是两份独立维护的 JS，
 * 任一端漏采 {@code childAnchors}，「结构+文案都相同」的双胞胎就又退回绝对 XPath。
 *
 * <p>同时锁住「生成侧绝不使用 Playwright 专有伪类」：该伪类仅 Playwright locator 支持，
 * 原生 {@code document.querySelector/All} 不识别（会抛 SyntaxError），生成侧一旦出现就红。
 */
public class CodegenHasScopeContractTest {

    @Test
    public void injectedFactsCollectDescendantAnchors() throws Exception {
        String js = resource("codegen/codegen-inject.js");
        assertTrue("注入脚本必须采集后代锚点", js.contains("function descendantAnchorsOf("));
        assertTrue("祖先事实必须带 childAnchors", js.contains("childAnchors: descendantAnchorsOf("));
        assertTrue("生成侧不得使用 Playwright 专有伪类别名", !js.contains(":visible"));
    }

    @Test
    public void cdpFactsCollectTheSameAnchors() throws Exception {
        Field f = CodegenCdpNodeExtractor.class.getDeclaredField("SELF_CONTAINED_GET_INFO_FN");
        f.setAccessible(true);
        String js = (String) f.get(null);

        assertTrue("CDP 函数必须采集后代锚点", js.contains("function descendantAnchorsOf("));
        assertTrue("CDP 祖先事实必须带 childAnchors", js.contains("childAnchors: descendantAnchorsOf("));
        assertTrue("生成侧不得使用 Playwright 专有伪类别名", !js.contains(":visible"));
    }

    /**
     * 2026-11-14 两项加固的<b>双端契约</b>（注入脚本 + CDP 自包含函数必须同步，改一处漏另一处即红）：
     *
     * <p>① <b>目标自身</b>也要采集 {@code childAnchors}。此前只有祖先采集，Java 侧
     * {@code self.childAnchors} 恒为空 ⇒ 无法生成 {@code button:has([data-i18n="x"])} 短形式，
     * 「自身无属性、内部有唯一语义锚点」的按钮只能退化成按 div 序号拼的结构链。
     * <p>② 结构链分支必须做<b>零命中即作废</b>：语法合法但匹配 0 个元素的链（典型如
     * {@code … > div:nth-of-type(3) button} 而父级直接子元素其实是 {@code button}）绝不能写进
     * {@code cssSelector}——回放必然等不到元素，交给带唯一性校验的 XPath / role 兜底更好。
     */
    @Test
    public void selfFactsCarryAnchors_andZeroMatchChainsAreDropped() throws Exception {
        String inject = resource("codegen/codegen-inject.js");
        assertTrue("注入脚本：目标自身必须采集 childAnchors",
                inject.contains("childAnchors: descendantAnchorsOf(el, 3, 6)"));
        assertTrue("注入脚本：结构链必须做零命中校验", inject.contains("totalCount(pathSel) <= 0"));

        Field f = CodegenCdpNodeExtractor.class.getDeclaredField("SELF_CONTAINED_GET_INFO_FN");
        f.setAccessible(true);
        String cdp = (String) f.get(null);
        assertTrue("CDP 函数：目标自身必须采集 childAnchors",
                cdp.contains("childAnchors: descendantAnchorsOf(x, 3, 6)"));
        assertTrue("CDP 函数：结构链必须做零命中校验", cdp.contains("totalCount(pathSel) <= 0"));
    }

    /**
     * 框架自绘 UI（被测页内控制条 / 高亮框）必须从录制与拾取中排除。
     *
     * <p>缺陷现场：控制条按钮就在被测页 DOM 里，而录制监听在 window <b>捕获</b>层，先于按钮
     * onclick 执行 ⇒ 「点暂停」这一下会被当成业务点击上报，此刻 Java 侧 {@code pickOn} 仍为 true，
     * 于是暂停按钮自己被拾取进面板（停止 / 继续按钮同理）。
     *
     * <p>本测试锁三件事：① 判定助手存在；② 两个自绘容器都打了 {@code data-codegen-ui} 标记；
     * ③ 四个录制入口（click / input / change / keydown）都真的守卫了。
     */
    @Test
    public void codegenOwnUi_isExcludedFromRecordingAndPick() throws Exception {
        String js = resource("codegen/codegen-inject.js");

        assertTrue("必须存在框架 UI 判定助手", js.contains("function isCodegenUi(node)"));
        assertTrue("高亮框必须打 data-codegen-ui 标记",
                js.contains("d.setAttribute('data-codegen-ui', '1')"));
        assertTrue("控制条必须打 data-codegen-ui 标记",
                js.contains("bar.setAttribute('data-codegen-ui', '1')"));

        assertTrue("click 监听必须守卫", js.contains("if (isCodegenUi(target)) return;"));
        assertEquals("input / change / keydown 三个监听都要守卫",
                3, occurrences(js, "if (isCodegenUi(ev.target)) return;"));
        assertEquals("悬停高亮也要跳过框架 UI", 1, occurrences(js, "if (isCodegenUi(el)) return;"));
    }

    /**
     * 图片 {@code src} 的语言变体归一化：两份 JS 都必须把 {@code xxx_EN.png} 变成
     * {@code img[src*="xxx"]}，否则切换语言（EN→ZH/TC）后整条定位失效。
     */
    @Test
    public void localizedImgSrcIsNormalizedOnBothJsCopies() throws Exception {
        String inject = resource("codegen/codegen-inject.js");
        assertTrue("注入脚本：必须有语言段归一化助手",
                inject.contains("function srcLocaleNeutralPrefix(tail)"));
        assertTrue("注入脚本：img 分支必须使用它",
                inject.contains("var neutral = srcLocaleNeutralPrefix(tail);"));

        Field f = CodegenCdpNodeExtractor.class.getDeclaredField("SELF_CONTAINED_GET_INFO_FN");
        f.setAccessible(true);
        String cdp = (String) f.get(null);
        assertTrue("CDP 函数：必须有语言段归一化助手",
                cdp.contains("function srcLocaleNeutralPrefix(tail)"));
        assertTrue("CDP 函数：img 分支必须使用它",
                cdp.contains("var neutral = srcLocaleNeutralPrefix(tail);"));
    }

    private static int occurrences(String haystack, String needle) {
        int count = 0;
        int from = 0;
        while ((from = haystack.indexOf(needle, from)) >= 0) {
            count++;
            from += needle.length();
        }
        return count;
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
