package com.hsbc.cmb.hk.dbb.automation.framework.report.analysis;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.NoSuchElementException;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link FailureAnalysis} 分类规则门禁——对齐 Serenity 的三类语义错误。
 * 不依赖 Playwright / Cucumber 具体实现类：以 JDK 类与"同名本地类"按简单名命中
 * {@link FailureAnalysis#COMPROMISED_TYPES}，覆盖真实分类集合的等价行为。
 */
class FailureAnalysisTest {

    /** 本地类：简单名 = "PlaywrightException"，等价命中 COMPROMISED_TYPES 的浏览器传输异常。 */
    static final class PlaywrightException extends RuntimeException {
        PlaywrightException(String msg) {
            super(msg);
        }
    }

    @Test
    @DisplayName("AssertionError 及其子类 → FAILURE（被测系统未达预期）")
    void assertionErrorIsFailure() {
        FailureAnalysis a = FailureAnalysis.analyse(new AssertionError("step boom"));
        assertThat(a.getErrorType()).isEqualTo(FailureAnalysis.ErrorType.FAILURE);
        assertThat(a.getMessage()).isEqualTo("step boom");
    }

    @Test
    @DisplayName("非断言意外异常 → ERROR（真实缺陷 / 未预期错误）")
    void unexpectedExceptionIsError() {
        assertThat(FailureAnalysis.analyse(new RuntimeException("boom")).getErrorType())
                .isEqualTo(FailureAnalysis.ErrorType.ERROR);
        assertThat(FailureAnalysis.analyse(new NullPointerException()).getErrorType())
                .isEqualTo(FailureAnalysis.ErrorType.ERROR);
    }

    @Test
    @DisplayName("已知环境/基础设施异常（类名）→ COMPROMISED")
    void infrastructureExceptionByClassNameIsCompromised() {
        assertThat(FailureAnalysis.analyse(new TimeoutException()).getErrorType())
                .isEqualTo(FailureAnalysis.ErrorType.COMPROMISED);
        assertThat(FailureAnalysis.analyse(new NoSuchElementException()).getErrorType())
                .isEqualTo(FailureAnalysis.ErrorType.COMPROMISED);
        // 以同名本地类命中"PlaywrightException"条目
        assertThat(FailureAnalysis.analyse(new PlaywrightException("pipe closed")).getErrorType())
                .isEqualTo(FailureAnalysis.ErrorType.COMPROMISED);
    }

    @Test
    @DisplayName("消息关键字命中（启动/连接失败兜底）→ COMPROMISED")
    void messageKeywordIsCompromised() {
        assertThat(FailureAnalysis.analyse(new RuntimeException("browser has been closed")).getErrorType())
                .isEqualTo(FailureAnalysis.ErrorType.COMPROMISED);
        assertThat(FailureAnalysis.analyse(new IllegalStateException("Failed to launch browser")).getErrorType())
                .isEqualTo(FailureAnalysis.ErrorType.COMPROMISED);
    }

    @Test
    @DisplayName("cause 链命中受损异常 → COMPROMISED（顶层虽是通用 RuntimeException）")
    void causeChainCompromised() {
        RuntimeException wrapped = new RuntimeException("wrap", new PlaywrightException("pipe closed"));
        assertThat(FailureAnalysis.analyse(wrapped).getErrorType())
                .isEqualTo(FailureAnalysis.ErrorType.COMPROMISED);
    }

    @Test
    @DisplayName("null 根因 → ERROR（无有效根因，按非断言意外处理）")
    void nullThrowableIsError() {
        assertThat(FailureAnalysis.analyse(null).getErrorType())
                .isEqualTo(FailureAnalysis.ErrorType.ERROR);
    }

    @Test
    @DisplayName("消息为空时回退为类名（不抛 NPE）")
    void blankMessageFallsBackToClassName() {
        FailureAnalysis a = FailureAnalysis.analyse(new RuntimeException());
        assertThat(a.getMessage()).isEqualTo(RuntimeException.class.getName());
        assertThat(a.getErrorType()).isEqualTo(FailureAnalysis.ErrorType.ERROR);
    }
}
