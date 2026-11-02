package com.hsbc.cmb.hk.dbb.automation.framework.report.migration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.hsbc.cmb.hk.dbb.automation.framework.report.render.JsonWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Serenity 报告源（Phase 0 双跑基线）：读 Serenity 的 {@code serenity-summary.json}。
 *
 * <p>兼容两种结构：① 顶层 {@code outcomes[]}（部分 Serenity 版本/聚合器导出）；
 * ② 顶层 {@code features[].scenarios[]}。两者缺一则回退空列表（不抛）。
 *
 * <p>注意：Serenity 汇总 JSON 通常<b>不含步骤级明细</b>，故 stepTexts 恒为空——
 * 四一致门禁的 STEP_TREE 维度在 Serenity 侧不可比时会自动跳过（仅 WARN 级，
 * 不影响 result/summary 比对）。若需步骤树比对，需另行解析 Serenity 的
 * 详细 outcome JSON（不在本基线结构内，故留作框架侧接入点）。
 */
public final class ManagedReport implements ReportSource {

    private static final Logger log = LoggerFactory.getLogger(ManagedReport.class);

    @Override
    public String sourceName() {
        return "serenity";
    }

    @Override
    public List<CanonicalOutcome> load(Path dir) {
        Path summary = dir.resolve("serenity-summary.json");
        if (!Files.exists(summary)) {
            return List.of();
        }
        try {
            JsonNode root = JsonWriter.mapper().readTree(summary.toFile());
            List<CanonicalOutcome> list = new ArrayList<>();
            JsonNode outcomes = root.get("outcomes");
            if (outcomes instanceof ArrayNode a) {
                for (JsonNode o : a) {
                    list.add(fromOutcome(o, o.path("feature").asText(null)));
                }
                return list;
            }
            JsonNode features = root.get("features");
            if (features instanceof ArrayNode f) {
                for (JsonNode feat : f) {
                    String featName = feat.path("name").asText(null);
                    JsonNode scenarios = feat.get("scenarios");
                    if (scenarios instanceof ArrayNode s) {
                        for (JsonNode sc : s) {
                            list.add(fromOutcome(sc, featName));
                        }
                    }
                }
            }
            return list;
        } catch (IOException e) {
            log.warn("serenity summary unreadable: {}", e.toString());
            return List.of();
        }
    }

    private CanonicalOutcome fromOutcome(JsonNode o, String feature) {
        String id = o.path("id").asText(null);
        String name = o.path("name").asText(null);
        String result = o.path("result").asText(null);
        long dur = o.path("duration").asLong(0);
        return new CanonicalOutcome(id, feature, name, result, dur, List.of());
    }
}
