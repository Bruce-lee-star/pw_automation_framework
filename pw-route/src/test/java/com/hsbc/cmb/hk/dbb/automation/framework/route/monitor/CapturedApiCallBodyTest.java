package com.hsbc.cmb.hk.dbb.automation.framework.route.monitor;

import com.hsbc.cmb.hk.dbb.automation.framework.route.diag.RequestOrigin;
import org.junit.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * 载体接入后 {@link CapturedApiCall} 派生访问器的契约（加法式迁移正确性）。
 *
 * <p>三条要守住的语义：① 载体存在时 {@code responseBody()} / {@code responseBodyBytes()} 由载体派生、
 * 内容与旧路径等价；② 载体为 null 时完全回落旧组件（派发链证据路径），<b>null 语义不变</b>；
 * ③ 内容类型缺失时文本解码回落 UTF-8（与采集路径同口径）。
 */
public class CapturedApiCallBodyTest {

    /** 规范构造（19 参）：体只放在载体里，文本/字节旧组件传 null —— 与 sink 接线后的形态一致。 */
    private static CapturedApiCall withCarrier(CapturedBody body, String contentType) {
        return new CapturedApiCall("p", "GET", "https://host/x", null, null, 200, null,
                null, false, 1L, false, 2L, null, "MONITOR", contentType, null, body,
                CapturedApiCall.UNSEQUENCED, RequestOrigin.UNKNOWN);
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

    @Test
    public void evidenceBodyIsNotBoundedAndTheStoredBodyIsNotEither() {
        // 2026-10-22 用户要求：报告里的响应体不得被截断，必须完整可见 —— 证据文本与存储体一致
        String big = "x".repeat(5000);
        CapturedApiCall call = new CapturedApiCall("p", "GET", "https://host/big", null, null, 200, null,
                big, false, 1L, false, 2L);
        String detail = call.detailWith();
        assertFalse("证据不得再有有界化标记", detail.contains("[evidence limited"));
        assertFalse("证据不得再有截断标记", detail.contains("[truncated]"));
        assertTrue("证据必须包含整份 5000 字符响应体", detail.contains(big));
        // 存储侧同样整响应保留
        assertEquals(5000, call.responseBody().length());
        assertNull("证据路径无载体 ⇒ 字节组件保持 null（语义不变）", call.responseBodyBytes());
    }
}
