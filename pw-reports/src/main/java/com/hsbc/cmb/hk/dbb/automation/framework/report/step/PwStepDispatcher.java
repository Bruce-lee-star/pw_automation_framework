package com.hsbc.cmb.hk.dbb.automation.framework.report.step;

import com.hsbc.cmb.hk.dbb.automation.framework.report.event.ReportBus;
import com.hsbc.cmb.hk.dbb.automation.framework.report.event.StepHandle;
import com.hsbc.cmb.hk.dbb.automation.framework.report.model.TestResult;
import net.bytebuddy.implementation.bind.annotation.Origin;
import net.bytebuddy.implementation.bind.annotation.RuntimeType;
import net.bytebuddy.implementation.bind.annotation.SuperCall;

import java.lang.reflect.Method;
import java.util.concurrent.Callable;

/**
 * 静态分发目标——对应 Serenity {@code StepInterceptor.intercept} 的核心语义
 * （方法进出 → 步骤边界；异常 rethrow 不变式）。
 *
 * <p>不变式：
 * <ul>
 *   <li><b>rethrow</b>：业务异常原样抛给调用方——报告永不吞业务异常（与
 *       RouteAssertionFailure 捕获策略一致）；</li>
 *   <li><b>零开销直通</b>：总线未激活（{@code pw.report.enabled=false}）时直接
 *       {@code superCall.call()}，不做任何记录（D4 同款）；</li>
 *   <li>描述 = @Step 的 {@code value}（按名读取）或方法名；<b>不带参数值</b>
 *       （参数可能含敏感数据，脱敏闸门兜底但不主动采集）。</li>
 * </ul>
 *
 * <p><b>ByteBuddy 绑定契约（实证，勿改）</b>：{@code @RuntimeType} 是必需的——
 * 没有它，带返回值/参数的 @Step 方法（如 {@code String echo(String)}）无法绑定到
 * 本方法（ByteBuddy 对非 void 拦截方法要求目标返回类型可被 @RuntimeType 放宽），
 * 表现为整个步骤库代理创建失败、静默降级为无拦截。{@code Callable<?>} 配
 * {@code @RuntimeType} 可同时覆盖 void 与有返回值方法（探针实证）。
 *
 * <p>本类只保留 {@code intercept} 一个可绑定方法——额外的 public 静态方法会进入
 * ByteBuddy 的委派候选集，某些签名下造成 ambiguous delegation。
 */
public final class PwStepDispatcher {

    private PwStepDispatcher() {
    }

    @RuntimeType
    public static Object intercept(@Origin Method method, @SuperCall Callable<?> superCall) throws Throwable {
        ReportBus bus = ReportBus.getDefault();
        if (!bus.isEnabled()) {
            return superCall.call(); // kill-switch 直通，零开销
        }
        StepHandle handle = bus.stepStarted(PwStepAnnotations.stepDescription(method));
        try {
            Object result = superCall.call();
            bus.stepFinished(handle, TestResult.SUCCESS, null);
            return result;
        } catch (Throwable t) {
            bus.stepFinished(handle, TestResult.FAILURE, t);
            throw t;
        }
    }
}
