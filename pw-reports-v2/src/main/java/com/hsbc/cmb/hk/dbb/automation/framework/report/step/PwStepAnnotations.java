package com.hsbc.cmb.hk.dbb.automation.framework.report.step;

import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.pool.TypePool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.annotation.Annotation;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * 框架自有步骤注解的名匹配（{@code @Step} / {@code @Steps}）。
 *
 * <p>按注解<b>类型名</b>（FQN）匹配框架自有注解
 * {@code com.hsbc...report.annotation.Step} / {@code ...Steps}——框架即这些注解的<b>唯一</b>
 * 提供者（无第三方同 FQN 副本），故无需 split-brain 防护。
 */
final class PwStepAnnotations {

    private static final Logger log = LoggerFactory.getLogger(PwStepAnnotations.class);

    static final String STEP_FQN = "com.hsbc.cmb.hk.dbb.automation.framework.report.annotation.Step";
    static final String STEPS_FQN = "com.hsbc.cmb.hk.dbb.automation.framework.report.annotation.Steps";

    private PwStepAnnotations() {
    }

    static boolean hasStepAnnotation(Method method) {
        return hasAnnotationByName(method, STEP_FQN);
    }

    static boolean hasStepsAnnotation(Field field) {
        return hasAnnotationByName(field, STEPS_FQN);
    }

    private static boolean hasAnnotationByName(AnnotatedElement element, String fqn) {
        for (Annotation a : element.getAnnotations()) {
            if (fqn.equals(a.annotationType().getName())) {
                return true;
            }
        }
        return false;
    }

    /** 读 {@code shared} 成员（@Steps 的共享开关）。缺失/异常 → false。 */
    static boolean readShared(Field field) {
        for (Annotation a : field.getAnnotations()) {
            if (!STEPS_FQN.equals(a.annotationType().getName())) {
                continue;
            }
            try {
                Object value = a.annotationType().getMethod("shared").invoke(a);
                return Boolean.TRUE.equals(value);
            } catch (ReflectiveOperationException e) {
                return false;
            }
        }
        return false;
    }

    /** 读 {@code value} 成员（@Step 描述）；空/异常 → null。 */
    static String readStepValue(Method method) {
        for (Annotation a : method.getAnnotations()) {
            if (!STEP_FQN.equals(a.annotationType().getName())) {
                continue;
            }
            try {
                Object value = a.annotationType().getMethod("value").invoke(a);
                if (value instanceof String s && !s.isBlank()) {
                    return s;
                }
            } catch (ReflectiveOperationException e) {
                return null;
            }
        }
        return null;
    }

    /**
     * 步骤描述：@Step 的 {@code value} 或方法名。
     * 由 {@code PwStepDispatcher} 消费——<b>不放 dispatcher 上</b>：dispatcher 的
     * public 静态方法会进入 ByteBuddy 委派候选集，可能造成 ambiguous delegation。
     */
    static String stepDescription(Method method) {
        String value = readStepValue(method);
        return value != null ? value : method.getName();
    }

    /**
     * ByteBuddy 匹配用的注解 TypeDescription——从 classloader 解析框架自有 @Step。
     */
    static List<TypeDescription> stepAnnotationTypes(ClassLoader classLoader) {
        TypePool pool = TypePool.Default.of(classLoader);
        List<TypeDescription> out = new ArrayList<>();
        try {
            TypePool.Resolution resolution = pool.describe(STEP_FQN);
            if (resolution != null) {
                out.add(resolution.resolve());
            }
        } catch (Throwable t) {
            log.debug("cannot resolve annotation type {} in {}: {}", STEP_FQN, classLoader, t.toString());
        }
        return out;
    }
}
