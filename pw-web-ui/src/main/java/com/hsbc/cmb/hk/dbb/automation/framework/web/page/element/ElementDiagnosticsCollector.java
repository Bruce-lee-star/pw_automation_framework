package com.hsbc.cmb.hk.dbb.automation.framework.web.page.element;


import com.hsbc.cmb.hk.dbb.automation.framework.web.exceptions.ElementOperationException;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.PlaywrightManager;
import com.microsoft.playwright.Frame;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.PlaywrightException;
import com.microsoft.playwright.TimeoutError;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * 元素失败诊断采集器 —— <b>动作失败时，产出"能直接进报告"的最小证据</b>。
 *
 * <p><b>契约（做什么 / 不做什么）</b>：</p>
 * <ul>
 *   <li><b>做什么</b>：{@link #collect()} 用 Playwright 原生 API 取元素状态
 *       （{@code count()} / {@code isVisible()} / {@code isEnabled()} / {@code isEditable()}），
 *       {@link #getObstructingElements()} 用 actionability {@code trial} 判遮挡，
 *       {@link #getPageUrl()} / {@link #getPageTitle()} 记录失败现场；
 *       产出由 {@code ElementOperationSupport} 装配进 {@link ElementOperationException}，
 *       最终出现在 Serenity/Cucumber 报告与控制台 —— 那是本类产出的<b>唯一消费者</b>。</li>
 *   <li><b>不做什么</b>：不等待、不重试、不做 DOM 抓取（不注入 JS、不维护属性白名单）、
 *       不解析选择器、不异步。Playwright 对象非线程安全，所有调用都在<b>场景线程</b>内同步完成
 *       （历史上"异步诊断线程池"跨线程操作 Playwright → pipe closed，已彻底移除）。</li>
 *   <li><b>为什么用原生 API</b>：判定口径与被测动作<b>同源</b>（同一套 actionability 语义），
 *       且对 CSS / XPath / role / text 等所有定位引擎一致。</li>
 * </ul>
 *
 * <p>频率与成本：仅发生在"动作已失败"之后，属低频兜底路径；单次成本上限 = 若干次即时探测
 * + 一次超时封顶的 actionability 探测（{@code PROBE_TIMEOUT_MS}）+ 一张截图。</p>
 *
 * @since 1.0.0
 */
public class ElementDiagnosticsCollector {

    private static final Logger logger = LoggerFactory.getLogger(ElementDiagnosticsCollector.class);


    private final Locator locator;
    private final String selector;
    private final Page page;
    /** 当前 iframe 上下文（null 表示在主页面 DOM 中操作） */
    private final Frame currentFrame;

    public ElementDiagnosticsCollector(Locator locator, String selector, Page page, Frame currentFrame) {
        this.locator = locator;
        this.selector = selector;
        this.page = page;
        this.currentFrame = currentFrame;
    }

    /**
     * 收集元素诊断信息（<b>全部使用 Playwright 原生 API，不注入 JS</b>）。
     *
     * <p><b>为什么不再用 {@code evaluate} / {@code document.querySelector}</b>：</p>
     * <ol>
     *   <li><b>语义一致性</b>：判定口径必须与被测动作一致 —— 原生 {@code count()/isVisible()/isEnabled()/
     *       isEditable()} 就是 Playwright actionability 用的同一套语义；自己写 JS 命中测试会与之分歧。</li>
     *   <li><b>引擎无关</b>：原生 API 对 CSS / XPath / role / text 等所有定位方式一致；JS 路径必须自己
     *       解析选择器（历史事故：XPath 交给 {@code querySelector} ⇒ {@code SyntaxError: … is not a valid selector}）。</li>
     *   <li><b>不引入等待</b>：诊断发生在"元素已判定不可用"之后；原生查询 API <b>即时返回且不抛</b>。</li>
     * </ol>
     *
     * <p>注：{@code tagName} 不再采集（Playwright 无原生 tagName API）；也<b>不再采集自选属性白名单</b>
     * （{@code id/class/name/...} 这类清单不是 Playwright 的语义，且框架内无任何消费方）。
     * 需要结构化证据时，{@code detailed=true} 下记录 Playwright 原生的
     * {@code locator.ariaSnapshot()}（引擎无关、无白名单）。</p>
     */
    public ElementOperationException.DiagnosticInfo collect() {
        ElementOperationException.DiagnosticInfo info = ElementOperationException.DiagnosticInfo.create();

        if (locator == null) {
            if (selector == null || selector.isBlank()) {
                logger.warn("Cannot collect element diagnostics: locator is null and selector is blank");
            } else {
                logger.debug("[diagnostic] no Locator available for '{}' — native probes need a Locator; "
                        + "reporting unknown state", selector);
            }
            return info;
        }

        long startedNanos = System.nanoTime();
        int matched = getElementCount();                 // 即时、不等待、不抛
        info.elementCount(matched);
        if (matched <= 0) {
            return info.existsInDom(false)
                       .isVisible(false)
                       .isEnabled(false)
                       .isEditable(false);
        }

        // isVisible 的 timeout 在客户端契约里是 @deprecated「option is ignored」——该 API 不等待、
        // 立即返回（vendored Locator.java:1562/1568），故不传（传了反而误导读者以为它会被尊重）。
        info.existsInDom(true)
            .isVisible(probe(locator::isVisible))
            // isEnabled / isEditable 的 timeout 会被尊重（元素未 attach 时会等到超时）：
            // 已 count()>0 时它们立即返回，极端竞态下也最多等 PROBE_TIMEOUT_MS。
            .isEnabled(probe(() -> locator.isEnabled(
                    new Locator.IsEnabledOptions().setTimeout(PROBE_TIMEOUT_MS))))
            .isEditable(probe(() -> locator.isEditable(
                    new Locator.IsEditableOptions().setTimeout(PROBE_TIMEOUT_MS))));

        if (PlaywrightManager.config().isElementDetailedDiagnostics()) {
            // 结构化证据用 Playwright 自己的 ARIA 快照（引擎无关、无自选属性白名单）
            String snapshot = stringProbe(locator::ariaSnapshot);
            if (snapshot != null && !snapshot.isBlank()) {
                logger.debug("[diagnostic] ARIA snapshot of [{}]:\n{}", selector, snapshot);
            }
        }
        logger.debug("[diagnostic] collect() for [{}] took {}ms (count={})",
                selector, elapsedMs(startedNanos), matched);
        return info;
    }

    /**
     * actionability 探测的超时上界（毫秒）。
     *
     * <p>只对「timeout 会被尊重」的探测生效：{@code isEnabled()} / {@code isEditable()} /
     * trial click（{@code isVisible()} 不等待，其 timeout 选项在客户端已 deprecated/ignored）。
     * 探测只发生在元素已 {@code count()&gt;0} 之后，正常情况下立即返回；本值只是「元素在探测
     * 途中被 detach」这类竞态的封顶 —— 属<b>安全护栏</b>，不是业务可调参数，故不做成配置项。</p>
     */
    private static final double PROBE_TIMEOUT_MS = 250;

    /** 原生布尔探测：任何异常一律归 {@code false}（诊断绝不影响主异常抛出）。 */
    static boolean probe(BooleanSupplier nativeProbe) {
        try {
            return nativeProbe.getAsBoolean();
        } catch (Exception e) {
            return false;
        }
    }

    /** 原生字符串探测：任何异常一律归 {@code null}（诊断绝不影响主异常抛出）。 */
    private static String stringProbe(Supplier<String> nativeProbe) {
        try {
            return nativeProbe.get();
        } catch (Exception e) {
            return null;
        }
    }

    /** 自采集开始至此刻的毫秒数（仅留痕用，不参与判定）。 */
    static long elapsedMs(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000L;
    }

    /**
     * 获取匹配元素数量
     */
    private int getElementCount() {
        try {
            return locator.count();
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * 获取当前页面标题
     */
    public String getPageTitle() {
        try {
            return page.title();
        } catch (Exception e) {
            return "unknown";
        }
    }

    /**
     * 获取当前页面 URL
     */
    public String getPageUrl() {
        try {
            // iframe 上下文下，"元素所在 frame 的 URL" 比顶层页面 URL 更贴近失败现场
            if (currentFrame != null) {
                String frameUrl = currentFrame.url();
                if (frameUrl != null && !frameUrl.isEmpty() && !"about:blank".equals(frameUrl)) {
                    return frameUrl;
                }
            }
            return page.url();
        } catch (Exception e) {
            return "unknown";
        }
    }

    /**
     * 获取可见的遮挡元素信息（selector 作为参数传递，避免字符串注入风险）
     */
    /**
     * 遮挡判定（<b>Playwright 原生 actionability trial，不注入 JS</b>）。
     *
     * <p>用 {@code click(setTrial(true))} 让 Playwright 自己跑完整 actionability 检查
     * （attached → visible → stable → enabled → receives events）且<b>只判定不执行</b>：
     * 通过 ⇒ {@code None}；失败 ⇒ 从其原生报因提取可读结论（{@code … intercepts pointer events} /
     * {@code not visible} / {@code outside of the viewport} / {@code not stable} / {@code not enabled}…）。</p>
     *
     * <p>为什么不用 {@code document.elementFromPoint}：那是"自己再实现一套命中测试"，与浏览器真实的
     * pointer-events 判定可能不一致，且依赖选择器语义与 JS 注入。trial actionability 的判定口径与
     * 被测动作<b>完全一致</b>（企业级要求：判定与执行同源）。</p>
     */
    public String getObstructingElements() {
        if (locator == null) {
            return "N/A (no locator)";
        }
        if (getElementCount() <= 0) {
            return "Element not found";
        }
        try {
            locator.click(new Locator.ClickOptions().setTrial(true).setTimeout(PROBE_TIMEOUT_MS));
            return "None";
        } catch (TimeoutError e) {
            return classifyActionability(e.getMessage());
        } catch (PlaywrightException e) {
            return "Unable to check: " + firstLine(e.getMessage());
        }
    }

    /** Playwright actionability 报因里的可读原因（顺序即优先级）。 */
    private static final String[] ACTIONABILITY_REASONS = {
            "intercepts pointer events", "not visible", "outside of the viewport",
            "not stable", "not enabled", "not editable", "not attached"};

    /**
     * 把 Playwright 的 actionability 报因归类为可读结论（包级可测接缝）。
     *
     * @param message Playwright 原始报因全文（含 call log）
     * @return {@code Obstructed by <拦截者>} / {@code Not actionable: <原因>} / 首行兜底
     */
    static String classifyActionability(String message) {
        if (message == null || message.isBlank()) {
            return "Not actionable (no reason reported)";
        }
        if (message.contains("intercepts pointer events")) {
            String blocker = interceptorLine(message);
            return blocker == null
                    ? "Obstructed by another element (intercepts pointer events)"
                    : "Obstructed by " + blocker;
        }
        for (String reason : ACTIONABILITY_REASONS) {
            if (message.contains(reason)) {
                return "Not actionable: " + reason;
            }
        }
        return "Not actionable: " + firstLine(message);
    }

    /** 从 call log 取"拦截者"那一行（形如 {@code <div class="overlay">…</div> intercepts pointer events}）。 */
    private static String interceptorLine(String message) {
        for (String raw : message.split("\\R")) {
            String line = raw.trim();
            if (line.contains("intercepts pointer events")) {
                return line.replaceFirst("^[-*]\\s*", "").trim();
            }
        }
        return null;
    }

    /** 报文首行（排障用短摘要）。 */
    private static String firstLine(String message) {
        if (message == null) {
            return "unknown";
        }
        int idx = message.indexOf('\n');
        String line = (idx < 0 ? message : message.substring(0, idx)).trim();
        return line.isEmpty() ? "unknown" : line;
    }

}
