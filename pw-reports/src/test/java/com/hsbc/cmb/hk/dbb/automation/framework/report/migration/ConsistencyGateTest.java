package com.hsbc.cmb.hk.dbb.automation.framework.report.migration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hsbc.cmb.hk.dbb.automation.framework.report.model.TestOutcome;
import com.hsbc.cmb.hk.dbb.automation.framework.report.model.TestResult;
import com.hsbc.cmb.hk.dbb.automation.framework.report.model.UserStory;
import com.hsbc.cmb.hk.dbb.automation.framework.report.render.JsonWriter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 双跑四一致门禁（Tier2 #7 / 迁移 Phase 0）：
 * <ul>
 *   <li>自洽：pw-reports 目录与自己比对 → 无 ERROR；</li>
 *   <li>result 漂移 → ERROR；</li>
 *   <li>coverage 缺例 → WARN；</li>
 *   <li>Serenity 基线（对齐 feature/name）比对 → 无 ERROR（证明跨格式门禁可用）。</li>
 * </ul>
 */
class ConsistencyGateTest {

    private static final ObjectMapper M = JsonWriter.mapper();

    @TempDir
    Path pwDir;

    @Test
    @DisplayName("自洽：pw-reports 目录与自身比对不报 ERROR")
    void selfConsistent() throws Exception {
        writeRun(pwDir, a(), b());
        GateReport report = comparePwToPw(pwDir, pwDir);
        assertThat(report.failed()).isFalse();
        assertThat(report.errors()).isEmpty();
    }

    @Test
    @DisplayName("result 漂移被捕获为 ERROR（阻断迁移）")
    void detectsResultDrift() throws Exception {
        writeRun(pwDir, a(), b());
        // 基线侧把 b 由 FAILURE 改为 SUCCESS —— 形成 result 漂移
        Path baseDir = copyWithResult(pwDir, "checkout-20-pay", TestResult.SUCCESS);
        GateReport report = comparePwToPw(pwDir, baseDir);
        assertThat(report.failed()).isTrue();
        assertThat(report.errors()).anyMatch(v -> v.dimension() == GateViolation.Dimension.RESULT);
    }

    @Test
    @DisplayName("coverage 缺例被捕获为 WARN")
    void detectsCoverageGap() throws Exception {
        writeRun(pwDir, a(), b());
        Path baseDir = Files.createTempDirectory("base-missing");
        writeRun(baseDir, a()); // 基线缺 b
        GateReport report = comparePwToPw(pwDir, baseDir);
        assertThat(report.warnings()).anyMatch(v -> v.dimension() == GateViolation.Dimension.COVERAGE);
    }

    @Test
    @DisplayName("Serenity 基线（outcomes[] 形态，feature/name 对齐）比对无 ERROR")
    void serenityBaselineAligned() throws Exception {
        writeRun(pwDir, a(), b());
        Path serenityDir = Files.createTempDirectory("serenity");
        writeSerenitySummary(serenityDir,
                "authentication-12-user-logs-in", "Authentication", "User logs in", "SUCCESS",
                "checkout-20-pay", "Checkout", "Pay", "FAILURE");
        GateReport report = ReportConsistencyGate.compare(pwDir, serenityDir);
        assertThat(report.failed()).isFalse();
        assertThat(report.errors()).isEmpty();
    }

    /** 两份 pw-reports 报告互比（漂移/覆盖用例走此路径，语义与真实双跑一致）。 */
    private static GateReport comparePwToPw(Path left, Path right) {
        return ReportConsistencyGate.compare(new PwReportSource().load(left), new PwReportSource().load(right));
    }

    @Test
    @DisplayName("Serenity 源解析 outcomes[] 抽取归一化视图")
    void serenitySourceExtracts() throws Exception {
        Path serenityDir = Files.createTempDirectory("serenity");
        writeSerenitySummary(serenityDir,
                "id-1", "Authentication", "User logs in", "SUCCESS");
        List<CanonicalOutcome> list = new ManagedReport().load(serenityDir);
        assertThat(list).hasSize(1);
        assertThat(list.get(0).name()).isEqualTo("User logs in");
        assertThat(list.get(0).result()).isEqualTo("SUCCESS");
        assertThat(list.get(0).stepTexts()).isEmpty(); // Serenity 汇总不含步骤明细
    }

    // ---------------------------------------------------------------- fixtures

    private static TestOutcome a() {
        TestOutcome o = new TestOutcome();
        o.setOutcomeId("authentication-12-user-logs-in");
        o.setName("User logs in");
        o.setUserStory(new UserStory("Authentication", "Authentication", "auth.feature"));
        o.setResult(TestResult.SUCCESS);
        o.setDuration(120);
        return o;
    }

    private static TestOutcome b() {
        TestOutcome o = new TestOutcome();
        o.setOutcomeId("checkout-20-pay");
        o.setName("Pay");
        o.setUserStory(new UserStory("Checkout", "Checkout", "checkout.feature"));
        o.setResult(TestResult.FAILURE);
        o.setDuration(80);
        return o;
    }

    private static void writeRun(Path dir, TestOutcome... outcomes) throws Exception {
        for (TestOutcome o : outcomes) {
            JsonWriter.writeOutcome(dir, o);
        }
        JsonWriter.writeSummary(dir, List.of(outcomes));
    }

    private static Path copyWithResult(Path src, String outcomeId, TestResult newResult) throws Exception {
        Path dst = Files.createTempDirectory("serenity-drift");
        Files.walk(src).filter(Files::isRegularFile).forEach(p -> {
            try {
                Path target = dst.resolve(src.relativize(p).toString());
                Files.createDirectories(target.getParent());
                if (p.getFileName().toString().equals(outcomeId + ".json")) {
                    TestOutcome o = M.readValue(p.toFile(), TestOutcome.class);
                    o.setResult(newResult);
                    M.writeValue(target.toFile(), o);
                } else {
                    Files.copy(p, target);
                }
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        return dst;
    }

    private static void writeSerenitySummary(Path dir, String id, String feature,
                                             String name, String result, String... more) throws Exception {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"reportName\":\"serenity\",\"outcomes\":[");
        sb.append(serenityOutcome(id, feature, name, result));
        for (int i = 0; i < more.length; i += 4) {
            sb.append(',').append(serenityOutcome(more[i], more[i + 1], more[i + 2], more[i + 3]));
        }
        sb.append("]}");
        Files.writeString(dir.resolve("serenity-summary.json"), sb.toString());
    }

    private static String serenityOutcome(String id, String feature, String name, String result) {
        return "{\"id\":\"" + id + "\",\"feature\":\"" + feature + "\",\"name\":\"" + name
                + "\",\"result\":\"" + result + "\",\"duration\":100}";
    }
}
