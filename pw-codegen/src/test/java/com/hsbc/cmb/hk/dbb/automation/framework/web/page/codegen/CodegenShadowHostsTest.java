package com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen.model.CodegenEntry;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen.model.CodegenFacts;
import org.junit.Test;

import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * open shadow 宿主链（{@code shadowHosts}）的「事实解析 → 定位入参 → 结果输出」全链路契约。
 *
 * <p>为什么这条链必须存在：{@code document.querySelector} <b>不穿透 shadow 边界</b>，
 * CDP 侧按页面侧上报的 {@code cssSelector} 反查元素时，shadow root 内元素必然打空，
 * 只能靠宿主链拼成 {@code document.querySelector(h0)?.shadowRoot?.querySelector(css)}。
 * 同时宿主链又是「自包含事实」的一部分（面板/日志/排障都要能看到它）。
 */
public class CodegenShadowHostsTest {

    @Test
    public void factsCarryShadowHostsForSelfAndAncestors() {
        JsonObject payload = JsonParser.parseString("{\"self\":{\"role\":\"button\",\"name\":\"Save\","
                + "\"cssSelector\":\"#save\",\"shadowHosts\":[\"#h0\",\"#h1\"]},"
                + "\"ancestors\":[{\"role\":\"generic\",\"cssSelector\":\"#panel\",\"shadowHosts\":[\"#h0\"]}]}")
                .getAsJsonObject();

        CodegenFacts facts = CodegenFacts.fromJson(payload);

        assertNotNull(facts);
        assertEquals("宿主链必须 outermost-first 原样保留", List.of("#h0", "#h1"), facts.self.shadowHosts);
        assertEquals(List.of("#h0"), facts.ancestors.get(0).shadowHosts);
    }

    @Test
    public void factsWithoutShadowHostsDegradeToEmptyChain() {
        JsonObject missing = JsonParser.parseString("{\"self\":{\"role\":\"button\",\"cssSelector\":\"#save\"}}")
                .getAsJsonObject();
        JsonObject wrongType = JsonParser.parseString(
                "{\"self\":{\"role\":\"button\",\"cssSelector\":\"#save\",\"shadowHosts\":\"#h0\"}}")
                .getAsJsonObject();

        assertEquals("旧版注入脚本没有 shadowHosts 字段 ⇒ 空链（普通文档内查找）",
                List.of(), CodegenFacts.fromJson(missing).self.shadowHosts);
        assertEquals("非数组必须降级为空链，而不是抛异常",
                List.of(), CodegenFacts.fromJson(wrongType).self.shadowHosts);
    }

    @Test
    public void blankChainEntryIsPreservedSoLocatorCanRejectIt() {
        JsonObject payload = JsonParser.parseString(
                "{\"self\":{\"role\":\"button\",\"cssSelector\":\"#save\",\"shadowHosts\":[\"#h0\",\"\"]}}")
                .getAsJsonObject();

        List<String> hosts = CodegenFacts.fromJson(payload).self.shadowHosts;

        assertEquals("空项不能静默丢弃（丢层会去错误 shadow root 里找元素），必须原样交给定位侧判定",
                List.of("#h0", ""), hosts);
    }

    @Test
    public void entryExposesShadowHostsToPanelPayload() {
        CodegenEntry entry = new CodegenEntry("button|Save#0", "button", "Save", "ROLE", 105,
                "role=button[name=\"Save\"]", "#save", null, 0, List.of("#iframe"), List.of("#h0"));

        Map<String, Object> map = entry.toMap();

        assertEquals(List.of("#iframe"), map.get("framePath"));
        assertEquals("宿主链是自包含事实的一部分，必须随面板载荷输出", List.of("#h0"), map.get("shadowHosts"));
        assertEquals("旧构造器（无宿主链）必须退化为空列表而非 null",
                List.of(), new CodegenEntry("x", "button", "Save", "ROLE", 100, "s", "#x", null, 0).toMap()
                        .get("shadowHosts"));
    }

    @Test
    public void launcherParsesHostsFromEventInfo() {
        assertEquals(List.of("#h0", "#h1"),
                CodegenLauncher.shadowHostsOf(Map.of("shadowHosts", List.of("#h0", "#h1"))));
        assertEquals("缺失该字段 ⇒ 空链（老脚本 / 主文档元素）",
                List.of(), CodegenLauncher.shadowHostsOf(Map.of("cssSelector", "#save")));
        assertEquals("非数组必须降级为空链",
                List.of(), CodegenLauncher.shadowHostsOf(Map.of("shadowHosts", "#h0")));
        assertEquals("null info 必须安全降级", List.of(), CodegenLauncher.shadowHostsOf(null));
    }
}
