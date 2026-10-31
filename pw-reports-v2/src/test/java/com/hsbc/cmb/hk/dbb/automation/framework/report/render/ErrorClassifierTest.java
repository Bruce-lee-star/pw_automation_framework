package com.hsbc.cmb.hk.dbb.automation.framework.report.render;

import com.hsbc.cmb.hk.dbb.automation.framework.report.model.FailureCause;
import com.hsbc.cmb.hk.dbb.automation.framework.report.model.TestOutcome;
import com.hsbc.cmb.hk.dbb.automation.framework.report.model.TestResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 失败分类器判定测试：类名结构化信号优先、消息精确短语次之、语义类别兜底。
 * 文案用例与 {@code ElementOperationSupportTest} 的 isNavigationOrContextLoss 判据对齐
 * （同源文案，避免同一失败在两处被归到不同语义）。
 */
class ErrorClassifierTest {

    // ---- 1) 异常类名（结构化信号，最高优先级） ----

    @Test
    @DisplayName("异常类名 TimeoutError → TIMEOUT，消息措辞不得覆盖")
    void classnameTimeoutWinsOverMessage() {
        assertThat(ErrorClassifier.classify(
                "Timeout 30000ms exceeded during navigation", "com.microsoft.playwright.TimeoutError", "ERROR"))
                .isEqualTo(ErrorClassifier.Category.TIMEOUT);
    }

    @Test
    @DisplayName("异常类名 AssertionError → ASSERTION（消息仅业务文案也归断言）")
    void classnameAssertion() {
        assertThat(ErrorClassifier.classify(
                "expected status 200 but was 401", "java.lang.AssertionError", "FAILURE"))
                .isEqualTo(ErrorClassifier.Category.ASSERTION);
    }

    @Test
    @DisplayName("异常类名 NoSuchElementException → ELEMENT_NOT_FOUND")
    void classnameNoSuchElement() {
        assertThat(ErrorClassifier.classify(
                "Unable to locate element", "org.openqa.selenium.NoSuchElementException", "ERROR"))
                .isEqualTo(ErrorClassifier.Category.ELEMENT_NOT_FOUND);
    }

    // ---- 2) 消息精确短语 ----

    @Test
    @DisplayName("导航期超时（无 TimeoutError 类名）→ TIMEOUT（超时优先于导航语义）")
    void messageTimeoutBeatsNavigation() {
        assertThat(ErrorClassifier.classify(
                "Timeout 30000ms exceeded during navigation", "com.hsbc.framework.ElementOperationException", "ERROR"))
                .isEqualTo(ErrorClassifier.Category.TIMEOUT);
    }

    @Test
    @DisplayName("执行上下文销毁（导航重建 DOM 的典型文案）→ NAVIGATION_LOSS")
    void messageExecutionContextDestroyed() {
        assertThat(ErrorClassifier.classify(
                "Execution context was destroyed, most likely because of a navigation", null, null))
                .isEqualTo(ErrorClassifier.Category.NAVIGATION_LOSS);
    }

    @Test
    @DisplayName("等待导航中 / 导航失败页面关闭 → NAVIGATION_LOSS（对齐 isNavigationOrContextLoss 接受集）")
    void messageNavigationPhrases() {
        assertThat(ErrorClassifier.classify("waiting while navigating to url", null, null))
                .isEqualTo(ErrorClassifier.Category.NAVIGATION_LOSS);
        assertThat(ErrorClassifier.classify("Navigation failed because the page was closed", null, null))
                .isEqualTo(ErrorClassifier.Category.NAVIGATION_LOSS);
        assertThat(ErrorClassifier.classify("Object doesn't exist: frame@1", null, null))
                .isEqualTo(ErrorClassifier.Category.NAVIGATION_LOSS);
    }

    @Test
    @DisplayName("页面/上下文/浏览器关闭 → CONTEXT_CLOSED")
    void messageContextClosed() {
        assertThat(ErrorClassifier.classify("Target page, context or browser has been closed", null, null))
                .isEqualTo(ErrorClassifier.Category.CONTEXT_CLOSED);
    }

    @Test
    @DisplayName("frame 分离 → FRAME_DETACHED")
    void messageFrameDetached() {
        assertThat(ErrorClassifier.classify("frame was detached", null, null))
                .isEqualTo(ErrorClassifier.Category.FRAME_DETACHED);
    }

    @Test
    @DisplayName("元素不存在 → ELEMENT_NOT_FOUND；且裸 'navigatetab' 类名不误伤导航分类")
    void messageElementNotFoundNoNavigationFalsePositive() {
        assertThat(ErrorClassifier.classify("element with class 'navigatetab' is not found", null, null))
                .isEqualTo(ErrorClassifier.Category.ELEMENT_NOT_FOUND);
    }

    @Test
    @DisplayName("连接/协议级异常 → DRIVER_INFRA")
    void messageDriverInfra() {
        assertThat(ErrorClassifier.classify("Connection refused: localhost:9222", null, null))
                .isEqualTo(ErrorClassifier.Category.DRIVER_INFRA);
    }

    // ---- 3) 语义类别兜底 ----

    @Test
    @DisplayName("无类名无关键词：errorType=FAILURE → ASSERTION（兼容旧 failureTypeOf 兜底）")
    void fallbackFailureSemantic() {
        assertThat(ErrorClassifier.classify("expected status 200 but was 401", null, "FAILURE"))
                .isEqualTo(ErrorClassifier.Category.ASSERTION);
    }

    @Test
    @DisplayName("无类名无关键词：errorType=COMPROMISED → DRIVER_INFRA（环境/基础设施）")
    void fallbackCompromisedSemantic() {
        assertThat(ErrorClassifier.classify("page load aborted", null, "COMPROMISED"))
                .isEqualTo(ErrorClassifier.Category.DRIVER_INFRA);
    }

    // ---- 4) 边界与展示名 ----

    @Test
    @DisplayName("全空输入 → UNKNOWN；null outcome → UNKNOWN")
    void unknownsAndNulls() {
        assertThat(ErrorClassifier.classify(null, null, null)).isEqualTo(ErrorClassifier.Category.UNKNOWN);
        assertThat(ErrorClassifier.classify(null)).isEqualTo(ErrorClassifier.Category.UNKNOWN);
        assertThat(ErrorClassifier.classify("element is not visible", "java.lang.RuntimeException", null))
                .isEqualTo(ErrorClassifier.Category.UNKNOWN);
    }

    @Test
    @DisplayName("displayLabel：内置枚举美化，配置自定义类型原样，空值回退 Unknown")
    void displayLabels() {
        assertThat(ErrorClassifier.displayLabel("TIMEOUT")).isEqualTo("Timeout");
        assertThat(ErrorClassifier.displayLabel("Navigation loss")).isEqualTo("Navigation loss");
        assertThat(ErrorClassifier.displayLabel("AuthFailure")).isEqualTo("AuthFailure");
        assertThat(ErrorClassifier.displayLabel(null)).isEqualTo("Unknown");
        assertThat(ErrorClassifier.displayLabel("  ")).isEqualTo("Unknown");
    }

    @Test
    @DisplayName("TestOutcome 入口：失败用例走完整判定链（fixture 口径）")
    void classifyOutcome() {
        TestOutcome o = new TestOutcome();
        o.setResult(TestResult.FAILURE);
        o.setTestFailureCause(new FailureCause("expected status 200 but was 401", "FAILURE"));
        o.setTestFailureClassname("java.lang.AssertionError");
        assertThat(ErrorClassifier.classify(o)).isEqualTo(ErrorClassifier.Category.ASSERTION);
    }
}
