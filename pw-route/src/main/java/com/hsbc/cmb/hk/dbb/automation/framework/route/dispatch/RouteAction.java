package com.hsbc.cmb.hk.dbb.automation.framework.route.dispatch;

import com.hsbc.cmb.hk.dbb.automation.framework.route.dsl.ApiSpec;
import com.microsoft.playwright.APIRequestContext;
import com.microsoft.playwright.APIResponse;
import com.microsoft.playwright.Request;
import com.microsoft.playwright.Route;
import com.microsoft.playwright.options.RequestOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 终结动作 —— 对 Playwright Route 的全部终结调用的统一出口。
 *
 * <p>为什么统一出口（对应 playwright-java-1.62.0 源码事实）：
 * <ul>
 *   <li>{@code startHandling()} 单次性：重复终结抛 "Route is already handled!"（RouteImpl:246-251），
 *       统一出口把所有终结路径收敛到一个 try/catch 边界，失败一律记录并转为 fail-open；</li>
 *   <li>{@code resume/fulfill/abort/fallback} 均为 {@code sendMessageAsync}（Connection:139），
 *       不阻塞事件线程——因此这些动作允许在事件线程直接调用，而 {@code fetch()} 是同步 HTTP，
 *       只允许在调用线程经 {@link GuardedRunner} 限流调用（见 {@link #fetchAndFulfill}；
 *       不得派发到其它线程，避免并发驱动同一条连接）。</li>
 * </ul>
 */
public final class RouteAction {

    private static final Logger LOGGER = LoggerFactory.getLogger(RouteAction.class);

    /** fetch 同步请求的超时（毫秒），与 Playwright APIRequestContext 默认一致。 */
    public static final long DEFAULT_FETCH_TIMEOUT_MS = 30_000L;

    /**
     * 诊断探针（默认<b>开</b>；<b>纯日志、行为零改动</b>）：intercept 的 fetch 失败时打印判定所需的
     * 全部事实 —— 全局在飞 fetch 数、本次序号、失败 URL、最近一次 {@code APIResponse.dispose()} 的
     * 响应（url@时刻）与异常全文。
     *
     * <p><b>已得结论（两趟 A/B，2026-10-09）</b>：该异常抛在 {@code APIRequestContext.fetch(...)}
     * <b>内部</b>，来自驱动 {@code Connection.getExistingObject}（引用的句柄已被回收），且落点随机
     * （{@code frame@}/{@code request@}/{@code worker@}/{@code response@} 随趟次漂移）；
     * <b>与"我们自己的多个 fetch 并发"无关</b> —— 5/6 次失败时在飞数=1，且串行化开关开/关两趟的
     * 失败数、失败场景、毒化与 reset 次数完全一致，故本类<b>不再提供串行化开关</b>。
     *
     * <p>真实条件是"连接共享泵"：谁在等回包谁就泵这条连接，任一在等回包的调用（主线程 UI 调用、
     * 驱动线程的 {@code bind}/{@code unroute}、我们的 fetch）都可能被死句柄消息撞上。
     *
     * <p>关闭：{@code -Droute.fetch.probe=false} 或环境变量 {@code ROUTE_FETCH_PROBE=false}。
     */
    private static final boolean FETCH_PROBE = flag("route.fetch.probe", "ROUTE_FETCH_PROBE", true);

    /** 全局在飞 intercept fetch 数（探针用；多 Context 并行时为其总和）。 */
    private static final AtomicInteger FETCH_INFLIGHT = new AtomicInteger();

    /** intercept fetch 全局序号（探针用）。 */
    private static final AtomicLong FETCH_SEQ = new AtomicLong();

    /** 配置自证只打一次（探针用）：让日志能证明本次运行的有效开关。 */
    private static final java.util.concurrent.atomic.AtomicBoolean FLAGS_LOGGED =
            new java.util.concurrent.atomic.AtomicBoolean();

    /** 最近一次 {@code APIResponse.dispose()} 的描述（{@code url@时刻}），供 fetch 失败时对照。 */
    private static final AtomicReference<String> LAST_DISPOSE = new AtomicReference<>("<none>");

    /** 读开关：系统属性优先，其次环境变量，最后默认值（与 {@code MonitorSink} 同款口径）。 */
    private static boolean flag(String prop, String env, boolean defaultValue) {
        String v = System.getProperty(prop);
        if (v == null || v.isEmpty()) {
            v = System.getenv(env);
        }
        if (v == null || v.isEmpty()) {
            return defaultValue;
        }
        return !"false".equalsIgnoreCase(v.trim());
    }

    /**
     * 由终结路径在 {@code APIResponse.dispose()} 之后调用（探针用）。
     *
     * @param url 被释放响应所属请求的 URL（可为 null）
     */
    public static void noteApiResponseDisposed(String url) {
        if (FETCH_PROBE) {
            LAST_DISPOSE.set(url + " @" + System.currentTimeMillis());
        }
    }

    private RouteAction() {
    }

    /**
     * 放行请求（可选 overrides：MODIFY_REQUEST 语义）。
     *
     * @return true=resume 命令成功发出；false=route 已被处理或对象已失效（无悬挂风险）
     */
    public static boolean resume(Route route, ApiSpec spec) {
        return resume(route, spec, null);
    }

    /**
     * 纯放行请求（无 MODIFY/MOCK，仅 DELAY/MONITOR 观测）：等价于 {@code route.resume()} 无参。
     *
     * @return true=resume 命令成功发出；false=route 已被处理或对象已失效（无悬挂风险）
     */
    public static boolean resume(Route route) {
        try {
            route.resume();
            return true;
        } catch (RuntimeException e) {
            LOGGER.warn("[Route] resume failed for url='{}': {}", route.request().url(), e.toString());
            return false;
        }
    }

    /**
     * 放行请求（MODIFY_REQUEST 语义，可同时携带 method、header 与 body 修改）。
     *
     * <p>对照 playwright-java-1.62.0：{@code Route.ResumeOptions} 原生支持
     * {@code setMethod/setHeaders/setPostData}（Route.java:38-83），{@code RouteImpl.resumeImpl}
     * 序列化 url/method/headers/postData 四项（RouteImpl.java:118-137）——因此
     * {@code modifyMethod} 无需 fetch+fulfill 重放，直接 resume(options) 即可：
     * 请求真实发出、单次请求零额外 HTTP 往返、无副作用重放（POST 不会二次提交）。
     *
     * <p>headers 语义：合并（保留未指定 header + 删除 remove 集 + 覆盖 set 集）后整体传入。
     * 与官方「resume(headers) 整体替换」行为等效（官方以传入集为准；此处传入集=原集+修改）。
     *
     * @param postData 修改后的请求体；null=不改 body
     */
    public static boolean resume(Route route, ApiSpec spec, String postData) {
        try {
            if (spec == null && postData == null) {
                route.resume();
            } else {
                Route.ResumeOptions options = new Route.ResumeOptions();
                if (spec != null && spec.modifyMethod() != null) {
                    options.setMethod(spec.modifyMethod());
                }
                if (spec != null && (!spec.requestHeadersToSet().isEmpty() || !spec.requestHeadersToRemove().isEmpty())) {
                    Map<String, String> headers = new LinkedHashMap<>(route.request().headers());
                    headers.keySet().removeAll(spec.requestHeadersToRemove());
                    headers.putAll(spec.requestHeadersToSet());
                    options.setHeaders(headers);
                }
                if (postData != null) {
                    options.setPostData(postData);
                }
                route.resume(options);
            }
            return true;
        } catch (RuntimeException e) {
            LOGGER.warn("[Route] resume failed for url='{}': {}", route.request().url(), e.toString());
            return false;
        }
    }

    /** 静态伪造响应（MOCK 静态）。 */
    public static boolean fulfill(Route route, ApiSpec spec) {
        try {
            Route.FulfillOptions options = new Route.FulfillOptions();
            if (spec.mockStatus() != null) {
                options.setStatus(spec.mockStatus());
            }
            if (spec.mockBodyBytes() != null) {
                // 字节保真通道：由 Playwright setBodyBytes 交付原始字节，不经任何字符集往返
                options.setBodyBytes(spec.mockBodyBytes());
            } else if (spec.mockBody() != null) {
                options.setBody(spec.mockBody());
            }
            if (spec.mockContentType() != null) {
                options.setContentType(spec.mockContentType());
            }
            if (!spec.mockHeaders().isEmpty()) {
                options.setHeaders(spec.mockHeaders());
            }
            route.fulfill(options);
            return true;
        } catch (RuntimeException e) {
            LOGGER.warn("[Route] fulfill failed for url='{}': {}", route.request().url(), e.toString());
            return false;
        }
    }

    /** 以真实响应终结（MOCK intercept，在调用线程就地调用）。 */
    public static boolean fulfillWithResponse(Route route, APIResponse response) {
        try {
            route.fulfill(new Route.FulfillOptions().setResponse(response));
            return true;
        } catch (RuntimeException e) {
            LOGGER.warn("[Route] fulfill(response) failed for url='{}': {}", route.request().url(), e.toString());
            return false;
        }
    }

    /** 以显式 status/headers/body 终结（MOCK 字段替换后，在调用线程就地调用）。 */
    public static boolean fulfillWithBody(Route route, Integer status, Map<String, String> headers, String body) {
        try {
            Route.FulfillOptions options = new Route.FulfillOptions();
            if (status != null) {
                options.setStatus(status);
            }
            if (headers != null && !headers.isEmpty()) {
                options.setHeaders(sanitizeBodyHeaders(headers));
            }
            if (body != null) {
                options.setBody(body);
            }
            route.fulfill(options);
            return true;
        } catch (RuntimeException e) {
            LOGGER.warn("[Route] fulfill(body) failed for url='{}': {}", route.request().url(), e.toString());
            return false;
        }
    }

    /**
     * 以显式 status/headers/<b>字节体</b>终结（在调用线程就地调用）。
     *
     * <p>供<b>字节保真通道</b>使用：非文本 Mock 体（或 {@code fromCaptured} 的非文本采集记录）
     * 只存在于 {@code mockBodyBytes}，当规则同时声明了字段替换时必须按原字节交付 ——
     * 替换对二进制"不适用"（{@code FieldReplacer} 契约：明确失败、保留原体）。
     */
    public static boolean fulfillWithBody(Route route, Integer status, Map<String, String> headers, byte[] body) {
        try {
            Route.FulfillOptions options = new Route.FulfillOptions();
            if (status != null) {
                options.setStatus(status);
            }
            if (headers != null && !headers.isEmpty()) {
                options.setHeaders(sanitizeBodyHeaders(headers));
            }
            if (body != null) {
                options.setBodyBytes(body);
            }
            route.fulfill(options);
            return true;
        } catch (RuntimeException e) {
            LOGGER.warn("[Route] fulfill(bodyBytes) failed for url='{}': {}", route.request().url(), e.toString());
            return false;
        }
    }

    /**
     * 「体编码/长度」相关头：框架交付的响应体一律是<b>已被 Playwright 解码</b>的明文（或重编码后的字节），
     * 与服务器原始 wire 头不再一致 —— 这三个头必须由 Playwright 依最终体重算，否则浏览器会按
     * {@code content-encoding} 去解一个已经不匹配的体（2026-10-10 实测：gzip 响应改字段后照抄
     * {@code content-encoding: gzip} ⇒ 页面取不到数据 ⇒ 顶栏下拉/首页局部空白）。
     */
    private static final Set<String> BODY_ENCODING_HEADERS =
            Set.of("content-encoding", "content-length", "transfer-encoding");

    /**
     * 过滤「体编码/长度」相关头（大小写不敏感）；其余头原样保留（{@code content-type} / {@code set-cookie} /
     * {@code cache-control} 等业务需要的头不受影响）。
     *
     * @param headers 原头（可为 null / 空）
     * @return 可安全交付给 {@link Route.FulfillOptions#setHeaders(java.util.Map)} 的头
     */
    static Map<String, String> sanitizeBodyHeaders(Map<String, String> headers) {
        if (headers == null || headers.isEmpty()) {
            return headers;
        }
        Map<String, String> out = new LinkedHashMap<>();
        java.util.List<String> stripped = new java.util.ArrayList<>();
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            String name = entry.getKey();
            if (name == null) {
                continue;
            }
            if (BODY_ENCODING_HEADERS.contains(name.toLowerCase(Locale.ROOT))) {
                stripped.add(name);
                continue;
            }
            out.put(name, entry.getValue());
        }
        if (!stripped.isEmpty()) {
            // 可观测：说明"这三个头由框架接管"，避免排障时误以为头被莫名吞掉
            LOGGER.debug("[Route] stripped body-encoding headers {} — Playwright recomputes them for the delivered body",
                    stripped);
        }
        return out;
    }

    /** 终止请求（仅 MOCK 在途任务取消场景使用；其余场景一律 fallback）。 */
    public static boolean abort(Route route, String errorCode) {
        try {
            route.abort(errorCode);
            return true;
        } catch (RuntimeException e) {
            LOGGER.warn("[Route] abort failed for url='{}': {}", route.request().url(), e.toString());
            return false;
        }
    }

    /**
     * 放弃处理、放行到真实后端（fail-open 出口）。
     *
     * <p>对应源码语义：pending 状态下 {@code fallback()} 自动转 continue（RouteImpl:68-77），
     * 是「框架不处理」时最安全的出口。
     */
    public static boolean fallback(Route route) {
        try {
            route.fallback();
            return true;
        } catch (RuntimeException e) {
            LOGGER.warn("[Route] fallback failed for url='{}': {}", route.request().url(), e.toString());
            return false;
        }
    }

    /**
     * 真实响应拦截（MOCK intercept）的取响应实现（在调用线程就地调用）。
     *
     * <p><b>必须在本线程就地调用（O1）</b>：它是同步 HTTP，且会驱动（泵）所属连接。经
     * {@link com.hsbc.cmb.hk.dbb.automation.framework.route.exec.GuardedRunner} 只是为"有界预算"
     * （该实现是信号量 + <b>内联执行</b>，不换线程）；<b>不得派发到其它线程</b> —— Playwright 的
     * {@code Connection} 由等待者线程分发消息，其对象表/回调表<b>非线程安全</b>，多线程并发驱动
     * 同一连接会命中竞态（假 {@code Object doesn't exist: xxx@} 与应答被丢弃 ⇒ 超时/毒化/RESET）。</p>
     *
     * <p><b>方案 A（结构化消除竞态）</b>：不使用 {@code route.fetch()}，统一用<b>事件线程拦截那一刻</b>
     * 取好的 {@link RequestSnapshot}（url/method/headers/body 值拷贝）+ {@code page.request()} 从零重发。
     * 取响应因此<b>不依赖</b>任何可能被浏览器驱动回收的 {@code frame}/{@code Request} 句柄，
     * 从结构上消除"导航期句柄回收"竞态——<b>无需</b>任何异常捕获或错误串匹配。任何失败直接上抛，
     * 由 {@link com.hsbc.cmb.hk.dbb.automation.framework.route.exec.GuardedRunner} 统一 fail-open。</p>
     *
     * @param requestContext runtime 所属 Context 的 {@link APIRequestContext}（调用方保证非空）
     * @param snapshot       拦截时的请求快照（调用方保证非空）
     */
    public static APIResponse fetch(APIRequestContext requestContext, RequestSnapshot snapshot) {
        RequestOptions options = RequestOptions.create()
                .setMethod(snapshot.method() == null || snapshot.method().isEmpty() ? "GET" : snapshot.method())
                .setTimeout(DEFAULT_FETCH_TIMEOUT_MS);
        Set<String> skip = Set.of("host", "content-length", "connection", "accept-encoding", "cookie");
        for (Map.Entry<String, String> entry : snapshot.headers().entrySet()) {
            String name = entry.getKey();
            if (name == null || name.isEmpty() || name.startsWith(":")
                    || skip.contains(name.toLowerCase(Locale.ROOT))) {
                continue; // 浏览器托管头交由客户端重算/注入，避免重复或冲突
            }
            options.setHeader(name, entry.getValue());
        }
        String body = snapshot.postData();
        if (body != null && !body.isEmpty()) {
            options.setData(body);
        }
        int inflight = FETCH_INFLIGHT.incrementAndGet();
        long seq = FETCH_SEQ.incrementAndGet();
        if (FLAGS_LOGGED.compareAndSet(false, true)) {
            // 自证本次运行的有效配置（诊断期排错用）
            LOGGER.info("[Route][FETCH-PROBE] flags: probe={} (via -Droute.fetch.probe / ROUTE_FETCH_PROBE)",
                    FETCH_PROBE);
        }
        try {
            return fetchOnce(requestContext, snapshot, options, seq, inflight);
        } finally {
            FETCH_INFLIGHT.decrementAndGet();
        }
    }

    /** 单次 fetch + 失败探针。不持有任何锁；失败上抛（由 GuardedRunner 统一 fail-open，语义不变）。 */
    private static APIResponse fetchOnce(APIRequestContext requestContext, RequestSnapshot snapshot,
                                         RequestOptions options, long seq, int inflight) {
        try {
            return requestContext.fetch(snapshot.url(), options);
        } catch (RuntimeException e) {
            if (FETCH_PROBE) {
                // 判定依据：失败瞬间的全局在飞数（>1 ⇒ 并发假设），本次序号，失败 URL，
                // 以及"最近一次被 dispose 的响应"（⇒ 上一次释放是否波及本次）。
                LOGGER.warn("[Route][FETCH-PROBE] fetch FAILED seq={} inflight={} url='{}' lastDispose={} cause={}",
                        seq, inflight, snapshot.url(), LAST_DISPOSE.get(), fullMessage(e));
            }
            throw e;
        }
    }

    /** 异常全文（类名 + message + 顶层 cause），探针用。 */
    private static String fullMessage(Throwable t) {
        Throwable root = t;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return t.getClass().getName() + ": " + t.getMessage()
                + (root == t ? "" : " | rootCause=" + root.getClass().getName() + ": " + root.getMessage());
    }

    /**
     * 被拦截请求的快照（值类型）：在事件线程拦截那一刻复制 url/method/headers/body，
     * 从而彻底脱离可能随后被驱动回收的 {@link Request} 句柄。
     */
    public record RequestSnapshot(String url, String method, Map<String, String> headers, String postData) {

        /**
         * 防御性拷贝（SpotBugs EI_EXPOSE_REP2）：headers 是可变 Map，直接持有外部引用即失去"快照"语义
         * （调用方后续改动会反映到已捕获的快照上）。副本不可修改，保证值语义与线程安全。
         */
        public RequestSnapshot {
            headers = Collections.unmodifiableMap(new LinkedHashMap<>(headers == null ? Map.of() : headers));
        }

        /** 同理（SpotBugs EI_EXPOSE_REP）：对外返回副本，不交出内部引用。 */
        @Override
        public Map<String, String> headers() {
            return Collections.unmodifiableMap(new LinkedHashMap<>(headers));
        }

        /** 从 {@link Request} 复制快照；任何异常（含 mock/句柄不可用）返回 {@code null}。 */
        public static RequestSnapshot of(Request request) {
            if (request == null) {
                return null;
            }
            try {
                Map<String, String> headers = request.headers();
                return new RequestSnapshot(request.url(), request.method(),
                        headers == null ? Map.of() : new LinkedHashMap<>(headers), request.postData());
            } catch (RuntimeException e) {
                return null;
            }
        }
    }
}
