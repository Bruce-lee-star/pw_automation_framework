package com.hsbc.cmb.hk.dbb.automation.framework.report.analysis;

import java.util.Locale;
import java.util.Set;

/**
 * 失败分析——对齐 Serenity {@code FailureAnalysis} 的核心能力：把任意 {@link Throwable}
 * 归类为三种语义错误类型之一（FAILURE / ERROR / COMPROMISED），使报告按"类别"而非"异常类名"
 * 分组展示（报告模型 {@code FailureCause.errorType} 即承载此语义类别，类名由
 * {@code TestOutcome.testFailureClassname} 单独立项兜底）。
 *
 * <p>分类规则（与 Serenity 一致，按序短路）：
 * <ol>
 *   <li>{@link AssertionError} 及其子类（含框架 {@code FrameworkAssertionError}、Playwright
 *       {@code expect} 失败抛出的 {@code AssertionError}）→ <b>FAILURE</b>：被测系统未达预期；</li>
 *   <li>已知"环境 / 基础设施 / 步骤库"异常（简单类名在 {@link #COMPROMISED_TYPES}）、或其 cause 链命中、
 *       或消息命中 {@link #COMPROMISED_MESSAGE_KEYWORDS}（如浏览器启动 / 连接失败）→
 *       <b>COMPROMISED</b>：测试无法被可靠验证，结论不可信；</li>
 *   <li>其余 → <b>ERROR</b>：非断言的意外异常（真实缺陷 / 未预期错误）。</li>
 * </ol>
 *
 * <p><b>零耦合约束</b>：类名以字符串匹配，刻意不 import web / route / cucumber 的实现类，
 * 保持报告模块与具体引擎实现解耦；分类集合属框架可调优项，集中在常量里，便于按项目实情增删。</p>
 */
public final class FailureAnalysis {

    /** Serenity 式三类语义错误（JSON 取值与 Serenity 对齐）。 */
    public enum ErrorType {
        /** 断言失败：被测系统未达预期。 */
        FAILURE,
        /** 意外异常：非断言、非环境受损的真实异常。 */
        ERROR,
        /** 测试受损：环境 / 基础设施 / 步骤库问题导致无法可靠验证。 */
        COMPROMISED
    }

    /** 已知"环境 / 基础设施 / 步骤库"异常简单类名——命中即 {@link ErrorType#COMPROMISED}。 */
    public static final Set<String> COMPROMISED_TYPES = Set.of(
            // 步骤库 / Cucumber 接线（步骤无法可靠执行）
            "CucumberException", "AmbiguousStepDefinitionsException", "DuplicateStepDefinitionException",
            "UndefinedStepException", "PendingException", "TestPendingException",
            // 浏览器 / 页面交互与传输（环境受损，结论不可靠）
            "PlaywrightException", "TimeoutException", "NoSuchElementException",
            "ElementNotInteractableException", "ElementNotFoundException", "StaleElementReferenceException",
            "TargetClosedError", "WebDriverException");

    /** 消息关键字——命中即 {@link ErrorType#COMPROMISED}（兜底捕获以通用异常形式抛出的启动 / 连接失败）。 */
    public static final Set<String> COMPROMISED_MESSAGE_KEYWORDS = Set.of(
            "failed to launch", "browser has been closed", "targetclosed",
            "connection refused", "could not be started", "executable doesn't exist");

    private final ErrorType errorType;
    private final String message;

    private FailureAnalysis(ErrorType errorType, String message) {
        this.errorType = errorType;
        this.message = message;
    }

    public ErrorType getErrorType() {
        return errorType;
    }

    public String getMessage() {
        return message;
    }

    /**
     * 分析失败类型。
     *
     * @param t 失败异常；{@code null} 返回 {@link ErrorType#ERROR}（无有效根因，按非断言意外处理）
     */
    public static FailureAnalysis analyse(Throwable t) {
        if (t == null) {
            return new FailureAnalysis(ErrorType.ERROR, null);
        }
        if (t instanceof AssertionError) {
            return new FailureAnalysis(ErrorType.FAILURE, firstMessage(t));
        }
        if (isCompromised(t)) {
            return new FailureAnalysis(ErrorType.COMPROMISED, firstMessage(t));
        }
        return new FailureAnalysis(ErrorType.ERROR, firstMessage(t));
    }

    private static boolean isCompromised(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (COMPROMISED_TYPES.contains(c.getClass().getSimpleName())) {
                return true;
            }
            String msg = c.getMessage();
            if (msg != null) {
                String lower = msg.toLowerCase(Locale.ROOT);
                for (String kw : COMPROMISED_MESSAGE_KEYWORDS) {
                    if (lower.contains(kw)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static String firstMessage(Throwable t) {
        String msg = t.getMessage();
        if (msg == null || msg.isEmpty()) {
            msg = t.getClass().getName();
        }
        return msg;
    }
}
