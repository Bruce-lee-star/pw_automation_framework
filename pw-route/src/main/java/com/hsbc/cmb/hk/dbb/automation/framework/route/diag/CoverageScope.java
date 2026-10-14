package com.hsbc.cmb.hk.dbb.automation.framework.route.diag;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 路由观测的<b>覆盖范围</b>（coverage scope）—— 显式声明"哪些流量真的被看见、哪些没有"。
 *
 * <p><b>为什么需要</b>：本框架的采集基于 {@code BrowserContext.route()}，而这个机制自带边界。
 * 未标注覆盖范围的"全量 API 监控"没有可审计意义：使用者无法判断"报告里没有这条 API"
 * 到底是产品没发、还是框架看不见。本类把这类边界固化为<b>机器可读</b>的标签，
 * 供一次性告警、报告元数据和后续自动化核对使用。
 *
 * <p><b>来源（Playwright 官方契约）</b>：
 * <ul>
 *   <li>{@code HTTP_CACHE_DISABLED_BY_ROUTE}：启用 {@code BrowserContext.route()} 后会绕过
 *       常规 HTTP 缓存路径。这不是"清空缓存"，而是拦截架构关闭了 cache —— 由此得到的响应时序
 *       可能与非拦截模式不同。<b>任何"缓存命中/未命中"类断言在本模式下不成立。</b></li>
 *   <li>{@code SERVICE_WORKER_UNVERIFIED}：官方明确 {@code BrowserContext.route()} 不拦截已被
 *       Service Worker 拦截的请求；完全在 worker 内处理的请求甚至不触及外部网络。框架当前
 *       <b>未</b>把 {@code serviceWorkers} 设为 {@code BLOCK}，故 SW 参与的/在 SW 内闭环的
 *       请求不可见。</li>
 *   <li>{@code DOWNLOADS_UNMODELLED}：下载走浏览器特殊路径，{@code Download} 事件在<b>开始</b>时发出，
 *       {@code path()} 完成后才可用，与普通 {@code Response} 语义不同；采集侧目前不把它归因到
 *       触发请求。</li>
 * </ul>
 *
 * <p><b>已闭合的边界（不再列为标签）</b>：
 * <ul>
 *   <li>归属三元组（{@code ownerPageId}/{@code frameId}/{@code navSeq}）此前只入库、业务读不到；
 *       现已映射到跨模块视图（{@code CapturedApiCallView#origin()}）并配套
 *       {@code getCallsByOwner} / {@code waitForApiFromOwner}。</li>
 *   <li>重定向各跳此前未串联（"最终 URL 命中一次"被误当作整条链）；现已按
 *       {@code redirectedFrom()} 回溯出 {@code redirectChainId} 与 {@code redirectHop}，
 *       各跳共享链 id，可分别断言链中/链尾状态。</li>
 * </ul>
 * 连续导航仍建议配合 {@code waitForApiSince} 的采集序号门槛使用。</p>
 * </ul>
 *
 * <p><b>线程安全</b>：不可变；{@link #announceOnce()} 用 {@link AtomicBoolean} 保证 JVM 内只告警一次
 * （避免每个 pattern 绑定都刷一行）。
 */
public final class CoverageScope {

    /** {@link #ANNOUNCED} 的一次性开关。 */
    private static final AtomicBoolean ANNOUNCED = new AtomicBoolean(false);

    /** 单一实例：无状态且不可变，可安全跨线程共享。 */
    private static final CoverageScope INSTANCE = new CoverageScope();

    private static final Logger LOGGER = LoggerFactory.getLogger(CoverageScope.class);

    private final Set<Tag> tags;

    private CoverageScope() {
        Set<Tag> built = new LinkedHashSet<>();
        built.add(Tag.ROUTE_SYNC);
        built.add(Tag.HTTP_CACHE_DISABLED_BY_ROUTE);
        built.add(Tag.SERVICE_WORKER_UNVERIFIED);
        built.add(Tag.DOWNLOADS_UNMODELLED);
        this.tags = Collections.unmodifiableSet(built);
    }

    /** 覆盖范围标签。 */
    public enum Tag {
        /** 采集经由 {@code context.route()} 同步取得（不是事件订阅）。 */
        ROUTE_SYNC,
        /** 路由已启用 ⇒ HTTP 缓存被绕开，缓存类结论不可用。 */
        HTTP_CACHE_DISABLED_BY_ROUTE,
        /** 未禁用 Service Worker ⇒ 其拦截/闭环的请求不可见。 */
        SERVICE_WORKER_UNVERIFIED,
        /** 下载未归因到触发请求。 */
        DOWNLOADS_UNMODELLED
    }

    /** 当前覆盖范围（不可变视图）。 */
    public static CoverageScope current() {
        return INSTANCE;
    }

    /** 不可变的标签集合。 */
    public Set<Tag> tags() {
        return tags;
    }

    /** 是否包含指定标签（供报告/测试判定）。 */
    public boolean has(Tag tag) {
        return tag != null && tags.contains(tag);
    }

    /** 逗号分隔的标签名（日志/报告可直接渲染）。 */
    public String describe() {
        StringBuilder sb = new StringBuilder();
        for (Tag tag : tags) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(tag.name());
        }
        return sb.toString();
    }

    /**
     * JVM 内<b>一次性</b>告警覆盖范围边界。
     *
     * <p>在首次成功注册 {@code context.route()} 之后调用（此时"缓存被绕开"这一事实才成立）。
     * 用 WARN 而非 ERROR：这不是故障，而是<b>使用时必须知道的观测边界</b>；重复调用无成本，
     * 也不改变行为 —— 采集链路绝不因告警失败而中断。
     */
    public static void announceOnce() {
        if (!ANNOUNCED.compareAndSet(false, true)) {
            return;
        }
        LOGGER.warn("[Route] API coverage scope = [{}]. Note: BrowserContext.route() bypasses the HTTP cache "
                        + "(cache-related observations are NOT representative); Service Worker is not set to BLOCK, "
                        + "so requests intercepted/served inside a Service Worker are invisible; downloads are not "
                        + "not attributed to their trigger request "
                        + "(a late response from a previous navigation may satisfy a later wait — use waitForApiSince(..) "
                        + "or waitForApiFromOwner(..) to scope waits to records produced after your action).",
                INSTANCE.describe());
    }
}
