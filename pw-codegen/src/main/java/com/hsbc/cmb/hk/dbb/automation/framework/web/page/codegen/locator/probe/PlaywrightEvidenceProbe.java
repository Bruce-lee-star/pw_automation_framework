package com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen.locator.probe;

import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.LocatorSpec;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.nls.NlsCatalog;
import com.hsbc.cmb.hk.dbb.automation.framework.web.agent.LocatorResolver;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen.locator.candidate.EvidenceProbe;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen.locator.candidate.LocatorCandidate;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen.locator.candidate.StructuralObservation;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;

import java.util.Map;

/**
 * {@link EvidenceProbe} 的 Playwright 实现：在真实页面上取证（设计 §4.4 / P3）。
 *
 * <h2>为什么必须走 {@link LocatorResolver}</h2>
 * <p>探针用的定位翻译必须与运行时命令通道<b>完全同一条</b>。否则会出现最坏的一种情况：
 * 探测时命中唯一的 {@code button "Log on"}，上线后 {@code nth} / NLS key 解析路径不同，
 * 命中的是另一个元素——而验证记录却白纸黑字写着"已验证"。所以这里不自己拼
 * {@code getByRole}，一律委托 {@code LocatorResolver}。</p>
 *
 * <h2>多元素时为什么不继续探测</h2>
 * <p>Playwright 的 {@code isVisible()} / {@code isEnabled()} 在定位符解析到多个元素时会抛
 * 严格模式异常。这里不去 {@code first()} 凑一个结果——那恰好是设计明令禁止的"次优回退"。
 * 命中数 ≠ 1 就直接判定，把页面真实状态如实记进证据。</p>
 */
public final class PlaywrightEvidenceProbe implements EvidenceProbe {

    /** 读取可访问名（best-effort，按 aria-label → aria-labelledby → label → value → 文本的顺序）。 */
    private static final String ACCESSIBLE_NAME_JS = """
            el => {
              const norm = s => (s || '').replace(/\\s+/g, ' ').trim();
              const attr = n => (el.getAttribute && el.getAttribute(n)) || '';
              const aria = norm(attr('aria-label'));
              if (aria) return { name: aria, tag: el.tagName.toLowerCase(),
                                  i18n: attr('data-i18n'), testid: attr('data-testid') };
              const lb = attr('aria-labelledby');
              if (lb) {
                const t = norm(lb.split(/\\s+/)
                    .map(id => { const n = document.getElementById(id); return n ? n.innerText : ''; })
                    .join(' '));
                if (t) return { name: t, tag: el.tagName.toLowerCase(),
                                i18n: attr('data-i18n'), testid: attr('data-testid') };
              }
              if (el.labels && el.labels.length) {
                const t = norm(Array.from(el.labels).map(l => l.innerText).join(' '));
                if (t) return { name: t, tag: el.tagName.toLowerCase(),
                                i18n: attr('data-i18n'), testid: attr('data-testid') };
              }
              if (el.tagName === 'INPUT'
                  && ['submit', 'button', 'reset'].includes((el.type || '').toLowerCase())) {
                return { name: norm(el.value), tag: 'input',
                         i18n: attr('data-i18n'), testid: attr('data-testid') };
              }
              const txt = norm(el.innerText || el.textContent);
              return { name: txt, tag: el.tagName.toLowerCase(),
                       i18n: attr('data-i18n'), testid: attr('data-testid') };
            }
            """;

    private final Page page;
    private final NlsCatalog nls;

    public PlaywrightEvidenceProbe(Page page) {
        this(page, null);
    }

    /**
     * @param nls 多语言目录（可空）；用于把 {@code nameKey} 解析为当前语言文案后再比较可访问名
     */
    @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
            value = "EI_EXPOSE_REP2",
            justification = "Playwright Page 是运行期驱动句柄，按设计必须持有；无法做防御性拷贝")
    public PlaywrightEvidenceProbe(Page page, NlsCatalog nls) {
        if (page == null) {
            throw new IllegalArgumentException("page 不可为空：取证需要真实页面");
        }
        this.page = page;
        this.nls = nls;
    }

    @Override
    public StructuralObservation observe(LocatorCandidate candidate) {
        if (candidate == null) {
            return StructuralObservation.unobserved("候选为空");
        }
        LocatorSpec spec = candidate.spec();
        String expectedName = LocatorResolver.expectedName(spec, nls);

        Locator locator;
        int matches;
        try {
            locator = LocatorResolver.resolve(page, spec, nls);
            matches = locator.count();
        } catch (RuntimeException e) {
            return StructuralObservation.unobserved("定位失败：" + brief(e));
        }

        if (matches != 1) {
            String why = matches == 0
                    ? "页面上无匹配元素"
                    : "命中 " + matches + " 个元素，严格模式下无法判定单一目标的状态";
            return new StructuralObservation(matches, false, false, expectedName, null, why);
        }

        boolean visible;
        boolean enabled;
        try {
            visible = locator.isVisible();
            enabled = locator.isEnabled();
        } catch (RuntimeException e) {
            return StructuralObservation.unobserved("状态读取失败：" + brief(e));
        }

        String actualName = null;
        String detail;
        try {
            Object raw = locator.evaluate(ACCESSIBLE_NAME_JS);
            Map<?, ?> info = raw instanceof Map<?, ?> m ? m : Map.of();
            actualName = str(info.get("name"));
            detail = describe(info);
        } catch (RuntimeException e) {
            // 名字读不到不放大成"未观测"：命中数和可见性已经是有效证据，
            // 只是文案一项会因"无从比较"判不一致（fail-closed）。
            detail = "可访问名读取失败：" + brief(e);
        }

        return new StructuralObservation(matches, visible, enabled, expectedName, actualName, detail);
    }

    private static String describe(Map<?, ?> info) {
        StringBuilder sb = new StringBuilder("tag=").append(nullToDash(str(info.get("tag"))));
        String i18n = str(info.get("i18n"));
        if (i18n != null && !i18n.isBlank()) {
            sb.append("；data-i18n=").append(i18n);
        }
        String testId = str(info.get("testid"));
        if (testId != null && !testId.isBlank()) {
            sb.append("；data-testid=").append(testId);
        }
        return sb.toString();
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static String nullToDash(String s) {
        return s == null || s.isBlank() ? "-" : s;
    }

    private static String brief(RuntimeException e) {
        String msg = e.getMessage();
        if (msg == null || msg.isBlank()) {
            return e.getClass().getSimpleName();
        }
        int nl = msg.indexOf('\n');
        return nl < 0 ? msg : msg.substring(0, nl);
    }
}
