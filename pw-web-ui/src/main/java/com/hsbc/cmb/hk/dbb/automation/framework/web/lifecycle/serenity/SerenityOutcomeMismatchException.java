package com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.serenity;

/**
 * 当 Serenity 已记录 Failure / Error / Compromised，但 Cucumber / JUnit 场景却通过
 * （业务吞掉了 {@code @Step} 代理步骤抛出的异常）时抛出。
 *
 * <p>目的是消除"日志报红、但 IDEA / JUnit 显示绿色"的不一致：让运行结果（IDEA）与 Serenity 报告保持一致。
 * 被吞的原始异常作为 {@link #getCause()} 保留，便于定位真实失败。</p>
 */
public class SerenityOutcomeMismatchException extends RuntimeException {

    public SerenityOutcomeMismatchException(String message) {
        super(message);
    }

    public SerenityOutcomeMismatchException(String message, Throwable cause) {
        super(message, cause);
    }
}
