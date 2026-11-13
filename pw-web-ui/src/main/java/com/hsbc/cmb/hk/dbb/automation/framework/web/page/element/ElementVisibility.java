package com.hsbc.cmb.hk.dbb.automation.framework.web.page.element;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.PlaywrightException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

/**
 * 严格可见性探测工具（framework-internal，可选增强）。
 *
 * <h3>为什么存在</h3>
 * 框架主路径的可见性判定统一走 Playwright 原生 {@code Locator.isVisible()} / {@code WaitForSelectorState.VISIBLE}
 * （见 {@link ElementDiagnosticsCollector}：判定与执行同源、不写 JS）。但原生 {@code :visible} 有盲点：
 * 不识别 {@code opacity:0}、不识别"视口外"、不识别"被遮挡"；而传统 JS 写法
 * （{@code offsetParent === null}、只看自身 {@code display}、{@code getBoundingClientRect} 全 0 混淆）在多场景误判。
 *
 * <p>本工具提供一套递归、全维度、双档语义的纯 JS 可见性算法，作为<b>显式可选</b>的"肉眼真正可见且可点击"
 * 语义层（例如"多个同名面板/按钮中只点真正可见的那个"），不污染核心 actionability 路径，不与
 * {@link ElementDiagnosticsCollector} 的"同源"原则冲突。</p>
 *
 * <h3>双档语义</h3>
 * <ul>
 *   <li><b>宽松（默认，{@link #isVisible(Locator)}）</b>：对齐 Playwright {@code :visible} —— 仅判
 *       {@code display} / {@code visibility} / 布局尺寸（递归祖先），零语义分歧，现有行为不变。</li>
 *   <li><b>严格（{@link #isVisibleStrict(Locator)}）</b>：在宽松基础上额外排除 {@code opacity:0} 与"视口外"
 *       （元素中心不在视口内）。</li>
 *   <li><b>严格 + 遮挡（{@link #isVisibleStrict(Locator, boolean)} 传 {@code true}）</b>：再额外排除被其它元素
 *       遮挡（{@code document.elementFromPoint} 命中测试）。该项成本最高且在 transform / iframe 场景不可靠，
 *       默认关闭，仅对"真正无遮拦可点击"有强诉求时开启。</li>
 * </ul>
 *
 * <h3>算法鲁棒性（规避传统 JS 陷阱）</h3>
 * <ul>
 *   <li>递归用 {@code parentElement} 而非 {@code offsetParent} —— 根治 {@code position:fixed} 元素
 *       {@code offsetParent === null} 被误判为隐藏的经典坑；</li>
 *   <li>用 {@code getComputedStyle} 取<b>继承后</b>的 {@code visibility} —— 祖先 {@code hidden} 会反映在子的
 *       computed 上，单查自身 {@code style.visibility} 会漏判；</li>
 *   <li>用 {@code getBoundingClientRect} 尺寸同时捕获"祖先 {@code display:none}（子无 box）"与"自身零尺寸"，
 *       并反映 {@code transform: scale()}；</li>
 *   <li>{@code isConnected} 排除脱离文档节点。</li>
 * </ul>
 *
 * <p>本类无状态、线程安全，仅依赖传入的 {@link Locator}（懒绑定当前页面，对首个匹配元素判定）。</p>
 */
public final class ElementVisibility {

    private static final Logger logger = LoggerFactory.getLogger(ElementVisibility.class);

    private ElementVisibility() {
    }

    /** 宽松档：对齐 Playwright {@code :visible}（display / visibility / 尺寸，递归祖先）。 */
    public static boolean isVisible(Locator locator) {
        return probe(locator, false, false);
    }

    /** 严格档：额外排除 opacity:0 与视口外。 */
    public static boolean isVisibleStrict(Locator locator) {
        return probe(locator, true, false);
    }

    /**
     * 严格档（含遮挡）：当 {@code checkOcclusion=true} 时再排除被其它元素遮挡的项。
     *
     * @param locator          目标定位器（对首个匹配元素判定）
     * @param checkOcclusion   是否启用 elementFromPoint 遮挡命中测试（成本高、transform/iframe 场景不准，谨慎开启）
     */
    public static boolean isVisibleStrict(Locator locator, boolean checkOcclusion) {
        return probe(locator, true, checkOcclusion);
    }

    private static boolean probe(Locator locator, boolean strict, boolean checkOcclusion) {
        if (locator == null) {
            return false;
        }
        Map<String, Object> opts = new HashMap<>(2);
        opts.put("strict", strict);
        opts.put("checkOcclusion", checkOcclusion);
        try {
            Object result = locator.evaluate(SCRIPT, opts);
            return Boolean.TRUE.equals(result);
        } catch (PlaywrightException e) {
            // 元素不存在 / detached / 计算失败 → 一律视为不可见（探针绝不影响主流程）
            logger.debug("[ElementVisibility] probe failed; treating as not visible: {}", e.toString());
            return false;
        }
    }

    /**
     * 递归可见性算法（在浏览器上下文执行）：对传入节点及其全部祖先做全维度判定。
     * 表达式以 {@code (node, opts)} 形式接收元素（首参）与选项（次参）。
     *
     * <p><b>根节点豁免</b>：{@code body} / {@code html} 是视口画布，其尺寸由视口决定，不做负向尺寸判定，
     * 也不再向上递归——否则"页面唯一内容是绝对/固定定位元素"时 body 高度塌缩为 0，会连锁把目标误判不可见。</p>
     */
    private static final String SCRIPT =
            "(node, opts) => {"
          + "  opts = opts || {};"
          + "  var strict = opts.strict === true;"
          + "  var checkOcclusion = opts.checkOcclusion === true;"
          + "  function vis(n) {"
          + "    if (!n || n.nodeType !== 1) return false;"
          + "    if (!n.isConnected) return false;"
          + "    var isRoot = (n === document.body || n === document.documentElement);"
          + "    var cs = window.getComputedStyle(n);"
          + "    if (cs.display === 'none') return false;"
          + "    if (cs.visibility === 'hidden' || cs.visibility === 'collapse') return false;"
          + "    if (strict && parseFloat(cs.opacity) === 0) return false;"
          + "    if (!isRoot) {"
          + "      var r = n.getBoundingClientRect();"
          + "      if (r.width <= 0 || r.height <= 0) return false;"
          + "      if (strict) {"
          + "        if (r.right <= 0 || r.bottom <= 0 || r.left >= window.innerWidth || r.top >= window.innerHeight) return false;"
          + "        if (checkOcclusion) {"
          + "          var cx = r.left + r.width / 2, cy = r.top + r.height / 2;"
          + "          var top = document.elementFromPoint(cx, cy);"
          + "          if (top && top !== n && !n.contains(top)) return false;"
          + "        }"
          + "      }"
          + "    }"
          + "    var p = n.parentElement;"
          + "    if (p && p !== document.body && p !== document.documentElement) {"
          + "      if (!vis(p)) return false;"
          + "    }"
          + "    return true;"
          + "  }"
          + "  return vis(node);"
          + "}";
}
