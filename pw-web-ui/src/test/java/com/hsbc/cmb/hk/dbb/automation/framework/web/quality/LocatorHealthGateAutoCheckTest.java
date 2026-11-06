package com.hsbc.cmb.hk.dbb.automation.framework.web.quality;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.element.LocatorHealthRecorder;
import com.microsoft.playwright.PlaywrightException;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 自动门禁（零配置路径）的契约锁定：
 * <ul>
 *   <li>门禁关闭 ⇒ {@code SKIPPED}，绝不失败；</li>
 *   <li>基线缺失 ⇒ {@code SEEDED}（<b>首次接入不误伤</b>），且能按真实数据播种出可用基线；</li>
 *   <li>基线损坏 / 不可读 ⇒ {@code SKIPPED}，不把门禁自身的故障算作用例失败；</li>
 *   <li>新增或恶化 ⇒ {@code FAILED}，失败信息可定位到选择器；</li>
 *   <li>容差只接受合法非负整数，非法值回落 0（不放宽门禁）。</li>
 * </ul>
 */
public class LocatorHealthGateAutoCheckTest {

    private static final String HEALTH_ENABLED = LocatorHealthRecorder.ENABLED_PROPERTY;

    private Path dir;
    private Path baseline;
    private String originalBaseline;
    private String originalEnabled;
    private String originalTolerance;
    private String originalHealth;

    @Before
    public void setUp() throws IOException {
        dir = Files.createTempDirectory("locator-health-auto-");
        baseline = dir.resolve("baseline.json");

        originalBaseline = System.getProperty(LocatorHealthGate.BASELINE_PROPERTY);
        originalEnabled = System.getProperty(LocatorHealthGate.ENABLED_PROPERTY);
        originalTolerance = System.getProperty(LocatorHealthGate.TOLERANCE_PROPERTY);
        originalHealth = System.getProperty(HEALTH_ENABLED);

        System.setProperty(LocatorHealthGate.BASELINE_PROPERTY, baseline.toString());
        System.setProperty(LocatorHealthGate.ENABLED_PROPERTY, "true");
        System.setProperty(HEALTH_ENABLED, "true");
        System.clearProperty(LocatorHealthGate.TOLERANCE_PROPERTY);

        LocatorHealthRecorder.reset();
        LocatorHealthGateBackend.resetForTest();
    }

    @After
    public void tearDown() throws IOException {
        LocatorHealthRecorder.reset();
        LocatorHealthGateBackend.resetForTest();
        restore(LocatorHealthGate.BASELINE_PROPERTY, originalBaseline);
        restore(LocatorHealthGate.ENABLED_PROPERTY, originalEnabled);
        restore(LocatorHealthGate.TOLERANCE_PROPERTY, originalTolerance);
        restore(HEALTH_ENABLED, originalHealth);
        deleteRecursively(dir);
    }

    // ==================== 门禁关闭 / 基线异常 ====================

    @Test
    public void disabledGateSkipsEvenWhenRegressionExists() throws IOException {
        writeBaseline();
        violation("#row", 3);
        System.setProperty(LocatorHealthGate.ENABLED_PROPERTY, "false");

        LocatorHealthGate.AutoResult result = LocatorHealthGate.autoCheck();

        assertEquals(LocatorHealthGate.AutoResult.Status.SKIPPED, result.status());
        assertFalse("门禁关闭时永久不失败", result.failed());
        assertTrue(result.message().contains(LocatorHealthGate.ENABLED_PROPERTY));
    }

    @Test
    public void unreadableBaselineSkipsInsteadOfFailing() throws IOException {
        Files.writeString(baseline, "{ this is not json", StandardCharsets.UTF_8);
        violation("#row", 3);

        LocatorHealthGate.AutoResult result = LocatorHealthGate.autoCheck();

        assertEquals("基线损坏不应把构建判红", LocatorHealthGate.AutoResult.Status.SKIPPED, result.status());
        assertFalse(result.failed());
        assertTrue(result.message().contains("unreadable"));
    }

    // ==================== 首次接入：播种 ====================

    @Test
    public void missingBaselineSeedsInsteadOfFailing() throws IOException {
        violation("#row", 3);

        LocatorHealthGate.AutoResult result = LocatorHealthGate.autoCheck();

        assertEquals(LocatorHealthGate.AutoResult.Status.SEEDED, result.status());
        assertFalse("首次接入不得失败", result.failed());
        assertTrue("应提示提交基线以启用回归检查", result.message().contains("commit"));

        Path written = LocatorHealthGate.seedIfMissing();
        assertTrue(Files.exists(written));
        assertTrue("播种内容应包含真实违例选择器",
                Files.readString(written, StandardCharsets.UTF_8).contains("#row"));

        assertEquals("播种后本轮即自洽通过", LocatorHealthGate.AutoResult.Status.PASS,
                LocatorHealthGate.autoCheck().status());
    }

    @Test
    public void seedDoesNotOverwriteExistingBaseline() throws IOException {
        writeBaseline(selector("#old", 1, 2));
        long sizeBefore = Files.size(baseline);
        String contentBefore = Files.readString(baseline, StandardCharsets.UTF_8);
        violation("#new", 4);

        LocatorHealthGate.seedIfMissing();

        assertEquals("已有基线必须原样保留（改善需人工确认后才刷新）", sizeBefore, Files.size(baseline));
        assertEquals(contentBefore, Files.readString(baseline, StandardCharsets.UTF_8));
    }

    @Test
    public void noDataRunWritesEmptyBaselineOnlyIfNoViolations() throws IOException {
        //  采集到数据但零违例：写出「干净基线」是合法且有用的（从此任何违例都算 NEW）
        LocatorHealthRecorder.recordUse("#clean");
        assertTrue(LocatorHealthGate.hasCollectedData());

        LocatorHealthGate.seedIfMissing();

        assertTrue(Files.exists(baseline));
        assertEquals("干净基线立即通过", LocatorHealthGate.AutoResult.Status.PASS,
                LocatorHealthGate.autoCheck().status());
    }

    @Test
    public void hasCollectedDataDistinguishesNoDataFromCleanData() {
        assertFalse(LocatorHealthGate.hasCollectedData());
        LocatorHealthRecorder.recordUse("#clean");
        assertTrue(LocatorHealthGate.hasCollectedData());
    }

    // ==================== 回归判定 ====================

    @Test
    public void newViolatingSelectorFailsWithActionableMessage() throws IOException {
        writeBaseline();
        violation("#row", 2);

        LocatorHealthGate.AutoResult result = LocatorHealthGate.autoCheck();

        assertEquals(LocatorHealthGate.AutoResult.Status.FAILED, result.status());
        assertTrue(result.failed());
        assertEquals(1, result.regressions().size());
        assertEquals("NEW", result.regressions().get(0).change());
        assertTrue("失败信息须能定位选择器", result.message().contains("#row"));
        assertTrue("失败信息须指向证据报告", result.message().contains("locator-health.html"));
    }

    @Test
    public void worsenedViolationsFail() throws IOException {
        writeBaseline(selector("#row", 1, 2));
        violation("#row", 2);
        violation("#row", 2);

        LocatorHealthGate.AutoResult result = LocatorHealthGate.autoCheck();

        assertEquals(LocatorHealthGate.AutoResult.Status.FAILED, result.status());
        assertEquals("WORSENED", result.regressions().get(0).change());
        assertTrue(result.message().contains("violations 1 -> 2"));
    }

    @Test
    public void improvedOrFixedDoesNotFail() throws IOException {
        writeBaseline(selector("#row", 5, 6), selector("#gone", 2, 3));
        violation("#row", 2);

        LocatorHealthGate.AutoResult result = LocatorHealthGate.autoCheck();

        assertEquals("改善不算失败", LocatorHealthGate.AutoResult.Status.PASS, result.status());
        assertTrue(result.regressions().isEmpty());
    }

    // ==================== 容差 ====================

    @Test
    public void toleranceAllowsConfiguredNumberOfRegressions() throws IOException {
        writeBaseline();
        violation("#a", 2);
        violation("#b", 2);

        System.setProperty(LocatorHealthGate.TOLERANCE_PROPERTY, "1");
        assertEquals(2, LocatorHealthGate.autoCheck().regressions().size());
        assertEquals("容差 1 不足以吸收 2 条回归", LocatorHealthGate.AutoResult.Status.FAILED,
                LocatorHealthGate.autoCheck().status());

        System.setProperty(LocatorHealthGate.TOLERANCE_PROPERTY, "2");
        assertEquals("容差 2 可通过", LocatorHealthGate.AutoResult.Status.PASS,
                LocatorHealthGate.autoCheck().status());
    }

    @Test
    public void invalidToleranceFallsBackToZeroAndNeverRelaxes() {
        System.setProperty(LocatorHealthGate.TOLERANCE_PROPERTY, "abc");
        assertEquals("非法值回落 0", 0, LocatorHealthGate.resolveTolerance());

        System.setProperty(LocatorHealthGate.TOLERANCE_PROPERTY, "-5");
        assertEquals("负数回落 0（不放宽门禁）", 0, LocatorHealthGate.resolveTolerance());

        System.setProperty(LocatorHealthGate.TOLERANCE_PROPERTY, " 3 ");
        assertEquals("合法值允许前后空白", 3, LocatorHealthGate.resolveTolerance());

        System.clearProperty(LocatorHealthGate.TOLERANCE_PROPERTY);
        assertEquals("未配置默认零容忍", 0, LocatorHealthGate.resolveTolerance());
    }

    // ==================== helpers ====================

    /** 记一条 Playwright strict 违例（一次定位命中多个元素）。 */
    private static void violation(String selector, int matched) {
        LocatorHealthRecorder.recordUse(selector);
        LocatorHealthRecorder.recordFailure(new PlaywrightException(
                "strict mode violation: locator('" + selector + "') resolved to " + matched + " elements"),
                selector, "click");
    }

    private void writeBaseline(JsonObject... selectors) throws IOException {
        JsonArray array = new JsonArray();
        for (JsonObject selector : selectors) {
            array.add(selector);
        }
        JsonObject root = new JsonObject();
        root.addProperty("version", "1");
        root.addProperty("selectorsTotal", selectors.length);
        root.add("selectors", array);
        Files.writeString(baseline, root.toString(), StandardCharsets.UTF_8);
    }

    private static JsonObject selector(String selector, int violations, int maxMatchCount) {
        JsonObject node = new JsonObject();
        node.addProperty("selector", selector);
        node.addProperty("kind", "css");
        node.addProperty("violations", violations);
        node.addProperty("maxMatchCount", maxMatchCount);
        return node;
    }

    private static void restore(String key, String value) {
        if (value == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, value);
        }
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }
}
