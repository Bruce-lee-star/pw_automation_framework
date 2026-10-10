package com.hsbc.cmb.hk.dbb.automation.framework.web.page.scan;

import com.microsoft.playwright.Page;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.BlockingQueue;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import static com.hsbc.cmb.hk.dbb.automation.framework.web.page.scan.RoleElementPicker.pickerEval;
import static com.hsbc.cmb.hk.dbb.automation.framework.web.page.scan.RoleElementPicker.pickerEvalSoft;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 页面跟踪引擎：维护「被跟踪页面 ↔ 页类名」映射，并在新页面打开/手动跳转/漏登场景下
 * 补做可被拾取的最小初始化与跟随。从 {@link RoleElementPicker} 抽出，方法体原样迁移，行为零变更。
 *
 * <p>说明：followPage 与仍留在 RoleElementPicker 的 registerPopupFollow 互递归，故本类通过包级可见的
 * RoleElementPicker 静态方法（registerPopupFollow / applyPickState / readPickStateJson / start）回调用；
 * 注入脚本常量统一取自 RolePickerScripts，调用点零字符串拼接。
 */
final class RolePickerPageTracker {

    private static final Logger log = LoggerFactory.getLogger(RolePickerPageTracker.class);

    private RolePickerPageTracker() {}

    /**
     * 开始拾取前，将浏览器当前真实打开的所有页面（context.pages()）与内存跟踪表 pageNames 对齐。
     * 兜底：任何在 stop→再 start 之间、或 onPage/onPopup/followPage 因异常而未登记进 pageNames 的打开页面，
     * 都会被补做最小初始化并纳入跟踪，使该页面在随后的 start 遍历中可被激活拾取，
     * 彻底消除"停止后再点开始，某个已打开页面点了开始却拾取不了"的问题。
     * 已登记页面不重复初始化（幂等）：命令桥/拾取桥用 Map 守卫仅注册一次；面板 addInitScript 仅对漏登页调用一次。
     */
    static void reconcileTrackedPages(RolePickerContext ctx, Page trigger) {
        ConcurrentHashMap<Page, String> pageNames = ctx.pageNames;
        ConcurrentHashMap<Page, String> snapshots = ctx.snapshots;
        LinkedHashMap<String, String> urlToClass = ctx.urlToClass;
        CopyOnWriteArrayList<Page> openedPages = ctx.openedPages;
        BlockingQueue<RolePickerBridgeRegistry.CmdEvent> cmdQueue = ctx.cmdQueue;
        LinkedHashMap<String, RoleEntry> javaPickBySig = ctx.javaPickBySig;
        if (trigger == null || trigger.isClosed()) return;
        for (Page p : trigger.context().pages()) {
            if (p == null || p.isClosed()) continue;
            if (!pageNames.containsKey(p)) {
                ensurePageTracked(ctx, p);
            } else {
                // 【修复"手动跳转后删除跨页误伤 / 再扫描为 0"】
                // 用户可能在面板之外手动导航（如直接改 URL、点原生链接跳转），这类跳转不经过
                // followPage/onPopup 钩子，window.__rolePageName 仍停留在旧页类名，导致新页拾取的元素
                // 被打上旧 pageClass；两个真实不同的页因此共享同一 pageClass，删除时整桶/值级兜底 +
                // 会话级已删集合（STATE_DELETED，N-18 已删除）会把两页当一页一并清除，且已删键永久屏蔽后续扫描。
                // 此处对【每个已登记页】按当前 URL 重新解析 pageClass 并刷新其自身 window.__rolePageName，
                // 确保手动跳转后的页面拿到正确类名（每页写的是"它自己"的类名，而非当前激活页的），
                // 从源头杜绝跨页 pageClass 串味。幂等、仅当解析结果变化时写回。
                try {
                    String curCls = pageNames.get(p);
                    String newCls = RolePickerClassNameResolver.resolvePageClassForUrl(p.url(), pageNames.values(), urlToClass);
                    if (newCls != null && !newCls.equals(curCls)) {
                        pageNames.put(p, newCls);
                    }
                    // 解析为空时【不要】把空值写进浏览器侧：那会把 window.__rolePageName 清空，
                    // 新页拾取的元素随即失去页归属（生成时退化成当前页，表现为"没有生成新页面"）。
                    if (newCls != null && !newCls.isEmpty()) {
                        pickerEval(p, RolePickerScripts.SET_PAGE_NAME_IF_CHANGED_JS, RolePickerScripts.args(RolePickerConstants.STATE_KEY_PAGE_NAME, newCls));
                    }
                } catch (Exception refreshEx) {
                    log.warn("[picker] reconcile failed to refresh the page class name: {}", refreshEx.getMessage());
                }
            }
        }
    }

    /**
     * 主循环每轮对【单个被跟踪页】按它<b>当前 URL</b> 重解析页类名，并幂等写回浏览器侧
     * {@code window.__rolePageName}（{@code SET_PAGE_NAME_IF_CHANGED_JS} 仅在不同时写入）。
     *
     * <p><b>为什么必须每轮做，而不能只靠 onFrameNavigated 的一次性写入</b>：
     * 整页导航会新建文档，而面板 bootstrap 脚本（panel-bootstrap-script.js）在新文档启动时会把
     * {@code window.__rolePageName} 从 localStorage <b>恢复成上一个文档的旧类名</b>。onFrameNavigated
     * 的一次性写入与该 bootstrap 之间存在先后竞态：先写入的会被随后恢复的旧值覆盖。此时新页拾取的元素
     * 会被打上<b>旧页类名</b>，生成时归入旧页类 —— 用户表现即"URL change 后没有生成新的页面"（以及
     * "面板按激活页过滤，看不到之前的元素"）。每轮幂等重写可在 ≤1 轮内自愈该竞态。
     *
     * <p>幂等性：解析结果与内存/浏览器侧一致时不产生任何写入（浏览器侧由 IF_CHANGED 脚本自行判定）；
     * 解析为空时直接返回，绝不把空值写进浏览器侧（否则元素会失去页归属）。解析命中会话级
     * {@code urlToClass} 稳定映射时零派生成本。
     *
     * @param ctx 拾取上下文（提供 pageNames / urlToClass）
     * @param p   待刷新的页面；为 null 或已关闭时静默返回
     */
    /**
     * 页类名漂移记录（Page → 被替换掉的旧类名 / 当前类名），仅供
     * {@link #retagStalePickClass} 在"拾取回传那一刻"纠正抢跑的元素归属。
     *
     * <p>为何需要：History API（pushState）导航不触发 {@code onFrameNavigated}，页类名只能靠
     * {@link #refreshPageClass} 每轮幂等刷新（最快 ≤1s）；用户点击可能正好落在刷新之前
     * （实测：拾取 20:10:20.805 → 刷新 20:10:20.880，相差 75ms），于是新页元素被打上旧页类名，
     * 生成结果里只有旧页类、没有新页类（用户表现即"urlchange 没有生成新的页面"）。
     * 记录"上一类名"后，回传桥即可识别"该 pick 用的是本页刚被替换掉的旧类名"，当场改标。
     */
    private static final ConcurrentHashMap<Page, String> PREV_CLASS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Page, String> CUR_CLASS = new ConcurrentHashMap<>();

    /**
     * 回传瞬间纠正"抢在页类刷新之前"的拾取（由拾取桥调用）。
     *
     * @param srcPage   回传来源页（{@code BindingCallback.Source#page()}）
     * @param pickClass pick 上携带的页类名
     * @return 需要改标时返回新类名；否则 null（保持原样）
     */
    /**
     * 会话级 URL→页类 映射的按 context 视图：供回传桥在"主循环被某次慢 evaluate 卡住"时
     * 仍能即时按当前 URL 解析页类（不依赖 refreshPageClass 是否刚跑过）。
     */
    private static final ConcurrentHashMap<com.microsoft.playwright.BrowserContext, LinkedHashMap<String, String>> CTX_URL_TO_CLASS =
            new ConcurrentHashMap<>();

    /**
     * 会话级「BrowserContext → RolePickerContext」注册表：供 context 级 console 兜底桥在收到浏览器侧
     * URL 变化信号（{@code __roleOnUrlChanged::}，见 gate-init.js 的 pushState/popstate 自愈钩子）时，
     * 拿到本会话的 pageNames/urlToClass/javaPickBySig，即时 {@link #refreshPageClass}。
     * 由 openPanel 在构造 pc 后经 {@link #bindContext} 登记（同一 context 二次 openPanel 覆盖为最新 pc）。
     */
    private static final ConcurrentHashMap<com.microsoft.playwright.BrowserContext, RolePickerContext> CTX_CONTEXT =
            new ConcurrentHashMap<>();

    /** 登记/更新某 BrowserContext 对应的会话上下文（供事件驱动自愈按 ctx 反查 pc）；同时暴露 urlToClass 给回传桥。 */
    static void bindContext(com.microsoft.playwright.BrowserContext ctx, RolePickerContext pc) {
        if (ctx == null || pc == null) return;
        CTX_CONTEXT.put(ctx, pc);
        try { CTX_URL_TO_CLASS.put(ctx, pc.urlToClass); } catch (Exception ignore) { /* 页面/上下文竞态：忽略 */ }
    }

    /** 按 BrowserContext 反查当前会话上下文；未登记返回 null（调用方需判空）。 */
    static RolePickerContext contextFor(com.microsoft.playwright.BrowserContext ctx) {
        return ctx == null ? null : CTX_CONTEXT.get(ctx);
    }

    static String retagStalePickClass(Page srcPage, String pickClass) {
        if (srcPage == null || pickClass == null || pickClass.isEmpty()) return null;
        String prev = PREV_CLASS.get(srcPage);
        String lastKnown = CUR_CLASS.get(srcPage);
        // ① 快速路径：pick 用的正是"本页刚被替换掉的旧类名"（refreshPageClass 记录）。
        boolean staleByRecord = (prev != null && prev.equals(pickClass));
        // ② 兜底路径（必须存在）：主循环可能被一次慢 evaluate 卡住数十秒（实测：auto-generate step 触发的
        //    page.evaluate 超时 30s，把整轮 refreshPageClass/回灌/计数器垫高一起拖住），此时 PREV/CUR 仍是
        //    导航前的旧值 ⇒ 仅靠①会漏判，回传的 pick 就被贴上旧页类（现场：回到 logon 页后拾取的元素全被
        //    标成 SetupSecondPwdPage）。故在此按【当前 URL】即时解析页类；只要它与 pick 的类名不同，
        //    且 pick 的类名正是本页"最后已知类名"，即认定是导航前打的旧标签并改标。
        //    限定"pickClass == 本页已知类名"是为了不误伤跨页搬运来的 pick（它们带的是别的页的类名）。
        boolean staleByUrl = (lastKnown != null && lastKnown.equals(pickClass));
        // ③ 权威兜底（不依赖 refreshPageClass 是否刚跑过）：pushState/hashchange 等 History API 导航不触发
        //    onFrameNavigated，若 refreshPageClass 未及时跑过，PREV/CUR 仍是旧值 ⇒ 仅靠①②会漏判，
        //    新页拾取的元素被打上旧页类（现场："url change 没有创建新页面"，所有元素都在 LogonPage）。
        //    此处直接读本会话 pageNames[srcPage]：只要 pick 的类名正是【本页 Java 侧当前登记的类名】、
        //    而按当前 URL 解析出的类名不同，即认定本页已导航、当场改标。
        //    限定"pickClass == pageNames[srcPage]"同样是为了不误伤跨页搬运来的 pick。
        if (!staleByRecord && !staleByUrl) {
            try {
                RolePickerContext pc = CTX_CONTEXT.get(srcPage.context());
                if (pc != null) {
                    String javaCls = pc.pageNames.get(srcPage);
                    if (javaCls != null && javaCls.equals(pickClass)) staleByUrl = true;
                }
            } catch (Exception ignore) { /* 页面/上下文竞态：忽略 */ }
        }
        if (!staleByRecord && !staleByUrl) return null;
        String cur = lastKnown;
        try {
            LinkedHashMap<String, String> urlToClass = CTX_URL_TO_CLASS.get(srcPage.context());
            if (urlToClass != null) {
                String resolved = RolePickerClassNameResolver.resolvePageClassForUrl(
                        srcPage.url(), urlToClass.values(), urlToClass);
                if (resolved != null && !resolved.isEmpty()) cur = resolved;
            }
        } catch (Exception resolveEx) {
            log.debug("[picker][nav] page class re-resolve at callback time failed: {}", resolveEx.getMessage());
        }
        if (cur == null || cur.isEmpty() || cur.equals(pickClass)) return null;
        return cur;
    }

    /**
     * 取 Java 权威态里已用过的最大动作号。
     *
     * <p>用于在检测到"换了文档"（页类变化）时把新文档的 {@code __rolePickSeq/__roleMaxNo} 垫高到该值，
     * 使整页导航后的新页号接着上一页走，避免"两个页面都出现 1"（跨页撞号会让按号排序的 step 互相穿插）。
     * 只读快照：与写入方（拾取桥/控制台桥）共用同一把 {@code javaPickBySig} 锁。
     */
    private static int maxPickNo(LinkedHashMap<String, RoleEntry> javaPickBySig) {
        if (javaPickBySig == null) return 0;
        int max = 0;
        synchronized (javaPickBySig) {
            for (RoleEntry e : javaPickBySig.values()) {
                if (e == null || e.getPickNos() == null) continue;
                for (Integer n : e.getPickNos()) {
                    if (n != null && n > max) max = n;
                }
            }
        }
        return max;
    }

    static void refreshPageClass(RolePickerContext ctx, Page p) {
        if (p == null || p.isClosed()) return;
        ConcurrentHashMap<Page, String> pageNames = ctx.pageNames;
        LinkedHashMap<String, String> urlToClass = ctx.urlToClass;
        // 暴露会话级 URL→页类 映射给回传桥（供其在"主循环被慢 evaluate 卡住"时按当前 URL 即时解析）。
        // 用 put 而非 putIfAbsent：同一 context 只会有同一个 urlToClass 引用，重复写等价（且避免
        // spotbugs RV_RETURN_VALUE_OF_PUTIFABSENT_IGNORED）。
        try { CTX_URL_TO_CLASS.put(p.context(), urlToClass); } catch (Exception ignore) { /* 页面/上下文竞态：忽略 */ }
        try {
            String curCls = pageNames.get(p);
            String newCls = RolePickerClassNameResolver.resolvePageClassForUrl(p.url(), pageNames.values(), urlToClass);
            if (newCls == null || newCls.isEmpty()) return;
            if (!newCls.equals(curCls)) {
                pageNames.put(p, newCls);
                // 记录漂移：供拾取桥在"回传那一刻"纠正抢在刷新之前拾取的元素归属（见 retagStalePickClass）。
                if (curCls != null && !curCls.isEmpty()) PREV_CLASS.put(p, curCls);
                CUR_CLASS.put(p, newCls);
                // 只清理已关闭页，避免两张 Map 长期持有已关闭 Page 的强引用。
                PREV_CLASS.keySet().removeIf(Page::isClosed);
                CUR_CLASS.keySet().removeIf(Page::isClosed);
                // 只在真的换页类时打一条 INFO：这是"URL 变化是否被正确识别为新页面"的判据日志。
                log.info("[picker][nav] page class refreshed: {} -> {} (url={})", curCls, newCls, p.url());
                // 【修复"URL 变化后面板缺少变化之前的元素 / 两个页面都出现 1"】
                // 页类变化 ⇒ 浏览器侧换了文档（整页导航，或 pushState 后整文档替换）：新文档的
                // __rolePicks / __rolePickSeq / __roleMaxNo 全为零，而回灌有 ETag 判重（Java 权威态没变
                // 就不写）⇒ 新文档永远收不到已拾元素，且新页首次点击又从 1 起号（与上一页撞号）。
                // 实测时间线：20:36:34.702 检测到导航 → 20:36:39.264 用户点击铸出 no=1，中间【无任何回灌】。
                // 故：① 作废该页 ETag ⇒ 下一轮必然全量重写（元素回到面板）；② 立刻用 Java 权威态最大号
                // 垫高新文档计数器 ⇒ 新页号接着上一页（不再两页都出现 1）。
                RolePickerPanelSync.invalidateSync(p);
                int __maxNo = maxPickNo(ctx.javaPickBySig);
                if (__maxNo > 0) {
                    // 同上：短超时（这是"回上一页后序号是否从 1 重启"的关键动作，不能被慢 evaluate 拖住）。
                    pickerEvalSoft(p, RolePickerScripts.SET_SEQ_BASELINE_JS, RolePickerScripts.args("max", __maxNo));
                }
            }
            // 【短超时】页类名写入是主循环每轮动作，且是"回上一页后页类是否正确"的关键；绝不能让它在
            // 导航抖动时占用默认超时把同一轮的其他动作一起拖住（失败下一轮重试，幂等）。
            pickerEvalSoft(p, RolePickerScripts.SET_PAGE_NAME_IF_CHANGED_JS,
                    RolePickerScripts.args(RolePickerConstants.STATE_KEY_PAGE_NAME, newCls));
        } catch (Exception e) {
            // 导航瞬间文档不稳定导致的 evaluate 失败属预期：下一轮会重试（幂等），故只记 debug。
            log.debug("[picker][nav] page class refresh skipped this round: {}", e.getMessage());
        }
    }

    /**
     * 对漏登记页面补做"可被拾取"的最小初始化（不搬运 opener 的当前 step，start 自身会续接/重置）。
     * 与 followPage 的区别：不注册子页跟随（避免重复 onPopup/onClose 监听），仅靠每轮 reconcile
     * 形成闭环——若漏登页再开子页，子页也会在下一轮空闲被补登。
     */
    static void ensurePageTracked(RolePickerContext ctx, Page p) {
        ensurePageTracked(ctx, p, false);
    }

    /**
     * 对漏登记页面补做"可被拾取"的最小初始化（见 {@link #ensurePageTracked(RolePickerContext, Page)}）。
     *
     * <p><b>{@code soft}</b> 开关（对齐 page.pause 的 {@code context.on(Page)} 事件驱动页跟踪）：
     * <ul>
     *   <li>{@code false}（CMD_START 显式补登记）：用满超时 {@code pickerEval}，确保开始拾取前注入成功；</li>
     *   <li>{@code true}（主循环每轮空闲调用）：改用软超时 {@code pickerEvalSoft}，注入失败仅记 warn、
     *       下轮重试，<b>绝不拖慢主循环、也不会引入新的长阻塞</b>（这是把页面跟踪从"点开始才触发"
     *       改为"每轮空闲自愈"的关键，消灭"漏网页直到点开始才出现"的 84s 静默）。</li>
     * </ul>
     */
    static void ensurePageTracked(RolePickerContext ctx, Page p, boolean soft) {
        ConcurrentHashMap<Page, String> pageNames = ctx.pageNames;
        ConcurrentHashMap<Page, String> snapshots = ctx.snapshots;
        LinkedHashMap<String, String> urlToClass = ctx.urlToClass;
        CopyOnWriteArrayList<Page> openedPages = ctx.openedPages;
        BlockingQueue<RolePickerBridgeRegistry.CmdEvent> cmdQueue = ctx.cmdQueue;
        LinkedHashMap<String, RoleEntry> javaPickBySig = ctx.javaPickBySig;
        try {
            // 命令桥/拾取桥/面板重建脚本均已在 context 级一次性注册（registerContextBridges /
            // registerContextInitScripts），本页自动持有，无需逐页补注册。
            // 按 URL 解析并登记本页类名（复用会话稳定映射 urlToClass）
            String cls = RolePickerClassNameResolver.resolvePageClassForUrl(p.url(), pageNames.values(), urlToClass);
            pageNames.put(p, cls);
            if (!openedPages.contains(p)) openedPages.add(p);
            // 暴露页面类名 + 开启面板开关（与 openPanel/followPage 一致）
            if (soft) {
                pickerEvalSoft(p, RolePickerScripts.SET_PAGE_NAME_JS, RolePickerScripts.args(RolePickerConstants.STATE_KEY_PAGE_NAME, cls));
                pickerEvalSoft(p, RolePickerScripts.ENABLE_PANEL_JS + RolePickerScripts.SET_PANEL_FORCE_JS);
                pickerEvalSoft(p, RolePickerScripts.PANEL_SCRIPT);   // 软超时重建当前已加载文档的面板
            } else {
                pickerEval(p, RolePickerScripts.SET_PAGE_NAME_JS, RolePickerScripts.args(RolePickerConstants.STATE_KEY_PAGE_NAME, cls));
                pickerEval(p, RolePickerScripts.ENABLE_PANEL_JS + RolePickerScripts.SET_PANEL_FORCE_JS);
                pickerEval(p, RolePickerScripts.PANEL_SCRIPT);   // 立即重建当前已加载文档的面板
            }
            snapshots.put(p, RoleElementPicker.readPickStateJson(p));
            log.info("[picker][reconcile] back-registered a missed page, now pickable: {} -> {}", p.url(), cls);
        } catch (Exception e) {
            log.warn("[picker][reconcile] failed to back-register the page (will retry on the next idle round): {}", e.getMessage());
        }
    }

    /**
     * 每轮空闲对齐（对齐 page.pause 的 {@code context.on(Page)} 事件驱动页跟踪）：
     * 仅把"浏览器真实打开、但 {@code pageNames} 尚未登记"的页面补登记为可拾取（含面板），
     * <b>不重复刷新已登记页的类名</b>（该职责由 {@code handleIdle} 的 {@code refreshPageClass} 承担，避免双算）。
     * 用软超时：漏登页的注入失败可下轮重试，绝不拖慢主循环、也不会引入新的长阻塞。
     *
     * <p>效果：任何"早于 {@code ctx.onPage} 注册、或 onPage/onPopup/followPage 因异常漏登记"的已打开页面，
     * 在 ≤1 个空闲轮（约 1s）内被补登记并出现面板——正是此前"漏网页直到点开始才出现、中间 84s 静默"的根因修复。</p>
     */
    static void reconcileMissedPages(RolePickerContext ctx, Page trigger) {
        if (trigger == null || trigger.isClosed()) return;
        for (Page p : trigger.context().pages()) {
            if (p == null || p.isClosed()) continue;
            if (!ctx.pageNames.containsKey(p)) {
                ensurePageTracked(ctx, p, true);
            }
        }
    }

    /**
     * 把 inspector 跟随到 newPage 并重建面板（单实例，供 onPopup 与 context.onPage 共用）。
     * 多页面模型：新页面加载当前面板（携带已抓元素），并继续把新页面拾取的元素归属到对应 Page 类；
     * 元素按各页 window.__rolePageName 打 _pageClass 标签，生成时据此分组，实现"打开新页显示之前抓的元素"。
     */
    static void followPage(RolePickerContext ctx, Page opener, Page newPage) {
        AtomicReference<Page> current = ctx.current;
        AtomicBoolean rootClosed = ctx.rootClosed;
        AtomicBoolean active = ctx.active;
        String nlsReverseJson = ctx.nlsReverseJson;
        String[] nlsFiles = ctx.nlsFiles;
        String packageName = ctx.packageName;
        String pageClassName = ctx.pageClassName;
        String stepClassName = ctx.stepClassName;
        ConcurrentHashMap<Page, String> pageNames = ctx.pageNames;
        ConcurrentHashMap<Page, String> snapshots = ctx.snapshots;
        LinkedHashMap<String, String> urlToClass = ctx.urlToClass;
        CopyOnWriteArrayList<Page> openedPages = ctx.openedPages;
        BlockingQueue<RolePickerBridgeRegistry.CmdEvent> cmdQueue = ctx.cmdQueue;
        Set<Page> navigatedPages = ctx.navigatedPages;
        Object closeSignal = ctx.closeSignal;
        LinkedHashMap<String, RoleEntry> javaPickBySig = ctx.javaPickBySig;
        try {
            final boolean sessionActive = active.get();
            // 命令桥/拾取桥已在 context 级一次性注册（registerContextBridges），新页面自动持有绑定，
            // 无需逐页注册；面板按钮点击/拾取回传经 BindingCallback.Source 天然区分来源页面。
            // 多实例：保留原页面（默认页）面板，不关闭、也不停止其拾取态，
            // 使默认页面板在打开新页时不消失；新页面另行注入一个独立面板。
            // 两个页面各自维护自己的面板与 active 状态，互不干扰（不再调用 closePanel/stop(opener)）。
            // 由 URL 解析新页面的 Page 类名（优先复用 urlToClass 稳定映射，同一 URL 复用同一类名），
            // 登记进 pageNames / openedPages，供生成时落到对应类。
            String cls = RolePickerClassNameResolver.resolvePageClassForUrl(newPage.url(), pageNames.values(), urlToClass);
            pageNames.put(newPage, cls);
            openedPages.add(newPage);
            // 【F2：先注册本页监听、后搬运状态】原实现把"本页自己的 onFrameNavigated / onPopup / onDialog /
            // onClose 注册"放在方法末尾。而 popup 触发时该页常立刻从 about:blank 重定向到真实 URL：若注册
            // 发生在重定向之后，本页自己的 onFrameNavigated 会【错过】这次导航 ⇒ 该页再也不会被跨域强制
            // 重注入 / 重建面板，表现为"新页面没有重建 panel"。故把注册提前到状态搬运之前（幂等，末尾不再重复）。
            RoleElementPicker.registerPopupFollow(ctx, newPage, opener);
            // 【关键路径·纯推】即时注入"面板 + 拾取库"，全部软超时：面板内容由 Java 权威态 javaPickBySig
            // 直接 push（与 page.pause() 的"模型→视图 push"一致），不再"读打开器状态"——那正是 10s 阻塞的根源。
            // 新页此刻可能仍是 about:blank：失败【不再】循环 evaluate 干等，而是注册一次性 load 事件，在真实文档
            // 就绪时重跑同一套幂等注入（事件驱动）；本页 onFrameNavigated 与门控 addInitScript 亦为兜底。
            boolean panelReady = injectPanelAndLib(newPage, cls, sessionActive, nlsReverseJson);
            if (!panelReady) {
                final AtomicBoolean loadRetried = new AtomicBoolean(false);
                try {
                    newPage.onLoad(loaded -> {
                        if (!loadRetried.compareAndSet(false, true)) return;
                        injectPanelAndLib(newPage, cls, active.get(), nlsReverseJson);
                    });
                } catch (Exception ignore) { RolePickerQuiet.ignore("RolePickerPageTracker#onLoad", ignore); }
            }
            // 关键修复：打开新页面【不再】把默认页当前步收尾成一个 step。step 的唯一边界是"开始→停止"，
            // 弹窗打开/关闭都只是同一 step 内的交互，绝不该切分出额外 step（用户明确要求"只有一个条件：开始-停止"）。
            // 因此此处只把 opener 的"进行中 step"（__currentStep，已带各元素原 _pageClass）整体搬运到新页继续累积，
            // 并把 opener 的 __currentStep 清空（转移而非复制），避免关闭弹窗合并回来时出现重复元素。
            // 旧页 pick 已带原 _pageClass，新页面板会显示之前抓的元素；新页拾取的元素再打上 cls。
            // 当前页始终持有全部页面的 pick 并集，故代码生成可在单一窗口按各元素自身 _pageClass 归类。
            // 【状态搬运·纯缓存】来源为内存缓存快照 snapshots.get(opener)（由主循环 / onClose 维护），
            // 零浏览器往返——不再用 evaluate 去"拉"打开器状态（那正是弹窗路径 10s 阻塞的根源）。
            // 缺失则跳过：pick 内容已由下方 Java 权威态 push 覆盖，绝不丢元素。
            try {
                String openerState = (opener == null) ? null : snapshots.get(opener);
                if (openerState != null && !openerState.isEmpty()) {
                    RoleElementPicker.applyPickState(newPage, openerState, nlsReverseJson, nlsFiles);
                }
            } catch (Exception applyEx) {
                log.warn("[picker] applyPickState into the followed page failed (continuing with library/panel injection): {}", applyEx.getMessage());
            }
            // 【纯推】用新鲜 Java 权威态回灌新页面板 pick 内容：applyPickState 用的是可能过期的缓存快照，
            // 而 javaPickBySig 是各页 pick 的【并集】（每次拾取都经 __roleOnPick 即时入库），故此处整体覆盖既
            // 不丢元素、又保证新页面板"即时显示全部已拾元素"（steps/currentStep 由上一步 applyPickState 保留）。
            try {
                RolePickerPanelSync.syncPanelToBrowser(newPage, null, javaPickBySig);
            } catch (Exception ignore) { RolePickerQuiet.ignore("RolePickerPageTracker#pushPanel", ignore); }
            // 【健壮性】以下为"非致命初始化"：onPopup 触发时新页文档常仍是 about:blank / 正在跨域重定向，
            // 任一 evaluate 都可能瞬态失败。旧实现把它们放在同一个大 try 里，任一失败都会中断后续
            // 【库注入 / 面板重建 / current 切换】，导致新页在被下一次导航触发前一直拾取不了
            //（现场：`page following failed: pickerEval failed` 后弹窗既无面板也点不动）。
            // 故逐个包 try/catch 做 fail-soft，并一律用【短超时】避免拖住关键路径：单点失败不阻断关键步骤，
            // 随后由本页 onFrameNavigated 与门控 addInitScript 兜底重建。
            try {
                if (opener != null && !opener.isClosed()) {
                    pickerEvalSoft(opener, RolePickerScripts.CLEAR_CURRENT_STEP_JS);
                }
            } catch (Exception ignore) { RolePickerQuiet.ignore("RolePickerPageTracker#clearCurrentStep", ignore); }
            try {
                pickerEvalSoft(newPage, RolePickerScripts.SET_PAGE_NAME_AND_RESET_INSTANCE_JS, RolePickerScripts.args(RolePickerConstants.STATE_KEY_PAGE_NAME, cls));
            } catch (Exception ignore) { RolePickerQuiet.ignore("RolePickerPageTracker#setPageName", ignore); }
            // 【修复"新页面面板不是 active、须重新点开始拾取"】上面的状态搬运（applyPickState）按设计会把
            // __rolePickActive 置回 false（原实现期望随后由 onFrameNavigated 重激活）。但 onFrameNavigated 的
            // 恢复块可能被弹窗/跨域页的慢 evaluate 拖住（实测 >10s），期间新页虽已重建面板却【非激活】——
            // 用户点击无效、必须重新点"开始拾取"。故在此（状态搬运之后）显式恢复激活位：库已由
            // injectPanelAndLib 注入，仅需轻量置位 activation 并渲染（幂等、无副作用），使新页"打开即可拾取"。
            if (sessionActive) {
                try { pickerEvalSoft(newPage, RolePickerScripts.SET_PICK_ACTIVE_AND_RENDER_JS); }
                catch (Exception ignore) { RolePickerQuiet.ignore("RolePickerPageTracker#reactivate", ignore); }
            }
            // 注：面板使能 + PANEL_SCRIPT + 拾取库注入（含"跨源 localStorage 隔离"的开关/__rolePanelForce 兜底）
            // 已上移并收敛到 injectPanelAndLib（幂等软超时 + 一次性 load 兜底），此处不再重复，避免双份逻辑与
            // 带 waitForLoadState（默认 30s）的重试循环再次拖住关键路径。
            current.set(newPage);
            // 记录新页初始快照（含搬运来的并集），供导航重建（onFrameNavigated）与关闭回退（onClose）使用。
            // fail-soft：新页仍在导航时 readPickStateJson 的 evaluate 可能瞬态失败，绝不能让它中断
            // "current 已切换 + 库/面板已注入"的成果（后续 onFrameNavigated / 主循环会补快照）。
            try {
                snapshots.put(newPage, RoleElementPicker.readPickStateJsonSoft(newPage));
            } catch (Exception ignore) { RolePickerQuiet.ignore("RolePickerPageTracker#snapshotNewPage", ignore); }
            // 新页面若再弹窗/再开页，继续跟随：该注册已提前到本方法开头（见 F2 说明），此处不再重复注册，
            // 否则会把 onPopup/onDialog/onClose/onFrameNavigated 挂两遍（同一事件被处理两次）。
            log.info("[picker] injected an independent panel into the new page ({}); the default panel is retained.", cls);
        } catch (Exception e) {
            log.warn("[picker] page following failed: {}", e.getMessage());
        }
    }

    /**
     * 新页「面板 + 拾取库」的幂等软超时注入（纯推，不读任何其它页状态）：
     * 置页类名 → 使能并强制重建面板 → 会话中则重挂拾取库。
     *
     * <p>供 {@link #followPage} 的关键路径与"一次性 load 事件兜底"复用。全部走 {@code pickerEvalSoft}（2s 上限），
     * 绝不因新页正在导航而干等满默认 10s；跨源页 localStorage 往往为空/不可写，故显式置位面板开关 +
     * {@code __rolePanelForce} 兜底。返回 {@code true} 表示面板脚本已成功执行（新页已可见面板）；失败交由本页
     * {@code onFrameNavigated}、门控 {@code addInitScript} 与主循环自愈兜底。
     */
    private static boolean injectPanelAndLib(Page p, String cls, boolean sessionActive, String nlsReverseJson) {
        if (p == null || p.isClosed()) return false;
        boolean ok = false;
        try {
            pickerEvalSoft(p, RolePickerScripts.SET_PAGE_NAME_JS, RolePickerScripts.args(RolePickerConstants.STATE_KEY_PAGE_NAME, cls));
            pickerEvalSoft(p, RolePickerScripts.ENABLE_PANEL_JS + RolePickerScripts.SET_PANEL_FORCE_JS);
            pickerEvalSoft(p, RolePickerScripts.PANEL_SCRIPT);
            ok = true;
        } catch (Exception ignore) { RolePickerQuiet.ignore("RolePickerPageTracker#injectPanel", ignore); }
        if (sessionActive) {
            // 重挂拾取库（幂等）：使该页后续导航由 context 门控注入脚本原生保活；失败不影响面板可见性与后续兜底。
            try { RoleElementPicker.start(p, nlsReverseJson); }
            catch (Exception ignore) { RolePickerQuiet.ignore("RolePickerPageTracker#injectLib", ignore); }
        }
        return ok;
    }
}
