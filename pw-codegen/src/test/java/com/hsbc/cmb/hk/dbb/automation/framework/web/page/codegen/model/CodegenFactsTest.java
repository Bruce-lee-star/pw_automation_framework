package com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen.model;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

/**
 * 反序列化契约：注入脚本回传的字段类型不符时<b>回落到默认值</b>，绝不能让整条拾取抛异常。
 *
 * <p>回归 P1-7：{@code JsonElement#getAsString()} 对 JSON 数组/对象会抛 {@code IllegalStateException}，
 * 而调用方 {@code CodegenCdpNodeExtractor#extract} 用 {@code catch (Exception)} 包着——
 * 一次类型异常就等于「点了元素却没反应」，且日志只有一行 warn。
 */
public class CodegenFactsTest {

    /** 非原始值（对象/数组）不得抛异常，一律回落默认值。 */
    @Test
    public void nonPrimitiveStringFieldsFallBackToDefaults() {
        JsonObject self = new JsonObject();
        self.add("role", new JsonObject());     // 对象
        self.add("name", new JsonArray());      // 数组
        self.add("id", new JsonObject());
        self.add("tag", new JsonArray());
        JsonObject payload = new JsonObject();
        payload.add("self", self);

        CodegenFacts facts = CodegenFacts.fromJson(payload);

        assertNotNull("类型异常不得让整个事实解析失败", facts);
        assertEquals("role 回落默认值", "generic", facts.self.role);
        assertEquals("name 回落默认值", "", facts.self.name);
        assertEquals("id 回落默认值", "", facts.self.id);
        assertEquals("tag 回落默认值", "", facts.self.tag);
    }

    /**
     * 数组元素类型不符时不得抛异常、也不得"丢掉一层"：shadow 链丢层会错指向别的 shadow root。
     * 按契约记为空串失效层，由定位侧判不可用（不做坐标兜底）。
     */
    @Test
    public void nonPrimitiveListItemsBecomeInvalidLayersWithoutThrowing() {
        JsonArray chain = new JsonArray();
        chain.add("host-a");
        chain.add(new JsonObject());
        chain.add("");
        JsonObject self = new JsonObject();
        self.add("shadowHosts", chain);
        JsonObject payload = new JsonObject();
        payload.add("self", self);

        CodegenFacts facts = CodegenFacts.fromJson(payload);

        assertNotNull(facts);
        java.util.List<String> list = facts.self.shadowHosts;
        assertEquals("链长必须保留", 3, list.size());
        assertEquals("host-a", list.get(0));
        assertEquals("对象元素记为失效层", "", list.get(1));
        assertEquals("既有空串项原样保留", "", list.get(2));
    }

    /** 正常字符串仍原样解析（不得因加守卫而丢值）。 */
    @Test
    public void primitiveStringFieldsAreStillParsed() {
        JsonObject self = new JsonObject();
        self.addProperty("role", "button");
        self.addProperty("name", "Continue");
        self.addProperty("tag", "button");
        JsonObject payload = new JsonObject();
        payload.add("self", self);

        CodegenFacts facts = CodegenFacts.fromJson(payload);

        assertNotNull(facts);
        assertEquals("button", facts.self.role);
        assertEquals("Continue", facts.self.name);
        assertEquals("button", facts.self.tag);
        assertNotNull("缺失的 ancestors 不得影响 self 解析", facts.ancestors);
    }
}
