package com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen.model;

import com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen.locator.LocatorStrategy;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 一次拾取的完整结果（同时也是推送给面板的 JSON 载荷）。
 *
 * <p>字段命名即面板 JS 侧读取的键；{@link #toMap()} 供 {@code page.evaluate} 直接序列化。
 */
public final class CodegenEntry {

    /**
     * 该条目不走 role 策略（CSS / XPath），{@link #roleHits} 不适用。
     *
     * <p>历史构造（未传 {@code roleHits}）一律取此值，保证既有调用方与测试零行为变更。
     */
    public static final int ROLE_HITS_NOT_APPLICABLE = -3;

    /** role 候选已给出，但生成前的引擎复核尚未执行——<b>命中数未知</b>。 */
    public static final int ROLE_HITS_UNVERIFIED = -2;

    /** 复核执行了但引擎不可用/抛异常——<b>不可判定</b>（与 {@code LocatorCounter} 的 {@code -1} 同源）。 */
    public static final int ROLE_HITS_UNDETERMINABLE = -1;

    public final String sigKey;
    public final String role;
    public final String name;
    public final String strategy;
    public final int score;
    public final String selector;
    public final String cssSelector;
    public final String nlsKey;
    /**
     * 在「同定位器」中的序号（0 起）。用于 List 识别：多个拾取共享同一 base-sig（剔除 {@code #index} 后）
     * 且序号互异时，判定为「一组元素」→ 生成 {@code PageElementList}。
     * 由注入脚本按"同级兄弟元素位置"计算；单元素定位恒为 {@code -1}。
     */
    public final int index;
    /**
     * 跨 frame 穿透路径（外层 → 内层，即 outermost-first）：每个元素是父 frame 文档内那个
     * {@code <iframe>} 元素的 CSS 选择器。元素位于主 frame 时为空列表。
     *
     * <p>这是支持<b>跨源 iframe</b> 录制/拾取可运行落地的关键：跨源 iframe 内的元素 CSS 只相对于
     * 其所属 frame 文档，必须串成 {@code frame=SELa :: SELb :: <内层 css>}，由框架
     * {@code LocatorFactory.bySelector} 解析为 {@code page.frameLocator(SELa).frameLocator(SELb).locator(<内层 css>)}。
     */
    public final java.util.List<String> framePath;
    /**
     * 元素所在的 open shadow 宿主链（outermost-first；主文档元素为空列表）。每层选择器相对其自身文档 /
     * 外层 shadow root 计算，与 {@link #cssSelector} 口径一致。
     *
     * <p>用于 CDP 侧按选择器反查元素（{@code document.querySelector} 不穿透 shadow 边界）；
     * 生成代码无需特殊处理——Playwright 的 CSS 引擎自动穿透 open shadow root。
     */
    public final java.util.List<String> shadowHosts;
    /**
     * <b>CSS/XPath 候选链</b>最终命中数（{@code 1} = 唯一；{@code >1} = 一组；{@code 0} = 当前无命中；
     * {@code -1} = 不可判定）。
     *
     * <p>role 策略条目里这个值只是「兜底 CSS」的计数，<b>不是</b>最终定位器 {@code getByRole} 的命中数——
     * 后者由 {@link #roleHits} 独立承载。生成侧一律经 {@link #effectiveMatchCount()} 取数，
     * 禁止直接读本字段判断单元素 / 一组（评审 P0-1）。
     */
    public final int matchCount;

    /**
     * role 定位器（{@code getByRole(role, name)}）经 Playwright <b>真实引擎</b>复核得到的命中数。
     *
     * <p>三态 + 一态：{@code >=0} = 复核成功且为真实命中数；{@link #ROLE_HITS_UNVERIFIED} =
     * 复核尚未执行；{@link #ROLE_HITS_UNDETERMINABLE} = 复核跑过但引擎不可用；
     * {@link #ROLE_HITS_NOT_APPLICABLE} = 该条目不走 role 策略。
     *
     * <p><b>为什么必须与 {@link #matchCount} 解耦</b>：两者分属两个完全不同的查询引擎
     * （CSS 选择器引擎 vs Playwright roleUtils），数值没有任何换算关系。role 复核又刻意推迟到生成前
     * （见 {@code CodegenCdpNodeExtractor#requiresDeferredRoleVerification}），
     * 拾取时刻只有 CSS 计数可用。混用会让「CSS 命中 5、role 命中 1」的元素被判成
     * {@code PageElementList + get(0)}（组语义静默降级），或反过来漏报 strict violation。
     *
     * <p>不可判定与未复核都<b>不是</b>「唯一」，调用方必须按此处理并在产物里显式标注。
     */
    public final int roleHits;
    /**
     * 元素标签名（小写，如 {@code select} / {@code input}），可能为 null（历史构造 / 测试）。
     *
     * <p>生成侧用它区分「原生 <b>&lt;select&gt;</b>」与「自定义下拉（div role=combobox）」：
     * 前者必须生成 {@code selectByValue(...)}，后者只能展开后点选项，两者都不能用 {@code fill()}。
     * additive 字段，旧构造保留（{@code null} = 未知）。
     */
    public final String tag;
    /**
     * 元素在拾取时刻的勾选态（仅 checkbox / radio 有意义）。
     *
     * <p>生成侧据此区分 {@code check()} / {@code uncheck()} 与断言期望值 {@code is(true/false)}：
     * 不读该状态时，录制「取消勾选」会退化成 {@code check()}，产物与录制终态相反。
     */
    public final boolean checked;
    /**
     * 是否由「目标本身是 ARIA 集合里的一项」<b>自动</b>判成一组元素（{@code PageElementList}）。
     *
     * <p>与「多次拾取 / 面板标记为一组」的区别在于：那种组的 {@link #index} 是组内成员序号，
     * 生成侧固定取 {@code get(0)}；而自动成组的 {@link #index} 是<b>用户实际点的那一项</b>的序号
     * （如密保问题下拉里第 5 个 {@code li[role=option]}），生成侧必须取 {@code get(index)}——
     * 否则回放会点错选项。
     */
    public final boolean autoList;

    public CodegenEntry(String sigKey, String role, String name, String strategy,
                        int score, String selector, String cssSelector, String nlsKey, int index) {
        this(sigKey, role, name, strategy, score, selector, cssSelector, nlsKey, index,
                java.util.List.of(), java.util.List.of());
    }

    public CodegenEntry(String sigKey, String role, String name, String strategy,
                        int score, String selector, String cssSelector, String nlsKey, int index,
                        java.util.List<String> framePath) {
        this(sigKey, role, name, strategy, score, selector, cssSelector, nlsKey, index,
                framePath, java.util.List.of());
    }

    public CodegenEntry(String sigKey, String role, String name, String strategy,
                        int score, String selector, String cssSelector, String nlsKey, int index,
                        java.util.List<String> framePath, java.util.List<String> shadowHosts) {
        this(sigKey, role, name, strategy, score, selector, cssSelector, nlsKey, index,
                framePath, shadowHosts, 1);
    }

    /**
     * 完整构造（含 CSS 候选链命中数）。
     *
     * @param matchCount {@link #matchCount} 的值；生成侧判单元素/一组必须经
     *                   {@link #effectiveMatchCount()}，不得直接读本参数
     */
    public CodegenEntry(String sigKey, String role, String name, String strategy,
                        int score, String selector, String cssSelector, String nlsKey, int index,
                        java.util.List<String> framePath, java.util.List<String> shadowHosts, int matchCount) {
        this(sigKey, role, name, strategy, score, selector, cssSelector, nlsKey, index,
                framePath, shadowHosts, matchCount, null);
    }

    /**
     * 完整构造（含 CSS 候选链命中数与元素标签）。
     *
     * @param matchCount {@link #matchCount} 的值；生成侧判单元素/一组必须经
     *                   {@link #effectiveMatchCount()}，不得直接读本参数
     * @param tag        元素标签名（小写）；{@code null} 表示未知
     */
    public CodegenEntry(String sigKey, String role, String name, String strategy,
                        int score, String selector, String cssSelector, String nlsKey, int index,
                        java.util.List<String> framePath, java.util.List<String> shadowHosts,
                        int matchCount, String tag) {
        this(sigKey, role, name, strategy, score, selector, cssSelector, nlsKey, index,
                framePath, shadowHosts, matchCount, tag, false);
    }

    /**
     * 完整构造（含元素标签与勾选态）。
     *
     * @param tag     元素标签名（小写）；{@code null} 表示未知
     * @param checked 拾取时刻的勾选态（checkbox / radio 有意义）
     */
    public CodegenEntry(String sigKey, String role, String name, String strategy,
                        int score, String selector, String cssSelector, String nlsKey, int index,
                        java.util.List<String> framePath, java.util.List<String> shadowHosts,
                        int matchCount, String tag, boolean checked) {
        this(sigKey, role, name, strategy, score, selector, cssSelector, nlsKey, index,
                framePath, shadowHosts, matchCount, tag, checked, ROLE_HITS_NOT_APPLICABLE);
    }

    /**
     * 完整构造（含 role 引擎复核结果）。
     *
     * @param roleHits role 定位器复核命中数；非 role 策略传 {@link #ROLE_HITS_NOT_APPLICABLE}，
     *                 尚未复核传 {@link #ROLE_HITS_UNVERIFIED}
     */
    public CodegenEntry(String sigKey, String role, String name, String strategy,
                        int score, String selector, String cssSelector, String nlsKey, int index,
                        java.util.List<String> framePath, java.util.List<String> shadowHosts,
                        int matchCount, String tag, boolean checked, int roleHits) {
        this(sigKey, role, name, strategy, score, selector, cssSelector, nlsKey, index,
                framePath, shadowHosts, matchCount, tag, checked, roleHits, false);
    }

    /**
     * 完整构造（含 role 复核结果与「集合项自动成组」标记）。
     *
     * @param autoList {@code true} 表示本条目不来自多次拾取，而是由「目标本身是 ARIA 集合里的一项」自动判成
     *                 一组元素（{@link #cssSelector} 是命中整组的定位器，{@link #index} 是被拾取项在组内的
     *                 0 基序号）。Step 生成据此用 {@code get(index)} 指向用户实际点的那一项，而不是 {@code get(0)}。
     */
    public CodegenEntry(String sigKey, String role, String name, String strategy,
                        int score, String selector, String cssSelector, String nlsKey, int index,
                        java.util.List<String> framePath, java.util.List<String> shadowHosts,
                        int matchCount, String tag, boolean checked, int roleHits, boolean autoList) {
        this.sigKey = sigKey;
        this.role = role;
        this.name = name;
        this.strategy = strategy;
        this.score = score;
        this.selector = selector;
        this.cssSelector = cssSelector;
        this.nlsKey = nlsKey;
        this.index = index;
        this.framePath = framePath == null ? java.util.List.of() : framePath;
        this.shadowHosts = shadowHosts == null ? java.util.List.of() : shadowHosts;
        // 原样保留 0（当前无命中）/ -1（计数不可判定）：过去 Math.max(1, …) 把两者抬成 1，
        // 生成侧因此无法在产物里标注「这个定位器当时一个都没匹配到」。isList 判据用 >1，不受影响。
        this.matchCount = matchCount;
        this.tag = tag == null || tag.isBlank() ? null : tag.toLowerCase(java.util.Locale.ROOT);
        this.checked = checked;
        if (roleHits < ROLE_HITS_NOT_APPLICABLE) {
            throw new IllegalArgumentException("invalid roleHits: " + roleHits);
        }
        this.roleHits = roleHits;
        this.autoList = autoList;
    }

    /** 是否为 role 策略（决定 {@link #effectiveMatchCount()} 该取哪个引擎的计数）。 */
    public boolean isRoleStrategy() {
        return LocatorStrategy.ROLE.equals(strategy) || LocatorStrategy.ROLE_WITH_NAME.equals(strategy);
    }

    /**
     * <b>最终生效定位器</b>的命中数；生成侧判「单元素 / 一组」的唯一入口。
     *
     * @return CSS 策略 → {@link #matchCount}；role 策略 → {@link #roleHits}；
     *         未复核 / 不可判定 / 不适用一律返回负数（调用方不得当成 1 个或一组）
     */
    public int effectiveMatchCount() {
        return effectiveMatchCount(isRoleStrategy());
    }

    /**
     * 同上，但由调用方指定<b>产物里实际采用的</b>定位引擎。
     *
     * <p>{@code CodegenFieldPlanner} 可能因多语言门控（无 NLS key）、角色不可映射、跨 frame 等原因，
     * 把一条 {@code strategy=ROLE} 的条目改渲染成 {@code @Element(css)}；此时最终生效引擎是 CSS，
     * 计数也必须随之改读 {@link #matchCount}。{@code roleUsed} 必须是生成侧的真实决策，
     * 不能凭 {@code strategy} 猜。
     */
    public int effectiveMatchCount(boolean roleUsed) {
        return roleUsed ? roleHits : matchCount;
    }

    /** 复制并替换 CSS 命中数（其余字段含 {@code roleHits} / {@code autoList} 不变）。 */
    public CodegenEntry withMatchCount(int count) {
        return new CodegenEntry(sigKey, role, name, strategy, score, selector, cssSelector, nlsKey,
                index, framePath, shadowHosts, count, tag, checked, roleHits, autoList);
    }

    /** 复制并替换 role 复核命中数（其余字段逐字保留）。 */
    public CodegenEntry withRoleHits(int hits) {
        return new CodegenEntry(sigKey, role, name, strategy, score, selector, cssSelector, nlsKey,
                index, framePath, shadowHosts, matchCount, tag, checked, hits, autoList);
    }

    /**
     * 复制并只替换定位策略 / 选择器，其余字段（{@code matchCount / roleHits / tag / checked /
     * framePath / shadowHosts / nlsKey / score / index}）<b>逐字保留</b>。
     *
     * <p>专供「role 引擎复核失败 ⇒ 回退 CSS」这类语义修订：不得因为换定位器而悄悄把原生
     * {@code <select>}、勾选态、组元素等生成侧语义一起丢掉。{@code roleHits} 之所以也保留，
     * 是因为 {@link #effectiveMatchCount()} 会按<b>新策略</b>自动改读 {@code matchCount}，
     * 历史复核值留作诊断证据（面板可见），不影响产物判定。
     */
    public CodegenEntry withLocator(String strategy, String selector, String cssSelector) {
        return new CodegenEntry(sigKey, role, name, strategy, score, selector, cssSelector, nlsKey,
                index, framePath, shadowHosts, matchCount, tag, checked, roleHits, autoList);
    }

    /** 复制并只替换勾选态，供「同元素多次操作」按末次状态生成断言。 */
    public CodegenEntry withChecked(boolean checked) {
        return new CodegenEntry(sigKey, role, name, strategy, score, selector, cssSelector, nlsKey,
                index, framePath, shadowHosts, matchCount, tag, checked, roleHits, autoList);
    }

    /**
     * 复制并只替换 {@code sigKey}，其余字段（定位器 / 计数 / 组语义 …）<b>逐字保留</b>。
     *
     * <p>唯一用途是「孪生元素消歧」：两处 role+name+组内序号完全相同的<b>不同</b>元素会算出相同 sigKey，
     * 而字段规划按 sigKey 去重 ⇒ 第二个元素会被当成重复条目丢弃。由
     * {@code CodegenLauncher#disambiguateSigKey} 给后来者追加 {@code @n} 后缀，使两个元素各自成为独立字段。
     *
     * @param sigKey 新的签名（不得为空）
     */
    public CodegenEntry withSigKey(String sigKey) {
        String next = (sigKey == null || sigKey.isEmpty()) ? this.sigKey : sigKey;
        return new CodegenEntry(next, role, name, strategy, score, selector, cssSelector, nlsKey,
                index, framePath, shadowHosts, matchCount, tag, checked, roleHits, autoList);
    }

    /** 转为面板 evaluate 可直接序列化的 Map（字段名即面板 JS 读取的键）。 */
    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("sigKey", sigKey);
        m.put("role", role);
        m.put("name", name == null ? "" : name);
        m.put("strategy", strategy);
        m.put("score", score);
        m.put("selector", selector);
        m.put("cssSelector", cssSelector);
        m.put("nlsKey", nlsKey == null ? "" : nlsKey);
        m.put("index", index);
        m.put("framePath", new ArrayList<>(framePath));
        m.put("shadowHosts", new ArrayList<>(shadowHosts));
        m.put("matchCount", matchCount);
        m.put("roleHits", roleHits);
        m.put("tag", tag == null ? "" : tag);
        m.put("checked", checked);
        m.put("autoList", autoList);
        return m;
    }
}
