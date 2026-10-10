package com.hsbc.cmb.hk.dbb.automation.framework.route.dispatch;

import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * fulfil 前的"体编码/长度"头过滤契约（2026-10-10）。
 *
 * <p><b>为什么需要它</b>：MOCK intercept 路径取到真实响应后，Playwright 的
 * {@code APIResponse.body()} 返回的是<b>已解压</b>的明文，而 {@code headers()} 仍是 wire 头
 * （含 {@code content-encoding: gzip}）。若照抄这些头 + 交付已解码/已重写的体，浏览器会按
 * gzip 去解一个非 gzip 的体 ⇒ 该接口在页面里取不到数据 ⇒ 顶栏下拉 / 首页局部空白
 * （2026-10-10 实测 7.txt：6/7 例下拉行找不到，1 例切换后首页 60s 不渲染）。</p>
 *
 * <p>契约：{@code content-encoding} / {@code content-length} / {@code transfer-encoding}
 * 三个头一律剥离（大小写不敏感），其余头原样保留。</p>
 */
public class RouteActionBodyHeaderTest {

    @Test
    public void stripsBodyEncodingHeaders_andKeepsTheRest() {
        Map<String, String> in = new LinkedHashMap<>();
        in.put("Content-Type", "application/json;charset=UTF-8");
        in.put("Content-Encoding", "gzip");
        in.put("content-length", "126");
        in.put("Transfer-Encoding", "chunked");
        in.put("Set-Cookie", "TS01=abc");
        in.put("Cache-Control", "no-store");

        Map<String, String> out = RouteAction.sanitizeBodyHeaders(in);

        assertTrue("content-encoding 必须剥离",
                out.keySet().stream().noneMatch(k -> k.equalsIgnoreCase("content-encoding")));
        assertTrue("content-length 必须剥离",
                out.keySet().stream().noneMatch(k -> k.equalsIgnoreCase("content-length")));
        assertTrue("transfer-encoding 必须剥离",
                out.keySet().stream().noneMatch(k -> k.equalsIgnoreCase("transfer-encoding")));
        assertEquals("application/json;charset=UTF-8", out.get("Content-Type"));
        assertEquals("TS01=abc", out.get("Set-Cookie"));
        assertEquals("no-store", out.get("Cache-Control"));
    }

    @Test
    public void withoutEncodingHeaders_isUnchanged() {
        Map<String, String> in = new LinkedHashMap<>();
        in.put("Content-Type", "application/json");
        in.put("X-Trace", "t-1");

        Map<String, String> out = RouteAction.sanitizeBodyHeaders(in);

        assertEquals(2, out.size());
        assertEquals("application/json", out.get("Content-Type"));
        assertEquals("t-1", out.get("X-Trace"));
    }

    @Test
    public void nullAndEmpty_arePassedThrough() {
        assertNull(RouteAction.sanitizeBodyHeaders(null));
        Map<String, String> empty = new LinkedHashMap<>();
        assertSame("空头应原样返回（不做无谓拷皮）", empty, RouteAction.sanitizeBodyHeaders(empty));
    }
}
