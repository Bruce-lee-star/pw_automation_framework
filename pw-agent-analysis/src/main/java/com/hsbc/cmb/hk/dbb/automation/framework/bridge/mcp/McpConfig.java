package com.hsbc.cmb.hk.dbb.automation.framework.bridge.mcp;

import java.util.LinkedHashSet;
import java.util.List;

/**
 * MCP 桥的<b>服务端配置</b>：唯一对外可配置项是「已批准来源」白名单。
 *
 * <p>为什么不能由 IDE client 传 {@code origins}：{@code SkeletonCli check --origin} 是
 * 确定性基线翻译器的取值域白名单（批准裁决的一部分）。若交给第三方模型驱动的 client 任意指定，
 * 等于让被校验方自己定义校验基准，统一门禁形同虚设。因此该值只从启动进程的系统属性
 * {@code -Dpw.mcp.approved.origins=...} 或环境变量 {@code PW_MCP_APPROVED_ORIGINS} 读取，
 * <b>未配置即 fail-closed</b>（check 工具拒绝执行，而不是放空跑）。</p>
 *
 * <p>刻意不提供 {@code exposeImpact} 之类开关：{@code impact} 允许 {@code --sources} 扫描任意
 * 目录并把源码命中行原文回传，暴露给 IDE client 等于开出「任意文件读 + 内容出域」通道；
 * 该能力保留在本地 CLI，不进协议面（见 {@link McpToolCatalog}）。</p>
 */
public final class McpConfig {

    /** 已批准来源（逗号分隔）；优先于环境变量。 */
    public static final String ORIGINS_PROPERTY = "pw.mcp.approved.origins";
    /** 已批准来源的环境变量形态（CI / IDE MCP 配置里更方便）。 */
    public static final String ORIGINS_ENV = "PW_MCP_APPROVED_ORIGINS";

    private McpConfig() {
    }

    /** 已批准来源列表（去空白、去重、保序）；未配置返回空列表。 */
    public static List<String> approvedOrigins() {
        String raw = System.getProperty(ORIGINS_PROPERTY);
        if (raw == null || raw.isBlank()) {
            raw = System.getenv(ORIGINS_ENV);
        }
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        LinkedHashSet<String> origins = new LinkedHashSet<>();
        for (String part : raw.split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                origins.add(trimmed);
            }
        }
        return List.copyOf(origins);
    }

    /** {@code SkeletonCli --origin} 的实参形态；空配置返回空串（调用方须先判空并拒绝）。 */
    public static String originsArgument() {
        return String.join(",", approvedOrigins());
    }
}
