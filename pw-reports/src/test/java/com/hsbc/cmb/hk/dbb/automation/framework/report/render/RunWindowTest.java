package com.hsbc.cmb.hk.dbb.automation.framework.report.render;

import com.hsbc.cmb.hk.dbb.automation.framework.report.config.PwReportsConfig;
import com.hsbc.cmb.hk.dbb.automation.framework.report.model.TestOutcome;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 运行窗口口径（index / feature / per-case / 邮件共用 {@link SummaryHtmlRenderer#runWindow}）。
 *
 * <p>回归背景（2026-11-11 用户反馈）：Surefire {@code rerunFailingTestsCount} 重跑后，
 * 重跑用例的 {@code startTime} 被覆盖成重跑时刻，于是 index 的 Started 显示成 rerun 的时间。
 * 现在 Started 取 {@code firstStartTime}（首轮首次开始），并额外输出 rerun 标注，
 * 让读者既能看懂整段运行何时开始，也知道中间发生过重跑。</p>
 *
 * <p>断言用 {@link #human(String)} 动态算期望值（换算到本机报告时区），
 * 因此测试与运行机器的时区无关；格式化本身的正确性由
 * {@link #humanTsConvertsAndLabelsZone()} 用固定时区锁死。</p>
 */
class RunWindowTest {

    /** 与生产同源：报告时区（默认 JVM 时区，可用 {@code pw.report.timezone} 覆盖）。 */
    private static final ZoneId ZONE = PwReportsConfig.reportZone();

    @Test
    @DisplayName("rerun 后 Started 仍取首轮首次开始，并标注 rerun 轮次与用例数")
    void startedUsesFirstStartAcrossRerun() {
        //  A：首轮 10:00:00 开始、10:05:00 结束，未重跑
        //  B：首轮 10:00:30 开始，重跑轮 10:30:00 → 10:35:10（startTime 已被覆盖）
        TestOutcome a = outcome("10:00:00", "10:00:00", "10:05:00", 1);
        TestOutcome b = outcome("10:00:30", "10:30:00", "10:35:10", 2);

        Map<String, Object> window = SummaryHtmlRenderer.runWindow(List.of(a, b));

        //  Started 必须取 A 的首轮开始（若误用 b 的 startTime 会得到 10:30:00）
        assertThat(window.get("startedAt")).isEqualTo(human("2026-11-11T10:00:00+08:00"));
        assertThat(window.get("endedAt")).isEqualTo(human("2026-11-11T10:35:10+08:00"));
        assertThat(window.get("rerunNote")).isEqualTo("rerun ×1 · 1 case");
        //  最后一次执行的开始时刻（per-case 页用它与 endedAt 拼「last run」窗口）
        assertThat(window.get("lastStartedAt")).isEqualTo(human("2026-11-11T10:30:00+08:00"));
    }

    @Test
    @DisplayName("未发生重跑时不输出 rerunNote（模板据此不渲染标注）")
    void noRerunNoteWithoutRerun() {
        TestOutcome a = outcome("10:00:00", "10:00:00", "10:05:00", 1);
        TestOutcome b = outcome("10:02:00", "10:02:00", "10:09:00", 1);

        Map<String, Object> window = SummaryHtmlRenderer.runWindow(List.of(a, b));

        assertThat(window).doesNotContainKey("rerunNote");
        assertThat(window.get("startedAt")).isEqualTo(human("2026-11-11T10:00:00+08:00"));
        assertThat(window.get("endedAt")).isEqualTo(human("2026-11-11T10:09:00+08:00"));
    }

    @Test
    @DisplayName("旧 JSON 缺 firstStartTime 时回退 startTime（不出现 \"—\"）")
    void fallsBackToStartTimeWhenFirstStartMissing() {
        TestOutcome legacy = outcome(null, "10:00:00", "10:05:00", 1);

        Map<String, Object> window = SummaryHtmlRenderer.runWindow(List.of(legacy));

        assertThat(window.get("startedAt")).isEqualTo(human("2026-11-11T10:00:00+08:00"));
    }

    @Test
    @DisplayName("多轮重跑：rerun ×N 的 N 取最大 attempt-1，cases 统计被重跑用例数")
    void rerunNoteCountsRoundsAndCases() {
        TestOutcome a = outcome("10:00:00", "10:40:00", "10:41:00", 3);
        TestOutcome b = outcome("10:01:00", "10:20:00", "10:21:00", 2);
        TestOutcome c = outcome("10:02:00", "10:02:00", "10:03:00", 1);

        Map<String, Object> window = SummaryHtmlRenderer.runWindow(List.of(a, b, c));

        assertThat(window.get("startedAt")).isEqualTo(human("2026-11-11T10:00:00+08:00"));
        assertThat(window.get("rerunNote")).isEqualTo("rerun ×2 · 2 cases");
    }

    @Test
    @DisplayName("时间统一换算到报告时区并带时区后缀（UTC+08:00 / UTC）")
    void humanTsConvertsAndLabelsZone() {
        OffsetDateTime utc = OffsetDateTime.parse("2026-11-11T02:00:00Z");

        assertThat(SummaryHtmlRenderer.humanTs(utc, ZoneId.of("Asia/Shanghai")))
                .isEqualTo("2026-11-11 10:00:00 (UTC+08:00)");
        assertThat(SummaryHtmlRenderer.humanTs(utc, ZoneOffset.UTC))
                .isEqualTo("2026-11-11 02:00:00 (UTC)");
        assertThat(SummaryHtmlRenderer.humanTs(utc, ZoneId.of("America/New_York")))
                .isEqualTo("2026-11-10 21:00:00 (UTC-05:00)");
    }

    /** 期望值：同一 ISO 时刻换算到本机报告时区（与生产同源算法）。 */
    private static String human(String iso) {
        return SummaryHtmlRenderer.humanTs(OffsetDateTime.parse(iso), ZONE);
    }

    private static TestOutcome outcome(String firstStart, String start, String end, int attempt) {
        TestOutcome o = new TestOutcome();
        o.setFirstStartTime(firstStart == null ? null : "2026-11-11T" + firstStart + "+08:00");
        o.setStartTime("2026-11-11T" + start + "+08:00");
        o.setEndTime("2026-11-11T" + end + "+08:00");
        o.setAttempt(attempt);
        return o;
    }
}
