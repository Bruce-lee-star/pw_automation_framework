package com.hsbc.cmb.hk.dbb.automation.framework.report.render;

import com.hsbc.cmb.hk.dbb.automation.framework.report.model.TestOutcome;
import com.hsbc.cmb.hk.dbb.automation.framework.report.model.TestResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 控制台输出模板渲染分支覆盖——{@link OutcomeFixtures#full()} 不含 console，
 * 故 HTML 快照门禁不变；此测试专门喂入 console 验证模板不报错且正确渲染。
 *
 * <p>2026-10-18 起控制台按类别分组（{@code con-group}/{@code con-out}/{@code con-err}...），
 * 不再使用笼统的 “Standard output / Standard error” 文案。
 */
class ConsoleRenderTest {

    @Test
    @DisplayName("步骤级与用例级 console 归经互斥：步骤内归步骤、hook 归用例级，两者都渲染不互相压制")
    void stepAndCaseConsoleAreRoutedDisjointlyAndBothRender() throws Exception {
        TestOutcome o = OutcomeFixtures.full();
        // 用例级 = 步骤之外的输出（如 @Before/@After hook）；步骤级 = 步骤内输出。ConsoleCapture 按线程栈顶
        // sink 归经（route 只投递 peek），二者天然互斥、不会重复，故页面两者都展示。早先"有步骤即抑制用例级"
        // 的去重预期与互斥归经模型不符（且会误丢 hook 输出），已更正。
        o.getConsoleOutput().add("CASE_OUT_LINE");
        o.getConsoleError().add("CASE_ERR_LINE");
        o.getTestSteps().get(0).getConsoleOutput().add("STEP_OUT_LINE");

        String html = HtmlRenderer.renderToString(o);

        // 普通 stdout → “Console output” 分组（con-out），不再是 Standard output
        assertThat(html).contains("Console output");
        assertThat(html).contains("con-out");
        // 步骤级 console 挂在步骤下
        assertThat(html).contains("STEP_OUT_LINE");
        // 用例级 console（hook / 步骤外输出）照常展示，与步骤级并存、互不压制
        assertThat(html).contains("CASE_OUT_LINE");
        assertThat(html).contains("CASE_ERR_LINE");
    }

    @Test
    @DisplayName("无步骤时用例级 console 作为兜底渲染")
    void fallsBackToCaseConsoleWhenNoSteps() throws Exception {
        TestOutcome o = new TestOutcome();
        o.setOutcomeId("solo-1");
        o.setName("solo");
        o.setResult(TestResult.SUCCESS);
        o.getConsoleOutput().add("CASE_OUT_LINE");
        o.getConsoleError().add("CASE_ERR_LINE");

        String html = HtmlRenderer.renderToString(o);

        assertThat(html).contains("CASE_OUT_LINE").contains("CASE_ERR_LINE");
        assertThat(html).contains("Console output").contains("Console errors");
    }

    @Test
    @DisplayName("无 console 时不渲染任何 console 区块（保持快照稳定）")
    void noConsoleRendersNothing() throws Exception {
        TestOutcome o = OutcomeFixtures.full();
        String html = HtmlRenderer.renderToString(o);
        assertThat(html)
                .doesNotContain("con-group")
                .doesNotContain("Console output")
                .doesNotContain("Console errors");
    }

    @Test
    @DisplayName("RestAssured 请求转储渲染为键值行 + 续行对齐，值为 <none> 的行整行丢弃")
    void restAssuredDumpRendersAsKeyValueRows() throws Exception {
        TestOutcome o = new TestOutcome();
        o.setOutcomeId("kv-1");
        o.setName("kv");
        o.setResult(TestResult.SUCCESS);
        o.getConsoleOutput().add("Request method:\tGET");
        o.getConsoleOutput().add("Query params:\ttype=1");
        o.getConsoleOutput().add("\t\tkey=abc");
        o.getConsoleOutput().add("Body:\t<none>");
        o.getConsoleOutput().add("https://example.com:8443/api");
        o.getConsoleOutput().add("12:34:56.789 [main] INFO  demo - hi");

        String html = HtmlRenderer.renderToString(o);

        assertThat(html).contains("<span class=\"con-k\">Request method</span>")
                .contains("<span class=\"con-v\">GET</span>")
                .contains("class=\"con-cont\">key=abc</li>")
                // <none> 无信息量，整行不渲染（2026-10-18 用户反馈）
                .doesNotContain("<span class=\"con-k\">Body</span>");
        // https:// 与时间戳 12:34:56 不能被误判为键值行
        assertThat(html).doesNotContain("<span class=\"con-k\">https</span>")
                .doesNotContain("<span class=\"con-k\">12</span>");
    }
}
