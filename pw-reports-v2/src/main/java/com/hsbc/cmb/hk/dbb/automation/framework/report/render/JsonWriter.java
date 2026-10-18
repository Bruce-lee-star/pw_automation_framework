package com.hsbc.cmb.hk.dbb.automation.framework.report.render;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.hsbc.cmb.hk.dbb.automation.framework.report.model.TestOutcome;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * per-case JSON 写出——真相源，优先级最高（R4：一个坏 case 不拖累其余）。
 *
 * <p>文件名 {@code <outcomeId>.json}；汇总 {@code serenity-summary.json}。
 * 序列化口径：NON_NULL（空值省略，与 Serenity 一致）、空集合保留为 {@code []}、
 * pretty-print（人可 diff，CI golden 对比稳定）。
 */
public final class JsonWriter {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .setSerializationInclusion(JsonInclude.Include.NON_NULL)
            .enable(SerializationFeature.INDENT_OUTPUT)
            .disable(SerializationFeature.FAIL_ON_EMPTY_BEANS);

    private JsonWriter() {
    }

    /** 写单个用例 JSON。 */
    public static Path writeOutcome(Path dir, TestOutcome outcome) throws IOException {
        Files.createDirectories(dir);
        Path target = dir.resolve(outcome.getOutcomeId() + ".json");
        writeAtomic(target, MAPPER.writeValueAsBytes(outcome));
        return target;
    }

    /** 写汇总 JSON（Serenity 口径文件名，DR-1）。 */
    public static Path writeSummary(Path dir, List<TestOutcome> outcomes) throws IOException {
        Files.createDirectories(dir);
        Map<String, Object> summary = new TreeMap<>();
        summary.put("reportVersion", 1);
        summary.put("outcomes", outcomes.stream().map(JsonWriter::outcomeSummary).toList());
        summary.put("totals", totals(outcomes));
        Path target = dir.resolve("serenity-summary.json");
        writeAtomic(target, MAPPER.writeValueAsBytes(summary));
        return target;
    }

    static Map<String, Object> outcomeSummary(TestOutcome o) {
        Map<String, Object> m = new TreeMap<>();
        m.put("outcomeId", o.getOutcomeId());
        m.put("name", o.getName());
        m.put("result", o.getResult() == null ? null : o.getResult().name());
        m.put("duration", o.getDuration());
        m.put("startTime", o.getStartTime());
        m.put("endTime", o.getEndTime());
        m.put("feature", o.getUserStory() == null ? null : o.getUserStory().getStoryName());
        return m;
    }

    public static Map<String, Object> totals(List<TestOutcome> outcomes) {
        Map<String, Integer> byResult = new TreeMap<>();
        long totalDuration = 0;
        int failures = 0;
        for (TestOutcome o : outcomes) {
            String key = o.getResult() == null ? "UNKNOWN" : o.getResult().name();
            byResult.merge(key, 1, (a, b) -> a + b);
            totalDuration += o.getDuration();
            if (o.getResult() != null && o.getResult().isFailure()) {
                failures++;
            }
        }
        Map<String, Object> totals = new TreeMap<>();
        totals.put("count", outcomes.size());
        totals.put("failures", failures);
        totals.put("duration", totalDuration);
        totals.put("byResult", byResult);
        return totals;
    }

    /** 先写临时文件再 move——避免渲染中断留下半文件 JSON。 */
    private static void writeAtomic(Path target, byte[] content) throws IOException {
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        Files.write(tmp, content);
        try {
            Files.move(tmp, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            Files.move(tmp, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** 测试/推送用的共享 mapper 视图（HttpPushSink 序列化事件）。 */
    public static ObjectMapper mapper() {
        return MAPPER;
    }

    static String asString(byte[] bytes) {
        return new String(bytes, StandardCharsets.UTF_8);
    }
}
