package com.hsbc.cmb.hk.dbb.automation.framework.web.page.scan;

import com.microsoft.playwright.Page;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.scan.model.StepRec;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 单次拾取会话的可变工作集（T5-1 优化：消除 followPage / registerPopupFollow /
 * reconcileTrackedPages / runPickerCommand 等方法的 16 参数长签名）。
 *
 * <p>由 {@link RoleElementPicker#openPanel} 一次性构造，汇聚「被跟踪页面 ↔ 页类名」映射、快照、
 * 会话级 URL→类名映射、命令队列、拾取内存态，以及驱动多页面板/导航/关闭协调所需的共享可变状态。
 *
 * <p>线程安全说明（T5-1 扫描包并发加固）：本对象被两条线程共享——
 * ① 面板主循环线程（openPanel while-loop / handleIdle / runPickerCommand）；
 * ② Playwright 事件派发线程（onPage→followPage、onPopup / onClose / onFrameNavigated / onDialog…）。
 * 二者会并发读写以下可变共享态，故做如下加固（行为与原逐参透传完全等价、零变更）：
 * <ul>
 *   <li>pageNames / snapshots 改为 {@link ConcurrentHashMap}，openedPages 改为
 *       {@link CopyOnWriteArrayList}，navigatedPages 改为并发 Set：读端遍历不再抛
 *       ConcurrentModificationException，写端结构修改对各线程立即可见；</li>
 *   <li>current / active / rootClosed 由单元素数组改为 {@link AtomicReference} /
 *       {@link AtomicBoolean}：跨线程写入对他线程立即可见，且单元素读写原子（避免撕裂读）；</li>
 *   <li>javaPickBySig 仍由既有 synchronized(javaPickBySig) 守卫（见 RolePickerPanelController /
 *       RolePickerPanelSync），读端加锁前取快照，锁不延伸到 page.evaluate 等阻塞调用。</li>
 * </ul>
 * urlToClass 在构造后只读，经 final 引用安全发布，无需额外同步。
 */
final class RolePickerContext {

    final AtomicReference<Page> current;
    final AtomicBoolean rootClosed;
    final AtomicBoolean active;
    final String nlsReverseJson;
    final String[] nlsFiles;
    final String packageName;
    final String pageClassName;
    final String stepClassName;
    final ConcurrentHashMap<Page, String> pageNames;
    final ConcurrentHashMap<Page, String> snapshots;
    final LinkedHashMap<String, String> urlToClass;
    final CopyOnWriteArrayList<Page> openedPages;
    final BlockingQueue<RolePickerBridgeRegistry.CmdEvent> cmdQueue;
    final Set<Page> navigatedPages;
    final Object closeSignal;
    final LinkedHashMap<String, RoleEntry> javaPickBySig;
    /**
     * per-session 自愈调度器（daemon 单线程）：承载"按 page 去抖的事件驱动自愈"，
     * 把 iframe 导航 / 拾取回调的突发合并为每页至多一次 {@code selfHealPage}，替代原先每 1s/5s 的整页轮询。
     * 会话结束由 openPanel 的 finally 调 {@code shutdownNow()} 关闭，避免线程泄漏。
     */
    final ScheduledExecutorService selfHealScheduler;
    /** 每页当前在途的自愈任务（去抖用）；页面关闭时移除、任务执行完自移除。 */
    final ConcurrentHashMap<Page, ScheduledFuture<?>> selfHealTasks;
    /**
     * 每页最近一次"主帧导航事件"的处理时间与 URL，用于对【同一 URL 的重复/抖动导航】去抖。
     *
     * <p>重定向链与 SPA 抖动会让同一 URL 连发两次 {@code onFrameNavigated}（现场：{@code onFrameNavigated
     * triggered ×2} 间隔 11ms），整套"恢复 + 激活 + 回灌 + 诊断"被完整跑两遍，翻倍占用 evaluate 与页级锁。
     * 回灌已由【文档起始自举】接管，故合并重复导航是安全的（真正的状态恢复不再依赖该事件）。
     */
    final ConcurrentHashMap<Page, Long> lastNavHandledAt = new ConcurrentHashMap<>();
    final ConcurrentHashMap<Page, String> lastNavHandledUrl = new ConcurrentHashMap<>();

    /**
     * 每页上次自愈（页类刷新 + 跨源 frame 读回 + 面板回灌）完成的时间戳。
     *
     * <p>用途：给 {@code scheduleSelfHeal} 提供【按页最小间隔】节流依据。广告/跟踪 iframe 会频繁导航，
     * 而每个子帧导航都会触发一次自愈；仅靠 250ms 去抖不足以合并这种风暴（现场表现为 iframe 合并 WARN
     * 成片、并把同页的关键 evaluate 挤到队尾）。
     */
    final ConcurrentHashMap<Page, Long> lastSelfHealAt = new ConcurrentHashMap<>();
    /**
     * 已封存的 step（权威来源＝浏览器 {@code __steps}，每次「停止」时缓存一份）。
     * 供「边拾边生成」实时预览构造 {@code steps = 已封存 + 当轮进行中}，使预览与「停止」生成在两个 Tab
     * （步骤代码 / 断言）上完全一致：每轮「开始→停止」一个 step / 一个 assertStep，跨轮累积。
     * 写入为整体替换（volatile 引用），读端直接取引用即得一致快照。
     */
    volatile List<StepRec> sealedSteps = new ArrayList<>();
    /**
     * 当轮（上一次「停止」之后）进行中的拾取：key → RoleEntry（与 javaPickBySig 同键、同对象引用）。
     * 由 {@code __roleOnPick}（binding / console 两通道）在合并进权威态后累加，「停止」时清空；
     * 实时预览据此把当轮呈现为"进行中的第 N+1 个 step"，与浏览器 {@code __currentStep} 同口径。
     * 由 {@code synchronized (roundPicks)} 守卫（写端＝Playwright 回调线程，读端＝面板主循环线程）。
     */
    final LinkedHashMap<String, RoleEntry> roundPicks = new LinkedHashMap<>();

    RolePickerContext(AtomicReference<Page> current, AtomicBoolean rootClosed, AtomicBoolean active,
                      String nlsReverseJson, String[] nlsFiles,
                      String packageName, String pageClassName, String stepClassName,
                      ConcurrentHashMap<Page, String> pageNames,
                      ConcurrentHashMap<Page, String> snapshots,
                      LinkedHashMap<String, String> urlToClass,
                      CopyOnWriteArrayList<Page> openedPages,
                      BlockingQueue<RolePickerBridgeRegistry.CmdEvent> cmdQueue,
                      Set<Page> navigatedPages, Object closeSignal,
                      LinkedHashMap<String, RoleEntry> javaPickBySig) {
        this.current = current;
        this.rootClosed = rootClosed;
        this.active = active;
        this.nlsReverseJson = nlsReverseJson;
        this.nlsFiles = nlsFiles;
        this.packageName = packageName;
        this.pageClassName = pageClassName;
        this.stepClassName = stepClassName;
        this.pageNames = pageNames;
        this.snapshots = snapshots;
        this.urlToClass = urlToClass;
        this.openedPages = openedPages;
        this.cmdQueue = cmdQueue;
        this.navigatedPages = navigatedPages;
        this.closeSignal = closeSignal;
        this.javaPickBySig = javaPickBySig;
        this.selfHealScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "picker-self-heal");
            t.setDaemon(true);
            return t;
        });
        this.selfHealTasks = new ConcurrentHashMap<>();
    }
}
