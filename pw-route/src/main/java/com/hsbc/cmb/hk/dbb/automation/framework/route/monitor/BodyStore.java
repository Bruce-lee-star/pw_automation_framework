package com.hsbc.cmb.hk.dbb.automation.framework.route.monitor;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * 响应体留存（<b>内部使用</b>）：按大小与类型在"内存原字节 / 内存 gzip / 临时文件"三档之间自动选择，
 * 使"整响应不截断"在超大 body 下**不撑爆堆**，同时对业务保持同一套读取语义。
 *
 * <p><b>三档策略</b>（阈值均为"固定常量 + 系统属性可覆盖"，不引入配置文件项）：
 * <ol>
 *   <li>{@code len <= route.capture.inline.max.bytes}（默认 256 KB）⇒ 原字节持内存；</li>
 *   <li>文本型（json / xml / html / text / form，按 {@code content-type} 判定）且
 *       {@code len <= route.capture.gzip.max.bytes}（默认 8 MB）⇒ <b>gzip 后持内存</b>
 *       （常见大 JSON 可省 5~10 倍）；</li>
 *   <li>其余 ⇒ <b>落盘临时文件</b>，本对象只持 {@code Path} + 长度。</li>
 * </ol>
 *
 * <p><b>线程模型（调用方注意）</b>：gzip 与落盘都是 IO，{@link #of(byte[], String)} 会同步完成它们。
 * 采集路径必须把本调用交给既有有界 IO 池执行（事件/调度线程只做"趁句柄存活时快照字节"这一步）。
 *
 * <p><b>生命周期</b>：落盘文件必须由 {@link #close()} 删除；{@code CaptureSink.reset()} 与
 * context 收尾（{@code stopCaptureFor}）会遍历关闭，另有 JVM 退出兜底。
 *
 * <p><b>解码</b>：{@link #text(Charset)} 按需解码并<b>只解一次</b>并缓存（大体避免重复解码开销）。
 */
final class BodyStore {

    /** 内联阈值：不超过它就直接持原字节。 */
    private static final int INLINE_MAX =
            Integer.getInteger("route.capture.inline.max.bytes", 262_144);

    /** 文本型 gzip 上限：不超过它就用内存 gzip（超过则落盘）。 */
    private static final int GZIP_MAX =
            Integer.getInteger("route.capture.gzip.max.bytes", 8_388_608);

    /** 留存形态。 */
    private enum Storage { MEMORY, GZIP, FILE }

    private final Storage storage;
    private final String contentType;
    /** MEMORY=原字节；GZIP=gzip 字节；FILE=null。 */
    private final byte[] payload;
    /** FILE=落盘路径；其余为 null。 */
    private final Path file;
    private final long length;
    /** 按需解码缓存（volatile：多线程读取时只解一次）。 */
    private volatile String cachedText;

    private BodyStore(Storage storage, byte[] payload, Path file, long length, String contentType) {
        this.storage = storage;
        this.payload = payload;
        this.file = file;
        this.length = length;
        this.contentType = contentType;
    }

    /**
     * 按三档策略留存一段响应体。
     *
     * @param bytes       原始响应体（不会被持有引用：本方法内部按需拷贝）
     * @param contentType 响应 {@code content-type}（可为 null；用于判断是否文本型与 charset）
     */
    static BodyStore of(byte[] bytes, String contentType) {
        if (bytes == null) {
            return new BodyStore(Storage.MEMORY, null, null, 0L, contentType);
        }
        if (bytes.length <= INLINE_MAX) {
            return new BodyStore(Storage.MEMORY, bytes.clone(), null, bytes.length, contentType);
        }
        if (isTextual(contentType) && bytes.length <= GZIP_MAX) {
            byte[] gz = gzip(bytes);
            if (gz != null && gz.length < bytes.length) {
                return new BodyStore(Storage.GZIP, gz, null, bytes.length, contentType);
            }
            return new BodyStore(Storage.MEMORY, bytes.clone(), null, bytes.length, contentType);
        }
        Path tmp = spillToFile(bytes);
        if (tmp == null) {
            // 落盘失败（磁盘满 / 权限）⇒ fail-open：退回内存原字节，绝不让采集失败影响主流程
            return new BodyStore(Storage.MEMORY, bytes.clone(), null, bytes.length, contentType);
        }
        return new BodyStore(Storage.FILE, null, tmp, bytes.length, contentType);
    }

    /** 原始响应体长度（字节）。 */
    long length() {
        return length;
    }

    /** 响应 {@code content-type}（原样，可为 null）。 */
    String contentType() {
        return contentType;
    }

    /** 是否文本型（供上层决定"要不要解码"；二进制型永不解码）。 */
    boolean isTextual() {
        return isTextual(contentType);
    }

    /** 整读原始字节（<b>副本</b>；文件/gzip 场景会解压/读盘）。 */
    byte[] readAll() {
        switch (storage) {
            case MEMORY:
                return payload == null ? new byte[0] : payload.clone();
            case GZIP:
                return gunzip(payload);
            case FILE:
            default:
                try {
                    return Files.readAllBytes(file);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
        }
    }

    /** 流式读取（大 body 首选；调用方负责关闭）。 */
    InputStream open() {
        switch (storage) {
            case MEMORY:
                return new ByteArrayInputStream(payload == null ? new byte[0] : payload.clone());
            case GZIP:
                return new ByteArrayInputStream(gunzip(payload));
            case FILE:
            default:
                try {
                    return Files.newInputStream(file);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
        }
    }

    /** 按需解码为文本（只解一次并缓存）。非文本型调用方不应调用（返回 null 由上层决定）。 */
    String text(Charset charset) {
        String cached = cachedText;
        if (cached != null) {
            return cached;
        }
        byte[] raw = readAll();
        String decoded = new String(raw, charset == null ? java.nio.charset.StandardCharsets.UTF_8 : charset);
        cachedText = decoded;
        return decoded;
    }

    /** 删除落盘文件（幂等；内存/gzip 场景无操作）。 */
    void close() {
        if (file != null) {
            try {
                Files.deleteIfExists(file);
            } catch (IOException e) {
                // 收尾阶段删除失败只影响临时目录整洁，不影响任何业务语义
                cachedText = null;
            }
        }
    }

    // ---------------------------------------------------------------- 内部工具

    private static boolean isTextual(String contentType) {
        if (contentType == null) {
            return false;
        }
        String ct = contentType.toLowerCase(Locale.ROOT);
        return ct.contains("json") || ct.contains("xml") || ct.contains("html")
                || ct.startsWith("text/") || ct.contains("x-www-form-urlencoded")
                || ct.contains("javascript");
    }

    private static byte[] gzip(byte[] bytes) {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(64, bytes.length / 4));
             GZIPOutputStream gz = new GZIPOutputStream(out)) {
            gz.write(bytes);
            gz.finish();
            return out.toByteArray();
        } catch (IOException e) {
            return null;
        }
    }

    private static byte[] gunzip(byte[] gz) {
        if (gz == null) {
            return new byte[0];
        }
        try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(gz));
             ByteArrayOutputStream out = new ByteArrayOutputStream(gz.length * 4)) {
            in.transferTo(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Path spillToFile(byte[] bytes) {
        try {
            Path tmp = Files.createTempFile("route-capture-", ".bin");
            Files.write(tmp, bytes);
            tmp.toFile().deleteOnExit();
            return tmp;
        } catch (IOException e) {
            return null;
        }
    }
}
