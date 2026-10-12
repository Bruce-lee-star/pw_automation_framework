package com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.trace;

import com.hsbc.cmb.hk.dbb.automation.framework.common.config.VerboseLogging;
import com.hsbc.cmb.hk.dbb.automation.framework.web.config.WebFrameworkConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Trace 目录磁盘治理：在每个 scenario 的 trace.zip 落盘后，按 mtime LRU 清理上一轮的旧 trace 文件，
 * 把 trace 目录压回配置的容量 / 文件数上限之内，防止长期本地跑或 CI agent 不清理把磁盘撑满。
 *
 * <p><b>安全边界</b>：只删 {@code mtime < runStartMs} 的旧产物；本次 run 刚生成的 trace.zip
 * 一律保留，避免"证据凭空消失"。开关 {@code playwright.context.trace.retention.enabled=false}
 * 可整体关闭。
 *
 * <p><b>目标目录固定</b>：{@code target/site/serenity/traces}（与 {@link ScenarioTraceRecorder#TRACE_DIR}
 * 一致）。这是唯一被治理的目录——截图 / WebP 等其它产物不在治理范围。
 */
public final class TraceRetention {

    private static final Logger logger = LoggerFactory.getLogger(TraceRetention.class);

    /** 本次 run 起点（JVM 加载本类时记录，早于第一个 scenario）。 */
    private static final long RUN_START_MS = System.currentTimeMillis();

    private TraceRetention() {
    }

    /**
     * 在 trace.zip 落盘后调用：若超阈值则按 mtime 从旧到新删，直到回到上限内。
     * 任何异常都静默降级，不影响主流程（trace 本身已成功落盘）。
     */
    public static void pruneIfDue() {
        if (!WebFrameworkConfig.PLAYWRIGHT_CONTEXT_TRACE_RETENTION_ENABLED.getBooleanValue()) {
            return;
        }
        Path dir = Paths.get(ScenarioTraceRecorder.TRACE_DIR);
        if (!Files.isDirectory(dir)) {
            return;
        }
        long maxTotalBytes = Math.max(0L,
                WebFrameworkConfig.PLAYWRIGHT_CONTEXT_TRACE_RETENTION_MAX_TOTAL_MB.getIntValue()) * 1024L * 1024L;
        int maxFiles = Math.max(0,
                WebFrameworkConfig.PLAYWRIGHT_CONTEXT_TRACE_RETENTION_MAX_FILES.getIntValue());

        List<Path> stale;
        long totalBytes;
        try (Stream<Path> s = Files.list(dir)) {
            List<Path> all = s.filter(Files::isRegularFile).toList();
            // 只允许删上一轮的旧文件，本轮新产物永不删
            stale = new ArrayList<>();
            long bytes = 0L;
            for (Path p : all) {
                try {
                    BasicFileAttributes attr = Files.readAttributes(p, BasicFileAttributes.class);
                    bytes += attr.size();
                    if (attr.lastModifiedTime().toMillis() < RUN_START_MS) {
                        stale.add(p);
                    }
                } catch (IOException ignore) {
                    // 单个文件 stat 失败跳过，不影响整体治理
                }
            }
            totalBytes = bytes;
        } catch (IOException e) {
            VerboseLogging.logDebugIfVerbose(logger, "TraceRetention list {} failed: {}", dir, e.getMessage());
            return;
        }

        if (stale.isEmpty()) {
            return;
        }
        // 旧文件按 mtime 升序（最旧的先删）
        stale.sort(Comparator.comparingLong(p -> {
            try {
                return Files.readAttributes(p, BasicFileAttributes.class).lastModifiedTime().toMillis();
            } catch (IOException e) {
                return Long.MAX_VALUE;
            }
        }));

        int deleted = 0;
        long freed = 0L;
        long staleBytes = 0L;
        // 先按文件数算：需要删 (all - maxFiles) 个，但只从 stale 里删
        int allCount = countAll(dir);
        int overflowFiles = Math.max(0, allCount - maxFiles);
        long overflowBytes = Math.max(0L, totalBytes - maxTotalBytes);

        for (Path p : stale) {
            if (overflowFiles <= 0 && overflowBytes <= 0) {
                break;
            }
            long sz;
            try {
                sz = Files.size(p);
            } catch (IOException e) {
                continue;
            }
            try {
                Files.deleteIfExists(p);
                deleted++;
                freed += sz;
                staleBytes += sz;
                overflowFiles--;
                overflowBytes -= sz;
            } catch (IOException e) {
                VerboseLogging.logDebugIfVerbose(logger, "TraceRetention delete {} failed: {}", p, e.getMessage());
            }
        }
        if (deleted > 0) {
            logger.info("[trace-retention] pruned {} old trace file(s), freed {} KB from {}",
                    deleted, freed / 1024, dir);
        }
    }

    private static int countAll(Path dir) {
        try (Stream<Path> s = Files.list(dir)) {
            return (int) s.filter(Files::isRegularFile).count();
        } catch (IOException e) {
            return 0;
        }
    }
}
