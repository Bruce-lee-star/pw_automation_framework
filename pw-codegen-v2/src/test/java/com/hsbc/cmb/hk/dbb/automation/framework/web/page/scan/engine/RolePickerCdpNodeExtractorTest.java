package com.hsbc.cmb.hk.dbb.automation.framework.web.page.scan.engine;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.scan.model.RoleEntry;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.CDPSession;
import com.microsoft.playwright.Page;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 函数级验证：CDP 通道"CDP 点选事件 → RoleEntry"的核心转换 {@link RolePickerCdpNodeExtractor#extract}。
 *
 * <p>这是"用户点击页面 → 得到 RoleEntry"在 CDP 模式（{@code -Ddbb.codegen.cdp-capture.enabled=true}）下
 * 的<b>直接产出点</b>，此前完全未被单测覆盖。受限于 {@code RolePickerCdpSession} 为 final 类、且本环境未启用
 * mockito-inline，无法直接 mock 它；故改为 mock 底层 {@link CDPSession}（接口，可 mock），令真实
 * {@code RolePickerCdpSession} 构造时把 {@code page.context().newCDPSession(page)} 接到 mock 上，
 * 再 stub {@code cdp.send(...)} 返回构造的 DOM / 可访问性树 JSON，从而在不安装浏览器的情况下验证
 * extract 的真实策略解析与 RoleEntry 产出。</p>
 *
 * <p>@apiNote 纯单元；不依赖 Playwright / 浏览器 / mockito-inline。</p>
 */
public class RolePickerCdpNodeExtractorTest {

    private static final long BACKEND_NODE_ID = 123L;

    /** 构造单 AX 节点的会话（节点携带真实字段 backendDOMNodeId）。 */
    private RolePickerCdpSession sessionFor(String localName, JsonArray attributes, String axRole, String axName) {
        JsonArray nodes = new JsonArray();
        JsonObject n = new JsonObject();
        // 【关键】CDP Accessibility.AXNode 的真实字段是 backendDOMNodeId（请求参数才是 backendNodeId）。
        // 此处用真实字段构造，确保 extract 走"按 backendDOMNodeId 精确匹配"的正确路径。
        n.addProperty("backendDOMNodeId", BACKEND_NODE_ID);
        JsonObject r = new JsonObject();
        r.addProperty("value", axRole);
        n.add("role", r);
        JsonObject nm = new JsonObject();
        nm.addProperty("value", axName);
        n.add("name", nm);
        nodes.add(n);
        return sessionForAxNodes(localName, attributes, nodes);
    }

    /** 用给定的 AX 节点数组构造会话（供多节点回归用例）。 */
    private RolePickerCdpSession sessionForAxNodes(String localName, JsonArray attributes, JsonArray axNodes) {
        Page page = mock(Page.class);
        BrowserContext ctx = mock(BrowserContext.class);
        CDPSession cdp = mock(CDPSession.class);
        when(page.context()).thenReturn(ctx);
        when(ctx.newCDPSession(page)).thenReturn(cdp);

        JsonObject dom = new JsonObject();
        JsonObject node = new JsonObject();
        node.addProperty("localName", localName);
        node.add("attributes", attributes);
        dom.add("node", node);
        when(cdp.send(eq("DOM.describeNode"), any(JsonObject.class))).thenReturn(dom);

        JsonObject ax = new JsonObject();
        ax.add("nodes", axNodes);
        when(cdp.send(eq("Accessibility.getPartialAXTree"), any(JsonObject.class))).thenReturn(ax);

        return new RolePickerCdpSession(page);
    }

    private RoleEntry extract(RolePickerCdpSession session, String pageClass) {
        RolePickerCdpNodeExtractor extractor =
                new RolePickerCdpNodeExtractor(session.page(), pageClass, "{}");
        JsonObject inspectParams = new JsonObject();
        inspectParams.addProperty("backendNodeId", BACKEND_NODE_ID);
        return extractor.extract(session, inspectParams);
    }

    @Test
    public void extractDataTestIdStrategy() {
        JsonArray attrs = new JsonArray();
        attrs.add("id");
        attrs.add("user");
        attrs.add("placeholder");
        attrs.add("Enter user");
        attrs.add("data-testid");
        attrs.add("login-username");

        RolePickerCdpSession session = sessionFor("input", attrs, "textbox", "Enter user");
        RoleEntry e = extract(session, "LoginPage");

        assertNotNull(e);
        assertEquals(RolePickerConstants.STRATEGY_TEST_ID, e.getStrategy());
        assertEquals("[data-testid=\"login-username\"]", e.getSelector());
        assertEquals("textbox", e.getRole());
        assertEquals("Enter user", e.getName());
        assertEquals("input", e.getTag());
        assertEquals("LoginPage", e.getPageClass());
    }

    @Test
    public void extractAriaLabelStrategy() {
        JsonArray attrs = new JsonArray();
        attrs.add("aria-label");
        attrs.add("Submit");

        RolePickerCdpSession session = sessionFor("button", attrs, "button", "Submit");
        RoleEntry e = extract(session, "LoginPage");

        assertNotNull(e);
        assertEquals(RolePickerConstants.STRATEGY_LABEL, e.getStrategy());
        assertEquals("[aria-label=\"Submit\"]", e.getSelector());
        assertEquals("button", e.getRole());
        assertEquals("Submit", e.getName());
        assertEquals("button", e.getTag());
    }

    @Test
    public void extractMissingBackendNodeIdReturnsNull() {
        RolePickerCdpSession session = sessionFor("div", new JsonArray(), "generic", "x");
        RolePickerCdpNodeExtractor extractor =
                new RolePickerCdpNodeExtractor(session.page(), "LoginPage", "{}");
        // inspectParams 无 backendNodeId → extract 应安全返回 null（绝不抛异常）
        RoleEntry e = extractor.extract(session, new JsonObject());
        assertEquals(null, e);
    }

    /**
     * 回归：AX 树含多个节点、目标不在首位时，必须按 {@code backendDOMNodeId} 精确命中目标节点，
     * 而不能回退到 {@code nodes[0]}（祖先）。旧实现按错误的 {@code backendNodeId} 匹配不到任何节点，
     * 会取到祖先的 role/name —— 表现为"所有元素 name=null、role 退化为标签名"。
     */
    @Test
    public void extractMatchesBackendDomNodeIdInsteadOfFirstAxNode() {
        JsonArray attrs = new JsonArray();   // 无 testid/placeholder/aria-label/alt/title → 走 role 策略
        JsonArray nodes = new JsonArray();

        // nodes[0]：祖先节点（不同 backendDOMNodeId、不同 name）
        JsonObject ancestor = new JsonObject();
        ancestor.addProperty("backendDOMNodeId", 999L);
        JsonObject ar = new JsonObject();
        ar.addProperty("value", "generic");
        ancestor.add("role", ar);
        JsonObject an = new JsonObject();
        an.addProperty("value", "Ancestor");
        ancestor.add("name", an);
        nodes.add(ancestor);

        // nodes[1]：真正的目标节点（backendDOMNodeId == BACKEND_NODE_ID）
        JsonObject target = new JsonObject();
        target.addProperty("backendDOMNodeId", BACKEND_NODE_ID);
        JsonObject tr = new JsonObject();
        tr.addProperty("value", "heading");
        target.add("role", tr);
        JsonObject tn = new JsonObject();
        tn.addProperty("value", "Log on to Business Internet Banking");
        target.add("name", tn);
        nodes.add(target);

        RolePickerCdpSession session = sessionForAxNodes("h1", attrs, nodes);
        RoleEntry e = extract(session, "LogonPage");

        assertNotNull(e);
        assertEquals("heading", e.getRole());
        assertEquals("Log on to Business Internet Banking", e.getName());
    }
}
