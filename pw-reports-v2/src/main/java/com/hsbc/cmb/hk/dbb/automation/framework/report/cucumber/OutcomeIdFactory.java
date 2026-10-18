package com.hsbc.cmb.hk.dbb.automation.framework.report.cucumber;

import java.util.Locale;

/**
 * outcomeId 构造——{@code slug(feature)-line-slug(scenario)}（R7）。
 *
 * <p>唯一性：冲突（scenario outline 同名示例、重试插件重发 TestCaseStarted）由
 * {@code ReportBus.startCase} 追加 {@code -2/-3} 后缀解决；本类只负责 base id。
 *
 * <p>slug 规则（对齐 Serenity 命名习惯）：小写、非字母数字折叠为单个 {@code -}、
 * 去首尾 {@code -}、限长 60（防文件名过长/路径长度问题）。
 */
public final class OutcomeIdFactory {

    private static final int MAX_SLUG_LEN = 60;

    private OutcomeIdFactory() {
    }

    /** @param featureUri feature 文件 URI（{@code file:///.../login.feature} 或相对路径均可） */
    public static String baseId(String featureUri, int line, String scenarioName) {
        return slug(featureBaseName(featureUri)) + "-" + line + "-" + slug(scenarioName);
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
