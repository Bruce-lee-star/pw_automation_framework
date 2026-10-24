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

    @Test
    @DisplayName("Jenkins 运行：BUILD_URL + JOB_NAME + BUILD_NUMBER + GIT_COMMIT 全部识别")
    void jenkinsBuildUrlDetected() {
        Map<String, String> env = Map.of(
                "BUILD_URL", "https://jenkins.corp/job/regression/123/",
                "JENKINS_URL", "https://jenkins.corp/",
                "JOB_NAME", "team/regression",
                "BUILD_NUMBER", "123",
                "GIT_COMMIT", "a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2");

        Map<String, Object> ci = ReportView.ciBuild(env);

        assertThat(ci.get("present")).isEqualTo(true);
        assertThat(ci.get("ciName")).isEqualTo("Jenkins");
        assertThat(ci.get("url")).isEqualTo("https://jenkins.corp/job/regression/123/");
        assertThat(ci.get("job")).isEqualTo("team/regression");
        assertThat(ci.get("number")).isEqualTo("123");
        assertThat(ci.get("commitShort")).isEqualTo("a1b2c3d");
    }

    @Test
    @DisplayName("pw.report.ci.url 覆盖：BUILD_URL 含错误 /jenkins context 时改用可达链接（占位符展开并优先）")
    void ciUrlOverrideReplacesBuildUrl() {
        String prev = System.getProperty("pw.report.ci.url");
        try {
            System.setProperty("pw.report.ci.url",
                    "http://localhost:61438/job/${JOB_NAME}/${BUILD_NUMBER}/");
            Map<String, String> env = Map.of(
                    "BUILD_URL", "http://localhost:61438/jenkins/job/team/regression/123/",
                    "JENKINS_URL", "http://localhost:61438/jenkins/",
                    "JOB_NAME", "team/regression",
                    "BUILD_NUMBER", "123");

            Map<String, Object> ci = ReportView.ciBuild(env);

            assertThat(ci.get("present")).isEqualTo(true);
            assertThat(ci.get("ciName")).isEqualTo("Jenkins");
            assertThat(ci.get("url"))
                    .isEqualTo("http://localhost:61438/job/team/regression/123/");
            assertThat(ci.get("job")).isEqualTo("team/regression");
            assertThat(ci.get("number")).isEqualTo("123");
        } finally {
            if (prev == null) System.clearProperty("pw.report.ci.url");
            else System.setProperty("pw.report.ci.url", prev);
        }
    }

    @Test
    @DisplayName("Jenkins 仅给 JENKINS_URL + JOB_NAME + BUILD_NUMBER：按约定拼接构建链接")
    void jenkinsUrlConstructedWhenBuildUrlAbsent() {
        Map<String, String> env = Map.of(
                "JENKINS_URL", "https://jenkins.corp/",
                "JOB_NAME", "team/regression",
                "BUILD_NUMBER", "123");

        Map<String, Object> ci = ReportView.ciBuild(env);

        assertThat(ci.get("present")).isEqualTo(true);
        assertThat(ci.get("url")).isEqualTo("https://jenkins.corp/job/team/job/regression/123/");
    }

    @Test
    @DisplayName("GitLab CI：CI_JOB_URL + CI_COMMIT_SHA 识别为 CI（非 Jenkins）")
    void gitlabCiDetected() {
        Map<String, String> env = Map.of(
                "CI_JOB_URL", "https://gitlab.corp/grp/proj/-/jobs/456",
                "CI_COMMIT_SHA", "deadbeefcafe");

        Map<String, Object> ci = ReportView.ciBuild(env);

        assertThat(ci.get("present")).isEqualTo(true);
        assertThat(ci.get("ciName")).isEqualTo("CI");
        assertThat(ci.get("url")).isEqualTo("https://gitlab.corp/grp/proj/-/jobs/456");
        assertThat(ci.get("commitShort")).isEqualTo("deadbee");
    }

    @Test
    @DisplayName("本地 IDE 运行（无任何 CI 变量）：present=false，不显示 CI 痕迹")
    void noCiOnLocalRun() {
        Map<String, String> env = Map.of("PATH", "/usr/bin");

        Map<String, Object> ci = ReportView.ciBuild(env);

        assertThat(ci.get("present")).isEqualTo(false);
        assertThat(ci.get("url")).isNull();
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
