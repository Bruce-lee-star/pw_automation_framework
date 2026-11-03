package com.hsbc.cmb.hk.dbb.automation.framework.agent.nls;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * NLS 文案的「页面可见文本」归一化（设计 §4.7）。
 *
 * <p><b>为什么必须归一化</b>：nls json 的值里常内嵌 {@code <b>/<img>/&nbsp;} 等标签与实体
 * （如 {@code "保安編碼器&nbsp; <img ...>"}），而浏览器渲染后元素的<b>可访问名/可见文本</b>
 * 不含标签、实体已解码、空白已折叠（{@code "保安編碼器"}）。直接用原始值去匹配必然失败，
 * 因此「nls 值 → 定位用的 name」「可访问名 → 反查 key」两侧都必须先归一化到同一形态。</p>
 *
 * <p><b>与 web 侧 {@code NLSUtils} 的关系</b>：本类是同一套归一化语义在 pw-core 的落地
 * （pw-core 不能反向依赖 pw-web-ui，而 agent 契约层需要它）。{@code NLSUtils.visibleText}
 * 与 {@code NLSUtils.templateRegexSource} 均已委托到本类，保证"codegen 拾取"与"Agent 资产/定位"
 * 看到完全一致的文本形态。</p>
 *
 * <p><b>HTML 实体处理</b>：{@link #decodeEntities(String)} 覆盖<b>全部</b>常用实体——
 * 命名实体（{@code &lt; &gt; &amp; &quot; &copy; &reg; &trade; &eacute; &mdash; &hellip;} 等，
 * 含重音拉丁字母、货币、箭头、数学、希腊）、数字实体（{@code &#169;} 十进制、
 * {@code &#x1F600;} 十六进制，可表示任意码点）。解码顺序为<b>先解码实体、再剥标签</b>，
 * 否则 {@code &lt;b&gt;x&lt;/b&gt;} 解码成 {@code <b>x</b>} 时去标签已执行过，字面尖括号会残留。</p>
 *
 * <p>无浏览器、无 LLM、无状态，纯函数。</p>
 */
public final class NlsText {

    private NlsText() {
    }

    /**
     * 把 nls 原始值归一化为「页面可见文本」：归一化空白（{@code \r\n→\n}、{@code &nbsp;/&#160;→空格}、折叠）、
     * 先解码 HTML 实体、再剥离 HTML 标签（{@code <br>} 转空格、其余标签删为空格），最后 trim。
     *
     * @param value nls 原始值（可为 null）
     * @return 归一化后的可见文本；null 视为 {@code ""}
     */
    public static String visibleText(String value) {
        if (value == null) {
            return "";
        }
        return stripHtmlAndNormalize(value).trim();
    }

    /**
     * 是否为含模板变量的 nls 值（形如 {@code {{deviceModel}}}）。
     *
     * <p>这类值运行时由页面注入真实值，可见文本与 nls 原始值不同，只能按正则匹配，
     * 不能按字符串相等定位/反查。
     */
    public static boolean isTemplate(String value) {
        return value != null && value.contains("{{");
    }

    /**
     * 把 nls 值编译为「跨语言通用」的正则：归一化 → 转义元字符 → 把 {@code {{var}}} 换成 {@code (.*?)}。
     *
     * <p>占位符名在各语言里保持一致（如 {@code {{deviceModel}}}），故任意语言编译出的正则都能匹配页面实际值。
     */
    public static String templateRegexSource(String value) {
        if (value == null) {
            return "";
        }
        // 归一化后 trim：nls 值常带首尾空白（如 "...All rights reserved. "），而页面可访问名已被浏览器 trim，
        // 不 trim 会让编译出的正则要求"尾部空格"导致反查/定位失败（footer 的 Copyright 模板即典型场景）。
        return escapeKeepingPlaceholders(stripHtmlAndNormalize(value).trim());
    }

    /** 把 nls 值编译为正则（{@link Pattern#DOTALL}，使 {@code .} 可跨换行）。 */
    public static Pattern pattern(String value) {
        String src = templateRegexSource(value);
        return src.isEmpty()
                ? Pattern.compile(Pattern.quote(value == null ? "" : value), Pattern.DOTALL)
                : Pattern.compile(src, Pattern.DOTALL);
    }

    /**
     * 解码 HTML 实体（命名实体 + 数字实体），覆盖 nls 文件里出现的<b>全部</b>常见形式。
     *
     * <p>支持：
     * <ul>
     *   <li>命名实体：{@code &amp; &lt; &gt; &quot; &apos; &nbsp; &copy; &reg; &trade; &eacute; &mdash; &hellip;} 等
     *       （完整表见 {@link #NAMED_ENTITIES}：重音拉丁字母、货币、箭头、数学、希腊等）。</li>
     *   <li>数字实体：{@code &#169;}（十进制）、{@code &#x1F600;}（十六进制），可表示任意 Unicode 码点。</li>
     * </ul>
     *
     * <p><b>边界</b>：单趟左到右解码，与浏览器一致——{@code &amp;amp;} 解码为 {@code &amp;}（字面），
     * 不会被过度解成 {@code &}；不成对的 {@code &}（如 {@code a & b} 无分号）原样保留。
     * 实体名区分大小写（{@code &copy;} 而非 {@code &Copy;}），与 HTML 规范一致。</p>
     *
     * @param s 可能含实体的字符串（可为 null）
     * @return 解码后的字符串
     */
    public static String decodeEntities(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(s.length());
        int i = 0;
        int n = s.length();
        while (i < n) {
            char c = s.charAt(i);
            if (c == '&') {
                int semi = s.indexOf(';', i + 1);
                if (semi > i + 1) {
                    String token = s.substring(i + 1, semi);
                    // 数字实体：&#123;（十进制）或 &#x1F600;（十六进制）
                    if (token.length() > 1 && token.charAt(0) == '#') {
                        int codePoint = parseNumericCharRef(token);
                        if (codePoint >= 0) {
                            sb.appendCodePoint(codePoint);
                            i = semi + 1;
                            continue;
                        }
                    } else {
                        String replacement = NAMED_ENTITIES.get(token);
                        if (replacement != null) {
                            sb.append(replacement);
                            i = semi + 1;
                            continue;
                        }
                    }
                }
                // 不成对的 & 或未知实体：原样保留 &
                sb.append('&');
                i++;
            } else {
                sb.append(c);
                i++;
            }
        }
        return sb.toString();
    }

    /** 归一化 + 解码实体 + 剥 HTML（不 trim，供正则编译复用）。 */
    private static String stripHtmlAndNormalize(String value) {
        String t = value.replace("\r\n", "\n");
        // 先解码实体：&lt;b&gt; 要先变回 <b> 才能被剥掉；&#160; 解码成 nbsp 字符也要归一为空格
        t = decodeEntities(t);
        // 实体解码后可能产生字面 nbsp（&#160;）或原文就有 nbsp，统一成普通空格
        t = t.replace('\u00A0', ' ');
        // 再剥标签：<br> 转空格，其余标签删为空格
        t = t.replaceAll("<br\\s*/?>", " ").replaceAll("<[^>]+>", " ");
        return t.replaceAll("\\s+", " ");
    }

    private static int parseNumericCharRef(String token) {
        String digits = token.substring(1);
        try {
            if (digits.length() > 1 && (digits.charAt(0) == 'x' || digits.charAt(0) == 'X')) {
                return Integer.parseInt(digits.substring(1), 16);
            }
            return Integer.parseInt(digits, 10);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static String escapeKeepingPlaceholders(String t) {
        StringBuilder sb = new StringBuilder();
        int i = 0;
        int n = t.length();
        while (i < n) {
            if (t.startsWith("{{", i)) {
                int end = t.indexOf("}}", i);
                if (end < 0) {
                    sb.append(escapeLiteral(t.substring(i)));
                    break;
                }
                sb.append("(.*?)");
                i = end + 2;
            } else {
                int next = t.indexOf("{{", i);
                if (next < 0) {
                    next = n;
                }
                sb.append(escapeLiteral(t.substring(i, next)));
                i = next;
            }
        }
        return sb.toString();
    }

    private static String escapeLiteral(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if ("[](){}.*+?^$|\\".indexOf(c) >= 0) {
                sb.append('\\').append(c);
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /**
     * 命名实体表（HTML 4.01 + 常用 HTML5）。键区分大小写。
     * 含：基础/XML、标点排版、货币、关系箭头、数学、希腊、重音拉丁字母（大小写）。
     */
    private static final Map<String, String> NAMED_ENTITIES = new HashMap<>();
    static {
        // —— 基础 / XML ——
        NAMED_ENTITIES.put("amp", "&");
        NAMED_ENTITIES.put("lt", "<");
        NAMED_ENTITIES.put("gt", ">");
        NAMED_ENTITIES.put("quot", "\"");
        NAMED_ENTITIES.put("apos", "'");
        NAMED_ENTITIES.put("nbsp", " ");
        // —— 符号 ——
        NAMED_ENTITIES.put("copy", "©");
        NAMED_ENTITIES.put("reg", "®");
        NAMED_ENTITIES.put("trade", "™");
        // —— 标点 / 排版 ——
        NAMED_ENTITIES.put("hellip", "…");
        NAMED_ENTITIES.put("mdash", "—");
        NAMED_ENTITIES.put("ndash", "–");
        NAMED_ENTITIES.put("middot", "·");
        NAMED_ENTITIES.put("bull", "•");
        NAMED_ENTITIES.put("dagger", "†");
        NAMED_ENTITIES.put("Dagger", "‡");
        NAMED_ENTITIES.put("para", "¶");
        NAMED_ENTITIES.put("sect", "§");
        NAMED_ENTITIES.put("laquo", "«");
        NAMED_ENTITIES.put("raquo", "»");
        NAMED_ENTITIES.put("lsaquo", "‹");
        NAMED_ENTITIES.put("rsaquo", "›");
        NAMED_ENTITIES.put("ldquo", "\"");
        NAMED_ENTITIES.put("rdquo", "\"");
        NAMED_ENTITIES.put("lsquo", "'");
        NAMED_ENTITIES.put("rsquo", "'");
        NAMED_ENTITIES.put("bdquo", "„");
        NAMED_ENTITIES.put("sbquo", "‚");
        NAMED_ENTITIES.put("prime", "′");
        NAMED_ENTITIES.put("Prime", "″");
        NAMED_ENTITIES.put("deg", "°");
        NAMED_ENTITIES.put("plusmn", "±");
        NAMED_ENTITIES.put("times", "×");
        NAMED_ENTITIES.put("divide", "÷");
        NAMED_ENTITIES.put("frac12", "½");
        NAMED_ENTITIES.put("frac14", "¼");
        NAMED_ENTITIES.put("frac34", "¾");
        NAMED_ENTITIES.put("sup1", "¹");
        NAMED_ENTITIES.put("sup2", "²");
        NAMED_ENTITIES.put("sup3", "³");
        NAMED_ENTITIES.put("ordm", "º");
        NAMED_ENTITIES.put("ordf", "ª");
        NAMED_ENTITIES.put("micro", "µ");
        NAMED_ENTITIES.put("permil", "‰");
        NAMED_ENTITIES.put("acute", "´");
        NAMED_ENTITIES.put("cedil", "¸");
        NAMED_ENTITIES.put("shy", "­");
        NAMED_ENTITIES.put("zwj", "‍");
        NAMED_ENTITIES.put("zwnj", "‌");
        // —— 货币 ——
        NAMED_ENTITIES.put("cent", "¢");
        NAMED_ENTITIES.put("pound", "£");
        NAMED_ENTITIES.put("yen", "¥");
        NAMED_ENTITIES.put("euro", "€");
        NAMED_ENTITIES.put("curren", "¤");
        // —— 关系 / 箭头 ——
        NAMED_ENTITIES.put("ne", "≠");
        NAMED_ENTITIES.put("le", "≤");
        NAMED_ENTITIES.put("ge", "≥");
        NAMED_ENTITIES.put("equiv", "≡");
        NAMED_ENTITIES.put("cong", "≅");
        NAMED_ENTITIES.put("asymp", "≈");
        NAMED_ENTITIES.put("sim", "∼");
        NAMED_ENTITIES.put("infin", "∞");
        NAMED_ENTITIES.put("prop", "∝");
        NAMED_ENTITIES.put("rarr", "→");
        NAMED_ENTITIES.put("larr", "←");
        NAMED_ENTITIES.put("uarr", "↑");
        NAMED_ENTITIES.put("darr", "↓");
        NAMED_ENTITIES.put("harr", "↔");
        NAMED_ENTITIES.put("rArr", "⇒");
        NAMED_ENTITIES.put("lArr", "⇐");
        NAMED_ENTITIES.put("hArr", "⇔");
        // —— 数学符号 ——
        NAMED_ENTITIES.put("sum", "∑");
        NAMED_ENTITIES.put("prod", "∏");
        NAMED_ENTITIES.put("int", "∫");
        NAMED_ENTITIES.put("radic", "√");
        NAMED_ENTITIES.put("cap", "∩");
        NAMED_ENTITIES.put("cup", "∪");
        NAMED_ENTITIES.put("sub", "⊂");
        NAMED_ENTITIES.put("sup", "⊃");
        NAMED_ENTITIES.put("nsub", "⊄");
        NAMED_ENTITIES.put("notin", "∉");
        NAMED_ENTITIES.put("otimes", "⊗");
        NAMED_ENTITIES.put("oplus", "⊕");
        NAMED_ENTITIES.put("empty", "∅");
        NAMED_ENTITIES.put("lowast", "∗");
        NAMED_ENTITIES.put("loz", "◊");
        NAMED_ENTITIES.put("diams", "♦");
        NAMED_ENTITIES.put("hearts", "♥");
        NAMED_ENTITIES.put("clubs", "♣");
        NAMED_ENTITIES.put("spades", "♠");
        // —— 希腊字母 ——
        NAMED_ENTITIES.put("alpha", "α");
        NAMED_ENTITIES.put("beta", "β");
        NAMED_ENTITIES.put("gamma", "γ");
        NAMED_ENTITIES.put("delta", "δ");
        NAMED_ENTITIES.put("epsilon", "ε");
        NAMED_ENTITIES.put("zeta", "ζ");
        NAMED_ENTITIES.put("eta", "η");
        NAMED_ENTITIES.put("theta", "θ");
        NAMED_ENTITIES.put("iota", "ι");
        NAMED_ENTITIES.put("kappa", "κ");
        NAMED_ENTITIES.put("lambda", "λ");
        NAMED_ENTITIES.put("mu", "μ");
        NAMED_ENTITIES.put("nu", "ν");
        NAMED_ENTITIES.put("xi", "ξ");
        NAMED_ENTITIES.put("omicron", "ο");
        NAMED_ENTITIES.put("pi", "π");
        NAMED_ENTITIES.put("rho", "ρ");
        NAMED_ENTITIES.put("sigma", "σ");
        NAMED_ENTITIES.put("tau", "τ");
        NAMED_ENTITIES.put("upsilon", "υ");
        NAMED_ENTITIES.put("phi", "φ");
        NAMED_ENTITIES.put("chi", "χ");
        NAMED_ENTITIES.put("psi", "ψ");
        NAMED_ENTITIES.put("omega", "ω");
        NAMED_ENTITIES.put("Gamma", "Γ");
        NAMED_ENTITIES.put("Delta", "Δ");
        NAMED_ENTITIES.put("Theta", "Θ");
        NAMED_ENTITIES.put("Lambda", "Λ");
        NAMED_ENTITIES.put("Pi", "Π");
        NAMED_ENTITIES.put("Sigma", "Σ");
        NAMED_ENTITIES.put("Phi", "Φ");
        NAMED_ENTITIES.put("Psi", "Ψ");
        NAMED_ENTITIES.put("Omega", "Ω");
        // —— 重音拉丁字母（小写） ——
        NAMED_ENTITIES.put("agrave", "à");
        NAMED_ENTITIES.put("aacute", "á");
        NAMED_ENTITIES.put("acirc", "â");
        NAMED_ENTITIES.put("atilde", "ã");
        NAMED_ENTITIES.put("auml", "ä");
        NAMED_ENTITIES.put("aring", "å");
        NAMED_ENTITIES.put("aelig", "æ");
        NAMED_ENTITIES.put("ccedil", "ç");
        NAMED_ENTITIES.put("egrave", "è");
        NAMED_ENTITIES.put("eacute", "é");
        NAMED_ENTITIES.put("ecirc", "ê");
        NAMED_ENTITIES.put("euml", "ë");
        NAMED_ENTITIES.put("igrave", "ì");
        NAMED_ENTITIES.put("iacute", "í");
        NAMED_ENTITIES.put("icirc", "î");
        NAMED_ENTITIES.put("iuml", "ï");
        NAMED_ENTITIES.put("ntilde", "ñ");
        NAMED_ENTITIES.put("ograve", "ò");
        NAMED_ENTITIES.put("oacute", "ó");
        NAMED_ENTITIES.put("ocirc", "ô");
        NAMED_ENTITIES.put("otilde", "õ");
        NAMED_ENTITIES.put("ouml", "ö");
        NAMED_ENTITIES.put("oslash", "ø");
        NAMED_ENTITIES.put("oelig", "œ");
        NAMED_ENTITIES.put("szlig", "ß");
        NAMED_ENTITIES.put("ugrave", "ù");
        NAMED_ENTITIES.put("uacute", "ú");
        NAMED_ENTITIES.put("ucirc", "û");
        NAMED_ENTITIES.put("uuml", "ü");
        NAMED_ENTITIES.put("yacute", "ý");
        NAMED_ENTITIES.put("yuml", "ÿ");
        // —— 重音拉丁字母（大写） ——
        NAMED_ENTITIES.put("Agrave", "À");
        NAMED_ENTITIES.put("Aacute", "Á");
        NAMED_ENTITIES.put("Acirc", "Â");
        NAMED_ENTITIES.put("Atilde", "Ã");
        NAMED_ENTITIES.put("Auml", "Ä");
        NAMED_ENTITIES.put("Aring", "Å");
        NAMED_ENTITIES.put("AElig", "Æ");
        NAMED_ENTITIES.put("Ccedil", "Ç");
        NAMED_ENTITIES.put("Egrave", "È");
        NAMED_ENTITIES.put("Eacute", "É");
        NAMED_ENTITIES.put("Ecirc", "Ê");
        NAMED_ENTITIES.put("Euml", "Ë");
        NAMED_ENTITIES.put("Igrave", "Ì");
        NAMED_ENTITIES.put("Iacute", "Í");
        NAMED_ENTITIES.put("Icirc", "Î");
        NAMED_ENTITIES.put("Iuml", "Ï");
        NAMED_ENTITIES.put("Ntilde", "Ñ");
        NAMED_ENTITIES.put("Ograve", "Ò");
        NAMED_ENTITIES.put("Oacute", "Ó");
        NAMED_ENTITIES.put("Ocirc", "Ô");
        NAMED_ENTITIES.put("Otilde", "Õ");
        NAMED_ENTITIES.put("Ouml", "Ö");
        NAMED_ENTITIES.put("Oslash", "Ø");
        NAMED_ENTITIES.put("OElig", "Œ");
        NAMED_ENTITIES.put("Ugrave", "Ù");
        NAMED_ENTITIES.put("Uacute", "Ú");
        NAMED_ENTITIES.put("Ucirc", "Û");
        NAMED_ENTITIES.put("Uuml", "Ü");
        NAMED_ENTITIES.put("Yacute", "Ý");
    }
}
