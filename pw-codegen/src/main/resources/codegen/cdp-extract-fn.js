function () {
    var el = this;
    // 状态类 ARIA：值恒为布尔/枚举态，页面上到处都有（aria-hidden="true" 遍布装饰节点与隐藏副本），
    // 当定位锚点零区分度。与 Java 侧 CodegenScorer#ARIA_STATE_ONLY 同口径；
    // 页面侧若不同口径，:has(hr[aria-hidden="true"]) 这类空语义锚点会被当成"稳定锚点"，
    // 逼着生成器再叠 nth-of-type 才能唯一 ⇒ 产物只在拾取那一刻的 DOM 成立。
    var STATE_ONLY_ARIA = {
        'aria-hidden': 1, 'aria-modal': 1, 'aria-expanded': 1, 'aria-selected': 1, 'aria-checked': 1,
        'aria-pressed': 1, 'aria-disabled': 1, 'aria-invalid': 1, 'aria-readonly': 1, 'aria-required': 1,
        'aria-busy': 1, 'aria-multiselectable': 1, 'aria-grabbed': 1, 'aria-dropeffect': 1,
        'aria-orientation': 1, 'aria-haspopup': 1, 'aria-live': 1, 'aria-atomic': 1, 'aria-relevant': 1,
        'aria-sort': 1, 'aria-valuemax': 1, 'aria-valuemin': 1, 'aria-valuenow': 1
    };
    // 纯装饰/无语义标签：作为 :has() 锚点没有业务区分度（hr 只是分隔线）。
    var DECORATIVE_TAGS = {
        'hr': 1, 'br': 1, 'script': 1, 'style': 1, 'meta': 1, 'link': 1, 'source': 1, 'track': 1, 'wbr': 1
    };
    // 状态/布尔属性：进锚点池但固定最低档（标识「当前状态」而非区块语义）。
    var STATE_ATTRS = {
        'style': 1, 'hidden': 1, 'disabled': 1, 'readonly': 1, 'required': 1, 'multiple': 1,
        'selected': 1, 'open': 1, 'checked': 1, 'novalidate': 1, 'tabindex': 1, 'autofocus': 1,
        'data-state': 1, 'data-status': 1, 'data-active': 1, 'data-selected': 1, 'data-open': 1,
        'data-collapsed': 1, 'data-expanded': 1, 'data-loading': 1, 'data-disabled': 1, 'data-visible': 1
    };
    // 锚点优先级（越小越优先）：语义身份 > 通用 > 状态；所有属性进池，换属性不改代码。
    // 状态/存在性属性判定（状态属性表 + 状态类 ARIA 的单一判据）。
    function isStateAttr(name) {
        return !!(STATE_ATTRS[name] || STATE_ONLY_ARIA[name]);
    }
    function attrAnchorPriority(name) {
        if (name === 'data-testid') return 0;
        if (name === 'data-i18n') return 1;
        if (name === 'name') return 2;
        if (name.indexOf('data-') === 0) return 4;
        if (name === 'aria-label' || name === 'aria-labelledby' || name === 'aria-describedby') return 6;
        if (name === 'title' || name === 'alt' || name === 'placeholder' || name === 'for'
            || name === 'type' || name === 'role' || name === 'scope' || name === 'colspan') return 8;
        if (isStateAttr(name)) return 40;
        return 10;
    }
    function segAttrName(seg) {
        if (!seg) return '';
        if (seg.charAt(0) === '#') return 'id';
        var m = /\[\s*([A-Za-z_:][A-Za-z0-9_:.\-]*)\s*=/.exec(seg);
        return m ? m[1].toLowerCase() : '';
    }
    function isStateAnchorSeg(seg) {
        var name = segAttrName(seg);
        if (!name) return false;
        if (isStateAttr(name)) return true;
        return /="\s*\]/.test(seg);
    }
    function roleOf(e) {
        var r = e.getAttribute && e.getAttribute('role');
        if (r) return r.toLowerCase();
        var t = e.tagName ? e.tagName.toLowerCase() : '';
        if (t === 'button') return 'button';
        if (t === 'a' && e.getAttribute('href')) return 'link';
        if (t === 'input') {
            // 按 HTML-AAM 隐式 role 映射（与注入脚本逐条一致）：绝不能一律兜底 textbox，
            // hidden/file/color/date 等无隐式 role，判成 textbox 会生成恒 0 命中的 @RoleElement。
            var ty = (e.getAttribute('type') || 'text').toLowerCase();
            if (ty === 'checkbox') return 'checkbox';
            if (ty === 'radio') return 'radio';
            if (ty === 'submit' || ty === 'button' || ty === 'reset' || ty === 'image') return 'button';
            if (ty === 'range') return 'slider';
            if (ty === 'number') return 'spinbutton';
            if (ty === 'search') return 'searchbox';
            if (ty === 'hidden' || ty === 'file' || ty === 'color'
                    || ty === 'date' || ty === 'time' || ty === 'month'
                    || ty === 'week' || ty === 'datetime-local') return 'generic';
            return 'textbox';
        }
        if (t === 'textarea') return 'textbox';
        if (t === 'select') return e.getAttribute('multiple') != null ? 'listbox' : 'combobox';
        if (/^h[1-6]$/.test(t)) return 'heading';
        if (t === 'img') return 'img';
        if (t === 'nav') return 'navigation';
        if (t === 'main') return 'main';
        if (t === 'table') return 'table';
        if (t === 'li') return 'listitem';
        if (t === 'label') return 'label';
        // 其余隐式 role 按 HTML-AAM 补齐（dialog / 表格子元素 / 列表容器等此前恒 generic）。
        if (t === 'dialog') return 'dialog';
        if (t === 'progress') return 'progressbar';
        if (t === 'meter') return 'meter';
        if (t === 'details' || t === 'fieldset') return 'group';
        if (t === 'figure') return 'figure';
        if (t === 'form') return 'form';
        if (t === 'header') return 'banner';
        if (t === 'footer') return 'contentinfo';
        if (t === 'aside') return 'complementary';
        if (t === 'article') return 'article';
        if (t === 'section') {
            return (e.getAttribute('aria-label') || e.getAttribute('aria-labelledby')) ? 'region' : 'generic';
        }
        if (t === 'ul' || t === 'ol') return 'list';
        if (t === 'tr') return 'row';
        if (t === 'td') return 'cell';
        if (t === 'th') return (e.getAttribute('scope') === 'row') ? 'rowheader' : 'columnheader';
        if (t === 'output') return 'status';
        if (t === 'hr') return 'separator';
        return 'generic';
    }
    // 状态播报区（aria-live / role=status|alert|log）：读屏器的公告，不是控件标签。
    // 必须与 codegen-inject.js 的 isStatusRegion 保持同源（改一处须同步另一处）。
    function isStatusRegion(n) {
        if (!n || !n.getAttribute) return false;
        var live = n.getAttribute('aria-live');
        if (live && String(live).toLowerCase() !== 'off') return true;
        var r = (n.getAttribute('role') || '').toLowerCase();
        return r === 'status' || r === 'alert' || r === 'log';
    }
    // 渲染树剔除（hidden/aria-hidden/display:none/visibility:hidden）：name from content 的通用跳过规则。
    function isRenderingHidden(n) {
        if (!n || n.nodeType !== 1) return false;
        if (n.hidden) return true;
        if (n.getAttribute && n.getAttribute('aria-hidden') === 'true') return true;
        if (typeof window !== 'undefined' && typeof window.getComputedStyle === 'function') {
            try {
                var cs = window.getComputedStyle(n);
                if (cs && (cs.display === 'none' || cs.visibility === 'hidden' || cs.visibility === 'collapse')) return true;
            } catch (e) { }
        }
        return false;
    }
    // name from content：拼接后代文本，跳过状态播报区与渲染剔除子树。HSBC 的按钮里常驻 aria-live
    // 的 "Loading, please wait." span，算进名字就会得到 "Loading, please wait. Continue"
    // （字段名 loadingPleaseWaitContinueBtn），点击后更只剩播报文案 ⇒ 定位器失效。
    function nameTextOf(e) {
        var parts = [];
        (function walk(node) {
            var kids = node.childNodes || [];
            for (var i = 0; i < kids.length; i++) {
                var c = kids[i];
                if (!c) continue;
                if (c.nodeType === 3) { parts.push(c.nodeValue || ''); continue; }
                if (c.nodeType !== 1) continue;
                if (isStatusRegion(c)) continue;
                if (isRenderingHidden(c)) continue;
                walk(c);
            }
        })(e);
        return parts.join('').replace(/\s+/g, ' ').trim();
    }
    // 元素内部状态播报区文本（多个以空格拼接）：供 Java 侧把 AX 名收窄成稳定标签。
    // 有界遍历（深度 6 / 访问 200 节点 / 最多 3 段）：拾取可能落在容器上，无界遍历会拖慢每次抽取。
    function statusTextOf(e) {
        var out = [], visited = 0;
        (function walk(node, depth) {
            if (depth > 6 || out.length >= 3) return;
            var kids = node.childNodes || [];
            for (var i = 0; i < kids.length; i++) {
                if (visited++ > 200 || out.length >= 3) return;
                var c = kids[i];
                if (!c || c.nodeType !== 1) continue;
                if (isStatusRegion(c)) {
                    var t = (c.textContent || '').replace(/\s+/g, ' ').trim();
                    if (t.length > 120) t = t.substring(0, 120);
                    if (t) out.push(t);
                    continue;
                }
                walk(c, depth + 1);
            }
        })(e, 1);
        return out.join(' ');
    }
    // aria-labelledby = 空格分隔 id 列表，逐个取文本拼接（旧实现恒取自身，等于没做）。
    function labelledByText(e) {
        var ids = (e.getAttribute && e.getAttribute('aria-labelledby') || '').trim();
        if (!ids) return '';
        var parts = [], list = ids.split(/\s+/);
        for (var i = 0; i < list.length; i++) {
            if (!list[i]) continue;
            var n = document.getElementById(list[i]);
            if (!n) continue;
            var t = (n.textContent || '').replace(/\s+/g, ' ').trim();
            if (t) parts.push(t);
        }
        return parts.join(' ').trim();
    }
    function accessibleName(e) {
        var by = labelledByText(e);
        if (by) return by;
        var a = e.getAttribute && e.getAttribute('aria-label');
        if (a && a.trim()) return a.trim();
        if (e.id) {
            var l = document.querySelector('label[for="' + (window.CSS ? CSS.escape(e.id) : e.id) + '"]');
            if (l && l.textContent.trim()) return l.textContent.trim();
        }
        var lb = e.closest ? e.closest('label') : null;
        if (lb && lb !== e && lb.textContent.trim()) return lb.textContent.trim();
        var tag = e.tagName ? e.tagName.toLowerCase() : '';
        if (tag === 'img') {
            var alt = e.getAttribute('alt');
            if (alt && alt.trim()) return alt.trim();
        }
        if (tag === 'input') {
            var ty = ((e.getAttribute('type') || 'text') + '').toLowerCase();
            if (ty === 'submit' || ty === 'button' || ty === 'reset') {
                var val = (typeof e.value === 'string') ? e.value.trim() : '';
                if (val) return val;
            }
        }
        var cap = e.querySelector ? e.querySelector('caption, legend, figcaption') : null;
        if (cap) {
            var ct = (cap.textContent || '').replace(/\s+/g, ' ').trim();
            if (ct) return ct;
        }
        var t = nameTextOf(e);
        if (t) return t;
        var ph = e.getAttribute && e.getAttribute('placeholder');
        if (ph && ph.trim()) return ph.trim();
        var title = e.getAttribute && e.getAttribute('title');
        if (title && title.trim()) return title.trim();
        return '';
    }
    function normalizedText(e) {
        var t = (e.textContent || '').replace(/\s+/g, ' ').trim();
        return t.length > 240 ? t.substring(0, 240) : t;
    }
    function labelTextOf(e) {
        if (e.id) {
            var l = document.querySelector('label[for="' + (window.CSS ? CSS.escape(e.id) : e.id) + '"]');
            if (l && l.textContent.trim()) return l.textContent.trim();
        }
        var p = e.parentElement;
        if (p && p.tagName && p.tagName.toLowerCase() === 'label' && p.textContent.trim()) return p.textContent.trim();
        return '';
    }
    // 动态/随机 id 识别（Radix、Headless UI、react-select、MUI、antd 等每次渲染随机），
    // 命中即视为无稳定 id，降级 tag:nth-of-type 结构链或 role/name。保守前缀黑名单，不误伤稳定 id。
    function isDynamicId(id) {
        if (!id || !id.length) return false;
        if (/^_R_/.test(id) || /_R_[A-Za-z0-9]{4,}_/.test(id)) return true;
        if (/:r\d+:/.test(id)) return true;
        if (/^headlessui-/.test(id)) return true;
        if (/^react-select-/.test(id)) return true;
        if (/^mui-\d+$/.test(id)) return true;
        if (/^[a-z]+(-[a-z]+)*-\d{4,}$/.test(id)) return true;
        if (/\d{4,}-id$/.test(id)) return true;
        if (/^[a-z]+(-[a-z]+)*\d{4,}$/.test(id)) return true; // 通用: 段前缀 + ≥4 位尾随数字（无连字符）
        if (/[0-9a-f]{8,}/i.test(id)) return true;            // 十六进制/哈希 token
        if (/[A-Za-z0-9]{8,}/.test(id)) {                      // 高熵 base62 token：≥8 位无分隔符，且（含大小写+≥2 数字）或（≥3 数字），且数字非纯尾随
            var d = (id.match(/[0-9]/g) || []).length;
            if (/[0-9][A-Za-z]/.test(id) && ((/[A-Z]/.test(id) && /[a-z]/.test(id) && d >= 2) || d >= 3)) return true;
        }
        return false;
    }
    /** 结构链上溯层数上限（深层页面 6 层常覆盖不到带 id 的容器）。 */
    var MAX_PATH_DEPTH = 8;
    /** 结构链允许的「裸 tag:nth-of-type(n)」补位层数上限（超过才整条作废）。 */
    var MAX_FILLER_LAYERS = 2;
    /** 单层兜底段：tag:nth-of-type(k)（无父级时退化为裸 tag，如 html）。 */
    function fallbackSeg(n) {
        if (!n || !n.tagName) return '';
        var tag = n.tagName.toLowerCase();
        return groupIndexSel(tag, n) || tag;
    }
    /**
     * 稳定 CSS 选择器：稳定 id 优先；动态 id 先试稳定前缀/包含选择器（唯一才用）；
     * 否则结构链逐层上溯——每层优先接受稳定 id / 动态 id 前缀 / 稳定属性段（含组内索引），
     * 无锚点层用 :nth-of-type 补位（上限 MAX_FILLER_LAYERS 层）；补位超限才返回空串，
     * 交给 Java 侧 XPath 兜底（属性优先：稳定属性精确 → 双/三精确 AND → 含随机值属性 ^=/*=$= 变体）。
     */
    function cssSel(e) {
        if (!e || e.nodeType !== 1) return '';
        if (e.id && !isDynamicId(e.id)) {
            var idSel = '#' + (window.CSS ? CSS.escape(e.id) : e.id);
            if (validCssSel(idSel)) return idSel;
        }
        if (e.id) {
            var fb = dynamicIdFallbackSel(e.id);
            if (fb && validCssSel(fb)) return fb;
        }
        // 自身层（Tier 1）：只接受不挂祖先即全局唯一的自身选择器（属性 / 组合，均经严格唯一校验）。
        var selfNoScope = attrFallbackSel(e, false);
        if (selfNoScope && validCssSel(selfNoScope)) return selfNoScope;
        // 子代锚点层（Tier 2）：元素自身无稳定属性时，借后代的 data-i18n/data-testid/aria-label 等经 :has() 唯一定位。
        var da = descendantAnchorSel(e);
        if (da && validCssSel(da)) return da;
        // 父/祖先作用域层（Tier 3）：把自身选择器挂到最近的、组合后全量唯一的祖先上（隐藏副本短路补丁）。
        var ab = attrFallbackSel(e, true);
        if (ab && validCssSel(ab)) return ab;
        var parts = [];
        var n = e;
        var anchored = false;
        var filler = 0;
        while (n && n.nodeType === 1 && parts.length < MAX_PATH_DEPTH) {
            if (n.id && !isDynamicId(n.id)) { parts.unshift('#' + (window.CSS ? CSS.escape(n.id) : n.id)); anchored = true; break; }
            if (n.id) {
                var pf = dynamicIdFallbackSel(n.id);
                if (pf) { parts.unshift(pf); anchored = true; break; }
            }
            var seg = attrFallbackSel(n);
            // 祖先的稳定 class/属性段（如 modal 的 forgot-username-modal）作为作用域锚点并停止上溯。
            if (seg) { parts.unshift(seg); anchored = true; break; }
            // 该层无稳定锚点也无可用属性段：用 :nth-of-type 补位，超过上限才整条作废。
            if (filler >= MAX_FILLER_LAYERS) return '';
            var fs = fallbackSeg(n);
            if (!fs) return '';
            // 加固：若该层有稳定 class token，给位置段加 class 收窄（更抗 DOM 重排）。
            var fcls = (typeof n.className === 'string') ? n.className : '';
            var ftoks = stableClassTokens(fcls);
            if (ftoks.length) {
                var ftag = n.tagName.toLowerCase();
                fs = fs.replace(ftag, ftag + '[class*="' + attrEsc(ftoks[0]) + '"]');
            }
            parts.unshift(fs);
            filler++;
            n = n.parentElement;
        }
        if (!anchored) return '';
        var pathSel = parts.join(' > ');
        // 终极护栏（双重）：① 语法非法（回归式畸形拼接）⇒ 作废；② 当前零命中 ⇒ 同样作废。
        // ② 防的是"层级错位死链"（如 … > div:nth-of-type(3) button 而父级子元素实为 button）：
        // 语法合法但一个都匹配不到，回放必然等不到元素，交给带唯一性校验的 XPath / role 兜底更好。
        if (!validCssSel(pathSel) || totalCount(pathSel) <= 0) return '';
        return pathSel;
    }
    // 动态 id 稳定前缀/包含选择器兜底（唯一才用）；无前缀/不唯一返回空串交给结构链
    function stableIdPrefix(id) {
        var m = id.match(/^(.*?)(?:_R_[A-Za-z0-9]{4,}_|:r\d+:|-\d{4,}|\d{4,}$|[0-9a-f]{8,})/i);
        return m ? m[1] : '';
    }
    // 动态 id 的稳定后缀：截掉开头随机段（Radix 夹心/`:rN:`/数字段+连字符）后，剩余尾部在组件语义上稳定。
    function stableIdSuffix(id) {
        var m = id.match(/^(?:_R_[A-Za-z0-9]{4,}_|:r\d+:|\d{4,}-|[0-9a-f]{8,})(.*)$/i);
        return m ? m[1] : '';
    }
    function attrEsc(v) {
        return String(v).replace(/["\\]/g, function (c) { return '\\' + c; });
    }
    /** 可见性判定：hidden、aria-hidden="true"、display:none / visibility:hidden（含祖先，经计算样式）、
     * visibility:collapse、opacity:0（含祖先链，不继承需逐级查）、clip 裁零 / clip-path 裁零、
     * 零尺寸或整体移出视口（响应式/移动端隐藏副本常见）均视为隐藏。
     *
     * <p>祖先链必须逐级查 display / visibility：getComputedStyle(child).display 返回元素自身计算值，
     * 父级 display:none 不会传递下来——只看自身会把「藏在 style="display: none;" 面板里的副本」判成可见。
     * 祖先上限 10 层。
     *
     * <p>本函数只回答「渲染是否可见」，不等于「应从候选排除」：class="accessibleHidden" / .sr-only
     * 这类元素渲染不可见但仍在可访问性树里（getByRole 能命中，往往是唯一可访问名来源）。
     * 只有「渲染树剔除 ⇒ 同时从 AX 树剔除」的形态（display:none / visibility:hidden / hidden 属性 /
     * aria-hidden="true"）才可以作为排除依据。 */
    /** CSS clip / clip-path 是否「裁成零尺寸」（通用判定，不再枚举具体字面量）。 */
    function isZeroClip(clip) {
        var m = /^rect\(([^)]*)\)$/i.exec(String(clip || '').trim());
        if (!m) return false;
        var parts = m[1].split(/[\s,]+/);
        if (parts.length !== 4) return false;
        for (var i = 0; i < 4; i++) {
            if (!/^0(?:px|%)?$/i.test(parts[i])) return false;
        }
        return true;
    }
    function isZeroClipPath(cp) {
        var s = String(cp || '').trim().toLowerCase();
        if (!s) return false;
        if (/^inset\(\s*(?:0(?:px|%)?[\s,]*){4}\)$/.test(s)) return true;
        if (/^inset\(\s*0(?:px|%)?\s*\)$/.test(s)) return true;
        if (/^inset\(\s*50%\s*\)$/.test(s)) return true;
        if (/^circle\(\s*0(?:px|%)?\s*(?:at\b[^)]*)?\)$/.test(s)) return true;
        if (/^polygon\(\s*(?:0(?:px|%)?\s+0(?:px|%)?\s*,?\s*)+\)$/.test(s)) return true;
        return false;
    }

    function isVisible(e) {
        if (!e || e.nodeType !== 1) return false;
        if (e.hidden) return false;
        if (e.getAttribute && e.getAttribute('aria-hidden') === 'true') return false;
        if (e.style && (e.style.display === 'none' || e.style.visibility === 'hidden')) return false;
        if (typeof window !== 'undefined' && typeof window.getComputedStyle === 'function') {
            try {
                var cs = window.getComputedStyle(e);
                if (cs.display === 'none' || cs.visibility === 'hidden' || cs.visibility === 'collapse') return false;
                if (cs.opacity === '0' || cs.opacity === '0.0' || cs.opacity === '0.00') return false;
                if (cs.clip && isZeroClip(cs.clip)) return false;
                if (cs.clipPath && isZeroClipPath(cs.clipPath)) return false;
                var p = e.parentElement, d = 0;
                while (p && p.nodeType === 1 && d < 10) {
                    var pcs = window.getComputedStyle(p);
                    if (pcs.display === 'none' || pcs.visibility === 'hidden' || pcs.visibility === 'collapse') return false;
                    if (pcs.opacity === '0' || pcs.opacity === '0.0' || pcs.opacity === '0.00') return false;
                    p = p.parentElement; d++;
                }
            } catch (err) { }
        }
        if (typeof e.getBoundingClientRect === 'function') {
            try {
                var r = e.getBoundingClientRect();
                if (!r || (r.width === 0 && r.height === 0)) return false;
                var vw = (typeof window !== 'undefined' && window.innerWidth) || 0;
                var vh = (typeof window !== 'undefined' && window.innerHeight) || 0;
                if (vw > 0 && vh > 0 && (r.right <= 0 || r.bottom <= 0 || r.left >= vw || r.top >= vh)) return false;
            } catch (err) { }
        }
        return true;
    }


    /**
     * 匹配总数（<b>含隐藏元素</b>）。
     *
     * <p>Playwright 的 {@code locator(sel)} 不做可见性过滤，strict mode 下多匹配直接抛错，
     * 故唯一性必须按 DOM 全量计数。只数可见会漏掉隐藏的重复副本——典型如隐藏面板里那个
     * 同名 "Continue" 按钮：拾取时被判唯一，运行期面板一显示立刻变成多匹配。
     * 这是"重复面板里 Continue 按钮定位不准"的根因（2026-11-11 用户反馈）。
     */
    /**
     * 匹配总数（<b>含隐藏元素</b>）。
     *
     * <p>Playwright 的 {@code locator(sel)} 不做可见性过滤，strict mode 下多匹配直接抛错，
     * 故唯一性必须按 DOM 全量计数。只数可见会漏掉隐藏的重复副本——典型如隐藏面板里那个
     * 同名 "Continue" 按钮：拾取时被判唯一，运行期面板一显示立刻变成多匹配。
     * 这是"重复面板里 Continue 按钮定位不准"的根因（2026-11-11 用户反馈）。
     */
    function totalCount(sel) {
        try {
            if (!sel) return 0;
            return document.querySelectorAll(sel).length;
        } catch (e) { return 0; }
    }

    /**
     * 唯一性校验：DOM 全量匹配恰好 1 个（隐藏元素同样参与计数，对齐 Playwright strict mode）。
     * 绝不使用 Playwright 专有可见性伪类（原生 {@code document} 不识别，会抛 SyntaxError）。
     */
    function uniqueSel(sel) {
        return totalCount(sel) === 1;
    }

    function dynamicIdFallbackSel(id) {
        var prefix = stableIdPrefix(id);
        if (prefix) {
            var byPrefix = '[id^="' + attrEsc(prefix) + '"]';
            if (uniqueSel(byPrefix)) return byPrefix;
        }
        var suffix = stableIdSuffix(id);
        if (suffix) {
            var bySuffix = '[id$="' + attrEsc(suffix) + '"]';
            if (uniqueSel(bySuffix)) return bySuffix;
        }
        var core = (prefix || suffix).replace(/^[-_]+|[-_]+$/g, '');
        if (core) {
            var byCore = '[id*="' + attrEsc(core) + '"]';
            if (uniqueSel(byCore)) return byCore;
        }
        return '';
    }
    // 资源 URL 锚点池：标签 → 承载 URL 的属性（同类问题一次覆盖；不含 a[href]，见注入脚本说明）。
    var RESOURCE_URL_ATTRS = {
        img: ['src'], video: ['poster', 'src'], audio: ['src'], source: ['src'],
        embed: ['src'], object: ['data'], form: ['action'], track: ['src']
    };
    function resourceUrlTail(url) {
        var s = String(url || '').trim();
        if (!s) return '';
        if (/^(javascript:|#|mailto:|tel:|data:|blob:)/i.test(s)) return '';
        return srcTail(s);
    }
    // 取 src 尾部文件名（URL 末段，去 query/hash）；data URI / 空值返回空串。
    function srcTail(s) {
        if (!s) return '';
        s = String(s).trim();
        if (s.indexOf('data:') === 0) return '';
        var q = s.indexOf('?'); if (q >= 0) s = s.substring(0, q);
        var h = s.indexOf('#'); if (h >= 0) s = s.substring(0, h);
        var sl = s.lastIndexOf('/'); if (sl >= 0) s = s.substring(sl + 1);
        return s.length >= 3 ? s : '';
    }
    // 语言无关的 src 文件名前缀：xxx_EN.png / xxx_zh-HK.png / xxx.en_US.png 切语言后整条换名，
    // 必须锚定「语言段之前」的前缀（img[src*="xxx"]）；只吃最后一个语言段，前缀 < 4 字符不归一化。
    // 语言/地区段（同 codegen-inject.js）：新增语言只改这一处。
    var SRC_TRAILING_LOCALE = /^(.{4,}?)[._-](?:en|zh|tc|sc|hk|cn|tw|ja|jp|ko|kr|fr|de|es|pt|it|nl|ru|ar|th|vi|id|ms|hi|tr|pl|sv|da|fi|no|cs|el|he|hu|ro|sk|uk)(?:[._-](?:us|gb|uk|hk|cn|tw|sg|au|ca|in|jp|kr|de|fr|es|pt|br|mx|it|nl|ru|ae|sa|th|vn|id|my|tr|pl|hans|hant))?$/i;
    function srcLocaleNeutralPrefix(tail) {
        if (!tail) return '';
        var stem = String(tail).replace(/[.][A-Za-z0-9]{1,5}$/, '');
        var m = SRC_TRAILING_LOCALE.exec(stem);
        if (!m) return '';
        var prefix = m[1].replace(/[._-]+$/, '');
        return prefix.length >= 4 ? prefix : '';
    }
    // class 里的稳定类名（空格切分，滤掉框架随机 token 与过短碎片）。
    function stableClassTokens(cls) {
        var out = [], seen = {};
        if (!cls) return out;
        var raw = String(cls).trim().split(/\s+/);
        for (var i = 0; i < raw.length; i++) {
            var t = raw[i];
            if (t.length < 3) continue;
            // 随机 token 判据与 id / 属性值同源（RAND_RE），避免同一字符串在不同专档得到相反判定。
            if (RAND_RE.test(t)) continue;                          // Radix 夹心 / 数字段 / 长 hash / 高熵 base62
            if (/^(css|sc)-[a-z0-9]{5,}$/i.test(t)) continue;      // Emotion css-xxx
            if (/^[a-z]{16,}$/.test(t)) continue;                   // 无数字的长纯字母 hash（RAND_RE 无法判定）
            if (seen[t]) continue;
            seen[t] = true;
            out.push(t);
        }
        return out;
    }
    /**
     * 随机段判定（id / 属性值 / class token 的单一事实来源）：Radix 夹心（_R_xxxx_ / :rN:）、
     * ≥4 位数字段、≥6 位纯数字、≥10 位十六进制、高熵 base62 token。
     */
    var RAND_RE = /_R_[A-Za-z0-9]{4,}_|:r\d+:|\d{4,}-|-\d{4,}|\d{6,}|[0-9a-fA-F]{10,}|(?=[A-Za-z0-9]*[0-9][A-Za-z])(?:(?=(?:[^0-9]*[0-9]){3})|(?=[^A-Z]*[A-Z])(?=[^a-z]*[a-z])(?=(?:[^0-9]*[0-9]){2}))[A-Za-z0-9]{8,}/;

    /** 属性值稳定段：值无随机段 → 精确(=)；含随机段（Radix 夹心 / :rN: / 数字段）→ 稳定前缀 ^=、核心 *=、稳定后缀 $=（段自身无随机段、长度 2~40，避免随机值；^= → *= → $= 顺序）。 */
    function attrValueSegments(v) {
        if (!v) return [];
        v = String(v).trim();
        if (!v || v.length > 40) return [];
        var rand = RAND_RE;
        if (!rand.test(v)) return [{ op: '=', seg: v }];
        var parts = v.split(rand);
        var head = (parts[0] || '').replace(/^[-_ :]+|[-_ :]+$/g, '');
        var tail = (parts[parts.length - 1] || '').replace(/^[-_ :]+|[-_ :]+$/g, '');
        var core = parts.join(' ').replace(/\s+/g, ' ').trim();
        var out = [];
        if (head.length >= 2 && !rand.test(head)) out.push({ op: '^=', seg: head });
        if (core.length >= 2 && core !== head && !rand.test(core)) out.push({ op: '*=', seg: core });
        if (tail.length >= 2 && tail !== head && tail !== core && !rand.test(tail)) out.push({ op: '$=', seg: tail });
        return out;
    }

    /** 稳定属性候选（id/class/style/src 各走专档）：值非空且 ≤40 字符、非纯数字长串；含随机段的属性不再丢弃，标 rand=1（值用 ^=/*=$= 变体）；稳定（rand=0）排前、按文档顺序，上限 6。 */
    function stableAttrs(e, withState) {
        var out = [], seen = {};
        var attrs = e.attributes;
        if (!attrs) return out;
        var rand = RAND_RE;
        for (var i = 0; i < attrs.length; i++) {
            var a = attrs[i];
            var n = a.name;
            if (n === 'id' || n === 'class' || n === 'src') continue;
            var state = isStateAttr(n);
            var v = (a.value == null) ? '' : String(a.value);
            v = v.trim();
            // 非空 style 不猜：display:block / display:none 之类随组件版本漂移。
            if (n === 'style' && v) continue;
            if (!v) {
                // 空值只在锚点池里、且只收状态/布尔属性（style=""、hidden=""、readonly=""…）
                if (!withState || !state || seen[n]) continue;
                seen[n] = true;
                out.push({ name: n, value: '', rand: 0, pri: 40, state: 1 });
                continue;
            }
            if (v.length > (withState ? 60 : 40)) continue;
            if (/^[\d.,-]+$/.test(v) && v.length > 6) continue;
            if (!withState && state) continue;
            if (seen[n]) continue;
            seen[n] = true;
            out.push({
                name: n, value: v, rand: rand.test(v) ? 1 : 0,
                pri: withState ? attrAnchorPriority(n) : 0,
                state: state ? 1 : 0
            });
            }
            out.sort(function (x, y) { return (x.pri - y.pri) || (x.rand - y.rand); });
        if (out.length > 6) out.length = 6;
        return out;
    }

    // 单节点属性锚点候选段（按 attrAnchorPriority 排序；withState 时状态锚点排最后）——锚点收集的唯一事实来源。
    function anchorSegsOf(n, withState) {
        var out = [], states = [];
        if (!n || !n.attributes) return out;
        var attrs = stableAttrs(n, !!withState);
        for (var i = 0; i < attrs.length; i++) {
            if (attrs[i].rand) continue;
            var seg = '[' + attrs[i].name + '="' + attrEsc(attrs[i].value) + '"]';
            if (isStateAnchorSeg(seg)) {
                if (withState) states.push({ seg: seg, state: 1 });
                continue;
            }
            out.push({ seg: seg, state: 0 });
        }
        return out.concat(states);
    }
    function bestAnchorSeg(n, withState) {
        if (!n) return { seg: '', state: 0 };
        if (n.id && !isDynamicId(n.id)) {
            return { seg: '#' + (window.CSS ? CSS.escape(n.id) : n.id), state: 0 };
        }
        var list = anchorSegsOf(n, withState);
        return list.length ? list[0] : { seg: '', state: 0 };
    }

    /** 可见匹配计数（唯一性之外，用于识别"一组元素"场景）。 */
    function visibleCount(sel) {
        try {
            if (!sel) return 0;
            var nodes = document.querySelectorAll(sel), n = 0;
            for (var i = 0; i < nodes.length; i++) if (isVisible(nodes[i])) n++;
            return n;
        } catch (e) { return 0; }
    }

    /** 组内索引：候选在父下同 tag 兄弟中的第 k 个（1 起），产出 {@code sel:nth-of-type(k)}；无父/无 tag 返回空串。 */
    function groupIndexSel(sel, el) {
        var parent = el && el.parentElement;
        if (!parent || !el.tagName) return '';
        var tag = el.tagName.toLowerCase();
        var k = 0, sibs = parent.children || [];
        for (var i = 0; i < sibs.length; i++) {
            var c = sibs[i];
            if (c && c.nodeType === 1 && c.tagName && c.tagName.toLowerCase() === tag) {
                k++;
                if (c === el) return sel + ':nth-of-type(' + k + ')';
            }
        }
        return '';
    }

    /**
     * CSS 选择器语法合法性护栏：拦截一切非法选择器（如把两个属性塞进同一方括号），杜绝畸形串写进 cssSelector。
     * 全部为原生合法 CSS —— 绝不使用 Playwright 专有可见性伪类（原生 document 不识别，会抛 SyntaxError）。
     */
    function validCssSel(sel) {
        if (typeof sel !== 'string' || !sel) return false;
        // 结构性畸形：同一方括号内出现两个以空白分隔的 name=value 段（把两个属性塞进同一方括号），杜绝畸形串写进 cssSelector。
        var depth = 0, seg = '';
        for (var i = 0; i < sel.length; i++) {
            var ch = sel.charAt(i);
            if (ch === '[') { depth++; seg = ''; }
            else if (ch === ']') { depth--; seg = ''; }
            else if (depth > 0) {
                seg += ch;
                if (ch === ' ' || ch === '\t' || ch === '\n' || ch === '\r') {
                    var f = seg.indexOf('=');
                    if (f >= 0 && seg.indexOf('=', f + 1) >= 0) return false;
                }
            }
        }
        try {
            document.querySelectorAll(sel);
            return true;
        } catch (e) {
            return false;
        }
    }

    /** 隐藏副本判别符（按优先级）：d-none / aria-hidden / hidden / 行内 display:none。
     *  全部为原生合法 CSS —— 绝不使用 Playwright 专有可见性伪类（原生 document 不识别，会抛 SyntaxError）。 */
    var HIDDEN_EXCLUDES = [':not(.d-none)', ':not([aria-hidden="true"])', ':not([hidden])',
        ':not([style*="display: none"])', ':not([style*="display:none"])'];
    function scopeSegExcludingHidden(seg) {
        if (!seg) return '';
        for (var i = 0; i < HIDDEN_EXCLUDES.length; i++) {
            var ex = HIDDEN_EXCLUDES[i];
            var s2 = seg + ex;
            // 仅在「判别符确实剔除了隐藏副本（总匹配下降且仍 >0）」时采纳，避免误把可见副本也排除。
            var c2 = totalCount(s2);
            if (c2 > 0 && c2 < totalCount(seg)) return s2;
        }
        return '';
    }

    /** 祖先作用域收窄（隐藏副本短路补丁）：把 sel 挂到最近的、组合后全量唯一的祖先作用域上；
     *  候选集叠加“后代锚点”候选（按钮内子元素往往更精确）。只接受组合后全量唯一 / 可见唯一的结果。 */
    function scopeUnderUniqueAncestor(sel, e) {
        if (!sel || !e) return '';
        var cands = [sel];
        if (sel.indexOf(':has') === -1) {
            var dacs = descendantAnchorCandidates(e);
            for (var d = 0; d < dacs.length; d++) cands.push(dacs[d]);
        }
        var n = e.parentElement;
        var guard = 0;
        while (n && n.nodeType === 1 && guard++ < 12) {
            var t = n.tagName ? n.tagName.toLowerCase() : '';
            if (!t || t === 'html') break;
            var seg = '';
            if (n.id && !isDynamicId(n.id)) {
                seg = '#' + (window.CSS ? CSS.escape(n.id) : n.id);
            } else if (n.id) {
                var pf = dynamicIdFallbackSel(n.id);
                if (pf) seg = pf;
            }
            if (!seg && t) {
                var cls = (typeof n.className === 'string') ? n.className : '';
                var toks = stableClassTokens(cls);
                for (var i = 0; i < toks.length && !seg; i++) {
                    var ts = t + '[class*="' + attrEsc(toks[i]) + '"]';
                    if (uniqueSel(ts)) seg = ts;
                }
            }
            if (!seg && t) {
                var aSegs = anchorSegsOf(n, false);
                for (var a = 0; a < aSegs.length && !seg; a++) {
                    var as = t + aSegs[a].seg;
                    if (uniqueSel(as)) seg = as;
                }
            }
            if (!seg) {
                var f = fallbackSeg(n);
                if (f) seg = f;
            }
            if (seg) {
                var isDirectParent = (n === e.parentElement);
                for (var c = 0; c < cands.length; c++) {
                    var combined = seg + ' ' + cands[c];
                    if (uniqueSel(combined)) return combined;
                    if (isDirectParent) {
                        // 直接父级 + 子代轴（>）：隐藏副本常是目标的兄弟，用父级差异（如 body vs div[display:none]）
                        // 即可把「该父级下唯一」的目标单独摘出——这是「父级标签+属性」兜底档。
                        var childCombined = seg + ' > ' + cands[c];
                        if (uniqueSel(childCombined)) return childCombined;
                        var parentRefined = scopeSegExcludingHidden(seg);
                        if (parentRefined) {
                            var prc = parentRefined + ' > ' + cands[c];
                            if (uniqueSel(prc)) return prc;
                        }
                    }
                    if (visibleCount(combined) === 1) {
                        // 隐藏副本短路补丁（生成侧）：给祖先段附加原生排除判别符，收窄到可见副本。
                        var refined = scopeSegExcludingHidden(seg);
                        if (refined) {
                            var rc = refined + ' ' + cands[c];
                            if (uniqueSel(rc)) return rc;
                        }
                    }
                }
            }
            n = n.parentElement;
        }
        return '';
    }

    /** 子代锚点候选：用目标后代（深度≤3、稳定属性元素）构造 t:has(后代[稳定属性="值"]) 形式的选择器，
     *  并追加“自身稳定信号 × 后代锚点”的 AND 复合加固。 */
    function descendantAnchorCandidates(e) {
        var t = e.tagName ? e.tagName.toLowerCase() : '';
        var out = [];
        if (!t || t === 'html' || t === 'body') return out;
        var selfAttrs = [];
        var sa = stableAttrs(e);
        for (var z = 0; z < sa.length && selfAttrs.length < 2; z++) {
            if (!sa[z].rand) selfAttrs.push(sa[z]);
        }
        var selfCls = [];
        var cls = (typeof e.className === 'string') ? e.className : '';
        var ctoks = stableClassTokens(cls);
        for (var c0 = 0; c0 < ctoks.length && selfCls.length < 2; c0++) {
            selfCls.push(ctoks[c0]);
        }
        var plainDescs = [];
        var queue = [], visited = 0, seen = new Set();
        for (var i = 0; i < e.children.length; i++) queue.push({ n: e.children[i], d: 1 });
        while (queue.length && visited < 120) {
            var cur = queue.shift();
            var n = cur.n;
            if (!n || n.nodeType !== 1) continue;
            // 装饰标签不产 :has 候选（hr 只是分隔线，不能当锚点）。
            if (DECORATIVE_TAGS[(n.tagName || '').toLowerCase()]) continue;
            visited++;
            var segs = anchorSegsOf(n, false);
            for (var j = 0; j < segs.length; j++) {
                var key = segAttrName(segs[j].seg);
                if (!key || seen.has(key)) continue;
                seen.add(key);
                plainDescs.push({
                    ds: t + ':has(' + n.tagName.toLowerCase() + segs[j].seg + ')',
                    pri: anchorPriority(n, segs[j].seg)
                });
            }
            if (cur.d < 3) {
                for (var k = 0; k < n.children.length; k++) queue.push({ n: n.children[k], d: cur.d + 1 });
            }
        }
        // 标题型锚点（分区标识）优先于任意后代（见 anchorPriority）；同优先级保持 DOM 顺序。
        plainDescs.sort(function (a, b) { return a.pri - b.pri; });
        for (var q0 = 0; q0 < plainDescs.length; q0++) out.push(plainDescs[q0].ds);
        for (var p = 0; p < plainDescs.length; p++) {
            var idx = plainDescs[p].ds.indexOf(':has(');
            if (idx < 0) continue;
            var hasPart = plainDescs[p].ds.slice(idx);
            for (var a = 0; a < selfAttrs.length; a++) {
                var ownA = '[' + selfAttrs[a].name + '="' + attrEsc(selfAttrs[a].value) + '"]';
                out.push(t + ownA + hasPart);
                for (var b = a + 1; b < selfAttrs.length; b++) {
                    var ownB = '[' + selfAttrs[b].name + '="' + attrEsc(selfAttrs[b].value) + '"]';
                    out.push(t + ownA + ownB + hasPart);
                }
            }
            for (var x = 0; x < selfCls.length; x++) {
                var ownC = '[class*="' + attrEsc(selfCls[x]) + '"]';
                out.push(t + ownC + hasPart);
                for (var y = x + 1; y < selfCls.length; y++) {
                    var ownD = '[class*="' + attrEsc(selfCls[y]) + '"]';
                    out.push(t + ownC + ownD + hasPart);
                }
            }
            for (var a2 = 0; a2 < selfAttrs.length; a2++) {
                var oa = '[' + selfAttrs[a2].name + '="' + attrEsc(selfAttrs[a2].value) + '"]';
                for (var x2 = 0; x2 < selfCls.length; x2++) {
                    var oc = '[class*="' + attrEsc(selfCls[x2]) + '"]';
                    out.push(t + oa + oc + hasPart);
                }
            }
        }
        return out;
    }

    function descendantAnchorSel(e) {
        var cands = descendantAnchorCandidates(e);
        for (var i = 0; i < cands.length; i++) {
            if (uniqueSel(cands[i])) return cands[i];
            var vc = visibleCount(cands[i]);
            if (vc > 0 && vc <= 10) {
                var sc = scopeUnderUniqueAncestor(cands[i], e);
                if (sc) return sc;
            }
        }
        return '';
    }

    /**
     * 唯一或组内索引（allowScope 控制是否允许祖先作用域收窄）。
     *
     * <p>① 全量唯一（含隐藏）直接用；② 否则只要存在“有限个可见匹配”就尝试 :nth-of-type(k) 收窄；
     * ③ 隐藏副本导致的非唯一：尝试祖先作用域收窄（scopeUnderUniqueAncestor）；
     * ④ 祖先作用域也失效时（隐藏副本与可见副本祖先结构相同）：本层自身即“可见命中恰 1”，
     * 直接用排除隐藏副本的原生判别符收窄本层自身段（如 :not(.d-none)），恢复 strict mode 唯一性。
     */
    function uniqueOrGroup(sel, e, allowScope) {
        if (allowScope === undefined) allowScope = true;
        if (!sel || !e) return '';
        if (uniqueSel(sel)) return sel;
        if (!allowScope) return '';
        var cnt = visibleCount(sel);
        if (cnt > 0 && cnt <= 10) {
            var g = groupIndexSel(sel, e);
            if (g && uniqueSel(g)) return g;
            var sc = scopeUnderUniqueAncestor(sel, e);
            if (sc) return sc;
            if (cnt === 1) {
                var refined = scopeSegExcludingHidden(sel);
                if (refined) return refined;
            }
        }
        return '';
    }
    /**
     * 目标元素属性兜底（在结构链之前）：无 id / 动态 id 无法唯一时，先试
     * {@code img[src$="文件名"]}、稳定 class token / class 双 token AND，然后按「最稳定优先」用属性：
     * ① 稳定属性（值无随机段）精确 {@code [name="value"]}；② 稳定属性双→三精确 AND；③ 含随机值属性
     * 用开头 {@code ^=}、包含 {@code *=}、结尾 {@code $=} 变体（段自身无随机值）；④ 组合层：单属性仍
     * 不唯一时多个属性共同定位——稳定属性精确 AND 随机变体（最稳定优先）、随机变体 × 随机变体 AND。
     * 每个候选先做<b>全量</b>唯一性校验（含隐藏元素，对齐 Playwright strict mode）——唯一即采用；
     * 否则尝试组内索引再复核；全部失败返回空串交给结构链/XPath。
     */
    function attrFallbackSel(e, allowScope) {
        if (allowScope === undefined) allowScope = true;
        if (!e || e.nodeType !== 1) return '';
        var t = e.tagName.toLowerCase();
        // 资源 URL 锚点（原先只处理 img.src，其余同类标签直接落到 class/结构链）。
        var urlAttrs = RESOURCE_URL_ATTRS[t];
        if (urlAttrs) {
            for (var u = 0; u < urlAttrs.length; u++) {
                var tail = resourceUrlTail(e.getAttribute && e.getAttribute(urlAttrs[u]));
                if (!tail || RAND_RE.test(tail)) continue;
                var neutral = srcLocaleNeutralPrefix(tail);
                var byTail = t + '[' + urlAttrs[u] + (neutral ? '*=' : '$=') + '"'
                    + attrEsc(neutral || tail) + '"]';
                var s0 = uniqueOrGroup(byTail, e, allowScope);
                if (s0) return s0;
            }
        }
        var cls = (typeof e.className === 'string') ? e.className : '';
        var toks = stableClassTokens(cls);
        for (var i = 0; i < toks.length; i++) {
            var byCls = t + '[class*="' + attrEsc(toks[i]) + '"]';
            var s1 = uniqueOrGroup(byCls, e, allowScope);
            if (s1) return s1;
        }
        // class 双 token AND：单 token 不唯一时，两个稳定 token 组合常能收敛。
        for (var i = 0; i < toks.length && i < 4; i++) {
            for (var j = i + 1; j < toks.length && j < 4; j++) {
                var byCls2 = t + '[class*="' + attrEsc(toks[i]) + '"][class*="' + attrEsc(toks[j]) + '"]';
                var s2 = uniqueOrGroup(byCls2, e, allowScope);
                if (s2) return s2;
            }
        }
        // 跨属性：按「最稳定优先」用属性——稳定属性（值无随机段）单精确 → 双/三精确 AND → 含随机值属性 ^=/*=$= 变体。
        var names = stableAttrs(e);
        for (var i = 0; i < names.length && !names[i].rand; i++) {
            var by1 = t + '[' + names[i].name + '="' + attrEsc(names[i].value) + '"]';
            var s3 = uniqueOrGroup(by1, e, allowScope);
            if (s3) return s3;
        }
        for (var i = 0; i < names.length && !names[i].rand; i++) {
            for (var j = i + 1; j < names.length && !names[j].rand; j++) {
                var by2 = t + '[' + names[i].name + '="' + attrEsc(names[i].value) + '"][' + names[j].name + '="' + attrEsc(names[j].value) + '"]';
                var s4 = uniqueOrGroup(by2, e, allowScope);
                if (s4) return s4;
            }
        }
        for (var i = 0; i < names.length && !names[i].rand; i++) {
            for (var j = i + 1; j < names.length && !names[j].rand; j++) {
                for (var k = j + 1; k < names.length && !names[k].rand; k++) {
                    var by3 = t + '[' + names[i].name + '="' + attrEsc(names[i].value) + '"][' + names[j].name + '="' + attrEsc(names[j].value) + '"][' + names[k].name + '="' + attrEsc(names[k].value) + '"]';
                    var s6 = uniqueOrGroup(by3, e, allowScope);
                    if (s6) return s6;
                }
            }
        }
        for (var i = 0; i < names.length; i++) {
            if (!names[i].rand) continue;
            var segs = attrValueSegments(names[i].value);
            for (var m = 0; m < segs.length; m++) {
                var byV = t + '[' + names[i].name + segs[m].op + '"' + attrEsc(segs[m].seg) + '"]';
                var s5 = uniqueOrGroup(byV, e, allowScope);
                if (s5) return s5;
            }
        }
        // 组合层：单属性仍不唯一时多个属性共同定位——稳定属性精确 AND 随机属性变体 / 随机变体 × 随机变体 AND。
        var randNames = [], stabNames = [];
        for (var i = 0; i < names.length; i++) if (names[i].rand) randNames.push(names[i]);
        for (var i = 0; i < names.length && !names[i].rand && i < 2; i++) stabNames.push(names[i]);
        for (var a = 0; a < stabNames.length; a++) {
            for (var b = 0; b < randNames.length && b < 2; b++) {
                var rbSegs = attrValueSegments(randNames[b].value);
                for (var m = 0; m < rbSegs.length; m++) {
                    var byMix = t + '[' + stabNames[a].name + '="' + attrEsc(stabNames[a].value) + '"][' + randNames[b].name + rbSegs[m].op + '"' + attrEsc(rbSegs[m].seg) + '"]';
                    var s7 = uniqueOrGroup(byMix, e, allowScope);
                    if (s7) return s7;
                }
            }
        }
        for (var b = 0; b < randNames.length && b < 2; b++) {
            for (var c = b + 1; c < randNames.length && c < 2; c++) {
                var rb2Segs = attrValueSegments(randNames[b].value);
                var rcSegs = attrValueSegments(randNames[c].value);
                for (var m = 0; m < rb2Segs.length; m++) {
                    for (var m2 = 0; m2 < rcSegs.length; m2++) {
                        var byRand = t + '[' + randNames[b].name + rb2Segs[m].op + '"' + attrEsc(rb2Segs[m].seg) + '"][' + randNames[c].name + rcSegs[m2].op + '"' + attrEsc(rcSegs[m2].seg) + '"]';
                        var s8 = uniqueOrGroup(byRand, e, allowScope);
                        if (s8) return s8;
                    }
                }
            }
        }
        return '';
    }

    function siblingIndex(e) { var i = 0, s = e.previousElementSibling; while (s) { i++; s = s.previousElementSibling; } return i; }
    // 绝对 XPath（结构 CSS 无法唯一时的兜底定位器）。shadow root 内返回空串：XPath 表达不了
    // "穿透 shadow 边界"的路径，那种情况由 shadowHosts + CSS 负责。
    function absXPath(e) {
        if (!e || e.nodeType !== 1) return '';
        var root = e.getRootNode ? e.getRootNode() : null;
        if (root && root.nodeType === 11) return '';
        var parts = [], n = e, guard = 0;
        while (n && n.nodeType === 1 && guard++ < 64) {
            var t = n.tagName.toLowerCase();
            if (t === 'html') { parts.unshift('/html'); break; }
            var same = 0, idx = 0, sib = n.parentElement ? n.parentElement.firstElementChild : null;
            while (sib) {
                if (sib.tagName === n.tagName) { same++; if (sib === n) idx = same; }
                sib = sib.nextElementSibling;
            }
            parts.unshift('/' + t + (same > 1 ? '[' + idx + ']' : ''));
            n = n.parentElement;
        }
        return parts.join('');
    }
    // 拾取目标归一化（2026-11-11 定稿规则：拾取对象必须 = 用户实际点击的 DOM 对象）。
    //
    // 保留的归一：点击装饰性节点（<img alt="" aria-hidden="true"> 信息图标、空 <span>）或文本节点容器时，
    // 若自身无可访问名（eHasName 为假），上溯到最近的「带稳定 #id 的语义容器」——使产物落到语义容器
    // （如提示区 div#newPasswordHint），带回完整文本：既能反查 NLS key，也能回退到稳定 #id。
    // 注意：上溯只认「稳定 #id」，无 id 的纯布局容器（content-wrapper / section / 大段文本 div）一律不接收，
    // 否则会落到这种大文本容器，定位过广且不稳健（2026-11-12 收紧）。
    //
    // 归一（2026-11-15 修订）：交互控件优先——点击落在 button 文案 span / a 内文本 / 包裹式
    // label 内文本时，拾取对象是控件本身（否则 role 恒 generic，只能退化成结构 CSS；
    // 点 radio 的 label 还会产出「文本 + 控件」两条定位）。
    // 例外仍是 label[for] 指向 label 之外控件的场景：保留 label 自身（那是另一个 DOM 对象）。
    //
    // eHasName 守卫保证「自身已有名的元素」（文本 div、按钮、带 data-i18n 的 span 等）行为不变，避免无谓上溯。
    function hasIdentityAttr(e) {
        if (!e || e.nodeType !== 1) return false;
        if (e.id && !isDynamicId(e.id)) return true;
        var attrs = stableAttrs(e);
        for (var i = 0; i < attrs.length; i++) {
            if (attrs[i].rand) continue;
            var n = attrs[i].name.toLowerCase();
            if (/^data-/.test(n) || n === 'aria-label' || n === 'name' || n === 'type') return true;
        }
        return false;
    }

    /** label 包裹的控件（仅「控件是 label 后代」时成立；label[for] 指向外部控件不归一）。 */
    function nestedControlOf(lb) {
        if (!lb || !lb.querySelector) return null;
        var c = lb.querySelector('input:not([type=hidden]), select, textarea');
        return (c && lb.contains(c)) ? c : null;
    }

    /**
     * 交互目标归一：点击落在交互控件的装饰性子节点（button 内 span/svg、a 内文本、
     * 包裹式 label 内文本）时返回控件本身；无可交互祖先返回 null。
     */
    function interactiveAncestor(e) {
        var INTERACTIVE_ROLE = /^(button|link|checkbox|radio|switch|tab|menuitem|menuitemcheckbox|menuitemradio|option|combobox|listbox|textbox|searchbox|spinbutton|treeitem|gridcell|row)$/;
        var n = e, depth = 0;
        while (n && n.nodeType === 1 && depth < 6) {
            var t = n.tagName ? n.tagName.toLowerCase() : '';
            if (t === 'input') {
                var ty = ((n.getAttribute && n.getAttribute('type')) || '').toLowerCase();
                return ty === 'hidden' ? null : n;
            }
            if (t === 'select' || t === 'textarea' || t === 'button') return n;
            if (t === 'a') {
                if (n.getAttribute && n.getAttribute('href')) return n;
            } else if (t === 'label') {
                return nestedControlOf(n);
            } else {
                var r = n.getAttribute && n.getAttribute('role');
                if (r && INTERACTIVE_ROLE.test(String(r).toLowerCase())) return n;
            }
            n = n.parentElement;
            depth++;
        }
        return null;
    }

    function pickTarget(e) {
        var interactive = interactiveAncestor(e);
        if (interactive) return interactive;
        if (e && e.nodeType === 1 && hasIdentityAttr(e)) return e;
        var eHasName = (accessibleName(e) || '').trim();
        var n = e, depth = 0;
        while (n && n.nodeType === 1 && depth < 5) {
            var t = n.tagName ? n.tagName.toLowerCase() : '';
            if (t === 'input' || t === 'select' || t === 'textarea' || t === 'button') return n;
            //  2026-11-11：点 <label> 就保留 <label> 本身，绝不转发到 label.control。
            //  关联控件是另一个 DOM 对象，转发会导致「点'用户名'标签、产物却定位到 input」——
            //  拾取对象与用户实际点击对象不一致（用户明确否定的行为）。
            //  label 自身带可访问名（其文本），命中 NLS 时可走文本语义策略定位。
            if (t === 'label') return n;
            if (t === 'a' && n.getAttribute && n.getAttribute('href')) return n;
            // 2026-11-13：容器/地标类 role（document/dialog/group/region/...）不是可交互目标，
            // 不应把「用户点的标题/正文」上溯到它们——Bootstrap .modal-dialog=role=document、.modal=role=dialog
            // 会把 modal 内点击提升为对话框容器，再被 isContainerRoot 当背景误杀，导致 modal 内拾取静默无结果。
            // 仅对交互/语义控件角色（button/tab/checkbox/...）保留上溯提升。
            var __role = n.getAttribute && n.getAttribute('role');
            if (__role) {
                var __rl = String(__role).toLowerCase();
                var __containerRole = /^(document|dialog|alertdialog|application|group|region|banner|main|navigation|contentinfo|complementary|form|section|generic|none|presentation|article|aside|header|footer|search|tooltip|status|log|list|listitem|figure|tree|tablist|menu|menubar|toolbar|grid|table|rowgroup|row|columnheader|rowheader|feed|timer)$/.test(__rl);
                if (!__containerRole) return n;
            }
            // 2026-11-12：上溯只接受「带稳定 #id 的语义容器」（如提示区 div#newPasswordHint），
            // 拒绝无 id 的纯布局容器（content-wrapper / section / 文本 div / span）——它们只是把大段文本
            // 包起来，落到这类容器会让产物定位过广且不稳健。找不到稳定 id 容器则保留点击对象本身
            // （precise = 用户实际点击的 DOM 对象），即便它只得 class 兜底选择器，也比大容器窄得多。
            if (depth > 0 && !eHasName && (accessibleName(n) || '').trim() && n.id && !isDynamicId(n.id)) return n;
            n = n.parentElement; depth++;
        }
        return e;
    }
    function shadowHostsOf(e) {
        var hosts = [];
        try {
            var node = e;
            while (node) {
                var root = node.getRootNode ? node.getRootNode() : null;
                if (!root || !root.host) break;
                hosts.unshift(cssSel(root.host));
                node = root.host;
            }
        } catch (ignore) { return []; }
        return hosts;
    }
    // 可定位的语义属性（data-* + 整族 aria-*）：业务自写/无障碍属性，值稳定时通常一个就能唯一定位。
    // data-testid 已由 testid 字段单独消费，这里跳过；id/class/style 由其它字段处理，也不收集；
    // 值随机与否交给 Java 侧判定（状态类属性只进组合，详见 CodegenScorer）。
    function semanticAttrsOf(e, limit) {
        var out = {}, used = 0;
        if (!e || !e.attributes) return out;
        for (var i = 0; i < e.attributes.length && used < limit; i++) {
            var a = e.attributes[i];
            if (!a || !a.name) continue;
            var name = a.name.toLowerCase();
            if (name.indexOf('data-') !== 0 && name.indexOf('aria-') !== 0) continue;
            if (name === 'data-testid') continue;
            var v = (a.value || '').replace(/\s+/g, ' ').trim();
            if (!v || v.length > 120) continue;
            out[name] = v;
            used++;
        }
        return out;
    }
    // 与 codegen-inject.js 的 checkedStateOf 保持同源（评审 N-02）：
    // 原生控件读 .checked，自定义 role=checkbox 的 div 回退 aria-checked。
    function checkedStateOf(el) {
        if (!el) return false;
        if (typeof el.checked === 'boolean') return el.checked;
        var aria = el.getAttribute && el.getAttribute('aria-checked');
        return aria === 'true';
    }
    function factsOf(x) {
        if (!x || x.nodeType !== 1) return null;
        var tag = x.tagName ? x.tagName.toLowerCase() : '';
        return {
            role: roleOf(x), name: accessibleName(x), tag: tag, id: x.id || '',
            testid: (x.getAttribute && x.getAttribute('data-testid')) || '',
            type: ((x.getAttribute && x.getAttribute('type')) || '').toLowerCase(),
            placeholder: (x.getAttribute && x.getAttribute('placeholder')) || '',
            alt: (x.getAttribute && x.getAttribute('alt')) || '',
            title: (x.getAttribute && x.getAttribute('title')) || '',
            value: (typeof x.value === 'string') ? x.value : '',
            checked: checkedStateOf(x),
            text: normalizedText(x), label: labelTextOf(x), nth: siblingIndex(x), cssSelector: cssSel(x),
            shadowHosts: shadowHostsOf(x),
            className: (typeof x.className === 'string') ? x.className : '',
            xpath: absXPath(x),
            nameAttr: (x.getAttribute && x.getAttribute('name')) || '',
            src: (x.getAttribute && x.getAttribute('src')) || '',
            attrs: semanticAttrsOf(x, 10),
            // 目标自身的后代锚点：供 Java 侧生成 `tag:has([data-i18n="x"])` 短形式。
            // 深度 3 / 最多 6 个，与注入脚本 factsOf 同口径（两端必须同步）。
            childAnchors: descendantAnchorsOf(x, 3, 6),
            // 状态播报区文本：name 已剔除它，Java 侧据此验证「AX 名 = 播报文本 + 稳定标签」再收窄。
            statusText: statusTextOf(x)
        };
    }
    // 单层选择器段：稳定 id 优先，动态 id 试稳定前缀（唯一才用），否则稳定属性段（含组内索引）；
    // 一律不产出裸 tag:nth-of-type(n) 段——纯 tag 段既无锚点也无属性，不作 CSS 候选（交 Java 侧 XPath/role 兜底）。
    function segOf(n) {
        if (n.id && !isDynamicId(n.id)) return '#' + (window.CSS ? CSS.escape(n.id) : n.id);
        if (n.id) {
            var fb = dynamicIdFallbackSel(n.id);
            if (fb) return fb;
        }
        return attrFallbackSel(n);
    }
    // 父级嵌套降级候选：每项是「从该祖先到目标的完整路径」（段链 > 连接、层级严格连续）。
    // 必须整条替换 base，绝不能当 base 的前缀拼接——cssSel 是"上溯最多 6 层"的窗口，
    // 两个窗口相拼会重叠，产出 div:nth-of-type(7) > body:nth-of-type(1) 这类不可能的层级，
    // 生成的 CSS 永远匹配不到元素。
    // v2.12 收紧：每一层都必须有稳定 id / 动态 id 前缀 / 稳定属性段（含组内索引）——
    // 目标层或任一中间层是裸 tag:nth-of-type(n) 段时整条链作废（返回空数组），不再作为 CSS 候选，
    // 交给 Java 侧 XPath / role 兜底；「锚点 + 中间裸 tag 段」的链同样不产出。
    // 后代锚点（与 codegen-inject.js 的 descendantAnchorsOf 同源）：
    // 深度 4 / 最多 4 个 / 最多访问 200 节点，供 :has() 作用域使用。
    function descendantAnchorsOf(root, maxDepth, maxCount, exclude) {
        var out = [];
        if (!root || !root.children) return out;
        var items = [], queue = [], visited = 0;
        for (var i = 0; i < root.children.length; i++) queue.push({ n: root.children[i], d: 1 });
        while (queue.length && items.length < maxCount * 3 && visited < 200) {
            var cur = queue.shift();
            var n = cur.n;
            if (!n || n.nodeType !== 1) continue;
            // 父级锚点必须来自「目标子树之外」：目标 button 内部的 span 也会被遍历收进来，
            // 把它当父级锚点会产出「父谓词与子谓词同指目标内部」的废候选（零区分度、白占额度）。
            if (exclude && (n === exclude || (n.contains && n.contains(exclude)))) continue;
            // 装饰标签（hr/br/…）不产锚点：hr[aria-hidden="true"] 零区分度，只会误导作用域收窄。
            if (DECORATIVE_TAGS[(n.tagName || '').toLowerCase()]) continue;
            visited++;
            // 锚点选段统一走 bestAnchorSeg（泛化池 + 状态锚点兜底，单一事实来源）。
            var pick = bestAnchorSeg(n, true);
            if (pick.seg) {
                items.push({ seg: pick.seg, pri: anchorPriority(n, pick.seg), st: pick.state });
            }
            if (cur.d < maxDepth) {
                for (var k = 0; k < n.children.length; k++) queue.push({ n: n.children[k], d: cur.d + 1 });
            }
        }
        // 标题型锚点（分区标识）优先，其余按属性语义；同优先级保持 DOM 顺序（稳定排序）。
        items.sort(function (a, b) { return a.pri - b.pri; });
        // 状态型锚点单独保底（语义锚点让出 1 个名额，它固定排最后）。
        var stateAnchor = '';
        items = items.filter(function (it) {
            if (!it.st) return true;
            if (!stateAnchor) stateAnchor = it.seg;
            return false;
        });
        var limit = stateAnchor ? Math.max(0, maxCount - 1) : maxCount;
        for (var m = 0; m < items.length && out.length < limit; m++) out.push(items[m].seg);
        if (stateAnchor && out.length < maxCount) out.push(stateAnchor);
        return out;
    }
    /** 锚点质量排序：标题（h1~h6/legend/caption/label/role=heading）> data-i18n > data-testid > #id > aria-label > 其它。
     *  与 codegen-inject.js 的 anchorPriority 同源（改一处须同步另一处）。 */
    function anchorPriority(n, seg) {
        var t = (n.tagName || '').toLowerCase();
        var role = ((n.getAttribute && n.getAttribute('role')) || '').toLowerCase();
        var heading = /^h[1-6]$/.test(t) || t === 'legend' || t === 'caption' || t === 'label'
            || role === 'heading';
        var p = heading ? 0 : 20;
        // 状态型锚点最低优先（判据统一交 isStateAnchorSeg）。
        if (isStateAnchorSeg(seg)) return p + 40;
        if (seg.indexOf('[data-i18n=') === 0) return p;
        if (seg.indexOf('[data-testid=') === 0) return p + 2;
        if (seg.charAt(0) === '#') return p + 4;
        if (seg.indexOf('[aria-label=') === 0) return p + 6;
        return p + 10;
    }
    function ancestorFacts(n, path, target) {
        return {
            cssSelector: path,
            id: n.id || '',
            tag: n.tagName ? n.tagName.toLowerCase() : '',
            testid: (n.getAttribute && n.getAttribute('data-testid')) || '',
            // 稳定 class token（Java 侧取第一个拼 `div.page`）：裸 tag 头会命中 html/body 等
            // 所有含该标题的祖先层，必然多命中；带容器 class 才是真作用域。
            className: (typeof n.className === 'string') ? n.className : '',
            attrs: semanticAttrsOf(n, 6),
            // 深度 6 / 最多 6 个：4 层时行级锚点（卡片 > 列表 > 行 > 单元格 > 标题）采不到，
            // 而 :has() 作用域恰恰依赖深层锚点。节点访问上限 200 不变。
            // target 作排除子树：父级锚点不得取自目标自身内部。
            childAnchors: descendantAnchorsOf(n, 6, 6, target)
        };
    }
    // 祖先链：从近到远的「从该祖先到目标的完整路径」。
    // 无锚点层改为 :nth-of-type 补位（上限 MAX_FILLER_LAYERS），超过才停止上溯——
    // 历史上任一层失败即返回空数组，复杂页面（中间是纯布局 div）一条祖先候选都产不出。
    // 注意「截断而非清空」：已收集的近端路径仍然有效。
    function ancestorChain(el, maxDepth) {
        var first = segOf(el) || fallbackSeg(el);
        if (!first) return [];
        var chain = [first], out = [], last = '';
        var filler = segOf(el) ? 0 : 1;
        var p = el.parentElement, d = 0;
        while (p && p.nodeType === 1) {
            var seg = segOf(p);
            if (!seg) {
                if (filler >= MAX_FILLER_LAYERS) break;   // 补位超限：截断，保留已有近端路径
                seg = fallbackSeg(p);
                if (!seg) break;
                filler++;
            }
            chain.unshift(seg);
            last = chain.join(' > ');
            if (d < maxDepth) out.push(ancestorFacts(p, last, el));
            p = p.parentElement;
            d++;
        }
        if (d > maxDepth && out.length && last !== out[out.length - 1].cssSelector) {
            out.push({ cssSelector: last, id: '' });
        }
        return out;
    }
    /**
     * 容器作用域段：只返回「全文档唯一」的稳定段（#id → tag[稳定属性] → tag[class*=稳定类]）。
     *
     * <p>为什么不直接拼整条祖先链：列表选择器必须能<i>唯一定位到这一个容器</i>，
     * 否则会跨到同类容器里（HSBC 重置密码页有两个 security-question 下拉，不设作用域就是 20 命中）。
     * 找不到唯一段就返回空串 ⇒ 放弃成组（宁可不生成列表，也不生成会点错项的序号）。
     */
    function listScopeSeg(n) {
        var t = n.tagName ? n.tagName.toLowerCase() : '';
        if (!t) return '';
        if (n.id && !isDynamicId(n.id)) {
            var byId = '#' + (window.CSS ? CSS.escape(n.id) : n.id);
            if (uniqueSel(byId)) return byId;
        }
        var attrs = stableAttrs(n);
        for (var i = 0; i < attrs.length && !attrs[i].rand; i++) {
            var s = t + '[' + attrs[i].name + '="' + attrEsc(attrs[i].value) + '"]';
            if (uniqueSel(s)) return s;
        }
        var toks = stableClassTokens((typeof n.className === 'string') ? n.className : '');
        for (var j = 0; j < toks.length; j++) {
            var sc = t + '[class*="' + attrEsc(toks[j]) + '"]';
            if (uniqueSel(sc)) return sc;
        }
        return '';
    }
    /**
     * 列表项成组提示（2026-11-15 用户规则）：ARIA 集合里的一项 ⇒ 应生成 {@code PageElementList}。
     *
     * <p>现场：10 个 {@code li[role=option]} 的可访问名各不相同（"Name a memorable city or country"…），
     * 以 {@code role|name} 为基座的组判定永远不成立，于是点一个选项只得到单元素
     * {@code @Element("#security-question-1_option_4")}，既看不到整组，也无法 {@code get(i)} 按题号取项。
     *
     * <p>三条同时成立才给提示（缺一就放弃，退回单元素——组语义错误比"没有组"更糟）：
     * <ol>
     *   <li>目标 role 是集合项（option/listitem/row/cell/tab/treeitem/menuitem…）；</li>
     *   <li>最近的集合容器（listbox/list/grid/table/tablist/tree/menu/ul/ol/table/tbody/select）可唯一分段；</li>
     *   <li>容器的元素子节点<b>全是同类项</b>（混入别的标签时 nth 与下标不一致，{@code get(n)} 会点错项）。</li>
     * </ol>
     *
     * @return {@code {selector, count, index}} 或 {@code null}
     */
    function listHintOf(x) {
        var ITEM = { option: 1, listitem: 1, row: 1, cell: 1, tab: 1, treeitem: 1, radio: 1,
                     menuitem: 1, menuitemcheckbox: 1, menuitemradio: 1 };
        var role = roleOf(x);
        if (!ITEM[role]) return null;
        var tag = x.tagName ? x.tagName.toLowerCase() : '';
        if (!tag) return null;
        var COLL = { listbox: 1, list: 1, grid: 1, table: 1, tablist: 1, tree: 1, menu: 1,
                     rowgroup: 1, radiogroup: 1 };
        var p = x.parentElement, depth = 0;
        while (p && p.nodeType === 1 && depth < 4) {
            var pr = ((p.getAttribute && p.getAttribute('role')) || '').toLowerCase();
            var pt = p.tagName ? p.tagName.toLowerCase() : '';
            if (COLL[pr] || pt === 'ul' || pt === 'ol' || pt === 'table' || pt === 'tbody' || pt === 'select') break;
            p = p.parentElement;
            depth++;
        }
        if (!p || p.nodeType !== 1) return null;
        var kids = p.children || [], same = 0, idx = -1;
        for (var i = 0; i < kids.length; i++) {
            if (kids[i].tagName !== x.tagName) continue;
            if (kids[i] === x) idx = same;
            same++;
        }
        if (same < 2 || same !== kids.length || idx < 0) return null;
        var scope = listScopeSeg(p);
        if (!scope) return null;
        // 条目选择器只按标签：容器子节点已保证「全是同标签」，此时 nth 与组内下标严格一致。
        // 不能拼 [role="…"]——隐式 ARIA 角色（<ul role="listbox"> 里的 <li>、<table> 里的 <tr>）
        // 的元素身上并没有这个属性，拼上会让整组选择器命中 0 个，get(n) 直接越界。
        return { selector: scope + ' > ' + tag, count: same, index: idx };
    }
    if (!el || el.nodeType !== 1) return null;
    var target = pickTarget(el);
    if (!target || target.nodeType !== 1) target = el;
    return { self: factsOf(target), ancestors: ancestorChain(target, 8), list: listHintOf(target) };
}