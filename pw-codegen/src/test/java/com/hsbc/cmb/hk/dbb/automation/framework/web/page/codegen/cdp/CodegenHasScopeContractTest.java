package com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen.cdp;

import org.junit.Test;

import java.io.InputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
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
     * 2026-11-17 作用域收窄三项的<b>双端契约</b>：注入脚本与 CDP 自包含函数各维护一份 JS，
     * 只改一端等于没改（实际拾取走 CDP 端，只改注入脚本时产物完全不变）。
     *
     * <p>① 祖先事实必须带 {@code className}——Java 侧据此生成 {@code div.page:has(标题)} 作用域头；
     * 无此项时只剩裸 tag 头（{@code div:has(标题)} 命中 html/body 等所有祖先层，必然多命中）。
     * <p>② 父级锚点必须<b>排除目标子树</b>——否则父谓词与子谓词同指目标内部（零区分度、白占额度）。
     * <p>③ 状态型锚点 {@code [style=""]}（属性存在且为空）必须两端同口径采集，否则「激活页」兜底缺失。
     */
    @Test
    public void scopeHeadFactsAndStateAnchorAreSyncedAcrossBothJsCopies() throws Exception {
        String inject = resource("codegen/codegen-inject.js");
        Field f = CodegenCdpNodeExtractor.class.getDeclaredField("SELF_CONTAINED_GET_INFO_FN");
        f.setAccessible(true);
        String cdp = (String) f.get(null);

        String classNameLine = "className: (typeof n.className === 'string') ? n.className : ''";
        assertTrue("注入脚本：祖先事实必须带 className", inject.contains(classNameLine));
        assertTrue("CDP 函数：祖先事实必须带 className", cdp.contains(classNameLine));

        assertTrue("注入脚本：父级锚点必须排除目标子树",
                inject.contains("descendantAnchorsOf(n, 6, 6, target)"));
        assertTrue("CDP 函数：父级锚点必须排除目标子树",
                cdp.contains("descendantAnchorsOf(n, 6, 6, target)"));

        assertTrue("注入脚本：祖先链必须把目标传给 ancestorFacts",
                inject.contains("ancestorFacts(p, last, el)"));
        assertTrue("CDP 函数：祖先链必须把目标传给 ancestorFacts",
                cdp.contains("ancestorFacts(p, last, el)"));

        // 状态型锚点已从「[style=""] 单点特判」泛化为「一类判据」：两端都必须走 isStateAnchorSeg，
        // 且空值 style 仍必须留在状态锚点池里（否则单页应用用 style="" 标记的激活页会丢锚点）。
        assertTrue("注入脚本：状态型锚点必须由 isStateAnchorSeg 统一判定",
                inject.contains("isStateAnchorSeg("));
        assertTrue("CDP 函数：状态型锚点必须由 isStateAnchorSeg 统一判定",
                cdp.contains("isStateAnchorSeg("));
        assertTrue("注入脚本：空值 style 必须进状态锚点池", inject.contains("'style': 1"));
        assertTrue("CDP 函数：空值 style 必须进状态锚点池", cdp.contains("'style': 1"));
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

    /**
     * 后代锚点里的 {@code data-i18n} 必须能被还原成显式 NLS key。
     *
     * <p><b>为什么关键</b>：拾取归一把按钮/链接/label 包裹的控件定为拾取对象后，控件自身往往<b>没有</b>
     * {@code data-i18n}（HSBC 标在文案 {@code <span>} 上），只能按可见文案反查；而同一文案常常对应多个 key
     * （{@code reset.json} 里 8 个 key 的值都是 "Continue"），反查"文件顺序先到先得"会拿到语义不对的 key。
     * 后代锚点是唯一能取回开发者显式绑定 key 的数据源（锚点按 BFS 采集，取第一个 {@code data-i18n}）。
     */
    @Test
    public void descendantI18nKeyIsExtractedFromChildAnchors() {
        assertEquals("continue", CodegenCdpNodeExtractor.descendantI18nKey(
                java.util.List.of("#please-wait-loading", "[data-i18n=\"continue\"]", "[data-testid=\"cta\"]")));
        assertEquals("reset_password_11", CodegenCdpNodeExtractor.descendantI18nKey(
                java.util.List.of("[class*=\"spinner\"]", "[data-i18n=\"reset_password_11\"]")));
        assertNull("没有 i18n 锚点时应返回 null",
                CodegenCdpNodeExtractor.descendantI18nKey(java.util.List.of("#id", "[aria-label=\"Close\"]")));
        assertNull("空锚点列表应返回 null", CodegenCdpNodeExtractor.descendantI18nKey(java.util.List.of()));
        assertNull("null 应返回 null", CodegenCdpNodeExtractor.descendantI18nKey(null));
    }

    /**
     * 定位核心（锚点池 / 状态档 / 随机判定 / 可见性 / 名字来源）的<b>双端存在性契约</b>。
     *
     * <p>这些函数是「一类问题」的单一事实来源：{@code isStateAttr}、{@code anchorSegsOf}、
     * {@code bestAnchorSeg}、{@code isStateAnchorSeg}、{@code attrAnchorPriority}、{@code RAND_RE}、
     * {@code isZeroClip}、{@code resourceUrlTail}、{@code isRenderingHidden}、{@code labelledByText}。
     * 任一端缺失即代表两份脚本开始漂移——实际拾取走 CDP 端，只改注入脚本时<b>产物完全不变</b>
     * （2026-11-17 审计发现的 {@code roleOf} input 分支漂移即此类：注入脚本有 range/search/hidden
     * 映射，CDP 端一律兜底 textbox，导致同一元素两条路径给出不同 role）。
     */
    @Test
    public void sharedLocatorCoreIsSyncedAcrossBothCopies() throws Exception {
        String inject = resource("codegen/codegen-inject.js");
        Field f = CodegenCdpNodeExtractor.class.getDeclaredField("SELF_CONTAINED_GET_INFO_FN");
        f.setAccessible(true);
        String cdp = (String) f.get(null);

        String[] shared = {
                "function isStateAttr(",
                "function attrAnchorPriority(",
                "function segAttrName(",
                "function isStateAnchorSeg(",
                "function anchorSegsOf(",
                "function bestAnchorSeg(",
                "var RAND_RE =",
                "function isZeroClip(",
                "function isZeroClipPath(",
                "function resourceUrlTail(",
                "function isRenderingHidden(",
                "function labelledByText(",
                "function stableClassTokens(",
                "function stableAttrs(e, withState)",
                "function roleOf(",
                "function accessibleName(",
                "function nameTextOf("
        };
        for (String snippet : shared) {
            assertTrue("注入脚本缺少共享定位核心：" + snippet, inject.contains(snippet));
            assertTrue("CDP 脚本缺少共享定位核心：" + snippet, cdp.contains(snippet));
        }
    }

    /**
     * 判据数据（随机正则 / 语言表 / role 名单 / 状态属性表 / 资源属性池）必须<b>逐字一致</b>。
     *
     * <p>这些是「一类问题」的判定数据：漂移不会编译报错、也不会让既有测试红，只会让「注入侧按 A 判、
     * CDP 侧按 B 判」。而实际拾取走 CDP 端，于是「只改注入脚本」等于白改——本次审计发现的
     * {@code roleOf} input 分支漂移正是这类。上面的存在性契约守住「函数还在」，这里守住「数据没分叉」。
     */
    @Test
    public void sharedLocatorDataIsIdenticalAcrossBothCopies() throws Exception {
        String inject = resource("codegen/codegen-inject.js");
        Field f = CodegenCdpNodeExtractor.class.getDeclaredField("SELF_CONTAINED_GET_INFO_FN");
        f.setAccessible(true);
        String cdp = (String) f.get(null);

        String[] single = {
                "var RAND_RE = ",
                "var SRC_TRAILING_LOCALE = ",
                "var INTERACTIVE_ROLE = ",
                "var __containerRole = "
        };
        for (String head : single) {
            assertEquals("判据数据漂移：" + head, lineOf(inject, head), lineOf(cdp, head));
        }
        String[] blocks = {"var STATE_ATTRS = {", "var RESOURCE_URL_ATTRS = {"};
        for (String head : blocks) {
            assertEquals("判据数据漂移：" + head, blockOf(inject, head), blockOf(cdp, head));
        }
    }

    /** 取包含 {@code head} 的整行（去首尾空白）；缺失返回 {@code null}，让断言直接失败。 */
    private static String lineOf(String js, String head) {
        int i = js.indexOf(head);
        if (i < 0) return null;
        int end = js.indexOf('\n', i);
        return (end < 0 ? js.substring(i) : js.substring(i, end)).trim();
    }

    /** 取从 {@code head} 起到首个 {@code "};"} 的多行块（逐行 trim 后拼接）。 */
    private static String blockOf(String js, String head) {
        int i = js.indexOf(head);
        if (i < 0) return null;
        int end = js.indexOf("};", i);
        if (end < 0) return null;
        StringBuilder sb = new StringBuilder();
        for (String line : js.substring(i, end + 2).split("\\R")) {
            String t = line.trim();
            if (!t.isEmpty()) {
                sb.append(t).append('\n');
            }
        }
        return sb.toString().trim();
    }

    /** 右键录制链路的存在性契约：注入侧必须监听 contextmenu 并上报同名动作类型。 */
    @Test
    public void rightClickIsRecordedByInjectScript() throws Exception {
        String inject = resource("codegen/codegen-inject.js");
        assertTrue("注入脚本必须监听右键", inject.contains("addEventListener('contextmenu'"));
        assertTrue("右键必须上报 contextmenu 动作类型", inject.contains("type: 'contextmenu'"));
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
