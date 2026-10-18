package com.hsbc.cmb.hk.dbb.automation.framework.report.step;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

/**
 * {@code @Steps} 字段注入——平移 {@code injectScenarioStepsInto} 的最小充分子集。
 *
 * <p>规则（§16.4.5 边界表）：
 * <ul>
 *   <li>按注解<b>类型名</b>匹配框架自有 {@code @Steps}；</li>
 *   <li>幂等：字段已有值（业务显式赋值 / 已注入代理）→ 不覆盖；</li>
 *   <li>static / final 字段跳过（final 记 warn）；</li>
 *   <li>{@code shared=true} → 同类型在<b>当前线程</b>全 glue 共享一个
 *       代理实例（per-thread，每 Scenario 一个 StepFactory 语义）；false（默认）→ 每注入点
 *       独立实例；</li>
 *   <li>接口 / 抽象类字段类型无法代理 → warn 跳过（不炸）。</li>
 * </ul>
 */
public final class PwStepInjector {

    private static final Logger log = LoggerFactory.getLogger(PwStepInjector.class);

    private PwStepInjector() {
    }

    public static void injectInto(Object glueInstance) {
        if (glueInstance == null) {
            return;
        }
        Class<?> clazz = glueInstance.getClass();
        while (clazz != null && clazz != Object.class) {
            for (Field field : clazz.getDeclaredFields()) {
                injectField(glueInstance, field);
            }
            clazz = clazz.getSuperclass();
        }
    }

    private static void injectField(Object glueInstance, Field field) {
        // 框架自有 @Steps 命中即触发注入
        if (!PwStepAnnotations.hasStepsAnnotation(field)) {
            return;
        }
        int modifiers = field.getModifiers();
        if (Modifier.isStatic(modifiers)) {
            return;
        }
        try {
            field.setAccessible(true);
            if (field.get(glueInstance) != null) {
                return; // 幂等：不覆盖业务显式赋值 / 已注入代理
            }
            if (Modifier.isFinal(modifiers)) {
                log.warn("@Steps field {}.{} is final; injection skipped", glueInstance.getClass().getName(), field.getName());
                return;
            }
            Class<?> fieldType = field.getType();
            if (fieldType.isInterface() || Modifier.isAbstract(fieldType.getModifiers())) {
                log.warn("@Steps field {}.{} type {} is interface/abstract; cannot proxy, injection skipped",
                        glueInstance.getClass().getName(), field.getName(), fieldType.getName());
                return;
            }
            boolean shared = PwStepAnnotations.readShared(field);
            Object proxy = shared ? PwStepFactory.sharedProxyFor(fieldType) : PwStepFactory.proxyFor(fieldType);
            if (proxy == null) {
                return; // proxyFor 内部已 warn
            }
            field.set(glueInstance, proxy);
        } catch (Throwable t) {
            log.warn("@Steps injection failed for {}.{}: {}",
                    glueInstance.getClass().getName(), field.getName(), t.toString());
        }
    }
}
