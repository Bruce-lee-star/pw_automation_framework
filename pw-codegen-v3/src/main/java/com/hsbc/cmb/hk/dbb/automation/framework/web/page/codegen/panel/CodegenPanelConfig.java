package com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen.panel;

import com.hsbc.cmb.hk.dbb.automation.framework.common.config.ConfigReader;
import java.nio.file.Path;

/**
 * 面板常驻服务的运行配置（B 轨道 2.1：{@code CodegenPanelManager} 的构造入参）。
 *
 * <p>所有超时/目录均允许 {@code -D} 覆盖（金融级可运维：CI 或本地临时调参无需改代码）：
 * <ul>
 *   <li>{@code codegen.panel.profile.dir} —— 浏览器 profile 目录（默认系统临时目录下固定目录，暖 profile）；</li>
 *   <li>{@code codegen.panel.idle.timeout.minutes} —— idle 回收分钟数，{@code 0} = 常驻不回收（默认 5）。</li>
 * </ul>
 *
 * <p>浏览器二进制下载/校验归框架（面板 driver 复用框架下载目录，经 {@code PLAYWRIGHT_BROWSERS_PATH} /
 * {@code PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1} 环境约定），故本配置不含 browserChannel —— 面板固定无 channel
 * （启动参数按 Chromium 调优，不吞换 channel 死窗风险，见 {@code CodegenPanelManager.PanelProcess#launchArgs}）。
 */
public record CodegenPanelConfig(
        /** 面板资源目录（serveDir：HTTP 服务托管根；由 {@link PanelResource} 固定导出，跨会话稳定）。 */
        Path panelResourceDir,
        /** 面板浏览器 profile 目录（跨会话复用 = 暖 profile）。 */
        Path profileDir,
        /** idle 回收阈值（毫秒）；{@code 0} = 常驻，watchdog 不启动。默认 5 分钟。 */
        long idleTimeoutMillis,
        /** 浏览器进程启动上限（毫秒）。 */
        double launchTimeoutMs,
        /** 面板页导航上限（毫秒）。 */
        double navigateTimeoutMs,
        /** 面板可交互（命令桥就绪）等待上限（毫秒）。 */
        double readyTimeoutMs,
        /** 命令 outbox 轮询间隔（毫秒）。 */
        long commandPollIntervalMs,
        /** 命令处理线程的 NLS 语言（ThreadLocal 传递，见 {@code LanguageState}）。 */
        String nlsLang) {

    /** 面板浏览器 profile 目录的系统属性覆盖项。 */
    public static final String PROFILE_DIR_PROPERTY = "codegen.panel.profile.dir";
    /** idle 回收分钟数的系统属性；{@code 0} = 常驻不回收。 */
    public static final String IDLE_TIMEOUT_MINUTES_PROPERTY = "codegen.panel.idle.timeout.minutes";

    /** 默认 idle 回收：5 分钟（B 轨道 2.5）。 */
    public static final long DEFAULT_IDLE_TIMEOUT_MINUTES = 5;

    /** 浏览器进程启动上限：profile 被占用/驱动异常时快速失败并降级，绝不让面板无限等待。 */
    public static final double DEFAULT_LAUNCH_TIMEOUT_MS = 60_000;
    /** 面板页导航上限（本地 http，正常毫秒级；有界以防代理/驱动异常时卡满默认 30s）。 */
    public static final double DEFAULT_NAVIGATE_TIMEOUT_MS = 20_000;
    /** 面板可交互等待上限：判据是命令桥就绪（{@code window.__codegenOutbox}），不是 load 事件。 */
    public static final double DEFAULT_READY_TIMEOUT_MS = 5_000;
    /** 命令 outbox 轮询间隔：面板按钮反馈的延迟上限（本地 evaluate，开销极低）。 */
    public static final long DEFAULT_COMMAND_POLL_INTERVAL_MS = 100;

    /** 从系统属性解析全套配置（默认值 = 既有 {@code CodegenPanel} 行为，无回归）。 */
    public static CodegenPanelConfig fromSystem() {
        long idleMinutes = Long.getLong(IDLE_TIMEOUT_MINUTES_PROPERTY, DEFAULT_IDLE_TIMEOUT_MINUTES);
        long idleMillis = idleMinutes <= 0 ? 0 : idleMinutes * 60_000L;
        return new CodegenPanelConfig(
                null,                                   // panelResourceDir 由 PanelResource 固定解析（open 时注入）
                panelProfileDir(PROFILE_DIR_PROPERTY),
                idleMillis,
                DEFAULT_LAUNCH_TIMEOUT_MS,
                DEFAULT_NAVIGATE_TIMEOUT_MS,
                DEFAULT_READY_TIMEOUT_MS,
                DEFAULT_COMMAND_POLL_INTERVAL_MS,
                null);                                  // nlsLang 由 open 调用方注入（会话级）
    }

    /** 面板浏览器 profile 目录：默认系统临时目录下固定目录；可用 {@code -Dcodegen.panel.profile.dir} 或
     * 环境变量 {@code CODEGEN_PANEL_PROFILE_DIR} 指定。 */
    private static Path panelProfileDir(String property) {
        String configured = ConfigReader.getProperty(property);
        Path dir = (configured == null || configured.trim().isEmpty())
                ? Path.of(System.getProperty("java.io.tmpdir"), "codegen-panel-profile")
                : Path.of(configured.trim());
        return dir.toAbsolutePath().normalize();
    }
}
