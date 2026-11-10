package com.hsbc.cmb.hk.dbb.automation.framework.report.render;

import com.hsbc.cmb.hk.dbb.automation.framework.report.event.ScrubGate;
import com.hsbc.cmb.hk.dbb.automation.framework.report.model.FailureCause;
import com.hsbc.cmb.hk.dbb.automation.framework.report.model.TestOutcome;
import com.hsbc.cmb.hk.dbb.automation.framework.report.model.TestResult;
import com.hsbc.cmb.hk.dbb.automation.framework.report.model.UserStory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 三类 HTML 出口的掩码端到端回归：per-case、monitor-failures、email。
 *
 * <p>锁住：① 脱敏与限长都由数据入口产出 {@code *****}，报告原样显示，不需要展示层翻译；
 * ② 掩码之外的内容仍正确 HTML 转义；③ 展示层不再对已限长的数据做二次裁剪。
 */
class ReportMarkersRenderTest {

    private static final String MASK = ScrubGate.MASK;

    @TempDir
    Path dir;

    @Test
    @DisplayName("per-case：脱敏后的值原样显示掩码，HTML 仍转义")
    void perCaseHtmlShowsMaskAndStillEscapesHtml() throws Exception {
        String line = ScrubGate.defaults().scrubLine(
                "GET /api?token=secret <script>alert(1)</script>");
        TestOutcome o = OutcomeFixtures.full();
        o.setBrowserErrors(List.of("token=" + MASK + " mask=" + MASK + " <b>raw</b>"));
        o.setConsoleOutput(List.of(line));

        String html = HtmlRenderer.renderToString(o);

        assertThat(html)
                .contains("token=" + MASK)
                .contains("mask=" + MASK)
                .contains("&lt;b&gt;raw&lt;/b&gt;")
                .contains("&lt;script&gt;alert(1)&lt;/script&gt;");
    }

    @Test
    @DisplayName("API 卡片的值与 URL 同样保留掩码")
    void apiCardValuesAndUrlKeepMask() throws Exception {
        TestOutcome o = OutcomeFixtures.full();
        o.getTestSteps().get(1).getApiOperations().clear();
        o.getTestSteps().get(1).getApiOperations().add(
                "REST|GET|https://api.example.com/profile?token=" + MASK + "|200|12 ms\n"
                        + "RESB|Response body|payload=" + MASK + " with " + MASK);

        String html = HtmlRenderer.renderToString(o);

        assertThat(html)
                .contains("profile?token=" + MASK)
                .contains("payload=" + MASK + " with " + MASK);
    }

    @Test
    @DisplayName("堆栈展示层不再二次裁剪，尾部内容完整可见")
    void stackTraceTailSurvivesRendering() throws Exception {
        TestOutcome o = OutcomeFixtures.full();
        o.setTestFailureSummary("java.lang.AssertionError: boom\n"
                + "a".repeat(3000) + "\nSENTINEL_AT_END");

        String html = HtmlRenderer.renderToString(o);

        assertThat(html).contains("SENTINEL_AT_END");
    }

    @Test
    @DisplayName("monitor-failures：掩码 + 转义（原文不再裸输出）")
    void monitorFailuresHtmlShowsMaskAndEscapes() throws Exception {
        TestOutcome o = new TestOutcome();
        o.setOutcomeId("m-1");
        o.setName("monitor failed");
        o.setTitle("monitor failed");
        o.setResult(TestResult.FAILURE);
        o.setUserStory(new UserStory("Payments", "Payments", null));
        o.setApiMonitorFailures(List.of("MONITOR FAIL token=" + MASK + " <b>x</b>"));

        Path out = SummaryHtmlRenderer.writeMonitorFailures(dir, List.of(o));
        assertThat(out).isNotNull();
        String html = Files.readString(out);

        assertThat(html).contains("token=" + MASK).contains("&lt;b&gt;x&lt;/b&gt;");
    }

    @Test
    @DisplayName("邮件报告：超长失败消息的省略位置显示掩码")
    void emailFailureMessageShowsMask() throws Exception {
        TestOutcome o = OutcomeFixtures.full();
        o.setTestFailureCause(new FailureCause("boom " + "x".repeat(400), "FAILURE"));

        List<Map<String, Object>> rows = SummaryHtmlRenderer.failureRows(List.of(o));
        assertThat((String) rows.get(0).get("message")).endsWith(MASK);

        String html = EmailReportRenderer.renderToString(List.of(o));
        assertThat(html).contains(MASK);
    }
}
