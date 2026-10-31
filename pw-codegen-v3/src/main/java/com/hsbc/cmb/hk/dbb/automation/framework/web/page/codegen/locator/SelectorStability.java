package com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen.locator;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 属性值稳定性判定：区分「语义化的稳定值」与「框架/随机生成的值」，并为后者提取可用的部分匹配片段。
 *
 * <p><b>为什么要判定</b>：{@code id}/{@code data-testid}/{@code class} 在现代前端大量由框架生成，属于一次性值：
 * <ul>
 *   <li>React {@code useId}：{@code :r1:}、{@code radix-:r7:}；</li>
 *   <li>Angular：{@code mat-input-3}、{@code ng-tns-c12-4}；</li>
 *   <li>MUI/Emotion：{@code mui-42}、{@code css-1a2b3c}；</li>
 *   <li>UUID / hash / 时间戳：{@code 8f14e45f-ea...}、{@code a3f9c1d2}、{@code el-id-1729930000123-0}。</li>
 * </ul>
 * 用它们生成 {@code #id} 或 {@code [data-testid="..."]}，本次能跑、下次构建就跑不通。正确做法是
 * <b>退到稳定片段的部分匹配</b>（{@code ^=}/{@code $=}/{@code *=}，仍须通过唯一性校验），
 * 而不是直接放弃成结构 CSS。
 *
 * <p>判定刻意保守（宁可保留也不误杀语义值）：{@code userName}、{@code newPasswordForm}、
 * {@code otp_radio_label}、{@code profile-list} 都判为稳定。
 */
public final class SelectorStability {

    /** UUID（含大小写）。 */
    private static final Pattern UUID = Pattern.compile(
            "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$", Pattern.CASE_INSENSITIVE);

    /** React 19 useId：{@code :r1:}、{@code :R2m:}（也常被拼进 radix 的 id 前缀）。 */
    private static final Pattern REACT_USE_ID = Pattern.compile(":r[0-9a-z]{1,4}:", Pattern.CASE_INSENSITIVE);

    /**
     * Radix 夹心随机段（infix）：{@code dropdown-list-_R_kmub7m_}、{@code menu-_R_1a2b3c4d_-trigger}。
     *
     * <p>注意整串用 {@link Matcher#find()}：随机段出现在值中间而非首尾，matches() 会漏掉。
     * 段级切分（{@link #parts}）还会把 {@code _R_kmub7m_} 拆成 {@code R}/{@code kmub7m}，
     * 而 {@code kmub7m} 既非 hex 又不满足高熵阈值（<12 位），会被误判为"稳定段"拼进前缀——
     * 因此 {@code parts()} 必须同时识别下划线包夹的 Radix 段。
     */
    private static final Pattern RADIX_INFIX = Pattern.compile("_R_[A-Za-z0-9]{4,}_");

    /** 纯十六进制（≥8 位且字母数字混合）——打包器 hash 的典型形态。 */
    private static final Pattern HEX_HASH = Pattern.compile("^[0-9a-f]{8,}$", Pattern.CASE_INSENSITIVE);

    /** 片段级十六进制 hash（≥4 位）：{@code a1b2}、{@code 9f3c} 这类短 hash 片段同样不可用。 */
    private static final Pattern SHORT_HEX = Pattern.compile("^[0-9a-f]{4,}$", Pattern.CASE_INSENSITIVE);

    /** 纯数字（≥6 位）——自增序号 / 时间戳。 */
    private static final Pattern PURE_DIGITS = Pattern.compile("^\\d{6,}$");

    /** 尾部长数字段——时间戳后缀。 */
    private static final Pattern TRAILING_TIMESTAMP = Pattern.compile("[-_]\\d{10,}$");

    /** 中间长数字段（≥8 位连续数字）——时间戳/hash 数字段。 */
    private static final Pattern LONG_DIGIT_RUN = Pattern.compile("\\d{8,}");

    /**
     * 已知 UI 框架/工具生成的「语义 + 自增序号」形态。
     *
     * <p>注意用整串匹配（{@link Matcher#matches()}），避免把 {@code form-input} 之类的语义值误判。
     */
    private static final List<Pattern> FRAMEWORK_GENERATED = List.of(
            Pattern.compile("^(ember|ext-gen|gwt-|yui_|j_id_|__bvid__)\\d+.*", Pattern.CASE_INSENSITIVE),
            Pattern.compile("^react-select-\\d+.*", Pattern.CASE_INSENSITIVE),
            Pattern.compile("^headlessui-.*-\\d+.*", Pattern.CASE_INSENSITIVE),
            Pattern.compile("^radix-.*:\\w+:$", Pattern.CASE_INSENSITIVE),
            Pattern.compile("^rc-[a-z]+-\\d+.*", Pattern.CASE_INSENSITIVE),
            Pattern.compile("^(el|ant|mat|mui)-[a-z0-9-]*\\d+.*", Pattern.CASE_INSENSITIVE),
            Pattern.compile("^downshift-\\d+.*", Pattern.CASE_INSENSITIVE),
            Pattern.compile("^ng-tns-.*", Pattern.CASE_INSENSITIVE),
            Pattern.compile("^_ngcontent-.*", Pattern.CASE_INSENSITIVE),
            Pattern.compile("^(css|sc)-[a-z0-9]{5,}$", Pattern.CASE_INSENSITIVE),
            Pattern.compile("^\\d+-\\d+$"));

    /** 部分匹配片段的最小长度（太短会命中一大片，最终也会被唯一性校验否掉，干脆不生成）。 */
    private static final int MIN_FRAGMENT_LENGTH = 3;

    private SelectorStability() {
    }

    /**
     * 值是否「一次性/随机」（不可用于精确属性定位）。
     *
     * @param value 属性值；{@code null}/空白视为不可用（{@code true}）
     */
    public static boolean isVolatile(String value) {
        if (value == null) return true;
        String v = value.trim();
        if (v.isEmpty()) return true;
        if (UUID.matcher(v).matches()) return true;
        if (REACT_USE_ID.matcher(v).find()) return true;
        if (RADIX_INFIX.matcher(v).find()) return true;
        if (PURE_DIGITS.matcher(v).matches()) return true;
        if (TRAILING_TIMESTAMP.matcher(v).find()) return true;
        if (LONG_DIGIT_RUN.matcher(v).find()) return true;
        for (Pattern p : FRAMEWORK_GENERATED) {
            if (p.matcher(v).matches()) return true;
        }
        if (HEX_HASH.matcher(v).matches() && hasDigit(v) && hasLetter(v)) return true;
        return isHighEntropy(v);
    }

    /** 值是否可直接用于精确属性定位。 */
    public static boolean isStable(String value) {
        return value != null && !value.isBlank() && !isVolatile(value);
    }

    /**
     * 从可能随机的值里提取语义片段（用于 {@code *=} 包含匹配）。
     *
     * @return 去重、保序的片段；无可用片段返回空列表
     */
    public static List<String> stableTokens(String value) {
        Set<String> out = new LinkedHashSet<>();
        for (Part p : parts(value)) {
            if (p.isVolatile() || p.text.length() < MIN_FRAGMENT_LENGTH) continue;
            out.add(p.text);
        }
        return new ArrayList<>(out);
    }

    /**
     * 稳定前缀（用于 {@code ^=}）：从头开始连续稳定片段拼成的原文片段。
     *
     * <p>例：{@code mat-input-3} → {@code mat-input}；{@code a3f9c1d2-userName} → 空（首段随机）。
     *
     * @return 前缀；不足 {@link #MIN_FRAGMENT_LENGTH} 位或等于整值时返回空串
     */
    public static String stablePrefix(String value) {
        List<Part> ps = parts(value);
        int end = -1;
        for (Part p : ps) {
            if (p.isVolatile()) break;
            end = p.end;
        }
        if (end <= 0) return "";
        return trimTrailingSeparators(value.substring(0, end));
    }

    /**
     * 稳定后缀（用于 {@code $=}）：从尾部开始连续稳定片段拼成的原文片段。
     *
     * <p>例：{@code a3f9c1d2-userName-input} → {@code userName-input}。
     */
    public static String stableSuffix(String value) {
        List<Part> ps = parts(value);
        int start = -1;
        for (int i = ps.size() - 1; i >= 0; i--) {
            Part p = ps.get(i);
            if (p.isVolatile()) break;
            start = p.start;
        }
        if (start < 0) return "";
        return trimLeadingSeparators(value.substring(start));
    }

    /**
     * class 属性里的稳定类名（{@code class} 以空格分隔类名，类名内部允许 {@code -}，故按空格切分）。
     *
     * <p>例：{@code "login-form primary css-1a2b3c"} → {@code ["login-form", "primary"]}。
     * 只按连字符切分会把 {@code login-form} 拆成 {@code login}/{@code form}，生成过宽的选择器。
     */
    public static List<String> stableClassNames(String className) {
        Set<String> out = new LinkedHashSet<>();
        if (className == null || className.isBlank()) return new ArrayList<>();
        for (String raw : className.trim().split("\\s+")) {
            if (raw.length() < MIN_FRAGMENT_LENGTH) continue;
            if (isVolatile(raw)) continue;
            out.add(raw);
        }
        return new ArrayList<>(out);
    }

    // ------------------------------------------------------------------

    private static boolean isHighEntropy(String v) {
        if (v.length() < 12) return false;
        int digits = 0;
        int letters = 0;
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            if (c >= '0' && c <= '9') digits++;
            else if (Character.isLetter(c)) letters++;
        }
        // 长且数字密集（≥3）又字母众多（≥5）⇒ 多半是 hash/打包产物，而非 camelCase 语义名
        return digits >= 3 && letters >= 5;
    }

    private static boolean hasDigit(String v) {
        for (int i = 0; i < v.length(); i++) {
            if (Character.isDigit(v.charAt(i))) return true;
        }
        return false;
    }

    private static boolean hasLetter(String v) {
        for (int i = 0; i < v.length(); i++) {
            if (Character.isLetter(v.charAt(i))) return true;
        }
        return false;
    }

    private static String trimTrailingSeparators(String s) {
        int end = s.length();
        while (end > 0 && !isAlnum(s.charAt(end - 1))) end--;
        return s.substring(0, end);
    }

    private static String trimLeadingSeparators(String s) {
        int start = 0;
        while (start < s.length() && !isAlnum(s.charAt(start))) start++;
        return s.substring(start);
    }

    private static boolean isAlnum(char c) {
        return Character.isLetterOrDigit(c);
    }

    /** 按「字母数字段」切分，并逐段判定是否随机。 */
    private static List<Part> parts(String value) {
        List<Part> out = new ArrayList<>();
        if (value == null || value.isBlank()) return out;
        Matcher m = Pattern.compile("[A-Za-z0-9]+").matcher(value);
        while (m.find()) {
            String text = m.group();
            boolean radixInfix = m.start() >= 2
                    && value.charAt(m.start() - 1) == '_'
                    && value.charAt(m.start() - 2) == 'R'
                    && m.end() < value.length()
                    && value.charAt(m.end()) == '_'
                    && text.length() >= 4;
            out.add(new Part(text, m.start(), m.end(),
                    isVolatilePart(text) || radixInfix));
        }
        return out;
    }

    /**
     * 单段是否随机（整串判定会用到整值规则，这里用段级规则，避免把 {@code input} 这类语义段误杀）。
     */
    private static boolean isVolatilePart(String text) {
        if (text.isEmpty()) return true;
        if (PURE_DIGITS.matcher(text).matches()) return true;
        if (text.length() < MIN_FRAGMENT_LENGTH) return true;   // "1"/"x9" 之类的碎片
        if (SHORT_HEX.matcher(text).matches() && hasDigit(text) && hasLetter(text)) return true;
        if (isHighEntropy(text)) return true;
        for (Pattern p : FRAMEWORK_GENERATED) {
            if (p.matcher(text).matches()) return true;
        }
        // 纯大写短段（BVID 之类）保留；纯数字已排除
        return false;
    }

    private record Part(String text, int start, int end, boolean volatile_) {
        boolean isVolatile() {
            return volatile_;
        }
    }
}
