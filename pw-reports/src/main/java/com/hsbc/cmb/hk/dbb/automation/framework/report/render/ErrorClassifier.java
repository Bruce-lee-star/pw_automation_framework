package com.hsbc.cmb.hk.dbb.automation.framework.report.render;

import com.hsbc.cmb.hk.dbb.automation.framework.report.model.TestOutcome;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 失败分类器（报告口径）：把一条失败归入稳定的「错误类型」标签，供邮件/站点报告
 * 明细行与聚合榜共用同一判定链。
 *
 * <p><b>判定优先级（金融级健壮：结构化信号 &gt; 消息证据 &gt; 语义兜底）</b>：
 * <ol>
 *   <li><b>异常类名</b>——最可靠信号（Playwright 结构化子类如 {@code TimeoutError}、
 *       JUnit {@code AssertionError}、{@code NoSuchElementException}），任何消息措辞都不得覆盖；</li>
 *   <li><b>消息精确短语</b>——预编译 Pattern、大小写不敏感；只匹配有把握的完整语义短语
 *       （如 {@code execution context ... destroyed}），不再用裸子串（裸 {@code "navigation"}
 *       会误伤 {@code class='navigatetab'} 这类报文，见 {@code ElementOperationSupportTest}
 *       收窄先例）；</li>
 *   <li><b>语义类别兜底</b>——{@code testFailureCause.errorType}（FAILURE / ERROR / COMPROMISED），
 *       与旧 {@code failureTypeOf} 行为兼容；</li>
 *   <li>{@code UNKNOWN}。</li>
 * </ol>
 *
 * <p>纯字符串实现，不依赖 Playwright 类型——pw-reports 保持模块独立性，任何引擎/框架
 * （Java Playwright / Selenium / 纯业务断言）的失败都能分类。</p>
 */
public final class ErrorClassifier {

    /** 内置分类。key 为稳定标识（模板/统计用），label 为展示名。 */
    public enum Category {
        TIMEOUT("Timeout"),
        NAVIGATION_LOSS("Navigation loss"),
        CONTEXT_CLOSED("Context closed"),
        FRAME_DETACHED("Frame detached"),
        ELEMENT_NOT_FOUND("Element not found"),
        ASSERTION("Assertion failure"),
        DRIVER_INFRA("Driver / infra"),
        UNKNOWN("Unknown");

        private final String label;

        Category(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    private ErrorClassifier() {
    }

    //  结构化类名信号：简名 + 全名均小写参与匹配（key 判定用）
    private static final Pattern CLASS_TIMEOUT = Pattern.compile("timeouterror");
    private static final Pattern CLASS_ASSERTION = Pattern.compile("assertionerror|assertionfailederror");
    private static final Pattern CLASS_ELEMENT_NOT_FOUND =
            Pattern.compile("nosuchelement|notfoundexception|elementnotfound");

    //  消息精确短语（大小写不敏感；按判定顺序逐个尝试）
    private static final Pattern MSG_TIMEOUT =
            Pattern.compile("timeout.{0,40}(exceeded|expired|timed out)|timed out");
    private static final Pattern MSG_NAVIGATION_LOSS = Pattern.compile(
            "execution context.{0,60}destroyed|context was destroyed|object doesn't exist"
                    + "|navigating to|during navigation|navigation.{0,40}(failed|interrupted)"
                    + "|page was closed");
    private static final Pattern MSG_CONTEXT_CLOSED = Pattern.compile(
            "has been closed|target page.{0,40}closed|context is closed|browser has closed");
    private static final Pattern MSG_FRAME_DETACHED = Pattern.compile("frame (was )?detached");
    private static final Pattern MSG_ELEMENT_NOT_FOUND = Pattern.compile(
            "element.{0,40}not found|no such element|locator.{0,40}not found");
    private static final Pattern MSG_DRIVER_INFRA = Pattern.compile(
            "connection refused|websocket.{0,40}(error|closed|failed)|internal server error");

    /**
     * 分类一条用例失败（渲染期调用）。失败判断（isFailure）由调用方保证，本方法对任何
     * outcome 都返回稳定分类（成功用例无意义，但也不会抛）。
     *
     * @param o 用例结果；null 安全
     * @return 分类 key 对应枚举（永不返回 null）
     */
    public static Category classify(TestOutcome o) {
        if (o == null) {
            return Category.UNKNOWN;
        }
        String message = o.getTestFailureCause() != null ? o.getTestFailureCause().getMessage() : null;
        if (message == null) {
            message = o.getTestFailureMessage();
        }
        String classname = o.getTestFailureClassname();
        String errorType = o.getTestFailureCause() == null ? null : o.getTestFailureCause().getErrorType();
        return classify(message, classname, errorType);
    }

    /**
     * 分类（纯数据入口，测试用）。
     *
     * @param message   失败首行消息（可 null）
     * @param classname 异常全限定名（可 null）
     * @param errorType 语义类别 FAILURE / ERROR / COMPROMISED（可 null）
     */
    public static Category classify(String message, String classname, String errorType) {
        // 1) 异常类名（结构化信号，最高优先级）
        String cls = classname == null ? "" : classname.toLowerCase(Locale.ROOT);
        if (CLASS_TIMEOUT.matcher(cls).find()) {
            return Category.TIMEOUT;
        }
        if (CLASS_ASSERTION.matcher(cls).find()) {
            return Category.ASSERTION;
        }
        if (CLASS_ELEMENT_NOT_FOUND.matcher(cls).find()) {
            return Category.ELEMENT_NOT_FOUND;
        }
        // 2) 消息精确短语（按判定顺序：先超时，再导航/上下文，再元素）
        String msg = message == null ? "" : message.toLowerCase(Locale.ROOT);
        if (MSG_TIMEOUT.matcher(msg).find()) {
            return Category.TIMEOUT;
        }
        if (MSG_NAVIGATION_LOSS.matcher(msg).find()) {
            return Category.NAVIGATION_LOSS;
        }
        if (MSG_CONTEXT_CLOSED.matcher(msg).find()) {
            return Category.CONTEXT_CLOSED;
        }
        if (MSG_FRAME_DETACHED.matcher(msg).find()) {
            return Category.FRAME_DETACHED;
        }
        if (MSG_ELEMENT_NOT_FOUND.matcher(msg).find()) {
            return Category.ELEMENT_NOT_FOUND;
        }
        if (MSG_DRIVER_INFRA.matcher(msg).find()) {
            return Category.DRIVER_INFRA;
        }
        // 3) 语义类别兜底（兼容旧 failureTypeOf 行为；ERROR 即"未归类的意外错误"→ UNKNOWN）
        if (errorType != null && !errorType.isBlank()) {
            return switch (errorType.toUpperCase(Locale.ROOT)) {
                case "FAILURE" -> Category.ASSERTION;
                case "COMPROMISED" -> Category.DRIVER_INFRA;
                default -> Category.UNKNOWN;
            };
        }
        return Category.UNKNOWN;
    }

    /** key → 展示名：内置枚举走 label；外部自定义类型名（report.error.types 配置）原样返回。 */
    public static String displayLabel(String failureType) {
        if (failureType == null || failureType.isBlank()) {
            return Category.UNKNOWN.label();
        }
        for (Category c : Category.values()) {
            if (c.name().equalsIgnoreCase(failureType)) {
                return c.label();
            }
        }
        return failureType;
    }
}
