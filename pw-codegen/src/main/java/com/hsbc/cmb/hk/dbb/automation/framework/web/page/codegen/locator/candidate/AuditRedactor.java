package com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen.locator.candidate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 审计报告<b>脱敏投影</b>：把一条或多条候选的敏感字段按 {@link AuditExposure} 级别改写。
 *
 * <p>设计原则（R1·R7 数据最小化）：
 * <ul>
 *   <li><b>审批人/操作者</b>：SANITIZED 下做确定性假名（同一人在同一份报告里始终是同一个 {@code APPROVER-xxxx}，
 *       所以仍能看出"同一个人既提出又审批"，但认不出是谁）；</li>
 *   <li><b>origin</b>：SANITIZED 下只留 {@code scheme://host}，剥掉路径/查询/片段——它们可能带 token 或会话 id；</li>
 *   <li><b>证据细节</b>：INTERNAL 及以上就把 Windows/Unix 绝对路径收敛为文件名，避免带出用户名与目录结构；</li>
 *   <li><b>note / 证据引用</b>：SANITIZED 下直接隐去（"(redacted)"）；</li>
 *   <li><b>事件链自由文本</b>：SANITIZED 下丢弃，只保留状态名——事件 detail 里常嵌 origin/审批人，留着就破功。</li>
 * </ul>
 *
 * <p>本类是<b>无状态投影</b>（除假名映射外不保存业务数据），可重复调用、结果幂等。
 */
public final class AuditRedactor {

    private final AuditExposure exposure;
    private final Map<String, String> pseudonyms = new LinkedHashMap<>();
    private int counter;

    public AuditRedactor(AuditExposure exposure) {
        this.exposure = Objects.requireNonNull(exposure, "exposure");
    }

    public AuditExposure exposure() {
        return exposure;
    }

    /** 审批人实名 → 展示值。SANITIZED 下确定性假名，其余原样。 */
    public String approver(String name) {
        return redactIdentity(name);
    }

    /** 多个审批人 → 展示值（各自走 {@link #approver}）。 */
    public List<String> approverAll(List<String> names) {
        if (names == null) {
            return List.of();
        }
        return names.stream().map(this::approver).toList();
    }

    /** 事件链操作者 → 展示值（与审批人共用同一套假名映射，便于对账）。 */
    public String actor(String name) {
        return redactIdentity(name);
    }

    private String redactIdentity(String name) {
        if (name == null || name.isBlank()) {
            return name;
        }
        if (exposure == AuditExposure.SANITIZED) {
            return pseudonyms.computeIfAbsent(name, k -> "APPROVER-" + hash8(k));
        }
        return name;
    }

    /** origin → 展示值。SANITIZED 仅留 host，其余原样。 */
    public String origin(String origin) {
        if (origin == null || origin.isBlank()) {
            return origin;
        }
        if (exposure == AuditExposure.SANITIZED) {
            return hostOnly(origin);
        }
        return origin;
    }

    /** 证据细节 → 展示值。INTERNAL/FULL 都剥离绝对路径，SANITIZED 在调用方已不输出此项。 */
    public String evidenceDetail(String detail) {
        if (detail == null) {
            return null;
        }
        if (exposure == AuditExposure.FULL) {
            return detail;
        }
        return stripPaths(detail);
    }

    /** 审批说明（note）→ 展示值。SANITIZED 下隐去。 */
    public String note(String note) {
        if (note == null) {
            return null;
        }
        if (exposure == AuditExposure.SANITIZED) {
            return "(redacted)";
        }
        return note;
    }

    /** 证据引用（如 session#7）→ 展示值。SANITIZED 下隐去。 */
    public String evidenceRef(String ref) {
        if (ref == null) {
            return null;
        }
        if (exposure == AuditExposure.SANITIZED) {
            return "(redacted)";
        }
        return ref;
    }

    /** 事件链自由文本 → 展示值。SANITIZED 下丢弃，只保留状态名。 */
    public String eventDetail(CandidateState state, String detail) {
        if (detail == null) {
            return null;
        }
        if (exposure == AuditExposure.SANITIZED) {
            return state == null ? "(redacted)" : state.name();
        }
        return detail;
    }

    private static String hash8(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 4; i++) {
                sb.append(String.format("%02x", d[i] & 0xff));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用，无法生成脱敏假名", e);
        }
    }

    /** 把 {@code scheme://host/path} 收敛为 {@code scheme://host}；裸环境标识（如 {@code uat}）原样返回。 */
    private static String hostOnly(String origin) {
        try {
            java.net.URI u = java.net.URI.create(origin);
            if (u.getScheme() != null && u.getHost() != null) {
                return u.getScheme() + "://" + u.getHost();
            }
        } catch (IllegalArgumentException ignored) {
            // 不是合法 URI（比如就叫 "uat"）就原样
        }
        return origin;
    }

    private static final Pattern LEADING_FILENAME = Pattern.compile("[\\w.\\-]+\\.\\w+");

    /**
     * 把证据细节里的绝对路径收敛为文件名，避免把用户名/目录带出。
     *
     * <p>逐 token 处理：把 {@code trace=C:\Users\alice\builds\t1.zip#frame-42} 这类现场的目录部分去掉，
     * 只留 {@code trace=t1.zip#frame-42}；无路径的纯文件名（如 {@code trace=t1.zip}）原样保留。
     * 用显式切分而非单个复杂正则，避免跨平台反斜杠的边界陷阱。
     */
    private static String stripPaths(String detail) {
        String[] tokens = detail.split("\\s+");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < tokens.length; i++) {
            if (i > 0) {
                sb.append(' ');
            }
            sb.append(stripToken(tokens[i]));
        }
        return sb.toString();
    }

    private static String stripToken(String token) {
        int eq = token.indexOf('=');
        String prefix = "";
        String body = token;
        if (eq >= 0) {
            prefix = token.substring(0, eq + 1);
            body = token.substring(eq + 1);
        }
        int lastSep = -1;
        for (int i = 0; i < body.length(); i++) {
            char c = body.charAt(i);
            if (c == '\\' || c == '/') {
                lastSep = i;
            }
        }
        if (lastSep >= 0) {
            String after = body.substring(lastSep + 1);
            // 只有后半段确实以"文件名.扩展名"开头时才剥离目录，避免误伤普通文本
            if (LEADING_FILENAME.matcher(after).find()) {
                body = after;
            }
        }
        return prefix + body;
    }
}
