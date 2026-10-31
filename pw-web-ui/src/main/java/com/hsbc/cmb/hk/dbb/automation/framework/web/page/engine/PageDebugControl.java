package com.hsbc.cmb.hk.dbb.automation.framework.web.page.engine;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 页面调试控制（企业级封装单元，WEB-P1-2 Phase 6 收口）。
 *
 * <p>承载 BasePage 的「本地可调试环境识别」与「安全 {@code pause()} 控制」逻辑，
 * 避免调试能力散落在门面类中。原 BasePage 相关代码逐字迁移，行为零变更。
 *
 * <h3>Java 三大特性取舍（呼应 WEB-P1-6 复盘）</h3>
 * <ul>
 *   <li><b>封装 ✅</b>：调试环境判定与 pause 副作用收敛到本单元，日志统一路由回
 *       {@link BasePage#logger} 以保持生产溯源一致。</li>
 *   <li><b>继承 ❌</b>：本类为 {@code final}，不存在可替换子类层次（组合优于继承）。</li>
 *   <li><b>多态 🔶</b>：纯静态工具式封装，单一实现，无多实现需求，不强行引入接口。</li>
 * </ul>
 *
 * @apiNote Framework-internal — 由 BasePage 委派调用，业务代码请勿直接依赖。
 */
public final class PageDebugControl {

    private static final Logger logger = LoggerFactory.getLogger(BasePage.class);

    /**
     * 共享「调试/录制暂停态」信号：原生 {@link #pause(BasePage)} 与自研 codegen 面板（pw-codegen 的
     * RolePickerPanelController）皆可发布/读取，使两套系统对齐 page.pause() 的调试态（③联动基础，无反向依赖）。
     */
    private static final ThreadLocal<Boolean> PAUSED = new ThreadLocal<>();

    /**
     * 共享「codegen 面板会话活跃」信号：自研 codegen 面板（pw-codegen 的 RolePickerPanelController）
     * 经 {@code context.newCDPSession(page)} 独占 {@code Overlay.inspectNodeRequested}。若同时调原生
     * {@code page.pause()}（会拉起 Inspector 的第二条 CDP 连接也监听同一 Overlay 事件）将双触发同一点选 ⇒ 冲突。
     * 该信号让原生 {@link #pause(BasePage)} 在 codegen 活跃时跳过原生 pause，避免冲突（同模块，无反向依赖）。
     */
    private static final ThreadLocal<Boolean> CODEGEN_ACTIVE = new ThreadLocal<>();

    private PageDebugControl() {
        // 纯静态工具单元，禁止实例化
    }

    /**
     * 判断当前是否为可调试本地环境。
     * @return true=本地允许 pause；false=Jenkins/BrowserStack 禁止暂停
     */
    static boolean isDebugEnvironment() {
        // 1. 识别 BrowserStack 云端环境
        boolean isBsEnv = System.getenv().containsKey("BROWSERSTACK_USERNAME")
                || System.getenv().containsKey("BROWSERSTACK_ACCESS_KEY");

        // 2. 识别 Jenkins CI 环境
        boolean isJenkinsEnv = System.getenv().containsKey("JENKINS_HOME")
                || System.getProperty("ci", "false").equalsIgnoreCase("true");

        // 云端/CI 直接判定为非调试环境
        return !isBsEnv && !isJenkinsEnv;
    }

    /**
     * 安全暂停方法：本地 IDE 正常 pause 调试；Jenkins / BrowserStack 自动跳过，杜绝流程阻塞。
     * <p>当且仅当为可调试本地环境时才访问页面 {@code pause()}，避免在非调试环境触发页面初始化副作用。
     * 若自研 codegen 面板会话正处于活跃（其 CDP 会话已独占 {@code Overlay.inspectNodeRequested}），
     * 跳过原生 {@code page.pause()}（不再拉起 Inspector 的第二条 CDP 连接），仅保持共享暂停态，
     * 避免两条 CDP 连接双监听同一 Overlay 事件造成点选被重复处理。
     *
     * @param bp 当前页面对象（用于获取 Playwright Page）
     */
    static void pause(BasePage bp) {
        if (isDebugEnvironment()) {
            if (isCodegenActive()) {
                // codegen 面板已用 newCDPSession 独占 Overlay 监听；再开原生 page.pause() 会拉起 Inspector
                // 的第二条 CDP 连接 → 双监听冲突。故跳过原生 pause（不打开 Inspector），共享暂停态已由面板
                // markPaused(true)，此处仅幂等保活。
                logger.warn("[PageDebugControl] codegen panel session active; skip native page.pause() to avoid duplicate Overlay listeners (Inspector + codegen CDP conflict)");
                markPaused(true);
            } else {
                try {
                    bp.getPage().pause();
                    markPaused(true); // 发布共享「调试/录制暂停态」信号（③联动：对齐 page.pause 调试态）
                } catch (Exception e) {
                    logger.warn("Page pause failed, skip debug pause", e);
                }
            }
        } else {
            logger.warn("[Security Control] Jenkins/BrowserStack environment, auto skip pause() to avoid block");
        }
    }

    /**
     * 读取当前线程是否处于「调试/录制暂停态」：原生 {@code page.pause()} 或自研 codegen 面板会话均可发布。
     * 供 codegen 面板等消费，实现与 page.pause() 调试态联动，无需反向依赖。
     */
    public static boolean isPaused() {
        return Boolean.TRUE.equals(PAUSED.get());
    }

    /** 发布/清除共享「调试/录制暂停态」信号。 */
    public static void markPaused(boolean v) {
        if (v) {
            PAUSED.set(true);
        } else {
            PAUSED.remove();
        }
    }

    /**
     * 读取当前线程是否处于 codegen 面板会话（其 CDP 会话已独占 Overlay 监听）。
     * 供 {@link #pause(BasePage)} 判定是否跳过原生 page.pause()，避免与 Inspector 双监听冲突。
     */
    public static boolean isCodegenActive() {
        return Boolean.TRUE.equals(CODEGEN_ACTIVE.get());
    }

    /** 发布/清除「codegen 面板会话活跃」信号（由 RolePickerPanelController 在 openPanel 起止调用）。 */
    public static void markCodegenActive(boolean v) {
        if (v) {
            CODEGEN_ACTIVE.set(true);
        } else {
            CODEGEN_ACTIVE.remove();
        }
    }
}
