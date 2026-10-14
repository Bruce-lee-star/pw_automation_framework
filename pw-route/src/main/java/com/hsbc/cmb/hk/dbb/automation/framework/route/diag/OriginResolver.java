package com.hsbc.cmb.hk.dbb.automation.framework.route.diag;

import com.microsoft.playwright.Frame;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Request;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 请求归属解析器：从 {@link Request} 的<b>本地字段</b>派生 {@link RequestOrigin}。
 *
 * <p><b>线程模型</b>：方法运行于 Playwright 事件线程（route 处理器所在线程），因此必须
 * <b>O(1)、无阻塞、零协议调用</b>。{@code Request.frame()} / {@code Frame.page()} /
 * {@code Frame.parentFrame()} 在 Playwright Java 中都是构造期写入的本地引用，
 * 不产生协议往返；但以下场景它们会为 null 或直接抛异常，必须整体兜住：
 * <ul>
 *   <li>请求来自 Service Worker —— 官方明确：对 {@code request.serviceWorker()} 非空的
 *       Request/Response 调用 {@code frame()} 会抛异常；</li>
 *   <li>导航主文档请求可能先于对应 Frame 创建，此时没有可用 {@code frame()}；</li>
 *   <li>Frame 被 detach 后其 {@code page()} 可能不可用。</li>
 * </ul>
 *
 * <p><b>铁律：归属失败只能降级，绝不能丢观测</b> —— 单条请求的归属异常不得污染整条采集链，
 * 故任何 Throwable 都收敛为 {@link RequestOrigin#DEGRADED}，不影响请求本身被正常记录。
 *
 * <p><b>边界</b>：{@code navSeq} 只在"主 Frame 的主文档导航请求"时前进；iframe 内部导航、
 * XHR/fetch 不改变代次 —— 代次表达的是"页面这次主导航"，不是"任意请求数量"。
 */
public final class OriginResolver {

    /**
     * 重定向链回溯上限。
     *
     * <p>循环重定向（A→B→A）会让 {@code redirectedFrom()} 链无限延长；即便服务端通常会先报
     * {@code ERR_TOO_MANY_REDIRECTS}，本方法也必须自带硬上限 —— 事件线程上不允许任何无界遍历。
     */
    private static final int MAX_REDIRECT_HOPS = 32;

    private final ConcurrentMap<String, AtomicLong> epochsByOwner = new ConcurrentHashMap<>();

    /**
     * 解析归属。
     *
     * @param request 当前被 route 的请求（null 安全）
     * @return 归属三元组；永不返回 null，异常时返回 {@link RequestOrigin#DEGRADED}
     */
    public RequestOrigin resolve(Request request) {
        if (request == null) {
            return RequestOrigin.UNKNOWN;
        }
        try {
            boolean navigation = request.isNavigationRequest();
            Frame frame = request.frame();
            if (frame == null) {
                // 主文档可能先于 Frame 创建；这不是故障，仅表示此刻无法归属
                return RequestOrigin.UNKNOWN;
            }
            Page page = frame.page();
            if (page == null) {
                return RequestOrigin.UNKNOWN;
            }
            String ownerPageId = id("page", page);
            String frameId = id("frame", frame);
            // 主 Frame：无 parentFrame。只有主 Frame 的主文档导航才推进代次。
            boolean mainFrame = frame.parentFrame() == null;
            AtomicLong counter = epochsByOwner.computeIfAbsent(ownerPageId, key -> new AtomicLong());
            long navSeq = counter.get();
            if (navigation && mainFrame) {
                navSeq = counter.incrementAndGet();
            }
            return new RequestOrigin(ownerPageId, frameId, navSeq, false,
                    chainIdOf(request), hopOf(request));
        } catch (Throwable degradation) {
            // Service Worker / detach / 驱动对象已回收：记录降级，但绝不向上抛，更不影响采集
            return RequestOrigin.DEGRADED;
        }
    }

    /**
     * 清空代次表（{@code CaptureSink#reset} 调用）。
     *
     * <p>Page 身份 id 由 {@code identityHashCode} 派生，理论上可能与其他对象重复；
     * 每个 scenario 收尾清空既限制内存增长，也让代次语义重新对齐新的页面集合。
     */
    public void reset() {
        epochsByOwner.clear();
    }

    /** 已跟踪的 Page 数（诊断用）。 */
    public int trackedPages() {
        return epochsByOwner.size();
    }

    private static String id(String prefix, Object target) {
        return prefix + "@" + Integer.toHexString(System.identityHashCode(target));
    }

    /**
     * 链 id：取自<b>链首</b>请求的身份哈希；非重定向请求返回 null。
     *
     * <p>每次服务器重定向都会产生<b>新的 Request</b>，二者由
     * {@code redirectedFrom()}/{@code redirectedTo()} 连接；因此"最终 URL 命中一次"
     * 不能代表整条链 —— 中间 hop 可能是 302、链尾才是 200。用链首身份作 id
     * 可把各跳归到同一条链上，供"链尾状态 + 每跳状态分别断言"。
     */
    private static String chainIdOf(Request request) {
        Request root = rootOf(request);
        return root == null || root == request ? null : id("chain", root);
    }

    /** 本请求在链上的跳序（链首为 0）。 */
    private static int hopOf(Request request) {
        int hop = 0;
        Request cursor = request;
        try {
            while (hop < MAX_REDIRECT_HOPS) {
                Request previous = cursor.redirectedFrom();
                if (previous == null) {
                    break;
                }
                cursor = previous;
                hop++;
            }
        } catch (Throwable t) {
            // redirectedFrom() 在对象已被驱动回收时可能抛：按"能回溯多少算多少"处理，绝不外抛
            return hop;
        }
        return hop;
    }

    /** 回溯到链首；到达上限或异常时返回当前已到达的节点（有界，绝不无限）。 */
    private static Request rootOf(Request request) {
        Request cursor = request;
        try {
            for (int i = 0; i < MAX_REDIRECT_HOPS; i++) {
                Request previous = cursor.redirectedFrom();
                if (previous == null) {
                    break;
                }
                cursor = previous;
            }
        } catch (Throwable t) {
            return cursor;
        }
        return cursor;
    }
}
