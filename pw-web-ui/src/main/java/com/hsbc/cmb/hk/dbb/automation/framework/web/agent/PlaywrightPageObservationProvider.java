package com.hsbc.cmb.hk.dbb.automation.framework.web.agent;

import com.hsbc.cmb.hk.dbb.automation.framework.agent.observation.ObservedElement;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.observation.PageObservation;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.observation.PageObservationProvider;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.LocatorSpec;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.nls.NlsCatalog;
import com.microsoft.playwright.Page;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 真实浏览器页面观测实现（设计 §3.1.3 / §4.6 ② / §4.7）。
 *
 * <p>基于 Playwright 在隔离靶场（{@link RouteTestBedHarness}）采集<b>脱敏、只读</b>的
 * {@link PageObservation}，服务于<b>分析期</b>（资产生产）。与既有
 * {@link PlaywrightChannel#snapshot()}（运行时定位 / 执行用的 ARIA 快照）不同：本类额外读取元素上的
 * {@code data-i18n} 属性，把其<b>直接</b>当作跨语言稳定的 NLS key 填入
 * {@link ObservedElement#nameKey()}，从而比"可访问名 → 反查 key"更精确——尤其是解决
 * <b>同文案多 key</b>（例如两个按钮文案都是 "Submit" 但语义不同）时反查按文件顺序取首个的歧义。</p>
 *
 * <p><b>多语言闭环</b>：元素带 {@code data-i18n="x.y"} → 直接 {@code nameKey="x.y"}（首选，无歧义）；
 * 不带则回退到由 {@link NlsCatalog} 把当前语言可访问名反查为 key（兼容未埋点的页面）。</p>
 *
 * <p>数据最小化（R1/R7）：只采集 role、可访问名、可见文本（截断至 240 字符）、disabled 状态，以及
 * {@code data-i18n} / {@code data-testid} 白名单属性；绝不采集 DOM 全文、截图或网络细节。</p>
 */
public final class PlaywrightPageObservationProvider implements PageObservationProvider {

    /**
     * 采集脚本：遍历 DOM，返回每个有语义角色且可见元素的脱敏视图（含 {@code data-i18n}）。
     *
     * <p>角色计算沿用与 Playwright 无障碍树一致的隐式角色表（{@code <a href>→link}、
     * {@code <button>→button}、{@code <input type=checkbox>→checkbox} 等），显式 {@code role} 优先；
     * 可访问名按 aria-label → aria-labelledby → alt/title/placeholder → textContent 近似
     * （仅在无 {@code data-i18n} 时才用于反查，不影响带埋点元素的资产正确性）。</p>
     */
    private static final String COLLECT_JS = """
            (() => {
              const implicitRole = (el) => {
                const explicit = el.getAttribute && el.getAttribute('role');
                if (explicit) return explicit;
                const tag = el.tagName ? el.tagName.toLowerCase() : '';
                switch (tag) {
                  case 'a': return el.hasAttribute('href') ? 'link' : 'generic';
                  case 'button': return 'button';
                  case 'input': {
                    const t = (el.getAttribute('type') || 'text').toLowerCase();
                    if (t === 'checkbox') return 'checkbox';
                    if (t === 'radio') return 'radio';
                    if (t === 'button' || t === 'submit' || t === 'reset') return 'button';
                    if (t === 'range') return 'slider';
                    if (t === 'search') return 'searchbox';
                    return 'textbox';
                  }
                  case 'select': return el.hasAttribute('multiple') ? 'listbox' : 'combobox';
                  case 'textarea': return 'textbox';
                  case 'img': return 'img';
                  case 'h1': case 'h2': case 'h3': case 'h4': case 'h5': case 'h6': return 'heading';
                  case 'nav': return 'navigation';
                  case 'main': return 'main';
                  case 'header': return 'banner';
                  case 'footer': return 'contentinfo';
                  case 'ul': case 'ol': return 'list';
                  case 'table': return 'table';
                  default: return null;
                }
              };
              const norm = (s) => (s == null ? '' : String(s).replace(/\s+/g, ' ').trim());
              const visible = (el) => {
                if (el.hasAttribute('hidden')) return false;
                const cs = getComputedStyle(el);
                if (cs.visibility === 'hidden' || cs.display === 'none') return false;
                // offsetParent === null 表示脱离布局流（被 display:none 祖先隐藏）；
                // 但 position:fixed 元素 offsetParent 也为 null 却可见，需放行
                return el.offsetParent !== null || cs.position === 'fixed';
              };
              const nameOf = (el) => {
                const al = el.getAttribute('aria-label');
                if (al && al.trim()) return norm(al);
                const alb = el.getAttribute('aria-labelledby');
                if (alb) {
                  const ids = alb.trim().split(/\s+/);
                  let t = '';
                  for (const id of ids) {
                    const n = document.getElementById(id);
                    if (n) t += ' ' + n.textContent;
                  }
                  if (t.trim()) return norm(t);
                }
                const tag = el.tagName.toLowerCase();
                if (tag === 'img') { const a = el.getAttribute('alt'); if (a !== null) return norm(a); }
                const title = el.getAttribute('title');
                if (title && title.trim()) return norm(title);
                if (tag === 'input' || tag === 'textarea') {
                  const ph = el.getAttribute('placeholder');
                  if (ph) return norm(ph);
                }
                return norm(el.textContent);
              };
              const out = [];
              const walk = (node) => {
                const children = node.children;
                for (let i = 0; i < children.length; i++) {
                  const el = children[i];
                  const role = implicitRole(el);
                  if (role && role !== 'generic' && visible(el)) {
                    const disabled = el.disabled === true || el.hasAttribute('disabled') ||
                                     el.getAttribute('aria-disabled') === 'true';
                    out.push({
                      role: role,
                      name: nameOf(el),
                      text: norm(el.textContent).slice(0, 240),
                      dataI18n: el.getAttribute('data-i18n') || null,
                      testId: el.getAttribute('data-testid') || null,
                      disabled: !!disabled
                    });
                  }
                  walk(el);
                }
              };
              walk(document.body);
              return out;
            })()
            """;

    private final Page page;
    private final NlsCatalog nls;

    public PlaywrightPageObservationProvider(Page page, NlsCatalog nls) {
        this.page = page;
        this.nls = nls;
    }

    @Override
    @SuppressWarnings("unchecked")
    public PageObservation observe(String pageRef) {
        List<Map<String, Object>> rows =
                (List<Map<String, Object>>) page.evaluate(COLLECT_JS);
        List<ObservedElement> elements = new ArrayList<>();
        Map<String, Integer> counters = new HashMap<>();
        for (Map<String, Object> r : rows) {
            String role = str(r, "role");
            String name = str(r, "name");
            String dataI18n = str(r, "dataI18n");
            boolean disabled = Boolean.TRUE.equals(r.get("disabled"));
            String text = str(r, "text");
            String testId = str(r, "testId");

            // 同组序号（多个同名 / 同 key 元素时用于 nth 消歧）
            String dedup = role + "|" + (dataI18n != null ? dataI18n : (name == null ? "" : name));
            int nth = counters.getOrDefault(dedup, 0);
            counters.put(dedup, nth + 1);

            List<LocatorSpec> candidates = new ArrayList<>();
            if (dataI18n != null) {
                // 首选「角色 + NLS key」——跨语言稳定，与 codegen 的 Nls.t(key) 同义
                candidates.add(LocatorSpec.roleOfKey(role, dataI18n, nth));
            } else {
                // 回退：字面可访问名定位（单语言或反查失败时的兜底）
                candidates.add(LocatorSpec.role(role, name, nth));
            }

            Map<String, String> attributes = new LinkedHashMap<>();
            if (dataI18n != null) {
                attributes.put("data-i18n", dataI18n);
            }
            if (testId != null) {
                attributes.put("data-testid", testId);
            }

            elements.add(new ObservedElement(role, name, text, !disabled, true,
                    candidates, attributes, dataI18n));
        }
        return new PageObservation(page.url(), pageRef, elements, null);
    }

    private static String str(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v == null ? null : v.toString();
    }
}
