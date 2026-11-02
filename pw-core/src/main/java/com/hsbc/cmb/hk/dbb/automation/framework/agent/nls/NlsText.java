package com.hsbc.cmb.hk.dbb.automation.framework.agent.nls;

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
 * 已委托到本类，保证"codegen 拾取"与"Agent 资产/定位"看到完全一致的文本形态。</p>
 *
 * <p>无浏览器、无 LLM、无状态，纯函数。</p>
 */
public final class NlsText {

    private NlsText() {
    }

    /**
     * 把 nls 原始值归一化为「页面可见文本」：归一化空白（{@code \r\n→\n}、{@code &nbsp;→空格}、折叠）、
     * 剥离 HTML 标签（{@code <br>} 转空格、其余标签删为空格）、解码常见实体，最后 trim。
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
        return escapeKeepingPlaceholders(stripHtmlAndNormalize(value));
    }

    /** 把 nls 值编译为正则（{@link Pattern#DOTALL}，使 {@code .} 可跨换行）。 */
    public static Pattern pattern(String value) {
        String src = templateRegexSource(value);
        return src.isEmpty()
                ? Pattern.compile(Pattern.quote(value == null ? "" : value), Pattern.DOTALL)
                : Pattern.compile(src, Pattern.DOTALL);
    }

    /** 归一化 + 剥 HTML + 解码实体（不 trim，供正则编译复用）。 */
    private static String stripHtmlAndNormalize(String value) {
        String t = value.replace("\r\n", "\n").replace('\u00A0', ' ');
        t = t.replaceAll("<br\\s*/?>", " ").replaceAll("<[^>]+>", " ");
        t = decodeEntities(t);
        return t.replaceAll("\\s+", " ");
    }

    private static String decodeEntities(String s) {
        return s.replace("&nbsp;", " ")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&apos;", "'")
                .replace("&amp;", "&")
                .replace("&copy;", "©");
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
}
