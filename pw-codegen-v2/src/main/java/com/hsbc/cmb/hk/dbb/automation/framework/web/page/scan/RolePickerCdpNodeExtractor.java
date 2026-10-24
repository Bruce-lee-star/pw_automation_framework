package com.hsbc.cmb.hk.dbb.automation.framework.web.page.scan;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.microsoft.playwright.Page;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * CDP 节点反查：由 CDP 上报的 {@code backendNodeId} 反查定位策略与可访问名（CDP 捕获层改造 Phase 1）。
 *
 * <p>对照改造方案「策略解析序」打分：{@code testid(1) < placeholder(100) < label(120) < role+name(140)
 * < altText(160) < title(200) < css 兜底}。本 Phase 1 实现主框架内的策略解析；跨 frame 由 Phase 2 补齐。
 *
 * <p>反查手段全部走 CDP（与 page.pause 同源），不依赖浏览器侧 JS：
 * <ul>
 *   <li>{@code DOM.describeNode}：取标签名 + 属性（id / data-testid / placeholder / aria-label / alt / title）；</li>
 *   <li>{@code Accessibility.getPartialAXTree}：取 role 与可访问名（Inspector 正是据此标注元素）；</li>
 *   <li>role + 可访问名 作为默认策略（最健壮，与 page.pause 的 getByRole 口径一致）；</li>
 *   <li>若可访问名命中 nls 反向查表（{@code ctx.nlsReverseJson}），回填 {@code resolvedKey} 供生成 {@code @RoleElement(T(key))}。</li>
 * </ul>
 */
final class RolePickerCdpNodeExtractor {

    private static final Logger log = LoggerFactory.getLogger(RolePickerCdpNodeExtractor.class);
    private static final Gson GSON = new Gson();

    private final Page page;
    private final String pageClass;
    private final String nlsReverseJson;

    RolePickerCdpNodeExtractor(Page page, String pageClass, String nlsReverseJson) {
        this.page = page;
        this.pageClass = pageClass;
        this.nlsReverseJson = nlsReverseJson;
    }

    /**
     * 反查并构造 {@link RoleEntry}。任一环节失败返回 null（调用方跳过，绝不抛异常）。
     *
     * @param session      当前页 CDP 会话
     * @param inspectParams CDP {@code Overlay.inspectNodeRequested} 事件参数（含 {@code backendNodeId}）
     */
    RoleEntry extract(RolePickerCdpSession session, JsonObject inspectParams) {
        if (inspectParams == null || !inspectParams.has("backendNodeId")) return null;
        long backendNodeId = inspectParams.get("backendNodeId").getAsLong();
        try {
            // 1) 节点描述：标签名 + 属性
            JsonObject desc = session.send("DOM.describeNode", Map.of("backendNodeId", backendNodeId));
            if (desc == null || !desc.has("node")) return null;
            JsonObject node = desc.getAsJsonObject("node");
            String localName = node.has("localName") && !node.get("localName").isJsonNull()
                    ? node.get("localName").getAsString() : null;
            String nodeName = node.has("nodeName") && !node.get("nodeName").isJsonNull()
                    ? node.get("nodeName").getAsString() : null;
            String tag = localName != null ? localName : (nodeName != null ? nodeName.toLowerCase() : null);
            Map<String, String> attrs = parseAttributes(node);

            // 2) 可访问性树：role + 可访问名（与 page.pause 的 Inspector 同源）
            String role = null;
            String axName = null;
            JsonObject ax = session.send("Accessibility.getPartialAXTree", Map.of("backendNodeId", backendNodeId));
            if (ax != null && ax.has("nodes")) {
                JsonArray nodes = ax.getAsJsonArray("nodes");
                for (int i = 0; i < nodes.size(); i++) {
                    JsonObject n = nodes.get(i).getAsJsonObject();
                    if (!n.has("backendNodeId") || n.get("backendNodeId").getAsLong() != backendNodeId) continue;
                    if (n.has("role") && !n.get("role").isJsonNull()) {
                        JsonObject r = n.getAsJsonObject("role");
                        if (r.has("value") && !r.get("value").isJsonNull()) role = r.get("value").getAsString();
                    }
                    if (n.has("name") && !n.get("name").isJsonNull()) {
                        JsonObject nm = n.getAsJsonObject("name");
                        if (nm.has("value") && !nm.get("value").isJsonNull()) axName = nm.get("value").getAsString();
                    }
                    break;
                }
            }
            if (role == null) role = (tag != null ? tag : "generic");
            String name = (axName != null && !axName.isEmpty()) ? axName : attrs.get("innerText");

            // 3) 策略解析（打分序取最优）
            String strategy = null;
            String selector = null;
            String resolvedKey = null;

            String testId = attrs.get("data-testid");
            String id = attrs.get("id");
            if (testId != null && !testId.isEmpty()) {
                strategy = RolePickerConstants.STRATEGY_TEST_ID;
                selector = "[data-testid=\"" + testId + "\"]";
            } else if (id != null && !id.isEmpty() && id.toLowerCase().startsWith("test")) {
                strategy = RolePickerConstants.STRATEGY_TEST_ID;
                selector = "#" + id;
            } else if (isInputish(tag) && attrs.get("placeholder") != null && !attrs.get("placeholder").isEmpty()) {
                strategy = RolePickerConstants.STRATEGY_PLACEHOLDER;
                selector = "[placeholder=\"" + attrs.get("placeholder") + "\"]";
            } else if (attrs.get("aria-label") != null && !attrs.get("aria-label").isEmpty()) {
                strategy = RolePickerConstants.STRATEGY_LABEL;
                selector = "[aria-label=\"" + attrs.get("aria-label") + "\"]";
            } else if ("img".equals(tag) && attrs.get("alt") != null && !attrs.get("alt").isEmpty()) {
                strategy = RolePickerConstants.STRATEGY_ALT_TEXT;
                selector = "[alt=\"" + attrs.get("alt") + "\"]";
            } else if (attrs.get("title") != null && !attrs.get("title").isEmpty()) {
                strategy = RolePickerConstants.STRATEGY_TITLE;
                selector = "[title=\"" + attrs.get("title") + "\"]";
            } else {
                // 默认：role + 可访问名（与 page.pause 的 getByRole 口径一致）
                strategy = null; // 视为 role 策略
                if (name != null && nlsReverseJson != null && !nlsReverseJson.isEmpty()
                        && !"{}".equals(nlsReverseJson)) {
                    resolvedKey = reverseLookupNls(name);
                }
            }

            // 4) 构造 RoleEntry 并入权威态（framePath 留 null：主框架；跨 frame 为 Phase 2）
            RoleEntry entry = new RoleEntry(
                    role, name, tag, name,
                    strategy, selector, resolvedKey, false,
                    null, false, -1, false, pageClass);
            entry.setFramePath(null);
            entry.setSigKey(sigKeyOf(strategy, selector, role, name));
            entry.setCount(1);
            entry.setSpace("main");
            return entry;
        } catch (Exception e) {
            log.warn("[picker][cdp] node extraction failed (backendNodeId={}): {}", backendNodeId, e.getMessage());
            return null;
        }
    }

    /** DOM.describeNode 的 attributes 为扁平数组 [name,val,name,val,...]，解析为 Map。 */
    private static Map<String, String> parseAttributes(JsonObject node) {
        Map<String, String> m = new LinkedHashMap<>();
        if (node == null || !node.has("attributes")) return m;
        JsonArray a = node.getAsJsonArray("attributes");
        for (int i = 0; i + 1 < a.size(); i += 2) {
            String k = a.get(i).getAsString();
            String v = a.get(i + 1).getAsString();
            m.put(k, v);
        }
        return m;
    }

    private static boolean isInputish(String tag) {
        return tag != null && ("input".equals(tag) || "textarea".equals(tag) || "select".equals(tag));
    }

    /** 在 nls 反向查表（{exact:{visibleText->key}}）中精确反查可访问名对应的 key。 */
    private String reverseLookupNls(String visibleName) {
        try {
            JsonObject root = GSON.fromJson(nlsReverseJson, JsonObject.class);
            if (root == null || !root.has("exact")) return null;
            JsonObject exact = root.getAsJsonObject("exact");
            if (exact.has(visibleName) && !exact.get(visibleName).isJsonNull()) {
                return exact.get(visibleName).getAsString();
            }
        } catch (Exception ignore) {
            RolePickerQuiet.ignore("RolePickerCdpNodeExtractor#nls", ignore);
        }
        return null;
    }

    /** 稳定去重键：同策略同目标视为同一元素（与浏览器侧 _sigKey 同口径，用于 javaPickBySig 合并）。 */
    private static String sigKeyOf(String strategy, String selector, String role, String name) {
        if (strategy == null || strategy.isEmpty()) {
            return "role|" + (role == null ? "" : role) + "|" + (name == null ? "" : name);
        }
        return strategy + "|" + (selector != null ? selector : (role + "|" + name));
    }
}
