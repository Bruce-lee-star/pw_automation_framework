package com.hsbc.cmb.hk.dbb.automation.framework.web.page.scan.engine;

import com.google.gson.JsonObject;
import com.hsbc.cmb.hk.dbb.automation.framework.common.route.PageRefs;
import com.microsoft.playwright.CDPSession;
import com.microsoft.playwright.Page;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.function.Consumer;

/**
 * 单页 CDP 会话封装（CDP 捕获层改造 Phase 1）。
 *
 * <p>基于 Playwright Java 的 {@code BrowserContext.newCDPSession(page)} 建立与页面渲染进程的直接通道，
 * 启用 {@code DOM}/{@code Overlay}/{@code Accessibility} 域，进入 {@code Overlay.setInspectMode("searchForNode")}；
 * 用户在页面上点选元素时，CDP 通过 {@code Overlay.inspectNodeRequested} 事件直接上报被点节点的
 * {@code backendNodeId}（与 page.pause 的 Inspector 同源），由 {@link RolePickerCdpNodeExtractor} 反查定位信息。
 *
 * <p>所有 CDP 调用均 fail-soft：任一异常仅记日志并降级回 JS 注入，绝不拖住面板主循环或抛到上层。
 */
final class RolePickerCdpSession {

    private static final Logger log = LoggerFactory.getLogger(RolePickerCdpSession.class);

    private final Page page;
    private final CDPSession cdp;

    RolePickerCdpSession(Page page) {
        // V2 修复：框架在录制开启时会把 Page 包成 JDK 动态代理（web 的 RecordingPageProxy /
        // RecordingHandler），而 Playwright 的 BrowserContext.newCDPSession(page) 内部会把 page 强转为
        // PageImpl 以取底层渲染进程连接，代理上强转会抛 ClassCastException（"$ProxyN cannot be cast to
        // PageImpl"），导致 CDP 抓取静默失效、元素拾取不可用。
        // 用核心层 PageRefs 剥去代理、取真实 PageImpl 建立 CDP 通道；剥不开时原样回退（零行为变更）。
        Page real = (Page) PageRefs.unwrap(page);
        this.page = real;
        this.cdp = real.context().newCDPSession(real);
    }

    /** 启用各域并进入 inspect 模式；{@code onPick} 接收每次点选上报的 {@code Overlay.inspectNodeRequested} 参数。 */
    void enableAndInspect(Consumer<JsonObject> onPick) {
        try {
            cdp.send("DOM.enable");
            cdp.send("Accessibility.enable");
            cdp.send("Runtime.enable");
            cdp.send("Overlay.enable");
            cdp.on("Overlay.inspectNodeRequested", ev -> {
                try {
                    onPick.accept((JsonObject) ev);
                } catch (Exception e) {
                    log.warn("[picker][cdp] inspect callback failed: {}", e.getMessage());
                }
            });
            JsonObject inspect = new JsonObject();
            inspect.addProperty("mode", "searchForNode");
            // Chromium 在 mode != "none" 时强制要求 highlightConfig，缺失即报
            // "highlight configuration parameter is missing"，故必须随命令附带（与 Chrome DevTools 一致配色）。
            inspect.add("highlightConfig", highlightConfig());
            cdp.send("Overlay.setInspectMode", inspect);
            log.info("[picker][cdp] inspect mode enabled on page: {}", page.url());
        } catch (Exception e) {
            log.warn("[picker][cdp] failed to enable inspect mode; falling back to JS injection: {}", e.getMessage());
        }
    }

    /** 发送 CDP 命令（带 Map 参数，自动转为 Gson JsonObject），失败返回 null。 */
    JsonObject send(String method, Map<String, Object> params) {
        try {
            return cdp.send(method, toJson(params));
        } catch (Exception e) {
            log.debug("[picker][cdp] send {} failed: {}", method, e.getMessage());
            return null;
        }
    }

    /** 发送 CDP 命令（无参数），失败返回 null。 */
    JsonObject send(String method) {
        try {
            return cdp.send(method);
        } catch (Exception e) {
            log.debug("[picker][cdp] send {} failed: {}", method, e.getMessage());
            return null;
        }
    }

    /** 退出 inspect 模式并禁用 Overlay 域。 */
    void disable() {
        try {
            JsonObject none = new JsonObject();
            none.addProperty("mode", "none");
            cdp.send("Overlay.setInspectMode", none);
        } catch (Exception ignore) {
            RolePickerQuiet.ignore("RolePickerCdpSession#disable", ignore);
        }
        try {
            cdp.send("Overlay.disable");
        } catch (Exception ignore) {
            RolePickerQuiet.ignore("RolePickerCdpSession#overlayDisable", ignore);
        }
    }

    /** 关闭 CDP 会话（释放渲染进程通道）。 */
    void close() {
        try {
            cdp.detach();
        } catch (Exception ignore) {
            RolePickerQuiet.ignore("RolePickerCdpSession#close", ignore);
        }
    }

    Page page() {
        return page;
    }

    /**
     * 构造 CDP {@code Overlay.setInspectMode} 在 {@code searchForNode} 模式下必需的 {@code highlightConfig}。
     * Chromium 在 {@code mode != "none"} 时强制要求该参数，缺失即报
     * "highlight configuration parameter is missing"。配色与 Chrome DevTools 的元素高亮一致。
     */
    static JsonObject highlightConfig() {
        JsonObject c = new JsonObject();
        c.addProperty("showInfo", true);
        c.addProperty("showRulers", false);
        c.addProperty("showExtensionLines", true);
        c.add("contentColor", rgba(111, 168, 220, 0.6));
        c.add("paddingColor", rgba(147, 196, 253, 0.6));
        c.add("borderColor", rgba(255, 229, 153, 0.6));
        c.add("marginColor", rgba(246, 178, 107, 0.6));
        c.add("eventTargetColor", rgba(255, 196, 196, 0.6));
        c.add("shapeColor", rgba(96, 82, 177, 0.6));
        c.add("shapeMarginColor", rgba(96, 82, 127, 0.6));
        c.add("cssGridColor", rgba(75, 0, 130, 0.6));
        return c;
    }

    /** RGBA 颜色对象（CDP {@code HighlightConfig} 要求的子结构）。 */
    private static JsonObject rgba(int r, int g, int b, double a) {
        JsonObject o = new JsonObject();
        o.addProperty("r", r);
        o.addProperty("g", g);
        o.addProperty("b", b);
        o.addProperty("a", a);
        return o;
    }

    /** 将简单 Map 参数转为 Gson JsonObject（CDPSession.send 第二参要求 JsonObject）。 */
    private static JsonObject toJson(Map<String, Object> params) {
        JsonObject jo = new JsonObject();
        if (params != null) {
            for (Map.Entry<String, Object> e : params.entrySet()) {
                Object v = e.getValue();
                if (v instanceof String) jo.addProperty(e.getKey(), (String) v);
                else if (v instanceof Number) jo.addProperty(e.getKey(), (Number) v);
                else if (v instanceof Boolean) jo.addProperty(e.getKey(), (Boolean) v);
                else if (v != null) jo.addProperty(e.getKey(), String.valueOf(v));
            }
        }
        return jo;
    }
}
