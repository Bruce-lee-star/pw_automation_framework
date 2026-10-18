package com.hsbc.cmb.hk.dbb.automation.framework.report.render;

import com.hsbc.cmb.hk.dbb.automation.framework.report.model.TestOutcome;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * HTML 渲染快照门禁——模板视觉/结构变更必须显式重新冻结，防止无意漂移。
 *
 * <p>规范化（易变内容替换为占位符）：
 * <ul>
 *   <li>{@code \d+ ms} → {@code <D> ms}（时长）；</li>
 *   <li>ISO-8601 时间戳 → {@code <TS>}；</li>
 *   <li>{@code step-\d+.png} → {@code step-<N>.png}（确定性命名但仍归一，防序号策略调整误报）；</li>
 *   <li>{@code reportVersion \d+} → {@code reportVersion <V>}（版本归 schema 门禁管）。</li>
 * </ul>
 *
 * <p>重新冻结（模板故意变更后）：
 * {@code mvn test -Dtest=SnapshotGateTest -Dpw.report.test.write-snapshot=true}
 */
class SnapshotGateTest {

    private static final Path SNAPSHOT =
            Paths.get("src/test/resources/render/test-outcome.snapshot.html");

    private static final Pattern DURATION = Pattern.compile("\\b\\d+ ms\\b");
    private static final Pattern ISO_TIMESTAMP =
            Pattern.compile("\\d{4}-\\d{2}-\\d{2}T[\\d:.]+(?:Z|[+-]\\d{2}:\\d{2})");
    private static final Pattern SCREENSHOT_NAME = Pattern.compile("step-\\d+\\.png");
    private static final Pattern REPORT_VERSION = Pattern.compile("reportVersion \\d+");

    private static String normalizedSnapshot;

    @BeforeAll
    static void renderAndNormalize() throws Exception {
        TestOutcome outcome = OutcomeFixtures.full();
        String html = HtmlRenderer.renderToString(outcome);
        String normalized = normalize(html);
        if (Boolean.getBoolean("pw.report.test.write-snapshot")) {
            Files.createDirectories(SNAPSHOT.getParent());
            Files.writeString(SNAPSHOT, normalized, StandardCharsets.UTF_8);
            System.out.println("[SnapshotGateTest] frozen snapshot written: "
                    + SNAPSHOT.toAbsolutePath());
            return;
        }
        assertThat(SNAPSHOT).as("snapshot file missing — generate with "
                        + "-Dpw.report.test.write-snapshot=true (first run or intentional reset)")
                .exists();
        normalizedSnapshot = Files.readString(SNAPSHOT, StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("全量 outcome 渲染 HTML 与冻结快照一致（规范化后）")
    void renderedHtmlMatchesFrozenSnapshot() throws Exception {
        if (normalizedSnapshot == null) {
            return; // write-snapshot 模式：本轮只冻结，不比对
        }
        TestOutcome outcome = OutcomeFixtures.full();
        String normalized = normalize(HtmlRenderer.renderToString(outcome));
        assertThat(normalized)
                .as("HTML snapshot drift — 模板变更有意为止？重新冻结："
                        + "mvn test -Dtest=SnapshotGateTest -Dpw.report.test.write-snapshot=true")
                .isEqualTo(normalizedSnapshot);
    }

    private static String normalize(String html) {
        return REPORT_VERSION.matcher(
                SCREENSHOT_NAME.matcher(
                        ISO_TIMESTAMP.matcher(
                                DURATION.matcher(html).replaceAll("<D> ms"))
                                .replaceAll("<TS>"))
                        .replaceAll("step-<N>.png"))
                .replaceAll("reportVersion <V>");
    }

    /** 快照内容自检：规范化必须真的把时长/时间戳/截图名打掉（防规则失效）。 */
    @Test
    @DisplayName("规范化规则自检：快照中无残留易变值")
    void normalizationRulesAreEffective() {
        if (normalizedSnapshot == null) {
            return;
        }
        assertThat(normalizedSnapshot).doesNotContainPattern(DURATION);
        assertThat(normalizedSnapshot).doesNotContainPattern(ISO_TIMESTAMP);
        assertThat(normalizedSnapshot).doesNotContainPattern(SCREENSHOT_NAME);
        assertThat(normalizedSnapshot).contains("<D> ms").contains("<TS>").contains("step-<N>.png");
    }
}
