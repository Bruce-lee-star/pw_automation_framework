package com.hsbc.cmb.hk.dbb.automation.framework.report.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 退出标记：标注在步骤库类上，跳过 ByteBuddy 子类代理（直通，零开销）。
 *
 * <p>适用场景：已自带埋点/不需要步骤边界的工具类、或代理会导致问题的类
 * （如被 final 修饰的类会自动降级并告警，无需显式标注）。
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface NoStepInterception {
}
