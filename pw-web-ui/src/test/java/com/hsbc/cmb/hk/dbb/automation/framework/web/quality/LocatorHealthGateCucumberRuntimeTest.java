package com.hsbc.cmb.hk.dbb.automation.framework.web.quality;

import com.hsbc.cmb.hk.dbb.automation.framework.web.page.element.LocatorHealthRecorder;
import com.hsbc.cmb.hk.dbb.automation.framework.web.quality.smoke.LocatorHealthSmokeGlue;
import io.cucumber.core.cli.Main;
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
 * 门禁的<b>端到端</b>契约（真实 Cucumber 运行时，不用 mock）：
 * <ol>
 *   <li>SPI 装配成功：门禁钩子被 Cucumber 真正加载（无需 {@code @CucumberOptions} 声明）；</li>
 *   <li>首次运行（基线缺失）<b>退出码 0</b>，运行收尾按真实数据播种基线；</li>
 *   <li>下一次运行出现<b>新增</b>违例 ⇒ 场景失败 ⇒ <b>退出码 1</b>——即"能让构建红"。</li>
 * </ol>
 *
 * <p>这是把"零配置门禁"从单测契约提升为可观测事实的关键用例：单测只能证明钩子被调用，
 * 只有真实 runtime 能证明"钩子抛错 = 运行失败"这条链路成立。
 *
 * <p>注：两次 {@code run} 之间显式调用 {@link LocatorHealthGateBackend#resetForTest()}
 * 模拟"下一次 JVM 运行"——同一 JVM 内首轮播种后门禁按设计不再判定，避免自伤。
 */
public class LocatorHealthGateCucumberRuntimeTest {

    private static final String HEALTH_ENABLED = LocatorHealthRecorder.ENABLED_PROPERTY;
    private static final String GLUE_PACKAGE =
            "com.hsbc.cmb.hk.dbb.automation.framework.web.quality.smoke";

    private Path dir;
    private Path baseline;
    private Path features;
    private String originalBaseline;
    private String originalEnabled;
    private String originalTolerance;
    private String originalHealth;
    private String originalSelector;

    @Before
    public void setUp() throws IOException {
        dir = Files.createTempDirectory("locator-health-runtime-");
        baseline = dir.resolve("baseline.json");
        features = dir.resolve("features");
        Files.createDirectories(features);
        Files.writeString(features.resolve("locator-health-smoke.feature"), String.join("\n",
                "Feature: locator health gate smoke",
                "",
                "  Scenario: a scenario that exercises a locator",
                "    Given a locator violation is recorded",
                ""), StandardCharsets.UTF_8);

        originalBaseline = System.getProperty(LocatorHealthGate.BASELINE_PROPERTY);
        originalEnabled = System.getProperty(LocatorHealthGate.ENABLED_PROPERTY);
        originalTolerance = System.getProperty(LocatorHealthGate.TOLERANCE_PROPERTY);
        originalHealth = System.getProperty(HEALTH_ENABLED);
        originalSelector = System.getProperty(LocatorHealthSmokeGlue.SELECTOR_PROPERTY);

        System.setProperty(LocatorHealthGate.BASELINE_PROPERTY, baseline.toString());
        System.setProperty(LocatorHealthGate.ENABLED_PROPERTY, "true");
        System.setProperty(HEALTH_ENABLED, "true");
        System.clearProperty(LocatorHealthGate.TOLERANCE_PROPERTY);
        System.clearProperty(LocatorHealthSmokeGlue.SELECTOR_PROPERTY);

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
        restore(LocatorHealthSmokeGlue.SELECTOR_PROPERTY, originalSelector);
        deleteRecursively(dir);
    }

    @Test
    public void firstRunSeedsBaselineAndNextRunFailsOnNewRegression() throws IOException {
        assertEquals("首次接入（基线缺失）绝不能让构建红", 0, runCucumber());

        assertTrue("运行收尾应播种基线", Files.exists(baseline));
        String seeded = Files.readString(baseline, StandardCharsets.UTF_8);
        assertTrue("播种内容应包含真实违例选择器", seeded.contains(LocatorHealthSmokeGlue.DEFAULT_SELECTOR));

        //  模拟下一次独立运行：清空采集、复位门禁状态机
        LocatorHealthRecorder.reset();
        LocatorHealthGateBackend.resetForTest();
        System.setProperty(LocatorHealthSmokeGlue.SELECTOR_PROPERTY, "#newcomer");

        assertEquals("新增违例必须让场景失败（构建红）", 1, runCucumber());
    }

    @Test
    public void disabledGateKeepsRunGreenAndWritesNoBaseline() throws IOException {
        System.setProperty(LocatorHealthGate.ENABLED_PROPERTY, "false");

        assertEquals("门禁关闭时不得影响运行结果", 0, runCucumber());

        assertFalse("门禁关闭时不得写入基线", Files.exists(baseline));
    }

    // ==================== helpers ====================

    /** 以真实 Cucumber runtime 跑一遍临时 feature；返回退出码（0 通过 / 1 失败）。 */
    private byte runCucumber() {
        return Main.run(new String[]{
                "--glue", GLUE_PACKAGE,
                "--monochrome",
                features.toAbsolutePath().toString()
        }, getClass().getClassLoader());
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
