/*
 * codegen-inject.js —— 注入被测页（addInitScript，随导航自动重挂）
 *
 * 角色/可访问名计算保留自 Playwright roleUtils.ts 的"轻量"子集（role 判定 + accessible name 含文本回退）。
 * 本脚本只负责把元素事实（role / name / 属性 / 文本 / 同级序号）连同"上溯父级链"打包回 Java；
 * <b>打分选择 / 唯一性校验 / 父级嵌套降级</b>全部在 Java 侧 {@code CodegenScorer} 完成（可纯单元测试、不依赖浏览器）。
 * recorder 监听真实用户操作，经 window.__codegenEvent 桥回 Java（Step 生成后续迭代，见 CodegenRecorder）。
 */
(function () {
    'use strict';
    if (window.__CODEGEN__) return;

    /** ARIA role 判定（显式 role 或 HTML 标签映射）。 */
    function roleOf(e) {
        var r = e.getAttribute && e.getAttribute('role');
        if (r) return r.toLowerCase();
        var t = e.tagName ? e.tagName.toLowerCase() : '';
        if (t === 'button') return 'button';
        if (t === 'a' && e.getAttribute('href')) return 'link';
        if (t === 'input') {
            var ty = (e.getAttribute('type') || '').toLowerCase();
            if (ty === 'checkbox') return 'checkbox';
            if (ty === 'radio') return 'radio';
            if (ty === 'submit' || ty === 'button' || ty === 'reset') return 'button';
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
        return 'generic';
    }

    /** 可访问名（accessible name）：aria-label → aria-labelledby → label[for] → 包裹 label → title 兜底。 */
    function accessibleName(e) {
        var a = e.getAttribute && e.getAttribute('aria-label');
        if (a && a.trim()) return a.trim();
        if (e.id) {
            var byId = document.getElementById(e.id);
            if (byId && byId !== e) {
                var bid = byId.getAttribute('aria-label');
                if (bid && bid.trim()) return bid.trim();
            }
            var l = document.querySelector('label[for="' + (window.CSS ? CSS.escape(e.id) : e.id) + '"]');
            if (l && l.textContent.trim()) return l.textContent.trim();
        }
        if (e.tagName && e.tagName.toLowerCase() === 'img') {
            var alt = e.getAttribute('alt');
            if (alt && alt.trim()) return alt.trim();
        }
        // name from content（button/link/heading 等允许从子内容算 name；不截断长度，完整文本才利于 NLS 反查）
        var t = (e.textContent || '').replace(/\s+/g, ' ').trim();
        if (t) return t;
        var title = e.getAttribute && e.getAttribute('title');
        if (title && title.trim()) return title.trim();
        return '';
    }

    /** 折叠空白、去换行后的可见文本（截断护栏）。 */
    function normalizedText(e) {
        var t = (e.textContent || '').replace(/\s+/g, ' ').trim();
        return t.length > 240 ? t.substring(0, 240) : t;
    }

    /** 关联 <label> 文本（含 label[for] 与包裹 label）。 */
    function labelTextOf(e) {
        if (e.id) {
            var l = document.querySelector('label[for="' + (window.CSS ? CSS.escape(e.id) : e.id) + '"]');
            if (l && l.textContent.trim()) return l.textContent.trim();
        }
        var p = e.parentElement;
        if (p && p.tagName && p.tagName.toLowerCase() === 'label' && p.textContent.trim()) {
            return p.textContent.trim();
        }
        return '';
    }

    /**
     * 动态/随机 id 识别：此类 id 每次渲染都会变（Radix、Headless UI、react-select、MUI、antd 等），
     * 直接落进 CSS 选择器会产出「录制时唯一、回放必挂」的定位器。命中即视为无稳定 id，
     * 由调用方降级为 tag:nth-of-type 结构链或 role/name 定位。规则取保守前缀黑名单，避免误伤
     * password / username 这类稳定 id。
     */
    function isDynamicId(id) {
        if (!id || !id.length) return false;
        if (/^_R_/.test(id) || /_R_[A-Za-z0-9]{4,}_/.test(id)) return true; // Radix: _R_kmub7m_ / dropdown-list-_R_kmub7m_
        if (/:r\d+:/.test(id)) return true;                                // Radix v1/v2: :r1:
        if (/^headlessui-/.test(id)) return true;    // Headless UI
        if (/^react-select-/.test(id)) return true;  // react-select
        if (/^mui-\d+$/.test(id)) return true;       // MUI: mui-12345
        if (/^[a-z]+(-[a-z]+)*-\d{4,}$/.test(id)) return true; // 通用: 任意段前缀 + ≥4 位数字（antd 类）
        return false;
    }

    /**
     * 动态 id 的稳定前缀：截掉随机夹心（Radix {@code _R_xxxx_}/ {@code :rN:}、数字后缀）后，
     * 剩余部分在组件语义上稳定（如 {@code dropdown-list-_R_kmub7m_} → {@code dropdown-list-}）。
     * 无稳定前缀时返回空串。
     */
    function stableIdPrefix(id) {
        var m = id.match(/^(.*?)(?:_R_[A-Za-z0-9]{4,}_|:r\d+:|-\d{4,})/);
        return m ? m[1] : '';
    }

    /** 动态 id 的稳定后缀：截掉开头随机段（Radix 夹心/`:rN:`/数字段+连字符）后，剩余尾部在组件语义上稳定。 */
    function stableIdSuffix(id) {
        var m = id.match(/^(?:_R_[A-Za-z0-9]{4,}_|:r\d+:|\d{4,}-)(.*)$/);
        return m ? m[1] : '';
    }

    function attrEsc(v) {
        return String(v).replace(/["\\]/g, function (c) { return '\\' + c; });
    }
    /** 可见性判定：hidden、aria-hidden="true"、display:none / visibility:hidden（含祖先，经计算样式）、
     * visibility:collapse、opacity:0（含祖先链，不继承需逐级查，限 5 层）、clip 裁零 / clip-path 裁零、
     * 零尺寸或整体移出视口（响应式/移动端隐藏副本常见）均视为隐藏。 */
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
                if (cs.clip && (cs.clip === 'rect(0px, 0px, 0px, 0px)' || cs.clip.indexOf('rect(0') === 0)) return false;
                if (cs.clipPath && (/inset\(0%\)/.test(cs.clipPath) || /inset\(50%\)/.test(cs.clipPath) || /circle\(0/.test(cs.clipPath))) return false;
                var p = e.parentElement, d = 0;
                while (p && p.nodeType === 1 && d < 5) {
                    var pcs = window.getComputedStyle(p);
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


    /** 当前文档中唯一性校验：只统计可见元素（供 id 前缀/包含/组内索引兜底使用）。 */
    function uniqueSel(sel) {
        try {
            if (!sel) return false;
            var nodes = document.querySelectorAll(sel), n = 0;
            for (var i = 0; i < nodes.length; i++) if (isVisible(nodes[i])) n++;
            return n === 1;
        } catch (e) { return false; }
    }


    /**
     * 动态 id 的 CSS 兜底：优先稳定前缀 {@code [id^="…"]}（组件语义前缀，如 {@code dropdown-list-}），
     * 前缀不唯一时再试包含 {@code [id*="…"]}（去尾部分隔符的核心词）。每个候选都做文档唯一性校验，
     * 唯一才采用 —— 保证生成的选择器回放时命中且不歧义；全部不唯一则返回空串交给结构链兜底。
     */
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

    /** 取 src 尾部文件名（URL 末段，去 query/hash）；data URI / 空值返回空串。 */
    function srcTail(s) {
        if (!s) return '';
        s = String(s).trim();
        if (s.indexOf('data:') === 0) return '';
        var q = s.indexOf('?'); if (q >= 0) s = s.substring(0, q);
        var h = s.indexOf('#'); if (h >= 0) s = s.substring(0, h);
        var sl = s.lastIndexOf('/'); if (sl >= 0) s = s.substring(sl + 1);
        return s.length >= 3 ? s : '';
    }

    /** class 里的稳定类名（空格切分，滤掉框架随机 token 与过短碎片）。 */
    function stableClassTokens(cls) {
        var out = [], seen = {};
        if (!cls) return out;
        var raw = String(cls).trim().split(/\s+/);
        for (var i = 0; i < raw.length; i++) {
            var t = raw[i];
            if (t.length < 3) continue;
            if (/^(css|sc)-[a-z0-9]{5,}$/i.test(t)) continue;      // Emotion css-xxx
            if (/^[a-z0-9]{8,}$/i.test(t)) continue;                // 长 hash
            if (/_R_[A-Za-z0-9]{4,}_/.test(t)) continue;            // Radix 夹心
            if (/^[a-z]+(-[a-z]+)*-\d{4,}$/.test(t)) continue;      // 数字后缀
            if (seen[t]) continue;
            seen[t] = true;
            out.push(t);
        }
        return out;
    }
    /** 属性值稳定段：值无随机段 → 精确(=)；含随机段（Radix 夹心 / :rN: / 数字段）→ 稳定前缀 ^=、核心 *=、稳定后缀 $=（段自身无随机段、长度 2~40，避免随机值；^= → *= → $= 顺序）。 */
    function attrValueSegments(v) {
        if (!v) return [];
        v = String(v).trim();
        if (!v || v.length > 40) return [];
        var rand = /_R_[A-Za-z0-9]{4,}_|:r\d+:|\d{4,}-|-\d{4,}/;
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
    function stableAttrs(e) {
        var out = [], seen = {};
        var attrs = e.attributes;
        if (!attrs) return out;
        var rand = /_R_[A-Za-z0-9]{4,}_|:r\d+:|\d{4,}-|-\d{4,}/;
        for (var i = 0; i < attrs.length; i++) {
            var a = attrs[i];
            var n = a.name;
            if (n === 'id' || n === 'class' || n === 'style' || n === 'src') continue;
            var v = a.value;
            if (!v) continue;
            v = String(v).trim();
            if (!v || v.length > 40) continue;
            if (/^[\d.,-]+$/.test(v) && v.length > 6) continue;
            if (seen[n]) continue;
            seen[n] = true;
            out.push({ name: n, value: v, rand: rand.test(v) ? 1 : 0 });
        }
        out.sort(function (a, b) { return a.rand - b.rand; });
        if (out.length > 6) out.length = 6;
        return out;
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

    /** 唯一或组内索引：候选可见唯一即用；可见不唯一（一组元素）时取组内索引并校验可见唯一，失败返回空串。 */
    function uniqueOrGroup(sel, e) {
        if (uniqueSel(sel)) return sel;
        var cnt = visibleCount(sel);
        if (cnt > 1 && cnt <= 10) {
            var g = groupIndexSel(sel, e);
            if (g && uniqueSel(g)) return g;
        }
        return '';
    }
    /**
     * 目标元素属性兜底（在结构链之前）：无 id / 动态 id 无法唯一时，先试
     * {@code img[src$="文件名"]}、稳定 class token / class 双 token AND，然后按「最稳定优先」用属性：
     * ① 稳定属性（值无随机段）精确 {@code [name="value"]}；② 稳定属性双→三精确 AND；③ 含随机值属性
     * 用开头 {@code ^=}、包含 {@code *=}、结尾 {@code $=} 变体（段自身无随机值）；④ 组合层：单属性仍
     * 不唯一时多个属性共同定位——稳定属性精确 AND 随机变体（最稳定优先）、随机变体 × 随机变体 AND。
     * 每个候选先做可见唯一性校验——唯一即采用；可见不唯一（一组元素）时取组内索引；全部失败返回空串交给结构链/XPath。
     */
    function attrFallbackSel(e) {
        if (!e || e.nodeType !== 1) return '';
        var t = e.tagName.toLowerCase();
        if (t === 'img') {
            var tail = srcTail(e.getAttribute && e.getAttribute('src'));
            if (tail) {
                var byTail = 'img[src$="' + attrEsc(tail) + '"]';
                var s0 = uniqueOrGroup(byTail, e);
                if (s0) return s0;
            }
        }
        var cls = (typeof e.className === 'string') ? e.className : '';
        var toks = stableClassTokens(cls);
        for (var i = 0; i < toks.length; i++) {
            var byCls = t + '[class*="' + attrEsc(toks[i]) + '"]';
            var s1 = uniqueOrGroup(byCls, e);
            if (s1) return s1;
        }
        // class 双 token AND：单 token 不唯一时，两个稳定 token 组合常能收敛。
        for (var i = 0; i < toks.length && i < 4; i++) {
            for (var j = i + 1; j < toks.length && j < 4; j++) {
                var byCls2 = t + '[class*="' + attrEsc(toks[i]) + '"][class*="' + attrEsc(toks[j]) + '"]';
                var s2 = uniqueOrGroup(byCls2, e);
                if (s2) return s2;
            }
        }
                // 跨属性：按「最稳定优先」用属性——稳定属性（值无随机段）单精确 → 双/三精确 AND → 含随机值属性 ^=/*=$= 变体。
        var names = stableAttrs(e);
        for (var i = 0; i < names.length && !names[i].rand; i++) {
            var by1 = t + '[' + names[i].name + '="' + attrEsc(names[i].value) + '"]';
            var s3 = uniqueOrGroup(by1, e);
            if (s3) return s3;
        }
        for (var i = 0; i < names.length && !names[i].rand; i++) {
            for (var j = i + 1; j < names.length && !names[j].rand; j++) {
                var by2 = t + '[' + names[i].name + '="' + attrEsc(names[i].value) + '"][' + names[j].name + '="' + attrEsc(names[j].value) + '"]';
                var s4 = uniqueOrGroup(by2, e);
                if (s4) return s4;
            }
        }
        for (var i = 0; i < names.length && !names[i].rand; i++) {
            for (var j = i + 1; j < names.length && !names[j].rand; j++) {
                for (var k = j + 1; k < names.length && !names[k].rand; k++) {
                    var by3 = t + '[' + names[i].name + '="' + attrEsc(names[i].value) + '"][' + names[j].name + '="' + attrEsc(names[j].value) + '"][' + names[k].name + '="' + attrEsc(names[k].value) + '"]';
                    var s6 = uniqueOrGroup(by3, e);
                    if (s6) return s6;
                }
            }
        }
        for (var i = 0; i < names.length; i++) {
            if (!names[i].rand) continue;
            var segs = attrValueSegments(names[i].value);
            for (var m = 0; m < segs.length; m++) {
                var byV = t + '[' + names[i].name + segs[m].op + '"' + attrEsc(segs[m].seg) + '"]';
                var s5 = uniqueOrGroup(byV, e);
                if (s5) return s5;
            }
        }
        // 组合层：单属性仍不唯一时多个属性共同定位——① 稳定属性精确 AND 随机属性变体
        // （稳定取前 2、随机取前 2，随机变体按 ^= → *= → $=）；② 随机变体 × 随机变体 AND。
        // 全部经可见唯一校验（含组内索引）；命中即返回。
        var randNames = [], stabNames = [];
        for (var i = 0; i < names.length; i++) if (names[i].rand) randNames.push(names[i]);
        for (var i = 0; i < names.length && !names[i].rand && i < 2; i++) stabNames.push(names[i]);
        for (var a = 0; a < stabNames.length; a++) {
            for (var b = 0; b < randNames.length && b < 2; b++) {
                var rbSegs = attrValueSegments(randNames[b].value);
                for (var m = 0; m < rbSegs.length; m++) {
                    var byMix = t + '[' + stabNames[a].name + '="' + attrEsc(stabNames[a].value) + '"][' + randNames[b].name + rbSegs[m].op + '"' + attrEsc(rbSegs[m].seg) + '"]';
                    var s7 = uniqueOrGroup(byMix, e);
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
                        var s8 = uniqueOrGroup(byRand, e);
                        if (s8) return s8;
                    }
                }
            }
        }
        return '';
    }
    /**
     * 稳定 CSS 选择器：稳定 id 优先；动态 id 先试稳定前缀/包含选择器（唯一才用）；
     * 否则结构链逐层上溯——每层只接受稳定 id / 动态 id 前缀 / 稳定属性段（含组内索引），
     * 任何一层既无锚点也无可用属性段（裸 tag:nth-of-type(n)）时整条链不产出，返回空串
     * 交给 Java 侧 XPath 兜底（属性优先：稳定属性精确 → 双/三精确 AND → 含随机值属性 ^=/*=$= 变体）。
     */
    function cssSel(e) {
        if (!e || e.nodeType !== 1) return '';
        if (e.id && !isDynamicId(e.id)) return '#' + (window.CSS ? CSS.escape(e.id) : e.id);
        if (e.id) {
            var fb = dynamicIdFallbackSel(e.id);
            if (fb) return fb;
        }
        var ab = attrFallbackSel(e);
        if (ab) return ab;
        var parts = [];
        var n = e;
        var anchored = false;
        while (n && n.nodeType === 1 && parts.length < 6) {
            if (n.id && !isDynamicId(n.id)) { parts.unshift('#' + (window.CSS ? CSS.escape(n.id) : n.id)); anchored = true; break; }
            if (n.id) {
                var pf = dynamicIdFallbackSel(n.id);
                if (pf) { parts.unshift(pf); anchored = true; break; }
            }
            var seg = attrFallbackSel(n);
            if (seg) { parts.unshift(seg); n = n.parentElement; continue; }
            // 该层既无稳定锚点也无可用属性段：裸 tag:nth-of-type(n) 段不再产出，整条链作废交 XPath。
            return '';
        }
        if (!anchored) return '';
        return parts.join(' > ');
    }

    /** 同级兄弟元素中的序号（0 起），供"一组元素（List）"识别。 */
    function siblingIndex(e) {
        var i = 0, s = e.previousElementSibling;
        while (s) { i++; s = s.previousElementSibling; }
        return i;
    }

    /**
     * 拾取目标归一化：点 <label> / 标签内文本 / 链接内 <span> / 装饰性图标（alt="" 或 aria-hidden）时，
     * 上溯到真正可操作的控件或「带可访问名的语义祖先」。
     *
     * <p>为什么要归一：<label> 在 Playwright {@code AriaRole} 枚举里没有对应常量，<span>/<div> 只是 generic ——
     * 直接拾取它们只能生成又长又脆的结构 CSS；归一到控件后即可用 role（radio/checkbox/textbox）+ label 文本
     * （即 AX 可访问名）定位，产物既短又稳。
     *
     * <p>装饰性节点（如 {@code <img alt="" aria-hidden="true">} 信息图标、空 {@code <span>}）：自身无可访问名，
     * 拾取只会产出 {@code role=img} 这类既无名又易碎的定位器。故当<b>被点元素自身无可访问名</b>时，继续上溯到
     * 最近一个带可访问名（含 textContent 兜底，不再截断长度）的祖先，使产物落到其所在的语义容器（如提示区
     * {@code <div id="newPasswordHint">}），从而带回完整提示文本——既能反查出 NLS 文案 key，又能回退到稳定的
     * {@code #id} 定位器。<b>注意</b>：{@code eHasName} 守卫保证「自身已有名的元素」（文本 div、按钮、带 data-i18n
     * 的 span 等）行为完全不变，避免无谓上溯。
     */
    function pickTarget(e) {
        var eHasName = (accessibleName(e) || '').trim();
        var n = e, depth = 0;
        while (n && n.nodeType === 1 && depth < 5) {
            var t = n.tagName ? n.tagName.toLowerCase() : '';
            if (t === 'input' || t === 'select' || t === 'textarea' || t === 'button') return n;
            if (t === 'label') {
                var ctrl = n.control || (n.querySelector && n.querySelector('input,select,textarea,button'));
                if (ctrl) return ctrl;
            }
            if (t === 'a' && n.getAttribute && n.getAttribute('href')) return n;
            if (n.getAttribute && n.getAttribute('role')) return n;
            // 仅当被点元素自身无名（装饰/空白）时，上溯到最近「带名」祖先，避免产出 role=img 这类无用定位器
            if (depth > 0 && !eHasName && (accessibleName(n) || '').trim()) return n;
            n = n.parentElement;
            depth++;
        }
        return e;
    }

    /**
     * 元素所在的 open shadow 宿主链（outermost-first；主文档元素返回空数组）。
     *
     * <p>每层宿主的选择器都相对其<b>自身文档 / 外层 shadow root</b> 计算，与 {@link cssSel} 口径一致。
     * Java 侧据此拼出 {@code document.querySelector(h0).shadowRoot.querySelector(h1)…} 的链式求值——
     * 因为 {@code document.querySelector} <b>无法穿透 shadow 边界</b>，这正是 shadow 内元素拾取失败的根因。
     */
    function shadowHostsOf(e) {
        var hosts = [];
        try {
            var node = e;
            while (node) {
                var root = node.getRootNode ? node.getRootNode() : null;
                if (!root || !root.host) break;   // 已到 Document（或游离节点）
                hosts.unshift(cssSel(root.host));
                node = root.host;
            }
        } catch (ignore) {
            return [];
        }
        return hosts;
    }

    /**
     * 绝对 XPath（结构 CSS 无法唯一时的兜底定位器）。
     *
     * <p>shadow root 内返回空串：XPath 无法表达"穿透 shadow 边界"的路径，那种场景由 shadowHosts + CSS 负责。
     */
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

    /**
     * 可定位的语义属性集合（{@code data-*} + {@code aria-label}），供 CSS 属性定位使用。
     *
     * <p><b>为什么必须采集整族</b>：{@code data-i18n} / {@code data-qa} / {@code data-test} /
     * {@code data-automation-id} 这类业务自写属性值稳定、语义清晰，通常一个就能唯一定位；ARIA 属性
     * （{@code aria-label} / {@code aria-labelledby} / {@code aria-describedby} / {@code aria-roledescription} /
     * {@code aria-placeholder} 等身份类，以及 {@code aria-modal} / {@code aria-hidden} / {@code aria-expanded} 等
     * 状态类）同样可作为定位依据。只回传 name/value（trim、限长）；值是否随机由 Java 侧
     * {@code SelectorStability} 判定后，再决定用精确还是部分匹配。
     *
     * <p>{@code data-testid} 已由 {@code testid} 字段单独消费（优先级最高），这里跳过以免重复；
     * {@code id} / {@code class} / {@code style} 等由其它字段单独处理，也不在此收集。
     */
    function semanticAttrsOf(e, limit) {
        var out = {};
        if (!e || !e.attributes) return out;
        var used = 0;
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

    /** 单个元素的事实快照（Java 侧打分所需原始材料）。 */
    function factsOf(el) {
        if (!el || el.nodeType !== 1) return null;
        var tag = el.tagName ? el.tagName.toLowerCase() : '';
        return {
            role: roleOf(el),
            name: accessibleName(el),
            tag: tag,
            id: el.id || '',
            testid: (el.getAttribute && el.getAttribute('data-testid')) || '',
            type: ((el.getAttribute && el.getAttribute('type')) || '').toLowerCase(),
            placeholder: (el.getAttribute && el.getAttribute('placeholder')) || '',
            alt: (el.getAttribute && el.getAttribute('alt')) || '',
            title: (el.getAttribute && el.getAttribute('title')) || '',
            value: (typeof el.value === 'string') ? el.value : '',
            text: normalizedText(el),
            label: labelTextOf(el),
            nth: siblingIndex(el),
            cssSelector: cssSel(el),
            shadowHosts: shadowHostsOf(el),
            className: (typeof el.className === 'string') ? el.className : '',
            xpath: absXPath(el),
            nameAttr: (el.getAttribute && el.getAttribute('name')) || '',
            src: (el.getAttribute && el.getAttribute('src')) || '',
            attrs: semanticAttrsOf(el, 10)
        };
    }

    /** 单层选择器段：稳定 id 优先，动态 id 试稳定前缀（唯一才用），否则稳定属性段（含组内索引）；
     * 一律不产出裸 tag:nth-of-type(n) 段——纯 tag 段既无锚点也无属性，不作 CSS 候选（交 Java 侧 XPath/role 兜底）。 */
    function segOf(n) {
        if (n.id && !isDynamicId(n.id)) return '#' + (window.CSS ? CSS.escape(n.id) : n.id);
        if (n.id) {
            var fb = dynamicIdFallbackSel(n.id);
            if (fb) return fb;
        }
        return attrFallbackSel(n);
    }

    /**
     * 父级嵌套降级候选：每项是「从该祖先到目标的完整路径」（段链以 {@code >} 连接、层级严格连续）。
     *
     * <p><b>必须整条替换 base，不能当 base 的前缀拼接</b>：{@link cssSel} 是"上溯最多 6 层"的<b>窗口</b>，
     * 两个窗口相拼会重叠，产出 {@code div:nth-of-type(7) > body:nth-of-type(1)} 这类不可能的层级，
     * 生成的 CSS 永远匹配不到元素（历史 bug）。
     *
     * <p>v2.12 收紧：<b>每一层</b>都必须有稳定 id / 动态 id 前缀 / 稳定属性段（含组内索引）——
     * 目标层或任一中间层是裸 tag:nth-of-type(n) 段时整条链作废（返回空数组），不再作为 CSS 候选，
     * 交给 Java 侧 XPath / role 兜底；「锚点 + 中间裸 tag 段」的链同样不产出。
     *
     * <p>只回传 {@code cssSelector}：祖先不需要 role/name/xpath/shadowHosts —— 为每层重复算全量事实
     * 是白付多轮 DOM 遍历。
     */
    function ancestorChain(el, maxDepth) {
        var first = segOf(el);
        if (!first) return [];
        var chain = [first], out = [], last = '';
        var p = el.parentElement, d = 0;
        while (p && p.nodeType === 1) {
            var seg = segOf(p);
            if (!seg) return [];
            chain.unshift(seg);
            last = chain.join(' > ');
            if (d < maxDepth) out.push({ cssSelector: last, id: p.id || '' });
            p = p.parentElement;
            d++;
        }
        if (d > maxDepth && out.length && last !== out[out.length - 1].cssSelector) {
            out.push({ cssSelector: last, id: '' });
        }
        return out;
    }

    window.__CODEGEN__ = {
        /** 富事实：self + 父级链；Java 侧 CodegenScorer 据此打分/唯一性/嵌套。 */
        getInfo: function (el) {
            var target = pickTarget(el);
            var self = factsOf(target);
            if (!self) return null;
            return { self: self, ancestors: ancestorChain(target, 8) };
        },
        recorder: {
            /** 启动录制监听（幂等：脚本在每个新文档都会跑，重复 start 不得重复注册）。 */
            start: function () {
                if (window.__CODEGEN_RECORDER_STARTED__) return;
                window.__CODEGEN_RECORDER_STARTED__ = true;
                // 只取元素自身事实（factsOf）；不要用 getInfo —— 它还会额外算 6 层祖先链，
                // 而录制只需要 self，白白让每次 click/input 多做一遍 DOM 遍历（大页面上表现为输入卡顿）。
                // 同元素缓存：一个输入框连续输入（每个按键一次 input 事件）时，元素的静态结构
                // （cssSelector / role / name / id …）不变，只有 value 在变；value 由事件顶层字段单独回传 Java，
                // 故这里缓存 factsOf 结果、每次刷新 value 即可，省掉每个按键一整轮 DOM 遍历。
                var _lastInfoEl = null, _lastInfo = null;
                function infoOf(node) {
                    try {
                        if (node === _lastInfoEl && _lastInfo) {
                            _lastInfo.value = (typeof node.value === 'string') ? node.value : '';
                            return _lastInfo;
                        }
                        var info = factsOf(pickTarget(node));
                        if (info) { _lastInfoEl = node; _lastInfo = info; }
                        return info;
                    } catch (e) { return null; }
                }
                /**
                 * 把本 frame 的文档坐标换算到<b>顶层文档坐标</b>的偏移量。
                 *
                 * <p>CDP {@code DOM.getNodeForLocation} 只认主 frame 的文档坐标，而事件在 iframe 内触发时
                 * {@code pageX/pageY} 是相对该 iframe 文档的。逐层向上累加宿主 iframe 的位置即可换算；
                 * 跨源 iframe 读不到 {@code frameElement}（或无 frameElement 对象）时返回 null ⇒ 放弃拾取，
                 * 避免用一个错误坐标去命中无关元素。
                 */
                function topFrameOffset() {
                    var dx = 0, dy = 0, w = window;
                    try {
                        while (w !== w.parent) {
                            var fe = w.frameElement;
                            if (!fe) return null;
                            var r = fe.getBoundingClientRect();
                            var cs = w.parent.getComputedStyle(fe);
                            dx += r.left + (parseFloat(cs.borderLeftWidth) || 0) + (w.parent.scrollX || 0);
                            dy += r.top + (parseFloat(cs.borderTopWidth) || 0) + (w.parent.scrollY || 0);
                            w = w.parent;
                        }
                        return { x: dx, y: dy };
                    } catch (e) {
                        return null;   // 跨源 iframe：SecurityError / frameElement 为 null
                    }
                }
                /** 元素中心点的顶层文档坐标（CSS 像素、含滚动）：fill 这类没有可靠 pointer 坐标的事件用它。 */
                function centerOf(el, off) {
                    try {
                        if (!el || !el.getBoundingClientRect) return null;
                        var r = el.getBoundingClientRect();
                        if (!r || (r.width === 0 && r.height === 0)) return null;
                        return { x: r.left + r.width / 2 + (window.scrollX || 0) + off.x,
                                 y: r.top + r.height / 2 + (window.scrollY || 0) + off.y };
                    } catch (e) { return null; }
                }
                /** 点击点优先用真实 pointer 坐标，缺失（键盘触发/合成事件）时回退元素中心。 */
                function pointOf(ev, el, off) {
                    var x = ev.pageX, y = ev.pageY;
                    if (typeof x === 'number' && typeof y === 'number' && (x !== 0 || y !== 0)) {
                        return { x: x + off.x, y: y + off.y };
                    }
                    return centerOf(el, off);
                }
                /**
                 * 元素所属 frame 的稳定路径：从内到外每层 iframe 在其父文档中的序号（outer→inner）。
                 * 例：[2, 0] = 「顶层文档第 3 个 iframe」里的「第 1 个 iframe」。
                 * 与 Java 侧 Page.getFrameTree 的 childFrames 文档顺序一致，可据此映射到 CDP frameId，从而只探命中帧、
                 * 省掉全 frame 扫描（iframe 多时显著降低每拾取 CDP 往返）。
                 * 跨源 iframe 内 frameElement 为 null（安全限制）⇒ 返回 null，Java 侧回退全帧扫描（保持跨源正确）；
                 * 顶层文档返回 []（空路径 = 主 frame）。
                 */
                function framePathOf() {
                    try {
                        var path = [];
                        var w = window;
                        while (w && w !== w.top) {
                            var fe = w.frameElement;            // 父文档里的 <iframe>（跨源父时为 null）
                            if (!fe) return null;
                            var doc = fe.ownerDocument;
                            if (!doc || !doc.querySelectorAll) return null;
                            var iframes = doc.querySelectorAll('iframe');
                            var idx = -1;
                            for (var i = 0; i < iframes.length; i++) {
                                if (iframes[i] === fe) { idx = i; break; }
                            }
                            if (idx < 0) return null;
                            path.unshift(idx);                  // 外层在前
                            w = w.parent;
                        }
                        return path;
                    } catch (e) { return null; }
                }
                // 注意：这里绝不 preventDefault / stopPropagation —— 拾取必须与页面交互共存
                // （早先版本用 CDP Overlay.setInspectMode 拦截鼠标，导致「一进拾取页面就点不动」）。
                // 注册在 window 捕获层而非 document：跨域官网弹窗（如 business.hsbc.com.hk 的
                // privacy-and-security / terms-of-use）带 consent/analytics 全局脚本，会在更早阶段
                // stopPropagation 吞掉点击，document 捕获层监听收不到（症状：新页面 enable 后
                // 点击零日志、拾取无显示）。本脚本经 context.addInitScript 注入，先于页面任何脚本执行，
                // 故 window 捕获监听总是最早注册 —— 页面脚本的 stopImmediatePropagation 拦不到我们。
                window.addEventListener('click', function (ev) {
                    try {
                        // 诊断计数：Java 侧事件泵据此区分「DOM 事件没进来」与「事件进来了但 binding 没派发」。
                        window.__CODEGEN_CLICK_COUNT__ = (window.__CODEGEN_CLICK_COUNT__ || 0) + 1;
                        var off = topFrameOffset();
                        var pt = off ? pointOf(ev, ev.target, off) : null;
                        window.__codegenEvent({ type: 'click', tag: ev.target && ev.target.tagName,
                            x: pt ? pt.x : null, y: pt ? pt.y : null,
                            coordSkipped: !off,
                            framePath: framePathOf(),
                            info: infoOf(ev.target) });
                    } catch (e) {}
                }, true);
                window.addEventListener('input', function (ev) {
                    try {
                        var off2 = topFrameOffset();
                        var pt2 = off2 ? centerOf(ev.target, off2) : null;
                        window.__codegenEvent({ type: 'fill', tag: ev.target && ev.target.tagName,
                            x: pt2 ? pt2.x : null, y: pt2 ? pt2.y : null,
                            coordSkipped: !off2,
                            framePath: framePathOf(),
                            value: ev.target.value, info: infoOf(ev.target) });
                    } catch (e) {}
                }, true);
            }
        }
    };

    // ---- 拾取悬停高亮（纯 JS 自绘）----
    // 旧版靠 CDP Overlay.setInspectMode 顺带提供高亮，但那个模式会吞掉鼠标事件（页面点不动），
    // 故改为自绘：只画一个 pointer-events:none 的框，绝不参与事件流，不影响页面交互。
    var HL_ID = '__codegen_highlight__';
    var hlAttached = false;
    var hlLast = null;

    function hlBox() {
        var d = document.getElementById(HL_ID);
        if (d) return d;
        d = document.createElement('div');
        d.id = HL_ID;
        d.style.cssText = 'position:fixed;z-index:2147483647;pointer-events:none;display:none;'
            + 'border:2px solid rgba(59,130,246,.95);background:rgba(59,130,246,.10);'
            + 'border-radius:2px;box-sizing:border-box;';
        var label = document.createElement('span');
        label.style.cssText = 'position:absolute;left:0;top:-19px;background:#2563eb;color:#fff;'
            + 'font:11px/17px -apple-system,"Segoe UI",sans-serif;padding:0 6px;border-radius:3px;'
            + 'white-space:nowrap;max-width:320px;overflow:hidden;text-overflow:ellipsis;';
        d.appendChild(label);
        (document.body || document.documentElement).appendChild(d);
        return d;
    }

    // 高亮标签只取「便宜」的事实：标签名 + role + 可访问名（均不遍历兄弟/祖先链），
    // 绝不用 factsOf（会上溯 6 层算 cssSel、64 层算 absXPath、遍历属性）——否则每次 mouseover 都重做一遍 DOM 遍历，
    // 鼠标一移动浏览器主线程就被打满，整机随即是卡死。
    function hlLabelOf(el) {
        var text = el.tagName ? el.tagName.toLowerCase() : '';
        try {
            var role = roleOf(el);
            if (role && role !== 'generic') text += ' · ' + role;
            var name = accessibleName(el);
            if (name) text += ' "' + String(name).slice(0, 40) + '"';
        } catch (e) { }
        return text;
    }

    var hlCurrent = null;   // 当前已高亮的元素：同一元素重入 mouseover 直接跳过
    var hlPending = null;   // rAF 待刷新目标：合并同一帧内的多次 mouseover/scroll
    var hlRaf = 0;

    function hlFlush() {
        hlRaf = 0;
        var el = hlPending;
        hlPending = null;
        if (!el || el.nodeType !== 1) return;
        hlCurrent = el;
        hlLast = el;
        try { hlShow(el); } catch (e) { }
    }

    function hlSchedule(el) {
        hlPending = el;
        if (hlRaf !== 0) return;
        if (window.requestAnimationFrame) {
            hlRaf = window.requestAnimationFrame(hlFlush);
        } else {
            hlRaf = 1;
            setTimeout(hlFlush, 16);
        }
    }

    function hlShow(el) {
        if (!el || el.nodeType !== 1) return;
        var r = el.getBoundingClientRect();
        if (!r || (r.width === 0 && r.height === 0)) return;
        var d = hlBox();
        d.style.display = 'block';
        d.style.left = r.left + 'px';
        d.style.top = r.top + 'px';
        d.style.width = r.width + 'px';
        d.style.height = r.height + 'px';
        d.firstChild.textContent = hlLabelOf(el);
    }

    function hlOnOver(ev) {
        var el = ev.target;
        if (!el || el.nodeType !== 1 || el.id === HL_ID) return;
        if (el === hlCurrent) return;          // 同一元素：mouseover 在元素内移动会高频重入，跳过
        hlSchedule(el);
    }

    function hlOnScroll() {
        if (hlCurrent) hlSchedule(hlCurrent);  // 滚动/缩放时只重定位，标签文本复用上次结果
    }

    function hlAttach() {
        if (hlAttached) return;
        hlAttached = true;
        document.addEventListener('mouseover', hlOnOver, true);
        window.addEventListener('scroll', hlOnScroll, true);
        window.addEventListener('resize', hlOnScroll, true);
    }

    function hlDetach() {
        if (!hlAttached) return;
        hlAttached = false;
        hlCurrent = null;
        hlLast = null;
        hlPending = null;
        if (hlRaf) {
            if (window.cancelAnimationFrame) window.cancelAnimationFrame(hlRaf);
            hlRaf = 0;
        }
        document.removeEventListener('mouseover', hlOnOver, true);
        window.removeEventListener('scroll', hlOnScroll, true);
        window.removeEventListener('resize', hlOnScroll, true);
        var d = document.getElementById(HL_ID);
        if (d) d.style.display = 'none';
    }

    /**
     * 拾取高亮开关（幂等）。Java 事件泵每 120ms 同步一次：页面导航 / 新标签页后本页标志会丢，
     * 靠它自动恢复；暂停拾取时立即隐藏，不留残框。
     */
    window.__CODEGEN_SET_HIGHLIGHT__ = function (on) {
        if (on === window.__CODEGEN_HL_ON__) return;
        window.__CODEGEN_HL_ON__ = on;
        try { on ? hlAttach() : hlDetach(); } catch (e) { }
    };

    // ---- 被测页控制条（对标 page.pause 的调试栏）：开始 / 继续 / 停止，且实时显示拾取态 ----
    // 与面板命令同源：按钮经 window.__codegenEvent({type:'control', action:...}) 回传 Java，
    // 复用与面板 Start/Pause/Stop 完全相同的会话控制逻辑。state: 'ready' | 'picking' | 'paused' | 'hidden'。
    window.__CODEGEN_SET_STATUS__ = function (state) {
        try {
            if (state === 'hidden') {
                var old = document.getElementById('__codegen_statusbar__');
                if (old) old.parentNode && old.parentNode.removeChild(old);
                return;
            }
            var bar = document.getElementById('__codegen_statusbar__');
            if (!bar) {
                bar = document.createElement('div');
                bar.id = '__codegen_statusbar__';
                bar.style.cssText = 'position:fixed;left:0;right:0;top:0;z-index:2147483646;'
                    + 'pointer-events:auto;display:flex;align-items:center;gap:10px;height:34px;padding:0 12px;'
                    + 'font:600 12px/34px -apple-system,"Segoe UI",sans-serif;letter-spacing:.2px;'
                    + 'background:#1f2937;color:#e5e7eb;box-shadow:0 1px 6px rgba(0,0,0,.45);';
                var txt = document.createElement('span');
                txt.style.cssText = 'flex:1;white-space:nowrap;overflow:hidden;text-overflow:ellipsis;';
                var btnToggle = document.createElement('button');
                btnToggle.type = 'button';
                btnToggle.style.cssText = BTN_CSS();
                var btnStop = document.createElement('button');
                btnStop.type = 'button';
                btnStop.textContent = '停止';
                btnStop.style.cssText = BTN_CSS() + 'background:#7f1d1d;color:#fff;border-color:#b91c1c;';
                btnStop.onclick = function () { sendControl('stop'); };
                bar.appendChild(txt);
                bar.appendChild(btnToggle);
                bar.appendChild(btnStop);
                bar.__txt = txt;
                bar.__btnToggle = btnToggle;
                (document.body || document.documentElement).appendChild(bar);
            }
            var toggle = bar.__btnToggle;
            toggle.onclick = function () {
                sendControl(bar.dataset.state === 'picking' ? 'pause' : 'start');
            };
            if (state === 'ready') {
                bar.__txt.textContent = '就绪 · 点「开始拾取」后点击页面元素即被记录';
                toggle.textContent = '开始拾取';
            } else if (state === 'picking') {
                bar.__txt.textContent = '拾取中 · 点击元素加入 Elements；点「暂停」仅停止收集元素，交互与录制保持';
                toggle.textContent = '暂停拾取';
            } else { // paused
                bar.__txt.textContent = '已暂停 · 页面交互与录制保持，点「继续拾取」恢复收集元素';
                toggle.textContent = '继续拾取';
            }
            bar.dataset.state = state;
        } catch (e) { }
    };

    function BTN_CSS() {
        return 'cursor:pointer;border:1px solid #374151;border-radius:4px;padding:3px 12px;'
            + 'font:600 12px/1 -apple-system,"Segoe UI",sans-serif;background:#2563eb;color:#fff;';
    }
    function sendControl(action) {
        try { if (window.__codegenEvent) window.__codegenEvent({ type: 'control', action: action }); } catch (e) { }
    }

    // 录制器随脚本自动启动：本脚本经 addInitScript 在每个新文档运行（也由 inject 立即在当前文档求值），
    // 故导航/新页都会自动重挂监听，Java 侧不需要（也无法可靠地）逐页调用 start。
    // 若 __codegenEvent 绑定尚未建立，事件会被 internal try/catch 丢弃，不影响页面。
    try { window.__CODEGEN__.recorder.start(); } catch (e) { }
})();
