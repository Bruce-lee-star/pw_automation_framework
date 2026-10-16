package com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.serenity;

import net.thucydides.core.steps.BaseStepListener;
import net.thucydides.core.steps.StepEventBus;
import net.thucydides.model.domain.TestOutcome;
import net.thucydides.model.domain.TestResult;
import net.thucydides.model.domain.stacktrace.FailureCause;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.EnumSet;
import java.util.Set;

/**
 * 校验当前线程 Serenity outcome：若业务捕获并吞掉了 {@code @Step} 代理步骤抛出的异常
 * （JUnit / Cucumber 收不到未处理异常 → IDEA 显示绿色），但 Serenity 已记录
 * {@link TestResult#FAILURE} / {@link TestResult#ERROR} / {@link TestResult#COMPROMISED}
 * （→ 报告与日志显示红色），则抛出 {@link SerenityOutcomeMismatchException}，
 * 使运行结果（IDEA / JUnit）与 Serenity 报告保持一致，原始异常作为 cause 保留。
 *
 * <p><b>触发位置</b>：由 {@link SerenityOutcomeBackendProvider} 经 Cucumber {@code BackendProviderService}
 * SPI 注册的 After 钩子（order = {@code Integer.MAX_VALUE}，在常规清理之后执行）在 scenario 结束时调用。</p>
 *
 * <p><b>非 Serenity 环境</b>（总线不可用 / 无 base step listener / 不含 Selenium 的 classpath）
 * 安全降级为 no-op，绝不向上抛（与 {@code SerenityBusBridge} 一致）。</p>
 *
 * <p><b>设计铁律</b>：只读校验、不修改 Serenity 状态；线程语义为当前业务线程
 * （{@code StepEventBus} 为 ThreadLocal 单例）。</p>
 *
 * <p><b>关闭阀</b>：可通过 {@code -Dserenity.outcome.verifier.disabled=true} 临时关闭（安全降级），
 * 便于在批量修复业务吞异常期间不阻断 CI。</p>
 */
public final class SerenityOutcomeVerifier {

    private static final Logger LOGGER = LoggerFactory.getLogger(SerenityOutcomeVerifier.class);

    private static final Set<TestResult> FAILED_STATUSES =
            EnumSet.of(TestResult.FAILURE, TestResult.ERROR, TestResult.COMPROMISED);

    private static final String DISABLE_PROPERTY = "serenity.outcome.verifier.disabled";

    private SerenityOutcomeVerifier() {
    }

    /**
     * 校验当前线程的 Serenity outcome；若已记录失败 / 错误 / 受损则抛出（原始异常作为 cause）。
     *
     * <p>非 Serenity 环境，或已通过 {@code -Dserenity.outcome.verifier.disabled=true} 关闭时，安全降级为 no-op。</p>
     */
    public static void verifyCurrentThreadOutcome() {
        if (Boolean.getBoolean(DISABLE_PROPERTY)) {
            return;
        }
        TestOutcome outcome = safeCurrentOutcome();
        if (outcome == null) {
            return;
        }
        verifyOutcome(outcome);
    }

    /**
     * 对给定 outcome 做失败判定并抛出（若有）。包级可见，便于单测隔离校验逻辑。
     */
    static void verifyOutcome(TestOutcome outcome) {
        if (outcome == null) {
            return;
        }
        TestResult result = outcome.getResult();
        if (result == null || !FAILED_STATUSES.contains(result)) {
            return;
        }
        Throwable original = safeOriginalCause(outcome);
        String message = "Serenity recorded a " + result + " on the current thread, but the Cucumber/JUnit "
                + "scenario passed — the @Step exception was swallowed by business code. Failing the run to stay "
                + "consistent with the Serenity report (original exception preserved as cause).";
        throw new SerenityOutcomeMismatchException(message, original);
    }

    private static TestOutcome safeCurrentOutcome() {
        try {
            StepEventBus bus = StepEventBus.getEventBus();
            if (bus == null || !bus.isBaseStepListenerRegistered()) {
                return null;
            }
            BaseStepListener listener = bus.getBaseStepListener();
            if (listener == null) {
                return null;
            }
            return listener.getCurrentTestOutcome();
        } catch (Throwable t) {
            // 非 Serenity 环境（如不含 Selenium 的 classpath 上 isBaseStepListenerRegistered 会触发 LinkageError）
            LOGGER.debug("[SerenityOutcomeVerifier] outcome check skipped (no Serenity bus available): {}",
                    t.getMessage());
            return null;
        }
    }

    private static Throwable safeOriginalCause(TestOutcome outcome) {
        try {
            FailureCause failureCause = outcome.getTestFailureCause();
            if (failureCause == null) {
                return null;
            }
            Throwable original = failureCause.getOriginalCause();
            return original != null ? original : null;
        } catch (Throwable t) {
            LOGGER.debug("[SerenityOutcomeVerifier] original cause extraction skipped: {}", t.getMessage());
            return null;
        }
    }
}
