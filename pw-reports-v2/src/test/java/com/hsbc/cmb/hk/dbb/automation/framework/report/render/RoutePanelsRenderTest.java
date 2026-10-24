package com.hsbc.cmb.hk.dbb.automation.framework.report.render;

import com.hsbc.cmb.hk.dbb.automation.framework.report.model.TestOutcome;
import com.hsbc.cmb.hk.dbb.automation.framework.report.model.TestResult;
import com.hsbc.cmb.hk.dbb.automation.framework.report.model.TestStep;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Route 面板渲染门禁：口径区分 + 去重。
 *
 * <p>覆盖 2026-10-21 用户反馈的三点：
 * <ul>
 *   <li>步骤默认折叠（{@code <details class="step-details">} 不带 {@code open}）；</li>
 *   <li>Route Register 与 Route Captured 用<b>不同色调</b>（{@code con-register} / {@code con-captured}）；</li>
 *   <li>同一抓包不得在「步骤内」与「用例级兜底」各出现一次（不再多出一块 Route Captured）。</li>
 * </ul>
 */
class RoutePanelsRenderTest {

    /** 控制台里"实际抓到的调用"是 {@code NET ...} 形态（{@code [Route ...]} 属注册日志，归 ROUTE）。 */
    private static final String CAPTURED_LINE = "NET POST https://host/auth/assert -> 200 (86 ms)";

    @Test
    @DisplayName("步骤默认折叠，且 Route Register / Route Captured 使用不同色调类名")
    void stepsCollapsedByDefaultAndPanelsUseDistinctTones() throws Exception {
        TestOutcome o = baseOutcome();
        TestStep step = new TestStep();
        step.setStepId("s1");
        step.setDescription("Given logon");
        step.setResult(TestResult.SUCCESS);
        step.getRouteRegistrations().add("[Route] register MONITOR route for 'auth/assert'");
        step.getConsoleOutput().add(CAPTURED_LINE);
        o.getTestSteps().add(step);

        String html = HtmlRenderer.renderToString(o);

        assertThat(html).doesNotContain("<details class=\"step-details\" open>");
        assertThat(html).contains("con-register").contains("con-captured");
        assertThat(html).contains("con-group con-captured step-captured");
        //  截图与步骤明细同级（flex 兄弟）：容器存在，且截图在 <details> 之外
        assertThat(html).contains("class=\"step-line\"");
    }

    @Test
    @DisplayName("通过用例右栏为空 → 单栏布局（Execution steps 与上下列同宽）")
    void passingCaseUsesSingleColumnLayout() throws Exception {
        TestOutcome o = baseOutcome();
        String html = HtmlRenderer.renderToString(o);
        assertThat(html).contains("case-layout is-single");
    }

    @Test
    @DisplayName("失败用例：Case actions 并入主列 → 仍是单栏（Execution steps 右边缘与上下卡片齐平）")
    void failingCaseKeepsSingleColumnLayout() throws Exception {
        TestOutcome o = baseOutcome();
        o.setResult(TestResult.FAILURE);
        String html = HtmlRenderer.renderToString(o);
        //  2026-10-22 起取消右侧 340px 栏：失败用例也要单栏，否则执行步骤比上下卡片窄一截
        assertThat(html).contains("case-layout is-single");
        assertThat(html).contains("case-actions");
    }

    @Test
    @DisplayName("同一条抓包（结构化上报 + 控制台镜像 + 用例级残留）只渲染一次")
    void sameCapturedLineIsRenderedOnlyOnce() throws Exception {
        TestOutcome o = baseOutcome();
        TestStep step = new TestStep();
        step.setStepId("s1");
        step.setDescription("Given logon");
        step.setResult(TestResult.SUCCESS);
        //  同一步骤内：结构化上报一条 + 控制台镜像同样一条
        step.getApiOperations().add(CAPTURED_LINE);
        step.getConsoleOutput().add(CAPTURED_LINE);
        o.getTestSteps().add(step);
        //  用例级再残留一条（历史数据 / 归属失败）：不得再渲染出第二块 Route Captured
        o.getApiOperations().add(CAPTURED_LINE);

        String html = HtmlRenderer.renderToString(o);

        assertThat(countOccurrences(html, "https://host/auth/assert")).isEqualTo(1);
        //  用例级兜底卡片整块不出现（去重后为空）
        assertThat(html).doesNotContain("Route Captured (");
    }

    @Test
    @DisplayName("同路径的 控制台镜像 + 结构化上报 + 用例级残留 → 步骤内只留结构化一份，用例级不再出现")
    void sameRouteFromMultipleSourcesIsShownOnce() throws Exception {
        String structured = "[Route MONITOR RESULT] https://host/api/v2/auth/assert?*** - {\n"
                + "  pattern : auth/assert\n}";
        String consoleMirror = "NET POST https://host/api/v2/auth/assert -> 200 (86 ms)";

        TestOutcome o = baseOutcome();
        TestStep step = new TestStep();
        step.setStepId("s1");
        step.setDescription("Given logon");
        step.setResult(TestResult.SUCCESS);
        step.getApiOperations().add(structured);
        step.getConsoleOutput().add(consoleMirror);
        o.getTestSteps().add(step);
        o.getApiOperations().add(structured);

        String html = HtmlRenderer.renderToString(o);

        //  控制台镜像被结构化上报抵扣（信息更全的一份保留）
        assertThat(html).doesNotContain("NET POST");
        //  步骤内恰好一条
        assertThat(html).contains("Route Captured<span class=\"con-count\">1</span>");
        //  用例级卡片整块不再出现
        assertThat(html).doesNotContain("Route Captured (");
    }

    private static TestOutcome baseOutcome() {
        TestOutcome o = new TestOutcome();
        o.setOutcomeId("route-1");
        o.setName("route panels");
        o.setResult(TestResult.SUCCESS);
        return o;
    }

    private static int countOccurrences(String text, String needle) {
        int count = 0;
        int idx = 0;
        while ((idx = text.indexOf(needle, idx)) >= 0) {
            count++;
            idx += needle.length();
        }
        return count;
    }
}
