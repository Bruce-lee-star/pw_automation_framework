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
    @DisplayName("步骤级与用例级 stdout/stderr 统一汇入底部 Console log，按类别分组渲染，互不压制")
    void stepAndCaseConsoleAreRoutedDisjointlyAndBothRender() throws Exception {
        TestOutcome o = OutcomeFixtures.full();
        // route/net（抓包相关）归位到发生步骤（stepConsole），普通 stdout/stderr（out/err）
        // 步骤内 + 步骤外合并后进页面底部 Console log。
        o.getConsoleOutput().add("CASE_OUT_LINE");
        o.getConsoleError().add("CASE_ERR_LINE");
        o.getTestSteps().get(0).getConsoleOutput().add("STEP_OUT_LINE");

        String html = HtmlRenderer.renderToString(o);

        // 普通 stdout → “Console output” 分组（con-out），不再是 Standard output
        assertThat(html).contains("Console output");
        assertThat(html).contains("con-out");
        // 步骤内普通 stdout 与步骤外输出合并进底部 Console log
        assertThat(html).contains("STEP_OUT_LINE");
        // 用例级 console（hook / 步骤外输出）照常展示，与步骤级并存、互不压制
        assertThat(html).contains("CASE_OUT_LINE");
        assertThat(html).contains("CASE_ERR_LINE");
    }

    @Test
    @DisplayName("无步骤时用例级 console 作为兜底渲染（底部 Console log 区）")
    void fallsBackToCaseConsoleWhenNoSteps() throws Exception {
        TestOutcome o = new TestOutcome();
        o.setOutcomeId("solo-1");
        o.setName("solo");
        o.setResult(TestResult.SUCCESS);
        o.getConsoleOutput().add("CASE_OUT_LINE");
        o.getConsoleError().add("CASE_ERR_LINE");

        String html = HtmlRenderer.renderToString(o);

        assertThat(html).contains("CASE_OUT_LINE").contains("CASE_ERR_LINE");
        //  步骤外 console 同样并入底部统一的 "Console log" 区，并按类别分组（con-group）渲染。
        assertThat(html).contains("Console log").contains("con-group");
    }

    @Test
    @DisplayName("无 console 时不渲染任何 console 分组（Tab 头仍占位，pane 显示空态）")
    void noConsoleRendersNothing() throws Exception {
        TestOutcome o = OutcomeFixtures.full();
        String html = HtmlRenderer.renderToString(o);
        //  Route Register 复用 con-group 外观（可折叠），故不能整体断言 con-group 缺席；
        //  底部 Tab 头（Console log / Playwright trace / Playwright records）常驻占位，
        //  但「分组内容」必须缺席：无 Console output / Console errors 组、无 con-out/con-err。
        assertThat(html)
                .doesNotContain("Console output")
                .doesNotContain("Console errors")
                .doesNotContain("con-out")
                .doesNotContain("con-err")
                //  空态提示在，说明 Tab 结构正常而不是把内容渲染了出来（英文文案，2026-10-22 统一）
                .contains("No console output was captured");
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
