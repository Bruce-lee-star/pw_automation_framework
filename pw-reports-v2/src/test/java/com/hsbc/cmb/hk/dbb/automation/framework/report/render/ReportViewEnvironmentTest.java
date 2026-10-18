package com.hsbc.cmb.hk.dbb.automation.framework.report.render;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Environment 面板数据源：空值不展示、构建工具 / 浏览器驱动有真实来源。
 *
 * <p>背景（2026-10-18 用户反馈）：Environment 卡出现 Build tool / Browser driver / Target
 * 三行 “—”，视觉上像坏掉了。约定改为「取不到就不出现」，模板因此不再需要占位符。</p>
 */
class ReportViewEnvironmentTest {

    private static final String[] TOUCHED = {
            "pw.report.target", "base.url", "maven.version", "maven.build.version",
            "playwright.version", "pw.report.browser.driver"};

    private final Map<String, String> backup = new HashMap<>();

    @AfterEach
    void restore() {
        for (String key : TOUCHED) {
            String original = backup.remove(key);
            if (original == null) {
                System.clearProperty(key);
            } else {
                System.setProperty(key, original);
            }
        }
    }

    @Test
    @DisplayName("空值不出现在 Environment 数据里（模板无需 — 占位）")
    void blankValuesAreDropped() {
        set("pw.report.target", null);
        set("base.url", "");

        List<Map<String, Object>> rows = ReportView.environment();

        assertThat(rows).allSatisfy(row -> assertThat(row.get("v")).isNotNull());
        assertThat(rows).extracting(row -> row.get("k"))
                .contains("JDK", "OS", "Test framework");
        // CI 可能注入 BASE_URL 环境变量；仅在其缺席时才断言 Target 行被丢弃
        if (System.getenv("BASE_URL") == null) {
            assertThat(rows).extracting(row -> row.get("k")).doesNotContain("Target");
        }
    }

    @Test
    @DisplayName("Build tool / Browser driver / Target 取自系统属性并格式化")
    void knownValuesAreFormatted() {
        set("maven.version", "3.9.9");
        set("playwright.version", "1.62.0");
        set("pw.report.target", "https://sit.example");

        Map<String, Object> rows = byKey(ReportView.environment());

        assertThat(rows).containsEntry("Build tool", "Maven 3.9.9")
                .containsEntry("Browser driver", "Playwright 1.62.0")
                .containsEntry("Target", "https://sit.example");
    }

    private void set(String key, String value) {
        backup.putIfAbsent(key, System.getProperty(key));
        if (value == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, value);
        }
    }

    private static Map<String, Object> byKey(List<Map<String, Object>> rows) {
        Map<String, Object> out = new HashMap<>();
        for (Map<String, Object> row : rows) {
            out.put(String.valueOf(row.get("k")), row.get("v"));
        }
        return out;
    }
}
