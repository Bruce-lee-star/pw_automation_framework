package com.hsbc.cmb.hk.dbb.automation.framework.route.binding;

import com.microsoft.playwright.BrowserContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ServiceLoader;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * {@link GuardedDriverCall} 注册表（SPI 约定 + 测试可注入）。
 *
 * <p>除保留历史 {@link #instance()} 默认/注入信道外，2026-10-24 起额外提供
 * <b>按连接（{@code BrowserContext}）的每实例信道</b>：</p>
 * <ul>
 *   <li>{@link #channelFor(BrowserContext)}：为指定连接创建 / 复用专属 {@link GuardedDriverCallImpl}，
 *       并行场景（各自独立 Playwright 连接）彼此隔离，某连接的超时 {@code shutdownNow()} 不再牵连其它连接；</li>
 *   <li>{@link #remove(BrowserContext)}：context 关闭时清退其专属信道（释放 daemon 线程）；</li>
 *   <li>{@link #currentChannel()}：当前线程所属连接的信道，供全局查询 / 复位方法定位"本连接的信道"。</li>
 * </ul>
 *
 * <p>注入（{@link #setInstance}）仍优先：测试注入替身时，{@link #channelFor} / {@link #currentChannel} 一律返回替身。</p>
 */
public final class GuardedDriverCallRegistry {

    private static final Logger LOGGER = LoggerFactory.getLogger(GuardedDriverCallRegistry.class);

    /** SPI 约定文件（classpath）。 */
    public static final String SPI_FILE =
            "META-INF/services/" + GuardedDriverCall.class.getName();

    private static volatile GuardedDriverCall instance;
    /** 是否由 {@link #setInstance} 注入了替身（注入优先级高于默认解析与按连接缓存）。 */
    private static final AtomicBoolean injected = new AtomicBoolean(false);
    private static final Object LOCK = new Object();

    /** 每连接（BrowserContext）信道实例缓存：并行场景下各用例拥有专属串行化线程，互不污染。 */
    private static final ConcurrentHashMap<BrowserContext, GuardedDriverCallImpl> CHANNELS = new ConcurrentHashMap<>();

    /** 当前线程活动信道：全局查询 / 复位方法据此定位"本连接的信道"。 */
    private static final ThreadLocal<GuardedDriverCallImpl> CURRENT = new ThreadLocal<>();

    private GuardedDriverCallRegistry() {
    }

    /** 由 {@link GuardedDriverCallImpl} 构造登记自身为当前线程活动信道（同包可见）。 */
    static void setCurrent(GuardedDriverCallImpl impl) {
        CURRENT.set(impl);
    }

    /** 当前线程所属连接的信道（无则回退默认实例；均不可得返回 null）。 */
    public static GuardedDriverCallImpl currentChannel() {
        GuardedDriverCallImpl c = CURRENT.get();
        if (c != null) {
            return c;
        }
        GuardedDriverCall g = instance();
        return (g instanceof GuardedDriverCallImpl) ? (GuardedDriverCallImpl) g : null;
    }

    /**
     * 取 / 建指定 context 专属信道（并行隔离的关键：每连接一条串行化线程）。
     *
     * <p>注入替身、或 {@code ctx == null} 时回退 {@link #instance()}。</p>
     */
    public static GuardedDriverCall channelFor(BrowserContext ctx) {
        if (injected.get() || ctx == null) {
            return instance();
        }
        GuardedDriverCallImpl impl = CHANNELS.computeIfAbsent(ctx, k -> new GuardedDriverCallImpl());
        CURRENT.set(impl);
        return impl;
    }

    /** context 关闭时清理其专属信道（释放 daemon 线程，防泄漏）。 */
    public static void remove(BrowserContext ctx) {
        if (ctx == null) {
            return;
        }
        GuardedDriverCallImpl removed = CHANNELS.remove(ctx);
        if (removed != null) {
            removed.shutdownNow();
        }
    }

    /** 套件收尾：复位所有缓存信道 + 当前信道（不打 ERROR/WARN）。 */
    public static void resetAllForTeardown() {
        for (GuardedDriverCallImpl impl : CHANNELS.values()) {
            impl.resetChannelForTeardownInstance();
        }
        GuardedDriverCallImpl cur = CURRENT.get();
        if (cur != null) {
            cur.resetChannelForTeardownInstance();
        }
        CHANNELS.clear();
    }

    /**
     * 解析默认实现（SPI 优先，否则回退 {@link GuardedDriverCallImpl}）。
     */
    public static GuardedDriverCall instance() {
        GuardedDriverCall guard = instance;
        if (guard == null) {
            synchronized (LOCK) {
                guard = instance;
                if (guard == null) {
                    instance = guard = resolve();
                }
            }
        }
        if (guard instanceof GuardedDriverCallImpl) {
            CURRENT.set((GuardedDriverCallImpl) guard);
        }
        return guard;
    }

    private static GuardedDriverCall resolve() {
        try {
            ServiceLoader<GuardedDriverCall> loader = ServiceLoader.load(GuardedDriverCall.class);
            for (GuardedDriverCall guard : loader) {
                LOGGER.debug("[Route] GuardedDriverCall resolved via SPI: {}", guard.getClass().getName());
                return guard;
            }
        } catch (Throwable t) {
            //  环境缺失 / 自定义实现加载失败时，安全回退默认实现（框架自带，零依赖）
            LOGGER.debug("[Route] GuardedDriverCall SPI 解析失败，回退默认实现: {}", t.toString());
        }
        return new GuardedDriverCallImpl();
    }

    /**
     * 注入替代实现（测试用）。一旦注入，{@link #channelFor} / {@link #currentChannel} / {@link #instance}
     * 一律返回该替身。
     */
    public static void setInstance(GuardedDriverCall guard) {
        instance = java.util.Objects.requireNonNull(guard, "guard");
        injected.set(true);
    }

    /** 复位为默认（重新走 SPI 解析）。 */
    public static void reset() {
        instance = null;
        injected.set(false);
    }
}
