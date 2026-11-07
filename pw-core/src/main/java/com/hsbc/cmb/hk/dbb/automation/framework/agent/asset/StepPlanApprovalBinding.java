package com.hsbc.cmb.hk.dbb.automation.framework.agent.asset;

import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.VerifiableStepPlan;

/**
 * <b>StepPlan 执行域的审批绑定</b>（T8 / I-07）：把「被批准的那份内容」压成指纹，在执行前逐字核对。
 *
 * <p>本类是审批绑定的<b>单一事实来源</b>：{@link AssetRepository} 的审批动作调用
 * {@link #digestOf(AssetType, String)} 记录摘要，{@code AssetStepPlanExecutor.run} 调用
 * {@link #verify(AssetId, String, String)} 校验，二者共用同一份反算逻辑，杜绝三处口径漂移。</p>
 *
 * <h2>两套摘要的语义边界（评审 D4 勘误，务必区分）</h2>
 * <ul>
 *   <li><b>执行域</b>：{@code StepPlan.planDigest()}，前缀 {@code stepplan-v1}，覆盖
 *       schemaVersion + 逐步 action/target/参数 + 逐步与 plan 级 scope。由本类用于
 *       {@code STEP_PLAN} 资产的审批绑定校验。</li>
 *   <li><b>定位器回注域</b>：{@code LocatorCandidate.planDigestOf()}（pw-codegen），前缀
 *       {@code locator-v1}，覆盖 targetRef/spec/expectedRole。由 {@code ApprovalGrant.covers()}
 *       在定位器回注时比对。</li>
 * </ul>
 * <p>二者<b>前缀不同、覆盖域不同、消费方不同，互不派生、不可混用</b>：本类只认执行域摘要，
 * 不引用也不改写定位器回注域的 {@code ApprovalGrant}，因此定位器回注域的
 * {@code ApprovalGrant.covers()} 行为不受本次整改影响。</p>
 *
 * <h2>为什么异常要收敛成 {@code null}</h2>
 * <p>落库内容是 {@link VerifiableStepPlan} JSON，反算摘要需先重建计划；若内容损坏或结构不完整
 * （例如 {@code stepScopes} 与 {@code steps} 不等长），{@code planDigest()} 会抛运行时异常。
 * 审批动作不应因「算不出指纹」而崩溃，故此处返回 {@code null} 表示<b>不做绑定</b>；
 * 而对<b>已绑定</b>资产，{@link #verify} 遇到当前内容算不出摘要时按
 * <b>fail-closed 拒绝执行</b>，不会把损坏内容放行。</p>
 */
public final class StepPlanApprovalBinding {

    private StepPlanApprovalBinding() {
    }

    /**
     * 从落库 {@code VerifiableStepPlan} JSON 反算执行域摘要。
     *
     * @return 摘要（16 位十六进制）；内容为空、损坏或结构不完整时返回 {@code null}（不做绑定）
     */
    public static String digestOf(String contentJson) {
        if (contentJson == null || contentJson.isBlank()) {
            return null;
        }
        try {
            VerifiableStepPlan vsp = VerifiableStepPlan.fromJson(contentJson);
            return (vsp == null || vsp.plan() == null) ? null : vsp.plan().planDigest();
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * 按资产类型计算审批绑定摘要：仅 {@link AssetType#STEP_PLAN} 参与绑定，其它类型返回 {@code null}。
     */
    public static String digestOf(AssetType type, String contentJson) {
        return type == AssetType.STEP_PLAN ? digestOf(contentJson) : null;
    }

    /**
     * 执行前置校验（fail-closed）。
     *
     * @param id             资产标识（进错误信息，便于定位）
     * @param approvedDigest 审批通过时记录的摘要；{@code null} 表示资产未审批，直接放行（保留既有行为）
     * @param contentJson    本次即将执行的落库内容
     * @throws IllegalStateException 当前内容摘要与已审批摘要不一致，或当前内容无法反算摘要
     */
    public static void verify(AssetId id, String approvedDigest, String contentJson) {
        if (approvedDigest == null) {
            return;
        }
        String currentDigest = digestOf(contentJson);
        if (currentDigest == null || !approvedDigest.equalsIgnoreCase(currentDigest)) {
            throw new IllegalStateException(
                    "StepPlan 内容摘要与已审批摘要不一致，拒绝执行（疑似审批后内容被篡改）："
                            + "approved=" + approvedDigest + ", current=" + currentDigest
                            + " (" + id + ")");
        }
    }
}
