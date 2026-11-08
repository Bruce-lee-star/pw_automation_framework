package com.hsbc.cmb.hk.dbb.automation.framework.bridge.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Map;
import java.util.Set;

/**
 * MCP 工具目录：<b>只暴露 {@code prompt} 与 {@code check}</b> 两个工具。
 *
 * <p><b>T13 的前置决策（impact 是否对外暴露）：不暴露，且不提供开关。</b>依据是本仓现有
 * {@code SkeletonCli impact} 的能力面：{@code --sources} 只校验目录存在、可用
 * {@code Files.walk} 读任意绝对路径下的 {@code *.java/json/properties}，
 * {@code ImpactReport} 会把 feature 路径/行号、业务场景名、定位符原文及源码命中行
 * （{@code CodeTarget.snippet}，折叠空白后 160 字符，无脱敏）写进 Markdown；
 * {@code --out} 还可写任意路径。IDE 的 MCP client 通常由第三方模型驱动，tool result 会进入
 * 远端上下文——暴露即把内部资产清单与源码片段出域。且 {@code impact} 与 T13 验收
 * 「一次会话拿到可采信 StepPlan」无关。保留在<b>本地 CLI</b>（由人显式执行）即可满足原用途；
 * 若将来必须暴露，须另立专项：路径白名单、禁 {@code --sources/--out}、snippet 脱敏、只回计数。</p>
 *
 * <p>inputSchema 只声明业务数据字符串，<b>不暴露任何路径类参数</b>
 * （{@code --features/--sources/--out/--skeleton}）与 {@code --keep-values}，
 * 也不接受 {@code origins}（由 {@link McpConfig} 服务端注入）。
 * {@code additionalProperties=false} 由 {@link McpProtocol} 在服务端二次强制。</p>
 */
public final class McpToolCatalog {

    public static final String PROMPT = "prompt";
    public static final String CHECK = "check";
    /** 仅用于识别并明确拒绝，绝不注册进 tools/list。 */
    public static final String IMPACT = "impact";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final Map<String, String> PROPERTY_DESCRIPTIONS = Map.of(
            "skeletonJson", "RecordedSkeleton JSON（录制骨架本体，UTF-8 文本）",
            "suggestionText", "IDE 助手返回的原文，可含 Markdown 代码围栏与说明文字");

    private static final Set<String> PROMPT_ARGUMENTS = Set.of("skeletonJson");
    private static final Set<String> CHECK_ARGUMENTS = Set.of("skeletonJson", "suggestionText");

    private McpToolCatalog() {
    }

    /** tools/list 的工具定义（每次构造新节点，调用方不可修改共享状态）。 */
    public static ArrayNode tools() {
        ArrayNode tools = MAPPER.createArrayNode();
        tools.add(tool(PROMPT,
                "把录制骨架 JSON 转成可交给 IDE 助手的提示词（值默认占位化）。",
                PROMPT_ARGUMENTS));
        tools.add(tool(CHECK,
                "校验助手回文：统一门禁 + 与确定性基线的结构化等价对账；通过时直接返回可采信的 StepPlan JSON。",
                CHECK_ARGUMENTS));
        return tools;
    }

    /** 是否对外暴露该工具。 */
    public static boolean isExposed(String name) {
        return PROMPT.equals(name) || CHECK.equals(name);
    }

    /** 该工具允许的参数名（用于服务端 fail-closed 拒绝额外字段）。 */
    public static Set<String> allowedArguments(String name) {
        return switch (name) {
            case PROMPT -> PROMPT_ARGUMENTS;
            case CHECK -> CHECK_ARGUMENTS;
            default -> Set.of();
        };
    }

    private static ObjectNode tool(String name, String description, Set<String> arguments) {
        ObjectNode tool = MAPPER.createObjectNode();
        tool.put("name", name);
        tool.put("description", description);

        ObjectNode schema = MAPPER.createObjectNode();
        schema.put("type", "object");
        ObjectNode properties = MAPPER.createObjectNode();
        ArrayNode required = MAPPER.createArrayNode();
        for (String argument : arguments) {
            ObjectNode property = MAPPER.createObjectNode();
            property.put("type", "string");
            property.put("description", PROPERTY_DESCRIPTIONS.get(argument));
            properties.set(argument, property);
            required.add(argument);
        }
        schema.set("properties", properties);
        schema.set("required", required);
        schema.put("additionalProperties", false);
        tool.set("inputSchema", schema);
        return tool;
    }
}
