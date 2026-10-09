package com.hsbc.cmb.hk.dbb.automation.framework.route.monitor;

import org.junit.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/**
 * 载体接入后 {@link CapturedApiCall} 派生访问器的契约（加法式迁移正确性）。
 *
 * <p>三条要守住的语义：① 载体存在时 {@code responseBody()} / {@code responseBodyBytes()} 由载体派生、
 * 内容与旧路径等价；② 载体为 null 时完全回落旧组件（派发链证据路径），<b>null 语义不变</b>；
 * ③ 内容类型缺失时文本解码回落 UTF-8（与采集路径同口径）。
 */
public class CapturedApiCallBodyTest {

    /** 规范构造（17 参）：体只放在载体里，文本/字节旧组件传 null —— 与 sink 接线后的形态一致。 */
    private static CapturedApiCall withCarrier(CapturedBody body, String contentType) {
        return new CapturedApiCall("p", "GET", "https://host/x", null, null, 200, null,
                null, false, 1L, false, 2L, null, "MONITOR", contentType, null, body);
    }

    @Test
    public void textCarrierDerivesBothAccessors() {
        byte[] raw = "{\"a\":1}".getBytes(StandardCharsets.UTF_8);
        CapturedApiCall call = withCarrier(
                BodyStore.of(raw, "application/json", 1024, 1024), "application/json");
        assertEquals("文本由载体解码而来", "{\"a\":1}", call.responseBody());
        assertArrayEquals("字节由载体提供（副本）", raw, call.responseBodyBytes());
    }

    @Test
    public void contentTypeAbsentFallsBackToUtf8() {
        byte[] raw = "中文".getBytes(StandardCharsets.UTF_8);
        CapturedApiCall call = withCarrier(BodyStore.of(raw, null, 1024, 1024), null);
        assertEquals("无 content-type 时按 UTF-8 解码", "中文", call.responseBody());
    }

    @Test
    public void nullCarrierAndNullComponentsKeepNullSemantics() {
        CapturedApiCall call = withCarrier(null, "application/json");
        assertNull("captureBody 关闭/读取失败：responseBody() 必须为 null", call.responseBody());
        assertNull("无载体且无字节组件：responseBodyBytes() 必须为 null", call.responseBodyBytes());
    }

    @Test
    public void legacyTextComponentStillWorksWhenCarrierAbsent() {
        // 12 参兼容构造：派发链证据路径（只有 preview 文本、无载体）
        CapturedApiCall call = new CapturedApiCall("p", "GET", "https://host/y", null, null, 200, null,
                "legacy-body", false, 1L, false, 2L);
        assertEquals("无载体时回落旧文本组件", "legacy-body", call.responseBody());
        assertNull("旧路径字节组件为 null，语义不得改变", call.responseBodyBytes());
    }
}
