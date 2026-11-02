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
 * pw-reports 报告源：读 {@code pw-summary.json}（outcomes[]）取元信息，
 * 再逐个读 {@code <outcomeId>.json} 取步骤树（扁平化描述序列）。
 */
public final class PwReportSource implements ReportSource {

    private static final Logger log = LoggerFactory.getLogger(PwReportSource.class);

    @Override
    public String sourceName() {
        return "pw-reports";
    }

    @Override
    public List<CanonicalOutcome> load(Path dir) {
        Path summary = dir.resolve("pw-summary.json");
        if (!Files.exists(summary)) {
            return List.of();
        }
        try {
            JsonNode root = JsonWriter.mapper().readTree(summary.toFile());
            JsonNode outcomes = root.get("outcomes");
            if (outcomes == null || outcomes.isNull()) {
                return List.of();
            }
            List<CanonicalOutcome> list = new ArrayList<>();
            for (JsonNode o : outcomes) {
                String id = o.path("outcomeId").asText(null);
                String name = o.path("name").asText(null);
                String feature = o.path("feature").asText(null);
                // result / duration / 步骤树以 per-outcome JSON 为权威；缺则回退 summary
                JsonNode detail = readOutcomeJson(dir, id);
                String result = detail != null && !detail.path("result").isMissingNode()
                        ? detail.path("result").asText(null) : o.path("result").asText(null);
                long dur = detail != null && !detail.path("duration").isMissingNode()
                        ? detail.path("duration").asLong(0) : o.path("duration").asLong(0);
                List<String> steps = detail != null ? flatten(detail.withArray("testSteps")) : List.of();
                list.add(new CanonicalOutcome(id, feature, name, result, dur, steps));
            }
            return list;
        } catch (IOException e) {
            log.warn("pw-reports summary unreadable: {}", e.toString());
            return List.of();
        }
    }

    private JsonNode readOutcomeJson(Path dir, String id) {
        if (id == null) {
            return null;
        }
        //  per-case JSON 位于 dir/<id>/<id>.json（2026-10-21 per-case 目录迁移）
        Path f = dir.resolve(id).resolve(id + ".json");
        if (!Files.exists(f)) {
            return null;
        }
        try {
            return JsonWriter.mapper().readTree(f.toFile());
        } catch (IOException e) {
            log.debug("outcome JSON unreadable for {}: {}", id, e.toString());
            return null;
        }
    }

    private List<String> flatten(ArrayNode steps) {
        List<String> out = new ArrayList<>();
        for (JsonNode s : steps) {
            String d = s.path("description").asText(null);
            if (d != null) {
                out.add(d);
            }
            JsonNode children = s.get("children");
            if (children instanceof ArrayNode a) {
                out.addAll(flatten(a));
            }
        }
        return out;
    }
}
