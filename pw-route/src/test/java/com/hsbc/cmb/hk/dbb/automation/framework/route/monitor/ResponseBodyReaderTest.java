package com.hsbc.cmb.hk.dbb.automation.framework.route.monitor;

import com.microsoft.playwright.PlaywrightException;
import com.microsoft.playwright.Response;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link ResponseBodyReader} 单元测试：只读一次 + 失败分类。
 *
 * <p>固化两条语义：① 未抛错（含 null/空体）不算失败；② 抛错才判失败、交回根因，且<b>绝不重试</b>
 * （跨浏览器无挽回：页面关闭三浏览器俱败、Chromium 重取仅限 GET 静态安全类型、不含 JSON/XHR/POST，
 * 见《网络API分层指南》§3.1）。</p>
 */
public class ResponseBodyReaderTest {

    private Response resp() {
        Response response = mock(Response.class);
        when(response.status()).thenReturn(200);
        when(response.headers()).thenReturn(Map.of("content-type", "application/json"));
        return response;
    }

    @Test
    public void successReturnsBytesWithoutFailure() {
        Response r = resp();
        byte[] expected = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
        when(r.body()).thenReturn(expected);

        ResponseBodyReader.Result result = new ResponseBodyReader().read(r);
        assertFalse("未抛错不得判失败", result.failed());
        assertArrayEquals(expected, result.bytes);
        verify(r, times(1)).body();
    }

    @Test
    public void noThrowEmptyBodyIsNotAFailure() {
        Response r = resp();
        when(r.body()).thenReturn(new byte[0]); // 合法空体，未抛错

        ResponseBodyReader.Result result = new ResponseBodyReader().read(r);
        assertFalse("未抛错（即便空体）不得判失败——保持'只有抛错才 fail-open'口径", result.failed());
        assertNotNull(result.bytes);
        assertEquals(0, result.bytes.length);
        verify(r, times(1)).body();
    }

    @Test
    public void throwIsFailureAndCarriesRootCause() {
        Response r = resp();
        PlaywrightException boom = new PlaywrightException("Object doesn't exist: response@dead");
        when(r.body()).thenThrow(boom);

        ResponseBodyReader.Result result = new ResponseBodyReader().read(r);
        assertTrue("抛错判失败", result.failed());
        assertNull(result.bytes);
        assertEquals("交回主读取根因（供调用方分类计数）", boom, result.failure);
    }

    @Test
    public void neverRetriesAfterFailure() {
        Response r = resp();
        when(r.body()).thenThrow(new PlaywrightException("Object doesn't exist: response@abc"));

        new ResponseBodyReader().read(r);
        // 关键：只读一次，失败后绝不重试（重试对 JSON/XHR/POST 无效，纯属浪费协议调用）
        verify(r, times(1)).body();
    }
}
