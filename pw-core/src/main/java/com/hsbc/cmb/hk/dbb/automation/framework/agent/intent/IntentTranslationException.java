package com.hsbc.cmb.hk.dbb.automation.framework.agent.intent;

/**
 * 意图翻译拒绝信号（设计 §4.3 / §9 P1 验收：非法 schema、越权 Action、未知字段、不批准 origin 均被拒绝）。
 *
 * <p>所有拒绝都经本异常<b>显式</b>抛出并带 {@link Reason}，避免裸 NPE / 静默降级；
 * 调用方（录制骨架回退、LLM 建议接入）据此走确定性回退路径，不阻塞回归 CI（§4.6）。</p>
 */
public class IntentTranslationException extends RuntimeException {

    /** 拒绝原因（可观测、可断言）。 */
    public enum Reason {
        /** 输入 JSON / 结构不合规。 */
        INVALID_INPUT,
        /** 不支持或缺失 schemaVersion。 */
        UNSUPPORTED_SCHEMA,
        /** 含白名单外字段。 */
        UNKNOWN_FIELD,
        /** 未知 ActionType。 */
        UNKNOWN_ACTION,
        /** 动作不允许由录制骨架 / 模型建议产生（越权）。 */
        ACTION_NOT_ALLOWED,
        /** 参数键不在该 ActionType 的 schema 内，或必填参数缺失。 */
        PARAM_SCHEMA_VIOLATION,
        /** target 缺失 / 非法 canonical / NAVIGATE 误带 target。 */
        TARGET_VIOLATION,
        /** origin 未列入批准集合。 */
        UNAPPROVED_ORIGIN,
        /** 骨架无步骤或超过有界上限。 */
        BOUNDS_VIOLATION,
        /** 产出计划未通过 ParamScopePolicy 策略校验。 */
        POLICY_VIOLATION,
        /** 产出计划 schema / 可执行性不合规。 */
        PLAN_NOT_EXECUTABLE
    }

    private final Reason reason;

    public IntentTranslationException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public IntentTranslationException(Reason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
