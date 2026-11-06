package com.hsbc.cmb.hk.dbb.automation.framework.web.page.element;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.hsbc.cmb.hk.dbb.automation.framework.common.reporting.LocatorHealthReportData;
import com.hsbc.cmb.hk.dbb.automation.framework.common.reporting.LocatorHealthReportRegistry;
import com.microsoft.playwright.PlaywrightException;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 定位健康度采集器 + 报告落盘器的行为锁定：
 * <ul>
 *   <li>用量只按选择器内存计数（不碰 DOM），并正确归类 role / xpath / css；</li>
 *   <li>失败只在 Playwright strict 违例（一次定位命中多个元素）时留证，其它失败不制造噪音；</li>
 *   <li>证据清单按「场景 + 选择器 + 操作 + 命中数」去重，计数器仍逐次累加；</li>
 *   <li>开关 {@code -Dpw.locator.health.enabled=false} 时完全静默；</li>
 *   <li>报告 JSON / Markdown 结构正确；无数据时不落盘（不给未跑浏览器用例的工程制造空文件）。</li>
 * </ul>
 */
public class LocatorHealthRecorderTest {

    private static final String ENABLED = LocatorHealthRecorder.ENABLED_PROPERTY;
    private static final String OUTPUT = LocatorHealthReportWriter.OUTPUT_PROPERTY;

    private String originalEnabled;
    private String originalOutput;
    private Path tempDir;

    @Before
    public void setUp() throws IOException {
        originalEnabled = System.getProperty(ENABLED);
        originalOutput = System.getProperty(OUTPUT);
        System.setProperty(ENABLED, "true");
        LocatorHealthRecorder.reset();
        tempDir = Files.createTempDirectory("locator-health-test-");
    }

    @After
    public void tearDown() throws IOException {
        LocatorHealthRecorder.reset();
        restoreProperty(ENABLED, originalEnabled);
        restoreProperty(OUTPUT, originalOutput);
        deleteRecursively(tempDir);
    }

    // ==================== 采集 ====================

    @Test
    public void recordUseAggregatesUsesAndClassifiesKind() {
        LocatorHealthRecorder.recordUse("#login");
        LocatorHealthRecorder.recordUse("#login");
        LocatorHealthRecorder.recordUse("role=button[name=\"Ok\"]");
        LocatorHealthRecorder.recordUse("//div[@id='x']");

        List<LocatorHealthRecorder.SelectorHealth> snapshot = LocatorHealthRecorder.snapshot();
        assertEquals("三个不同选择器各一条", 3, snapshot.size());

        assertEquals("#login", snapshot.get(0).selector());
        assertEquals("重复使用应累加", 2L, snapshot.get(0).uses());
        // 分类不看排序（用量相同的条目按选择器字典序），逐个查表断言
        assertEquals("css", findSelector("#login").kind());
        assertEquals("role", findSelector("role=button[name=\"Ok\"]").kind());
        assertEquals("xpath", findSelector("//div[@id='x']").kind());
        assertEquals("无违例时最大命中数为 0", 0, snapshot.get(0).maxMatchCount());
    }

    @Test
    public void recordUseIgnoresNullOrBlank() {
        LocatorHealthRecorder.recordUse(null);
        LocatorHealthRecorder.recordUse("   ");

        assertTrue("空白选择器不入库", LocatorHealthRecorder.snapshot().isEmpty());
    }

    @Test
    public void recordFailureRecordsOnlyStrictViolations() {
        LocatorHealthRecorder.recordFailure(
                new PlaywrightException("Error: strict mode violation: locator('#row') resolved to 3 elements"),
                "#row", "click");
        LocatorHealthRecorder.recordFailure(
                new PlaywrightException("Timeout 30000ms exceeded."), "#other", "click");

        List<LocatorHealthRecorder.Violation> violations = LocatorHealthRecorder.violations();
        assertEquals("非 strict 失败不留证", 1, violations.size());
        assertEquals("#row", violations.get(0).selector());
        assertEquals(3, violations.get(0).matchCount());
        assertEquals("click", violations.get(0).operation());
        assertFalse("线程名须被记录", violations.get(0).thread().isBlank());

        LocatorHealthRecorder.SelectorHealth health = findSelector("#row");
        assertEquals("违例计数器累加", 1L, health.violations());
        assertEquals("最大命中数被记录", 3, health.maxMatchCount());
        assertEquals("只统计违例、不推断用量", 0L, health.uses());
    }

    @Test
    public void recordFailureDeduplicatesSameEvidenceButCountsViolation() {
        PlaywrightException same = new PlaywrightException(
                "strict mode violation: locator('#row') resolved to 2 elements");
        LocatorHealthRecorder.recordFailure(same, "#row", "click");
        LocatorHealthRecorder.recordFailure(same, "#row", "click");

        assertEquals("同一（场景/选择器/操作/命中数）只留一条证据", 1,
                LocatorHealthRecorder.violations().size());
        assertEquals("但计数器逐次累加", 2L, findSelector("#row").violations());
    }

    @Test
    public void strictMatchCountReadsCauseChain() {
        PlaywrightException root = new PlaywrightException(
                "strict mode violation: locator('a') resolved to 4 elements");
        RuntimeException wrapped = new RuntimeException("wrapped", root);

        assertEquals(4, LocatorHealthRecorder.strictMatchCount(wrapped));
        assertEquals("非 strict 消息返回 0", 0,
                LocatorHealthRecorder.strictMatchCount(new RuntimeException("boom")));
        assertEquals("null 安全", 0, LocatorHealthRecorder.strictMatchCount(null));
    }

    @Test
    public void disabledStopsRecording() {
        System.setProperty(ENABLED, "false");

        LocatorHealthRecorder.recordUse("#login");
        LocatorHealthRecorder.recordFailure(new PlaywrightException(
                "strict mode violation: locator('#row') resolved to 2 elements"), "#row", "click");

        assertTrue("关闭后不采集用量", LocatorHealthRecorder.snapshot().isEmpty());
        assertTrue("关闭后不留证", LocatorHealthRecorder.violations().isEmpty());
        assertFalse(LocatorHealthRecorder.enabled());
    }

    // ==================== 落盘 ====================

    @Test
    public void writeProducesJsonAndMarkdown() throws IOException {
        LocatorHealthRecorder.recordUse("#login");
        LocatorHealthRecorder.recordFailure(new PlaywrightException(
                "strict mode violation: locator('#row') resolved to 3 elements"), "#row", "click");

        Path json = tempDir.resolve("nested/report.json");
        LocatorHealthReportWriter.write(json);

        assertTrue("父目录应自动创建", Files.exists(json));
        JsonObject root = JsonParser.parseString(
                Files.readString(json, StandardCharsets.UTF_8)).getAsJsonObject();
        assertEquals(2, root.get("selectorsTotal").getAsInt());
        assertEquals(1, root.get("violationsTotal").getAsInt());
        assertFalse("未达上界不应标记截断", root.get("truncated").getAsBoolean());

        JsonArray selectors = root.getAsJsonArray("selectors");
        assertEquals(2, selectors.size());
        JsonObject first = selectors.get(0).getAsJsonObject();
        assertEquals("#row", first.get("selector").getAsString());
        assertEquals(1, first.get("violations").getAsInt());
        assertEquals(3, first.get("maxMatchCount").getAsInt());

        JsonObject violation = root.getAsJsonArray("violations").get(0).getAsJsonObject();
        assertEquals("#row", violation.get("selector").getAsString());
        assertEquals("click", violation.get("operation").getAsString());
        assertEquals(3, violation.get("matchCount").getAsInt());

        Path md = tempDir.resolve("nested/report.md");
        assertTrue("同名 Markdown 应一并生成", Files.exists(md));
        String markdown = Files.readString(md, StandardCharsets.UTF_8);
        assertTrue(markdown.contains("定位健康度报告"));
        assertTrue("违例须出现在人读报告里", markdown.contains("#row"));
    }

    @Test
    public void writeDefaultIfAnySkipsWhenNoData() {
        Path json = tempDir.resolve("locator-health.json");
        System.setProperty(OUTPUT, json.toString());

        LocatorHealthReportWriter.writeDefaultIfAny();

        assertFalse("无采集数据时不落盘", Files.exists(json));
    }

    @Test
    public void writeDefaultIfAnyWritesConfiguredPath() {
        Path json = tempDir.resolve("out/locator-health.json");
        System.setProperty(OUTPUT, json.toString());
        LocatorHealthRecorder.recordUse("#login");

        LocatorHealthReportWriter.writeDefaultIfAny();

        assertTrue(Files.exists(json));
        assertTrue(Files.exists(tempDir.resolve("out/locator-health.md")));
    }

    // ==================== SPI 出口（报告模块消费） ====================

    @Test
    public void sinkMapsSnapshotToReportData() {
        LocatorHealthRecorder.recordUse("#login");
        LocatorHealthRecorder.recordFailure(new PlaywrightException(
                "strict mode violation: locator('#row') resolved to 3 elements"), "#row", "click");

        LocatorHealthReportData data = new LocatorHealthSink().collectData();

        assertTrue("有采集数据时应报告有内容", data.hasContent());
        assertEquals(2, data.getSelectorsTotal());
        assertEquals(1, data.getViolationsTotal());
        assertEquals(3, data.getViolations().get(0).getMatchCount());
        assertEquals("click", data.getViolations().get(0).getOperation());
        assertEquals("#row", data.getViolations().get(0).getSelector());
        assertEquals("未进入场景时应归因为 unknown", "unknown", data.getViolations().get(0).getScenario());
        assertFalse("违例时刻须已格式化", data.getViolations().get(0).getAt().isBlank());
        assertFalse("未达上界不应标记截断", data.isTruncated());
    }

    @Test
    public void spiProviderIsRegisteredForReportModule() {
        // 守 META-INF/services 注册文件：丢了它，报告侧会静默降级为空数据（不报错、只是区块消失）
        LocatorHealthReportRegistry.install(null);
        try {
            assertTrue("pw-web-ui 应经 SPI 暴露 LocatorHealthSink",
                    LocatorHealthReportRegistry.get() instanceof LocatorHealthSink);
        } finally {
            LocatorHealthReportRegistry.install(null);
        }
    }

    @Test
    public void sinkReturnsEmptyWhenNothingRecorded() {
        LocatorHealthReportData data = new LocatorHealthSink().collectData();

        assertFalse(data.hasContent());
        assertEquals(0, data.getSelectorsTotal());
        assertEquals(0, data.getViolationsTotal());
    }

    // ==================== helpers ====================

    private static LocatorHealthRecorder.SelectorHealth findSelector(String selector) {
        return LocatorHealthRecorder.snapshot().stream()
                .filter(h -> selector.equals(h.selector()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("selector not recorded: " + selector));
    }

    private static void restoreProperty(String key, String original) {
        // 本类读取的是裸系统属性（不经配置层），故清除即回落到默认值，无快照陈旧问题。
        if (original == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, original);
        }
    }

    private static void deleteRecursively(Path dir) throws IOException {
        if (dir == null || !Files.exists(dir)) {
            return;
        }
        try (var paths = Files.walk(dir)) {
            for (Path p : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        }
    }
}
