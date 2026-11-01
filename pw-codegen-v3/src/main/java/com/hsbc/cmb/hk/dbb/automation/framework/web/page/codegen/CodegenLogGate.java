package com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import com.hsbc.cmb.hk.dbb.automation.framework.common.config.ConfigReader;
import org.slf4j.LoggerFactory;

/**
 * Codegen 输出日志死开关的代码级兜底（v2.17）。
 *
 * <p>配置级（pw-core logback.xml 的 {@code ${pw.codegen.log:-WARN}}）只在 JVM 实际加载的
 * logback 配置来自 pw-core 且已含对应 logger 时生效；在外部测试工程里（自带
 * logback-test.xml / logback.xml，或依赖旧版 pw-core jar），配置级会被覆盖或缺失，
 * codegen 包的 INFO 刷屏将原样输出。本类在 JVM 内<b>强制</b>按属性收敛该包
 * 有效级别，与 classpath 加载哪份 logback 配置无关：
 *
 * <ul>
 *   <li>默认（未设 {@code pw.codegen.log}，或值为 OFF 之外的未知值）→ {@code WARN}：
 *       刷屏级 INFO/DEBUG 不输出，WARN/ERROR 异常保留可见（不做黑盒）；</li>
 *   <li>{@code -Dpw.codegen.log=INFO|DEBUG}（环境变量 {@code PW_CODEGEN_LOG} 同效）→ 显式打开详细日志；</li>
 *   <li>{@code -Dpw.codegen.log=OFF}（环境变量同效）→ 完全静默（异常也不输出，不建议）。</li>
 * </ul>
 *
 * <p>机制：包级 {@code setLevel}（后续尚未加载的子 logger 继承）＋ LoggerContext
 * 遍历已加载的具体 logger（外部配置若逐类显式设 INFO 也被强制收敛）。重复调用会重新应用当前配置。
 * 调用点：{@link CodegenLauncher}
 * 静态初始化 —— V3 会话入口类加载即收敛，早于任何 codegen 日志产生。
 */
public final class CodegenLogGate {

    /** 死开关属性名；与 pw-core logback.xml 的 {@code ${pw.codegen.log:-WARN}} 同源。 */
    public static final String PROPERTY = "pw.codegen.log";

    private static final String PKG_CODEGEN =
            "com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen";
    private static final String[] MANAGED_PACKAGES = {PKG_CODEGEN};

    private CodegenLogGate() {}

    /**
     * 按 {@code pw.codegen.log} 属性强制收敛 codegen 包有效级别。
     * 幂等、并发安全（重复调用会重新收敛已被外部配置修改的 logger）。
     */
    public static synchronized void apply() {
        Level target = resolveTarget();
        // 1) 包级：尚未加载的子 logger 沿继承链取到目标级别
        for (String pkg : MANAGED_PACKAGES) {
            ((Logger) LoggerFactory.getLogger(pkg)).setLevel(target);
        }
        // 2) 已加载的具体 logger：外部配置逐类显式 setLevel（如 INFO）也被强制收敛
        LoggerContext ctx = (LoggerContext) LoggerFactory.getILoggerFactory();
        for (Logger logger : ctx.getLoggerList()) {
            String name = logger.getName();
            if (!name.isEmpty()
                    && name.startsWith(PKG_CODEGEN)) {
                logger.setLevel(target);
            }
        }
    }

    /** 属性 → 目标级别：INFO/DEBUG 打开、OFF 全静默、其余（含未设）默认 WARN。 */
    static Level resolveTarget() {
        String value = ConfigReader.getProperty(PROPERTY, "");
        if ("INFO".equalsIgnoreCase(value) || "DEBUG".equalsIgnoreCase(value)) {
            return Level.valueOf(value.toUpperCase());
        }
        if ("OFF".equalsIgnoreCase(value)) {
            return Level.OFF;
        }
        return Level.WARN;
    }
}
