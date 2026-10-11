package com.hsbc.cmb.hk.dbb.automation.framework.route.reporting;

import com.hsbc.cmb.hk.dbb.automation.framework.common.reporting.MonitorFailureReportData;
import com.hsbc.cmb.hk.dbb.automation.framework.common.reporting.MonitorFailureItem;
import com.hsbc.cmb.hk.dbb.automation.framework.common.reporting.MonitorFailureReportSink;
import com.hsbc.cmb.hk.dbb.automation.framework.common.reporting.MonitorOwnerBlock;
import com.hsbc.cmb.hk.dbb.automation.framework.common.route.RouteAssertionFailure;
import org.junit.After;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 生产 {@link RouteMonitorFailureReportSink} 的行为验证：
 * 归集（按 owner=pattern 聚合）、落盘 JSON、clear 复位、并发喂入不丢失。
 *
 * <p>配套目的：套件收尾 {@code SummaryReportGenerator} 经 SPI 发现生产 sink 后，
 * 既有 WARN「No MonitorFailureReportSink implementation discovered」消失，且
 * monitor 失败报告真实落盘（{@code target/monitor-failures-by-owner.json}）。
 */
public class RouteMonitorFailureReportSinkTest {

    private final MonitorFailureReportSink sink = new RouteMonitorFailureReportSink();

    @org.junit.Before
    public void cleanFile() throws Exception {
        // 文件留档语义下，每个用例从干净文件起点开始（避免上个用例留档被本用例误读回）
        Files.deleteIfExists(Paths.get("target", "monitor-failures-by-owner.json"));
    }

    @After
    public void tearDown() {
        sink.clear();
    }

    /** 空归集：collectData 无内容、write 返回 0、不落盘。 */
    @Test
    public void emptySinkProducesNoContent() {
        assertFalse("空归集不应有内容", sink.collectData().hasContent());
        assertEquals("空归集 write 返回 0", 0, sink.write());
    }

    /** 状态码不匹配失败：按 owner(pattern) 聚合、计数正确、reason 含期望值。 */
    @Test
    public void statusMismatchIsAggregatedByOwner() {
        RouteAssertionFailure f1 = new RouteAssertionFailure(
                "/api/profile/list", "GET", "http://host/api/profile/list",
                200, 500, List.of(), false, 60_000L, "", "", "", "");
        RouteAssertionFailure f2 = new RouteAssertionFailure(
                "/api/profile/list", "POST", "http://host/api/profile/list",
                201, 400, List.of("$.code expected 0 but was 1"), false, 60_000L, "", "", "", "");
        sink.record(List.of(f1, f2));

        MonitorFailureReportData data = sink.collectData();
        assertTrue("有失败应有内容", data.hasContent());
        assertEquals("同一 owner 聚合为 1 个 block", 1, data.getOwners().size());
        assertEquals("owner 应为 pattern", "/api/profile/list", data.getOwners().get(0).getOwner());
        assertEquals("该 owner 下 2 条失败", 2, data.getOwners().get(0).getItems().size());
        assertEquals("failureCount 应为 2", 2, data.getFailureCount());
        assertEquals("ownerCount 应为 1", 1, data.getOwnerCount());

        MonitorFailureItem item = data.getOwners().get(0).getItems().get(0);
        assertTrue("reason 应含期望状态", item.getReason().contains("expect=200"));
        assertEquals("status 应含实际状态", "500 (expect 200)", item.getStatus());
        assertTrue("url 原样保留", item.getRequestUrl().contains("/api/profile/list"));
    }

    /** 超时失败：status 标记 TIMEOUT 且含超时窗口。 */
    @Test
    public void timeoutFailureIsMarked() {
        sink.record(List.of(new RouteAssertionFailure(
                "/api/auth/assert", "GET", "http://host/api/auth/assert",
                null, null, List.of(), true, 30_000L, "", "", "", "")));

        MonitorFailureReportData data = sink.collectData();
        assertEquals(1, data.getOwnerCount());
        assertTrue(data.getOwners().get(0).getItems().get(0).getStatus().contains("TIMEOUT"));
        assertTrue(data.getOwners().get(0).getItems().get(0).getStatus().contains("30000"));
    }

    /** write 落盘：文件存在、内容含 owner/pattern/url、二次 write 幂等（不清空）。 */
    @Test
    public void writePersistsJson() throws Exception {
        sink.record(List.of(new RouteAssertionFailure(
                "/api/error/500", "GET", "http://host/api/error/500?a=1&b=\"x\"",
                200, 500, List.of(), false, 60_000L, "", "", "", "")));
        assertEquals("write 返回 owner 数", 1, sink.write());

        Path path = Paths.get("target", "monitor-failures-by-owner.json");
        assertTrue("报告文件应存在", Files.exists(path));
        String content = Files.readString(path, StandardCharsets.UTF_8);
        assertTrue("JSON 含 owner", content.contains("\"owner\":\"/api/error/500\""));
        assertTrue("JSON 含 pattern", content.contains("\"pattern\":\"/api/error/500\""));
        assertTrue("JSON 含 url", content.contains("/api/error/500?a=1"));
        assertTrue("JSON 转义引号", content.contains("\\\"x\\\""));

        // write 不清空（clear 由 SummaryReportGenerator 负责）—— 幂等可重复写
        assertEquals("二次 write 仍返回 owner 数", 1, sink.write());
        assertTrue("write 后归集仍保留", sink.collectData().hasContent());
    }

    /** 喂入即落盘：record 后文件立即存在（不依赖报告阶段 write —— 跨进程可靠通道核心）。 */
    @Test
    public void recordPersistsFileImmediately() throws Exception {
        Path path = Paths.get("target", "monitor-failures-by-owner.json");
        Files.deleteIfExists(path);
        sink.record(List.of(new RouteAssertionFailure(
                "/api/feed/persist", "GET", "http://host/api/feed/persist",
                200, 500, List.of(), false, 60_000L, "", "", "", "")));
        assertTrue("record 后文件应已落盘（探针 JVM → 报告 JVM 的跨进程通道）", Files.exists(path));
        String content = Files.readString(path, StandardCharsets.UTF_8);
        assertTrue("落盘内容含 pattern", content.contains("\"pattern\":\"/api/feed/persist\""));
        assertTrue("落盘内容含 failureCount", content.contains("\"failureCount\":1"));
    }

    /** clear 复位：多套件同 JVM 不跨套件累积（内存清除；文件保留留档，下次喂入全量覆盖）。 */
    @Test
    public void clearResetsAggregation() {
        sink.record(List.of(new RouteAssertionFailure(
                "/api/x", "GET", "http://host/api/x", 200, 500, List.of(), false, 1000L, "", "", "", "")));
        assertTrue(sink.collectData().hasContent());
        assertTrue(Paths.get("target", "monitor-failures-by-owner.json").toFile().exists());
        sink.clear();
        assertFalse("clear 后无内容", sink.collectData().hasContent());
        assertEquals("clear 后 failureCount 归零", 0, sink.collectData().getFailureCount());
        assertEquals("clear 后 write 返回 0", 0, sink.write());
        // 文件保留留档（最新套件报告持续可见；无累积风险：每次喂入全量覆盖）
        assertTrue("clear 后文件保留（留档）",
                Paths.get("target", "monitor-failures-by-owner.json").toFile().exists());
    }

    /** 并发喂入：多线程 record 不丢条目、无重复（CopyOnWriteArrayList + ConcurrentHashMap）。 */
    @Test
    public void concurrentRecordIsLossless() throws Exception {
        int threads = 8;
        int perThread = 200;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        for (int t = 0; t < threads; t++) {
            final String owner = "/api/owner-" + t;
            pool.submit(() -> {
                ready.countDown();
                try {
                    go.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                List<RouteAssertionFailure> batch = new ArrayList<>(perThread);
                for (int i = 0; i < perThread; i++) {
                    batch.add(new RouteAssertionFailure(
                            owner, "GET", "http://host" + owner + "/" + i,
                            200, 500, List.of(), false, 1000L, "", "", "", ""));
                }
                sink.record(batch);
            });
        }
        ready.await();
        go.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));

        MonitorFailureReportData data = sink.collectData();
        assertEquals("8 个 owner", 8, data.getOwnerCount());
        assertEquals("总失败数 8*200", threads * perThread, data.getFailureCount());
        long items = data.getOwners().stream().mapToLong(b -> b.getItems().size()).sum();
        assertEquals("items 总数一致", threads * perThread, items);
    }
}
