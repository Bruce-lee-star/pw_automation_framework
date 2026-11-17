function () {
    var el = this;
    // 状态类 ARIA：值恒为布尔/枚举态，页面上到处都有（aria-hidden="true" 遍布装饰节点与隐藏副本），
    // 当定位锚点零区分度。与 Java 侧 CodegenScorer#ARIA_STATE_ONLY 同口径；
    // 页面侧若不同口径，:has(hr[aria-hidden="true"]) 这类空语义锚点会被当成"稳定锚点"，
    // 逼着生成器再叠 nth-of-type 才能唯一 ⇒ 产物只在拾取那一刻的 DOM 成立。
    /*__LOCATOR_CORE__*/

    // 纯装饰/无语义标签：作为 :has() 锚点没有业务区分度（hr 只是分隔线）。
    
    // 状态/布尔属性：进锚点池但固定最低档（标识「当前状态」而非区块语义）。
    
    // 锚点优先级（越小越优先）：语义身份 > 通用 > 状态；所有属性进池，换属性不改代码。
    // 状态/存在性属性判定（状态属性表 + 状态类 ARIA 的单一判据）。
    
    // 状态播报区（aria-live / role=status|alert|log）：读屏器的公告，不是控件标签。
    // 必须与 locator-core.js 的 isStatusRegion 保持同源（改一处须同步另一处）。
    
    // 渲染树剔除（hidden/aria-hidden/display:none/visibility:hidden）：name from content 的通用跳过规则。
    
    // name from content：拼接后代文本，跳过状态播报区与渲染剔除子树。HSBC 的按钮里常驻 aria-live
    // 的 "Loading, please wait." span，算进名字就会得到 "Loading, please wait. Continue"
    // （字段名 loadingPleaseWaitContinueBtn），点击后更只剩播报文案 ⇒ 定位器失效。
    
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
    
    // 动态/随机 id 识别（Radix、Headless UI、react-select、MUI、antd 等每次渲染随机），
    // 命中即视为无稳定 id，降级 tag:nth-of-type 结构链或 role/name。保守前缀黑名单，不误伤稳定 id。
    
    // 动态 id 稳定前缀/包含选择器兜底（唯一才用）；无前缀/不唯一返回空串交给结构链
    
    // 动态 id 的稳定后缀：截掉开头随机段（Radix 夹心/`:rN:`/数字段+连字符）后，剩余尾部在组件语义上稳定。
    
    /**
     * 匹配总数（<b>含隐藏元素</b>）。
     *
     * <p>Playwright 的 {@code locator(sel)} 不做可见性过滤，strict mode 下多匹配直接抛错，
     * 故唯一性必须按 DOM 全量计数。只数可见会漏掉隐藏的重复副本——典型如隐藏面板里那个
     * 同名 "Continue" 按钮：拾取时被判唯一，运行期面板一显示立刻变成多匹配。
     * 这是"重复面板里 Continue 按钮定位不准"的根因（2026-11-11 用户反馈）。
     */
    
    // 资源 URL 锚点池：标签 → 承载 URL 的属性（同类问题一次覆盖；不含 a[href]，见注入脚本说明）。
    
    // 取 src 尾部文件名（URL 末段，去 query/hash）；data URI / 空值返回空串。
    
    // 语言无关的 src 文件名前缀：xxx_EN.png / xxx_zh-HK.png / xxx.en_US.png 切语言后整条换名，
    // 必须锚定「语言段之前」的前缀（img[src*="xxx"]）；只吃最后一个语言段，前缀 < 4 字符不归一化。
    // 语言/地区段（同 locator-core.js）：新增语言只改这一处。
    
    // class 里的稳定类名（空格切分，滤掉框架随机 token 与过短碎片）。
    
    // 单节点属性锚点候选段（按 attrAnchorPriority 排序；withState 时状态锚点排最后）——锚点收集的唯一事实来源。
    
    // 绝对 XPath（结构 CSS 无法唯一时的兜底定位器）。shadow root 内返回空串：XPath 表达不了
    // "穿透 shadow 边界"的路径，那种情况由 shadowHosts + CSS 负责。
    
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
    
    // 可定位的语义属性（data-* + 整族 aria-*）：业务自写/无障碍属性，值稳定时通常一个就能唯一定位。
    // data-testid 已由 testid 字段单独消费，这里跳过；id/class/style 由其它字段处理，也不收集；
    // 值随机与否交给 Java 侧判定（状态类属性只进组合，详见 CodegenScorer）。
    
    // 与 locator-core.js 的 checkedStateOf 保持同源（评审 N-02）：
    // 原生控件读 .checked，自定义 role=checkbox 的 div 回退 aria-checked。
    
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
    
    // 父级嵌套降级候选：每项是「从该祖先到目标的完整路径」（段链 > 连接、层级严格连续）。
    // 必须整条替换 base，绝不能当 base 的前缀拼接——cssSel 是"上溯最多 6 层"的窗口，
    // 两个窗口相拼会重叠，产出 div:nth-of-type(7) > body:nth-of-type(1) 这类不可能的层级，
    // 生成的 CSS 永远匹配不到元素。
    // v2.12 收紧：每一层都必须有稳定 id / 动态 id 前缀 / 稳定属性段（含组内索引）——
    // 目标层或任一中间层是裸 tag:nth-of-type(n) 段时整条链作废（返回空数组），不再作为 CSS 候选，
    // 交给 Java 侧 XPath / role 兜底；「锚点 + 中间裸 tag 段」的链同样不产出。
    // 后代锚点（与 locator-core.js 的 descendantAnchorsOf 同源）：
    // 深度 4 / 最多 4 个 / 最多访问 200 节点，供 :has() 作用域使用。
    
    // 祖先链：从近到远的「从该祖先到目标的完整路径」。
    // 无锚点层改为 :nth-of-type 补位（上限 MAX_FILLER_LAYERS），超过才停止上溯——
    // 历史上任一层失败即返回空数组，复杂页面（中间是纯布局 div）一条祖先候选都产不出。
    // 注意「截断而非清空」：已收集的近端路径仍然有效。
    
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