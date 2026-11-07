package com.hsbc.cmb.hk.dbb.automation.framework.agent.impact;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * CSS / XPath 选择器分析器：抽出<b>稳定锚点</b>、识别<b>随机值</b>、给出<b>稳定化建议</b>。
 *
 * <p>role 定位不了时只能用 CSS/XPath，但前端常见动态 id/class（{@code j_idt23}、{@code ember1234}、
 * {@code sc-bdVaJa}、UUID、时间戳）。本类把这类取值判为随机值并<b>不</b>纳入锚点，同时给出可落地写法：
 * 前缀（{@code [id^='a1b']}）、后缀（{@code [id$='-input']}）、包含（{@code [id*='profile']}）、
 * 多属性组合（同元素多个稳定属性 AND）、父级限定（目标段全随机时用父级稳定属性 + 元素 tag/role）。</p>
 *
 * <p>只解析「目标元素那一步」（CSS 最后一段 / XPath 最后一个轴步）；父级段只用于生成建议，
 * 不参与锚点——否则父级锚点会和另一选择器的父级误配成「同一元素」。</p>
 *
 * <p><b>有界 fail-fast</b>：空白/超过 {@value #MAX_LENGTH} 字符直接拒绝，不猜测。
 * 无状态、线程安全。</p>
 */
public final class SelectorAnalyzer {

    /** 选择器长度上界（超长多半是把整段 DOM 粘贴进来）。 */
    public static final int MAX_LENGTH = 4096;

    private static final Pattern CSS_ATTR = Pattern.compile(
            "\\[\\s*([A-Za-z0-9_:-]+)\\s*(\\^=|\\$=|\\*=|~=|\\|=|=)\\s*(?:'([^']*)'|\"([^\"]*)\"|([^\\]\\s]+))\\s*\\]");
    private static final Pattern CSS_ID = Pattern.compile("#([A-Za-z0-9_:-]+)");
    private static final Pattern CSS_CLASS = Pattern.compile("\\.([A-Za-z0-9_-]+)");

    private static final Pattern XPATH_ATTR = Pattern.compile("@([A-Za-z0-9_:-]+)\\s*=\\s*['\"]([^'\"]*)['\"]");
    private static final Pattern XPATH_CONTAINS =
            Pattern.compile("(contains|starts-with|ends-with)\\s*\\(\\s*@([A-Za-z0-9_:-]+)\\s*,\\s*['\"]([^'\"]*)['\"]\\s*\\)");
    private static final Pattern XPATH_TEXT =
            Pattern.compile("(?:normalize-space\\()?text\\s*\\(\\s*\\)\\s*\\)?\\s*=\\s*['\"]([^'\"]*)['\"]");

    private static final Pattern PURE_DIGITS = Pattern.compile("\\d+");
    private static final Pattern HEX = Pattern.compile("(?i)[0-9a-f]+");
    private static final Pattern UUID = Pattern.compile(
            "(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    private static final Pattern GENERATED = Pattern.compile("(?i)"
            + "(j_idt\\d+|j_id_\\w+|ext-gen\\d+|ember\\d+|yui_\\d|react-select-\\d+|mui-\\d+|:r[0-9a-z]+:"
            + "|ng-tns-[a-z0-9-]+|_ngcontent-[a-z0-9-]+|^(sc|css|emotion|styled)-[a-z0-9]{5,}$"
            + "|^(el|ant|van)-[a-z0-9]{6,}$)");
    private static final Pattern MIXED_LONG = Pattern.compile("(?=.*\\d)(?=.*[a-z])(?=.*[A-Z])[A-Za-z0-9]{16,}");
    private static final Pattern DIGIT_RUN = Pattern.compile("\\d{4,}");
    private static final Pattern ALPHA_RUN = Pattern.compile("[A-Za-z]{4,}");
    private static final Pattern STABLE_WORD = Pattern.compile("[A-Za-z]{3,}");

    private SelectorAnalyzer() {
    }

    /**
     * 分析一条选择器。
     *
     * @throws IllegalArgumentException 空白或超过 {@value #MAX_LENGTH} 字符
     */
    public static SelectorFacts analyze(String selector) {
        Objects.requireNonNull(selector, "selector");
        String text = selector.trim().replaceAll("\\s+", " ");
        if (text.isEmpty()) {
            throw new IllegalArgumentException("selector must not be blank");
        }
        if (text.length() > MAX_LENGTH) {
            throw new IllegalArgumentException("selector exceeds " + MAX_LENGTH + " chars: " + text.length());
        }
        SelectorFacts.Kind kind = detectKind(text);
        String body = text.toLowerCase(Locale.ROOT).startsWith("xpath=") ? text.substring("xpath=".length()) : text;
        String target = kind == SelectorFacts.Kind.XPATH ? lastXpathStep(body) : lastCssSegment(body);
        String scope = kind == SelectorFacts.Kind.XPATH ? xpathScope(body) : cssScope(body);

        List<SelectorAnchor> anchors = new ArrayList<>();
        List<Hazard> hazards = new ArrayList<>();
        if (kind == SelectorFacts.Kind.XPATH) {
            parseXpath(target, anchors, hazards);
        } else {
            parseCss(target, anchors, hazards);
        }
        List<String> warnings = new ArrayList<>(hazards.size());
        for (Hazard hazard : hazards) {
            warnings.add(hazard.message());
        }
        return new SelectorFacts(text, kind, anchors, warnings, buildAdvice(hazards, anchors, scope));
    }

    /**
     * 两个选择器是否可能指向同一元素。
     *
     * <p>判定顺序：① 原文（忽略大小写）完全一致——即便全是随机值也算同一处，这样「录到的动态 id」
     * 仍能被标出来并给出稳定化建议；② 稳定锚点有交集。</p>
     */
    public static boolean intersects(SelectorFacts left, SelectorFacts right) {
        if (left == null || right == null) {
            return false;
        }
        if (left.raw().equalsIgnoreCase(right.raw())) {
            return true;
        }
        for (SelectorAnchor a : left.anchors()) {
            for (SelectorAnchor b : right.anchors()) {
                if (compatible(a, b)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 取值是否像是「每次渲染都会变」的随机值。
     *
     * <p>覆盖：UUID、时间戳、纯数字 ≥4 位、纯 hex ≥8 位、大小写数字混合长串、
     * 框架生成前缀（{@code j_idt}/{@code ext-gen}/{@code ember}/{@code :r1:}/{@code ng-tns-}/{@code _ngcontent-}）、
     * CSS-in-JS 类名（{@code sc-xxx}/{@code css-xxx}/{@code styled-xxx}）。</p>
     */
    public static boolean isVolatileValue(String value) {
        if (value == null) {
            return false;
        }
        String v = value.trim();
        if (v.isEmpty()) {
            return false;
        }
        if (UUID.matcher(v).matches() || GENERATED.matcher(v).find()) {
            return true;
        }
        if (PURE_DIGITS.matcher(v).matches()) {
            return v.length() >= 4;
        }
        if (HEX.matcher(v).matches() && v.length() >= 8) {
            return true;
        }
        if (MIXED_LONG.matcher(v).find()) {
            return true;
        }
        // 稳定前缀 + 长数字后缀（时间戳/自增序号）：profile-1234567890、list_item-20240101
        return DIGIT_RUN.matcher(v).find();
    }

    // ------------------------------------------------------------------
    // CSS
    // ------------------------------------------------------------------

    private static void parseCss(String segment, List<SelectorAnchor> anchors, List<Hazard> hazards) {
        // 标签名不参与锚点：任意 span/div 都可能同名，用它判「同一元素」会大面积误配；
        // 标签只在建议里作为「父级 + tag」的限定词出现。
        String withoutAttrs = segment.replaceAll("\\[[^\\]]*\\]", " ");
        Matcher id = CSS_ID.matcher(withoutAttrs);
        while (id.find()) {
            addAnchor(SelectorAnchor.id(id.group(1)), "id", id.group(1), anchors, hazards);
        }
        Matcher cssClass = CSS_CLASS.matcher(withoutAttrs);
        while (cssClass.find()) {
            addAnchor(SelectorAnchor.cssClass(cssClass.group(1)), "class", cssClass.group(1), anchors, hazards);
        }
        Matcher attr = CSS_ATTR.matcher(segment);
        while (attr.find()) {
            String name = attr.group(1);
            String operator = normalizeOperator(attr.group(2));
            String value = firstNonNull(attr.group(3), attr.group(4), attr.group(5));
            if (value == null) {
                continue;
            }
            addAnchor(SelectorAnchor.attr(name, operator, value), name, value, anchors, hazards);
        }
    }

    /** {@code ~=}（词匹配）与 {@code |=}（连字符前缀）都归一为包含/前缀语义。 */
    private static String normalizeOperator(String operator) {
        if ("~=".equals(operator)) {
            return SelectorAnchor.OP_CONTAINS;
        }
        if ("|=".equals(operator)) {
            return SelectorAnchor.OP_PREFIX;
        }
        return operator;
    }

    // ------------------------------------------------------------------
    // XPath
    // ------------------------------------------------------------------

    private static void parseXpath(String step, List<SelectorAnchor> anchors, List<Hazard> hazards) {
        Matcher attr = XPATH_ATTR.matcher(step);
        while (attr.find()) {
            addAnchor(SelectorAnchor.attr(attr.group(1), SelectorAnchor.OP_EQ, attr.group(2)),
                    attr.group(1), attr.group(2), anchors, hazards);
        }
        Matcher contains = XPATH_CONTAINS.matcher(step);
        while (contains.find()) {
            String operator = switch (contains.group(1)) {
                case "starts-with" -> SelectorAnchor.OP_PREFIX;
                case "ends-with" -> SelectorAnchor.OP_SUFFIX;
                default -> SelectorAnchor.OP_CONTAINS;
            };
            addAnchor(SelectorAnchor.attr(contains.group(2), operator, contains.group(3)),
                    contains.group(2), contains.group(3), anchors, hazards);
        }
        Matcher text = XPATH_TEXT.matcher(step);
        while (text.find()) {
            addAnchor(SelectorAnchor.text(SelectorAnchor.OP_EQ, text.group(1)),
                    "text", text.group(1), anchors, hazards);
        }
    }

    // ------------------------------------------------------------------
    // 锚点 / 建议
    // ------------------------------------------------------------------

    private static void addAnchor(SelectorAnchor anchor, String what, String value,
                                  List<SelectorAnchor> anchors, List<Hazard> hazards) {
        if (!isVolatileValue(value)) {
            anchors.add(anchor);
            return;
        }
        hazards.add(new Hazard("随机值 " + what + "=\"" + value + "\"",
                suggestFor(what, value)));
    }

    /** 随机值 → 可用写法：前缀 {@code ^=}、后缀 {@code $=}、包含 {@code *=}；留不出稳定片段则给空。 */
    static String suggestFor(String what, String value) {
        String attribute = "text".equals(what) ? "text" : what;
        String prefix = stableToken(value, true);
        if (!prefix.isEmpty() && prefix.length() < value.length()) {
            return "[" + attribute + "^='" + prefix + "']";
        }
        String suffix = stableToken(value, false);
        if (!suffix.isEmpty() && suffix.length() < value.length()) {
            return "[" + attribute + "$='" + suffix + "']";
        }
        Matcher run = ALPHA_RUN.matcher(value);
        String longest = "";
        while (run.find()) {
            if (run.group().length() > longest.length() && !isVolatileValue(run.group())) {
                longest = run.group();
            }
        }
        return longest.isEmpty() ? "" : "[" + attribute + "*='" + longest + "']";
    }

    /**
     * 取首/尾的稳定片段（按 {@code -_} 切分）。
     *
     * <p>只在<b>端部</b>取：{@code ember1234-input} 的首段是随机值 → 前缀为空、后缀 {@code input} 可用；
     * 若允许向内搜索，会把中间的 {@code input} 误当成前缀。</p>
     */
    private static String stableToken(String value, boolean head) {
        String[] parts = value.split("[-_]+");
        String edge = head ? parts[0] : parts[parts.length - 1];
        // 只认纯字母片段：j_idt23 的 "idt23" 之类切出来的残段不是稳定语义
        boolean stable = STABLE_WORD.matcher(edge).matches() && !isVolatileValue(edge);
        return stable ? edge : "";
    }

    private static String buildAdvice(List<Hazard> hazards, List<SelectorAnchor> anchors, String scope) {
        if (hazards.isEmpty()) {
            return "";
        }
        Set<String> parts = new LinkedHashSet<>();
        for (Hazard hazard : hazards) {
            if (!hazard.suggestion().isEmpty()) {
                parts.add(hazard.suggestion());
            }
        }
        StringBuilder sb = new StringBuilder();
        sb.append("含随机值，建议改用 ");
        if (parts.isEmpty()) {
            sb.append("父级稳定属性 + 元素 tag/role 限定");
            if (!scope.isEmpty()) {
                sb.append("（父级：").append(scope).append("）");
            }
        } else {
            sb.append(String.join(" 或 ", parts));
            if (anchors.size() > 1) {
                sb.append("；也可多属性组合 ").append(anchors);
            }
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------
    // 结构切分 / 判定
    // ------------------------------------------------------------------

    private static SelectorFacts.Kind detectKind(String text) {
        String lower = text.toLowerCase(Locale.ROOT);
        if (lower.startsWith("xpath=") || text.startsWith("/") || text.startsWith("(") || text.startsWith("./")) {
            return SelectorFacts.Kind.XPATH;
        }
        return SelectorFacts.Kind.CSS;
    }

    /** CSS 最后一段（逗号并列取第一段；忽略 {@code []} 与引号内的分隔符）。 */
    private static String lastCssSegment(String text) {
        List<String> segments = splitTopLevel(text, ',');
        String first = segments.isEmpty() ? text : segments.get(0);
        int cut = lastTopLevelIndex(first, new char[]{' ', '>'});
        return cut < 0 ? first.trim() : first.substring(cut + 1).trim();
    }

    private static String cssScope(String text) {
        List<String> segments = splitTopLevel(text, ',');
        String first = segments.isEmpty() ? text : segments.get(0);
        int cut = lastTopLevelIndex(first, new char[]{' ', '>'});
        return cut <= 0 ? "" : first.substring(0, cut).trim();
    }

    private static String lastXpathStep(String text) {
        int cut = text.lastIndexOf('/');
        return cut < 0 ? text.trim() : text.substring(cut + 1).trim();
    }

    private static String xpathScope(String text) {
        int cut = text.lastIndexOf('/');
        return cut <= 0 ? "" : text.substring(0, cut);
    }

    private static List<String> splitTopLevel(String text, char separator) {
        List<String> parts = new ArrayList<>();
        int depth = 0;
        char quote = 0;
        int start = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quote != 0) {
                if (c == quote) {
                    quote = 0;
                }
                continue;
            }
            if (c == '\'' || c == '"') {
                quote = c;
            } else if (c == '[' || c == '(') {
                depth++;
            } else if (c == ']' || c == ')') {
                depth = Math.max(0, depth - 1);
            } else if (c == separator && depth == 0) {
                parts.add(text.substring(start, i).trim());
                start = i + 1;
            }
        }
        parts.add(text.substring(start).trim());
        return parts;
    }

    private static int lastTopLevelIndex(String text, char[] separators) {
        int depth = 0;
        char quote = 0;
        int found = -1;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quote != 0) {
                if (c == quote) {
                    quote = 0;
                }
                continue;
            }
            if (c == '\'' || c == '"') {
                quote = c;
            } else if (c == '[' || c == '(') {
                depth++;
            } else if (c == ']' || c == ')') {
                depth = Math.max(0, depth - 1);
            } else if (depth == 0) {
                for (char separator : separators) {
                    if (c == separator) {
                        found = i;
                    }
                }
            }
        }
        return found;
    }

    private static String firstNonNull(String... values) {
        for (String value : values) {
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------
    // 锚点兼容判定
    // ------------------------------------------------------------------

    private static boolean compatible(SelectorAnchor left, SelectorAnchor right) {
        if (!left.sameSlot(right)) {
            return false;
        }
        return valuesCompatible(left, right);
    }

    private static boolean valuesCompatible(SelectorAnchor left, SelectorAnchor right) {
        boolean leftEq = SelectorAnchor.OP_EQ.equals(left.operator());
        boolean rightEq = SelectorAnchor.OP_EQ.equals(right.operator());
        if (leftEq && rightEq) {
            return left.value().equalsIgnoreCase(right.value());
        }
        if (leftEq) {
            return matchesOperator(left.value().toLowerCase(Locale.ROOT), right);
        }
        if (rightEq) {
            return matchesOperator(right.value().toLowerCase(Locale.ROOT), left);
        }
        String a = left.value().toLowerCase(Locale.ROOT);
        String b = right.value().toLowerCase(Locale.ROOT);
        return a.contains(b) || b.contains(a);
    }

    private static boolean matchesOperator(String value, SelectorAnchor anchor) {
        String expected = anchor.value().toLowerCase(Locale.ROOT);
        return switch (anchor.operator()) {
            case SelectorAnchor.OP_PREFIX -> value.startsWith(expected);
            case SelectorAnchor.OP_SUFFIX -> value.endsWith(expected);
            default -> value.contains(expected);
        };
    }

    /** 随机值告警（消息 + 建议片段）。 */
    private record Hazard(String message, String suggestion) {
    }
}
