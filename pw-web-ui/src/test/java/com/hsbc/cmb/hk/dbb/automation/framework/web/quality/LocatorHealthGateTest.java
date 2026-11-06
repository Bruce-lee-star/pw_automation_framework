package com.hsbc.cmb.hk.dbb.automation.framework.web.quality;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * 定位健康度门禁契约：
 * <ul>
 *   <li>新增 / 恶化 → 回归；改善 / 消失 → 不算失败；</li>
 *   <li>基线缺失 → <b>fail-closed</b>（抛错，退出码 2），不允许门禁被静默绕过；</li>
 *   <li>基线只固化"有违例"的选择器，用量类噪音不进基线；</li>
 *   <li>{@code --write-baseline} 可建立基线并自洽（写完立即比对必通过）；</li>
 *   <li>CLI 退出码语义稳定（0 通过 / 1 回归 / 2 输入错误）。</li>
 * </ul>
 */
public class LocatorHealthGateTest {

    private Path dir;
    private Path current;
    private Path baseline;

    @Before
    public void setUp() throws IOException {
        dir = Files.createTempDirectory("locator-health-gate-");
        current = dir.resolve("locator-health.json");
        baseline = dir.resolve("baseline.json");
    }

    @After
    public void tearDown() throws IOException {
        if (dir != null && Files.exists(dir)) {
            try (var paths = Files.walk(dir)) {
                for (Path p : paths.sorted(Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(p);
                }
            }
        }
    }

    // ==================== 比对 ====================

    @Test
    public void passesWhenNothingChanged() throws IOException {
        writeHealth(current, selector("#row", "css", 2, 3));
        writeHealth(baseline, selector("#row", "css", 2, 3));

        LocatorHealthGate.GateResult result = LocatorHealthGate.compare(current, baseline, 0);

        assertTrue(result.passed(0));
        assertTrue("同基线不应有回归", result.regressions().isEmpty());
        assertTrue(result.improved().isEmpty());
        assertTrue(result.fixed().isEmpty());
    }

    @Test
    public void failsOnNewViolatingSelector() throws IOException {
        writeHealth(current, selector("#row", "css", 1, 2));
        writeHealth(baseline);

        LocatorHealthGate.GateResult result = LocatorHealthGate.compare(current, baseline, 0);

        assertEquals(1, result.regressions().size());
        LocatorHealthGate.Regression r = result.regressions().get(0);
        assertEquals("NEW", r.change());
        assertEquals("#row", r.selector());
        assertNull("NEW 无基线事实", r.baseline());
        assertFalse(result.passed(0));
    }

    @Test
    public void failsWhenViolationsGetWorse() throws IOException {
        writeHealth(current, selector("#row", "css", 5, 4));
        writeHealth(baseline, selector("#row", "css", 2, 3));

        LocatorHealthGate.GateResult result = LocatorHealthGate.compare(current, baseline, 0);

        assertEquals(1, result.regressions().size());
        LocatorHealthGate.Regression r = result.regressions().get(0);
        assertEquals("WORSENED", r.change());
        assertNotNull(r.baseline());
        assertEquals(2L, r.baseline().violations());
        assertEquals(5L, r.current().violations());
    }

    @Test
    public void passesWhenImprovedAndReportsFixed() throws IOException {
        writeHealth(current, selector("#row", "css", 1, 2));
        writeHealth(baseline, selector("#row", "css", 4, 5), selector("#gone", "css", 2, 3));

        LocatorHealthGate.GateResult result = LocatorHealthGate.compare(current, baseline, 0);

        assertTrue("改善不算失败", result.passed(0));
        assertTrue(result.regressions().isEmpty());
        assertEquals(1, result.improved().size());
        assertEquals("#row", result.improved().get(0).selector());
        assertEquals(1, result.fixed().size());
        assertEquals("#gone", result.fixed().get(0).selector());
    }

    @Test
    public void toleranceAllowsConfiguredNumberOfRegressions() throws IOException {
        writeHealth(current, selector("#a", "css", 1, 2), selector("#b", "css", 1, 2));
        writeHealth(baseline);

        LocatorHealthGate.GateResult result = LocatorHealthGate.compare(current, baseline, 0);

        assertEquals(2, result.regressions().size());
        assertFalse("容差 0 时 2 条回归必须失败", result.passed(0));
        assertFalse("容差 1 时 2 条仍失败", result.passed(1));
        assertTrue("容差 2 时可通过", result.passed(2));
    }

    @Test
    public void failsClosedWhenBaselineMissing() throws IOException {
        writeHealth(current, selector("#row", "css", 1, 2));
        try {
            LocatorHealthGate.compare(current, baseline, 0);
            fail("基线缺失必须抛错（fail-closed），而不是静默通过");
        } catch (IOException e) {
            assertTrue("错误信息应提示如何建立基线",
                    e.getMessage().contains("--write-baseline"));
        }
    }

    // ==================== 基线生成 ====================

    @Test
    public void writeBaselineKeepsOnlyViolatingSelectorsAndMatchesItself() throws IOException {
        writeHealth(current,
                selector("#row", "css", 2, 3),
                selector("#clean", "css", 0, 0)); // 无违例的用量噪音不得进基线

        Path written = LocatorHealthGate.writeBaseline(current, baseline);

        assertTrue(Files.exists(written));
        String json = Files.readString(written, StandardCharsets.UTF_8);
        assertTrue("违例选择器应固化", json.contains("#row"));
        assertFalse("零违例选择器不入基线", json.contains("#clean"));

        // 自洽：刚写的基线立即比对必须通过
        assertTrue(LocatorHealthGate.compare(current, baseline, 0).passed(0));
    }

    // ==================== CLI ====================

    @Test
    public void cliReturnsRejectedOnRegressionAndOkAfterWriteBaseline() throws Exception {
        writeHealth(current, selector("#row", "css", 2, 3));
        writeHealth(baseline);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int rejected = LocatorHealthGate.run(new String[]{
                        "--current", current.toString(), "--baseline", baseline.toString()},
                stream(out), stream(err));
        assertEquals("存在回归应退出 1", LocatorHealthGate.EXIT_REJECTED, rejected);
        assertTrue("回归明细应打印选择器", text(out).contains("#row"));

        ByteArrayOutputStream out2 = new ByteArrayOutputStream();
        int seeded = LocatorHealthGate.run(new String[]{
                        "--current=" + current, "--baseline=" + baseline, "--write-baseline"},
                stream(out2), stream(err));
        assertEquals("写基线应退出 0", LocatorHealthGate.EXIT_OK, seeded);

        int ok = LocatorHealthGate.run(new String[]{
                        "--current", current.toString(), "--baseline", baseline.toString()},
                stream(new ByteArrayOutputStream()), stream(err));
        assertEquals("基线自洽后应退出 0", LocatorHealthGate.EXIT_OK, ok);
    }

    @Test
    public void cliSkipsWhenCurrentMissingOnlyIfAllowed() throws IOException {
        writeHealth(baseline, selector("#row", "css", 2, 3));
        Path missing = dir.resolve("not-produced.json");

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int skipped = LocatorHealthGate.run(new String[]{
                        "--current", missing.toString(), "--baseline", baseline.toString(), "--allow-missing"},
                stream(out), stream(new ByteArrayOutputStream()));
        assertEquals("允许缺失时应跳过而非失败", LocatorHealthGate.EXIT_OK, skipped);
        assertTrue("应明确打印 SKIP", text(out).contains("SKIP"));

        int strict = LocatorHealthGate.run(new String[]{
                        "--current", missing.toString(), "--baseline", baseline.toString()},
                stream(new ByteArrayOutputStream()), stream(new ByteArrayOutputStream()));
        assertEquals("不允许缺失时应按输入错误退出（fail-closed）",
                LocatorHealthGate.EXIT_INPUT_ERROR, strict);
    }

    @Test
    public void cliReturnsInputErrorOnBadArguments() {
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = LocatorHealthGate.run(new String[]{"--nope"},
                stream(new ByteArrayOutputStream()), stream(err));

        assertEquals(LocatorHealthGate.EXIT_INPUT_ERROR, code);
        assertTrue("应打印用法", text(err).contains("usage"));
    }

    // ==================== helpers ====================

    /** 写一份最小 locator-health.json（门禁只读 selectors 数组）。 */
    private static void writeHealth(Path file, JsonObject... selectors) throws IOException {
        JsonArray array = new JsonArray();
        for (JsonObject s : selectors) {
            array.add(s);
        }
        JsonObject root = new JsonObject();
        root.addProperty("generatedAt", "2026-11-06T10:00:00Z");
        root.addProperty("selectorsTotal", selectors.length);
        root.add("selectors", array);
        root.add("violations", new JsonArray());
        Files.writeString(file, root.toString(), StandardCharsets.UTF_8);
    }

    private static JsonObject selector(String selector, String kind, int violations, int maxMatchCount) {
        JsonObject node = new JsonObject();
        node.addProperty("selector", selector);
        node.addProperty("kind", kind);
        node.addProperty("uses", 10);
        node.addProperty("violations", violations);
        node.addProperty("maxMatchCount", maxMatchCount);
        return node;
    }

    private static PrintStream stream(ByteArrayOutputStream buffer) {
        return new PrintStream(buffer, true, StandardCharsets.UTF_8);
    }

    private static String text(ByteArrayOutputStream buffer) {
        return buffer.toString(StandardCharsets.UTF_8);
    }
}
