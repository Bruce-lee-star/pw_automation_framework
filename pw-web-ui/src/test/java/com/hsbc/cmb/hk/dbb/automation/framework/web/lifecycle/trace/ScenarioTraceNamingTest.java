package com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.trace;

import com.hsbc.cmb.hk.dbb.automation.framework.web.config.FrameworkConfigManager;
import org.junit.Test;

import java.time.Instant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * trace 文件命名（可读 slug + 可读 UTC 时间戳 + 结果）与 slug/sanitize 守卫。
 *
 * <p>命名演进：原为 {@code trace-<下划线洪泛>-<epoch起>-<epoch止>-<outcome>.zip}（难读）；
 * 现为 {@code trace-<slug>-<yyyyMMdd-HHmmss>-<outcome>.zip}——slug 把非 {@code [a-z0-9]} 段折叠为单个
 * {@code -}，时间戳用 UTC 可读格式（窗口区间仍在报告证据文本中）。
 */
public class ScenarioTraceNamingTest {

    @Test
    public void nameCarriesScenarioSlugReadableStampAndOutcome() {
        // slug 可读（_ 折叠为 -）+ UTC 可读时间戳（epoch 1000 = 1970-01-01T00:00:01Z）
        String name = ScenarioTraceRecorder.traceFileName("login_1_3", 1000L, "FAIL");
        assertEquals("trace-login-1-3-19700101-000001-FAIL.zip", name);
        assertTrue("trace 文件应带统一前缀，便于保留治理识别", name.startsWith("trace-"));
        assertTrue(name.endsWith(".zip"));
    }

    @Test
    public void readableStampIsUtcAndDeterministic() {
        assertEquals("19700101-000001", ScenarioTraceRecorder.readableStamp(1000L));
        assertEquals("20261019-000000",
                ScenarioTraceRecorder.readableStamp(Instant.parse("2026-10-19T00:00:00Z").toEpochMilli()));
    }

    @Test
    public void onCloseFallbackOutcomeIsDistinguished() {
        String name = ScenarioTraceRecorder.traceFileName("scn", 5L, "ONCLOSE");
        assertTrue("兜底导出（用例未走到收尾）必须与正常导出可区分", name.contains("-ONCLOSE.zip"));
    }

    @Test
    public void unknownOutcomeWhenResultNotAvailable() {
        String name = ScenarioTraceRecorder.traceFileName("scn", 1L, null);
        assertTrue(name.contains("-UNKNOWN.zip"));
    }

    @Test
    public void slugIsReadableAndHasNoPathUnsafeChars() {
        String name = ScenarioTraceRecorder.traceFileName("a/b:c d\\e", 1000L, "PASS");
        assertFalse("文件名不得含路径分隔符/冒号/空格：" + name,
                name.contains("/") || name.contains(":") || name.contains("\\") || name.contains(" "));
        assertEquals("trace-a-b-c-d-e-19700101-000001-PASS.zip", name);
    }

    @Test
    public void slugCollapsesSpecialsTrimsAndTruncates() {
        assertEquals("testbed-3mb-base64-body-ui",
                ScenarioTraceRecorder.slugForFileName("testbed - 3MB base64 ____ body _____UI__"));
        assertEquals("unknown", ScenarioTraceRecorder.slugForFileName(null));
        assertEquals("unknown", ScenarioTraceRecorder.slugForFileName(""));
        assertEquals("unknown", ScenarioTraceRecorder.slugForFileName("///"));
        assertEquals("片段应截断至 120 字符", 120, ScenarioTraceRecorder.slugForFileName("x".repeat(500)).length());
    }

    @Test
    public void sanitizeStillUsedForExporterThreadName() {
        // sanitizeForFileName（逐字符换 _）仍用于导出线程名，保持原契约不变
        assertEquals("unknown", ScenarioTraceRecorder.sanitizeForFileName(null));
        assertEquals("unknown", ScenarioTraceRecorder.sanitizeForFileName(""));
        assertEquals("___", ScenarioTraceRecorder.sanitizeForFileName("///"));
        assertEquals(120, ScenarioTraceRecorder.sanitizeForFileName("x".repeat(500)).length());
    }

    @Test
    public void traceDirIsFixedAndNotConfigurable() {
        //  trace 落点已改由【报告】指导：ReportingBridge#traceTarget() 返回 per-case 的 trace.zip，
        //  框架直接导出到那里（不产生"框架 traces/ + per-case"两份、也无须事后移动）。
        //  本目录只是【报告未指导时】的证据兜底位置，故为固定常量、不再由
        //  playwright.context.trace.dir 配置（该键已于 2026-10-18 移除）。
        String key = "playwright.context.trace.dir";
        String prev = System.getProperty(key);
        try {
            System.clearProperty(key);
            FrameworkConfigManager.clearCache();
            assertEquals("target/site/serenity/traces", ScenarioTraceRecorder.traceDir());

            System.setProperty(key, "custom/traces");
            FrameworkConfigManager.clearCache();
            assertEquals("兜底目录为固定值、不受已移除的配置键影响",
                    "target/site/serenity/traces", ScenarioTraceRecorder.traceDir());
        } finally {
            if (prev == null) {
                System.clearProperty(key);
            } else {
                System.setProperty(key, prev);
            }
            FrameworkConfigManager.clearCache();
        }
    }
}
