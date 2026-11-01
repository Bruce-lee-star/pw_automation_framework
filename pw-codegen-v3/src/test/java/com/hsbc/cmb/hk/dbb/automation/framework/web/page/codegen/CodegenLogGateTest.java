package com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import org.junit.Assert;
import org.junit.Assume;
import org.junit.Test;
import org.slf4j.LoggerFactory;

/**
 * 验证 Codegen 输出日志死开关（v2.16 配置级 + v2.17 代码级兜底，见 pw-core logback.xml
 * 与 {@link CodegenLogGate}）：
 *
 * <ul>
 *   <li>配置级（默认，无 {@code -Dpw.codegen.log}）→ codegen v3 包有效级别 ≥ WARN：
 *       刷屏级 INFO/DEBUG（regen 汇总、拾取/解析/面板推送、RoleElementPicker 细节等）
 *       默认不输出，WARN/ERROR 异常保留可见；</li>
 *   <li>代码级兜底：即使外部配置把包级别显式设为 INFO（模拟外部工程自带 logback 配置
 *       覆盖 pw-core logback.xml），{@link CodegenLogGate#apply()} 也强制收敛回默认 WARN；</li>
 *   <li>显式打开（{@code -Dpw.codegen.log=INFO|DEBUG}）→ 配置级与代码级均 ≤ INFO。
 *       该断言默认跳过，仅在打开开关的 JVM 里执行。</li>
 * </ul>
 */
public class CodegenLogGateTest {

    private static final String CODGEN_LAUNCHER =
            "com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen.CodegenLauncher";
    private static final String PKG_CODGEN =
            "com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen";

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
        CodegenLogGate.apply();
        Level lv = effectiveLevel(CODGEN_LAUNCHER);
        Assert.assertTrue("codegen v3 默认级别应 ≥ WARN（死开关默认关），实际 " + lv,
                lv.levelInt >= Level.WARN_INT);
    }

    @Test
    public void defaultGateBlocksVerboseInfoOnCodegenPackage() {
        Assume.assumeTrue(!verboseOpened());
        CodegenLogGate.apply();
        Level lv = effectiveLevel(PKG_CODGEN);
        Assert.assertTrue("codegen v3 默认级别应 ≥ WARN（死开关默认关），实际 " + lv,
                lv.levelInt >= Level.WARN_INT);
    }

    @Test
    public void codeLevelGateEnforcesDefaultOverExternalOverride() {
        Assume.assumeTrue(!verboseOpened());
        // 模拟外部测试工程自带 logback 配置、把 codegen 包显式覆盖为 INFO 的场景
        ((Logger) LoggerFactory.getLogger(PKG_CODGEN)).setLevel(Level.INFO);
        CodegenLogGate.apply();
        Level lv = effectiveLevel(CODGEN_LAUNCHER);
        Assert.assertTrue("代码级门应把被外部配置覆盖为 INFO 的包强制收敛回 WARN，实际 " + lv,
                lv.levelInt >= Level.WARN_INT);
    }

    @Test
    public void propertyCanOpenVerboseInfo() {
        // 默认路径不生效：仅在显式打开（-Dpw.codegen.log=INFO/DEBUG）的 JVM 里执行
        Assume.assumeTrue(verboseOpened());
        CodegenLogGate.apply();
        Level lv = effectiveLevel(CODGEN_LAUNCHER);
        Assert.assertTrue("打开后级别应 ≤ INFO，实际 " + lv, lv.levelInt <= Level.INFO_INT);
    }
}
