package com.hsbc.cmb.hk.dbb.automation.framework.report.render;

import com.hsbc.cmb.hk.dbb.automation.framework.report.cucumber.OutcomeIdFactory;
import com.hsbc.cmb.hk.dbb.automation.framework.report.model.TestOutcome;
import com.hsbc.cmb.hk.dbb.automation.framework.report.model.TestResult;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Scenario Outline 聚合（B3，sink 端）——把同一 outline 的多行 outcome 折叠为 1 个父 outcome，
 * 对齐 Serenity「一个数据驱动场景 → 一个 outcome + 每行结果」模型。
 *
 * <p><b>为何在 sink 端而非总线端</b>：{@code CaseFinished} 已 real-time 逐行写 per-case JSON
 * （真相源不损），总线不知道还有没有后续行；聚合只在 {@code RunFinished} 全量快照上做一次，
 * 零跨线程状态、可重复。</p>
 *
 * <p><b>口径</b>：仅对 {@code scenarioOutline==true 且 outlineKey!=null} 的行按 outlineKey 分组；
 * 分组 &gt;1 行 → 生成父 outcome（{@code scenarioOutline=true}，{@code examples} 挂各行），父结果 =
 * 任一行为 FAILURE/ERROR 即 FAILURE 否则 SUCCESS；分组恰 1 行 → 退化为普通用例（原样保留）。
 * 非 outline 行原样保留。输入顺序在无 outline 时零成本原样返回。</p>
 */
public final class OutlineAggregator {

    private static final DateTimeFormatter ISO_UTC =
            DateTimeFormatter.ISO_OFFSET_DATE_TIME.withZone(ZoneOffset.UTC);

    private OutlineAggregator() {
    }

    /** 折叠 outline 行；无 outline 时返回原列表（引用不变）。 */
    public static List<TestOutcome> aggregate(List<TestOutcome> outcomes) {
        if (outcomes == null) {
            return List.of();
        }
        Map<String, List<TestOutcome>> groups = new LinkedHashMap<>();
        boolean hasOutline = false;
        for (TestOutcome o : outcomes) {
            if (o != null && Boolean.TRUE.equals(o.getScenarioOutline()) && o.getOutlineKey() != null) {
                hasOutline = true;
                groups.computeIfAbsent(o.getOutlineKey(), k -> new ArrayList<>()).add(o);
            }
        }
        if (!hasOutline) {
            return outcomes; // 零成本
        }
        List<TestOutcome> result = new ArrayList<>();
        for (TestOutcome o : outcomes) {
            // 非 outline 行（或 outlineKey 缺失）原样保留
            if (o == null || !Boolean.TRUE.equals(o.getScenarioOutline()) || o.getOutlineKey() == null) {
                result.add(o);
            }
        }
        for (Map.Entry<String, List<TestOutcome>> e : groups.entrySet()) {
            List<TestOutcome> rows = e.getValue();
            if (rows.size() <= 1) {
                result.addAll(rows); // 单行 outline 退化为普通用例
                continue;
            }
            rows.sort(rowComparator());
            result.add(buildParent(e.getKey(), rows));
        }
        return result;
    }

    private static Comparator<TestOutcome> rowComparator() {
        return Comparator
                .comparingInt((TestOutcome o) -> o.getExampleRowNumber() == null ? Integer.MAX_VALUE : o.getExampleRowNumber())
                .thenComparing(o -> o.getOutcomeId() == null ? "" : o.getOutcomeId());
    }

    private static TestOutcome buildParent(String outlineKey, List<TestOutcome> rows) {
        TestOutcome first = rows.get(0);
        TestOutcome parent = new TestOutcome();
        parent.setOutcomeId(OutcomeIdFactory.timestampedId(outlineKey));
        parent.setName(first.getName());
        parent.setUserStory(first.getUserStory());
        parent.setTags(first.getTags());
        parent.setScenarioOutline(Boolean.TRUE);
        parent.setOutlineKey(outlineKey);
        parent.setExampleHeaders(first.getExampleHeaders());

        boolean anyFail = rows.stream().anyMatch(r -> {
            TestResult res = r.getResult();
            return res == TestResult.FAILURE || res == TestResult.ERROR;
        });
        parent.setResult(anyFail ? TestResult.FAILURE : TestResult.SUCCESS);

        Long minStart = rows.stream().map(OutlineAggregator::epochOfStart).filter(j -> j != null)
                .min(Comparator.naturalOrder()).orElse(null);
        Long maxEnd = rows.stream().map(OutlineAggregator::epochOfEnd).filter(j -> j != null)
                .max(Comparator.naturalOrder()).orElse(null);
        parent.setStartTime(minStart != null ? format(minStart) : first.getStartTime());
        parent.setEndTime(maxEnd != null ? format(maxEnd) : rows.get(rows.size() - 1).getEndTime());
        parent.setDuration(rows.stream().mapToLong(TestOutcome::getDuration).sum());
        parent.setExamples(rows);
        return parent;
    }

    private static Long epochOfStart(TestOutcome o) {
        return parse(o.getStartTime());
    }

    private static Long epochOfEnd(TestOutcome o) {
        return parse(o.getEndTime());
    }

    private static Long parse(String iso) {
        if (iso == null) {
            return null;
        }
        try {
            return OffsetDateTime.parse(iso).toInstant().toEpochMilli();
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static String format(long epochMilli) {
        return ISO_UTC.format(Instant.ofEpochMilli(epochMilli));
    }
}
