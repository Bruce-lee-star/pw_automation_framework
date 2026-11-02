package com.hsbc.cmb.hk.dbb.automation.framework.report.event;

/**
 * 步骤句柄——跨线程证据挂靠的显式凭据（R2）。
 *
 * <p>线程不变式：同一 scenario 的 hook/step/glue 在同线程顺序执行；并行 = 不同 scenario
 * 在不同线程。因此：
 * <ul>
 *   <li><b>场景线程内</b>可用零参便捷 API（{@code ReportBus.attachEvidence(String)}，
 *       ThreadLocal 解析当前句柄）；</li>
 *   <li><b>进入异步前</b>必须 {@code StepHandle h = ReportBus.captureHandle()}，
 *       回调内用 {@code ReportBus.attachEvidence(StepHandle, String)}；</li>
 *   <li><b>禁止</b>在异步线程调零参版本（ThreadLocal 解析不到或解析错）。</li>
 * </ul>
 *
 * <p>不可变 record；{@link #NOOP} 为总线未激活/无当前用例时的空操作句柄，
 * 向其挂载证据是静默 no-op（不抛、不记）。
 */
public record StepHandle(String outcomeId, String stepId, StepHandle parent) {

    /** 空操作句柄（总线未激活或调用点不在任何用例内）。 */
    public static final StepHandle NOOP = new StepHandle("", null, null);

    public StepHandle {
        if (outcomeId == null) {
            throw new IllegalArgumentException("outcomeId must not be null");
        }
    }

    /** 用例级句柄（无当前步骤）。 */
    public static StepHandle ofCase(String outcomeId) {
        return new StepHandle(outcomeId, null, null);
    }

    /** 步骤级句柄。 */
    public static StepHandle ofStep(String outcomeId, String stepId, StepHandle parent) {
        return new StepHandle(outcomeId, stepId, parent);
    }

    public boolean isNoop() {
        return outcomeId.isEmpty();
    }

    /** 是否指向用例级（无当前步骤）——证据挂到用例级列表。 */
    public boolean isCaseLevel() {
        return stepId == null;
    }
}
