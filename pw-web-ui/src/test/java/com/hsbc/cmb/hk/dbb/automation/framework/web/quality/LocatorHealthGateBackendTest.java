package com.hsbc.cmb.hk.dbb.automation.framework.web.quality;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.element.LocatorHealthRecorder;
import com.microsoft.playwright.PlaywrightException;
import io.cucumber.core.backend.BackendProviderService;
import io.cucumber.core.backend.Glue;
import io.cucumber.core.backend.HookDefinition;
import io.cucumber.core.backend.StaticHookDefinition;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.ServiceLoader;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * 门禁 Cucumber 适配器（{@link LocatorHealthGateBackend}）的契约锁定：
 * <ul>
 *   <li>装配两个钩子：场景 after（全局、order 最后）＋ 运行 after-all（播种）；</li>
 *   <li>回归只让<b>第一个</b>场景失败，后续场景不连坐；</li>
 *   <li>首次接入（基线缺失）不失败，运行收尾按真实数据播种；</li>
 *   <li>无采集数据不播种（避免写出"假装武装"的空基线）；</li>
 *   <li>门禁关闭 / 基线损坏 ⇒ 不失败、不改写任何文件。</li>
 * </ul>
 */
public class LocatorHealthGateBackendTest {

    private static final String HEALTH_ENABLED = LocatorHealthRecorder.ENABLED_PROPERTY;

    private Path dir;
    private Path baseline;
    private String originalBaseline;
    private String originalEnabled;
    private String originalHealth;

    @Before
    public void setUp() throws IOException {
        dir = Files.createTempDirectory("locator-health-backend-");
        baseline = dir.resolve("baseline.json");

        originalBaseline = System.getProperty(LocatorHealthGate.BASELINE_PROPERTY);
        originalEnabled = System.getProperty(LocatorHealthGate.ENABLED_PROPERTY);
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
        restore(HEALTH_ENABLED, originalHealth);
        deleteRecursively(dir);
    }

    // ==================== 装配 ====================

    @Test
    public void registersGlobalAfterHookAndAfterAllHookAsLast() {
        Glue glue = mock(Glue.class);
        LocatorHealthGateBackend backend = new LocatorHealthGateBackend();

        backend.loadGlue(glue, List.of());

        ArgumentCaptor<HookDefinition> after = ArgumentCaptor.forClass(HookDefinition.class);
        verify(glue).addAfterHook(after.capture());
        assertEquals("必须最后执行，才能看到业务清理钩子产生的数据",
                Integer.MAX_VALUE, after.getValue().getOrder());
        assertEquals("空 tag 表达式＝对所有场景生效", "", after.getValue().getTagExpression());
        assertFalse("框架钩子不参与用户 glue 冲突判定", after.getValue().isDefinedAt(new StackTraceElement("a", "b", "c", 1)));

        ArgumentCaptor<StaticHookDefinition> afterAll = ArgumentCaptor.forClass(StaticHookDefinition.class);
        verify(glue).addAfterAllHook(afterAll.capture());
        assertEquals(Integer.MAX_VALUE, afterAll.getValue().getOrder());

        assertNotNull("未定义步骤时的片段建议不得返回 null（防 NPE）", backend.getSnippet());
        assertNotNull(backend.getSnippet().template());
    }

    @Test
    public void providerIsDiscoverableAsCucumberBackendSpi() {
        boolean found = ServiceLoader.load(BackendProviderService.class).stream()
                .map(ServiceLoader.Provider::type)
                .anyMatch(LocatorHealthGateBackendProvider.class::equals);

        assertTrue("META-INF/services 必须注册门禁 backend，否则零配置失效", found);
    }

    @Test
    public void loadGlueNeverPropagatesRegistrationFailure() {
        Glue glue = mock(Glue.class);
        doThrow(new IllegalStateException("SPI conflict")).when(glue).addAfterHook(any());

        new LocatorHealthGateBackend().loadGlue(glue, List.of()); // 不得抛出

        verify(glue).addAfterHook(any());
    }

    // ==================== 首次接入 ====================

    @Test
    public void missingBaselineDoesNotFailAndSeedsAtRunEnd() throws IOException {
        violation("#row", 3);

        afterHook().execute(null); // 不得抛
        assertFalse("场景边界不播种（数据尚不完整）", Files.exists(baseline));

        afterAllHook().execute();

        assertTrue("运行收尾应播种基线", Files.exists(baseline));
        assertTrue(Files.readString(baseline, StandardCharsets.UTF_8).contains("#row"));
    }

    @Test
    public void noCollectedDataSkipsSeedingToAvoidFakeArmedBaseline() {
        afterAllHook().execute();

        assertFalse("没有任何采集数据时不得写出空基线（否则下一轮全是 NEW）", Files.exists(baseline));
    }

    // ==================== 回归 ====================

    @Test
    public void regressionFailsOnlyTheFirstScenarioAndCarriesDetails() throws IOException {
        writeBaseline();
        violation("#row", 3);
        HookDefinition hook = afterHook();

        try {
            hook.execute(null);
            fail("回归必须让场景失败");
        } catch (LocatorHealthRegressionException e) {
            assertTrue("失败信息须能定位选择器", e.getMessage().contains("#row"));
            assertEquals(1, e.regressions().size());
            assertEquals("NEW", e.regressions().get(0).change());
        }

        hook.execute(null); // 已报过：后续场景不连坐
    }

    @Test
    public void unreadableBaselineDoesNotFailScenario() throws IOException {
        Files.writeString(baseline, "not-json", StandardCharsets.UTF_8);
        violation("#row", 3);

        afterHook().execute(null); // 不得抛
    }

    @Test
    public void disabledGateNeitherFailsNorSeeds() throws IOException {
        System.setProperty(LocatorHealthGate.ENABLED_PROPERTY, "false");
        violation("#row", 3);

        afterHook().execute(null);
        afterAllHook().execute();

        assertFalse("门禁关闭时不得写入基线", Files.exists(baseline));
    }

    // ==================== helpers ====================

    private static HookDefinition afterHook() {
        Glue glue = mock(Glue.class);
        new LocatorHealthGateBackend().loadGlue(glue, List.of());
        ArgumentCaptor<HookDefinition> captor = ArgumentCaptor.forClass(HookDefinition.class);
        verify(glue).addAfterHook(captor.capture());
        return captor.getValue();
    }

    private static StaticHookDefinition afterAllHook() {
        Glue glue = mock(Glue.class);
        new LocatorHealthGateBackend().loadGlue(glue, List.of());
        ArgumentCaptor<StaticHookDefinition> captor = ArgumentCaptor.forClass(StaticHookDefinition.class);
        verify(glue).addAfterAllHook(captor.capture());
        return captor.getValue();
    }

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
