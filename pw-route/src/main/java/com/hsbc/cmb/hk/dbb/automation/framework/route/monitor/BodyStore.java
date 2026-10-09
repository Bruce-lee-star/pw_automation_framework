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
 * <p><b>三档策略</b>（阈值由装配方冻结的 {@code RouteCapturePolicy} 提供：层序在 {@code ConfigKeys}
 * 声明、解析由 {@code ConfigSource} 承担，不再由本类自带任何配置读取）：
 * <ol>
 *   <li>{@code len <= route.capture.inline.max.bytes}（默认 256 KB）⇒ 原字节持内存；</li>
 *   <li>文本型（json / xml / html / text / form，按 {@code content-type} 判定）且
 *       {@code len <= route.capture.gzip.max.bytes}（默认 8 MB）⇒ <b>gzip 后持内存</b>
 *       （常见大 JSON 可省 5~10 倍）；</li>
 *   <li>其余 ⇒ <b>落盘临时文件</b>，本对象只持 {@code Path} + 长度。</li>
 * </ol>
 *
 * <p><b>线程模型（当前实现）</b>：gzip 与落盘是 CPU/IO 工作，{@link #of(byte[], String, int, int)} 在
 * <b>响应轮询线程</b>上同步完成 —— 与改造前"在该线程整体解码整条 body"同量级（故非新增负担），
 * 且字节已趁句柄存活时快照完毕，不引入新的句柄竞态。代价：超大 body 的 gzip/落盘占用该线程。
 * 若将来改为派发到有界 IO 池，必须保住"入队即时可见"（dump/peek 立即可见）的语义。
 *
 * <p><b>生命周期</b>：落盘文件由 {@link #close()} 删除；{@code CaptureSink} 在 {@code reset()} 与该记录
 * 被 history 驱逐时关闭本实例，另保留 JVM 退出兜底。完整契约见 {@link CapturedBody}
 * （<b>{@code reset()} 之后不得再读取</b>）。
 *
 * <p><b>解码</b>：{@link #text(Charset)} 按需解码并<b>只解一次</b>并缓存（大体避免重复解码开销）。
 */
final class BodyStore implements CapturedBody {

    /** 留存形态。 */
    private enum Storage { MEMORY, GZIP, FILE }

    /** 落盘专用临时目录（懒创建，见 {@link #spillDir()}）。 */
    private static volatile Path spillDir;

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
     * @param inlineMax   内联阈值（字节）——由 {@code RouteConfig} 传入，避免本类自带配置解析
     * @param gzipMax     文本型 gzip 上限（字节）——同上
     */
    static BodyStore of(byte[] bytes, String contentType, int inlineMax, int gzipMax) {
        if (bytes == null) {
            return new BodyStore(Storage.MEMORY, null, null, 0L, contentType);
        }
        if (bytes.length <= inlineMax) {
            return new BodyStore(Storage.MEMORY, bytes.clone(), null, bytes.length, contentType);
        }
        if (isTextual(contentType) && bytes.length <= gzipMax) {
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
    @Override
    public long length() {
        return length;
    }

    /** 响应 {@code content-type}（原样，可为 null）。 */
    @Override
    public String contentType() {
        return contentType;
    }

    /** 是否文本型（供上层决定"要不要解码"；二进制型永不解码）。 */
    @Override
    public boolean isTextual() {
        return isTextual(contentType);
    }

    /** 整读原始字节（<b>副本</b>；文件/gzip 场景会解压/读盘）。 */
    @Override
    public byte[] readAll() {
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
    @Override
    public InputStream open() {
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
    @Override
    public String text(Charset charset) {
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
    @Override
    public void close() {
        // 语义：close 之后不得再读。无论删除成败都失效解码缓存 —— 否则 FILE 档删除失败时
        // text() 会静默返回 close 前的陈旧文本（内存/gzip 档字节仍在，重解码即可）。
        cachedText = null;
        if (file == null) {
            return;
        }
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            // 立即删不掉（Windows 上文件被占用等）⇒ 交 JVM 退出兜底，绝不影响业务语义
            file.toFile().deleteOnExit();
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
        Path dir = spillDir();
        if (dir == null) {
            return null;   // 落盘不可用（目录建不出来）：由 of(...) fail-open 退回内存
        }
        Path tmp = null;
        try {
            tmp = Files.createTempFile(dir, "capture-", ".bin");
            Files.write(tmp, bytes);
            return tmp;   // 每个文件的清理责任在 close()（成功删）+ 目录级退出钩子（兜底）
        } catch (IOException e) {
            // 写盘失败（磁盘满 / 中断）：已创建的临时文件必须就地清理，否则无人负责 → 永久泄漏。
            // 本方法以 null 表示"落盘不可用"，由 of(...) fail-open 退回内存。
            deleteQuietly(tmp);
            return null;
        }
    }

    /**
     * 采集落盘目录（懒创建；<b>永不抛</b>，不可用返回 null）。
     *
     * <p>刻意<b>不</b>使用 per-file {@code File.deleteOnExit()}：{@code DeleteOnExitHook} 的条目是
     * 只增集合，长跑套件下每个落盘体都会永久占用一条 → 堆缓慢增长。改为"一个目录 + 一个退出钩子"，
     * 条目数与落盘次数无关。
     */
    private static Path spillDir() {
        Path dir = spillDir;
        if (dir != null) {
            return dir;
        }
        synchronized (BodyStore.class) {
            if (spillDir == null) {
                Path created = createSpillDir();
                if (created == null) {
                    return null;
                }
                Runtime.getRuntime().addShutdownHook(
                        new Thread(() -> deleteRecursively(created), "route-capture-cleanup"));
                spillDir = created;
            }
            return spillDir;
        }
    }

    /**
     * 建落盘目录：<b>优先构建产物区 {@code <工作目录>/target/route-capture}</b> —— 随
     * {@code mvn clean} 一起消失，<b>不污染系统盘</b>；工作目录不可写（如从只读位置运行）时
     * 回退系统临时目录；两处都不可用则返回 {@code null}（采集 fail-open 退回内存）。
     */
    private static Path createSpillDir() {
        try {
            return Files.createDirectories(Path.of("target", "route-capture"));
        } catch (IOException e) {
            try {
                return Files.createTempDirectory("route-capture-");
            } catch (IOException fallbackFailure) {
                // 两处都不可建：以 null 表示落盘不可用，绝不影响采集主流程
                return null;
            }
        }
    }

    /** 退出清理：递归删除临时目录（尽力而为，绝不阻塞进程退出）。 */
    private static void deleteRecursively(Path dir) {
        try (java.util.stream.Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(BodyStore::deleteQuietly);
        } catch (IOException e) {
            // 目录不存在 / 不可遍历：退出阶段无需处理
            return;
        }
    }

    /** 尽力删除（退出清理用；失败不影响进程退出，交给操作系统清理临时目录）。 */
    private static boolean deleteQuietly(Path path) {
        if (path == null) {
            return false;
        }
        try {
            return Files.deleteIfExists(path);
        } catch (IOException e) {
            // 删除失败：不阻塞、不抛出（退出清理与失败路径都适用）
            return false;
        }
    }
}
