package com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen.engine;

import com.hsbc.cmb.hk.dbb.automation.framework.common.context.LanguageState;
import com.microsoft.playwright.Page;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 全局单线程串行事件循环（对齐 Playwright server 设计：顺序 = 提交顺序）。
 *
 * <p>取代原先分散的 {@code genExecutor} / per-page {@code pickExecutor}：所有页的拾取解析、实时代码生成、
 * 页面注入、通道开启、状态推送与页面清理统一入队到同一条 daemon 单线程，任务按提交顺序单调执行，天然有序；
 * 调用方（{@code onPage} / {@code bindingCalled} / {@code onDialog} 等派发线程回调）<b>只登记 + submit，
 * 永不做协议往返</b>，保留既有防死锁原则。引擎线程上的协议调用与主线程阻塞互不相关。
 *
 * <p>事件泵（驱动 Playwright 消息派发）也折叠进同一条线程（{@link #startPump}），与拾取/生成串行执行，
 * 不再额外起线程；代价是长拾取（~170ms）会短暂推迟下一拍泵，属可接受的次级影响。
 *
 * <p>任务带 {@link Kind} 与 {@code page.url()}，统一 {@code [engine#seq] KIND page=url} 前缀并写入 MDC，
 * 可观测、可回放。会话结束时 {@link #shutdownGracefully()} 先 drain 再关，避免残留任务覆盖定稿。
 */
public final class CodegenEngine {

    private static final Logger log = LoggerFactory.getLogger(CodegenEngine.class);

    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "codegen-engine");   // 统一命名，固定 1 线程
        t.setDaemon(true);
        return t;
    });
    private final AtomicLong seq = new AtomicLong();   // 引擎内单调序号 = 全局事件顺序
    private final String nlsLang;
    private volatile boolean stopped = false;

    /** 引擎任务种类（用于日志前缀与 MDC，便于按类型回放）。 */
    public enum Kind { PICK, REGEN, INJECT, ENABLE, STATUS, CLOSE, PUMP }

    public CodegenEngine(String nlsLang) {
        this.nlsLang = nlsLang;
    }

    /**
     * 入队一个引擎任务。引擎已 shutdown / 会话已 stopped 时静默拒绝（返回 false）。
     *
     * @return true 表示已入队；false 表示被拒绝（调用方应释放在途占位，如拾取 guard）。
     */
    public boolean submit(Kind kind, Page page, Runnable task) {
        if (stopped || executor.isShutdown()) {
            return false;
        }
        long n = seq.incrementAndGet();
        String url = page == null ? "-" : page.url();
        try {
            executor.execute(() -> {
                try {
                    if (nlsLang != null) {
                        LanguageState.setLanguage(nlsLang);
                    }
                    MDC.put("codegen.engine", Long.toString(n));
                    MDC.put("codegen.kind", kind.name());
                    MDC.put("codegen.page", url);
                    task.run();
                } catch (Throwable t) {
                    log.warn("[engine#{}] {} on {} failed: {}", n, kind, url, t.toString());
                } finally {
                    MDC.clear();
                }
            });
            return true;
        } catch (RuntimeException re) {   // 极少数 shutdown 竞态下的 RejectedExecutionException
            log.debug("[engine] submit rejected after shutdown: {}", re.toString());
            return false;
        }
    }

    /**
     * 在引擎线程上以固定延迟调度一个周期性泵任务（用于驱动 Playwright 消息派发）。
     * 泵与 {@link #submit} 的任务共用同一条 daemon 线程，串行执行。
     *
     * @param initialDelayMs 首次执行的延迟
     * @param periodMs       周期间隔
     * @param pump           每拍要执行的任务（自身须容错，异常不应向外传播）
     */
    public void startPump(long initialDelayMs, long periodMs, Runnable pump) {
        if (stopped || executor.isShutdown()) {
            return;
        }
        try {
            executor.scheduleWithFixedDelay(pump, initialDelayMs, periodMs, TimeUnit.MILLISECONDS);
        } catch (RuntimeException re) {   // shutdown 竞态
            log.debug("[engine] pump schedule rejected after shutdown: {}", re.toString());
        }
    }

    /** 优雅停：先置 stopped（拒绝新任务），再 drain 在途任务，超时强杀。 */
    public void shutdownGracefully() {
        stopped = true;
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            executor.shutdownNow();
        }
    }
}
