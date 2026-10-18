package com.hsbc.cmb.hk.dbb.automation.framework.report.event;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 脱敏闸门——证据入总线的唯一入口（R3）。
 *
 * <p>三条不变式的第一条：<b>证据入 bus 必经 ScrubGate</b>，调用方无法绕过；
 * 写盘与推送因此共用同一份脱敏后数据，单点修补、两个出口一致。
 *
 * <p>规则（方案 v3 §6）：
 * <ol>
 *   <li>URL query 敏感参数名（token/sig/signature/key/secret/password/pwd/code/state）值 → {@code ***}；</li>
 *   <li>敏感头名（Authorization/Cookie/Set-Cookie/X-Api-Key/X-CSRF-Token/Proxy-Authorization）值 → {@code ***}；</li>
 *   <li>模式匹配：{@code Bearer <token>}、JWT（{@code eyJ…}）、{@code sk-…}、长 base64 串 → {@code [SCRUBBED]}；</li>
 *   <li>行长 ≤ maxLineBytes（UTF-8 边界安全截断 + 标记）；</li>
 *   <li>body 永不内联：sha256 摘要 + 脱敏后 256B 预览。</li>
 * </ol>
 *
 * <p>规则集可配置扩展（{@code pw.report.scrub.extra-patterns}，逗号分隔正则）；
 * 漏网新模式进风险表跟踪。
 */
public final class ScrubGate {

    private static final String SCRUBBED = "[SCRUBBED]";

    /** query 参数名敏感词（大小写不敏感，contains 匹配）。 */
    private static final List<String> SENSITIVE_PARAM_TOKENS = List.of(
            "token", "sig", "signature", "key", "secret", "password", "pwd", "code", "state");

    /** 敏感头名（完整匹配，大小写不敏感）。 */
    private static final List<String> SENSITIVE_HEADERS = List.of(
            "authorization", "proxy-authorization", "cookie", "set-cookie",
            "x-api-key", "x-csrf-token", "x-auth-token", "x-xsrf-token");

    private static final Pattern URL_PATTERN = Pattern.compile("https?://[^\\s\"'<>\\\\]+");
    /** 相对路径 query（{@code GET /api/users?token=x}）——无 scheme 的请求行同样要脱敏。 */
    private static final Pattern RELATIVE_URL_PATTERN =
            Pattern.compile("/[^\\s\"'<>\\\\?]*\\?[^\\s\"'<>\\\\]+");
    /** 敏感头：值吃到最后（行尾或逗号分隔的下一个头）——只吃第一个 token 会漏后半截。 */
    private static final Pattern HEADER_PATTERN = Pattern.compile(
            "(?i)\\b(authorization|proxy-authorization|cookie|set-cookie|x-api-key|x-csrf-token|x-auth-token|x-xsrf-token)"
                    + "\\s*[:=]\\s*(\"[^\"]*\"|'[^']*'|[^,\\n]+)");
    private static final Pattern BEARER_PATTERN = Pattern.compile("(?i)\\bbearer\\s+[A-Za-z0-9\\-._~+/]+=*");
    private static final Pattern JWT_PATTERN = Pattern.compile("\\beyJ[A-Za-z0-9_-]{4,}\\.[A-Za-z0-9_-]{4,}\\.[A-Za-z0-9_-]+");
    private static final Pattern SK_KEY_PATTERN = Pattern.compile("\\bsk-[A-Za-z0-9]{16,}\\b");
    /**
     * 长 base64 串（≥40 字符，含常见填充）——签名/密钥的常见泄漏形态。
     * <p>白名单：纯小写 hex（traceId/requestId，0-9a-f）和纯数字长串不打码，
     * 避免排查时丢信息；真正的密钥通常含大写字母或 +/= 符号。
     */
    private static final Pattern BASE64_PATTERN = Pattern.compile(
            "\\b(?!(?:[0-9a-f]{40,}|[0-9]{40,})\\b)[A-Za-z0-9+/]{40,}={0,2}\\b");

    private final List<Pattern> extraPatterns;
    private final int maxLineBytes;
    private final int previewBytes;

    public ScrubGate(List<Pattern> extraPatterns, int maxLineBytes, int previewBytes) {
        this.extraPatterns = extraPatterns == null ? List.of() : List.copyOf(extraPatterns);
        this.maxLineBytes = Math.max(64, maxLineBytes);
        this.previewBytes = Math.max(32, previewBytes);
    }

    /** 默认配置（2048B / 256B 预览）。 */
    public static ScrubGate defaults() {
        return new ScrubGate(List.of(), 2048, 256);
    }

    /**
     * 脱敏 + 截断单行文本。null 安全（→ 空串）。
     *
     * <p>顺序固定：URL query → 敏感头 → 模式匹配（Bearer/JWT/sk-/base64/扩展）→ 截断。
     * URL 先行保证 query 里的 token 先被参数名规则吃掉，避免被 base64 规则误伤成 [SCRUBBED]
     * （两种结果都安全，但参数名规则信息量更高）。
     */
    public String scrubLine(String raw) {
        if (raw == null || raw.isEmpty()) {
            return "";
        }
        String s = scrubUrls(raw);
        s = scrubHeaders(s);
        s = applyPatterns(s);
        return truncateUtf8(s, maxLineBytes);
    }

    /** 堆栈脱敏——断言消息里也可能带 token/URL，与 scrubLine 同规则同上限。 */
    public String scrubStackTrace(String stack) {
        return scrubLine(stack);
    }

    /** body 摘要：永不内联，hash + 脱敏后预览。 */
    public BodyDigest digestBody(byte[] body) {
        if (body == null || body.length == 0) {
            return BodyDigest.EMPTY;
        }
        String hash = sha256(body);
        String preview = truncateUtf8(scrubLine(new String(body, StandardCharsets.UTF_8)), previewBytes);
        return new BodyDigest(hash, preview);
    }

    // ---------------------------------------------------------------- internals

    private String scrubUrls(String s) {
        Matcher m = URL_PATTERN.matcher(s);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            m.appendReplacement(sb, Matcher.quoteReplacement(scrubUrl(m.group())));
        }
        m.appendTail(sb);
        // 相对路径 query 二次过一遍（对已脱敏的绝对 URL 幂等）
        Matcher rm = RELATIVE_URL_PATTERN.matcher(sb);
        StringBuilder sb2 = new StringBuilder();
        while (rm.find()) {
            rm.appendReplacement(sb2, Matcher.quoteReplacement(scrubUrl(rm.group())));
        }
        rm.appendTail(sb2);
        return sb2.toString();
    }

    private String scrubUrl(String url) {
        int q = url.indexOf('?');
        if (q < 0 || q == url.length() - 1) {
            return url;
        }
        String head = url.substring(0, q + 1);
        String query = url.substring(q + 1);
        StringBuilder out = new StringBuilder(head);
        boolean first = true;
        for (String pair : query.split("&", -1)) {
            if (!first) {
                out.append('&');
            }
            first = false;
            int eq = pair.indexOf('=');
            if (eq < 0) {
                out.append(pair);
                continue;
            }
            String k = pair.substring(0, eq);
            String v = pair.substring(eq + 1);
            out.append(k).append('=');
            out.append(isSensitiveParam(k) ? "***" : v);
        }
        return out.toString();
    }

    private boolean isSensitiveParam(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        for (String token : SENSITIVE_PARAM_TOKENS) {
            if (n.contains(token)) {
                return true;
            }
        }
        return false;
    }

    private String scrubHeaders(String s) {
        Matcher m = HEADER_PATTERN.matcher(s);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String name = m.group(1);
            String replacement = name + ": " + "***";
            m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private String applyPatterns(String s) {
        String r = BEARER_PATTERN.matcher(s).replaceAll(SCRUBBED);
        r = JWT_PATTERN.matcher(r).replaceAll(SCRUBBED);
        r = SK_KEY_PATTERN.matcher(r).replaceAll(SCRUBBED);
        r = BASE64_PATTERN.matcher(r).replaceAll(SCRUBBED);
        for (Pattern extra : extraPatterns) {
            r = extra.matcher(r).replaceAll(SCRUBBED);
        }
        return r;
    }

    /**
     * UTF-8 边界安全截断：不把多字节字符切成乱码；超限时追加 {@code …[truncated]} 标记。
     */
    static String truncateUtf8(String s, int maxBytes) {
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= maxBytes) {
            return s;
        }
        int limit = maxBytes - MARKER_BYTES.length;
        if (limit <= 0) {
            limit = maxBytes;
        }
        int cut = limit;
        while (cut > 0 && (bytes[cut] & 0xC0) == 0x80) {
            cut--; // 回退到字符边界（不落在 continuation byte 上）
        }
        String out = new String(bytes, 0, cut, StandardCharsets.UTF_8);
        return limit == maxBytes ? out : out + MARKER;
    }

    private static final String MARKER = "…[truncated]";
    private static final byte[] MARKER_BYTES = MARKER.getBytes(StandardCharsets.UTF_8);

    private static String sha256(byte[] body) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(body);
            StringBuilder sb = new StringBuilder("sha256:");
            for (int i = 0; i < 8; i++) {
                sb.append(Character.forDigit((digest[i] >> 4) & 0xF, 16));
                sb.append(Character.forDigit(digest[i] & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 是 JDK 强制算法；真到这里说明 JDK 被裁剪，退化为长度摘要
            return "sha256:unavailable(len=" + body.length + ")";
        }
    }
}
