package com.hsbc.cmb.hk.dbb.automation.framework.web.page.scan.engine;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.hsbc.cmb.hk.dbb.automation.framework.common.route.PageRefs;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.scan.model.RoleEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.concurrent.BlockingQueue;

/**
 * 面板宿主窗口：把控制面板从「被测页 document 内的注入式 DOM」搬到一个<b>独立的 BrowserContext / Page</b>
 * （有头 Chromium 下即一个独立 OS 窗口），对齐 {@code page.pause()} Inspector 的隔离架构。
 *
 * <h3>为什么要搬（CDP 实测结论，勿凭直觉回退）</h3>
 * <p>被测页挂着 {@code Overlay.setInspectMode(searchForNode)} 时，<b>整个 frame 树内的指针事件一律不可达</b>：
 * <ul>
 *   <li>主文档 {@code mousemove} 收不到（实测计数 0）⇒ 任何"悬停自动暂停 inspect"方案机制上不成立；</li>
 *   <li>主文档 {@code click} 被浮层吞掉，元素自身的 onclick 不触发 ⇒ 面板「停止拾取」按钮点不动；</li>
 *   <li><b>子 iframe 也不免疫</b>——父 frame 的 inspect 会穿透进去，连 iframe 内部的按钮都会被上报成拾取结果。</li>
 * </ul>
 * 故只要面板 DOM 长在被测页里，就必然「被抓成元素 + 停止不了」。搬到独立窗口后面板页不在被 inspect 的
 * frame 树中，两个问题同时消失。
 *
 * <h3>顺带的性能收益</h3>
 * <p>面板刷新/代码回填的 {@code page.evaluate} 原先跑在<b>被测页主线程</b>上（HSBC 这类重页面会把它堵到
 * 2s 软超时，日志里满屏 {@code pickerEval timed out after 2000ms}）。面板独立成页后，这些 evaluate 跑在
 * 一个只有面板 UI 的轻量文档里，且是<b>独立 Renderer 主线程</b>，不再与被测页争抢。
 *
 * <h3>已知风险（已评估）</h3>
 * <p>{@code BrowserCleanupImpl.closeOrphanContextsForCurrentThread} 会遍历 {@code browser.contexts()}
 * 回收非本线程在用的 context。拾取期间测试线程正阻塞在 {@link RolePickerPanelController#openPanel} 中，
 * 正常流程不会触发该清扫；异常收尾即使扫到，后果也只是面板窗口被关（会话本就终止），且拾取仍可由
 * ESC 热键（{@link RolePickerScripts#INSTALL_STOP_HOTKEY_JS}，装在被测页上）结束，不会锁死会话。
 *
 * <p>本类全部静态访问：面板是整个拾取会话的单一实例，且调用点（同步/命令引擎/代码回填）多为静态方法。
 */
final class RolePickerPanelHost {

    private static final Logger log = LoggerFactory.getLogger(RolePickerPanelHost.class);

    /** 面板窗口宽度（面板 CSS 里的 docked 宽度即为 420，保持一致）。 */
    private static final int PANEL_WIDTH = 420;
    private static final int PANEL_HEIGHT = 900;

    /** 面板所属的独立 BrowserContext（自身即一个独立窗口）。 */
    private static volatile BrowserContext panelCtx;
    /** 面板页。null = 未开启或已关闭。 */
    private static volatile Page panelPage;

    private RolePickerPanelHost() {}

    /** 面板页是否可用（未开启 / 已关闭均返回 false）。 */
    static boolean isAlive() {
        Page p = panelPage;
        if (p == null) return false;
        try {
            return !p.isClosed();
        } catch (Exception e) {
            RolePickerQuiet.ignore("RolePickerPanelHost#isAlive", e);
            return false;
        }
    }

    /** 面板页；不可用返回 null。调用方须自行判空。 */
    static Page page() {
        return isAlive() ? panelPage : null;
    }

    /**
     * 开启面板窗口（幂等：已开启则直接复用）。
     *
     * @param anchor       被测页（仅用于取 Browser；会经 {@link PageRefs} 剥掉录制代理）
     * @param pageClassName 根页类名（面板标题展示）
     * @param nlsFiles     nls 文件路径
     * @param cmdQueue     会话命令队列（面板按钮经 bridge 投递到此）
     * @param javaPickBySig 会话级拾取权威态（pick 桥写入）
     * @return 面板页；开启失败返回 null（调用方应据此终止会话并告警，避免无 UI 的假会话）
     */
    static Page open(Page anchor, String pageClassName, String[] nlsFiles,
                     BlockingQueue<RolePickerBridgeRegistry.CmdEvent> cmdQueue,
                     LinkedHashMap<String, RoleEntry> javaPickBySig) {
        if (isAlive()) return panelPage;
        if (anchor == null) return null;
        try {
            Page real = (Page) PageRefs.unwrap(anchor);
            Browser browser = real.context().browser();
            if (browser == null) {
                log.error("[picker][panel-host] cannot resolve Browser from page, panel window not opened");
                return null;
            }
            BrowserContext ctx = browser.newContext();
            Page p = ctx.newPage();
            p.setViewportSize(PANEL_WIDTH, PANEL_HEIGHT);
            // 命令/拾取/控制台桥：桥接是 context 级注册，且 CTX_CMD_QUEUES.put 在幂等守卫之前，
            // 因此新 context 能挂上同一个 cmdQueue，面板按钮的命令原路回到本会话主循环。
            RolePickerBridgeRegistry.registerContextBridges(ctx, cmdQueue, javaPickBySig);
            // 【顺序关键·实测踩坑】必须先建文档，再【显式 evaluate】注入面板；不能用 addInitScript：
            // newPage 的 about:blank 已经跑过一次 init script（面板会被建出来），但随后的 setContent 走的是
            // document.open/write/close，会整篇替换文档且【不会重跑 init script】⇒ 面板随旧文档一起被丢弃，
            // 现场表现就是"面板窗口打开了、里面全白"。
            p.setContent("<!DOCTYPE html><html><head><meta charset='utf-8'>"
                    + "<style>html,body{margin:0;padding:0}</style></head><body></body></html>");
            // 显示门禁：面板页是独立源（opaque origin），localStorage 读写会抛异常，panel-core-a.js 的门禁
            // 因此只能走 window.__rolePanelForce 兜底 —— 必须在面板脚本【之前】置位。
            RoleElementPicker.pickerEval(p, RolePickerScripts.PANEL_FORCE_AND_ENABLE_JS);
            // 在当前文档里构建面板 UI（面板页此后不会导航，故只需这一次；万一被清空由 ensurePanelVisible 自愈）。
            RoleElementPicker.pickerEval(p, RolePickerScripts.PANEL_SCRIPT);
            // 自检：面板根节点没挂上就立刻告警，避免又变成"窗口白屏、却只在后续环节莫名超时"的排查黑洞。
            Object panelMounted = RoleElementPicker.pickerEvalSoft(p,
                    "() => !!document.getElementById('__rolePanel')");
            if (!Boolean.TRUE.equals(panelMounted)) {
                log.warn("[picker][panel-host] panel root #__rolePanel not mounted after injection "
                        + "— the panel window will stay blank (check panel-core-a/b.js errors).");
            }
            // 面板标题/文件名等展示数据（面板侧与被测页无关，随面板注入即可）。
            RoleElementPicker.pickerEval(p, RolePickerScripts.SET_NLS_FILES_JS,
                    RolePickerScripts.args(RolePickerConstants.STATE_KEY_FILES, nlsFiles));
            RoleElementPicker.pickerEval(p, RolePickerScripts.SET_PAGE_NAME_AND_RESET_INSTANCE_JS,
                    RolePickerScripts.args(RolePickerConstants.STATE_KEY_PAGE_NAME, pageClassName));
            panelCtx = ctx;
            panelPage = p;
            log.info("[picker][panel-host] panel window opened in a separate BrowserContext (own renderer thread): "
                    + "panel UI is outside the inspected frame tree => immune to Overlay inspect interception.");
            return p;
        } catch (Exception e) {
            log.error("[picker][panel-host] failed to open panel window: {}", e.getMessage());
            close();
            return null;
        }
    }

    /** 面板侧 evaluate（长超时，用于用户显式命令触发的回填，如代码生成）。 */
    static Object eval(String script) {
        Page p = page();
        return p == null ? null : RoleElementPicker.pickerEval(p, script);
    }

    static Object eval(String script, Object arg) {
        Page p = page();
        return p == null ? null : RoleElementPicker.pickerEval(p, script, arg);
    }

    /** 面板侧 evaluate（短超时 2s；面板页极轻，正常为毫秒级，超时即跳过本轮、下一轮重投）。 */
    static Object evalSoft(String script) {
        Page p = page();
        return p == null ? null : RoleElementPicker.pickerEvalSoft(p, script);
    }

    static Object evalSoft(String script, Object arg) {
        Page p = page();
        return p == null ? null : RoleElementPicker.pickerEvalSoft(p, script, arg);
    }

    /** 关闭面板窗口并释放其 BrowserContext（幂等；异常不外抛）。 */
    static void close() {
        Page p = panelPage;
        panelPage = null;
        if (p != null) {
            try { p.close(); } catch (Exception ignore) {
                RolePickerQuiet.ignore("RolePickerPanelHost#closePage", ignore);
            }
        }
        BrowserContext ctx = panelCtx;
        panelCtx = null;
        if (ctx != null) {
            try { ctx.close(); } catch (Exception ignore) {
                RolePickerQuiet.ignore("RolePickerPanelHost#closeContext", ignore);
            }
        }
    }
}
