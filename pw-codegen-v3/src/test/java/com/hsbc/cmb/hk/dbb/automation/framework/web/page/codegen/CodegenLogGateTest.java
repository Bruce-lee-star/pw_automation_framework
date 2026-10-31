package com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import org.junit.Assert;
import org.junit.Assume;
import org.junit.Test;
import org.slf4j.LoggerFactory;

/**
 * 验证 Codegen 输出日志死开关（v2.16，logback 配置级，见 pw-core logback.xml）：
 *
 * <ul>
 *   <li>默认（无 {@code -Dpw.codegen.log}）→ codegen v3 / scan v2 包有效级别 ≥ WARN：
 *       刷屏级 INFO/DEBUG（regen 汇总、拾取/解析/面板推送、RoleElementPicker 细节等）
 *       默认不输出，WARN/ERROR 异常保留可见；</li>
 *   <li>显式打开（{@code -Dpw.codegen.log=INFO|DEBUG}）→ 有效级别 ≤ INFO，详细日志恢复。
 *       该断言默认跳过，仅在打开开关的 JVM 里执行。</li>
 * </ul>
 */
public class CodegenLogGateTest {

    private static Level effectiveLevel(String loggerName) {
        return ((Logger) LoggerFactory.getLogger(loggerName)).getEffectiveLevel();
    }

    /** 开关是否被显式打开为详细级别（INFO/DEBUG）。 */
    private static boolean verboseOpened() {
        String prop = System.getProperty("pw.codegen.log");
        return "INFO".equalsIgnoreCase(prop) || "DEBUG".equalsIgnoreCase(prop);
    }

    @Test
    public void defaultGateBlocksVerboseInfoOnCodegenV3() {
        // 打开开关的 JVM 里级别被显式降为 INFO/DEBUG，本条默认断言不适用 → 跳过
        Assume.assumeTrue(!verboseOpened());
        Level lv = effectiveLevel(
                "com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen.CodegenLauncher");
        Assert.assertTrue("codegen v3 默认级别应 ≥ WARN（死开关默认关），实际 " + lv,
                lv.levelInt >= Level.WARN_INT);
    }

    @Test
    public void defaultGateBlocksVerboseInfoOnScanV2() {
        Assume.assumeTrue(!verboseOpened());
        Level lv = effectiveLevel(
                "com.hsbc.cmb.hk.dbb.automation.framework.web.page.scan.engine.RoleElementPicker");
        Assert.assertTrue("scan v2 默认级别应 ≥ WARN（死开关默认关），实际 " + lv,
                lv.levelInt >= Level.WARN_INT);
    }

    @Test
    public void propertyCanOpenVerboseInfo() {
        // 默认路径不生效：仅在显式打开（-Dpw.codegen.log=INFO/DEBUG）的 JVM 里执行
        Assume.assumeTrue(verboseOpened());
        Level lv = effectiveLevel(
                "com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen.CodegenLauncher");
        Assert.assertTrue("打开后级别应 ≤ INFO，实际 " + lv, lv.levelInt <= Level.INFO_INT);
    }
}
