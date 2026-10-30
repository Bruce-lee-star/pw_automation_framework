package com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen.cdp;

import com.google.gson.JsonObject;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen.quiet.CodegenQuiet;
import com.microsoft.playwright.CDPSession;
import com.microsoft.playwright.Page;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

/**
 * 单页 CDP 会话封装（V3 重写，摒弃 v2 的 setContent 面板方案）。
 *
 * <p>基于 Playwright 的 {@code BrowserContext.newCDPSession(page)} 建立与渲染进程的直接通道，启用
 * {@code DOM}/{@code Accessibility}/{@code Runtime} 域，供「按坐标反查元素」使用：注入脚本在 DOM 捕获阶段
 * 上报真实点击的文档坐标，本类经 {@code DOM.getNodeForLocation}（与 DevTools 的 element-from-point 同源）
 * 得到 {@code backendNodeId}，再交给 {@code CodegenCdpNodeExtractor} 组装 {@code CodegenEntry}。
 *
 * <p><b>刻意不使用 {@code Overlay.setInspectMode} 的 inspect 模式</b>：它会接管鼠标事件，使被测页
 * 完全无法交互（用户表现为「一进拾取页面就点不动」）。拾取与页面交互必须同时可用。
 *
 * <p>绝大多数 CDP 调用 fail-soft（{@link #send} 失败返回 {@code null}；{@link #disable}/{@link #close} 静默降级），
 * <b>唯独 {@link #enablePickChannel} 的成败必须上浮</b>：它返回 {@code true/false} 让调用方在面板显示
 * "inspecting" 或 "start failed"，避免「徽标显示拾取中、实际通道没建起来」的静默失效。
 */
public final class CodegenCdpSession {

    private static final Logger log = LoggerFactory.getLogger(CodegenCdpSession.class);

    private final Page page;
    private final CDPSession cdp;
    /** 跨 frame 选择器定位器（内含 frame→隔离世界 contextId 缓存，故随会话长期存在）。 */
    private final CdpFrameLocator frameLocator;

    /**
     * 在元素所属 frame 的隔离世界里批量统计选择器命中数（一次协议往返）。
     *
     * <p>供 {@link CdpLocatorCounter} 使用：把「唯一性校验」从页级（只看主 frame）改成
     * 「元素自身 frame + shadow 宿主链」作用域，并把 N 次计数合并成 1 次。
     *
     * @param frameId     元素所属 frameId（{@code null} 表示未知，返回空 Map 由调用方兜底）
     * @param shadowHosts 元素所在的 open shadow 宿主链（outermost-first）
     * @param selectors   待统计的选择器（支持 {@code xpath=} 前缀）
     * @return 选择器 → 命中数；整体不可判定时返回空 Map
     */
    public Map<String, Integer> countSelectorsInFrame(String frameId, List<String> shadowHosts,
                                                      List<String> selectors) {
        return frameLocator.countSelectors(frameId, shadowHosts, selectors);
    }

    public CodegenCdpSession(Page page, String nlsLang) {
        // 框架录制开启时 Page 会被包成 JDK 动态代理，newCDPSession 内部强转 PageImpl 会抛 ClassCastException，
        // 用 PageRefs 剥去代理取真实 PageImpl（剥不开原样回退，零行为变更）。
        Page real = (Page) com.hsbc.cmb.hk.dbb.automation.framework.common.route.PageRefs.unwrap(page);
        this.page = real;
        this.cdp = real.context().newCDPSession(real);
        this.frameLocator = new CdpFrameLocator(this);
    }

    /**
     * 启用拾取通道（只开 CDP 域，<b>不进入 Overlay inspect 模式</b>）。
     *
     * <p><b>为什么不再用 {@code Overlay.setInspectMode("searchForNode")}</b>：该模式让 Chromium 的 Overlay
     * 接管鼠标事件，被测页收不到任何 click/pointer 事件，用户侧表现就是「一进拾取，页面完全点不动」。
     * 而拾取的真实需求是<b>边操作页面、边把点到的元素记下来</b>，所以改为：注入脚本在 DOM 捕获阶段上报
     * 真实点击的文档坐标，Java 侧用 {@link #backendNodeIdAt(double, double)} 反查 backendNodeId
     * （与 DevTools 的 element-from-point 同源），页面交互完全不受影响。
     *
     * <p><b>失败必须上浮，绝不静默吞掉</b>：返回 {@code true/false} 让调用方在面板显示 "inspecting"
     * 或 "start failed"，避免「徽标显示拾取中、实际没建立通道」的静默失效。
     *
     * @return {@code true} 表示 CDP 拾取通道已就绪；{@code false} 表示底层调用失败（原因见 ERROR 级日志）
     */
    public boolean enablePickChannel() {
        try {
            cdp.send("DOM.enable");
            cdp.send("Accessibility.enable");
            cdp.send("Runtime.enable");
            // 与 DOM.enable 成对：enable 只订阅事件，不会把文档树推给 DOM agent。缺这一步时
            // DOM.requestNode 恒返回 nodeId=0（按选择器反查整体失效，只能退回坐标 hit-test）。
            frameLocator.ensureDocument();
            log.info("[codegen][cdp] pick channel enabled on page: {}", page.url());
            return true;
        } catch (Exception e) {
            log.error("[codegen][cdp] failed to enable pick channel on {}: {}", page.url(), e.getMessage(), e);
            return false;
        }
    }

    /**
     * 以文档坐标（CSS 像素、含滚动偏移，即 DOM 事件里的 {@code pageX/pageY}）反查元素，并带回其所属 frameId。
     *
     * <p>走 {@code DOM.getNodeForLocation}：与 Chrome DevTools 的「按坐标取节点」同源，<b>不往页面 DOM 打标记</b>
     * （零属性污染，不触发业务的 MutationObserver），也不要求元素在点击后仍挂在原父节点上。
     *
     * <p><b>不能依赖它命中跨源 iframe</b>：该命令在根 frame 坐标下做 hit-test，跨源 iframe（OOPIF）内只会命中
     * 宿主 {@code <iframe>} 元素甚至直接返回空。跨 frame 定位请用 {@link #elementBySelector(String)}。
     *
     * @return 命中元素（含所属 frameId）；未命中/调用失败返回 {@code null}
     */
    public ElementRef elementAt(double x, double y) {
        try {
            JsonObject params = new JsonObject();
            params.addProperty("x", x);
            params.addProperty("y", y);
            params.addProperty("includeUserAgentShadowDOM", true);
            JsonObject payload = cdp.send("DOM.getNodeForLocation", params);
            Long backendNodeId = CdpJson.longField(payload, "backendNodeId");
            if (backendNodeId == null) return null;
            return new ElementRef(backendNodeId, CdpJson.stringField(payload, "frameId"));
        } catch (Exception e) {
            log.debug("[codegen][cdp] getNodeForLocation({}, {}) failed: {}", x, y, e.getMessage());
            return null;
        }
    }

    /**
     * 用 CSS 选择器在<b>所有 frame（含跨域 iframe）</b>里反查元素，并带回其所属 frameId。
     *
     * <p>页面侧上报的 {@code cssSelector} 是相对元素<b>自身文档</b>算出来的，所以在该 frame 的执行上下文里求值
     * 必然命中；主 frame 的 {@code document.querySelector} 对 iframe 内元素恒为 null（旧兜底因此永远失败）。
     * 详见 {@link CdpFrameLocator}。
     *
     * @return 命中元素（含所属 frameId）；选择器非法 / 未命中 / 调用失败返回 {@code null}
     */
    public ElementRef elementBySelector(String cssSelector) {
        return frameLocator.findBySelector(cssSelector, List.of());
    }

    /**
     * 同上，并穿透 <b>open shadow DOM</b>：{@code document.querySelector} 不跨 shadow 边界，
     * 必须用页面侧给出的宿主链拼成链式表达式求值。
     *
     * @param cssSelector 内层 CSS 选择器（相对其自身文档 / 最内层 shadow root）
     * @param shadowHosts 宿主选择器链（outermost-first）；{@code null}/空等价于普通查找
     * @return 命中元素（含所属 frameId）；未命中 / 宿主链不可用返回 {@code null}
     */
    public ElementRef elementBySelector(String cssSelector, List<String> shadowHosts) {
        return frameLocator.findBySelector(cssSelector, shadowHosts);
    }

    /**
     * 同上，并带入元素所属 frame 路径（outer→inner 的 iframe 序号）。
     * 路径非空且能映射到 CDP frameId 时只探那一帧（约 2 次 CDP）；为 null 时回退全帧扫描（跨源 iframe 正确）。
     *
     * @param framePath 浏览器侧 {@code framePathOf()} 算出的 iframe 序号链；{@code null}/不可信 ⇒ 回退
     */
    public ElementRef elementBySelector(String cssSelector, List<String> shadowHosts, List<Integer> framePath) {
        return frameLocator.findBySelector(cssSelector, shadowHosts, framePath);
    }

    /** 发送 CDP 命令（带 Map 参数，自动转 Gson JsonObject），失败返回 null。 */
    JsonObject send(String method, Map<String, Object> params) {
        try {
            return cdp.send(method, toJson(params));
        } catch (Exception e) {
            log.debug("[codegen][cdp] send {} failed: {}", method, e.getMessage());
            return null;
        }
    }

    /**
     * 退出拾取通道（幂等）。
     *
     * <p>本类已不再进入 Overlay inspect 模式，故 {@code setInspectMode(none)} 只是<b>防御性收尾</b>：
     * 万一旧版本 jar 或外部脚本留下了 inspect 模式，这里能保证被测页恢复可交互（发一次失败也无副作用）。
     */
    public void disable() {
        try {
            JsonObject none = new JsonObject();
            none.addProperty("mode", "none");
            cdp.send("Overlay.setInspectMode", none);
        } catch (Exception ignore) {
            CodegenQuiet.ignore("CodegenCdpSession#disable", ignore);
        }
        try {
            cdp.send("Overlay.disable");
        } catch (Exception ignore) {
            CodegenQuiet.ignore("CodegenCdpSession#overlayDisable", ignore);
        }
    }

    /**
     * 关闭 CDP 会话（释放渲染进程通道）。
     *
     * <p>拾取队列的优雅排空由调用方（{@code PageSession}）负责，本方法只做通道 detach。
     */
    public void close() {
        try {
            cdp.detach();
        } catch (Exception ignore) {
            CodegenQuiet.ignore("CodegenCdpSession#close", ignore);
        }
    }

    Page page() {
        return page;
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
