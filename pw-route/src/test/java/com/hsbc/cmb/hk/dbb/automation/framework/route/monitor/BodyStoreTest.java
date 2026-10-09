package com.hsbc.cmb.hk.dbb.automation.framework.route.monitor;

import org.junit.Test;

import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/**
 * {@link BodyStore} 三档留存契约（接线前该类 0 覆盖）。
 *
 * <p>覆盖：内联内存档、文本型内存 gzip 档、超上限落盘档、落盘文件在 {@code close()} 后不可读
 * （这正是 {@code reset()} 之后不得再读 body 的契约来源）、null 体、charset 缺省回落 UTF-8。
 *
 * <p>本类只断言<b>可观察行为</b>（长度 / 内容 / 可读性 / 文本型判定）：留存形态是内部实现细节，
 * 不外暴露，故不通过反射断言 storage。
 */
public class BodyStoreTest {

    private static final String JSON = "application/json";

    @Test
    public void inlineBytesStayInMemoryWhenWithinInlineThreshold() {
        byte[] body = "{\"a\":1}".getBytes(StandardCharsets.UTF_8);
        BodyStore store = BodyStore.of(body, JSON, 1024, 1024);
        assertEquals("长度即原始字节数", body.length, store.length());
        assertEquals(JSON, store.contentType());
        assertTrue("json 为文本型", store.isTextual());
        assertArrayEquals("内存档必须完整回读", body, store.readAll());
        assertEquals("{\"a\":1}", store.text(StandardCharsets.UTF_8));
        store.close();   // 内存型无底层资源
    }

    @Test
    public void textualBodyAboveInlineButWithinGzipThresholdRoundTrips() {
        byte[] body = new byte[4096];
        java.util.Arrays.fill(body, (byte) 'x');   // 高可压缩文本
        BodyStore store = BodyStore.of(body, JSON, 64, 1_048_576);
        assertEquals("长度始终是原始字节数", body.length, store.length());
        assertArrayEquals("gzip 档必须透明回读原始字节", body, store.readAll());
        assertEquals(4096, store.text(StandardCharsets.UTF_8).length());
        store.close();
    }

    @Test
    public void binaryBodyAboveInlineThresholdSpillsAndRoundTrips() {
        byte[] body = new byte[8192];
        for (int i = 0; i < body.length; i++) {
            body[i] = (byte) (i % 251);
        }
        BodyStore store = BodyStore.of(body, "application/octet-stream", 64, 1_048_576);
        assertFalse("二进制型不做文本判定", store.isTextual());
        assertArrayEquals("落盘档必须透明回读原始字节", body, store.readAll());
        try (java.io.InputStream in = store.open()) {
            assertTrue("流式读取可作为大 body 首选", in.read() >= 0);
        } catch (java.io.IOException e) {
            throw new AssertionError("open() 读取失败", e);
        }
        store.close();
    }

    @Test
    public void fileBackedBodyBecomesUnreadableAfterClose() {
        byte[] body = new byte[8192];
        java.util.Arrays.fill(body, (byte) 'y');
        // 超 gzip 上限 ⇒ 走落盘档（文本型也落盘）
        BodyStore store = BodyStore.of(body, JSON, 8, 16);
        assertArrayEquals(body, store.readAll());
        store.close();
        store.close();   // 幂等
        assertThrows("契约：close() 之后不得再读（reset() 后禁读的来源）",
                UncheckedIOException.class, store::readAll);
    }

    @Test
    public void nullBodyIsEmptyAndSafe() {
        BodyStore store = BodyStore.of(null, JSON, 1024, 1024);
        assertEquals(0L, store.length());
        assertArrayEquals(new byte[0], store.readAll());
        assertEquals("", store.text(StandardCharsets.UTF_8));
        assertTrue(store.isTextual());
    }

    @Test
    public void textFallsBackToUtf8WhenCharsetIsNull() {
        byte[] body = "中文".getBytes(StandardCharsets.UTF_8);
        BodyStore store = BodyStore.of(body, "text/plain; charset=utf-8", 1024, 1024);
        assertEquals("中文", store.text(null));
        assertEquals("只解一次并缓存（同内容重复读取）", "中文", store.text(null));
    }
}
