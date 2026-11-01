package com.hsbc.cmb.hk.dbb.automation.framework.report.cucumber;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.Locale;

/**
 * outcomeId 构造——{@code yyyyMMdd-HHmmss-<SHA-256 hex>}。
 *
 * <p>唯一性：冲突（scenario outline 同名示例、重试插件重发 TestCaseStarted）由
 * {@code ReportBus.startCase} 追加 {@code -2/-3} 后缀解决；本类只负责 base id。
 *
 * <p>对齐 Serenity 默认报告文件名策略：以完整用例身份生成 SHA-256 十六进制摘要，
 * 避免长名称和文件系统特殊字符影响产物路径。
 */
public final class OutcomeIdFactory {

    private static final int MAX_SLUG_LEN = 60;
    private static final DateTimeFormatter DIRECTORY_TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.ROOT);

    private OutcomeIdFactory() {
    }

    /** @param featureUri feature 文件 URI（{@code file:///.../login.feature} 或相对路径均可） */
    public static String baseId(String featureUri, int line, String scenarioName) {
        String identity = featureBaseName(featureUri) + ":" + line + ":" + scenarioName;
        return timestampedId(identity);
    }

    static String baseId(String featureUri, int line, String scenarioName, ZonedDateTime timestamp) {
        String identity = featureBaseName(featureUri) + ":" + line + ":" + scenarioName;
        return timestampedId(identity, timestamp);
    }

    /** Prefix the SHA-256 hex digest of a case identity with the local time to seconds. */
    public static String timestampedId(String rawId) {
        return timestampedId(rawId, ZonedDateTime.now().format(DIRECTORY_TIMESTAMP));
    }

    static String timestampedId(String rawId, ZonedDateTime timestamp) {
        return timestampedId(rawId, timestamp.format(DIRECTORY_TIMESTAMP));
    }

    private static String timestampedId(String rawId, String timestamp) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(rawId.getBytes(StandardCharsets.UTF_8));
            return timestamp + "-" + HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the Java runtime", e);
        }
    }

    /** feature 文件名去目录与扩展名（{@code login.feature} → {@code login}）。 */
    public static String featureBaseName(String featureUri) {
        if (featureUri == null || featureUri.isBlank()) {
            return "feature";
        }
        String s = featureUri.replace('\\', '/');
        int slash = s.lastIndexOf('/');
        if (slash >= 0) {
            s = s.substring(slash + 1);
        }
        int dot = s.lastIndexOf('.');
        return dot > 0 ? s.substring(0, dot) : s;
    }

    /** slug 化：小写 + 折叠 + 限长。 */
    public static String slug(String raw) {
        if (raw == null || raw.isBlank()) {
            return "unnamed";
        }
        String s = raw.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-");
        s = s.replaceAll("^-+", "").replaceAll("-+$", "");
        if (s.length() > MAX_SLUG_LEN) {
            s = s.substring(0, MAX_SLUG_LEN).replaceAll("-+$", "");
        }
        return s.isEmpty() ? "unnamed" : s;
    }
}
