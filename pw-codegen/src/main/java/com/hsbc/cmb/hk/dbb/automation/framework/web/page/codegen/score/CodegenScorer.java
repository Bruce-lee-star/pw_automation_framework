package com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen.score;

import com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen.locator.LocatorStrategy;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen.locator.SelectorStability;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen.model.CodegenFacts;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
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
 * <h3>优先级总纲：先 CSS，后 XPath</h3>
 * <p>凡能用<b>原生 CSS</b>（{@code :has()} / {@code :not()} 都是标准伪类，{@code querySelectorAll} 与
 * Playwright 引擎都认）表达且唯一的方案一律优先；XPath 只做兜底。具体地：
 * <ul>
 *   <li>正常 CSS 候选（{@code <=530} 档，含 {@code :has()} 作用域收窄）→ 最先；</li>
 *   <li>锚定 XPath（{@link #XPATH_ANCHORED}，父级锚点 × 子级锚点）→ CSS 全都不能唯一时；</li>
 *   <li>位置型 CSS（{@link #CSS_STRUCT_POSITIONAL}）→ 语法是 CSS，但语义是「按序号猜元素」，故排在锚定 XPath 之后；</li>
 *   <li>绝对 XPath（{@link #XPATH}）→ 最后。</li>
 * </ul>
 *
 * <h3>候选序列（逐个做唯一性校验，第一个唯一者胜出）</h3>
 * <ol>
 *   <li>{@code [data-testid="v"]} / 该属性值的部分匹配（{@code ^=}/{@code $=}/{@code *=}）；</li>
 *   <li>{@code #id} / {@code [id^=…]} 等；</li>
 *   <li>{@code tag:has([data-i18n="…"])}（<b>子代锚点短形式</b>：目标自身无属性、内部有唯一语义锚点）；
 *       不唯一时在同一档内追加作用域前缀收窄：{@code #id …} / {@code [data-testid=…] …} /
 *       {@code [data-x=…] …} / <b>{@code div.page …}（稳定 class 头，2026-11-17 新增）</b> /
 *       裸 {@code tag …}，每档都是 {@code 前缀:has(祖先锚点) base}；</li>
 *   <li>{@code [name="v"]}（表单字段名，与语言无关，比可访问名稳定）/ 部分匹配；</li>
 *   <li>{@code [placeholder=…]}/{@code [alt=…]}/{@code [title=…]}——<b>仅单语言模式</b>：
 *       多语言环境下这些属性随语言变化，传 {@code textAttributesAllowed=false} 关闭；</li>
 *   <li>{@code [class*="稳定 token"]}（class 里混 hash 时只取语义 token）；</li>
 *   <li><b>属性组合</b> {@code tag[a="1"][b="2"]}：单属性命中多个时的首选，避免退化成长结构路径；</li>
 *   <li>结构 CSS（标签 + {@code :nth-of-type}）——<b>仅当标签本身不含位置段</b>时按普通结构候选计分；</li>
 *   <li><b>锚定 XPath</b> {@code //父[.//标题]//目标[.//子锚点]}——「父级锚点 + 子级锚点」，
 *       专治语义完全相同的双胞胎（见 {@link #anchoredXPaths}）；</li>
 *   <li>位置型结构链（含 {@code :nth-of-type} 的祖先路径）：<b>延后裁决</b>——
 *       只要还有锚点/属性候选能唯一就绝不用它（见 {@link #CSS_STRUCT_POSITIONAL}）；</li>
 *   <li>绝对 XPath（{@code xpath=/html/body/…}）——最后兜底。</li>
 * </ol>
 *
 * <h3>原生 CSS 伪类收敛（唯一性降级链）</h3>
 * <p>只有 {@code count == 1} 才算唯一。命中多个时按「证据强度」依次收窄：
 * id 祖先前缀 → 祖先语义属性前缀 → {@code :has(> 锚点)}（直接父级）→ {@code :has(锚点)}（任意后代）
 * → 完整祖先路径 → {@code :not(条件)} 减法。前四档是「加法」（缩小范围），
 * 末档是「减法」（排除整族干扰副本），且因可能误排目标而排在确定性最强的结构路径之后。
 * <p><b>例外</b>：完整祖先路径若「新引入」了 {@code :nth-of-type} 序号，其唯一结果不就地胜出，
 * 而是被 {@link #score} 降级为 {@link #CSS_STRUCT_POSITIONAL} 并延后到「一条锚点候选都没能唯一」时出场。
 * <p>伪类一律用原生 CSS：{@code :has()} / {@code :not()} 不被 {@code querySelectorAll} 拒绝；
 * Playwright 专有的 {@code :visible} / {@code :has-text()} <b>禁止</b>出现在产物里。
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
    /**
     * role 定位器可访问名被视为「大段文本」的字符上限：超过则不再用 role 定位，降级给 CSS 候选
     * （2026-11-12 用户规则"不定位大文本，太广了"）。
     *
     * <p>把整块正文（段落 / 长句）当 {@code getByRole(role, name)} 的 name 锚点既脆弱（文本稍变即碎）
     * 又宽泛（本质是"靠大量文本定位"而非"靠语义角色+短标签定位"）。短标签（"OK" / "Sign in" /
     * "Forgot username"）远在阈值内，不受影响——它们才是 role 定位最稳健的锚点。
     */
    public static final int ROLE_NAME_MAX_LEN = 60;
    /**
     * 可访问名单词数上限（按 {@code \\s+} 切分，含全角/不间断空格）：超过即视为一段正文而非标签，
     * 同样降级。与 {@link #ROLE_NAME_MAX_LEN} 取「任一命中」即判为大文本。
     */
    public static final int ROLE_NAME_MAX_WORDS = 10;
    public static final int PLACEHOLDER = 120;
    public static final int LABEL = 140;
    public static final int ALT = 160;
    /**
     * {@code img} 的 {@code src} 尾部文件名（{@code [src$="…"]}）：URL 末段稳定且通常唯一，
     * 比 {@code alt}（文案属性，多语言环境会变）更可靠；排在 alt 与 text 之间。
     *
     * <p><b>语言变体例外</b>：文件名带语言尾标（{@code xxx_EN.png} / {@code xxx_zh-HK.png} /
     * {@code xxx.en.png}）时，切换语言后 {@code src} 会变成 {@code xxx_ZH.png}，{@code $=} 精确匹配随即失效。
     * 这类文件名改用「语言段之前的稳定前缀」+ {@code *=}（{@code img[src*="img_username"]}），
     * 见 {@link #localeNeutralSrcPrefix(String)}。
     */
    public static final int SRC_TAIL = 170;
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
     * 子代锚点<b>短形式</b>：{@code tag:has([data-i18n="…"])} / {@code tag:has([data-testid="…"])}。
     *
     * <p>目标自身没有稳定属性、但浅层后代带业务语义锚点时（{@code <button><span data-i18n="x">},
     * 图标按钮、卡片容器），这是<b>零结构层级</b>的定位方式：不受 {@code :nth-of-type} 序号与层级重排
     * 影响，且 {@code data-i18n} 的 key 跨语言不变。
     *
     * <p>分数排在自身强属性之后、{@code name}/{@code class}/结构链之前：后代锚点是目标<b>内部</b>的业务
     * 事实（比祖先作用域更贴近目标），但毕竟多一跳 {@code :has()} 关系，略弱于"直接写在目标身上"的属性。
     */
    public static final int CSS_DESC_ANCHOR = 512;
    /**
     * <b>状态类</b> ARIA 属性：值通常是共享的布尔/枚举状态（{@code true}/{@code false}/{@code expanded}…）。
     *
     * <p>这类属性单独作为定位器几乎从不唯一（页面上所有隐藏节点都有 {@code aria-hidden="true"}），
     * 故<b>只进组合</b>（如 {@code [role="dialog"][aria-modal="true"]}），不生成独立候选。
     * 身份类 ARIA（{@code aria-label} / {@code aria-labelledby} / {@code aria-describedby} /
     * {@code aria-roledescription} / {@code aria-placeholder}）不在此列，可作独立定位器。
     *
     * <p><b>命名澄清（评审一致性 a）</b>：本集合的准确含义是「<b>禁止生成独立候选、只允许进组合</b>」，
     * 不是「所有状态属性」的全集，也不是「所有非 ARIA 属性都该排除」。
     * {@code role} 属性（如 {@code role="dialog"}）虽非 {@code aria-*}，单独确实用处有限，
     * 但作为组合条件与状态类完全同构，<b>有意允许进组合</b>——不要因为集合名而把它误加进来。
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
    /**
     * <b>位置型</b>结构链（选择器里含 {@code :nth-of-type}）：{@code div:nth-of-type(5) … > button[class*="float-right"]}。
     *
     * <p>这类选择器只在「拾取那一刻的 DOM 布局」成立：页面多插一个 {@code div}、向导推进一段、
     * 弹层出现与消失，序号就整体漂移 ⇒ 回放时 0 命中（2026-11-16 「两个 Continue，第一个定位不出来」即此类）。
     *
     * <p>分数<b>刻意高于 {@link #XPATH_ANCHORED}</b>：只要还有「父级 / 子级稳定属性」能锚定，
     * 就必须先用锚定方案，位置链只留到「一条锚点候选都没有」时才用。此前它按 {@link #CSS_TAG}（530）计分，
     * 比锚定 XPath（1000）更优 ⇒ 锚点方案永远轮不到（用户反馈「还是没用父级/子级稳定属性」的根因）。
     */
    public static final int CSS_STRUCT_POSITIONAL = 1050;
    /** 绝对 XPath 兜底（结构 CSS 无法唯一时）。 */
    public static final int XPATH = 1100;
    /** 稳健 XPath 兜底（借父级 / 子级属性锚定，优先于绝对路径与位置型结构链）。 */
    public static final int XPATH_ANCHORED = 1000;
    public static final int NTH = 10000;
    public static final int CSS_FALLBACK = 10000000;

    /** 单个属性最多尝试多少个部分匹配候选（防止随机值里 token 过多导致大量 CDP 往返）。 */
    private static final int MAX_PARTIAL_PER_ATTR = 3;

    /** 最多生成多少个属性组合候选（组合数随稳定属性数平方增长，必须设上限）。 */
    private static final int MAX_ATTR_COMBOS = 6;

    /** 最多尝试几个"id 祖先前缀"（越近越优先；超过 2 个通常说明 id 同质化，继续试没有收益）。 */
    private static final int MAX_ID_PREFIXES = 2;

    /**
     * 最多尝试几个"祖先语义属性作用域前缀"（{@code [data-i18n="…"]}/{@code [aria-label="…"]}）。
     *
     * <p>用于隐藏/显示面板 DOM 结构高度近似、同名按钮靠自身属性无法区分的场景——面板容器上的
     * 业务锚点（{@code data-i18n} 等）是唯一稳定证据。上限 3：候选数量有限，避免大量 CDP 往返。
     */
    private static final int MAX_SCOPE_PREFIXES = 3;

    /** 每个元素最多用几个语义属性（{@code data-*}/{@code aria-label}）做独立候选。 */
    private static final int MAX_DATA_ATTRS = 3;

    /**
     * 最多尝试几个「祖先 {@code :has(后代锚点)}」作用域锚点（每个祖先最多取 4 个锚点）。
     *
     * <p>额度按<b>锚点</b>计，同一锚点的「直接子级」与「任意后代」两种写法共享一次额度。
     *
     * <p>取值 6 而非 4：跨祖先轮转后，每层祖先都可能贡献 1~2 次尝试（近祖先 + 外层分区标题），
     * 4 会在「三层祖先各带锚点」的常见页面上把最外层（真正有分区区分力的标题）挤掉。
     * 代价是最多 6 个锚点 × 2 种写法 = 12 次计数往返，仍在一次拾取的可接受范围内。
     */
    private static final int MAX_HAS_PREFIXES = 6;

    /** 单个祖先最多贡献几个 {@code :has()} 锚点（防止近祖先的多锚点把总额度吃光）。 */
    private static final int MAX_HAS_ANCHORS_PER_ANCESTOR = 3;

    /**
     * 状态型作用域锚点：{@code style} 属性存在且为空串。
     *
     * <p>单页应用用它标记「当前激活 / 当前可见」的页面容器（隐藏页写成 {@code style="; display: none"}）。
     * 它能唯一命中激活页，但绑定了组件当前的行内样式写法，属于<b>实现细节</b>，因此只在
     * 「一条语义/标题锚点都不成立」时兜底，见 {@code uniqueCss} 的阶段 1d。
     *
     * <p>只接受空值这一固定形态；{@code display:block} / {@code display: none} 之类的具体值随组件
     * 版本漂移，属于猜测，一律不生成。
     */
    private static final String STATE_SCOPE_ANCHOR = "[style=\"\"]";

    /** 阶段 1d 的独立小额度：它是最后的作用域手段，不值得为它多付计数往返。 */
    private static final int MAX_STATE_SCOPE_TRIES = 2;

    /**
     * 目标自身最多生成几个 {@code tag:has(后代锚点)} 短形式候选。
     *
     * <p>注入脚本对目标自身采集深度 ≤3、最多 6 个锚点；每个锚点一次计数往返，
     * 取前 4 个已足够覆盖 {@code data-i18n} / {@code data-testid} 等首选项。
     */
    private static final int MAX_SELF_HAS_ANCHORS = 4;

    /**
     * 锚定 XPath 中「元素自身属性谓词」的上限（{@code [@id="…"]} / {@code [@data-i18n="…"]} …）。
     *
     * <p>谓词之间是 <b>OR</b> 关系（各自成一条候选），故不必贪多：一是候选数随谓词线性增长、
     * 每条都要一次 {@code LocatorCounter} 往返；二是超过 3 个稳定属性通常说明该元素已是「属性堆」，
     * 前几个语义属性就足以定位。
     */
    private static final int MAX_XPATH_SELF_ATTRS = 3;

    /** 锚定 XPath 中每个元素最多取几个后代/祖国内部锚点（{@code [.//*[@data-i18n="…"]]}）。 */
    private static final int MAX_XPATH_CHILD_ANCHORS = 3;

    /**
     * 锚定 XPath 最多上溯几层祖先做「父级锚点」。
     *
     * <p>分区标题锚点几乎都在目标的 1~3 层内（{@code section > div.panel > h3}）；再往上就是
     * {@code body}/{@code #main} 这类全域容器，锚不住任何分区，只是白付往返。
     */
    private static final int MAX_XPATH_PARENTS = 3;

    /**
     * 锚定 XPath 候选总数上限（父级数 × 父级锚点数 × 目标侧谓词数 是三重乘积）。
     *
     * <p>所有候选会被一次 {@code prefetch} 批量下发，但每条仍各有一次计数；12 条已足够覆盖
     * 「3 层祖先 × 各自锚点 × 目标侧组合」，再多只是拖慢拾取而不改变结果。
     */
    private static final int MAX_XPATH_CANDIDATES = 12;

    /**
     * 最多尝试几个单条 {@code :not()} 否定条件（只约束 CDP 往返次数，超出部分不尝试）。
     *
     * <p>候选全部是「<b>从渲染树剔除、同时从可访问性树剔除</b>」的形态：{@code [disabled]} /
     * {@code [aria-hidden="true"]} / {@code [hidden]} / {@code [style*="display:none"]} / {@code [readonly]}。
     */
    private static final int MAX_NEGATION_CANDIDATES = 6;

    /** 最多尝试几组「两条 {@code :not()}」叠加（防止候选数平方增长拖慢拾取）。 */
    private static final int MAX_NEGATION_COMBOS = 2;

    /**
     * 可带 {@code disabled} 属性的标签。
     *
     * <p>其余标签加 {@code :not([disabled])} 不改变匹配集（那些元素永远不会有 {@code disabled}），
     * 只会白付一次计数往返，故按 tag 剪枝。
     */
    private static final Set<String> DISABLEABLE_TAGS =
            Set.of("input", "select", "textarea", "button", "fieldset", "optgroup", "option");

    /**
     * 合法的 {@code :has()} 锚点段：{@code #id} 或 {@code [attr="value"]}（值内不得含引号/反斜杠）。
     * 锚点由注入脚本采集，但仍按白名单校验后才拼进选择器。
     *
     * <p><b>值允许为空串</b>（{@code {0,80}}）：用于 {@code [style=""]} 这类「属性存在且为空」的
     * 确定形态（单页应用用它标记当前激活页）。空值仍是确定判据，不是随机值——真正随机的值在注入侧
     * 已被 {@code stableAttrs} 过滤，不会走到这里。
     */
    private static final Pattern HAS_ANCHOR = Pattern.compile(
            "^(#[A-Za-z0-9_-]{1,80}|\\[[A-Za-z_:][A-Za-z0-9_:.\\-]*=\"[^\"\\\\]{0,80}\"\\])$");

    /** 合法 HTML 标签名（用于 {@code tag:has(...)} 短形式的标签头，拒绝注入非法字符）。 */
    private static final Pattern SIMPLE_TAG = Pattern.compile("[a-z][a-z0-9-]{0,15}");

    /**
     * 图片文件名尾部的「语言 + 可选地区」段（大小写不敏感，分隔符 {@code . _ -} 均可）。
     *
     * <p>用 lazy 前缀 + 锚定结尾，保证只吃掉<b>最后一个</b>语言段：
     * {@code Search_HSBC_HK_Business_Express_EN} 只把尾部 {@code _EN} 视为语言码，
     * 中间的 {@code _HK} 是业务名的一部分，不能被剥掉。
     */
    private static final Pattern SRC_TRAILING_LOCALE = Pattern.compile(
            "(?i)^(.{4,}?)[._-](?:en|zh|tc|sc|hk|cn|tw)(?:[._-](?:us|gb|hk|cn|tw|hans|hant))?$");

    /** 文件扩展名（{@code .png} / {@code .svg} / {@code .jpg} …）。 */
    private static final Pattern FILE_EXT = Pattern.compile("\\.[A-Za-z0-9]{1,5}$");

    /** 合法 HTML 属性名（用于 {@code [name="value"]} 选择器，拒绝注入非法字符）。 */
    private static final Pattern ATTR_NAME = Pattern.compile("[A-Za-z_:][A-Za-z0-9_:.\\-]*");

    /** 安全 CSS 标识符（可直接用于 {@code #id}）；否则退化为 {@code [id="…"]}。 */
    private static final Pattern SAFE_IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_-]*");

    /** 打分结果：角色可定位标志 + 唯一化后的 CSS/XPath + 分数 + 策略常量 + 最终命中数。 */
    public static final class ScoredLocator {
        public final boolean roleLocatable;
        public final String uniqueCss;
        public final int score;
        public final String strategy;
        /**
         * <b>{@link #uniqueCss} 这条 CSS/XPath</b> 在 DOM 中的命中元素个数（{@code 1} = 唯一）。
         *
         * <p>{@code >1} 表示所有唯一化尝试（属性组合 / 祖先作用域 / 结构路径）都失败，当前 CSS 是
         * <b>尽力而为的多匹配选择器</b>。生成侧据此产出 {@code PageElementList}，而不是丢弃这条拾取
         * （2026-11-11 用户规则③：多匹配输出元素列表，不直接跳过）。
         *
         * <p><b>注意（role 策略下不是最终定位器的命中数）</b>：当 {@link #roleLocatable} 为 {@code true}
         * 且 role 分数胜出时，最终定位器是 {@code getByRole(role, name)}，本值只是被一并带出的 CSS
         * 候选计数，<b>与 role 引擎无关</b>。role 的真实命中数只能由
         * {@code CodegenCdpNodeExtractor#countRoleMatches} 在生成前复核得出，下游<b>不得</b>用本值
         * 判定 role 条目是单元素还是一组（见 {@code CodegenEntry#effectiveMatchCount()} 的约定）。
         */
        public final int matchCount;

        ScoredLocator(boolean roleLocatable, String uniqueCss, int score, String strategy, int matchCount) {
            this.roleLocatable = roleLocatable;
            this.uniqueCss = uniqueCss;
            this.score = score;
            this.strategy = strategy;
            this.matchCount = matchCount;
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
        // 稳健 XPath（父级 / 子级属性锚定）：优先级低于所有 CSS 候选、高于位置型结构链与绝对路径，
        // 仅当 CSS（含 descendantAnchorSel / 祖先作用域前缀）全部失效时才被采用——比绝对路径更稳。
        for (String rx : anchoredXPathCandidates(facts)) {
            candidates.add(new Candidate(XPATH_ANCHORED, "xpath=" + rx));
        }
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
        // 2026-11-12：可访问名若为「大段文本」则不当作角色锚点（详见 isBroadRoleName），降级给 CSS 候选，
        // 避免"靠一大块正文定位"这种过宽又过脆的产物。
        boolean roleLocatable = self.roleLocatable() && !isBroadRoleName(self.name);
        int roleScore = roleLocatable ? ROLE_NAME : ROLE_NO_NAME;
        if (roleLocatable && roleScore <= fallbackScore) {
            // 带出的 matchCount 是「兜底 CSS 候选」的计数，role 引擎一次都没跑过。
            // 必须如实标注这一事实，否则下游会把 CSS 命中数当成 role 命中数（详见 matchCount 字段说明）。
            return new ScoredLocator(true, first.css(), roleScore, LocatorStrategy.ROLE_WITH_NAME, first.count());
        }

        // 逐候选校验唯一性：<b>只有 count == 1 才算唯一</b>。
        // count == 0 表示该选择器当前一个都匹配不到（元素未渲染 / hydration 未完成 / 选择器写错），
        // 它绝不能胜出——否则会遮蔽后面真正唯一的候选，产出运行期永远等不到的死选择器。
        Unique fallback = first;
        int fallbackScoreOfChoice = fallbackScore;
        // 「靠新引入的 :nth-of-type 序号凑出唯一」的结果（见 #introducesPosition）：<b>延后裁决</b>。
        // 它不能就地返回，否则一条 512 分的语义候选靠加长祖先结构就能压过 1000 分的
        // 「父级锚点 + 子级锚点」锚定 XPath（2026-11-16 实跑：两个 Continue 只能被锚定 XPath 区分，
        // 产物却是 div:nth-of-type(5) … > button[class*='float-right']，回放必漂移）。
        // 但也不能彻底丢弃：页面既无属性也无锚点时，它是唯一能收敛的手段，故留作最后手段——
        // 分数降级为 CSS_STRUCT_POSITIONAL（1000 锚定 XPath 之后、1100 绝对 XPath 之前）。
        Unique positionalOnly = null;
        for (int i = 0; i < candidates.size(); i++) {
            Candidate c = candidates.get(i);
            if (c.css() == null || c.css().isEmpty()) continue;
            Unique unique = (i == 0) ? first : uniqueCss(c.css(), facts, counter);
            if (unique.count() == 1) {
                if (introducesPosition(c.css(), unique.css())) {
                    if (positionalOnly == null) positionalOnly = unique;
                    continue;   // 让后面的锚定候选先有机会胜出
                }
                return new ScoredLocator(false, unique.css(), c.score(), LocatorStrategy.CSS, unique.count());
            }
            // 兜底候选：优先选「当前确实有命中」的那一个，避免最终产物落在 0 命中的选择器上。
            if (fallback.count() <= 0 && unique.count() > 0) {
                fallback = unique;
                fallbackScoreOfChoice = c.score();
            }
        }
        if (positionalOnly != null) {
            // 一条锚点/属性候选都没能唯一：位置链作为最后手段出场，并如实标注它的低分档位。
            return new ScoredLocator(false, positionalOnly.css(), CSS_STRUCT_POSITIONAL,
                    LocatorStrategy.CSS, positionalOnly.count());
        }
        return new ScoredLocator(false, fallback.css(), fallbackScoreOfChoice,
                LocatorStrategy.CSS, fallback.count());
    }

    /**
     * 唯一化结果：最终 CSS + 最后一次计数。
     *
     * <p>把「计数」与「结果」一起返回，是为了让调用方复用最后一次计数结果而不是再查一遍
     * （<b>只有 {@code count == 1} 才算唯一</b>；{@code 0} 是「当前无命中」、{@code -1} 是「不可判定」，
     * 两者都不得被当成唯一）。历史实现返回裸 String，调用方只能用 {@code isUnique} 二次计数，
     * 造成每个候选恒定多一次往返。
     */
    record Unique(String css, int count) {
        /** 空候选占位（{@code count=0} 与"空选择器视为不可用"语义一致）。 */
        static final Unique NONE = new Unique("", 0);
    }

    /**
     * 校验 CSS 唯一性并父级嵌套降级：<b>只有 {@code count == 1} 才算唯一</b>；{@code 0}（当前无命中）与
     * {@code -1}（计数不可判定）都不会被采用，且不再继续加长（前缀/祖先只会让匹配更严格，结果仍为 0）。
     * 仅当 {@code count > 1} 时才依次尝试 {@code facts.ancestors} 给出的
     * 「<b>从该祖先到目标的完整路径</b>」（由近及远逐级加长），
     * 直到唯一或尝试数达 {@link #MAX_NEST_DEPTH} + 1（最后一条是绝对路径兜底）。
     *
     * <p><b>为什么是"整条替换"而不是"左侧拼接"</b>：页面侧 {@code cssSel} 是「上溯最多 6 层」的
     * <b>窗口</b>，祖先项若再给一个窗口拼到左侧，两者会大量重叠，产出
     * {@code …div:nth-of-type(7) body:nth-of-type(1) > …} 这种不可能的层级（{@code body} 不可能是
     * {@code div} 的子元素）—— 生成的 CSS 永远匹配不到元素。祖先项必须是完整、层级连续的路径。
     *
     * <p>阶段顺序：id 前缀 → 祖先语义属性前缀 → {@code :has(> 直接子锚点)} / {@code :has(后代锚点)} 作用域
     * → 完整祖先路径 → <b>{@code :not(...)} 减法收窄</b>。
     *
     * <p>{@code :not()} 排在最后是因为它属<b>弱证据</b>：减法可能把目标元素自己也排除掉，剩余那个
     * 「唯一」其实是对应的干扰副本。结构路径能唯一时一律优先于减法。
     *
     * <p><b>只用原生 CSS 伪类</b>（{@code :has()} 的 Chromium 105+ 起支持、{@code :not()} 全支持），
     * 既不依赖可见性，也不引入 Playwright 专有的 {@code :visible} / {@code :has-text()}——
     * 产物必须同时被 {@code querySelectorAll} 与 Playwright 引擎接受。
     *
     * <p>「加法」负责把范围收到唯一容器内，「减法」负责砍掉同容器内的整族干扰副本；
     * 两者互补：只有加法时，双胞胎同处一个容器就只能退化到完整结构路径。
     *
     * <p>每个选择器只计数一次，返回值带上最后一次计数，调用方不需要重复查询。
     */
    static Unique uniqueCss(String baseCss, CodegenFacts facts, LocatorCounter counter) {
        if (baseCss == null || baseCss.isEmpty()) return Unique.NONE;
        int count = counter.count(baseCss);
        if (count == 1) return new Unique(baseCss, 1);          // 唯一命中：采用
        if (count <= 0) return new Unique(baseCss, count);      // 0 命中 / 不可判定(-1)：加长只会更严格，直接返回
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
            if (count == 1) return new Unique(best, 1);
            if (count <= 0) return new Unique(best, count);   // 0 命中 / 不可判定：继续加长无意义
        }

        // 阶段 1b：祖先的稳定语义属性做作用域前缀（业务锚点）。
        // 隐藏/显示两个面板的 DOM 结构高度近似时，面板容器上的 data-i18n / aria-label 往往是
        // 唯一能区分"哪个面板"的稳定证据——只靠按钮自身的 class token 必然同时命中两个。
        int scopeTried = 0;
        for (CodegenFacts.Fact a : facts.ancestors) {
            if (scopeTried >= MAX_SCOPE_PREFIXES) break;
            for (Map.Entry<String, String> e : sortedAttrs(a.attrs)) {
                if (scopeTried >= MAX_SCOPE_PREFIXES) break;
                if (!ATTR_NAME.matcher(e.getKey()).matches()) continue;
                // 状态类 ARIA（aria-hidden/aria-expanded…）区分不了面板，只会误锁"当前恰好展开"的那一个
                if (ARIA_STATE_ONLY.contains(e.getKey().toLowerCase())) continue;
                if (!SelectorStability.isStable(e.getValue())) continue;
                scopeTried++;
                best = attrSelector(e.getKey(), "=", e.getValue()) + " " + baseCss;
                count = counter.count(best);
                if (count == 1) return new Unique(best, 1);
            if (count <= 0) return new Unique(best, count);   // 0 命中 / 不可判定：继续加长无意义
            }
        }

        // 阶段 1c：祖先 :has(后代锚点) 作用域。
        // 结构/文案都相同的双胞胎（"Continue" 在隐藏 modal 与未激活分页里各一份）靠祖先自身属性区分不了；
        // 但祖国内部往往有唯一子锚点（面板标题的 data-i18n、行的 data-testid）。:has() 是原生 CSS
        // （Chromium 105+），querySelectorAll 与 Playwright 引擎都认，产物不依赖任何专有伪类。
        //
        // 同一锚点先用「直接子级」`:has(> anchor)`，再退到「任意后代」`:has(anchor)`：
        // 锚点位于深层时，html/body/每层包裹 div 都会命中 → 计数全部 >1 而被丢弃；
        // `>` 只认真正的直接父级，能把「嵌套容器共享同一锚点」一次收敛到唯一。
        // 注意 `:has(> X)` 是原生 CSS 相对选择器，不是 Playwright 专有语法。
        // 强/弱分级（评审 P1-5）：先试「能标识祖先」的 id/testid/语义属性头，
        // 裸 tag 头是弱证据，排在下一轮（其 :has() 实际是一次全页后代搜索）。
        // 两轮共享 MAX_HAS_PREFIXES 额度，弱头不会额外放大往返次数。
        int hasTried = 0;
        for (boolean strongHead : new boolean[]{true, false}) {
            // 跨祖先<b>轮转</b>：先让每个祖先的「第一优先锚点」都试一次，再试第二优先……
            // 按祖先顺序逐个灌额度会让近祖先（分区内的按钮容器，锚点多）把额度吃光，
            // 外层分区标题（`.page` 里的 `h1[data-i18n="title_triage"]`）根本没机会——
            // 而恰恰是外层标题才是「这个按钮属于哪个分区」的唯一标识。
            for (int round = 0; round < MAX_HAS_ANCHORS_PER_ANCESTOR; round++) {
                for (CodegenFacts.Fact a : facts.ancestors) {
                    if (hasTried >= MAX_HAS_PREFIXES) break;
                    if (hasIdentifyingScopeHead(a) != strongHead) continue;
                    if (round >= a.childAnchors.size()) continue;
                    String anchor = a.childAnchors.get(round);
                    if (anchor == null || !HAS_ANCHOR.matcher(anchor).matches()) continue;
                    // 状态型锚点由阶段 1d 单独处理：它必须排在「所有」语义/标题锚点之后，
                    // 不能占用轮转名额，否则可能凭祖先顺序抢在标题锚点前面。
                    if (STATE_SCOPE_ANCHOR.equals(anchor)) continue;
                    String head = hasScopeHead(a);
                    if (head.isEmpty()) continue;
                    // 额度按锚点计：同一锚点的两种写法共享一次，不因新增 `>` 变体挤掉其它锚点。
                    hasTried++;
                    best = head + ":has(> " + anchor + ") " + baseCss;
                    count = counter.count(best);
                    if (count == 1) return new Unique(best, 1);
                    best = head + ":has(" + anchor + ") " + baseCss;
                    count = counter.count(best);
                    if (count == 1) return new Unique(best, 1);
                    // 该锚点在此祖先下不成立（0 命中）≠ 整条链作废：换下一个锚点继续收窄。
                }
            }
        }

        // 阶段 1d：状态型作用域（`div.page:has([style=""])`）。
        //
        // 用户 2026-11-17 现场验证：`.page:has([style='']) button.float-right [data-i18n="continue"]`
        // 能唯一定位激活页的 Continue。语义上它是「可见性代理」——比标题锚点脆（切页时行内样式会写成
        // `display:block` / `"; display: none"`），但比位置链强得多：靠的是页面切换器写入的<b>状态标记</b>，
        // 不是元素在 DOM 里的序号。故排在所有语义/标题锚点之后、位置链之前，且只用独立小额度。
        int stateTried = 0;
        for (boolean strongHead : new boolean[]{true, false}) {
            for (CodegenFacts.Fact a : facts.ancestors) {
                if (stateTried >= MAX_STATE_SCOPE_TRIES) break;
                if (hasIdentifyingScopeHead(a) != strongHead) continue;
                if (!a.childAnchors.contains(STATE_SCOPE_ANCHOR)) continue;
                String head = hasScopeHead(a);
                if (head.isEmpty()) continue;
                stateTried++;
                best = head + ":has(" + STATE_SCOPE_ANCHOR + ") " + baseCss;
                count = counter.count(best);
                if (count == 1) return new Unique(best, 1);
            }
        }

        // 阶段 2：完整祖先路径逐级加长（<b>确定性最强</b>的结构证据：逐级 :nth-of-type 必然指向目标）。
        //
        // 注意：这一档产出的唯一结果可能只是「按序号凑出来的唯一」（baseCss 本无 :nth-of-type，
        // 加长后才引入）。这种结果不在这里丢弃——它仍是「既无属性也无锚点」页面上的唯一救场手段；
        // 但它<b>不得压过锚定方案</b>，由 {@link #score} 依据 {@link #introducesPosition} 把它降级为
        // {@link #CSS_STRUCT_POSITIONAL} 并延后裁决（详见 score 内注释）。
        int tried = 0;
        for (CodegenFacts.Fact a : facts.ancestors) {
            if (a.cssSelector == null || a.cssSelector.isEmpty() || isMalformedCssSelector(a.cssSelector)) continue;
            if (tried >= MAX_NEST_DEPTH + 1) break;
            tried++;
            best = a.cssSelector;
            count = counter.count(best);
            if (count == 1) return new Unique(best, 1);
            if (count <= 0) return new Unique(best, count);   // 0 命中 / 不可判定：继续加长无意义
        }

        // 阶段 3：`:not()` 减法收窄（原生 CSS 伪类）。
        // 走到这里说明前面所有阶段都失效——尤其是页面侧 {@code cssSel} 没产出祖先链（任何一层缺锚点
        // 就整条作废）时，阶段 2 根本无候选。此时干扰项的共同特征往往就是可排除项：同名按钮里只有
        // 一个是启用态、隐藏面板里的副本带 aria-hidden/hidden。一句属性存在性否定即可砍掉整族副本。
        //
        // 排在最末是因为减法属<b>弱证据</b>：被排除的集合可能恰好包含目标自己，剩余那个"唯一"其实是
        // 干扰项。结构路径能唯一时一律优先于减法。
        //
        // 两个与前面阶段相反的规则：
        // <ol>
        //   <li>{@code count == 0} 时<b>不 return</b>：减法把目标自己排除了，换下一个条件而不是放弃；</li>
        //   <li>失败尝试<b>不写回</b> {@code best}/{@code count}：否则兜底产物会变成一条从未验证过命中的
        //       组合选择器（历史测试 {@code zeroMatchCandidateFallsBackToCandidateWithMatches} 已锁定）。</li>
        // </ol>
        List<String> negations = negationCandidates(facts.self);
        int negTried = 0;
        for (String negation : negations) {
            if (negTried >= MAX_NEGATION_CANDIDATES) break;
            negTried++;
            String candidate = baseCss + ":not(" + negation + ")";
            if (counter.count(candidate) == 1) return new Unique(candidate, 1);
        }
        // 单个条件都不足以收敛时两两叠加（`button:not([disabled]):not([aria-hidden="true"])`）。
        // 上限 {@link #MAX_NEGATION_COMBOS}，避免候选数平方增长把一次拾取拖慢。
        int combo = 0;
        for (int i = 0; i < negations.size() && combo < MAX_NEGATION_COMBOS; i++) {
            for (int j = i + 1; j < negations.size() && combo < MAX_NEGATION_COMBOS; j++) {
                combo++;
                String candidate = baseCss + ":not(" + negations.get(i) + "):not(" + negations.get(j) + ")";
                if (counter.count(candidate) == 1) return new Unique(candidate, 1);
            }
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
        for (Map.Entry<String, String> e : sortedAttrs(self.attrs)) {
            if (dataAttrs >= MAX_DATA_ATTRS) break;
            String attr = e.getKey();
            if (!ATTR_NAME.matcher(attr).matches()) continue;
            // 状态类 ARIA（aria-hidden/aria-modal…）单独从不唯一，只进组合，不生成独立候选
            if (ARIA_STATE_ONLY.contains(attr.toLowerCase())) continue;
            dataAttrs++;
            addAttribute(out, attr, e.getValue(), CSS_DATA_ATTR, CSS_DATA_ATTR_PARTIAL);
        }

        // 2c) 子代锚点短形式（:has）：目标自身无稳定属性时，借浅层后代携带的业务语义锚点
        //     （data-i18n / data-testid / aria-label …）把目标自身绑定成定位器：
        //     button:has([data-i18n="forgot_username_button"])。
        //     零结构层级 ⇒ 不受 :nth-of-type 序号与层级重排影响；data-i18n 的 key 跨语言不变。
        //     这正是「长结构链命中 0」时的首选替代：modal footer 里 .button-panel 的直接子元素是
        //     <button> 而非 <div>，任何按 div 序号拼出的链都是死链，而 :has(后代锚点) 仍然精确。
        String selfTag = self.tag == null ? "" : self.tag.toLowerCase(Locale.ROOT);
        if (SIMPLE_TAG.matcher(selfTag).matches()
                && !"html".equals(selfTag) && !"body".equals(selfTag)) {
            int selfHas = 0;
            for (String anchor : self.childAnchors) {
                if (selfHas >= MAX_SELF_HAS_ANCHORS) break;
                if (anchor == null || !HAS_ANCHOR.matcher(anchor).matches()) continue;
                selfHas++;
                out.add(new Candidate(CSS_DESC_ANCHOR, selfTag + ":has(" + anchor + ")"));
            }
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

        // 4b) img 的 src 尾部文件名（[src$="…"]，tag 前缀限定为 img）：
        //     URL 域名/路径可能随环境变化，文件名（末段，去 query/hash）稳定且通常唯一——
        //     深层表格里 alt 全部相同的二维码/徽章图（如 QR 下载区），这是唯一可靠的属性锚点。
        //     文件名稳定用精确 $=；文件名本身含随机段则退到稳定 token 的 *=
        if (isImg(self)) {
            String tail = srcTail(self.src);
            if (tail != null) {
                // 4b-1) 文件名带语言尾标（_EN / _zh-HK / .en）：切换语言后 src 整体换名，
                //       $= 精确匹配必然失效 ⇒ 只锚定语言段之前的稳定前缀，用 *= 覆盖全部语言变体。
                String neutral = localeNeutralSrcPrefix(tail);
                if (neutral != null) {
                    out.add(new Candidate(SRC_TAIL, "img" + attrSelector("src", "*=", neutral)));
                } else if (SelectorStability.isStable(tail)) {
                    out.add(new Candidate(SRC_TAIL, "img" + attrSelector("src", "$=", tail)));
                } else {
                    int used = 0;
                    for (String token : SelectorStability.stableTokens(tail)) {
                        if (used >= MAX_PARTIAL_PER_ATTR) break;
                        used++;
                        out.add(new Candidate(SRC_TAIL, "img" + attrSelector("src", "*=", token)));
                    }
                }
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
        //    位置型（含 :nth-of-type）降权到锚定 XPath 之后：锚点方案永远优先于"按序号猜元素"。
        if (self.cssSelector != null && !self.cssSelector.isEmpty() && !isMalformedCssSelector(self.cssSelector)) {
            out.add(new Candidate(isPositionalCss(self.cssSelector) ? CSS_STRUCT_POSITIONAL : CSS_TAG,
                    self.cssSelector));
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
     * "单属性 -> 结构路径"两档，就会一路加祖先，产出 {@code html:nth-of-type(1) > body … > div:nth-of-type(7)}
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
        for (Map.Entry<String, String> e : sortedAttrs(self.attrs)) {
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
        if (isImg(self)) {
            String tail = srcTail(self.src);
            if (tail != null) {
                String neutral = localeNeutralSrcPrefix(tail);
                if (neutral != null) {
                    exact.add(attrSelector("src", "*=", neutral));
                } else if (SelectorStability.isStable(tail)) {
                    exact.add(attrSelector("src", "$=", tail));
                }
            }
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

    /** 组装组合属性选择器：{@code tag[a="1"][b="2"]}（tag 为空时退化为纯属性组合）。 */
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

    /**
     * 属性表按<b>属性名</b>排序后遍历（评审一致性问题 b）。
     *
     * <p>{@code CodegenFacts.Fact#attrs} 来自注入脚本的 JSON 对象，迭代顺序由页面 DOM 属性顺序决定，
     * 跨浏览器/跨版本不保证一致。而多个语义属性共用同一分数（{@link #CSS_DATA_ATTR}、
     * {@link #CSS_ATTR_COMBO}），最终胜出者会随键序漂移；更隐蔽的是
     * {@link #MAX_DATA_ATTRS} 截断时「选哪 3 个属性」也会漂移。
     *
     * <p>这里只改变<b>属性之间的</b>先后顺序，不触碰单个属性内部
     * 「精确 → 前缀 → 后缀 → 包含」的插入顺序，因此不会破坏候选链的优先级语义
     * （直接给 {@code candidates.sort} 加字典序兜底反而会把 {@code $=} 排到 {@code ^=} 前面）。
     *
     * @return 新的可改列表；入参为 {@code null} 时返回空列表
     */
    private static List<Map.Entry<String, String>> sortedAttrs(Map<String, String> attrs) {
        if (attrs == null || attrs.isEmpty()) return new ArrayList<>();
        List<Map.Entry<String, String>> out = new ArrayList<>(attrs.entrySet());
        out.sort(Map.Entry.comparingByKey());
        return out;
    }

    /**
     * 祖先是否带<b>可标识身份</b>（稳定 id / testid / 语义属性），决定 :has() 头的证据强度。
     *
     * <p>评审 P1-5 的核心观察成立：{@code div:has(> [data-i18n="title"]) button} 匹配的是
     * 「所有含该锚点的 div 的后代 button」，裸 tag 无法标识"目标所在的那个 div"，
     * 兄弟容器有相同锚点时必然多命中。但<b>直接删掉这一档是错的</b>：框架既有回归用例
     * （双胞胎面板）正是靠它在「祖先无 id/testid、也没有完整结构路径」时收敛到唯一，
     * 删了会让这类页面退化到更脆的长结构链。
     *
     * <p>企业级处理是<b>分级</b>：有身份的祖先（{@code true}）先试，它们是真作用域；
     * 裸 tag 头（{@code false}）降级为弱证据，排在最后兜底，且仍必须过
     * {@code count == 1} 唯一性校验。这样既不在强证据可用时白付全页搜索的往返，
     * 也不丢掉唯一能救场的候选。
     */
    private static boolean hasIdentifyingScopeHead(CodegenFacts.Fact a) {
        if (a == null) return false;
        if (SelectorStability.isStable(a.id) || SelectorStability.isStable(a.testid)) return true;
        // 稳定 class token（`div.page`）介于「语义属性」与「裸 tag」之间：它确实标识了容器种类，
        // 但 `.page` 这类通用类名同一页里会出现多次，故仍需 `:has(锚点)` 收窄，不单独当唯一判据。
        if (!firstStableClassToken(a.className).isEmpty()) return true;
        if (a.attrs == null) return false;
        for (Map.Entry<String, String> e : sortedAttrs(a.attrs)) {
            String key = e.getKey();
            if (!ATTR_NAME.matcher(key).matches()) continue;
            if (ARIA_STATE_ONLY.contains(key.toLowerCase())) continue;
            if (SelectorStability.isStable(e.getValue())) return true;
        }
        return false;
    }

    /**
     * {@code :has()} 作用域头部：稳定 id → 稳定 data-testid → 稳定的祖先语义属性 → 稳定 class token
     * → 裸标签名。
     *
     * <p>前四档是<b>强证据</b>（见 {@link #hasIdentifyingScopeHead}）；最后一档是弱证据兜底，
     * 由 {@link #uniqueCss} 分两轮调度，强头在前、裸 tag 在后。状态类 ARIA 与随机值一律排除。
     * 连标签名都没有时返回空串 ⇒ 跳过该祖先。
     */
    private static String hasScopeHead(CodegenFacts.Fact a) {
        if (a == null) return "";
        if (SelectorStability.isStable(a.id)) return idSelector(a.id);
        if (SelectorStability.isStable(a.testid)) {
            return attrSelector("data-testid", "=", a.testid);
        }
        for (Map.Entry<String, String> e : sortedAttrs(a.attrs)) {
            String key = e.getKey();
            if (!ATTR_NAME.matcher(key).matches()) continue;
            if (ARIA_STATE_ONLY.contains(key.toLowerCase())) continue;
            if (!SelectorStability.isStable(e.getValue())) continue;
            return attrSelector(key, "=", e.getValue());
        }
        // 第三档（新增，2026-11-17）：分区容器的稳定 class token，拼成 `div.page`。
        // 裸 tag 头（`div:has(标题)`）会命中 html/body/分区容器等所有含该标题的祖先层，
        // 计数必然 > 1；带容器 class 才是真作用域。仍是强证据（强于裸 tag、弱于 id/testid/语义属性）。
        String cls = firstStableClassToken(a.className);
        if (!cls.isEmpty()) {
            String tag = a.tag == null ? "" : a.tag.toLowerCase(Locale.ROOT);
            return SIMPLE_TAG.matcher(tag).matches() ? tag + "." + cls : "." + cls;
        }
        return a.tag == null ? "" : a.tag;
    }

    /**
     * 祖先的第一个稳定 class token（{@code class} 以空格分隔；hash/过短/动态值由
     * {@link SelectorStability#stableClassNames} 过滤），且必须能直接写进 CSS（{@link #SAFE_IDENTIFIER}）。
     *
     * <p>为什么只取一个：多 token 拼接（`div.page.a.b`）会把选择器绑死在样式的组合上——产品加一个
     * 布局 class 就失配；单 token 配合 `:has(锚点)` 已足够把范围收到分区。
     */
    private static String firstStableClassToken(String className) {
        for (String token : SelectorStability.stableClassNames(className)) {
            if (SAFE_IDENTIFIER.matcher(token).matches()) return token;
        }
        return "";
    }

    /**
     * {@code :not()} 减法候选（原生 CSS 伪类），按「干扰项出现频率 × 语义稳定」排序。
     *
     * <p><b>为什么只做「属性存在性/固定值」否定</b>：这类条件与属性值是否随机、页面语言是什么都无关，
     * 无需再过 {@link SelectorStability}（{@code [disabled]} 不关心值，{@code [type="hidden"]} 是 HTML 常量）。
     * {@code class} 一类的值依赖型否定不在此列——class 名可能是打包 hash，排除条件会随构建失效。
     *
     * <p><b>按 tag 剪枝</b>：{@code [disabled]} / {@code [readonly]} 只对相应控件有意义，
     * 给 {@code div} 加这些条件不改变匹配集，只会白付一次计数往返。
     *
     * <p>目标自身带 {@code aria-hidden="true"} 时不产出该条件：那必然把目标自己排除掉。
     * 其余属性（{@code disabled}/{@code hidden}）注入脚本未采集到事实里，无法前置剪枝，
     * 由 {@code count == 0} 在计数侧兜底（跳过该条件，不放弃整条链）。
     *
     * @return 否定条件列表（不含 {@code :not(...)} 外壳，如 {@code [disabled]}）
     */
    private static List<String> negationCandidates(CodegenFacts.Fact self) {
        if (self == null) return List.of();
        String tag = self.tag == null ? "" : self.tag.toLowerCase();
        List<String> out = new ArrayList<>(6);

        // 排除依据的<b>唯一判据</b>：该形态让元素同时离开渲染树与可访问性树。
        // 只有这类元素才不是「有效内容」：
        //   - display:none / visibility:hidden / hidden 属性 —— 不出现在 AX 树；
        //   - aria-hidden="true" —— 显式从 AX 树移除（视觉上可能还看得见）。
        //
        // 绝不能按"渲染不可见"排除：class="accessibleHidden" / .sr-only / .visually-hidden
        // 只是视觉隐藏（clip 裁零、1px 尺寸），它们<b>仍在 AX 树里</b>，getByRole 能命中，
        // 往往还是唯一可访问名来源（图标按钮的隐藏文字标签）。把它们当"垃圾副本"排除掉，
        // 等于丢弃无障碍语义、并让 @RoleElement 无从生成。
        // 同理，不按 class 名做否定：语义类名与"隐藏"没有必然关系，且 hash 类名会随构建失效。

        // 1) 禁用副本：同名按钮/输入框最常见的"第二份"就是 disabled 的那个
        if (DISABLEABLE_TAGS.contains(tag)) out.add("[disabled]");
        // 2) AX 树显式移除：目标自身就是 aria-hidden 时跳过（否则必然 0 命中）
        if (!"true".equalsIgnoreCase(self.attrs.get("aria-hidden"))) out.add("[aria-hidden=\"true\"]");
        // 3) 原生隐藏属性（同时离开渲染树与 AX 树）
        out.add("[hidden]");
        // 4) 内联 style 的 display:none（同样两者皆剔除）。
        //    子串匹配而非精确匹配：内联样式常带 !important 或其它声明。
        out.add("[style*=\"display:none\"]");
        out.add("[style*=\"display: none\"]");
        // 5) 只读输入框副本
        if ("input".equals(tag) || "textarea".equals(tag)) out.add("[readonly]");
        return out;
    }

    /**
     * 可访问名是否「大段文本」——命中则 {@link #score} 不把它当角色定位锚点，降级给 CSS 候选。
     *
     * <p>判据（任一即判为大文本）：
     * <ol>
     *   <li>字符数 {@code >} {@link #ROLE_NAME_MAX_LEN}；</li>
     *   <li>单词数（{@code \\s+} 切分，含全角空格 U+3000 / 不间断空格 U+00A0）{@code >} {@link #ROLE_NAME_MAX_WORDS}。</li>
     * </ol>
     *
     * <p>短标签（"OK" / "Sign in" / "Forgot username"）两项均在阈值内，照常走 role 定位；
     * 而一段正文 / 长句（如"Your session has expired. Please log in again to continue…"）会被识别并降级，
     * 因为用一大块正文当 {@code getByRole(role, name)} 的 name 既脆弱又过宽（2026-11-12 "不定位大文本"）。
     */
    private static boolean isBroadRoleName(String name) {
        if (name == null || name.isBlank()) return false;
        String n = name.trim();
        if (n.length() > ROLE_NAME_MAX_LEN) return true;
        return n.split("\\s+").length > ROLE_NAME_MAX_WORDS;
    }

    /** 文本框类元素（role 或标签维度判断），用于决定 placeholder 属性是否可降级为定位器。 */
    private static boolean isTextboxLike(CodegenFacts.Fact f) {
        String r = f.role == null ? "" : f.role.toLowerCase();
        if ("textbox".equals(r) || "searchbox".equals(r) || "spinbutton".equals(r)) return true;
        return "input".equals(f.tag) || "textarea".equals(f.tag);
    }

    private static boolean isImg(CodegenFacts.Fact f) {
        return f != null && "img".equals(f.tag);
    }

    /**
     * 提取「语言无关的 {@code src} 文件名前缀」：文件名尾部带语言/地区段时，返回该段之前的部分，
     * 否则返回 {@code null}（表示保持原样，可安全使用 {@code [src$="…"]}）。
     *
     * <p>动机：HSBC 侧图片按语言分文件——{@code img_username_EN.png} / {@code img_username_ZH.png} /
     * {@code img_username_TC.png}。用 {@code [src$="img_username_EN.png"]} 定位后，用户一切语言就整条失效
     * （回放等不到元素）。锚定语言段之前的前缀 {@code img[src*="img_username"]} 可覆盖全部语言变体，
     * 且与查询串/环境域名无关。
     *
     * <p>识别范围（大小写不敏感，分隔符 {@code . _ -} 均可）：尾部一段语言码（{@code en/zh/tc/sc/hk/cn/tw}），
     * 其后可再跟一段地区码（{@code us/gb/hk/cn/tw/hans/hant}）。例：
     * <ul>
     *   <li>{@code img_username_EN.png} → {@code img_username}</li>
     *   <li>{@code banner_zh-HK.png} → {@code banner}</li>
     *   <li>{@code Search_HSBC_HK_Business_Express_EN.png} → {@code Search_HSBC_HK_Business_Express}（只吃尾部 {@code _EN}）</li>
     * </ul>
     *
     * <p>前缀过短（&lt; 4 字符，如 {@code seo_EN} → {@code seo}）时不归一化：{@code *=} 太宽，
     * 容易在日后资源增多时误命中，宁可保留精确 {@code $=}。
     *
     * @param tail {@link #srcTail(String)} 的结果（已去 query/hash/目录）
     * @return 语言无关前缀；无语言尾标或前缀过短时返回 {@code null}
     */
    static String localeNeutralSrcPrefix(String tail) {
        if (tail == null || tail.isBlank()) {
            return null;
        }
        String stem = FILE_EXT.matcher(tail).replaceFirst("");
        Matcher m = SRC_TRAILING_LOCALE.matcher(stem);
        if (!m.matches()) {
            return null;
        }
        String prefix = m.group(1).replaceAll("[._-]+$", "");
        if (prefix.length() < 4 || !SelectorStability.isStable(prefix)) {
            return null;
        }
        return prefix;
    }

    /**
     * 取 {@code src} 的尾部文件名：URL 末段，去掉 query 与 hash，且至少 3 个字符。
     *
     * <p>{@code /x/y/Search_HSBC_HK_Business_Express_EN.png?t=1} → {@code Search_HSBC_HK_Business_Express_EN.png}；
     * data URI / 空值返回 {@code null}（无法用文件名定位）。
     */
    private static String srcTail(String src) {
        if (src == null || src.isBlank()) return null;
        String s = src.trim();
        if (s.startsWith("data:")) return null;
        int q = s.indexOf('?');
        if (q >= 0) s = s.substring(0, q);
        int h = s.indexOf('#');
        if (h >= 0) s = s.substring(0, h);
        int slash = s.lastIndexOf('/');
        if (slash >= 0) s = s.substring(slash + 1);
        if (s.length() < 3) return null;
        return s;
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

    /**
     * 非法 CSS 选择器护栏（与浏览器侧 {@code codegen-inject.js#validCssSel} 同源，纵深防御）：
     * 识别「单个 {@code [ ]} 内出现两个属性条件、中间以空白相接、无 {@code ]} 分隔」的畸形形态
     * （如 {@code [data-i18n="x" data-i18n="x"]}，缺 {@code ][}，属非法 CSS，会令运行期
     * {@code querySelectorAll} 抛 {@code SyntaxError} 或零匹配 → 表现为"未定位到"）。
     *
     * <p>合法的选择器（{@code [a][b]} 的 {@code ][} 复合、引号内含空格的 {@code [class*="btn primary"]}、
     * 后代组合 {@code div[a] span[b]}、{@code #id}、{@code :has()} 等）均不被误伤——判据是
     * 「{@code =} 与下一个 {@code =} 之间存在空白且二者处于同一对方括号内」。
     *
     * <p>本方法只判定<b>语法</b>畸形；唯一性 / 匹配数量由 {@link LocatorCounter} 负责。任何被判定为
     * 畸形的 {@code cssSelector} 都应在候选阶段被跳过，绝不进入最终 {@code @Element(...)} 产物。
     */
    private static final Pattern MALFORMED_CSS = Pattern.compile("\\[[^\\]]*=[^\\]]*\\s+[^\\]]*=");

    static boolean isMalformedCssSelector(String sel) {
        if (sel == null || sel.isEmpty()) return false;
        return MALFORMED_CSS.matcher(sel).find();
    }

    /**
     * 选择器是否含<b>位置</b>段（{@code :nth-of-type(...)}）。
     *
     * <p>位置段是可读但最脆的判据：它假设「目标在第 N 个同 tag 兄弟里」这一布局事实永恒不变。
     * 页面插入一个 {@code div}、向导多渲染一段、弹层出现/消失，序号整体漂移即 0 命中。
     * 故位置型选择器不得压过「父级 / 子级稳定属性」的锚定方案（见 {@link #CSS_STRUCT_POSITIONAL}）。
     *
     * @param sel CSS 选择器（可为 {@code null}）
     * @return 含位置段返回 {@code true}
     */
    static boolean isPositionalCss(String sel) {
        return sel != null && sel.contains(":nth-of-type(");
    }

    /**
     * 唯一化「加长」是否<strong>新引入</strong>位置段：{@code baseCss} 本来没有 {@code :nth-of-type}，
     * 加长后有了 ⇒ 这条收窄只是「用序号凑唯一」。
     *
     * <p>背景（2026-11-16 实跑）：{@code button:has(span[data-i18n='button_continue'])} 命中 2 个，
     * 加长阶段拿祖先完整路径拼出
     * {@code div:nth-of-type(5) div:has(span[data-i18n='back']) > div:nth-of-type(3) > button[class*='float-right']}：
     * count==1 通过，但语义上仍是「按序号猜元素」，回放必然漂移。
     *
     * <p><b>判定为 {@code true} 后的处置是「延后裁决」而不是「就地丢弃」</b>（见 {@link #score}）：
     * 这类结果被降级为 {@link #CSS_STRUCT_POSITIONAL} 并只在「一条属性/锚点候选都没能唯一」时出场。
     * 就地丢弃会让页面在「既无稳定属性、也无可用锚点」时丧失唯一的收敛手段，反而退化到
     * {@code :not()} 减法（弱证据，可能排除掉目标本身）或绝对 XPath。
     *
     * @param baseCss  加长前的候选
     * @param composed 加长后的候选
     * @return 加长步骤新引入了位置段返回 {@code true}
     */
    private static boolean introducesPosition(String baseCss, String composed) {
        return !isPositionalCss(baseCss) && isPositionalCss(composed);
    }

    // ------------------------------------------------------------------
    // 「父级锚点 + 子级锚点」锚定 XPath
    // ------------------------------------------------------------------

    /** 注入脚本回传的锚点段：{@code #id} / {@code [attr="v"]} / {@code tag[attr="v"]}（值内禁引号与反斜杠）。 */
    private static final Pattern CSS_ANCHOR = Pattern.compile(
            "^(?:([a-z][a-z0-9-]{0,15}))?\\[([A-Za-z_:][A-Za-z0-9_:.\\-]*)=\"([^\"\\\\]{0,80})\"\\]$");

    /**
     * 汇总全部锚定 XPath 候选（去重、保序）。
     *
     * <p>两个来源缺一不可：
     * <ol>
     *   <li>{@code facts.robustXpaths}——页面侧 {@code codegen-inject.js#robustXPaths} 生成、并已在文档内
     *       自检「恰好命中 1 个」的候选。放在前面：它们已经过页面事实校验，最可能直接胜出；</li>
     *   <li>{@link #anchoredXPaths}——本类由事实组合出的候选。页面侧只做「父级锚点 × <b>目标自身属性</b>」
     *       与「目标 × 子级锚点」两档，<b>缺「父级锚点 × 子级锚点」</b>这一档；而目标自身无属性、
     *       兄弟语义完全相同的双胞胎（两个 {@code Continue}）恰恰只能靠这一档区分。</li>
     * </ol>
     *
     * <p>去重是必须的：两个来源会产出同形候选（{@code //button[.//*[@data-i18n="x"]]}），
     * 重复候选会让同一条选择器被计数两次（违反「每个候选只查一次」的性能契约），
     * 也让 {@code prefetch} 载荷出现重复项。
     */
    private static List<String> anchoredXPathCandidates(CodegenFacts facts) {
        Set<String> out = new LinkedHashSet<>();
        if (facts.robustXpaths != null) {
            for (String rx : facts.robustXpaths) {
                if (rx != null && !rx.isBlank()) out.add(rx.trim());
            }
        }
        for (String rx : anchoredXPaths(facts)) {
            if (rx != null && !rx.isBlank()) out.add(rx);
        }
        return new ArrayList<>(out);
    }

    /**
     * 由事实现算「父级锚点 + 子级锚点」的锚定 XPath 候选。
     *
     * <p><b>为什么必须有这一档</b>（用户 2026-11-16 需求）：页面里常有「所有语义维度都相同的双胞胎」——
     * 两个 {@code Continue} 的 role/name/{@code data-i18n}/class 完全一致，单靠目标自身永远无法区分；
     * 真正能区分它们的是<b>所在分区</b>（父级锚点：分区标题的 {@code data-i18n}）＋<b>目标内部语义</b>
     * （子级锚点：按钮里那个 {@code span[data-i18n="button_continue"]}）。CSS 表达「父级 + 子级」只能写
     * {@code div:has(标题) button:has(按钮锚点)}，而 {@code div} 会命中所有包含该标题的祖先层（html/body/…），
     * 计数必 >1 被丢弃；XPath 的 {@code //父[.//标题]//目标[.//子锚点]} 结果集是<b>目标节点</b>，
     * 只要按钮唯一就成立。
     *
     * <p>只产候选，唯一性由 {@link LocatorCounter}（Playwright 引擎）裁决——页面侧
     * {@code document.querySelectorAll} 不穿透 shadow root、按 document 计数，不能作为最终裁判。
     *
     * @param facts 元素事实（self + ancestors）
     * @return 形如 {@code //div[.//h3[@data-i18n="step3"]]//button[.//*[@data-i18n="continue"]]} 的候选（去重、有上限）
     */
    static List<String> anchoredXPaths(CodegenFacts facts) {
        List<String> out = new ArrayList<>();
        if (facts == null || facts.self == null) return out;
        CodegenFacts.Fact self = facts.self;
        String selfTag = self.tag == null ? "" : self.tag.toLowerCase(Locale.ROOT);
        if (!SIMPLE_TAG.matcher(selfTag).matches() || "html".equals(selfTag) || "body".equals(selfTag)) return out;

        List<String> selfPreds = selfPredicates(self);
        List<String> childPreds = anchorPredicates(self.childAnchors, MAX_XPATH_CHILD_ANCHORS);
        // 1) 目标侧单锚点：//button[@data-testid="x"] / //button[.//*[@data-i18n="x"]]
        for (String p : selfPreds) addUnique(out, "//" + selfTag + p);
        for (String p : childPreds) addUnique(out, "//" + selfTag + p);

        // 2) 父级锚点 × 目标侧（父级标识分区，目标侧标识控件本身）
        //
        // 两条约束来自 2026-11-17 的实跑修正（Reset Password 页的两个 Continue）：
        // ① 父级谓词不得与目标侧谓词相同——相同即说明该"锚点"取自目标自身子树
        //    （`div:has(span[data-i18n="continue"])` 里的 span 就是按钮内部的文案），
        //    它对"这个按钮属于哪个分区"零区分度，只会产出
        //    `//div[.//*[@data-i18n="continue"]]//button[.//*[@data-i18n="continue"]]` 这种废候选并白占额度；
        // ② 跨祖先<b>轮转</b>，而不是按祖先顺序逐个灌满：近祖先（`.button-panel`）的多个锚点会把
        //    {@link #MAX_XPATH_CANDIDATES} 吃光，真正有分区区分力的<b>外层标题</b>
        //    （`.page` 的 `h1[data-i18n="title_triage"]`）反而轮不上——实测 12 条候选里一条 title 都没有。
        //    轮转后每个祖先的「第一优先锚点」（JS 侧已按标题优先排序）先全部试一遍，再试第二优先。
        List<String> targetSide = new ArrayList<>(selfPreds);
        targetSide.addAll(childPreds);
        Set<String> targetPredicates = new LinkedHashSet<>(targetSide);

        List<String> parentTags = new ArrayList<>();
        List<List<String>> parentPredicates = new ArrayList<>();
        for (CodegenFacts.Fact a : facts.ancestors) {
            if (parentTags.size() >= MAX_XPATH_PARENTS) break;
            String atag = a.tag == null ? "" : a.tag.toLowerCase(Locale.ROOT);
            if (!SIMPLE_TAG.matcher(atag).matches() || "html".equals(atag) || "body".equals(atag)) continue;
            List<String> preds = new ArrayList<>(selfPredicates(a));
            preds.addAll(anchorPredicates(a.childAnchors, MAX_XPATH_CHILD_ANCHORS));
            preds.removeAll(targetPredicates);   // ① 自指过滤
            if (preds.isEmpty()) continue;
            parentTags.add(atag);
            parentPredicates.add(preds);
        }
        int maxRounds = MAX_XPATH_SELF_ATTRS + MAX_XPATH_CHILD_ANCHORS;
        for (int round = 0; round < maxRounds; round++) {
            for (int i = 0; i < parentTags.size(); i++) {
                List<String> preds = parentPredicates.get(i);
                if (round >= preds.size()) continue;
                String prefix = "//" + parentTags.get(i) + preds.get(round) + "//" + selfTag;
                for (String tp : targetSide) {
                    addUnique(out, prefix + tp);
                    if (out.size() >= MAX_XPATH_CANDIDATES) return out;
                }
            }
        }
        return out;
    }

    /**
     * 元素自身稳定属性谓词（identity 类：{@code id} / {@code data-testid} / {@code data-} 语义属性 /
     * {@code name}，属性类最多 {@value #MAX_XPATH_SELF_ATTRS} 个）。
     *
     * <p>状态类 ARIA（{@code aria-hidden} 等）不进：它们是共享状态值，作 XPath 身份谓词只会命中整族。
     */
    private static List<String> selfPredicates(CodegenFacts.Fact f) {
        List<String> out = new ArrayList<>();
        if (f == null) return out;
        if (f.id != null && !f.id.isEmpty() && SelectorStability.isStable(f.id)) {
            String lit = xpathLiteral(f.id);
            if (lit != null) out.add("[@id=" + lit + "]");
        }
        if (f.testid != null && !f.testid.isEmpty() && SelectorStability.isStable(f.testid)) {
            String lit = xpathLiteral(f.testid);
            if (lit != null) out.add("[@data-testid=" + lit + "]");
        }
        // 表单 name：与语言无关，是 input 类元素最稳定的身份依据之一（{@code attrs} 只含 data-*/aria-*，
        // 故不能指望它从属性表里被遍历到，必须显式补）。
        if (f.nameAttr != null && !f.nameAttr.isEmpty() && SelectorStability.isStable(f.nameAttr)) {
            String lit = xpathLiteral(f.nameAttr);
            if (lit != null) out.add("[@name=" + lit + "]");
        }
        int n = 0;
        for (Map.Entry<String, String> e : sortedAttrs(f.attrs)) {
            if (n >= MAX_XPATH_SELF_ATTRS) break;
            String key = e.getKey() == null ? "" : e.getKey().toLowerCase(Locale.ROOT);
            if (!ATTR_NAME.matcher(e.getKey() == null ? "" : e.getKey()).matches()) continue;
            // 状态类 ARIA 不能当身份锚点（与 ARIA_STATE_ONLY / 页面侧 stableAttrs 同口径）
            if (ARIA_STATE_ONLY.contains(key)) continue;
            if (!SelectorStability.isStable(e.getValue())) continue;
            String lit = xpathLiteral(e.getValue());
            if (lit == null) continue;
            out.add("[@" + e.getKey() + "=" + lit + "]");
            n++;
        }
        return out;
    }

    /** 后代锚点 → XPath 谓词（{@code [.//*[@data-i18n="x"]]} / {@code [.//h3[@data-i18n="t"]]}）。 */
    private static List<String> anchorPredicates(List<String> anchors, int max) {
        List<String> out = new ArrayList<>();
        if (anchors == null) return out;
        for (String a : anchors) {
            if (out.size() >= max) break;
            String p = anchorToXPathPredicate(a, true);
            if (p != null && !out.contains(p)) out.add(p);
        }
        return out;
    }

    /**
     * CSS 锚点段 → XPath 谓词。
     *
     * @param anchor     {@code #id} / {@code [attr="v"]} / {@code tag[attr="v"]}
     * @param descendant {@code true} = 目标<b>内部</b>的后代锚点（{@code [.//…]}）；{@code false} = 该节点自身属性
     * @return 谓词；无法安全转换（含引号/反斜杠/非法名）时返回 {@code null}
     */
    static String anchorToXPathPredicate(String anchor, boolean descendant) {
        if (anchor == null) return null;
        String a = anchor.trim();
        if (a.isEmpty()) return null;
        if (a.charAt(0) == '#') {
            String lit = xpathLiteral(a.substring(1));
            if (lit == null) return null;
            return descendant ? "[.//*[@id=" + lit + "]]" : "[@id=" + lit + "]";
        }
        Matcher m = CSS_ANCHOR.matcher(a);
        if (!m.matches()) return null;
        // 无标签头时必须落到通配符 `*`：`[.//[@data-i18n="x"]]` 是非法 XPath（谓词前缺少轴步），
        // 引擎会直接抛 SyntaxError ⇒ 整条候选作废。页面侧 codegen-inject.js#cssAnchorToXpath 同样补 `*`。
        String tag = m.group(1) == null ? "*" : m.group(1);
        String attr = m.group(2);
        String lit = xpathLiteral(m.group(3));
        if (lit == null) return null;
        if (!descendant) return "[@" + attr + "=" + lit + "]";
        return "[.//" + tag + "[@" + attr + "=" + lit + "]]";
    }

    /**
     * XPath 1.0 字符串字面量：值里没有 {@code "} 用双引号，没有 {@code '} 用单引号，
     * 两者都有（极罕见）返回 {@code null} 表示放弃该候选（不做 {@code concat()} 拼接，保持可读）。
     */
    static String xpathLiteral(String value) {
        if (value == null) return null;
        if (value.indexOf('"') < 0) return "\"" + value + "\"";
        if (value.indexOf('\'') < 0) return "'" + value + "'";
        return null;
    }

    private static void addUnique(List<String> out, String xpath) {
        if (xpath != null && !xpath.isEmpty() && !out.contains(xpath)) out.add(xpath);
    }
}
