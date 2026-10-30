package com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen.score;

import com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen.locator.LocatorStrategy;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen.locator.SelectorStability;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen.model.CodegenFacts;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 定位器打分选择 + 唯一性校验 + 父级嵌套降级。
 *
 * <p>对齐 Playwright {@code selectorGenerator.ts} 的打分优先级（分数越低越优），但收敛为本框架可消费的两种产物：
 * <ul>
 *   <li><b>角色定位</b> {@code role=...[name=...]}（{@link LocatorStrategy#ROLE_WITH_NAME}）：role 可映射到
 *       {@code AriaRole} 且 name 非空；
 *   <li><b>CSS/XPath 定位</b> {@code @Element("<css|xpath=...>")}（{@link LocatorStrategy#CSS}）。
 * </ul>
 *
 * <h3>候选序列（逐个做唯一性校验，第一个唯一者胜出）</h3>
 * <ol>
 *   <li>{@code [data-testid="v"]} / 该属性值的部分匹配（{@code ^=}/{@code $=}/{@code *=}）；</li>
 *   <li>{@code #id} / {@code [id^=…]} 等；</li>
 *   <li>{@code [name="v"]}（表单字段名，与语言无关，比可访问名稳定）/ 部分匹配；</li>
 *   <li>{@code [placeholder=…]}/{@code [alt=…]}/{@code [title=…]}——<b>仅单语言模式</b>：
 *       多语言环境下这些属性随语言变化，传 {@code textAttributesAllowed=false} 关闭；</li>
 *   <li>{@code [class*="稳定 token"]}（class 里混 hash 时只取语义 token）；</li>
 *   <li><b>属性组合</b> {@code tag[a="1"][b="2"]}：单属性命中多个时的首选，避免退化成长结构路径；</li>
 *   <li>结构 CSS（标签 + {@code :nth-of-type}）→ 逐级重建祖先路径（<b>最后手段</b>，越短越好）；</li>
 *   <li>绝对 XPath（{@code xpath=/html/body/…}）——结构 CSS 无法唯一时的最后兜底。</li>
 * </ol>
 *
 * <p><b>随机值处理</b>：{@code id}/{@code data-testid}/{@code class}/{@code name} 若被 {@link SelectorStability}
 * 判定为一次性值（UUID / useId / 框架序号 / hash），<b>绝不</b>生成精确选择器，改用稳定片段的部分匹配
 * （{@code [id^="mat-input"]}、{@code [id$="-submit"]}、{@code [class*="login-form"]}），
 * 且同样必须通过唯一性校验。
 *
 * <p>纯函数、无浏览器依赖，可直接单测（{@link LocatorCounter} 注入桩实现）。
 */
public final class CodegenScorer {

    /**
     * 唯一性失败时父级嵌套的最大层数（超出则放弃，返回尽力而为的最具体定位器）。
     *
     * <p>页面侧还会额外回传一条「从 {@code documentElement} 到目标的完整路径」作为兜底
     * （逐级 {@code :nth-of-type} 精确，必然唯一），故实际最多尝试 {@code MAX_NEST_DEPTH + 1} 条候选。
     */
    public static final int MAX_NEST_DEPTH = 8;

    // ---- 打分优先级（对齐 Playwright selectorGenerator.ts；分数越低越优）----
    public static final int TESTID = 1;
    public static final int TESTID_VARIANT = 2;
    public static final int ROLE_NAME_SUBSTRING = 100;
    public static final int ROLE_NAME = 105;
    public static final int PLACEHOLDER = 120;
    public static final int LABEL = 140;
    public static final int ALT = 160;
    public static final int TEXT = 180;
    public static final int TITLE = 200;
    public static final int TEXT_REGEX = 250;
    public static final int CSS_ID = 500;
    public static final int CSS_ID_PARTIAL = 505;
    /**
     * 语义属性：整族 {@code data-*}（{@code data-i18n} / {@code data-qa} / {@code data-test} /
     * {@code data-automation-id} …）与身份类 {@code aria-*}（{@code aria-label} / {@code aria-labelledby} /
     * {@code aria-describedby} / {@code aria-roledescription} / {@code aria-placeholder}）。
     *
     * <p>这类属性由业务自己书写或语义稳定，值清晰（{@code data-i18n} 就是 NLS key），通常<b>一个就能唯一定位</b>，
     * 比 class token 与结构路径都可靠 ⇒ 分数仅次于 id。状态类 ARIA（{@link #ARIA_STATE_ONLY}）不在此档，
     * 只进组合。
     *
     * <p>值含随机片段时退到部分匹配（{@link #CSS_DATA_ATTR_PARTIAL}），仍须通过唯一性校验。
     */
    public static final int CSS_DATA_ATTR = 508;
    public static final int CSS_DATA_ATTR_PARTIAL = 509;
    /**
     * <b>状态类</b> ARIA 属性：值通常是共享的布尔/枚举状态（{@code true}/{@code false}/{@code expanded}…）。
     *
     * <p>这类属性单独作为定位器几乎从不唯一（页面上所有隐藏节点都有 {@code aria-hidden="true"}），
     * 故<b>只进组合</b>（如 {@code [role="dialog"][aria-modal="true"]}），不生成独立候选。
     * 身份类 ARIA（{@code aria-label} / {@code aria-labelledby} / {@code aria-describedby} /
     * {@code aria-roledescription} / {@code aria-placeholder}）不在此列，可作独立定位器。
     */
    public static final Set<String> ARIA_STATE_ONLY = Set.of(
            "aria-hidden", "aria-modal", "aria-expanded", "aria-selected", "aria-checked",
            "aria-pressed", "aria-disabled", "aria-invalid", "aria-readonly", "aria-required",
            "aria-busy", "aria-multiselectable", "aria-grabbed", "aria-dropeffect",
            "aria-orientation", "aria-haspopup", "aria-live", "aria-atomic", "aria-relevant",
            "aria-sort", "aria-valuemax", "aria-valuemin", "aria-valuenow");
    public static final int ROLE_NO_NAME = 510;
    /** 表单 {@code name} 属性：与语言无关，稳定但可能重名 ⇒ 排在 id 之后、结构之前。 */
    public static final int CSS_NAME = 515;
    public static final int CSS_NAME_PARTIAL = 518;
    /** 稳定 class token（现代前端多是工具类，命中面广，故排在结构 CSS 之前但依赖唯一性校验）。 */
    public static final int CSS_CLASS = 525;
    /**
     * 稳定属性<b>组合</b>（两个条件 AND，如 {@code input[name="user"][type="text"]}）。
     *
     * <p>单属性/单 class 命中多个时的<b>首选</b>：两个稳定属性往往就能唯一定位，且产物短、可读、
     * 不依赖页面层级。没有它就只能一路加祖先，产出
     * {@code html:nth-of-type(1) > body … > div:nth-of-type(7)} 这种又长又脆的结构路径 —— 那是最后手段。
     *
     * <p>分数刻意排在<b>单个</b>属性/class 之后：单属性已唯一时不该再叠条件（多绑一个属性 = 页面改样式
     * 就断），组合只在单条件不唯一时才值得付出"更长"的代价。
     */
    public static final int CSS_ATTR_COMBO = 527;
    public static final int CSS_TAG = 530;
    /** 绝对 XPath 兜底（结构 CSS 无法唯一时）。 */
    public static final int XPATH = 1100;
    public static final int NTH = 10000;
    public static final int CSS_FALLBACK = 10000000;

    /** 单个属性最多尝试多少个部分匹配候选（防止随机值里 token 过多导致大量 CDP 往返）。 */
    private static final int MAX_PARTIAL_PER_ATTR = 3;

    /** 最多生成多少个属性组合候选（组合数随稳定属性数平方增长，必须设上限）。 */
    private static final int MAX_ATTR_COMBOS = 6;

    /** 最多尝试几个"id 祖先前缀"（越近越优先；超过 2 个通常说明 id 同质化，继续试没有收益）。 */
    private static final int MAX_ID_PREFIXES = 2;

    /** 每个元素最多用几个语义属性（{@code data-*}/{@code aria-label}）做独立候选。 */
    private static final int MAX_DATA_ATTRS = 3;

    /** 合法 HTML 属性名（用于 {@code [name="value"]} 选择器，拒绝注入非法字符）。 */
    private static final Pattern ATTR_NAME = Pattern.compile("[A-Za-z_:][A-Za-z0-9_:.\\-]*");

    /** 安全 CSS 标识符（可直接用于 {@code #id}）；否则退化为 {@code [id="…"]}。 */
    private static final Pattern SAFE_IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_-]*");

    /** 打分结果：角色可定位标志 + 唯一化后的 CSS/XPath + 分数 + 策略常量。 */
    public static final class ScoredLocator {
        public final boolean roleLocatable;
        public final String uniqueCss;
        public final int score;
        public final String strategy;

        ScoredLocator(boolean roleLocatable, String uniqueCss, int score, String strategy) {
            this.roleLocatable = roleLocatable;
            this.uniqueCss = uniqueCss;
            this.score = score;
            this.strategy = strategy;
        }
    }

    /** 候选定位器（{@code css} 也可能是 {@code xpath=/…}，Playwright locator 两者都接受）。 */
    private record Candidate(int score, String css) {
    }

    /** 兼容重载：默认允许文本类属性（placeholder/alt/title）。 */
    public static ScoredLocator score(CodegenFacts facts, LocatorCounter counter) {
        return score(facts, counter, true);
    }

    /**
     * 基于富事实打分并校验唯一性。
     *
     * @param facts                  注入脚本回传的元素事实（含父级链）
     * @param counter                唯一定位数计数（真实实现走 {@code page.locator(css).count()}）
     * @param textAttributesAllowed  是否允许用 {@code placeholder}/{@code alt}/{@code title} 这类<b>文案属性</b>定位。
     *                               多语言环境必须传 {@code false}：文案随语言切换，产物会跨语言失效
     * @return 打分结果；{@link ScoredLocator#roleLocatable} 决定下游生成 {@code @RoleElement} 还是 {@code @Element}
     */
    public static ScoredLocator score(CodegenFacts facts, LocatorCounter counter,
                                      boolean textAttributesAllowed) {
        Objects.requireNonNull(facts, "facts");
        Objects.requireNonNull(counter, "counter");
        CodegenFacts.Fact self = facts.self;

        List<Candidate> candidates = cssCandidates(self, textAttributesAllowed);
        // 按分数排序（稳定排序：同分组内保持 前缀→后缀→包含 的顺序）
        candidates.sort(Comparator.comparingInt(Candidate::score));
        // 预热：实现方（CDP）可把整条候选链合并成一次协议往返；桩实现为空操作，语义不受影响。
        List<String> prefetch = new ArrayList<>(candidates.size());
        for (Candidate c : candidates) {
            if (c.css() != null && !c.css().isEmpty()) prefetch.add(c.css());
        }
        counter.prefetch(prefetch);
        // 尽力而为的兜底结果（角色被否 / 所有候选都不唯一时使用）：最优候选的唯一化尝试。
        // 结果缓存下来给循环与兜底分支复用——历史实现里同一选择器会被 count 2~3 次
        // （fallback 一次、循环里 uniqueCss 一次、isUnique 再一次），在 2k+ 元素页面上每次都是
        // 毫秒级开销，且对最终产物没有任何影响。
        Unique first = candidates.isEmpty() ? Unique.NONE : uniqueCss(candidates.get(0).css(), facts, counter);
        int fallbackScore = candidates.isEmpty() ? CSS_FALLBACK : candidates.get(0).score();

        // 角色候选按分数插入比较：语义最稳，但 testid/占位等更具体的属性仍优先
        boolean roleLocatable = self.roleLocatable();
        int roleScore = roleLocatable ? ROLE_NAME : ROLE_NO_NAME;
        if (roleLocatable && roleScore <= fallbackScore) {
            return new ScoredLocator(true, first.css(), roleScore, LocatorStrategy.ROLE_WITH_NAME);
        }

        // 逐候选校验唯一性：第一个唯一者胜出（后面的候选只在更优候选不唯一时才被用到）
        for (int i = 0; i < candidates.size(); i++) {
            Candidate c = candidates.get(i);
            if (c.css() == null || c.css().isEmpty()) continue;
            Unique unique = (i == 0) ? first : uniqueCss(c.css(), facts, counter);
            if (unique.count <= 1) {
                return new ScoredLocator(false, unique.css(), c.score(), LocatorStrategy.CSS);
            }
        }
        return new ScoredLocator(false, first.css(), fallbackScore, LocatorStrategy.CSS);
    }

    /**
     * 唯一化结果：最终 CSS + 最后一次计数。
     *
     * <p>把「计数」与「结果」一起返回，是为了让调用方复用最后一次计数结果而不是再查一遍
     * （{@code count <= 1} 即唯一）。历史实现返回裸 String，调用方只能用 {@code isUnique} 二次计数，
     * 造成每个候选恒定多一次往返。
     */
    record Unique(String css, int count) {
        /** 空候选占位（{@code count=0} 与"空选择器视为不可用"语义一致）。 */
        static final Unique NONE = new Unique("", 0);
    }

    /**
     * 校验 CSS 唯一性并父级嵌套降级：{@code count <= 1} 视为唯一；否则依次尝试
     * {@code facts.ancestors} 给出的「<b>从该祖先到目标的完整路径</b>」（由近及远逐级加长），
     * 直到唯一或尝试数达 {@link #MAX_NEST_DEPTH} + 1（最后一条是绝对路径兜底）。
     *
     * <p><b>为什么是"整条替换"而不是"左侧拼接"</b>：页面侧 {@code cssSel} 是「上溯最多 6 层」的
     * <b>窗口</b>，祖先项若再给一个窗口拼到左侧，两者会大量重叠，产出
     * {@code …div:nth-of-type(7) body:nth-of-type(1) > …} 这种不可能的层级（{@code body} 不可能是
     * {@code div} 的子元素）—— 生成的 CSS 永远匹配不到元素。祖先项必须是完整、层级连续的路径。
     *
     * <p>每个选择器只计数一次，返回值带上最后一次计数，调用方不需要重复查询。
     */
    static Unique uniqueCss(String baseCss, CodegenFacts facts, LocatorCounter counter) {
        if (baseCss == null || baseCss.isEmpty()) return Unique.NONE;
        int count = counter.count(baseCss);
        if (count <= 1) return new Unique(baseCss, count);
        if (baseCss.startsWith("xpath=")) return new Unique(baseCss, count);   // XPath 已是绝对路径

        String best = baseCss;

        // 阶段 1：最近的稳定 id 祖先做前缀（后代组合子）。产物最短、最可读：
        // `#newPasswordForm [class*="title"]` 远比 `html > body > … > div:nth-of-type(7)` 可维护。
        int idTried = 0;
        for (CodegenFacts.Fact a : facts.ancestors) {
            if (a.id == null || a.id.isEmpty() || !SelectorStability.isStable(a.id)) continue;
            if (idTried >= MAX_ID_PREFIXES) break;
            idTried++;
            best = idSelector(a.id) + " " + baseCss;
            count = counter.count(best);
            if (count <= 1) return new Unique(best, count);
        }

        // 阶段 2：完整祖先路径逐级加长（<b>最后手段</b>：无可用属性/id 时才走到这里）。
        int tried = 0;
        for (CodegenFacts.Fact a : facts.ancestors) {
            if (a.cssSelector == null || a.cssSelector.isEmpty()) continue;
            if (tried >= MAX_NEST_DEPTH + 1) break;
            tried++;
            best = a.cssSelector;
            count = counter.count(best);
            if (count <= 1) return new Unique(best, count);
        }
        return new Unique(best, count);
    }

    // ------------------------------------------------------------------
    // 候选构造
    // ------------------------------------------------------------------

    private static List<Candidate> cssCandidates(CodegenFacts.Fact self, boolean textAttributesAllowed) {
        List<Candidate> out = new ArrayList<>();

        // 1) data-testid：精确（稳定）或部分匹配（随机但有语义片段）
        addAttribute(out, "data-testid", self.testid, TESTID, TESTID_VARIANT);

        // 2) id
        if (SelectorStability.isStable(self.id)) {
            out.add(new Candidate(CSS_ID, idSelector(self.id)));
        } else {
            addPartials(out, "id", self.id, CSS_ID_PARTIAL);
        }

        // 2b) 语义属性（data-i18n / data-qa / data-test / aria-label …）：业务自写、值稳定，
        //     通常一个属性就能唯一定位 ⇒ 排在 id 之后、name/class/结构之前。
        //     值随机时交给 addAttribute 退到部分匹配（同样参与唯一性校验），绝不能跳过。
        int dataAttrs = 0;
        for (Map.Entry<String, String> e : self.attrs.entrySet()) {
            if (dataAttrs >= MAX_DATA_ATTRS) break;
            String attr = e.getKey();
            if (!ATTR_NAME.matcher(attr).matches()) continue;
            // 状态类 ARIA（aria-hidden/aria-modal…）单独从不唯一，只进组合，不生成独立候选
            if (ARIA_STATE_ONLY.contains(attr.toLowerCase())) continue;
            dataAttrs++;
            addAttribute(out, attr, e.getValue(), CSS_DATA_ATTR, CSS_DATA_ATTR_PARTIAL);
        }

        // 3) 表单 name 属性（与语言无关）
        addAttribute(out, "name", self.nameAttr, CSS_NAME, CSS_NAME_PARTIAL);

        // 4) 文案属性：多语言环境一律不用（值随语言变化）
        if (textAttributesAllowed) {
            if (SelectorStability.isStable(self.placeholder) && isTextboxLike(self)) {
                out.add(new Candidate(PLACEHOLDER, attrSelector("placeholder", "=", self.placeholder)));
            }
            if (SelectorStability.isStable(self.alt)) {
                out.add(new Candidate(ALT, attrSelector("alt", "=", self.alt)));
            }
            if (SelectorStability.isStable(self.title)) {
                out.add(new Candidate(TITLE, attrSelector("title", "=", self.title)));
            }
        }

        // 5) class 里的稳定类名（class 以空格分隔类名，类名内部可含 '-'，故按空格切分而非按分隔符切）
        for (String cls : SelectorStability.stableClassNames(self.className)) {
            out.add(new Candidate(CSS_CLASS, attrSelector("class", "*=", cls)));
        }

        // 5b) 属性组合（AND）：单属性不唯一时的首选——两个稳定属性通常就能唯一定位，
        //     不必退化到"逐级加祖先"的长结构路径（那是最后手段）
        addCombos(out, self, textAttributesAllowed);

        // 6) 结构 CSS（标签 + nth-of-type，必要时逐级拼祖先）
        if (self.cssSelector != null && !self.cssSelector.isEmpty()) {
            out.add(new Candidate(CSS_TAG, self.cssSelector));
        }

        // 7) 绝对 XPath 兜底（结构 CSS 无法唯一时；shadow root 内为空，由采集侧保证）
        if (self.xpath != null && !self.xpath.isBlank()) {
            out.add(new Candidate(XPATH, "xpath=" + self.xpath));
        }
        return out;
    }

    /**
     * 属性组合候选（AND）：{@code input[name="user"][type="text"]}、{@code div[class*="title"][class*="modal"]}。
     *
     * <p><b>为什么必须有这一档</b>：单个属性命中多个是常态（同名输入框、通用 class token）。若只有
     * "单属性 → 结构路径"两档，就会一路加祖先，产出 {@code html:nth-of-type(1) > body … > div:nth-of-type(7)}
     * 这种又长又脆的定位器——用户明确要求"能靠属性就用属性，长路径是最差情况"。
     *
     * <p>只组合<b>稳定</b>条件（{@link SelectorStability#isStable}），且总数量有上限
     * （{@link #MAX_ATTR_COMBOS}），避免候选爆炸带来的额外计数往返。
     */
    private static void addCombos(List<Candidate> out, CodegenFacts.Fact self, boolean textAttributesAllowed) {
        List<String> exact = new ArrayList<>();
        // 语义属性优先进入组合（data-i18n + class token 是最常见的"区块/部件"式定位）。
        // 身份类（aria-label/aria-labelledby/data-*…）最多取 2 个；状态类 ARIA 同样可进组合
        // （如 [role=dialog][aria-modal=true]），也最多取 2 个——两者分别计数，互不挤占。
        int identityInCombo = 0;
        int stateInCombo = 0;
        for (Map.Entry<String, String> e : self.attrs.entrySet()) {
            if (!ATTR_NAME.matcher(e.getKey()).matches() || !SelectorStability.isStable(e.getValue())) continue;
            String key = e.getKey().toLowerCase();
            if (ARIA_STATE_ONLY.contains(key)) {
                if (stateInCombo >= 2) continue;
                stateInCombo++;
            } else {
                if (identityInCombo >= 2) continue;
                identityInCombo++;
            }
            exact.add(attrSelector(e.getKey(), "=", e.getValue()));
        }
        if (SelectorStability.isStable(self.nameAttr)) {
            exact.add(attrSelector("name", "=", self.nameAttr));
        }
        if (SelectorStability.isStable(self.type)) {
            exact.add(attrSelector("type", "=", self.type));
        }
        if (textAttributesAllowed && isTextboxLike(self) && SelectorStability.isStable(self.placeholder)) {
            exact.add(attrSelector("placeholder", "=", self.placeholder));
        }
        if (textAttributesAllowed && SelectorStability.isStable(self.title)) {
            exact.add(attrSelector("title", "=", self.title));
        }
        if (textAttributesAllowed && SelectorStability.isStable(self.alt)) {
            exact.add(attrSelector("alt", "=", self.alt));
        }

        List<String> classes = new ArrayList<>();
        List<String> tokens = SelectorStability.stableClassNames(self.className);
        for (int i = 0; i < Math.min(2, tokens.size()); i++) {
            classes.add(attrSelector("class", "*=", tokens.get(i)));
        }

        Set<String> combos = new LinkedHashSet<>();
        // 两个精确属性（name+type 是最常见且最稳的一对，排在最前）
        for (int i = 0; i < exact.size(); i++) {
            for (int j = i + 1; j < exact.size(); j++) {
                combos.add(comboSelector(self, exact.get(i), exact.get(j)));
            }
        }
        // 精确属性 + class token
        for (String e : exact) {
            for (String c : classes) {
                combos.add(comboSelector(self, e, c));
            }
        }
        // 两个 class token（同一元素上的两个语义类名，常用于"区块 + 部件"式命名）
        if (classes.size() >= 2) {
            combos.add(comboSelector(self, classes.get(0), classes.get(1)));
        }

        int used = 0;
        for (String css : combos) {
            if (used >= MAX_ATTR_COMBOS) break;
            out.add(new Candidate(CSS_ATTR_COMBO, css));
            used++;
        }
    }

    /** 组装配���选择器：{@code tag[a="1"][b="2"]}（tag 为空时退化为纯属性组合）。 */
    private static String comboSelector(CodegenFacts.Fact self, String... conditions) {
        StringBuilder sb = new StringBuilder();
        if (self.tag != null && !self.tag.isBlank()) {
            sb.append(self.tag);
        }
        for (String condition : conditions) {
            sb.append(condition);
        }
        return sb.toString();
    }

    /** 属性候选：值稳定用精确匹配；值随机则退到「稳定前缀/后缀/片段」的部分匹配。 */
    private static void addAttribute(List<Candidate> out, String attr, String value,
                                     int exactScore, int partialScore) {
        if (SelectorStability.isStable(value)) {
            out.add(new Candidate(exactScore, attrSelector(attr, "=", value)));
        } else {
            addPartials(out, attr, value, partialScore);
        }
    }

    /**
     * 生成部分匹配候选：{@code ^=}(开头) → {@code $=}(结尾) → {@code *=}(包含)。
     *
     * <p>三种都生成是因为"稳定片段"可能出现在值的任意位置（{@code mat-input-3} 在前、{@code a3f9-userName} 在后）；
     * 具体用哪一个由唯一性校验决定。候选数量有上限，避免随机值里 token 过多导致大量 CDP 往返。
     */
    private static void addPartials(List<Candidate> out, String attr, String value, int score) {
        if (value == null || value.isBlank()) return;
        List<String> seen = new ArrayList<>();
        String prefix = SelectorStability.stablePrefix(value);
        if (usable(prefix, value) && !seen.contains(prefix)) {
            seen.add(prefix);
            out.add(new Candidate(score, attrSelector(attr, "^=", prefix)));
        }
        String suffix = SelectorStability.stableSuffix(value);
        if (usable(suffix, value) && !seen.contains(suffix)) {
            seen.add(suffix);
            out.add(new Candidate(score, attrSelector(attr, "$=", suffix)));
        }
        int used = 0;
        for (String token : SelectorStability.stableTokens(value)) {
            if (used >= MAX_PARTIAL_PER_ATTR) break;
            if (seen.contains(token)) continue;
            seen.add(token);
            used++;
            out.add(new Candidate(score, attrSelector(attr, "*=", token)));
        }
    }

    private static boolean usable(String fragment, String whole) {
        return fragment != null && !fragment.isEmpty() && !fragment.equals(whole);
    }

    private static String idSelector(String id) {
        return SAFE_IDENTIFIER.matcher(id).matches()
                ? "#" + id
                : attrSelector("id", "=", id);
    }

    /** 文本框类元素（role 或标签维度判断），用于决定 placeholder 属性是否可降级为定位器。 */
    private static boolean isTextboxLike(CodegenFacts.Fact f) {
        String r = f.role == null ? "" : f.role.toLowerCase();
        if ("textbox".equals(r) || "searchbox".equals(r) || "spinbutton".equals(r)) return true;
        return "input".equals(f.tag) || "textarea".equals(f.tag);
    }

    /** 构造属性选择器 {@code [name op "value"]}，转义双引号与反斜杠。 */
    static String attrSelector(String name, String op, String value) {
        StringBuilder sb = new StringBuilder("[").append(name).append(op).append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\\' || c == '"') sb.append('\\');
            sb.append(c);
        }
        return sb.append("\"]").toString();
    }

    /** 兼容旧签名：精确属性选择器。 */
    static String attrSelector(String name, String value) {
        return attrSelector(name, "=", value);
    }
}
