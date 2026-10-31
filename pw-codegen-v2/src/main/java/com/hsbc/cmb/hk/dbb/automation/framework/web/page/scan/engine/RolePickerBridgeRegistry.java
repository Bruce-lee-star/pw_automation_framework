package com.hsbc.cmb.hk.dbb.automation.framework.web.page.scan.engine;

import com.google.gson.Gson;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Frame;
import com.microsoft.playwright.Page;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.scan.model.RoleEntry;

/**
 * 上下文级「浏览器桥 / 面板命令抽干」注册中心（T5-1 拆分）：
 * 把 {@link RoleElementPicker} 中"向 Playwright {@link BrowserContext} 一次性注册命令桥 /
 * 拾取桥 / 删除桥 / 控制台兜底桥 / 动态 iframe 监听，以及面板命令队列抽干"等
 * 【扫描/捕获桥】职责收敛到本类，使 {@link RoleElementPicker} 退化为拾取编排门面。
 *
 * <p>行为与原实现逐字等价：仅物理搬移，逻辑 / 日志标签 / 去重键口径完全不变。
 * 桥方法经 {@link RoleElementPicker#pickerEval} 与浏览器交互（同包静态调用）。
 */
final class RolePickerBridgeRegistry {

    static final Logger log = LoggerFactory.getLogger(RolePickerBridgeRegistry.class);

    /** 反序列化任意拾取态 JSON 的 Gson 实例（桥方法统一用其解析回传）。 */
    static final Gson GSON = new Gson();

    /**
     * 拾取回调→面板「即时回灌」线程池（事件驱动，替代主循环 1s 轮询）。
     *
     * <p>背景：原设计把拾取权威态放在 Java 业务层（javaPickBySig），浏览器侧面板只是展示页面对象层；
     * 但面板刷新只发生在主循环 {@code poll(1s)} 的空闲轮里，遇 busy 页 / 跨域 iframe 的硬超时 evaluate 时
     * 整轮被卡，导致"点了元素、面板列表 1~12s 才出现"。
     * 现改为：拾取桥回调合并进权威态后，<b>立即</b>异步把面板回灌推回浏览器（页面对象层），与分层无冲突。</p>
     *
     * <p>设计约束：exposeBinding 回调跑在 Playwright 的<b>调度线程</b>上，绝不能在此线程内同步做
     * {@code page.evaluate}（会阻塞同上下文其余事件），故用<b>单线程 daemon</b>线程池把
     * {@code syncPanelToBrowser} 异步化；其内部用 soft 2s 上限超时、ETag 幂等，失败仅是下一轮补回。
     * 队列有界 + DiscardOldestPolicy：极端 busy 页下丢弃最旧待回灌，下一轮（主循环 / 下次点击）自然补上，不堆积。</p>
     */
    static final ExecutorService PICK_SYNC_POOL =
            new ThreadPoolExecutor(1, 1, 30L, TimeUnit.SECONDS,
                    new LinkedBlockingQueue<>(64),
                    r -> { Thread t = new Thread(r, "picker-pick-sync"); t.setDaemon(true); return t; },
                    new ThreadPoolExecutor.DiscardOldestPolicy());


    /**
     * 面板命令事件：由页面内 {@code window.__rolePickerCmd(c)}（经 {@link Page#exposeFunction} 暴露的
     * Java 回调）投递，携带"哪个页面发的命令"。主循环从阻塞队列取出后据此驱动，避免忙轮询所有页面。
     */
    static final class CmdEvent {
        final Page page;
        final String cmd;
        CmdEvent(Page page, String cmd) { this.page = page; this.cmd = cmd; }
    }

    /**
     * 把"面板命令桥 + 点击拾取桥 + 控制台兜底桥"一次性注册到 {@link BrowserContext}
     * （对齐 {@code page.pause()}：Recorder 用 {@code context.exposeBinding} 注册跨页面/跨导航存活的回传桥）。
     * <ul>
     *   <li>命令桥：页面内 {@code window.__rolePickerCmd(c)} → (来源 page, cmd) 投递进阻塞队列，主循环事件驱动消费；</li>
     *   <li>拾取桥：页面内 {@code window.__roleOnPick(json)} → 单个拾取元素 O(1) 回传进 Java 权威内存态；</li>
     *   <li>控制台桥：捕获 {@code __roleOnPick::<json>} 兜底回传（exposeBinding 异常失效时拾取不丢失）。</li>
     * </ul>
     * context 下所有当前与未来页面自动持有绑定，无需逐页注册/跟踪；每个 context 仅注册一次（幂等），
     * 会话级队列/状态经 CTX_* Map 动态解析，二次打开只换 Map 指向。
     */
    static void registerContextBridges(BrowserContext ctx,
                                               BlockingQueue<CmdEvent> queue,
                                               LinkedHashMap<String, RoleEntry> javaPickBySig) {
        RolePickerSessionState.CTX_CMD_QUEUES.put(ctx, queue);
        // 【关键修复"二次 openPanel 导致已拾元素清零"】二次 openPanel（设计上支持，见类注释"二次打开只换 Map 指向"）
        // 会 new 一个空 javaPickBySig 并传入；若此处直接 put 覆盖，context 权威内存态会被空 map 替换，
        // 后续 __roleOnPick 回调（RolePickerSessionState.CTX_PICK_STATES.get(ctx)）全部写进空 map → 此前 LogonPage+SetupSecondPwdPage
        // 已拾的全部元素丢失（日志现象：内存态 49→1）。故二次打开时把旧会话历史迁移合并进本次 map（保留去重顺序），
        // 让 openPanel 后续代码（RolePickerSessionState.CTX_PICK_STATES / 命令队列等）拿到"含历史"的同一引用，历史不丢。
        LinkedHashMap<String, RoleEntry> prev = RolePickerSessionState.CTX_PICK_STATES.get(ctx);
        // 【diag-migrate】追踪跨会话迁移是否把脏 pickNos/seq 带入本轮（定位 user_name 首号恒为 2 的根因）。
        if (prev != null) {
            for (java.util.Map.Entry<String, RoleEntry> e : prev.entrySet()) {
                if (e.getValue() != null && (e.getKey() != null && e.getKey().contains("user_name"))) {
                    log.info("[picker][diag-migrate] prev key={} pickNos={} seq={} (prev==javaPickBySig? {})",
                            e.getKey(), e.getValue().getPickNos(), e.getValue().getSeq(), (prev == javaPickBySig));
                }
            }
        }
        if (prev != null && prev != javaPickBySig) {
            for (java.util.Map.Entry<String, RoleEntry> e : prev.entrySet()) {
                if (!javaPickBySig.containsKey(e.getKey())) {
                    // 【修复"跨会话脏序号污染本轮（后拾取元素首号偏小，如 user_name 拿到 2 而非 4/5）"】
                    // 旧实现直接复用 prev 的 RoleEntry 引用（含上一轮被旧 bug 污染的 pickNos，如 [2]）。
                    // 新会话是全新拾取，序号需按本轮点击顺序重新生成；若沿用 prev 的 [2]，syncPanelToBrowser
                    // 会用它覆盖浏览器侧本轮正确累计的 [5]，最终 step 序号错乱。
                    // 修复：迁移历史仅为"保留已拾元素不丢"（见上方二次 openPanel 场景），但其 pickNos 必须清零，
                    // 让面板回退到 [-]，本轮重新点击时从干净状态按真实次序重新累计，杜绝脏序号串台。
                    RoleEntry migrated = e.getValue();
                    if (migrated != null) { migrated.setPickNos(new java.util.ArrayList<>()); migrated.setSeq(0); }
                    javaPickBySig.put(e.getKey(), migrated);
                }
            }
        }
        RolePickerSessionState.CTX_PICK_STATES.put(ctx, javaPickBySig);
        boolean first = RolePickerSessionState.CTX_BRIDGED.add(ctx);
        log.info("[picker] context bridges registerContextBridges: firstReg={} (command/pick/console bridges, registered once per context)", first);
        if (!first) return;
        // 命令桥：BindingCallback 的 Source 自带来源 Page，天然区分命令来自哪个页面
        // （新页/默认页共享同一绑定，CmdEvent.page 记录来源）。绑定对 context 下所有页面、所有导航存活。
        registerCmdBridge(ctx);
        // 控制台兜底桥：context 级 onConsoleMessage 捕获所有页面的兜底回传与拾取链路报错，即使绑定失效回传也不丢失。
        registerConsoleBridge(ctx);
    }

    /**
    /** 命令桥：浏览器侧经 window.__rolePickerCmd 投递，零往返把命令入队；BindingCallback 的 Source 自带来源 Page，天然区分命令来自哪个页面。 */
    private static void registerCmdBridge(BrowserContext ctx) {
        ctx.exposeBinding("__rolePickerCmd", (source, args) -> {
            BlockingQueue<CmdEvent> q = RolePickerSessionState.CTX_CMD_QUEUES.get(ctx);
            if (q == null) return null;
            String c = null;
            try {
                if (args != null && args.length > 0) {
                    Object v = args[0];
                    if (v == null) {
                        c = null;
                    } else if (v instanceof String) {
                        // 浏览器侧若以 JSON 字符串传入（如 window.__rolePickerCmd(JSON.stringify({...}))），原样使用
                        c = (String) v;
                    } else {
                        // Playwright 会把 JS 对象反序列化为 Java Map/List/Number/Boolean，
                        // 其 toString() 不是合法 JSON（单等号、无引号），直接 Gson 解析会抛 MalformedJsonException。
                        // 故统一用 GSON 规范序列化为 JSON 字符串再入队（markList 等对象命令依赖此路径）。
                        c = GSON.toJson(v);
                    }
                }
            } catch (Exception ignore) { RolePickerQuiet.ignore("RolePickerBridgeRegistry", ignore); }
            q.offer(new CmdEvent(source.page(), c));
            return null;
        });
    }



    /** 控制台兜底桥：context 级 onConsoleMessage 捕获所有页面的 __roleOnPick:: 兜底回传与拾取链路报错，即使绑定失效回传也不丢失。元素删除已收敛为 __rolePickerCmd(type='delete') 经命令桥处理。 */
    private static void registerConsoleBridge(BrowserContext ctx) {
        // 【F4 续：context 级 dialog 兜底 —— context 级"唯一处理点"】
        // 逐页 page.onDialog 只覆盖"已被 picker 跟踪并注册过的页"；未跟踪页 / 注册完成前弹出的
        // alert-confirm-prompt 此前无人处理（只依赖 Playwright 对未监听弹窗的默认 dismiss）。这里在
        // context 级补一道，覆盖该 context 下所有页；口径与逐页版一致：beforeunload 放行且不记录、
        // confirm/prompt 拒绝、其余接受。
        // 【为什么不会重复处理而丢标记】逐页版的 accept/dismiss 已单独包 try/catch（见
        // RolePickerPanelController 的 page.onDialog）：本处若先关掉弹窗，逐页版那次 accept/dismiss 只会
        // 抛 "No dialog is showing" 被吞掉，而其后的"回填 dialog 标记"照常执行（该标记只依赖
        // window.__lastPickSig，不依赖 dialog 是否仍处于打开状态），故标记不会丢，最多多一条 debug。
        ctx.onDialog(dialog -> {
            try {
                if (dialog == null) return;
                String t = dialog.type() != null ? dialog.type().toLowerCase() : "alert";
                if ("beforeunload".equals(t)) {
                    dialog.accept();
                    return;
                }
                if ("confirm".equals(t) || "prompt".equals(t)) dialog.dismiss();
                else dialog.accept();
                log.info("[picker] context-level onDialog handled a {} dialog on {}", t,
                        dialog.page() != null ? dialog.page().url() : "(unknown page)");
            } catch (Exception ex) {
                log.warn("[picker] context-level onDialog handling error (ignored): {}", ex.getMessage());
            }
        });
        ctx.onConsoleMessage(msg -> {
            String t = msg.text();
            if (t == null) return;
            // 【诊断通道】把浏览器侧以 [picker-diag] 开头的 console 输出原样转发进 Java 日志。
            // 此前只捕获 __roleOnPick:: 前缀，页面侧诊断（如 [picker-diag][mint-stack] 铸号调用栈探针）
            // 在 Java 日志里完全看不到，排查时被误判为"探针未触发"。带前缀即可被转发，便于一份日志定位。
            // 【优化-降噪】[picker-diag]* 是每次点击都会触发的探针行（record/mint/seq-baseline 等），
            // 长会话下会刷屏；默认级别降到 DEBUG，需要复现拾取/序号问题再开 DEBUG 查看，INFO 只保留
            // start/stop/nav 等生命周期事件。
            if (t.startsWith("[picker-diag]")) {
                log.debug("[browser] {}", t);
                return;
            } else if (t.startsWith("__roleOnUrlChanged::")) {
                // 浏览器侧 URL 变化信号（gate-init.js 在 pushState/replaceState/popstate/hashchange 时发出）：
                // History API 导航不触发 onFrameNavigated，页类名不会自动刷新 ⇒ 新页拾取的元素会被打上旧页类
                // （现场："url change 没有创建新页面、所有元素都在 LogonPage"）。此处反查本会话上下文，对来源页
                // 调度一次 debounced 自愈（refreshPageClass 即时刷新页类 + iframe 聚合 + 面板回灌），
                // 事件驱动补齐该空档，无需恢复轮询。
                RolePickerContext pc = RolePickerPageTracker.contextFor(ctx);
                if (pc != null) {
                    Page urlChangedPage = msg.page();
                    if (urlChangedPage != null && !urlChangedPage.isClosed()) {
                        RolePickerPanelController.scheduleSelfHeal(pc, urlChangedPage);
                    }
                }
            } else if ("error".equals(msg.type())
                    && (t.contains("rolePick") || t.contains("__record") || t.contains("__role"))) {
                log.info("[browser][error] {}", t);
            } else if (t.startsWith("[roleMouseDiag]")) {
                // 鼠标事件诊断（mousedown/up/dblclick/contextmenu）实时转发，前缀过滤避免误吞其它字节。
                // 拾取过程中每条鼠标动作一条 ⇒ 持续刷屏（实测每次点击 1~2 条），故降为 DEBUG：
                // 默认级别下静默，需要复现鼠标/拾取问题时再开 DEBUG 查看。
                log.debug("[browser]{}", t);
            }
        });
    }



    /**
     * 抽干浏览器端兜底命令队列 window.__panelCmds 并入 Java 命令队列 cmdQueue。
     * 当 exposeFunction 绑定（window.__rolePickerCmd）尚未就绪时，面板按钮的 pushCmd 会把命令
     * 暂存进 window.__panelCmds（见 PANEL_SCRIPT）。若不消费，这些命令会静默丢失，导致"点了开始却
     * 拾取不了"。此处由 Java 主循环周期性抽干，保证命令零丢失。与 exposeFunction 投递幂等、不会重复。
     */
    static void drainPanelCmds(Page page, BlockingQueue<CmdEvent> cmdQueue) {
        if (page == null || page.isClosed() || cmdQueue == null) return;
        try {
            Object raw = RoleElementPicker.pickerEval(page, RolePickerScripts.DRAIN_PANEL_CMDS_JS);
            if (raw instanceof List) {
                for (Object o : (List<?>) raw) {
                    String c = o == null ? null : o.toString();
                    cmdQueue.offer(new CmdEvent(page, c));
                }
            }
        } catch (Exception ignore) { RolePickerQuiet.ignore("RolePickerBridgeRegistry", ignore); }
    }
}
