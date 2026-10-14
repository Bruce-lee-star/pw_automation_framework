package com.hsbc.cmb.hk.dbb.automation.framework.web.concurrent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.management.ManagementFactory;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * SSO 并发自检（设计文档 §10 并发自检）。
 *
 * <p>为「同一 sessionKey 在 JVM 内必须串行」这一闸门不变量提供<b>可选的</b>运行期观测与兜底。
 * 默认关闭（{@code sso.concurrency.selfcheck.enabled=false}），仅在内网压测 / 排障时显式开启，
 * 不影响常规运行（零回归）。
 *
 * <p>计数维度：
 * <ul>
 *   <li>按 {@code sessionKey}（= {@code env_user}）统计当前持有闸门的活动线程数，应恒 ≤ {@code maxPerKey}（默认 1）；
 *       超出即说明闸门失效或跨 fork 同身份并发，触发 ERROR 诊断（{@code failfast=true} 时抛 {@link IllegalStateException}）。</li>
 *   <li>全局活动持有数 {@code TOTAL} 仅用于日志观测，便于核对「实际并发」与 §14 并发上限公式。</li>
 * </ul>
 *
 * <p>开关（均走 {@code System.getProperty}，实时读取；CI 经 surefire {@code argLine} / {@code -D} 注入）：
 * <ul>
 *   <li>{@code sso.concurrency.selfcheck.enabled} — 是否启用（默认 false）。</li>
 *   <li>{@code sso.concurrency.selfcheck.failfast} — 超限是否抛异常中断（默认 false，仅记 ERROR）。</li>
 *   <li>{@code sso.concurrency.selfcheck.maxPerKey} — 单身份允许的活动持有上限（默认 1）。</li>
 * </ul>
 */
public final class SsoConcurrencySelfCheck {

    private static final Logger LOGGER = LoggerFactory.getLogger(SsoConcurrencySelfCheck.class);

    private static final ConcurrentHashMap<String, AtomicLong> PER_KEY = new ConcurrentHashMap<>();
    private static final AtomicLong TOTAL = new AtomicLong(0);

    private SsoConcurrencySelfCheck() {
    }

    /** 闸门 acquire 成功后调用：登记活动持有，校验不变量。 */
    public static void onAcquire(Object sessionKey) {
        if (!isEnabled() || sessionKey == null) {
            return;
        }
        String key = sessionKey.toString();
        long total = TOTAL.incrementAndGet();
        long perKey = PER_KEY.computeIfAbsent(key, k -> new AtomicLong(0L)).incrementAndGet();
        int max = maxPerKey();
        if (perKey > max) {
            String msg = String.format(
                    "SSO concurrency self-check: same-identity active holders %d > maxPerKey %d "
                            + "[sessionKey=%s, pid=%s, fork.index=%s, thread=%s, totalActive=%d]",
                    perKey, max, key, pid(), forkIndex(),
                    Thread.currentThread().getName(), total);
            if (isFailfast()) {
                LOGGER.error("[SsoConcurrencySelfCheck] {}", msg);
                throw new IllegalStateException(msg);
            }
            LOGGER.error("[SsoConcurrencySelfCheck] {}", msg);
        }
    }

    /** 闸门 release 时调用：注销活动持有。 */
    public static void onRelease(Object sessionKey) {
        if (!isEnabled() || sessionKey == null) {
            return;
        }
        String key = sessionKey.toString();
        TOTAL.decrementAndGet();
        AtomicLong counter = PER_KEY.get(key);
        if (counter != null && counter.decrementAndGet() <= 0L) {
            PER_KEY.remove(key);
        }
    }

    private static boolean isEnabled() {
        return Boolean.getBoolean("sso.concurrency.selfcheck.enabled");
    }

    private static boolean isFailfast() {
        return Boolean.getBoolean("sso.concurrency.selfcheck.failfast");
    }

    private static int maxPerKey() {
        return Integer.getInteger("sso.concurrency.selfcheck.maxPerKey", 1);
    }

    private static String pid() {
        try {
            return ManagementFactory.getRuntimeMXBean().getName().split("@")[0];
        } catch (Exception e) {
            return "?";
        }
    }

    private static String forkIndex() {
        return System.getProperty("fork.index", "?");
    }
}
