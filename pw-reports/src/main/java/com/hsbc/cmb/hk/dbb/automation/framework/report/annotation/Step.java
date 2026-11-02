package com.hsbc.cmb.hk.dbb.automation.framework.report.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 步骤注解——方法进出成为报告中的步骤边界。
 *
 * <p>框架自有契约（{@code com.hsbc...report.annotation.Step}），由步骤调度器 / ByteBuddy
 * 代理按注解类型名拦截并记录 TestOutcome，与任何第三方供应商无关。
 *
 * <p>{@code value} 为步骤描述；为空时使用方法名。
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Step {

    /** 步骤描述；为空时使用方法名。 */
    String value() default "";
}
