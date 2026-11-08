package com.hsbc.cmb.hk.dbb.automation.framework.agent.navigation;

import com.hsbc.cmb.hk.dbb.automation.framework.agent.observation.ObservedElement;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.observation.PageObservation;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * 把<b>业务描述 / 菜单文案</b>解析为页面上唯一的 {@link com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.LocatorSpec}。
 *
 * <p>这是「零元素定位输入」的决策核心：输入来自需求里的自然语言（如 {@code Payments}、
 * {@code 转账}、NLS key），输入证据只有运行时的 {@link PageObservation}；框架不持有任何
 * 选择器、XPath 或预置路由表，因此每次调用都必须<strong>重新观察当前页面</strong>——
 * 连续多个 {@code clickOn("菜单文案")} 天然构成「按菜单文案逐级点击 + 每步复观察」。</p>
 *
 * <p><b>打分（可解释、确定性）</b>：{@code nameKey} 精确 130 &gt; 可访问名/文本/NLS 属性精确 100
 * &gt; 前缀 82 &gt; 包含 68 &gt; 词元重叠 45..55；交互角色 +6、{@code generic} -4、
 * 命中 {@code preferredRoles} +12（如 {@code fillWith} 优先 textbox、{@code clickOn} 优先 link/button）。
 * 只统计 {@code visible && enabled} 且带首选定位符的元素。</p>
 *
 * <p><b>fail-closed</b>：最高分低于 {@link #MIN_ACCEPTED_SCORE}，或前两名分差不超过
 * {@link #MAX_AMBIGUITY_GAP} 时抛 {@link SemanticElementException}，绝不"选一个像的"。
 * 归一化用 NFKC + 小写 + 空白折叠，紧凑比较再去除标点，兼容大小写/全半角/多空格差异。</p>
 *
 * <p><b>线程安全</b>：无状态、不可变，可被多个场景线程共享；观察数据由调用方按线程提供。</p>
 */
public final class SemanticElementResolver {

    /** 低于该分数视为「没有可信匹配」。 */
    public static final int MIN_ACCEPTED_SCORE = 40;
    /** 前两名分差不超过该值视为歧义。 */
    public static final int MAX_AMBIGUITY_GAP = 8;
    /** 诊断清单最多保留的候选数。 */
    public static final int MAX_CANDIDATES = 5;

    private static final Set<String> INTERACTIVE_ROLES = Set.of(
            "button", "link", "menuitem", "menuitemcheckbox", "menuitemradio", "tab",
            "treeitem", "option", "checkbox", "radio", "textbox", "combobox", "searchbox",
            "spinbutton", "switch");

    public SemanticMatch resolve(PageObservation observation, String businessText) {
        return resolve(observation, businessText, Set.of());
    }

    /**
     * 解析唯一匹配元素。
     *
     * @param observation    当前页面的实时观察（不可为 null）
     * @param businessText   需求里的业务描述 / 菜单文案（不可为空）
     * @param preferredRoles 动作语义偏好的角色（小写，如 {@code link}/{@code textbox}）；可为 null
     * @return 唯一可信的匹配
     * @throws SemanticElementException 无可信匹配或存在歧义
     */
    public SemanticMatch resolve(PageObservation observation, String businessText,
                                 Set<String> preferredRoles) {
        List<SemanticMatch> ranked = rank(observation, businessText, preferredRoles);
        if (ranked.isEmpty() || ranked.get(0).score() < MIN_ACCEPTED_SCORE) {
            throw SemanticElementException.notFound(businessText, candidatesOf(ranked));
        }
        SemanticMatch top = ranked.get(0);
        if (ranked.size() > 1 && top.score() - ranked.get(1).score() <= MAX_AMBIGUITY_GAP) {
            throw SemanticElementException.ambiguous(businessText, top, ranked.get(1), candidatesOf(ranked));
        }
        return top;
    }

    /**
     * 返回按分数降序的全部候选（不抛异常），供「是否已到达目标功能页」这类判据使用。
     * 稳定排序保证观察顺序相同则结果相同，便于对账与回放。
     */
    public List<SemanticMatch> rank(PageObservation observation, String businessText,
                                    Set<String> preferredRoles) {
        Objects.requireNonNull(observation, "observation");
        String query = requireText(businessText);
        String normalizedQuery = normalize(query);
        String compactQuery = compact(normalizedQuery);
        if (compactQuery.isEmpty()) {
            throw new IllegalArgumentException("businessText 归一化后为空，无法解析: " + businessText);
        }

        List<SemanticMatch> ranked = new ArrayList<>();
        for (ObservedElement element : observation.elements()) {
            int score = score(element, normalizedQuery, compactQuery, preferredRoles);
            if (score >= 0) {
                ranked.add(new SemanticMatch(element, element.primaryLocator(), score, List.of()));
            }
        }
        ranked.sort(Comparator.comparingInt(SemanticMatch::score).reversed());

        List<SemanticMatch.Candidate> candidates = candidatesOf(ranked);
        List<SemanticMatch> withCandidates = new ArrayList<>(ranked.size());
        for (SemanticMatch m : ranked) {
            withCandidates.add(new SemanticMatch(m.element(), m.locator(), m.score(), candidates));
        }
        return List.copyOf(withCandidates);
    }

    private static int score(ObservedElement element, String normalizedQuery,
                             String compactQuery, Set<String> preferredRoles) {
        if (element == null || !element.visible() || !element.enabled()
                || element.primaryLocator() == null) {
            return -1;
        }
        int best = -1;
        if (element.nameKey() != null
                && compact(normalize(element.nameKey())).equals(compactQuery)) {
            best = 130;
        }
        for (String value : new String[]{element.name(), element.text(),
                element.attributes().get("data-i18n"), element.attributes().get("data-testid")}) {
            best = Math.max(best, fieldScore(value, normalizedQuery, compactQuery));
        }
        if (best < 0) {
            return -1;
        }

        String role = element.role() == null ? "" : element.role().toLowerCase(Locale.ROOT);
        int bonus = 0;
        if (INTERACTIVE_ROLES.contains(role)) {
            bonus += 6;
        }
        if ("generic".equals(role)) {
            bonus -= 4;
        }
        if (preferredRoles != null && preferredRoles.contains(role)) {
            bonus += 12;
        }
        return best + bonus;
    }

    private static int fieldScore(String value, String normalizedQuery, String compactQuery) {
        if (value == null || value.isBlank()) {
            return -1;
        }
        String normalized = normalize(value);
        if (compact(normalized).equals(compactQuery)) {
            return 100;
        }
        if (normalized.startsWith(normalizedQuery)) {
            return 82;
        }
        if (normalized.contains(normalizedQuery) || normalizedQuery.contains(normalized)) {
            return 68;
        }
        double overlap = tokenOverlap(normalized, normalizedQuery);
        if (overlap >= 0.5) {
            return (int) (45 + overlap * 10);
        }
        return -1;
    }

    private static double tokenOverlap(String left, String right) {
        Set<String> a = tokens(left);
        Set<String> b = tokens(right);
        if (a.isEmpty() || b.isEmpty()) {
            return 0;
        }
        Set<String> intersection = new HashSet<>(a);
        intersection.retainAll(b);
        Set<String> union = new HashSet<>(a);
        union.addAll(b);
        return (double) intersection.size() / union.size();
    }

    private static Set<String> tokens(String value) {
        Set<String> out = new HashSet<>();
        for (String token : value.split("[^\\p{L}\\p{N}]+")) {
            if (!token.isEmpty()) {
                out.add(token);
            }
        }
        return out;
    }

    private static List<SemanticMatch.Candidate> candidatesOf(List<SemanticMatch> ranked) {
        List<SemanticMatch.Candidate> out = new ArrayList<>();
        for (SemanticMatch m : ranked) {
            if (out.size() >= MAX_CANDIDATES) {
                break;
            }
            out.add(new SemanticMatch.Candidate(m.label(), m.element().role(), m.score(),
                    m.locator().canonical()));
        }
        return List.copyOf(out);
    }

    private static String requireText(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("businessText 不可为空");
        }
        return value;
    }

    private static String normalize(String value) {
        String nfkc = Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT);
        return nfkc.replaceAll("[\\s\\u00A0]+", " ").trim();
    }

    private static String compact(String value) {
        return value.replaceAll("[\\s\\p{Punct}]+", "");
    }

    /** 常用动作语义偏好，供执行层直接复用，避免各模块各写一份角色集合。 */
    public static final class PreferredRoles {
        public static final Set<String> CLICK = Set.of("button", "link", "menuitem", "tab", "treeitem");
        public static final Set<String> INPUT = Set.of("textbox", "combobox", "searchbox", "spinbutton");
        public static final Set<String> SELECT = Set.of("combobox", "listbox");
        public static final Set<String> TOGGLE = Set.of("checkbox", "radio", "switch");
        public static final Set<String> ANY = Set.of();

        private PreferredRoles() {
        }
    }

    /** 供 javadoc 之外的调用方按需组合偏好（返回不可变集合）。 */
    public static Set<String> roles(String... roles) {
        return Set.copyOf(Arrays.asList(roles));
    }
}
