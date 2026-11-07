package com.hsbc.cmb.hk.dbb.automation.framework.report.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.perf.AgentPerfSample;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.perf.Percentiles;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalLong;
import java.util.TreeMap;

/**
 * <b>一页非功能基线数据</b>（T9 / I-08）：把本次运行的样本聚合成单页 Markdown + 结构化 JSON。
 *
 * <p>设计与 §8.1 的口径一致：<b>未定阈值前只做基线测量，不宣称达标</b>——本类只输出
 * 实测值、分位数、并发观测、失败分类与「待确认的告警项」，不做任何 pass/fail 判定，
 * 也<b>不保存历史趋势</b>（报告只代表本次运行）。</p>
 *
 * <p><b>隐私与确定性</b>：输出只含桶名 / 计数 / 毫秒 / 比率 / 失败类别，样本本身不含
 * URL、参数、异常消息或快照内容；所有浮点格式显式 {@link Locale#ROOT}，避免不同
 * locale 机器上出现 {@code 6,70%} 之类的漂移。</p>
 */
public final class AgentPerfBaseline {

    /** 一页人类可读基线。 */
    public static final String MARKDOWN_FILE = "agent-perf-baseline.md";
    /** 同内容的机器可读产物（供 CI 告警读取）。 */
    public static final String JSON_FILE = "agent-perf-baseline.json";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 告警项：阈值待项目负责人确认，当前仅观测、不阻断任何流水线。 */
    private static final List<String> ALERTS = List.of(
            "p95 相对 CI 保存的基线漂移 > 50%",
            "采集开启时任一步失败率 > 0",
            "观测并发峰值 > 配置上限 framework.playwright.concurrent.max",
            "敏感令牌 canary 泄漏 > 0（R1/R7，既有 CrossExitCanaryTest）",
            "采集自身异常 > 0（探针已 fail-open 降级，不拖垮执行链）");

    private final List<AgentPerfSample> samples;
    private final String generatedAt;
    private final String concurrencyCap;

    private AgentPerfBaseline(List<AgentPerfSample> samples, String generatedAt, String concurrencyCap) {
        this.samples = List.copyOf(samples);
        this.generatedAt = generatedAt;
        this.concurrencyCap = concurrencyCap;
    }

    /** 由本次运行的样本快照构造；{@code concurrencyCap} 为配置上限的展示值。 */
    public static AgentPerfBaseline from(List<AgentPerfSample> samples, String concurrencyCap) {
        return new AgentPerfBaseline(samples == null ? List.of() : samples,
                Instant.now().toString(),
                concurrencyCap == null || concurrencyCap.isBlank() ? "n/a" : concurrencyCap);
    }

    /** 显式指定生成时间（测试与离线重放用）。 */
    public static AgentPerfBaseline from(List<AgentPerfSample> samples, String concurrencyCap,
                                         String generatedAt) {
        return new AgentPerfBaseline(samples == null ? List.of() : samples, generatedAt,
                concurrencyCap == null || concurrencyCap.isBlank() ? "n/a" : concurrencyCap);
    }

    public boolean isEmpty() {
        return samples.isEmpty();
    }

    public int sampleCount() {
        return samples.size();
    }

    /**
     * 把两个产物写入 {@code dir}（自动建目录），返回 Markdown 路径。
     * 采集开关关闭 / 无样本时调用方应跳过，本方法不生成空报告。
     */
    public Path writeTo(Path dir) throws IOException {
        Files.createDirectories(dir);
        Path markdown = dir.resolve(MARKDOWN_FILE);
        Files.writeString(markdown, toMarkdown(), StandardCharsets.UTF_8);
        Files.writeString(dir.resolve(JSON_FILE), toJson(), StandardCharsets.UTF_8);
        return markdown;
    }

    /** 渲染一页 Markdown。 */
    public String toMarkdown() {
        List<BucketStats> buckets = bucketStats();
        long failures = samples.stream().filter(s -> !s.success()).count();
        int peak = samples.stream().mapToInt(AgentPerfSample::inFlight).max().orElse(0);

        StringBuilder sb = new StringBuilder();
        sb.append("# Agent 非功能基线（本次运行）\n\n");
        sb.append("> 生成时间：").append(generatedAt)
                .append(" ｜ 样本数：").append(samples.size())
                .append(" ｜ 分位数口径：nearest-rank（恒取真实观测值）\n>\n");
        sb.append("> 采集开关 `agent.perf.enabled` 默认 **false**；关闭时不产生样本、不生成本文件。\n");
        sb.append("> 本报告只代表本次运行，不含历史趋势；**阈值待确认前只做基线测量，不宣称达标**（§8.1）。\n\n");

        sb.append("## 1. 延迟基线（毫秒）\n\n");
        sb.append("| 桶 | 样本数 | p50 | p95 | max | 成功率 |\n");
        sb.append("| --- | ---: | ---: | ---: | ---: | ---: |\n");
        for (BucketStats b : buckets) {
            sb.append("| ").append(b.bucket)
                    .append(" | ").append(b.count)
                    .append(" | ").append(display(b.p50))
                    .append(" | ").append(display(b.p95))
                    .append(" | ").append(b.max)
                    .append(" | ").append(String.format(Locale.ROOT, "%.2f%%", b.successRate))
                    .append(" |\n");
        }
        if (buckets.isEmpty()) {
            sb.append("| n/a | 0 | n/a | n/a | n/a | n/a |\n");
        }
        sb.append("\n`step` = 单动作墙钟耗时（含该步有限重试）；`plan-N` = N 步计划端到端")
                .append("（含 harness acquire/release 与运行时组合根作用域）。\n");
        sb.append("幂等命中路径不产生样本；浏览器冷启动、网络往返与快照采集未单独拆分。\n\n");

        sb.append("## 2. 并发上限\n\n");
        sb.append("| 项 | 值 |\n| --- | --- |\n");
        sb.append("| 观测峰值 in-flight | ").append(peak).append(" |\n");
        sb.append("| 配置上限 `framework.playwright.concurrent.max` | ")
                .append(concurrencyCap).append(" |\n");
        sb.append("| 并发模型 | 单 JVM 进程内线程并行；`resolvedParallelism = min(taskCount, parallelism, cap)` |\n\n");

        sb.append("## 3. 失败恢复\n\n");
        sb.append("| 项 | 值 |\n| --- | --- |\n");
        sb.append("| 失败样本 / 总样本 | ").append(failures).append(" / ")
                .append(samples.size()).append(" |\n");
        sb.append("| 失败率 | ").append(String.format(Locale.ROOT, "%.2f%%",
                samples.isEmpty() ? 0.0 : 100.0 * failures / samples.size())).append(" |\n");
        for (Map.Entry<String, Long> e : failureCounts().entrySet()) {
            sb.append("| ").append(e.getKey()).append(" | ").append(e.getValue()).append(" |\n");
        }
        sb.append("\n**恢复目标（§8.1）**：浏览器崩溃 / 超时 / 取消 / 网络中断一律 fail-fast 上抛，")
                .append("运行时组合根逆序退出全部角色；写操作提交态不确定时停止并人工核对；")
                .append("收尾收敛受 `ShutdownCoordinator` 单任务 8s / 总预算 20s 约束。\n\n");

        sb.append("## 4. 告警项（只观测，不阻断基线）\n\n");
        for (String alert : ALERTS) {
            sb.append("- ").append(alert).append('\n');
        }
        sb.append("\n> 阈值与采样窗口须由项目负责人按靶场与 CI 资源确认；")
                .append("本页不给出达标结论。采集灰度策略见设计稿 §8.1.1。\n");
        return sb.toString();
    }

    /** 渲染机器可读 JSON（与 Markdown 同源数据）。 */
    public String toJson() throws JsonProcessingException {
        return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(toModel());
    }

    private Map<String, Object> toModel() {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("generatedAt", generatedAt);
        root.put("sampleCount", samples.size());
        root.put("percentileMethod", "nearest-rank");
        root.put("thresholdsConfirmed", false);
        List<Map<String, Object>> bucketRows = new ArrayList<>();
        for (BucketStats b : bucketStats()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("bucket", b.bucket);
            row.put("samples", b.count);
            row.put("p50Ms", b.p50);
            row.put("p95Ms", b.p95);
            row.put("maxMs", b.max);
            row.put("successRate", b.successRate);
            bucketRows.add(row);
        }
        root.put("buckets", bucketRows);
        Map<String, Object> concurrency = new LinkedHashMap<>();
        concurrency.put("observedPeakInFlight",
                samples.stream().mapToInt(AgentPerfSample::inFlight).max().orElse(0));
        concurrency.put("configuredCap", concurrencyCap);
        root.put("concurrency", concurrency);
        Map<String, Object> failures = new LinkedHashMap<>();
        failures.put("total", samples.stream().filter(s -> !s.success()).count());
        failures.put("byKind", failureCounts());
        root.put("failures", failures);
        root.put("alerts", ALERTS);
        return root;
    }

    private List<BucketStats> bucketStats() {
        Map<String, List<AgentPerfSample>> grouped = new TreeMap<>();
        for (AgentPerfSample s : samples) {
            grouped.computeIfAbsent(s.bucket(), k -> new ArrayList<>()).add(s);
        }
        List<BucketStats> result = new ArrayList<>();
        // step 恒定排在首位，其余按桶名字典序，保证跨运行顺序稳定
        List<String> ordered = new ArrayList<>(grouped.keySet());
        ordered.sort((a, b) -> {
            boolean aStep = AgentPerfSample.STEP_BUCKET.equals(a);
            boolean bStep = AgentPerfSample.STEP_BUCKET.equals(b);
            if (aStep != bStep) {
                return aStep ? -1 : 1;
            }
            return a.compareTo(b);
        });
        for (String bucket : ordered) {
            List<AgentPerfSample> group = grouped.get(bucket);
            List<Long> durations = group.stream().map(AgentPerfSample::durationMs).toList();
            OptionalLong p50 = Percentiles.percentile(durations, Percentiles.P50);
            OptionalLong p95 = Percentiles.percentile(durations, Percentiles.P95);
            long max = group.stream().mapToLong(AgentPerfSample::durationMs).max().orElse(0L);
            long ok = group.stream().filter(AgentPerfSample::success).count();
            result.add(new BucketStats(bucket, group.size(),
                    p50.isPresent() ? p50.getAsLong() : null,
                    p95.isPresent() ? p95.getAsLong() : null,
                    max, 100.0 * ok / group.size()));
        }
        return result;
    }

    private Map<String, Long> failureCounts() {
        Map<String, Long> counts = new TreeMap<>();
        for (AgentPerfSample s : samples) {
            if (!s.success()) {
                String kind = s.failureKind() == null ? "UNKNOWN" : s.failureKind().name();
                counts.merge(kind, 1L, Long::sum);
            }
        }
        return counts;
    }

    private static String display(Long value) {
        return value == null ? "n/a" : Long.toString(value);
    }

    /** 单桶聚合值。 */
    private record BucketStats(String bucket, int count, Long p50, Long p95,
                               long max, double successRate) {
    }
}
