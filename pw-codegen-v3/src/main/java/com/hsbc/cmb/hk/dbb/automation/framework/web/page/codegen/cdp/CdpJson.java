package com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen.cdp;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * CDP 响应取值工具。
 *
 * <p><b>取值层级铁律</b>：Playwright 的 {@code CDPSession.send} 返回 JSON-RPC 的 {@code result} <b>本体</b>，
 * 故命令级字段直接位于顶层：{@code Page.getFrameTree} → {@code {frameTree:…}}、{@code DOM.describeNode} →
 * {@code {node:…}}、{@code DOM.getFrameOwner} → {@code {backendNodeId,nodeId}}、
 * {@code Runtime.evaluate} → {@code {result: RemoteObject}}。按「外面还有一层 result」取值会全部取不到
 * （历史上 framePath 恒为空正是此因）。
 *
 * <p>为兼容个别版本把外层 {@code result} 又包一层的形态，{@link #field} 在直取不到时下钻一层，正常路径不受影响。
 */
final class CdpJson {

    private CdpJson() {
    }

    /** 按命令自身的键名取字段；直取不到时下钻一层 {@code result}（防御性兼容）。 */
    static JsonElement field(JsonObject payload, String name) {
        if (payload == null) return null;
        JsonElement direct = payload.get(name);
        if (direct != null) return direct;
        JsonElement outer = payload.get("result");
        if (outer != null && outer.isJsonObject()) {
            return outer.getAsJsonObject().get(name);
        }
        return null;
    }

    /** 对象取值；缺失或非对象返回 {@code null}。 */
    static JsonObject objectField(JsonObject payload, String name) {
        JsonElement v = field(payload, name);
        return v != null && v.isJsonObject() ? v.getAsJsonObject() : null;
    }

    /** 数组取值；缺失或非数组返回 {@code null}。 */
    static JsonArray arrayField(JsonObject payload, String name) {
        JsonElement v = field(payload, name);
        return v != null && v.isJsonArray() ? v.getAsJsonArray() : null;
    }

    /** 字符串取值；缺失或 JSON null 返回 {@code null}。 */
    static String stringField(JsonObject payload, String name) {
        JsonElement v = field(payload, name);
        return v == null || v.isJsonNull() ? null : v.getAsString();
    }

    /** long 取值；缺失或非数字返回 {@code null}。 */
    static Long longField(JsonObject payload, String name) {
        JsonElement v = field(payload, name);
        return v != null && v.isJsonPrimitive() && v.getAsJsonPrimitive().isNumber() ? v.getAsLong() : null;
    }

    /**
     * 取 {@code Runtime.callFunctionOn} / {@code Runtime.evaluate} 的 RemoteObject。
     *
     * <p>这两个命令的 payload <b>本身</b>以 {@code result} 为键，不能与「外层包壳」混为一谈；仅当取到的对象
     * 既无 {@code value} 也无 {@code objectId}（说明多包了一层）时才再下钻一次。
     */
    static JsonObject remoteObject(JsonObject payload) {
        JsonObject remote = objectField(payload, "result");
        if (remote == null) return null;
        if (!remote.has("value") && !remote.has("objectId")) {
            JsonObject inner = objectField(remote, "result");
            if (inner != null) return inner;
        }
        return remote;
    }

    /** 把 Java 字符串转成 JS 字符串字面量（含引号）。 */
    static String jsStringLiteral(String raw) {
        StringBuilder sb = new StringBuilder(raw.length() + 2).append('"');
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> sb.append(c);
            }
        }
        return sb.append('"').toString();
    }
}
