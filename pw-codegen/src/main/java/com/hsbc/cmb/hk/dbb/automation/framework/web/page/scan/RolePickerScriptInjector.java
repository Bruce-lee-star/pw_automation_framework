package com.hsbc.cmb.hk.dbb.automation.framework.web.page.scan;

import com.google.gson.Gson;
import com.microsoft.playwright.Frame;
import com.microsoft.playwright.Page;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Browser-side script injection orchestration (T5-1 step 3, cluster 2): relocates
 * RoleElementPicker's frame/script injection logic into this class. Inputs are a Playwright
 * Page/Frame plus the nls reverse JSON; it wires onFrameAttached/onFrameNavigated listeners,
 * evaluates the gated picker init script, and builds that script string. It performs no CTX_*
 * state access and no picker command handling; behavior is identical to the original inline logic.
 */
final class RolePickerScriptInjector {

    private static final Logger log = LoggerFactory.getLogger(RolePickerScriptInjector.class);
    private static final Gson GSON = new Gson();

    /**
     * 门控初始化脚本模板（CG-P2-N12 外置为资源 {@code /scan/js/gate-init.js}）。
     * 占位符在运行时替换：{@code __START_SCRIPT__}=核心拾取脚本、{@code __FORCE__}=是否强制注入、
     * {@code __NLS_JSON__}=NLS 反查表 JSON 字符串字面量。模板本身经构建期 JS 语法校验。
     */
    private static final String GATE_INIT_TEMPLATE = RolePickerScripts.loadScript("gate-init.js");

    /**
     * 已为哪些 page 挂过 frame 注入监听器。Key 用弱引用：page 关闭后即可被 GC，不泄漏。
     * 见 {@link #registerFrameInjection} 的节流说明——原实现每轮空闲（~1s）都重新遍历全 frame 重灌，
     * 并再挂一组 onFrameAttached/onFrameNavigated 监听器；长跑会话下监听器线性累积、每次 frame 事件触发
     * 全部，工作量随会话时长 O(ticks²) 膨胀，表现为拾取越久越卡。
     */
    private static final java.util.Set<Page> FRAME_INJECT_HOOKED =
            java.util.Collections.newSetFromMap(new java.util.WeakHashMap<Page, Boolean>());
    /**
     * 为指定页面注入拾取脚本到其所有 frame（含主框架与同源 iframe），并注册 frame 监听器，
     * 使「start 之后动态附加的 iframe」一出现即自动注入。门控脚本仅在拾取会话开启时挂载 RolePickerScripts.START_SCRIPT，
     * 对未参与拾取的页面零侵入。nls 反向表可为 null（仅影响 NLS key 反查，不影响拾取本身）。
     *
     * 复合嵌套 frame 场景（frame 内嵌 frame，多层）：Playwright 的 Frame 接口【没有】onFrameAttached /
     * onFrameNavigated（仅 Page 有），而 Page 级监听的参数 frame 虽只"直接挂名"于主框架，但【任意深度的
     * 子 frame 发生导航时，Page.onFrameNavigated 都会以该子 frame 本身为参数触发】。因此本实现以
     * onFrameNavigated 为核心覆盖信号：每次导航（含嵌套 frame 首次加载）到来即对该 frame 注入，并额外对
     * page.frames()（递归返回全部层）做一次全量补注入兜底，确保 A 内嵌 B、B 内嵌 C 任意深度的 frame 均被注入；
     * onFrameAttached 则负责"动态附加的 iframe"在 about:blank 阶段先注入一层，并遍历其 childFrames() 立即
     * 补入已存在的更深层 frame。
     */
    static void registerFrameInjection(Page page, String nlsReverseJson) {
        if (page == null || page.isClosed()) return;
        // 【节流】onFrameAttached/onFrameNavigated 监听器一旦挂上即存活到 page 关闭，足以覆盖
        // "动态附加的 iframe""子 frame 导航"等场景；frame 的导航/新建早已不需每轮重灌。故每 page 仅
        // 首次登记时做"全 frame 补注 + 挂监听"，之后幂等返回——消灭监听器泄漏与每轮 N 次 evaluate 重灌
        // （原每轮重挂一组监听器，长跑会话下工作量随会话时长 O(ticks²) 膨胀，拾取越久越卡）。
        boolean firstTime;
        synchronized (FRAME_INJECT_HOOKED) {
            firstTime = FRAME_INJECT_HOOKED.add(page);
        }
        if (!firstTime) return;
        try {
            // page 级 addInitScript：Playwright 会在该 page 的【主框架与所有（含嵌套/跨源）子 frame、每次导航】
            // 的文档起始自动执行 gatedPickerInitScript(force=true)，原生覆盖"动态 iframe / 子 frame 导航 /
            // 跨源弹窗 iframe"等场景（跨源子 frame 有独立 localStorage、读不到 context 级门控脚本的会话标记，
            // 故必须用 force=true 兜底）。复杂度从 O(ticks·pages·frames) 降到 O(1)（仅注册一次），无监听器泄漏。
            // 持久性：addInitScript 跟随 page 生命周期；force 注入的库在 __rolePickActive 未置位时本就惰性不拾取，无害。
            page.addInitScript(gatedPickerInitScript(nlsReverseJson, true));
        } catch (Exception ignore) { RolePickerQuiet.ignore("RolePickerScriptInjector", ignore); }
    }

    /** 对单个 frame 注入拾取脚本：Playwright 的 Frame 无 addInitScript（仅 Page 有），
     *  故对当前已就绪文档直接 evaluate 注入；后续导航由 onFrameNavigated 监听兜底重新注入。
     *  以 force=true 注入门控脚本：动态/子 frame 是独立 window，其会话开关标记与父页不互通
     *  （file:// 下 origin 不同、execution context 随文档切换重置），若仍走 on 判定会误判 false 而 return，
     *  导致 __renumberStep 等依赖未定义。force=true 跳过判定、直接执行 RolePickerScripts.START_SCRIPT，确保子 frame 内拾取依赖完整。
     *  仅影响已开启拾取会话期间注入的 frame，对无关页面无副作用。 */
    static void frameInjectOnce(Frame frame, String nlsReverseJson) {
        // 连接/页面已关闭或 frame 已分离时，evaluate 必然抛 PlaywrightException: connection closed，
        // 且监听器在浏览器关闭/导航销毁期间会被反复触发，若在此刷屏会大量污染日志。
        // 先在注入前做廉价的有效性检查，无效则静默返回。
        if (frame == null) return;
        try {
            Page owner = frame.page();
            if (owner == null || owner.isClosed()) return;
        } catch (Exception ignore) { return; }
        if (frame.isDetached()) return;
        try {
            frame.evaluate(gatedPickerInitScript(nlsReverseJson, true));
        } catch (Exception ex) {
            // 动态/子 frame 注入失败的两种性质需区分：
            //  (a) 真不可注入：frame 已关闭/已分离 → 永久跳过，丢弃。
            //  (b) 执行上下文竞态：子 frame 在 onFrameAttached 的 about:blank 阶段、或跨源 frame 文档切换瞬间，
            //       execution context 尚未就绪，evaluate 抛 "Execution context was destroyed" / "frame was detached"。
            //       这类是【暂时性】的——onFrameNavigated 兜底虽也会调本方法，但若导航事件与 context 就绪仍有微小错位，
            //       跨源/动态 iframe 可能在本会话内再无机会注入，表现为"iframe 内元素点不到"。
            // 优化：对 (b) 立即重试一次（同方法再 evaluate 一次）。onFrameNavigated 触发时 context 通常已就绪，
            // 首轮失败多为 about:blank 残留，重试一次即可成功，无需引入后台轮询线程。
            // 若重试仍失败，判为 (a) 真不可注入，静默跳过（用 log.debug 避免连接关闭时海量刷屏）。
            String url;
            try { url = frame.url(); } catch (Exception urlEx) { url = "<closed>"; }
            boolean detached = false;
            try { detached = frame.isDetached(); } catch (Exception ignore) { RolePickerQuiet.ignore("RolePickerScriptInjector", ignore); }
            if (!detached) {
                try {
                    frame.evaluate(gatedPickerInitScript(nlsReverseJson, true)); // 竞态重试一次
                    if (log.isDebugEnabled()) {
                        log.debug("[frameInjectOnce][retry-ok] retry succeeded after first-round race, frame={}", url);
                    }
                    return;
                } catch (Exception ex2) {
                    if (log.isDebugEnabled()) {
                        log.debug("[frameInjectOnce][skip] frame={} : {}", url, ex2.toString());
                    }
                }
            } else if (log.isDebugEnabled()) {
                log.debug("[frameInjectOnce][skip-detached] frame={} : {}", url, ex.toString());
            }
        }
    }

    /**
     * 门控式拾取初始化脚本（对齐 {@code page.pause()} 的 Recorder：拾取脚本经 context 注入脚本
     * 在【每个新文档】自动重跑，跨导航/弹窗/新标签页由浏览器原生保证监听重挂，无需 Java 端手动跟踪）。
     * 门控：仅当"会话拾取开关"（localStorage __rolePickSessionOn，由 start/stop 置位/清除）打开时
     * 才注入 nls 反向表并挂载 RolePickerScripts.START_SCRIPT；未拾取时新文档零侵入。
     */
    static String gatedPickerInitScript(String nlsReverseJson) {
        return gatedPickerInitScript(nlsReverseJson, false);
    }

    /**
     * 门控初始化脚本。force=true 时跳过"会话开关"判定、直接执行 RolePickerScripts.START_SCRIPT（定义 __renumberStep 等全套函数），
     * 用于"运行时新附加/导航的 iframe"：这类子 frame 是独立 window，其 localStorage/window 会话标记与父页不互通
     * （file:// 下不同路径 origin 不同、execution context 随文档切换重置），若仍走 on 判定会误判为 false 而直接 return，
     * 导致 __recordPick 调用 __renumberStep 报 "is not a function"。动态 iframe 的注入由 frameInjectOnce 以 force=true 调用，
     * 确保子 frame 内的拾取依赖完整；仅影响已开始拾取会话期间注入的 frame，对无关页面无副作用。
     */
    static String gatedPickerInitScript(String nlsReverseJson, boolean force) {
        // CG-P2-N12：脚本主体外置为资源 scan/js/gate-init.js（无 Java 内联 JS），运行时仅做占位符替换。
        // 与旧实现字节级等价：__START_SCRIPT__/__FORCE__/__NLS_JSON__ 三处占位对应原内联的
        // RolePickerScripts.START_SCRIPT、force 布尔、GSON.toJson(nls) 内联。
        // 注：gate-init.js 由 tools/gen_picker_scripts.js 从「原内联门控脚本」重建（该内联源存档于
        // tools/gate-inline-source.txt）。因本方法已外置为 GATE_INIT_TEMPLATE.replace(...)，若直接从本方法
        // 反推模板会陷入循环依赖，故生成期回落到上述存档内联源，保证 gate-init.js 字节级等价于旧实现。
        return GATE_INIT_TEMPLATE
                .replace("__START_SCRIPT__", RolePickerScripts.START_SCRIPT)
                .replace("__FORCE__", force ? "true" : "false")
                .replace("__NLS_JSON__", nlsReverseJson == null ? "\"\"" : GSON.toJson(nlsReverseJson));
    }
}