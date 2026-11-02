package com.hsbc.cmb.hk.dbb.automation.framework.report.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 步骤库字段注入注解——标记 glue 类中需要被代理拦截的步骤库字段。
 *
 * <p>框架自有契约（{@code com.hsbc...report.annotation.Steps}）：
 * <ul>
 *   <li>{@code shared = false}（默认）：每个注入点独立实例；</li>
 *   <li>{@code shared = true}：同一步骤库类型全 glue 共享一个实例。</li>
 * </ul>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface Steps {

    /** 同类型步骤库是否共享实例。 */
    boolean shared() default false;
}
