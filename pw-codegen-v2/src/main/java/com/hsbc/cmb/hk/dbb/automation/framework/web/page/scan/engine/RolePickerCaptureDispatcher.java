package com.hsbc.cmb.hk.dbb.automation.framework.web.page.scan.engine;

import com.google.gson.JsonObject;
import com.microsoft.playwright.Page;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.scan.model.RoleEntry;

/**
 * CDP 捕获调度：把一次 CDP 点选（{@code Overlay.inspectNodeRequested}）转化为一次拾取并写入权威态。
 *
 * <p>流程：反查节点 → 构造 {@link RoleEntry} → 以 {@code sigKey} 合并进 {@link RolePickerContext#javaPickBySig}
 * （与 JS 注入通道同一权威态，故两套通道天然去重、共用面板与生成链路）→ 即时回灌面板，使 CDP 捕获的元素
 * 与 JS 注入通道在 UI / 产物上完全一致。
 *
 * <p>会话上下文经 {@link RolePickerPageTracker#contextFor} 反查（CDP 通道不自行持有 ctx，避免与面板主循环竞争）。
 */
final class RolePickerCaptureDispatcher {

    private static final Logger log = LoggerFactory.getLogger(RolePickerCaptureDispatcher.class);

    void onInspect(RolePickerCdpSession session, JsonObject inspectParams) {
        Page page = session.page();
        RolePickerContext ctx = RolePickerPageTracker.contextFor(page.context());
        if (ctx == null) {
            log.warn("[picker][cdp] no active picker context for page {}; dropping inspect event.", page.url());
            return;
        }
        String pageClass = ctx.pageNames.getOrDefault(page, "");
        RolePickerCdpNodeExtractor extractor = new RolePickerCdpNodeExtractor(page, pageClass, ctx.nlsReverseJson);
        RoleEntry entry = extractor.extract(session, inspectParams);
        if (entry == null) return;
        synchronized (ctx.javaPickBySig) {
            ctx.javaPickBySig.put(entry.getSigKey(), entry);
        }
        try {
            RolePickerPanelSync.syncPanelToBrowser(page, null, ctx.javaPickBySig);
        } catch (Exception ignore) {
            RolePickerQuiet.ignore("RolePickerCaptureDispatcher#sync", ignore);
        }
        log.info("[picker][cdp] captured element via CDP: {}", entry);
    }
}
