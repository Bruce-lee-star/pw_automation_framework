package com.hsbc.cmb.hk.dbb.automation.framework.agent.intent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.ActionParamSchema;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.Command;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * 把录制骨架组装成「可直接粘贴给 IDE 助手（Copilot / 同类）的提示词包」
 * （设计 §4.3 + §9 P1，落地形态：<b>框架不调用 LLM</b>）。
 *
 * <p><b>为什么不是 LLM 客户端</b>：使用场景是开发者本人用 IDE 里的助手做翻译，
 * 框架侧不发起任何网络调用、不持有凭据、数据只在开发者自己的 IDE 会话内流转。
 * 框架的职责边界是两件事：<b>把最小化上下文交出去</b>（本类）与
 * <b>把回来的建议当成不可信输入校验</b>（{@link SuggestionIntake}、{@link IntentPlanValidator}）。</p>
 *
 * <p><b>数据最小化</b>：默认<b>值占位化</b>（{@code FILL.value} / {@code SELECT.option} 替换为
 * {@code ${value}} / {@code ${option}}）——模型只需要「这里要填一个值」，不需要知道填什么；
 * 需要时显式 {@code redactValues=false} 关闭（仅在开发者确认无敏感业务数据时）。</p>
 *
 * <p><b>确定性</b>：允许动作表按动作名排序、骨架 JSON 沿用
 * {@link RecordedSkeleton#toJson()} 的固定字段顺序，故同一骨架恒产出同一提示词（可入 git/diff）。</p>
 *
 * <p>无状态、线程安全。</p>
 */
public final class SkeletonPromptBundle {

    /** 占位符：保留「此处有一个值」的结构信息，去掉业务数据。 */
    static final String VALUE_PLACEHOLDER = "${value}";
    static final String OPTION_PLACEHOLDER = "${option}";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private SkeletonPromptBundle() {
    }

    /** 生成提示词（默认对 {@code value}/{@code option} 占位化）。 */
    public static String of(RecordedSkeleton skeleton) {
        return of(skeleton, true);
    }

    /**
     * 生成提示词。
     *
     * @param skeleton     录制骨架（非 null）
     * @param redactValues {@code true} = 值占位化（推荐）；{@code false} = 保留原值
     * @return 可直接粘贴的纯文本（Markdown 结构，但要求模型只回 JSON）
     */
    public static String of(RecordedSkeleton skeleton, boolean redactValues) {
        Objects.requireNonNull(skeleton, "skeleton");
        StringBuilder sb = new StringBuilder(2048);
        sb.append("# 任务：把「录制骨架」翻译为 StepPlan JSON\n\n")
                .append("你是代码助手。下面给出一次 UI 录制的结构化骨架与允许动作表。\n")
                .append("**只输出一个 JSON 对象**：不要解释、不要 Markdown 代码围栏、不要注释。\n\n");
        appendActionTable(sb);
        appendHardConstraints(sb, redactValues);
        sb.append("## 录制骨架\n")
                .append("origin=").append(skeleton.origin())
                .append("  pageRef=").append(skeleton.pageRef())
                .append("  steps=").append(skeleton.steps().size()).append("\n")
                .append(skeletonJson(skeleton, redactValues)).append("\n\n");
        appendOutputContract(sb);
        return sb.toString();
    }

    private static void appendActionTable(StringBuilder sb) {
        sb.append("## 允许的动作（只能使用这些）\n")
                .append("| action | 必填参数 | 允许参数 | 需要 target |\n")
                .append("| --- | --- | --- | --- |\n");
        List<Command.ActionType> actions = new ArrayList<>(IntentActionSchema.authorizedActions());
        actions.sort(Comparator.comparing(Enum::name));
        for (Command.ActionType action : actions) {
            ActionParamSchema.Spec spec = ActionParamSchema.specOf(action);
            sb.append("| ").append(action.name())
                    .append(" | ").append(joinOrDash(spec.requiredParams()))
                    .append(" | ").append(joinOrDash(spec.allowedParams()))
                    .append(" | ").append(spec.requiresTarget() ? "是" : "否")
                    .append(" |\n");
        }
        sb.append('\n');
    }

    private static void appendHardConstraints(StringBuilder sb, boolean redactValues) {
        sb.append("## 硬约束\n")
                .append("1. 只能使用上表中的 action；表外动作（含框架内部原语）一律禁止。\n")
                .append("2. 不得新增字段；不得输出解释性文字。\n")
                .append("3. target 必须**逐字沿用**骨架里的 target 字符串，不得改写、不得臆造定位符。\n")
                .append("4. 步骤数量、顺序与骨架一致，不得增删或重排。\n")
                .append("5. NAVIGATE 不得带 target；FILL/SELECT 必须给出非空参数值。\n");
        if (redactValues) {
            sb.append("6. 骨架中的 ${...} 是**占位符**：请原样保留，**不要**编造真实数据。\n");
        } else {
            sb.append("6. 骨架中的参数值是真实业务数据：只允许原样搬运，不得改写。\n");
        }
        sb.append('\n');
    }

    private static void appendOutputContract(StringBuilder sb) {
        sb.append("## 输出契约\n")
                .append("- 根字段：schemaVersion（必须为 1）、planId（任意非空字符串）、steps（数组）、scope（可选）\n")
                .append("- step 字段：action、target（可选）、params（可选）、scope（可选）\n")
                .append("- 示例形状：{\"schemaVersion\":1,\"planId\":\"plan-suggested\",\"steps\":[")
                .append("{\"action\":\"CLICK\",\"target\":\"role=button;name=Submit\"}]}\n");
    }

    /**
     * 骨架 JSON：与 {@link RecordedSkeleton#toJson()} 字段顺序一致；需要时把
     * {@code value}/{@code option} 换成占位符（结构不变、数据去掉）。
     */
    private static String skeletonJson(RecordedSkeleton skeleton, boolean redactValues) {
        if (!redactValues) {
            return skeleton.toJson();
        }
        ObjectNode root = MAPPER.createObjectNode();
        root.put("schemaVersion", skeleton.schemaVersion());
        root.put("origin", skeleton.origin());
        root.put("pageRef", skeleton.pageRef());
        ArrayNode steps = root.putArray("steps");
        for (RecordedStep step : skeleton.steps()) {
            ObjectNode node = steps.addObject();
            node.put("action", step.action());
            if (step.target() != null) {
                node.put("target", step.target());
            }
            ObjectNode params = node.putObject("params");
            step.params().entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .forEach(e -> params.put(e.getKey(), placeholderFor(e.getKey(), e.getValue())));
        }
        return root.toString();
    }

    private static String placeholderFor(String key, String value) {
        if ("value".equals(key)) {
            return VALUE_PLACEHOLDER;
        }
        if ("option".equals(key)) {
            return OPTION_PLACEHOLDER;
        }
        return value;
    }

    private static String joinOrDash(Set<String> keys) {
        return keys.isEmpty() ? "—" : String.join(", ", new TreeSet<>(keys));
    }
}
