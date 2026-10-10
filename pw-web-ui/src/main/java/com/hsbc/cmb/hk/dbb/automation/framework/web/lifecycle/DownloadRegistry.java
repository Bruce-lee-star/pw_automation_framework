package com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle;

import com.microsoft.playwright.BrowserContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * 下载登记簿（framework-internal）：记录每个 {@link BrowserContext} 最近保存的下载文件
 * （含<b>原始文件名</b>与<b>落盘路径</b>），供业务层经 {@link PlaywrightManager#getLastDownloadPath()} /
 * {@link PlaywrightManager#getLastDownloadFileName()} /
 * {@link PlaywrightManager#getLastDownloadOriginalFileName()} /
 * {@link PlaywrightManager#getDownloadPaths()} 查询。
 *
 * <p><b>线程安全：</b>以 {@code BrowserContext} 为键，各上下文独立维护一个 FIFO 队列（最近下载追加至队尾）。
 * 跨线程可见性由 {@link ConcurrentHashMap} / {@link ConcurrentLinkedDeque} 的 happens-before 语义保证——
 * 登记发生在 Playwright 下载事件回调线程，查询发生在业务（场景）线程，二者经本登记簿安全传递，无额外锁。</p>
 *
 * <p><b>生命周期：</b>随 {@code BrowserContext} 隔离；{@link #clear(BrowserContext)} 在 context 关闭时由
 * {@code PlaywrightContextManager.closeContext} 调用，避免陈旧上下文的下载记录无限堆积。</p>
 *
 * @apiNote 业务代码请勿直接使用本类，统一经 {@link PlaywrightManager} 的下载查询方法；本类仅承载框架内部登记状态。
 */
public final class DownloadRegistry {

    private static final Logger logger = LoggerFactory.getLogger(DownloadRegistry.class);

    /**
     * 单例持有者（lazy holder）。
     *
     * <p>把实例字段移入嵌套类，使 {@link #instance()} 不再「返回本类的 static 字段」——这是 SpotBugs
     * {@code MS_EXPOSE_REP}（public static 方法暴露可变静态表示）的触发形态。本类是无状态协作对象
     * （状态都在 {@link #byContext} 的并发容器里），懒加载不改变任何可观察行为。
     */
    private static final class InstanceHolder {
        private static final DownloadRegistry INSTANCE = new DownloadRegistry();
    }

    private final Map<BrowserContext, Deque<DownloadRecord>> byContext = new ConcurrentHashMap<>();

    /**
     * 一次已落盘下载的登记项：<b>原始文件名</b>（响应 {@code Content-Disposition}，即
     * {@code Download.suggestedFilename()}）与<b>落盘路径</b>。
     *
     * <p><b>为什么要分开存</b>：落盘路径经 {@code resolveNonConflictingDownloadPath} 做过同名去重 ——
     * 同名文件已存在（并发下载 / 重复导出同一份报表）时文件名会被改写，落盘名 != 原始名。
     * 断言"下载到的文件叫什么"应以原始名为准；要读文件内容则用落盘路径。</p>
     *
     * @param suggestedFileName 原始文件名（可为 null：极端情况下 Playwright 未提供）
     * @param savedPath         实际落盘的绝对路径
     */
    public record DownloadRecord(String suggestedFileName, Path savedPath) {
    }

    private DownloadRegistry() {
    }

    /**
     * 获取单例（饿汉、无状态协作对象）。
     */
    public static DownloadRegistry instance() {
        return InstanceHolder.INSTANCE;
    }

    /**
     * 登记一次已落盘的下载（由 {@code PlaywrightContextManager.registerDownloadHandler}
     * 在 {@code download.saveAs} 成功后调用）。
     *
     * @param context           触发下载的浏览器上下文（null 安全：忽略）
     * @param suggestedFileName 原始文件名（{@code Download.suggestedFilename()}；null 安全）
     * @param savedPath         已保存文件的绝对路径（null 安全：忽略）
     */
    public void record(BrowserContext context, String suggestedFileName, Path savedPath) {
        if (context == null || savedPath == null) {
            return;
        }
        byContext.computeIfAbsent(context, k -> new ConcurrentLinkedDeque<>())
                .addLast(new DownloadRecord(suggestedFileName, savedPath));
        if (logger.isDebugEnabled()) {
            logger.debug("[Download] Recorded saved file for context {}: {} -> {}",
                    System.identityHashCode(context), suggestedFileName, savedPath);
        }
    }

    /**
     * 取指定上下文最近一次下载的<b>落盘路径</b>；无记录返回 {@code null}。
     */
    public Path last(BrowserContext context) {
        DownloadRecord record = lastRecord(context);
        return record == null ? null : record.savedPath();
    }

    /**
     * 取指定上下文最近一次下载的完整登记项（原始文件名 + 落盘路径）；无记录返回 {@code null}。
     */
    public DownloadRecord lastRecord(BrowserContext context) {
        Deque<DownloadRecord> deque = context == null ? null : byContext.get(context);
        return (deque == null || deque.isEmpty()) ? null : deque.peekLast();
    }

    /**
     * 取指定上下文全部下载的<b>落盘路径</b>（按时间升序）；无记录返回空列表（非 {@code null}）。
     */
    public List<Path> all(BrowserContext context) {
        Deque<DownloadRecord> deque = context == null ? null : byContext.get(context);
        if (deque == null || deque.isEmpty()) {
            return Collections.emptyList();
        }
        List<Path> paths = new ArrayList<>(deque.size());
        for (DownloadRecord record : deque) {
            if (record != null && record.savedPath() != null) {
                paths.add(record.savedPath());
            }
        }
        return Collections.unmodifiableList(paths);
    }

    /**
     * 清除指定上下文的全部下载记录（context 关闭时调用）。
     */
    public void clear(BrowserContext context) {
        if (context != null) {
            byContext.remove(context);
        }
    }
}
