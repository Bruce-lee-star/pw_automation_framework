package com.hsbc.cmb.hk.dbb.automation.framework.agent.channel;

import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.scope.ParamScope;

/**
 * 步骤级幂等上下文（T7 / I-05 落地）：基于稳定结构指纹（snapshotDigest）的记忆，
 * 使「同一计划重跑」可跳过此前已通过且命令内容未变的步骤。
 *
 * <p>跳过判定以<b>命令内容指纹</b>为准：若第 i 步的命令（action + target + 排序参数 + 该步 resolvedScope）
 * 与上次通过的记录一致，则视为已达成相同后态，直接复用缓存结果、不再驱动浏览器；任一命令内容变化
 * （即「变更步」）都会使指纹变化 → 该步重新执行，从而满足「重跑同一计划跳过 N-1 步，仅跑变更步」。</p>
 *
 * <p>缓存结果携带的是该步通过时的<b>后态快照指纹</b>（snapshotDigest）——这是「基于 snapshotDigest
 * 跳过已通过步骤」的落点：被跳过的步以该指纹作为通过证据；失败步骤的 diff 也以指纹 + 字段断言呈现。</p>
 */
public interface StepIdempotency {

    /** 若该步此前已通过且命令内容未变，返回其缓存的通过结果（含后态指纹）；否则 {@code null}（需执行）。 */
    CommandResult cachedPass(StepPlan plan, int index, Command command, ParamScope resolvedScope);

    /** 记录该步通过（含执行后快照指纹），供后续幂等跳过。 */
    void recordPass(StepPlan plan, int index, Command command, ParamScope resolvedScope, String postSnapshotDigest);

    /** 空实现：不做记忆，行为退化为「每次都执行」，零回归默认。 */
    static StepIdempotency none() {
        return new StepIdempotency() {
            @Override public CommandResult cachedPass(StepPlan p, int i, Command c, ParamScope s) { return null; }
            @Override public void recordPass(StepPlan p, int i, Command c, ParamScope s, String d) { }
        };
    }
}
