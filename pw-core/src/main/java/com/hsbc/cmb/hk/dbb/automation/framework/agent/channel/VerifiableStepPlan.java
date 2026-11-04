package com.hsbc.cmb.hk.dbb.automation.framework.agent.channel;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.JsonProcessingException;

import java.util.List;
import java.util.Objects;

/**
 * 可验证步骤计划载体（闭环引擎 × 资产生产流水线的数据契约，无浏览器依赖）。
 *
 * <p>把「执行计划 {@link StepPlan}」「目标页面引用」打包——执行后期望已内联进 {@link StepPlan}
 * 的各命令（每步 = 动作 + 其期望后态，见 {@link Command#expectations()}），消除「计划 + 独立期望
 * 列表按索引对齐」的双轨耦合。资产生产层（pw-agent-analysis）产出的载体可被执行层（pw-web-ui 的
 * {@code StepPlanRunner}）直接消费做真实浏览器验证——数据经 pw-core 流动，
 * 避免分析层反向依赖 web-ui、把浏览器拉进分析层。</p>
 *
 * <p><b>往返</b>：{@link #fromJson(String)} 是 {@code AssetPipelineOrchestrator.toJson(vsp)} 的逆操作，
 * 与生产流水线<b>同一 Jackson 配置</b>（FIELD 可见 / GETTER 不可见），使落库的 {@code STEP_PLAN} 资产
 * 可被生产侧读回并执行——闭环在生产侧真正闭合。</p>
 *
 * @param plan    执行计划（含参数范围治理域与内联的逐步期望）
 * @param pageRef 目标页面引用（喂给隔离靶场 harness 的导航目标）
 */
public record VerifiableStepPlan(StepPlan plan, String pageRef) {

    /** 派生逐步执行后期望（与 {@code plan.steps()} 对齐；命令无期望则为空列表）。 */
    public List<List<SnapshotExpectation>> expectationsPerStep() {
        return plan.steps().stream()
                .map(Command::expectations)
                .toList();
    }

    /** 从落库 JSON 重建（{@code AssetPipelineOrchestrator} 写入配置的逆操作）。 */
    public static VerifiableStepPlan fromJson(String json) {
        Objects.requireNonNull(json, "json");
        try {
            ObjectMapper mapper = new ObjectMapper()
                    .setVisibility(PropertyAccessor.FIELD, JsonAutoDetect.Visibility.ANY)
                    .setVisibility(PropertyAccessor.GETTER, JsonAutoDetect.Visibility.NONE);
            JsonNode root = mapper.readTree(json);
            StepPlan plan = mapper.treeToValue(root.get("plan"), StepPlan.class);
            String pageRef = root.has("pageRef") ? root.get("pageRef").asText() : null;
            return new VerifiableStepPlan(plan, pageRef);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("VerifiableStepPlan 反序列化失败: " + e.getMessage(), e);
        }
    }
}
