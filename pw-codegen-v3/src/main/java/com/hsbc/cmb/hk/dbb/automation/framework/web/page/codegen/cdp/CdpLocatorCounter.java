package com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen.cdp;

import com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen.locator.AriaRoles;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen.score.LocatorCounter;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.AriaRole;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 生产用 {@link LocatorCounter}：CSS 计数走 CDP（元素所属 frame + shadow 作用域、可批量），
 * role 计数仍走 Playwright 引擎。
 *
 * <h2>为什么 CSS 计数不用 {@code page.locator(css).count()}</h2>
 * <ul>
 *   <li><b>作用域错</b>：页级定位器只搜索主 frame，iframe 内元素恒 0 命中，而 {@code 0} 被
 *       {@code CodegenScorer} 当成"唯一"⇒ 唯一性等于从未校验。这里改为在元素所属 frame（+ 其 shadow
 *       宿主链）内计数，口径与最终生成的 {@code frame=… :: <css>} / {@code host >>> <css>} 定位器一致。</li>
 *   <li><b>往返多</b>：Playwright 每次 {@code count()} 都是独立往返（2k 元素页面约 3.6ms、4k 约 6.2ms），
 *       候选链通常 5~20 个。这里由 {@link #prefetch} 合并成<b>一次</b> {@code Runtime.evaluate}。</li>
 * </ul>
 *
 * <p><b>语义差异（有意为之，已写入文档）</b>：Playwright 的 CSS 引擎会穿透整页所有 open shadow root，
 * 因此旧实现会把"别的 shadow root 里的同名元素"也算成重复；本实现以元素自身的 scoping root 为准，
 * 更贴近生成物（生成物带 shadow 前缀，本就只在自身链路内解析）。
 *
 * <p><b>fail-soft</b>：CDP 批量计数不可判定（导航中 / 上下文销毁 / 选择器非法）时回退到 Playwright 页级计数；
 * 仍失败则视为"唯一"（1），与历史语义一致——环境抖动不得把可用定位器退化成脆弱 CSS。
 *
 * <p><b>缓存生命周期</b>：缓存属于 {@link #forFrame} 返回的实例，即"一次拾取"范围；页面变化后不会读到陈旧计数。
 * 线程安全：由拾取执行器（单线程）使用，内部不加锁。
 */
public final class CdpLocatorCounter implements LocatorCounter {

    private static final Logger log = LoggerFactory.getLogger(CdpLocatorCounter.class);

    private final Page page;
    private final CodegenCdpSession session;
    /** 元素所属 frameId；{@code null} 表示未知（此时 CDP 计数不启用，全部回退 Playwright）。 */
    private final String frameId;
    /** 元素所在的 open shadow 宿主链（outermost-first）。 */
    private final List<String> shadowHosts;
    /** 本次拾取内的计数缓存（选择器 → 命中数）。 */
    private final Map<String, Integer> cache = new HashMap<>();

    public CdpLocatorCounter(Page page, CodegenCdpSession session) {
        this(page, session, null, List.of());
    }

    private CdpLocatorCounter(Page page, CodegenCdpSession session, String frameId, List<String> shadowHosts) {
        this.page = Objects.requireNonNull(page, "page");
        this.session = Objects.requireNonNull(session, "session");
        this.frameId = frameId;
        this.shadowHosts = shadowHosts == null ? List.of() : List.copyOf(shadowHosts);
    }

    /**
     * 绑定到元素自身的作用域，返回<b>带独立缓存</b>的视图（每次拾取一个新实例）。
     *
     * <p>{@code frameId} 为空时保持未绑定状态：所有计数回退 Playwright 页级语义（行为与改造前一致）。
     */
    @Override
    public LocatorCounter forFrame(String frameId, List<String> shadowHosts) {
        return new CdpLocatorCounter(page, session, frameId, shadowHosts);
    }

    /**
     * 把一批候选选择器合并成一次协议往返（结果进缓存，后续 {@link #count} 直接命中）。
     *
     * <p>只预热确实要用的候选；不可判定的选择器不会入缓存，仍会在 {@link #count} 时按需回退。
     */
    @Override
    public void prefetch(Collection<String> selectors) {
        if (selectors == null || selectors.isEmpty() || frameId == null) return;
        List<String> missing = new ArrayList<>();
        for (String s : selectors) {
            if (s == null || s.isEmpty() || cache.containsKey(s) || missing.contains(s)) continue;
            missing.add(s);
        }
        if (missing.isEmpty()) return;
        Map<String, Integer> counts = session.countSelectorsInFrame(frameId, shadowHosts, missing);
        cache.putAll(counts);
        if (counts.size() < missing.size()) {
            log.debug("[codegen][cdp] batch count resolved {}/{} selector(s) in frame {} "
                            + "(其余将回退 Playwright 页级计数)", counts.size(), missing.size(), frameId);
        }
    }

    @Override
    public int count(String cssSelector) {
        if (cssSelector == null || cssSelector.isEmpty()) return 0;
        Integer cached = cache.get(cssSelector);
        if (cached != null) return cached;
        int n = frameId == null ? pageCount(cssSelector) : frameCount(cssSelector);
        cache.put(cssSelector, n);
        return n;
    }

    /** 元素作用域计数：失败/不可判定才回退页级，绝不把"查不到"当成"唯一"。 */
    private int frameCount(String cssSelector) {
        Map<String, Integer> counts =
                session.countSelectorsInFrame(frameId, shadowHosts, List.of(cssSelector));
        Integer n = counts.get(cssSelector);
        if (n != null) return n;
        log.debug("[codegen][cdp] frame-scoped count unavailable for '{}' (frame={}) — falling back to page-level",
                cssSelector, frameId);
        return pageCount(cssSelector);
    }

    /**
     * 页级兜底计数（Playwright 语义；只在 CDP 不可用时使用）。
     *
     * <p>异常降级为 1（视为唯一）：与历史行为一致，避免环境抖动把可用定位器退化成脆弱 CSS。
     */
    private int pageCount(String cssSelector) {
        try {
            return page.locator(cssSelector).count();
        } catch (Exception e) {
            log.debug("[codegen] uniqueness count failed for '{}': {}", cssSelector, e.getMessage());
            return 1;
        }
    }

    /**
     * role 复核：仍由 <b>Playwright 真实引擎</b>计数（{@code @RoleElement} 运行期就是它）。
     *
     * <p>只对主 frame 元素调用（子 frame 元素在抽取阶段已跳过复核：页级 {@code getByRole} 对它们恒 0 命中）。
     * 引擎抛异常时返回 1（视为唯一），与历史语义一致。
     */
    @Override
    public int countByRole(String role, String name) {
        String constant = AriaRoles.ariaConstant(role);
        if (constant == null) return 0;   // 引擎侧不存在该角色 ⇒ 明确不可用
        try {
            return page.getByRole(AriaRole.valueOf(constant),
                    new Page.GetByRoleOptions().setName(name).setExact(true)).count();
        } catch (Exception e) {
            log.debug("[codegen] role count failed for role={} name='{}': {}", role, name, e.getMessage());
            return 1;
        }
    }
}
