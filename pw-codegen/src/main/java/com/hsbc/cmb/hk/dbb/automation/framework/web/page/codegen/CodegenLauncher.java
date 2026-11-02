package com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen;

import com.hsbc.cmb.hk.dbb.automation.framework.common.route.PageRefs;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen.cdp.CdpLocatorCounter;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen.cdp.CodegenCdpNodeExtractor;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen.cdp.CodegenCdpSession;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen.cdp.ElementRef;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen.codegen.CodegenFieldPlanner;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen.codegen.CodegenFieldSpec;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen.codegen.CodegenTarget;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen.codegen.MultiPageCodegen;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen.engine.CodegenEngine;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen.inject.CodegenScriptInjector;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen.locator.LocatorStrategy;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen.model.CodegenEntry;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen.nls.NlsReverseLookup;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen.panel.CodegenPanelHandle;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen.panel.CodegenPanelManager;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen.panel.PanelResource;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen.recorder.CodegenRecorder;
import com.hsbc.cmb.hk.dbb.automation.framework.web.utils.NLSUtils;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Dialog;
import com.microsoft.playwright.FrameLocator;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.Comparator;
import java.util.function.Consumer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

/**
 * V3 Inspector 入口与会话编排。
 *
 * <p>与 v2 的根本区别：
 * <ul>
 *   <li>面板是 <b>Chrome {@code --app=} 独立窗口</b>（不是被测页 document 内注入、也不是 setContent 注入的
 *       约轻量窗口），OS 可拖拽边缘缩放，内部 flex 响应式填满 ⇒ 解决"固定大小不可拖拽"；</li>
 *   <li>通信走 Playwright 原生 {@code exposeBinding}/{@code evaluate}，不再用脆弱的 setContent + evaluate；</li>
 *   <li>面板列表项点击 ⇒ 经桥命令回到 Java ⇒ 在被测页用 {@code Locator.highlight()} 高亮/滚动目标元素
 *       （类 {@code page.pause()} 的实时交互）⇒ 解决"面板无交互"。</li>
 *   <li>支持 <b>「标记为一组」</b>：勾选多个列表项合成 {@code PageElementList}，
 *       覆盖"不同容器里同 role+name（sigKey 相同、序号互不异）"这种自动分组识别不到的场景。</li>
 * </ul>
 *
 * <p><b>NLS 前置条件</b>：传入 nls 文件时，必须先用 {@link NLSUtils#setLanguage(String)} 告知框架当前语言
 * （nls 文件是 {@code {语言Key: {key: value}}} 两级结构），否则直接拒绝启动并给出明确指引，
 * 避免"反查静默失效、产物退化成一堆 name="。
 *
 * <h2>类名由 URL 派生</h2>
 * <p>调用方<b>不传 {@code pageClassName}</b>：页面类名、步骤类名、断言类名全部由被测页当前 URL 派生
 * （见 {@link CodegenTarget}）。Stop 时重新读一次 URL，因此拾取过程中发生导航也能得到与实际拾取页面
 * 一致的类名。
 *
 * <p>{@link #inspect} 会阻塞调用线程直到用户在面板点 Close。
 */
public final class CodegenLauncher {

    private static final Logger log = LoggerFactory.getLogger(CodegenLauncher.class);

    /**
     * v2.17 代码级日志死开关兜底：不依赖 classpath 实际加载的 logback 配置来源
     * （外部工程自带 logback-test.xml / 旧 pw-core jar 会覆盖配置级门），
     * 本类加载即强制把 codegen 包收敛到 pw.codegen.log 指定级别（默认 WARN）。
     */
    static {
        CodegenLogGate.apply();
    }

    private static final AtomicLong PICK_SEQ = new AtomicLong();
    /**
     * 全局事件序号（拾取 + 原生对话框共用）：用于跨页面推导「切换边界」、dialog 与动作的先后顺序。
     * 与 {@link #PICK_SEQ} 分离，语义是「操作发生顺序」而非「拾取序号」。
     */
    private static final AtomicLong EVENT_SEQ = new AtomicLong();

    private CodegenLauncher() {}

    /**
     * 一次拾取的稳定身份。
     *
     * <p><b>为何需要 {@code id} 而不能只用 {@code sigKey}</b>：sigKey 由 role+name+序号构成，
     * 而"位于不同容器但 role+name 相同"的两个元素会产生<b>完全相同的 sigKey</b>（各自在容器内都是第 0 个）。
     * 它们恰恰是 List 最典型的来源，若用 sigKey 作行标识会互相覆盖、无法分别勾选/高亮，
     * 故每次拾取都分配一个单调递增的唯一 id。
     */
    private static final class Pick {
        final long id;
        /** 全局操作序号（与 dialog 共用 {@code eventSeq}）：用于跨页面推导切换边界、dialog 与动作的先后。 */
        final long seq;
        /**
         * 所属会话序号（一次 Start→Pause 一个会话）：产物里对应一个 {@code stepN()} / {@code assertN()}。
         *
         * <p>同一个会话内可能跨多个页面操作，此时该 step 方法内会实例化多个页面类；
         * 但「一步」的边界永远由会话（Start→Pause）决定，而不是页面。
         */
        final int session;
        /** 可被「标记为一组」重写（改写 sigKey/index），其余字段保持原值以便面板继续正确展示。 */
        volatile CodegenEntry entry;
        /** 该元素所属的被测页（弹窗/新标签页场景下需在其所属页上高亮，而非主页面）。 */
        final Page page;
        /**
         * 拾取发生时的页面 URL（快照）。
         *
         * <p>这是<b>生成粒度从「标签页」改为「URL」</b>的关键数据：同一标签页内发生导航（A→B）时，
         * A 处拾取的元素按 A 的 URL 归到 {@code APage}，B 处拾取的按 B 的 URL 归到 {@code BPage}——
         * 旧设计在 Stop 时重读当前 URL，会把 A 的元素也并入最终 URL 的类，旧类名因此消失。
         * 此处把每一次拾取的 URL 冻结下来，保证两个 Page 类都产出、且字段各归各的 URL。
         */
        final String url;
        /**
         * 该元素所属页的抽取器：role 引擎复核（{@code countRoleMatches}）的唯一入口。
         *
         * <p>复核必须落在<b>元素自己那一页</b>上（弹窗/新标签页的计数入口不同）。
         */
        final CodegenCdpNodeExtractor extractor;
        /**
         * role 定位器尚未经 Playwright 引擎复核（拾取阶段刻意不做的中间态）。
         *
         * <p>生成前必须清零：未复核即生成 ⇒ 可能产出运行期命中不到的 {@code @RoleElement}。
         * 因此 {@code pushGeneratedCode} / Stop 都会先 {@code verifyPendingRoles}。
         */
        volatile boolean rolePending;

        Pick(long id, long seq, int session, CodegenEntry entry, Page page, String url,
              CodegenCdpNodeExtractor extractor, boolean rolePending) {
            this.id = id;
            this.seq = seq;
            this.session = session;
            this.entry = entry;
            this.page = page;
            this.url = url;
            this.extractor = extractor;
            this.rolePending = rolePending;
        }
    }

    /** 一次原生对话框事件（已自动关闭）：记录所属页 / 类型 / accept，供步骤里生成 {@code acceptAlert/dismissAlert}。 */
    private static final class DialogOp {
        final Page page;
        final String type;
        final boolean accept;
        final long seq;
        /** 所属会话序号：dialog 与拾取共用会话边界，落在同一个 stepN 方法里。 */
        final int session;

        DialogOp(Page page, String type, boolean accept, long seq, int session) {
            this.page = page;
            this.type = type;
            this.accept = accept;
            this.seq = seq;
            this.session = session;
        }
    }

    /**
     * 只传被测页：无 NLS ⇒ 一律以 <b>name</b> 定位（产物不含 {@code @RoleFile}、不含 {@code key=}）。
     * 页面/步骤/断言类名全部由当前页面 URL 派生（见 {@link CodegenTarget}），包名取
     * {@link CodegenTarget#DEFAULT_PACKAGE}（不接收包名实参）。
     *
     * <pre>{@code
     *     CodegenLauncher.inspect(page);          // name 定位
     * }</pre>
     *
     * @param inspectedPage 被测页（录制代理会被自动剥离）
     */
    public static void inspect(Page inspectedPage) {
        startSession(inspectedPage, new String[0]);
    }

    /**
     * 一站式入口（对齐 v2 {@code RoleElementPicker.openPanel} 的形态）：只传被测页 + NLS 文件，
     * <b>页面/步骤/断言类名由当前页面 URL 自动派生</b>（同一 URL 多次运行稳定复用），包名取
     * {@link CodegenTarget#DEFAULT_PACKAGE}（不接收包名实参）。
     *
     * <pre>{@code
     *     NLSUtils.setLanguage("zh");                                   // 必须先告知语言
     *     CodegenLauncher.inspect(page, "nls/login.nls.json", "nls/common.nls.json");
     * }</pre>
     *
     * @param inspectedPage 被测页（录制代理会被自动剥离）
     * @param nlsFiles      NLS bundle 路径（<b>可变参数，支持多个</b>；为空则退化为 name 定位）。
     *                      非空时必须已通过 {@link NLSUtils#setLanguage(String)} 设置语言
     */
    public static void inspect(Page inspectedPage, String... nlsFiles) {
        startSession(inspectedPage, nlsFiles);
    }

    /**
     * 会话编排：剥离录制代理 → 校验 NLS 语言前置 → 注入算法 → 开面板 → 阻塞至用户 Close。
     * 包名恒为 {@link CodegenTarget#DEFAULT_PACKAGE}（调用方无需也不应指定），类名由 URL 派生。
     */
    private static void startSession(Page inspectedPage, String[] nlsFiles) {
        requireLanguageWhenNlsProvided(nlsFiles);
        Page real = (Page) PageRefs.unwrap(requirePage(inspectedPage));
        BrowserContext ctx = real.context();
        NlsReverseLookup nls = new NlsReverseLookup(nlsFiles);
        // 当前语言（main 测试线程已设置）。抽取/生成运行在独立工作线程（LanguageState 为 ThreadLocal，线程隔离），
        // 需把语言带过去，否则 NlsReverseLookup.ensureLoaded 会因当前线程未设语言而抛错、导致拾取失败。
        final String lang = NLSUtils.getLanguage();

        // 拾取结果容器（必须在录制事件桥之前声明，lambda 才能捕获）
        List<Pick> picked = new CopyOnWriteArrayList<>();
        // 原生对话框事件容器（与 picked 同生命周期；供生成步骤里的 acceptAlert/dismissAlert）
        List<DialogOp> dialogOps = new CopyOnWriteArrayList<>();
        // 用数组持有者避免 lambda 捕获"正在被赋值的局部变量"导致的明确赋值错误
        final CodegenPanelHandle[] panelRef = { null };
        // 会话结束闩锁：用户关面板窗口（标题栏 ×）或点「停止」即释放，主线程从阻塞中返回。
        // 提前声明，使下方 commandHandler（面板与测试页控制条共用）能引用。
        final CountDownLatch closeLatch = new CountDownLatch(1);
        // 录制动作全局序号（供面板步骤区按真实发生顺序展示）
        final AtomicLong actionSeq = new AtomicLong(0);
        /**
         * 会话序号：面板每点一次 Start 就开启一个新会话（Start→Pause 区间 = 产物里的一步）。
         * 元素与 dialog 都记录当时的会话号，生成时按会话分组：
         * 一个会话 ⇒ 一个 {@code stepN()} + 一个 {@code assertN()}。
         */
        final AtomicInteger sessionSeq = new AtomicInteger(0);
        // 录制器（在 Sessions 之前声明：Sessions 内的拾取回调需用它做增量代码生成，lambda 才能捕获）
        final CodegenRecorder recorder = new CodegenRecorder();
        // 全局单线程串行引擎（必须在 Sessions 之前声明，拾取/生成/注入/关页回调才能捕获）
        CodegenEngine engine = new CodegenEngine(lang);
        // 实时代码生成专用单线程（与拾取引擎分离）：REGEN 实测 300–370ms，若与拾取同线程会推迟下一次拾取解析；
        // 独立后拾取解析永不被生成阻塞。共享态 picked/dialogOps/recorder 均为 CopyOnWriteArrayList，并发安全。
        ExecutorService regenExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "codegen-regen");
            t.setDaemon(true);
            return t;
        });
        // binding 回调计数（事件泵日志里与页面侧点击计数对照，用于区分「DOM 事件没进来」与「binding 未派发」）
        final AtomicLong bridgeCalls = new AtomicLong(0);
        // 页面侧（顶层文档）累计 click 计数，事件泵据此判断注入的监听器是否真的在工作
        final AtomicLong domClicks = new AtomicLong(-1);

        // ---- 多页/弹窗/新标签页跟随：每页一个 CDP 会话 + 抽取器，活动页随用户操作切换 ----
        final class PageSession {
            final Page page;
            private final String nlsLang;
            private final NlsReverseLookup nlsRef;
            private final Object initLock = new Object();
            private volatile CodegenCdpSession session;
            private volatile CdpLocatorCounter counter;
            private volatile CodegenCdpNodeExtractor extractor;
            // 拾取不再有 per-page 线程：所有页的拾取 / 解析任务统一提交到 startSession 作用域的共享单线程引擎
            // CodegenEngine（见 submitPick）；此处仅保留 per-page 的 CDP 会话 / 抽取器数据结构。
            /** CDP 拾取通道是否已开启（幂等标记）。 */
            volatile boolean pickChannelOn = false;

            PageSession(Page page, String nlsLang, NlsReverseLookup nls) {
                this.page = page;
                this.nlsLang = nlsLang;
                this.nlsRef = nls;
            }

            /**
             * 懒加载 CDP 会话 + 抽取器（double-checked locking，线程安全）。
             *
             * <p><b>调用方必须不在 onPage 回调 / 派发线程上</b>：{@code newCDPSession} 是一次真实协议往返，
             * 会阻塞调用线程；故只在共享拾取引擎线程（{@link #submitPick} 包装内，即 engine（CodegenEngine））或显式 Start 命令线程调用，
             * 实现「onPage 回调只登记、会话创建推迟到共享引擎」。
             */
            private CodegenCdpSession cdpSession() {
                if (session == null) {
                    synchronized (initLock) {
                        if (session == null) {
                            CodegenCdpSession s = openCdpSessionWithRetry();
                            CdpLocatorCounter c = new CdpLocatorCounter(page, s);
                            CodegenCdpNodeExtractor ex = new CodegenCdpNodeExtractor(s, nlsRef, c);
                            this.counter = c;
                            this.extractor = ex;
                            this.session = s;
                        }
                    }
                }
                return session;
            }

            /**
             * 建立 CDP 会话，带<b>有界重试</b>。
             *
             * <p>实测竞态：{@code context().newCDPSession(page)} 偶发抛
             * {@code PlaywrightException: Object doesn't exist: cdp-session@…}——驱动已返回会话句柄，
             * 但 Java 侧对象表在解析该句柄前把它回收了（页面正在导航 / 驱动正在重挂 target 时最易触发）。
             * 该失败是瞬时的：用户第二次点 Start 就能成功，说明通道本身可用；但首次失败会在面板上留下
             * 一条 ERROR「failed to start pick mode」，被误读为功能性故障。
             *
             * <p>故在此做有界重试（最多 5 次、每次间隔 100ms，总计 ≤400ms），全部失败仍原样上抛——
             * 绝不静默降级成「拾取中但没有通道」。
             */
            private CodegenCdpSession openCdpSessionWithRetry() {
                com.microsoft.playwright.PlaywrightException last = null;
                for (int attempt = 1; attempt <= 5; attempt++) {
                    try {
                        return new CodegenCdpSession(page, nlsLang);
                    } catch (com.microsoft.playwright.PlaywrightException e) {
                        last = e;
                        log.warn("[codegen][cdp] newCDPSession attempt {}/5 失败 on {}: {} — 100ms 后重试",
                                attempt, page.url(), e.getMessage());
                        try {
                            Thread.sleep(100L);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            throw e;
                        }
                    }
                }
                throw last;
            }

            /** 抽取器（首次访问时随 CDP 会话一并懒加载）。 */
            CodegenCdpNodeExtractor extractor() {
                cdpSession();
                return extractor;
            }

            /** 打开本页的 CDP 拾取通道（幂等）：只启用 CDP 域，<b>不拦截鼠标</b>，页面交互不受影响。 */
            boolean openPickChannel() {
                synchronized (initLock) {
                    if (pickChannelOn) return true;
                    boolean ok = cdpSession().enablePickChannel();
                    pickChannelOn = ok;
                    return ok;
                }
            }

            /** 拾取聚焦：把本页带到前台（浏览器窗口/标签），实现「拾取哪个页面的元素，就聚焦哪个页面」。 */
            void focusPick() {
                try {
                    cdpSession().bringToFront();
                } catch (Throwable t) {
                    log.warn("[codegen][focus] bringToFront on {} failed: {}", page.url(), t.toString());
                }
            }

            /** 面板内容跟随：拾取/切到本页时，通知面板把三栏代码区切到本页对应块（对齐键 = URL 派生的页面类名）。 */
            void focusPanel() {
                try {
                    CodegenPanelHandle h = panelRef[0];
                    if (h == null) return;
                    h.focusPage(CodegenTarget.of(null, page.url()).pageClassName);
                } catch (Throwable t) {
                    log.debug("[codegen][focus] panel content follow failed on {}: {}", page.url(), t.toString());
                }
            }

            /** 同元素并发去重 + 失败冷却（避免重复条目与「每个输入事件重跑一次 CDP」）。 */
            private final PickGuard guard = new PickGuard();

            /**
             * 把解析任务交给共享拾取引擎（CodegenEngine 单线程）。任务前先懒加载 CDP 会话 + 开启通道（均在引擎线程），
             * 实现「onPage 回调只登记、会话创建推迟到共享引擎」—— onPage 回调（派发线程）永不在此同步建会话；
             * 同时把 NLS 语言带到引擎线程（抽取器依赖 ThreadLocal 语言）。
             */
            void submitPick(String fingerprint, Runnable task) {
                try {
                    if (!engine.submit(CodegenEngine.Kind.PICK, page, () -> {
                        // 语言已由 CodegenEngine.submit 在运行任务前统一 setLanguage(nlsLang)，此处无需重复。
                        // 建会话 / 开通道可能抛（新页目标未就绪、页面已关等）：必须在 lambda 内捕获，
                        // 否则异常在守护线程里逃逸，guard 占位永不释放 → 该元素永久卡在「拾取中」且面板无显示。
                        try {
                            cdpSession();
                            openPickChannel();
                            // 拾取哪个页面的元素，就聚焦哪个页面：每次点击解析前把本页带到前台
                            // （多标签/多窗口下用户点击始终落在被拾取页面；fail-soft，失败不阻断），
                            // 并让面板三栏代码区跟随到本页（内容跟随，不移动面板窗口）。
                            focusPick();
                            focusPanel();
                        } catch (Throwable t) {
                            log.warn("[codegen][cdp] pick session/channel init failed on {}: {}",
                                    page.url(), t.getMessage());
                            guard.finish(fingerprint, false);
                            return;
                        }
                        task.run();
                    })) {
                        guard.finish(fingerprint, false);
                    }
                } catch (Exception e) {
                    // 入队失败必须释放占位，否则该元素会被永久当成「正在拾取中」。
                    guard.finish(fingerprint, false);
                }
            }

            /**
             * 解析被点元素。
             *
             * <p><b>优先页面侧选择器（逐 frame 求值）</b>：cssSelector 是相对元素<b>自身文档</b>算出来的，
             * 经 {@link com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen.cdp.CdpFrameLocator}
             * 在每个 frame（含跨源 iframe）的执行上下文里求值即可命中，并顺带返回元素所属 frameId——
             * 这是跨 frame 穿透（{@code frame=SELa :: SELb :: <内层 css>}）的必要输入。
             *
             * <p>元素若在 <b>open shadow root</b> 内，{@code document.querySelector} 穿不透 shadow 边界，
             * 必须由 {@code shadowHosts}（页面侧给出的宿主链，outermost-first）拼成链式表达式求值——
             * 否则必然落到坐标兜底甚至在 shadow + iframe 组合下彻底失败。
             *
             * <p>坐标法只在根 frame 坐标下 hit-test，<b>跨源 iframe 内必然打空</b>（OOPIF 只暴露宿主 iframe），
             * 故仅作选择器不可用/未命中时的兜底。
             *
             * <p>仅限共享引擎线程调用（会话与通道已由 {@link #submitPick} 包装内建好）。
             */
            CodegenEntry pickAt(String type, Object x, Object y, Object cssSelector, List<String> shadowHosts,
                                List<Integer> framePath) {
                cdpSession();   // 防御性保证会话已建（正常由 submitPick 包装已建）
                Double px = asDouble(x);
                Double py = asDouble(y);
                String selector = cssSelector instanceof String ? (String) cssSelector : null;
                ElementRef ref = session.elementBySelector(selector, shadowHosts, framePath);
                if (ref != null) {
                    log.debug("[codegen][cdp] resolved by selector ({}) '{}' shadowHosts={} → frame={}",
                            type, selector, shadowHosts, ref.frameId());
                }
                if (ref == null) {
                    if (selector != null) {
                        // 诊断：页侧 cssSelector 在任意 frame 都查不到（多半是元素在点击与 CDP 探测之间被重渲染替换，
                        // 或 cssSel 口径偏差）。记下选择器与 framePath，便于定位这类动态按钮失配根因；
                        // 随后走坐标兜底——抽取阶段会从命中的节点重新派生稳定选择器（role/name/cssSel），不影响最终产物。
                        log.debug("[codegen][cdp] page selector '{}' (framePath={}) not found in any frame — "
                                + "falling back to coordinates (type={}, x={}, y={})", selector, framePath, type, px, py);
                    }
                    ref = elementAt(px, py);
                    if (ref != null) {
                        log.info("[codegen][cdp] resolved by coordinates ({}) at ({}, {}) → frame={}",
                                type, px, py, ref.frameId());
                    }
                }
                if (ref == null) {
                    // 必须可见：静默失败时用户只看到「点了没反应」，无从判断是坐标偏差、页面已导航还是选择器失配。
                    log.warn("[codegen][cdp] pick failed ({}) at point ({}, {}) on {} (selector='{}', shadowHosts={}) — "
                                    + "所有 frame 内都没能找到该选择器，坐标 hit-test 也未命中："
                                    + "页面可能已导航 / 元素已被移除 / shadow root 为 closed 模式（不可穿透）",
                            type, px, py, page.url(), selector, shadowHosts);
                    return null;
                }
                try {
                    // 直接把定位结果（含 frame 归属）交给抽取：省掉一次 Page.getFrameTree，
                    // 并让子 frame 元素跳过必然失败的页级 role 复核（大页面上该复核 10~90ms）。
                    return extractor.extract(ref);
                } catch (Exception e) {
                    log.warn("[codegen][cdp] extract failed ({}) for backendNodeId={}: {}",
                            type, ref.backendNodeId(), e.getMessage());
                    return null;
                }
            }

            /** 坐标反查（缺坐标即视为不可用）。 */
            private ElementRef elementAt(Double px, Double py) {
                return (px != null && py != null) ? session.elementAt(px, py) : null;
            }

            /** 关闭本页：detach CDP 会话（懒加载可能为 null，即该页从未被拾取）；残留拾取由共享引擎按序排空。 */
            void close() {
                // 无 per-page 线程池可关：拾取任务统一在共享引擎（CodegenEngine）上，页面关闭只需 detach CDP 会话；
                // 队列中该页的残留拾取会在本 close 任务之前按序执行（close 也经引擎入队），失败由任务内 try 捕获。
                CodegenCdpSession s = session;
                if (s != null) {
                    s.disable();
                    s.close();
                }
            }
        }
        final class Sessions {
            /** 录制/拾取事件桥回调：每页经 page.exposeBinding 暴露（主页在 startSession、新页在 followNewPage），
             *  持有同一实例保证语义一致。不用 context 级 exposeBinding——它仅覆盖「注册之后才创建的页」，
             *  主页早已存在故拿不到桥。真正的新页拾取正确性靠注入脚本监听 + 桥 + PICK 链路共同保证，
             *  由事件泵 WARN 诊断丢桥（DOM +1 而 bridge +0）。 */
            com.microsoft.playwright.options.BindingCallback codegenEventBridge;

            final Map<Page, PageSession> map = new ConcurrentHashMap<>();
            final AtomicReference<Page> active = new AtomicReference<>(real);
            /** 活动页 MRU：队首 = 最近活动页。关闭某页时据此回落到「上一个仍在打开的活动页」，
             *  从而正确处理嵌套弹窗（在弹窗上再开弹窗后关掉子弹窗，应回到父弹窗而非直跳主页）。 */
            final java.util.List<Page> recentActive = java.util.Collections.synchronizedList(new java.util.LinkedList<>());
            /** 设置活动页并维护 MRU（供关闭页时回落）。 */
            void setActive(Page p) {
                if (p == null) return;
                active.set(p);
                synchronized (recentActive) {
                    recentActive.remove(p);
                    recentActive.add(0, p);
                }
            }
            /** 是否处于「边操作边拾取」态（面板 Start 开启、Pause 关闭；关闭后仅录制动作，不再拾取）。 */
            final AtomicBoolean pickOn = new AtomicBoolean(false);
            /** 是否已至少开启过一次拾取（用于被测页控制条在 ready/paused 间区分「开始拾取 / 继续拾取」）。 */
            final AtomicBoolean started = new AtomicBoolean(false);
            /** 会话是否已停止（Stop 后定稿）：置位后实时生成任务必须跳过，避免队列里残留的增量任务覆盖定稿代码。 */
            final AtomicBoolean stopped = new AtomicBoolean(false);
            /**
             * 已为哪些页挂过对话框自动关闭监听器（按页去重，避免同一弹窗被处理两遍）。会话结束（closeAll）
             * 或页面关闭（onClose）时经 {@link Page#offDialog} 注销——否则残留监听器会让 Playwright 停用其内置的
             * 自动关闭，污染用户后续使用（弹窗会被挂起而非自动关闭）。
             */
            final Map<Page, Consumer<Dialog>> dialogHandlers = new ConcurrentHashMap<>();

            PageSession of(Page p) {
                return map.computeIfAbsent(p, k -> new PageSession(k, lang, nls));
            }
            /** 开启某页的拾取能力（幂等）；失败返回 false 让面板显示 "start failed"。 */
            boolean enablePick(Page p) {
                return of(p).openPickChannel();
            }
            /**
             * 自动关闭原生对话框（alert/confirm/prompt/beforeunload）：被测页弹窗会冻结 JS 主线程，挂起事件泵的
             * evaluate 与拾取 callFunctionOn，导致高亮/拾取整体冻结（P0 缺口，对标 page.pause 的 dialogManager）。
             * 与 page.pause 一致一律 {@code accept()}：alert 唯一动作即接受；confirm/prompt 接受等价于「确定 / 空串」；
             * beforeunload 接受即放行导航——既不阻塞录制/拾取，也贴合用户默认操作。
             */
            void installDialogAutoDismiss(Page page) {
                if (page == null || dialogHandlers.containsKey(page)) return;
                Consumer<Dialog> h = dialog -> {
                    try {
                        dialog.accept();
                        // 记录该对话框事件（已自动关闭）：步骤里据此生成 acceptAlert()；按页归集、带全局序号。
                        dialogOps.add(new DialogOp(page, dialog.type(), true, EVENT_SEQ.incrementAndGet(),
                                currentSession(sessionSeq)));
                    } catch (Throwable t) {
                        // 同一弹窗可能被 context 级 / 其它监听器先关掉 → "No dialog is showing"，忽略即可。
                        log.debug("[codegen][dialog] dialog already handled / accept failed on {}: {}",
                                page.url(), t.getMessage());
                    }
                };
                page.onDialog(h);
                dialogHandlers.put(page, h);
                log.debug("[codegen][dialog] auto-dismiss installed on {}", page.url());
            }
            /** 注销某页的对话框自动关闭监听器（会话结束 / 页面关闭时调用）。 */
            void removeDialogAutoDismiss(Page page) {
                Consumer<Dialog> h = dialogHandlers.remove(page);
                if (h != null) {
                    try {
                        page.offDialog(h);
                    } catch (Throwable ignore) {
                        log.debug("[codegen][dialog] offDialog failed on {} (page may be closed)", page.url());
                    }
                }
            }
            void followNewPage(Page newPage) {
                if (newPage == null || newPage == real) return;
                // 注入已由 context.addInitScript 覆盖；此处<b>只登记页面 + 挂载关闭钩子</b>，
                // CDP 会话创建推迟到首次拾取（submitPick 内的拾取执行器线程）—— 避免在 onPage 回调
                // （派发线程）上同步建会话阻塞派发节奏。拾取进行中则切换活动页，通道在首次 pick 时开启。
                // 所有新页（含手动标签）都先登记 + 挂关闭钩子（防 map/CDP session 泄漏）+ 对话框自动关闭，
                // 再按 opener 决定是否跟随（见补丁 A）。
                of(newPage);
                newPage.onClose(p -> {
                    removeDialogAutoDismiss(newPage);
                    PageSession removed = map.remove(newPage);
                    // 释放本页 CDP 会话（拾取任务已统一在引擎线程）：页面关掉后若不 close，其 PageSession（含 CDPSession / 抽取器）
                    // 会悬空泄漏；放到 engine，避免在 onClose 派发线程上同步 shutdown/awaitTermination 阻塞派发。
                    if (removed != null) engine.submit(CodegenEngine.Kind.CLOSE, newPage, removed::close);
                    // 从 MRU 移除已关页
                    synchronized (recentActive) { recentActive.remove(newPage); }
                    // 关掉的是当前活动页 ⇒ 回落到「上一个仍在打开的活动页」（嵌套弹窗：关子弹窗 → 回到父弹窗，
                    // 而非直跳主页）；若无可回落的打开页，则回落主页（若仍打开）。
                    if (active.get() == newPage) {
                        Page fallback = null;
                        synchronized (recentActive) {
                            for (Page cand : recentActive) {
                                if (cand != null && !cand.isClosed() && map.containsKey(cand)) { fallback = cand; break; }
                            }
                        }
                        if (fallback == null && !real.isClosed() && map.containsKey(real)) fallback = real;
                        if (fallback != null) {
                            active.set(fallback);
                            // 在 engine 上补开回落页的 CDP 通道（页面已加载，无 attach 竞态），避免阻塞 onClose
                            // 派发线程；失败仅记 WARN（懒加载路径在下次拾取时会兜底）。
                            final Page fb = fallback;
                            if (pickOn.get()) {
                                engine.submit(CodegenEngine.Kind.ENABLE, fb, () -> {
                                    try { enablePick(fb); of(fb).focusPick(); of(fb).focusPanel(); }
                                    catch (Exception e) { log.warn("[codegen][follow] re-enable pick on fallback {} failed: {}", fb.url(), e.toString()); }
                                });
                            }
                        }
                    }
                });
                // 原生对话框自动关闭：覆盖本新页（P0 缺口修复，详见 installDialogAutoDismiss）。
                installDialogAutoDismiss(newPage);
                final Page np = newPage;
                // —— 补丁 A：opener 判定（对齐官方 popup vs openPage 语义）——
                // opener 非 null 且未关闭 ⇒ 业务弹窗（window.open / target=_blank 等），应跟随并开启拾取；
                // opener 为 null ⇒ 用户手动 Ctrl+T 开的独立标签，只登记防泄漏、不抢拾取焦点、不注入/不 enable。
                // opener() 属探针性质，失败按「手动标签」安全降级（不崩 followNewPage，避免整条跟随链路断掉）。
                Page opener = null;
                try {
                    opener = np.opener();
                } catch (Throwable ignore) {
                    log.debug("[codegen][follow] opener() failed on {} (treat as manual tab)", np.url());
                }
                boolean businessPopup = (opener != null && !opener.isClosed());
                if (!businessPopup) {
                    log.debug("[codegen][follow] new page {} opened without opener (manual tab) — register + dialog only, no follow/enable",
                            np.url());
                    return;
                }
                // 业务弹窗：拾取进行中打开立即设为活动页（并在 MRU 置顶）；嵌套弹窗同样正确跟随。
                if (pickOn.get()) {
                    setActive(np);
                }
                // 新标签页/弹窗的「拾取不了元素」根因：新页只靠 context.addInitScript 自动重挂录制脚本，
                // 而 context 级注入在部分打开方式 / Playwright 版本下不一定落到新页，且原实现从未对新页做自检，
                // 一旦失败就是「点了没反应、且无声无息」。这里在后台线程显式补注一次（含 recorder + bridge 自检，
                // 任一缺失会打 WARN），把静默失效变成可见诊断；context 已覆盖则本次幂等补强。
                // 绝不在 onPage 派发线程上调用 page.evaluate（会与该连接的读线程互等死锁），故交给 engine。
                // 注入（快，放 engine 单线程，不阻塞实时生成）
                // 探针同步闩：INJECT（内含 exposeBinding）在 engine 上异步执行，探针必须等它跑完再发 probe；
                // 否则探针会在绑定建立「之前」打出唯一一次 evaluate → 必然判 UNREACHABLE（实测假阳性：
                // 探针 32.25 开火、exposeBinding 32.895 才执行，导致同源 / 跨域新页一律误报）。
                final CountDownLatch injected = new CountDownLatch(1);
                engine.submit(CodegenEngine.Kind.INJECT, np, () -> {
                    try {
                        // 新页（弹窗/新标签页）按页暴露同名绑定：这是新页拿到 window.__codegenEvent 的唯一途径
                        // （主页在 startSession 暴露，其余页在此暴露；context 级 exposeBinding 与 page 级同名互斥、
                        //  且不覆盖会话启动时已存在的主页，故全局统一只用 page 级）。
                        // 若同页被重复暴露会抛 already-registered，捕获忽略（幂等）；若页面已关闭则 warn 不阻断注入。
                        try {
                            np.exposeBinding("__codegenEvent", codegenEventBridge);
                        } catch (Throwable ignore) {
                            log.debug("[codegen][follow] __codegenEvent already exposed on {}", np.url());
                        }
                        CodegenScriptInjector.inject(np);
                        pushPickStatus(np, pickStatusArg(pickOn.get(), started.get(), stopped.get()));
                        of(np).focusPick();
                        of(np).focusPanel();
                        log.debug("[codegen][follow] injected recorder + bridge into new page {}", np.url());
                    } catch (Exception e) {
                        log.warn("[codegen][follow] inject recorder/bridge into new page failed: {} — {}",
                                np.url(), e.getMessage());
                    } finally {
                        injected.countDown();   // 成败都放行探针，避免探针永久挂死
                    }
                });
                // 新页 CDP 目标常在 onPage 触发时尚未 attach，此刻 newCDPSession 会瞬时抛错；故此处<b>不</b>自己开裸线程去
                // waitForLoadState + 轮询（既与现有异步模式不一致，又难管理生命周期）。改为与 onClose 回落页完全一致的写法：
                // 在 engine 上「单次」尝试 enablePick —— 页面通常此时已 attach，多数情况能直接开通道；
                // 若仍撞 attach 竞态失败，则交给每帧点击的 submitPick 懒加载兜底（本就每条点击重试）。拾取进行中（pickOn）才开。
                // —— 补丁 B：ready 门（轻量探测，最多 3×500ms，绝不长占引擎）——
                // 避免「通道开了但页面 DOM 未就绪，点击落到空 DOM 上」的静默；未就绪则交给 submitPick 懒加载兜底。
                if (pickOn.get()) {
                    engine.submit(CodegenEngine.Kind.ENABLE, np, () -> {
                        try {
                            boolean ready = false;
                            for (int i = 0; i < 3 && !np.isClosed(); i++) {
                                try {
                                    Object rs = np.evaluate("() => document.readyState");
                                    if ("complete".equals(rs) || "interactive".equals(rs)) { ready = true; break; }
                                } catch (Exception ignore) { /* 页面未就绪，evaluate 抛属正常 */ }
                                try { Thread.sleep(500); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
                            }
                            if (ready) {
                                enablePick(np);
                                of(np).focusPick();
                                of(np).focusPanel();
                                log.debug("[codegen][follow] enabled pick channel on ready new page {}", np.url());
                            } else {
                                // 1.5s 内没 ready：不硬等 —— 交给 submitPick 懒加载兜底（每条点击重试开通道）
                                log.warn("[codegen][follow] new page {} not DOM-ready within 1.5s — pick enabled lazily on first click",
                                        np.url());
                            }
                        } catch (Exception e) {
                            log.warn("[codegen][follow] enable pick on new page {} deferred (lazy retry on next click): {}",
                                    np.url(), e.getMessage());
                        }
                    });
                }
            // #1 新页拾取绑定可达性探针：inject/enable 后真正调一次 window.__codegenEvent，
            // 看 Java 侧 bridgeCalls 是否自增（bridge 回调无条件自增，probe 类型同样计数）；
            // 不涨则说明该页 binding 未真正接上 Java 回调（静默失效），打点名 URL 的 WARN。
            // 绑定回调跑在 Playwright 派发线程（与引擎无关），故探针用独立 daemon 线程触发 + 轮询：
            // 既不阻塞引擎/派发线程，也不因触发调用可能挂起而卡住主流程（挂起则中断子线程）。
            // ⚠ 必须先等 INJECT 完成（injected 闩）：旧版在提交 INJECT 后立刻开火，与 engine 上的
            //   exposeBinding 竞态 —— evaluate 只发一次且发生在绑定建立之前，导致「同源 / 跨域新页
            //   一律 UNREACHABLE」的假阳性（已实测：探针 32.25 开火、exposeBinding 32.895 才执行）。
            final long probeBaseline = bridgeCalls.get();
            final String probeUrl = np.url();
            Thread probeThread = new Thread(() -> {
                try {
                    if (!injected.await(5, TimeUnit.SECONDS)) {
                        log.debug("[codegen][follow] probe on {} started before INJECT completed (timed out waiting)", probeUrl);
                    }
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
                Thread trigger = new Thread(() -> {
                    try {
                        np.evaluate("try { window.__codegenEvent({type:'probe'}); } catch (e) {}");
                    } catch (Throwable ignore) {
                        // 页面可能已关闭或正在卸载
                    }
                }, "codegen-probe-trigger");
                trigger.setDaemon(true);
                trigger.start();
                boolean reached = false;
                for (int i = 0; i < 12; i++) {
                    if (bridgeCalls.get() > probeBaseline) { reached = true; break; }
                    try { Thread.sleep(50); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
                }
                if (!reached) {
                    trigger.interrupt();
                    log.warn("[codegen][follow] PICK BINDING UNREACHABLE on page {} — this page's "
                            + "window.__codegenEvent did not deliver to Java; clicks here will be silently dropped "
                            + "(see codegen-inject.js note about unestablished binding).", probeUrl);
                }
            }, "codegen-probe");
            probeThread.setDaemon(true);
            probeThread.start();
            }
            void closeAll() {
                // 先注销所有对话框自动关闭监听器（恢复 Playwright 内置自动关闭），再拆 CDP 会话。
                for (Page p : dialogHandlers.keySet()) {
                    removeDialogAutoDismiss(p);
                }
                dialogHandlers.clear();
                for (PageSession ps : map.values()) {
                    ps.close();
                }
                map.clear();
            }
        }
        Sessions sessions = new Sessions();
        // 主页必须先登记进 map：否则 Start 命令遍历 map.keySet() 时 map 为空（主页此前只在首次拾取时
        // 经 sessions.map.get(active) 兜底 of(real) 才入表），导致 "pick channel could not be enabled on any page"。
        // 登记后，Start 的遍历才会覆盖主页；新开页面仍由 followNewPage 登记。
        sessions.of(real);
        sessions.recentActive.add(real);   // 初始活动页（主页）入 MRU，供后续弹窗关闭时回落

        // 一次真实动作 → 面板步骤 + recorder + 实时刷新。拾取/非拾取两条路径共用，
        // 可能跑在 Playwright 驱动线程（未拾取）或某个页的拾取执行器线程（拾取中）。
        //
        // 第二个参数是本动作「刚刚拾取到的元素」（拾取路径才有）：
        // 有它就<b>直接绑定该元素</b>，绝不再按 role/name 反查——页面侧 factsOf 与 CDP 侧 extractor 的
        // role/name/css 计算口径不同（例如链接被算成 generic 容器），反查会漏记，导致
        // 「Elements 有 N 个、Recorded Steps 却只有 M 条」。
        final java.util.function.BiConsumer<Map<String, Object>, CodegenEntry> recordAction = (a, justPicked) -> {
            Object infoObj = a.get("info");
            @SuppressWarnings("unchecked")
            Map<String, Object> info = infoObj instanceof Map ? (Map<String, Object>) infoObj : null;
            String key = (justPicked != null && justPicked.sigKey != null)
                    ? justPicked.sigKey : matchFieldKey(info, picked);
            if (key == null) return;   // 点了未拾取的元素：忽略，不污染产物
            String type = String.valueOf(a.get("type"));
            Object v = a.getOrDefault("value", "");
            String value = v == null ? "" : String.valueOf(v);
            // 只有「首次出现」的字段才会改变 Step/Assert 方法体的先后顺序，此时才重算；
            // 同一个输入框的连续 fill 事件不重复生成，避免录制过程中反复渲染。
            boolean firstSeen = !recorder.orderedFieldKeys().contains(key);
            recorder.record(key, type, value);
            // 推送给面板步骤区，按真实操作顺序展示（click/fill）
            long seq = actionSeq.incrementAndGet();
            String label = justPicked != null ? entryLabel(justPicked) : actionLabel(info);
            if (panelRef[0] != null) {
                panelRef[0].onAction(java.util.Map.of(
                        "seq", seq,
                        "type", type,
                        "label", label,
                        "value", "fill".equals(type) ? value : ""));
            }
            if (firstSeen) {
                requestLiveRegeneration(regenExecutor, panelRef, picked, recorder, dialogOps, nlsFiles, lang, sessions.stopped);
            }
        };

        // 拾取到元素 → Elements 表 + 增量生成（仅在共享引擎线程调用）。
        final java.util.function.BiConsumer<Page, CodegenEntry> addPick = (pg, entry) -> {
            if (entry == null) return;
            PageSession ps = sessions.of(pg);
            Pick pk = new Pick(PICK_SEQ.incrementAndGet(), EVENT_SEQ.incrementAndGet(), currentSession(sessionSeq),
                    entry, pg, safeUrl(pg), ps.extractor(),
                    ps.extractor().requiresDeferredRoleVerification(entry));
            picked.add(pk);
            if (panelRef[0] == null) {
                log.warn("[codegen][cdp] picked #{} but panelRef not ready (panel null)", pk.id);
                return;
            }
            panelRef[0].onPicked(pk.id, entry);
            // 实时增量生成：每次拾取后立即刷新 Page/Step/Assert，无需等到按 Stop 才看到代码。
            requestLiveRegeneration(regenExecutor, panelRef, picked, recorder, dialogOps, nlsFiles, lang, sessions.stopped);
            log.debug("[codegen][cdp] picked #{} name='{}' role='{}' selector='{}'",
                    pk.id, entry.name, entry.role, entry.selector);
        };

        // ===== 命令分发器：面板按钮（经 __codegenOutbox）与 被测页控制条（经 __codegenEvent type=control）
        // 共用同一套 start/pause/stop/... 处理，保证两处控制行为完全一致。 =====
        final java.util.function.Consumer<java.util.List<Object>> commandHandler = cmd -> {
            String command = String.valueOf(cmd.get(0));
            switch (command) {
                case "start":
                    if (panelRef[0] != null) panelRef[0].setStatus("starting");
                    try {
                        requireChromium(sessions.active.get());
                        sessionSeq.incrementAndGet();
                        sessions.pickOn.set(true);
                        sessions.started.set(true);
                        sessions.stopped.set(false);   // 续拾：解除 Stop 的定稿态，恢复实时生成
                        // 自愈合：若 map 因任何原因为空（防御性兜底，正常 Stop/Pause 已不再清 map），
                        // 重新登记当前活动页，避免抛 "could not be enabled on any page"。
                        if (sessions.map.isEmpty()) {
                            Page fallback = sessions.active.get() != null ? sessions.active.get() : real;
                            sessions.of(fallback);
                            sessions.setActive(fallback);
                        }
                        // 对"所有已登记页面"开启 CDP 拾取通道（不只活动页）：
                        // 否则后续弹窗/新标签在未显式 enablePick 前，其 DOM/Accessibility 域未启用，
                        // 选择器反查与坐标 hit-test 双双失败，导致新页拾取静默无显示。
                        boolean anyEnabled = false;
                        for (Page p : sessions.map.keySet()) {
                            if (sessions.enablePick(p)) anyEnabled = true;
                        }
                        if (!anyEnabled) {
                            throw new IllegalStateException("pick channel could not be enabled on any page (see logs)");
                        }
                        // 拾取哪个页面的元素，就聚焦哪个页面：Start 即把当前活动页带到前台，面板内容同步跟随。
                        Page act = sessions.active.get();
                        if (act != null && !act.isClosed()) {
                            sessions.of(act).focusPick();
                            sessions.of(act).focusPanel();
                        }
                        if (panelRef[0] != null) panelRef[0].setStatus("inspecting");
                        broadcastPickStatus(sessions.map, sessions.active.get(), sessions.pickOn.get(), sessions.started.get(), sessions.stopped.get());
                    } catch (Exception e) {
                        sessions.pickOn.set(false);
                        log.error("[codegen] failed to start pick mode", e);
                        if (panelRef[0] != null) {
                            panelRef[0].setStatus("start failed: " + e.getMessage());
                        }
                    }
                    break;
                case "pause":
                    // 暂停只切标记（停止拾取、保留录制）：<b>不</b>调 closeAll() 拆毁会话/线程池，
                    // 否则暂停后点 Start 续拾会因 map 空 / 会话已 detach 而失败（同 Stop 的坑）。
                    sessions.pickOn.set(false);
                    if (panelRef[0] != null) panelRef[0].setStatus("paused");
                    broadcastPickStatus(sessions.map, sessions.active.get(), sessions.pickOn.get(), sessions.started.get(), sessions.stopped.get());
                    log.info("[codegen] pick paused; page interaction kept, recording continues");
                    break;
                case "group":
                    handleGroup(picked, cmd, panelRef[0]);
                    requestLiveRegeneration(regenExecutor, panelRef, picked, recorder, dialogOps, nlsFiles, lang, sessions.stopped);
                    break;
                case "stop": {
                    sessions.pickOn.set(false);
                    // 同一会话内重复 Stop 去重：Stop 后除非经过 Start 续拾（会复位 stopped），否则内容不会变化，
                    // 用户连点 Stop 不应刷出 N 份相同产物。已定稿则只刷新状态条、跳过重生成。
                    if (sessions.stopped.get()) {
                        log.debug("[codegen] stop received but already finalized (no resume since last stop) — skipping re-generation");
                        if (panelRef[0] != null) panelRef[0].setStatus("stopped");
                        broadcastPickStatus(sessions.map, sessions.active.get(), sessions.pickOn.get(), sessions.started.get(), sessions.stopped.get());
                        break;
                    }
                    sessions.stopped.set(true);
                    // 注意：Stop 是「定稿」而非「拆毁会话」——用户仍可再次 Start 继续拾取。
                    // 故这里<b>不</b>调 sessions.closeAll()（它既 detach CDP 会话、shutdown 拾取线程池，又 map.clear()），
                    // 否则 Stop 后点 Start 会因 map 为空而抛 "pick channel could not be enabled on any page"，
                    // 且被关掉的线程池/会话也无法复活。拆毁只在 close / 面板关闭时发生。
                    broadcastPickStatus(sessions.map, sessions.active.get(), sessions.pickOn.get(), sessions.started.get(), sessions.stopped.get());
                    if (panelRef[0] != null) {
                        // 定稿必须准确：skipVerify=false ⇒ 内部先 verifyPendingRoles 再生成（此处不再重复复核）。
                        generateMultiPage(panelRef[0], picked, recorder, dialogOps, nlsFiles, sessions.stopped, /*skipVerify=*/false);
                        panelRef[0].setStatus("stopped");
                    }
                    log.info("[codegen] generated multi-page artifacts ({} picked, {} recorded actions, {} pages)",
                            picked.size(), recorder.snapshot().size(),
                            picked.stream().map(p -> p.page).distinct().count());
                    break;
                }
                case "endSession": {
                    // 2.0：面板「结束会话」按钮 —— 结束会话但<b>面板进程常驻</b>（B 轨道核心语义）。
                    // 未定稿时先定稿（与 stop 一致：verifyPendingRoles + 生成多页产物），再解绑句柄并释放闩锁。
                    // 与旧「关窗=结束会话」的区别：窗口保留、进程保留，下一次 inspect 直接复用（<500ms 级）。
                    sessions.pickOn.set(false);
                    if (!sessions.stopped.get()) {
                        sessions.stopped.set(true);
                        broadcastPickStatus(sessions.map, sessions.active.get(), sessions.pickOn.get(), sessions.started.get(), sessions.stopped.get());
                        if (panelRef[0] != null) {
                            generateMultiPage(panelRef[0], picked, recorder, dialogOps, nlsFiles, sessions.stopped, /*skipVerify=*/false);
                            panelRef[0].setStatus("ended");
                        }
                        log.info("[codegen] finalized on endSession ({} picked, {} recorded actions)",
                                picked.size(), recorder.snapshot().size());
                    } else {
                        if (panelRef[0] != null) panelRef[0].setStatus("ended");
                    }
                    // 解绑会话（进程常驻，不关窗口/浏览器）→ 释放闩锁让主线程返回。
                    if (panelRef[0] != null) {
                        CodegenPanelManager.instance().release(panelRef[0]);
                    }
                    sessions.closeAll();
                    closeLatch.countDown();
                    log.info("[codegen] session ended by panel '结束会话'; panel process stays resident for next inspect");
                    break;
                }
                case "highlight":
                    if (cmd.size() > 1) {
                        String rawId = String.valueOf(cmd.get(1));
                        Long id = parseId(rawId);
                        Pick target = id == null ? null : findPick(picked, id);
                        if (target == null) target = findPickBySig(picked, rawId);
                        highlight(target);
                    }
                    break;
                case "close":
                    // 2.0：结束会话（被测页控制条 close / 兼容路径）：解绑句柄（进程常驻）+ 释放闩锁。
                    sessions.pickOn.set(false);
                    sessions.closeAll();
                    if (panelRef[0] != null) {
                        CodegenPanelManager.instance().release(panelRef[0]);
                    }
                    closeLatch.countDown();
                    break;
                default:
                    log.debug("[codegen] unknown command: {}", command);
            }
        };

        // 录制/拾取事件桥：注入脚本在 DOM 捕获阶段上报真实操作（click/fill）与点击坐标。
        // context 级 ⇒ 所有页面（含弹窗）可用；必须先于注入建立，否则首个事件会被丢弃。
        // 页面交互全程不受影响（不再用 Overlay inspect 拦截鼠标）。
        com.microsoft.playwright.options.BindingCallback bridge = (source, args) -> {
            bridgeCalls.incrementAndGet();
            if (args == null || args.length == 0 || !(args[0] instanceof Map)) return null;
            @SuppressWarnings("unchecked")
            Map<String, Object> payload = (Map<String, Object>) args[0];
            // 被测页控制条（开始/继续/停止）命令：复用与面板完全相同的 commandHandler。
            if ("control".equals(String.valueOf(payload.get("type")))) {
                java.util.List<Object> c = new java.util.ArrayList<>();
                c.add(payload.get("action"));
                commandHandler.accept(c);
                return null;
            }
            // 绑定可达性探针（#1）：跨域弹窗拾取静默失效诊断。只确认 bridge 回调能到达 Java，
            // 不触发任何拾取（不进入下面的 guard / pickAt）。bridgeCalls 已在 785 行自增，探针借此判定。
            if ("probe".equals(String.valueOf(payload.get("type")))) {
                return null;
            }
            Page src = source != null ? source.page() : null;
            // 绝不在这里（binding 回调 / 消息派发线程）创建 PageSession：本回调跑在 Playwright 的消息派发线程上，
            // 在此同步发 newCDPSession 等 CDP 命令不会死锁（runUntil 会自派发响应），但会阻塞派发线程、
            // 拖慢所有事件派发节奏，且嵌套 runUntil 会顺带派发其它排队事件造成重入；故只做查表与活动页跟随，
            // 会话建立推迟到 followNewPage / 拾取执行器线程（见 sessions.of / submitPick）。
            PageSession ps = (src != null) ? sessions.map.get(src) : null;
            if (ps == null) ps = sessions.map.get(sessions.active.get());
            if (ps != null && src != null) {
                sessions.setActive(src);   // 用户在哪页操作，活动页就跟随到哪页（弹窗/新标签页/嵌套弹窗）
            }
            Object infoObj = payload.get("info");
            @SuppressWarnings("unchecked")
            Map<String, Object> info = infoObj instanceof Map ? (Map<String, Object>) infoObj : null;
            // 未拾取 / 该页没有拾取会话 / 该元素已在 Elements 里：只录制，省掉一次 CDP 往返
            // （输入框连续输入会连发 fill 事件，这里必须短路，否则每次按键都要查一遍节点）。
            if (!sessions.pickOn.get() || ps == null || matchFieldKey(info, picked) != null) {
                if (sessions.pickOn.get() && ps == null) {
                    log.warn("[codegen][bridge] no pick session for page {} — 该页不在拾取通道中（非活动页？）",
                            src != null ? src.url() : "null");
                }
                recordAction.accept(payload, null);
                return null;
            }
            final String actionType = String.valueOf(payload.get("type"));
            final String fingerprint = PickGuard.fingerprint(info);
            // 同元素正在拾取中（首次往返未回）或刚失败仍在冷却：动作照常录制，但不再排一次 CDP。
            // 否则一次点击会被排两次（Elements 出现重复条目），失败元素则会被每个输入事件反复重试。
            if (!ps.guard.begin(fingerprint, actionType)) {
                recordAction.accept(payload, null);
                return null;
            }
            if ("click".equals(actionType)) {
                log.debug("[codegen][bridge] click at ({}, {}) on {} (coordSkipped={})",
                        payload.get("x"), payload.get("y"), ps.page.url(), payload.get("coordSkipped"));
            }
            final Page eventPage = ps.page;
            final PageSession pickSession = ps;
            // 元素已在 Elements 里时不会走到这里（上面已短路），故本次一定能拿到新条目；
            // 万一解析失败（e == null），仍按 info 反查已拾取元素兜底，保证动作不丢。
            pickSession.submitPick(fingerprint, () -> {
                CodegenEntry e = null;
                try {
                    // P0-1 新页拾取兜底：极少数情况下（外网页加载慢、注入脚本尚未就绪 / addInitScript 尚未生效）
                    // 页面上 __CODEGEN__ 缺失，录制器与坐标辅助函数不可用 → 拾取静默失效。拾取执行前先自检，
                    // 缺失则同线程补注后再解析（不额外起线程、不阻塞派发线程）。注入脚本首行有幂等保护，重复求值安全。
                    ensureInstrumented(eventPage);
                    e = pickSession.pickAt(actionType, payload.get("x"), payload.get("y"),
                            info != null ? info.get("cssSelector") : null, shadowHostsOf(info),
                            framePathOf(payload));
                    if (e != null) addPick.accept(eventPage, e);
                    recordAction.accept(payload, e);
                } finally {
                    pickSession.guard.finish(fingerprint, e != null);
                }
            });
            return null;
        };
        sessions.codegenEventBridge = bridge;
        // 关键修复（P0-1 根因）：只能用 <b>page 级</b> exposeBinding，不能用 context 级。
        // ① 为什么不能用 context 级：实测 context.exposeBinding 仅作用于「绑定注册之后才创建的页面」，
        //    而会话启动时主页早已存在且已加载 → 仅靠 context 级，主页当前文档永远拿不到 window.__codegenEvent，
        //    点击被注入脚本 catch(e){} 静默吞掉（事件泵 DOM +1 / bridge +0）——即 P0-1 已修掉的老 bug。
        // ② 为什么不能「page 级 + context 级互补」：本 Playwright 版本下两者同名<b>互斥</b>——
        //    page 级先注册后，再调 context.exposeBinding 会抛
        //    "Function \"__codegenEvent\" has been already registered in one of the pages"（已实测）。
        //    故 context 级在此架构下永远不可能生效，不再尝试。
        // ③ 新页（弹窗 / 新标签页）由 followNewPage 对每页调 page 级 exposeBinding 暴露，同源与跨域均覆盖。
        try {
            real.exposeBinding("__codegenEvent", bridge);
        } catch (Throwable t) {
            log.warn("[codegen][bridge] exposeBinding on main page failed: {}", t.getMessage());
        }

        // 注入 role 算法 + recorder：page 级对「当前已加载文档」立即生效（addInitScript 只覆盖后续导航，
        // 登录页早已加载完成，只注册 init script 会让 __CODEGEN__ 根本不存在 → 录制器永不工作）；
        // context 级再用 addInitScript 覆盖后续新建的弹窗 / 新标签页。
        CodegenScriptInjector.inject(real);
        CodegenScriptInjector.inject(ctx);
        // 原生对话框自动关闭：覆盖当前 context 内所有已打开的页（含 real）；后续弹窗 / 新标签页由 followNewPage 补挂。
        // 不处理对话框信号（V2 二期再对齐 page.pause 生成 waitForDialog/accept 片段）—— V1 仅需「不冻结」即可。
        for (Page p : ctx.pages()) {
            sessions.installDialogAutoDismiss(p);
        }

        // ---- 事件泵：驱动 Playwright 的消息派发（独立 daemon 线程，与引擎拾取/生成线程分离）----
        // Playwright Java 的 driver 消息是「有线程阻塞等响应」时顺带派发的；codegen 会话把主线程阻塞在
        // closeLatch 上，期间若没有别的 API 调用，页面发来的 bindingCalled（click/fill）会一直缓冲不被派发，
        // 表现就是「点击页面毫无反应，日志里连 [bridge] 都没有」。旧版 CDP inspect 也是靠同样的泵活着的。
        // 泵同时读取页面侧 click 计数，用于区分「DOM 事件没进来」与「事件进来了但没派发」。
        // 关键：泵必须独立于引擎线程——实测 REGEN 300–370ms、慢拾取 800ms+，若折叠进引擎，这些长任务会饿死泵，
        // 导致弹窗/新标签页上的点击回调不被派发（R1 回归）。独立泵确保点击派发窗口永不被引擎任务阻塞。
        // pumpClicks[0]=上一拍活动页 DOM 点击数，pumpClicks[1]=上一拍全局 binding 回调数；
        // 用于「点击静默丢弃」诊断（区分「DOM 事件没进来」与「事件进来了但没派发/没桥」）。
        final long[] pumpClicks = {0L, 0L};
        // 连续偏差拍数：用于过滤「evaluate 响应 与 bindingCalled 消息」在协议层的排序竞态（一次性偏差），
        // 只有连续两拍仍偏差才判为真正的桥缺失（详见泵内注释）。
        final int[] skewTicks = {0};
        final ScheduledExecutorService eventPump = createEventPump();
        eventPump.scheduleWithFixedDelay(() -> {
            Page p = sessions.active.get();
            if (p == null) return;
            try {
                // 同一次 evaluate 顺手同步「拾取高亮」与「状态指示条」开关：页面导航/新标签页后标志会丢，
                // 靠泵在 120ms 内补回，这样悬停高亮与状态条在整轮拾取期间始终可见（暂停/停止时随之变化）。
                Object v = p.evaluate(
                        "s => { try { if (window.__CODEGEN_SET_HIGHLIGHT__) window.__CODEGEN_SET_HIGHLIGHT__(s.on);"
                                + " if (window.__CODEGEN_SET_STATUS__) window.__CODEGEN_SET_STATUS__(s.status);"
                                + " } catch (e) {} return (window.__CODEGEN_CLICK_COUNT__ || 0); }",
                        pickStatusArg(sessions.pickOn.get(), sessions.started.get(), sessions.stopped.get()));
                long clicks = v instanceof Number ? ((Number) v).longValue() : 0L;
                long bc = bridgeCalls.get();
                if (clicks > pumpClicks[0]) {
                    long deltaDom = clicks - pumpClicks[0];
                    long deltaBridge = bc - pumpClicks[1];
                    // 若本拍 DOM 点击涨了却没有对应的 binding 回调，可能是两类原因：
                    //  ① 协议排序竞态——同一拍的 p.evaluate 响应先到、bindingCalled 消息后到（探针已证明
                    //     exposeBinding 在主页/新页/同页导航后均存在且回调可达），属一次性偏差，下一拍就补上；
                    //  ② 真正的桥缺失——该页每拍都偏差（系统性）。
                    // 故要求「连续两拍」仍偏差才 WARN：既滤掉 ① 的瞬时误报，又不漏 ② 的系统性故障。
                    if (deltaDom - deltaBridge > 0) {
                        if (++skewTicks[0] >= 2) {
                            log.warn("[codegen][pump] active page DOM click dropped (consecutive {} ticks): "
                                    + "DOM +{} but bridge +{} on {} — instrumentation not firing on this page "
                                    + "(click listener or its window.__codegenEvent call is broken).",
                                    skewTicks[0], deltaDom, deltaBridge, p.url());
                            skewTicks[0] = 0;
                        } else {
                            log.debug("[codegen][pump] transient DOM/bridge skew (likely message ordering): "
                                    + "DOM +{} bridge +{} on {} — will re-check next tick", deltaDom, deltaBridge, p.url());
                        }
                    } else {
                        skewTicks[0] = 0;
                    }
                    domClicks.set(clicks);
                    log.debug("[codegen][pump] DOM clicks={} bridge callbacks={}", clicks, bc);
                } else if (clicks < pumpClicks[0]) {
                    // 活动页切换 / 页面导航导致计数器被重置：放弃旧基线，不报丢弃
                    domClicks.set(clicks);
                    skewTicks[0] = 0;
                }
                pumpClicks[0] = clicks;
                pumpClicks[1] = bc;
            } catch (Throwable ignore) {
                // 页面正在导航 / 执行上下文已销毁：本拍失败不影响下一拍
            }
        }, 0, 120, java.util.concurrent.TimeUnit.MILLISECONDS);
        log.info("[codegen][pump] event pump started (120ms, separate thread) — drives Playwright message dispatch");

        Path panelHtml = PanelResource.resolvePanelHtml();

        CodegenPanelHandle panel;
        ScheduledExecutorService closeWatchdog = null;
        try {
            // 2.1：面板进程常驻 —— open 交给 Manager（ensureProcess 复用 READY 进程 / 按 09.6 重建），
            // 返回本会话句柄；浏览器进程、driver、server、命令轮询的生命周期全归 Manager。
            panel = CodegenPanelManager.instance().open(panelHtml, commandHandler, lang);
        } catch (RuntimeException e) {
            // 面板没能打开（例如浏览器启动失败）：收掉事件泵、生成线程与引擎，避免线程泄漏。
            if (eventPump != null) eventPump.shutdownNow();
            regenExecutor.shutdownNow();
            engine.shutdownGracefully();
            throw e;
        }
        panelRef[0] = panel;

        // 2.0：会话结束语义改造 —— 面板「结束会话」按钮是正常结束路径（进程常驻，见 commandHandler
        // case "endSession"）；关窗（标题栏 ×）仍是结束路径，但语义变为「面板进程被终止、常驻失效」：
        // Manager 已把进程置 DEGRADED（下次 open 自动重建），这里只需解绑句柄并释放闩锁。
        // 注意：countDown() 必须放进 finally —— 若 closeAll()/broadcast() 抛异常（页面已随窗口关闭），
        // 不能让"释放主线程"这一步被跳过，否则表现为"关掉窗口程序还卡着"。
        panel.onClose(() -> {
            log.info("[codegen] panel window closed by user — ending codegen session (panel process terminated, resident service degraded)");
            try {
                sessions.pickOn.set(false);
                sessions.stopped.set(true);   // 关窗即结束：被测页上的状态指示条随之隐藏
                broadcastPickStatus(sessions.map, sessions.active.get(), sessions.pickOn.get(), sessions.started.get(), sessions.stopped.get());
                sessions.closeAll();
            } catch (Throwable t) {
                log.warn("[codegen] error during panel close cleanup (ignored): {}", t.getMessage());
            } finally {
                // 进程已死：Manager 已统一置 DEGRADED 并派发本回调；这里只解绑句柄（幂等），不再 close 进程。
                CodegenPanelManager.instance().release(panel);
                closeLatch.countDown();
            }
        });

        // 安全网：极少数情况下 --app= 窗口关闭事件（page.onClose）未被 Playwright 派发，主线程会永久阻塞在
        // closeLatch 上。这里兜底轮询面板是否已关闭，一旦关闭立即释放闩锁，确保"关掉窗口绝不再卡住"。
        closeWatchdog = java.util.concurrent.Executors
                .newSingleThreadScheduledExecutor(r -> {
                    Thread t = new Thread(r, "codegen-close-watchdog");
                    t.setDaemon(true);
                    return t;
                });
        final ScheduledExecutorService wd = closeWatchdog;   // lambda 需捕获 effectively-final 引用
        wd.scheduleWithFixedDelay(() -> {
            try {
                if (panel.isClosed()) {
                    // 兜底路径：onClose 未被派发 ⇒ Manager 未感知进程死亡；主动解绑（幂等）+ 释放闩锁。
                    CodegenPanelManager.instance().release(panel);
                    closeLatch.countDown();
                    wd.shutdownNow();
                }
            } catch (Throwable ignore) { /* 面板页状态读取失败：下一拍再试 */ }
        }, 0, 500, java.util.concurrent.TimeUnit.MILLISECONDS);

        // 跟随弹窗 / 新标签页：新页自动挂载 CDP 会话（注入已由 context.addInitScript 覆盖），inspect 进行中则切换活动页
        ctx.onPage(sessions::followNewPage);

        panel.setStatus("ready");
        log.info("[codegen] V3 Inspector session started; target={}", CodegenTarget.ofPage(real));
        // 交互式会话会在 closeLatch 上阻塞直到用户关闭面板窗口；期间场景线程栈不变属正常，
        // 暂停 HangWatchdog 的卡死判定，避免误报「真卡死」刷屏（也避免硬超时误打断）。
        com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.event.HangWatchdog.suspend();
        try {
            closeLatch.await();
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        } finally {
            com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.event.HangWatchdog.resume();
            if (closeWatchdog != null) { try { closeWatchdog.shutdownNow(); } catch (Exception ignore) { } }
            sessions.closeAll();
            if (eventPump != null) eventPump.shutdownNow();     // 会话结束：事件泵不再打扰被测页
            regenExecutor.shutdownNow();                       // 停掉实时代码生成线程
            engine.shutdownGracefully();   // 会话结束：停掉拾取引擎，避免结束后仍在推送
            // 2.0：进程<b>不再</b>随会话结束被关闭（常驻，见方案文档 B 轨道 / P0-2）。
            // 关窗路径：进程已死，Manager 已置 DEGRADED；endSession 路径：进程存活待复用。
            // 两种路径都无需在此主动 close —— 进程清理归 Manager（watchdog idle / ShutdownCoordinator）。
            // 兜底：异常路径可能残留句柄，release 幂等清扫（不关进程）。
            CodegenPanelManager.instance().release(panel);
        }
        log.info("[codegen] session ended; picked {} elements", picked.size());
    }

    // ------------------------------------------------------------------
    // NLS 前置校验
    // ------------------------------------------------------------------

    /**
     * 传了 nls 文件但没设语言时直接拒绝启动并给出指引：nls 文件按语言分表
     * （{@code { "zh": {...}, "en": {...} }}），不告知语言就无法反查 key，
     * 继续跑只会静默退化成一堆 {@code name=} 产物。
     */
    private static void requireLanguageWhenNlsProvided(String[] nlsFiles) {
        if (nlsFiles == null || nlsFiles.length == 0) return;
        String lang = NLSUtils.getLanguage();
        if (lang == null || lang.isBlank()) {
            throw new IllegalStateException("[codegen] NLS bundles were provided but the current language is not set — "
                    + "call NLSUtils.setLanguage(\"zh\"); before CodegenLauncher.inspect(...). "
                    + "NLS files are keyed by language ({ lang: { key: value } }), so the framework must know "
                    + "which language table to reverse-lookup against.");
        }
        log.info("[codegen] NLS enabled: {} bundle(s), lang='{}'", nlsFiles.length, lang);
    }

    // ------------------------------------------------------------------
    // 「标记为一组」：多次拾取 → 一个 PageElementList
    // ------------------------------------------------------------------

    private static void handleGroup(List<Pick> picked, List<Object> cmd, CodegenPanelHandle panel) {
        List<Pick> members = new ArrayList<>();
        for (int i = 1; i < cmd.size(); i++) {
            Long id = parseId(String.valueOf(cmd.get(i)));
            if (id == null) continue;
            Pick p = findPick(picked, id);
            if (p != null && members.stream().noneMatch(m -> m.id == p.id)) members.add(p);
        }
        if (members.size() < 2) {
            log.warn("[codegen] group skipped: need at least 2 distinct elements, got {}", members.size());
            panel.setStatus("grouped: 需要 ≥2 个元素");
            return;
        }
        List<CodegenEntry> rewritten = groupAsList(members.stream().map(m -> m.entry).collect(Collectors.toList()));
        for (int i = 0; i < members.size(); i++) members.get(i).entry = rewritten.get(i);
        panel.onGrouped(members.stream().map(m -> m.id).collect(Collectors.toList()));
        panel.setStatus("grouped " + members.size());
    }

    /**
     * 把若干条目重写为「同一 base-sig + 互异序号」，使 {@link PageClassGenerator} 按既有分组逻辑
     * 把它们合成<b>一个</b> {@code PageElementList}（运行期 {@code x.get(i)} 取单项）。
     *
     * <p>纯函数（不改动入参、返回重写后的同序列表），便于直接单测；失败前置条件由调用方保证（≥2 个成员）。
     */
    static List<CodegenEntry> groupAsList(List<CodegenEntry> members) {
        if (members == null || members.size() < 2) return members;
        CodegenEntry first = members.get(0);
        String base = first.role + "|" + (first.name == null ? "" : first.name);
        boolean uniform = members.stream().allMatch(e -> Objects.equals(e.role, first.role)
                && Objects.equals(e.name, first.name));
        if (!uniform) {
            log.warn("[codegen] grouped {} elements whose role/name differ — the list locator is derived from "
                    + "the first member (role='{}', name='{}'); confirm it really matches every item.",
                    members.size(), first.role, first.name);
        }
        List<CodegenEntry> out = new ArrayList<>(members.size());
        for (int i = 0; i < members.size(); i++) {
            CodegenEntry e = members.get(i);
            out.add(new CodegenEntry(base + "#" + i, e.role, e.name, e.strategy,
                    e.score, e.selector, e.cssSelector, e.nlsKey, i, e.framePath));
        }
        log.info("[codegen] marked {} picks as one list group (base='{}')", members.size(), base);
        return out;
    }

    // ------------------------------------------------------------------
    // 辅助
    // ------------------------------------------------------------------

    /** 入参校验：被测页缺失要立刻失败，而不是等到 CDP/面板打开后才抛语义不明的错。 */
    private static Page requirePage(Page inspectedPage) {
        if (inspectedPage == null) {
            throw new IllegalArgumentException("[codegen] inspectedPage must not be null");
        }
        return inspectedPage;
    }

    /**
     * 拾取依赖 CDP（{@code BrowserContext.newCDPSession}），<b>仅 Chromium 内核支持</b>。
     * Firefox / WebKit 下 {@code newCDPSession} 直接抛异常；缺这道前置校验时，用户点 Start 只能看到
     * 「面板毫无反应」（异常落进面板命令桥的兜底 catch），故这里显式失败并给出可操作的提示。
     */
    private static void requireChromium(Page page) {
        if (page == null) {
            throw new IllegalStateException("no active page to inspect");
        }
        BrowserContext ctx = page.context();
        com.microsoft.playwright.Browser browser = ctx == null ? null : ctx.browser();
        String name = (browser == null || browser.browserType() == null)
                ? "" : String.valueOf(browser.browserType().name());
        if (!name.toLowerCase(java.util.Locale.ROOT).contains("chromium")) {
            throw new IllegalStateException("element picking uses the CDP protocol and requires a Chromium browser"
                    + (name.isEmpty() ? "" : " (current: " + name + ")")
                    + " — run the inspector against chromium (remove @firefox/@edge overrides for that scenario)");
        }
    }

    private static Pick findPick(List<Pick> picked, long id) {
        for (Pick p : picked) if (p.id == id) return p;
        return null;
    }

    private static Pick findPickBySig(List<Pick> picked, String sigKey) {
        for (Pick p : picked) if (p.entry.sigKey.equals(sigKey)) return p;
        return null;
    }

    private static Long parseId(String s) {
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 点击面板列表项时，在<b>该元素所属页</b>上高亮/滚动对应元素（弹窗/新标签页场景下须用其所属页）。 */
    private static void highlight(Pick target) {
        if (target == null) return;
        String css = target.entry.cssSelector;
        if (css == null || css.isEmpty()) return;
        try {
            Locator loc;
            if (target.entry.framePath.isEmpty()) {
                loc = target.page.locator(css);
            } else {
                // 跨 frame：从主页面逐层 frameLocator 下钻到目标 frame，再定位元素
                FrameLocator fl = target.page.frameLocator(target.entry.framePath.get(0));
                for (int i = 1; i < target.entry.framePath.size(); i++) {
                    fl = fl.frameLocator(target.entry.framePath.get(i));
                }
                loc = fl.locator(css);
            }
            loc.scrollIntoViewIfNeeded();
            try {
                loc.highlight();
            } catch (Exception ignore) {
                // 极旧 API 兜底：scrollIntoViewIfNeeded 已足够提示位置
            }
        } catch (Exception e) {
            log.debug("[codegen] highlight failed for {}: {}", css, e.getMessage());
        }
    }

    /**
     * 把注入脚本回传的 JSON 数值转成 {@code double}（可能是 {@code Integer}/{@code Long}/{@code Double}/{@code String}）。
     * 缺省 / 非数值 / 非法值一律返回 {@code null}，由调用方降级为「本次不拾取」。
     */
    static Double asDouble(Object v) {
        if (v instanceof Number) {
            double d = ((Number) v).doubleValue();
            return (Double.isFinite(d)) ? d : null;
        }
        if (v instanceof String) {
            try {
                double d = Double.parseDouble(((String) v).trim());
                return Double.isFinite(d) ? d : null;
            } catch (NumberFormatException ignore) {
                return null;
            }
        }
        return null;
    }

    /**
     * 从注入脚本回传的元素事实里取 open shadow 宿主链（outermost-first）。
     *
     * <p>缺失 / 非数组一律返回空列表（旧版注入脚本没有该字段时退化为普通查找）。
     * <b>不静默丢弃空项</b>：链上任一层为空即说明事实不可信，原样带下去由
     * {@code CdpFrameLocator} 判定为不可用并回退坐标 hit-test——丢掉中间层会去错误的 root 里找元素。
     */
    static List<String> shadowHostsOf(Map<String, Object> info) {
        if (info == null) return List.of();
        Object raw = info.get("shadowHosts");
        if (!(raw instanceof List<?> items)) return List.of();
        List<String> hosts = new ArrayList<>(items.size());
        for (Object item : items) {
            hosts.add(item == null ? "" : String.valueOf(item));
        }
        return hosts;
    }

    /**
     * 从事件载荷取元素所属 frame 路径（outer→inner 的 iframe 序号）；缺失 / 非数组 / 含非数值项一律返回 null，
     * 由 {@code CdpFrameLocator} 回退全帧扫描（保持跨源 iframe 正确性）。空列表 [] 表示主 frame（合法、可解析）。
     */
    @SuppressWarnings("unchecked")
    static List<Integer> framePathOf(Map<String, Object> payload) {
        if (payload == null) return null;
        Object raw = payload.get("framePath");
        if (!(raw instanceof List<?> items)) return null;
        List<Integer> path = new ArrayList<>(items.size());
        for (Object item : items) {
            if (!(item instanceof Number)) return null;   // 路径不可信 ⇒ 回退全帧扫描
            path.add(((Number) item).intValue());
        }
        return path;
    }

    // ------------------------------------------------------------------
    // 录制动作 → 拾取元素匹配
    // ------------------------------------------------------------------

    /**
     * 把录制事件里的元素事实（role/name/cssSelector）匹配到某个已拾取条目，返回其 sigKey；
     * 找不到匹配（用户点了未拾取区域）返回 {@code null}（该动作被忽略，不污染产物）。
     * 优先按 CSS 选择器精确相等，其次按 role+name 完全相等。
     */
    static String matchFieldKey(Map<String, Object> info, List<Pick> picked) {
        if (info == null) return null;
        String css = str(info, "cssSelector", "");
        String role = str(info, "role", "generic");
        String name = str(info, "name", "");
        for (Pick p : picked) {
            CodegenEntry e = p.entry;
            if (e.cssSelector != null && e.cssSelector.equals(css)) return e.sigKey;
        }
        for (Pick p : picked) {
            CodegenEntry e = p.entry;
            if (Objects.equals(e.role, role) && Objects.equals(e.name, name)) return e.sigKey;
        }
        return null;
    }

    private static String str(Map<String, Object> m, String k, String def) {
        Object v = m.get(k);
        return v == null ? def : String.valueOf(v);
    }

    /** 用已拾取条目构造步骤区标签：优先可访问名，其次 role（与 Elements 表口径一致）。 */
    private static String entryLabel(CodegenEntry e) {
        if (e == null) return "(element)";
        String role = (e.role == null || e.role.isBlank()) ? "(element)" : e.role;
        return (e.name == null || e.name.isBlank()) ? role : role + " \"" + e.name + "\"";
    }

    /** 从录制动作提取每个字段的最终输入值（key=fieldKey=sigKey，最后一次 fill 覆盖），供 Step 类回填真实 fill 值。 */
    private static java.util.Map<String, String> fillValuesFromRecorder(CodegenRecorder recorder) {
        java.util.Map<String, String> m = new java.util.LinkedHashMap<>();
        for (CodegenRecorder.Action a : recorder.snapshot()) {
            if ("fill".equals(a.type) && a.value != null) {
                m.put(a.fieldKey, a.value);   // 最后一次输入为准（input 事件逐键触发，末次即终值）
            }
        }
        return m;
    }

    /** 为面板步骤区构造可读标签：role +（可访问名 / id / 标签）。 */
    private static String actionLabel(Map<String, Object> info) {
        if (info == null) return "(element)";
        String role = str(info, "role", "generic");
        String name = str(info, "name", "");
        String id = str(info, "id", "");
        String tag = str(info, "tag", "");
        if (name != null && !name.isEmpty()) return role + " \"" + name + "\"";
        if (id != null && !id.isEmpty()) return role + " #" + id;
        if (tag != null && !tag.isEmpty()) return role + " <" + tag + ">";
        return role;
    }

    /**
     * 按录制动作序列重排 entries：动作涉及的字段键（首次出现顺序）排在前，其余条目按原顺序附后。
     * 纯函数（不修改入参），便于单测；列表分组（List）依赖完整条目集合，故只排序不裁剪。
     */
    static List<CodegenEntry> orderBySequence(Collection<CodegenEntry> entries, List<String> actionKeys) {
        List<CodegenEntry> out = new ArrayList<>();
        for (String key : actionKeys) {
            for (CodegenEntry e : entries) {
                if (e.sigKey.equals(key) && !out.contains(e)) {
                    out.add(e);
                    break;
                }
            }
        }
        for (CodegenEntry e : entries) {
            if (!out.contains(e)) out.add(e);
        }
        return out;
    }



    /**
     * 事件泵线程：daemon 单线程调度器（<b>独立于引擎线程</b>）。
     *
     * <p>Playwright Java 的 driver 消息在「有线程阻塞等响应」时被顺带派发；codegen 会话把主线程阻塞在
     * {@code closeLatch} 上，若期间没有 API 调用，页面侧的 {@code bindingCalled}（点击/输入）会一直缓冲，
     * 表现为「点击完全没反应、日志里连 bridge 回调都没有」。泵用极轻量的 {@code evaluate} 持续驱动派发。
     * 必须独立于引擎线程：长拾取（~170ms，慢拾取 800ms+）与实时代码生成（实测 300–370ms）占住引擎时，
     * 折叠进引擎的泵会被饿死 → 点击回调不被派发（R1 回归）。独立泵保住点击派发窗口。
     */
    private static ScheduledExecutorService createEventPump() {
        return java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "codegen-event-pump");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * 请求一次实时刷新（异步，合并执行）。停止后不再提交，避免覆盖定稿代码。
     *
     * <p><b>合并而非排队</b>：连续快速拾取（一次点击/输入即一次请求）若逐个提交全量代码重生，会在
     * {@code CodegenEngine} 单线程里堆积 O(n²) 次生成，挤占注入/状态条推送，录制量大时明显卡顿。
     * 这里保证「同一时刻至多一个再生任务在跑」：期间到达的新请求只更新序号、不重复入队；
     * 在跑的任务生成一次后若发现序号又变（生成期间又来了新拾取），再补一次以覆盖最新状态，
     * 既限频又不丢最后一次结果。
     */
    /**
     * P0-1 新页拾取兜底：自检页面是否已注入 {@code codegen-inject.js}（{@code window.__CODEGEN__} 命名空间 +
     * recorder 监听器），缺失则同线程补注后再继续拾取。仅在拾取执行线程（引擎线程）调用——协议往返本就归属此线程，
     * 不阻塞派发线程。注入脚本首行带幂等保护（{@code if (window.__CODEGEN__) return;}），重复求值安全。
     *
     * <p>覆盖场景：外网页加载慢、{@code addInitScript} 尚未在新建弹窗/新标签页上生效前，用户已在上面点击，
     * 此时录制器/坐标辅助函数不可用，若不补注则拾取静默失效。注意：本方法只能挽救「监听已挂但辅助函数缺失」
     * 的情形；若点击发生在监听挂载之前（极窄窗口），该次点击已丢失，本方法为后续点击兜底。
     */
    private static void ensureInstrumented(Page page) {
        try {
            Object ok = page.evaluate("() => !!(window.__CODEGEN__ && window.__CODEGEN__.recorder)");
            if (!Boolean.TRUE.equals(ok)) {
                log.warn("[codegen][bridge] instrumentation missing on {} — lazy re-injecting codegen-inject.js",
                        page.url());
                CodegenScriptInjector.inject(page);
            }
        } catch (Throwable ignore) {
            // 页面正在导航 / 执行上下文已销毁：本拍重试时再补，不在此报错
        }
    }

    private static final AtomicLong REGEN_SEQ = new AtomicLong();
    private static final AtomicBoolean REGEN_INFLIGHT = new AtomicBoolean(false);
    // 【regen 明细日志节流】高频拾取/合并时 regen 每 80–200ms 一轮，逐条 INFO 会刷爆 IDEA 控制台
    //（数千行/分钟），拖卡 IDE UI、令 Stop 按钮失去响应。规则：慢轮（total > 500ms）保留完整 INFO
    //（推包/装配瓶颈逐轮可见）；正常轮与后台 verify+repush 入桶，每累计 25 轮或距上次汇总 ≥8s
    // 输出一条合并 INFO，既降频又保留「regen 是否在持续跑」的诊断能力。
    private static final AtomicInteger REGEN_LOG_CNT = new AtomicInteger();
    private static final AtomicLong REGEN_LOG_SUM_MS = new AtomicLong();
    private static final AtomicLong REGEN_LOG_LAST_AT = new AtomicLong(System.currentTimeMillis());

    private static void regenLogBucket(int ms) {
        int n = REGEN_LOG_CNT.incrementAndGet();
        REGEN_LOG_SUM_MS.addAndGet(ms);
        long now = System.currentTimeMillis();
        long last = REGEN_LOG_LAST_AT.get();
        if (n >= 25 || now - last >= 8000) {
            int cnt = REGEN_LOG_CNT.getAndSet(0);
            long sumMs = REGEN_LOG_SUM_MS.getAndSet(0);
            long spanMs = Math.max(1, now - last);
            log.info("[codegen][regen] {} regen round(s) merged: avg {}ms in {}ms window "
                            + "(per-round detail only on slow >{}ms rounds)",
                    cnt, sumMs / Math.max(1, cnt), spanMs, 500L);
        }
    }
    private static void requestLiveRegeneration(ExecutorService regenExecutor, CodegenPanelHandle[] panelRef,
                                                List<Pick> picks, CodegenRecorder recorder,
                                                List<DialogOp> dialogOps, String[] nlsFiles,
                                                String nlsLang, AtomicBoolean stopped) {
        if (regenExecutor == null || stopped.get()) {
            return;
        }
        REGEN_SEQ.incrementAndGet();                       // 标记“有新请求”
        if (!REGEN_INFLIGHT.compareAndSet(false, true)) {
            return;                                        // 已有合并任务在跑/排队，本次由它兜底
        }
        try {
            regenExecutor.execute(() -> {
                try {
                    if (nlsLang != null) {
                        com.hsbc.cmb.hk.dbb.automation.framework.common.context.LanguageState
                                .setLanguage(nlsLang);
                    }
                    long started = REGEN_SEQ.get();
                    boolean anyPending = picks.stream().anyMatch(p -> p.rolePending);
                    // 首推：跳过全量复核（verify 是 CDP 往返、首推瓶颈），先把代码推上面板（快，目标 <500ms）。
                    long tFirst = System.nanoTime();
                    generateMultiPage(panelRef[0], picks, recorder, dialogOps, nlsFiles, stopped, /*skipVerify=*/true);
                    long firstMs = (System.nanoTime() - tFirst) / 1_000_000;
                    if (firstMs > 500) {
                        log.warn("[codegen][regen] first push (skipVerify) took {} ms (>500ms) — "
                                + "push/assemble bottleneck, not verify", firstMs);
                    } else {
                        log.debug("[codegen][regen] first push (skipVerify) took {} ms", firstMs);
                    }
                    // 生成期间若又有新拾取（序号变化），合并覆盖最新（仍走快首推）。
                    if (REGEN_SEQ.get() != started) {
                        long tMerge = System.nanoTime();
                        generateMultiPage(panelRef[0], picks, recorder, dialogOps, nlsFiles, stopped, /*skipVerify=*/true);
                        log.debug("[codegen][regen] merged re-push took {} ms", (System.nanoTime() - tMerge) / 1_000_000);
                    }
                    // 首推完成后，若有未复核 role，后台补复核 + 重推修订（不阻塞首推；verify 是 CDP 往返，
                    // 落在首推路径之外，用户先把代码看到、再被修订覆盖）。Stop 定稿走 skipVerify=false 已含复核。
                    if (anyPending && !stopped.get()) {
                        long tBack = System.nanoTime();
                        generateMultiPage(panelRef[0], picks, recorder, dialogOps, nlsFiles, stopped, /*skipVerify=*/false);
                        // 后台 verify+repush 并入明细桶：高频时不再逐条 INFO（汇总条里已含耗时），
                        // 慢轮在 generateMultiPage 内以 (slow) INFO 单独呈现。
                        regenLogBucket((int) ((System.nanoTime() - tBack) / 1_000_000));
                    }
                } catch (Throwable t) {
                    log.warn("[codegen][regen] regeneration failed: {}", t.toString());
                } finally {
                    REGEN_INFLIGHT.set(false);
                }
            });
        } catch (RuntimeException re) {   // shutdown 竞态下的 RejectedExecutionException
            // 入队被拒绝：任务体不执行，finally 不触发，必须在此释放占位，否则 REGEN_INFLIGHT 永久为 true。
            REGEN_INFLIGHT.set(false);
        }
    }

    /** 当前拾取状态（用于被测页控制条）：stopped ⇒ hidden，pickOn ⇒ picking，已开启过 ⇒ paused，否则 ready。 */
    private static java.util.Map<String, Object> pickStatusArg(boolean pickOn, boolean started, boolean stopped) {
        String status = stopped ? "hidden" : (pickOn ? "picking" : (started ? "paused" : "ready"));
        return java.util.Map.of("on", pickOn, "status", status);
    }

    /** 向所有已登记页 + 活动页广播拾取控制条（就绪 / 拾取中 / 已暂停 / 隐藏），覆盖导航 / 新页恢复。 */
    private static void broadcastPickStatus(java.util.Map<Page, ?> pages, Page active,
                                           boolean pickOn, boolean started, boolean stopped) {
        java.util.Map<String, Object> arg = pickStatusArg(pickOn, started, stopped);
        for (Page p : pages.keySet()) pushPickStatus(p, arg);
        if (active != null) pushPickStatus(active, arg);
    }

    /** 单页同步高亮 + 状态指示条（页面可能已关闭，失败静默忽略）。 */
    private static void pushPickStatus(Page page, java.util.Map<String, Object> arg) {
        try {
            page.evaluate("s => { try {"
                    + " if (window.__CODEGEN_SET_HIGHLIGHT__) window.__CODEGEN_SET_HIGHLIGHT__(s.on);"
                    + " if (window.__CODEGEN_SET_STATUS__) window.__CODEGEN_SET_STATUS__(s.status);"
                    + " } catch (e) {} }", arg);
        } catch (Throwable ignore) {
            // 页面可能已关闭或正在卸载
        }
    }

    /**
     * 多页面 / frame / dialog 生成入口：按页面分组各自成 Page/Step/Assert 三件套，外加一个串联各页与
     * 页面切换的「场景流程」Step 类，三份产物分别推到 page/step/assert 三个 Tab（同类产物跨页拼接）。
     * 三个 Tab 各自独立兜错：某个生成器出问题不能再连累其它 Tab。
     */
    /**
     * 多页面 / frame / dialog 生成入口：按页面分组各自成 Page/Step/Assert 三件套，外加一个串联各页与
     * 页面切换的「场景流程」Step 类，三份产物分别推到 page/step/assert 三个 Tab（同类产物跨页拼接）。
     * 三个 Tab 各自独立兜错：某个生成器出问题不能再连累其它 Tab。
     *
     * @param skipVerify 跳过「生成前全量复核」（{@code verifyPendingRoles} 是 CDP 往返，首推瓶颈）。
     *                   {@code true} 用于实时增量生成的<b>首推</b>：先把代码推上面板（快，首推 &lt;500ms），
     *                   未复核 role 条目暂以拾取时策略产出；随后由 {@code requestLiveRegeneration} 的后台补推
     *                   （{@code skipVerify=false}）复核并修订。{@code false} 用于 Stop 定稿（必须准确）。
     */
    private static void generateMultiPage(CodegenPanelHandle panel, List<Pick> picks, CodegenRecorder recorder,
                                         List<DialogOp> dialogOps, String[] nlsFiles, AtomicBoolean stopped,
                                         boolean skipVerify) {
        if (panel == null || stopped.get() || picks.isEmpty()) {
            return;
        }
        long tVerify = 0L, tRender = 0L, tPush = 0L;
        if (!skipVerify) {
            // 生成前必须把未复核的 role 条目复核掉，产物才能与"拾取时同步复核"逐字一致。
            long tv = System.nanoTime();
            verifyPendingRoles(panel, picks);
            tVerify = (System.nanoTime() - tv) / 1_000_000;
        }
        MultiPageCodegen.MultiPageResult result;
        long tr = System.nanoTime();
        try {
            result = assembleMultiPage(picks, recorder, dialogOps, nlsFiles);
        } catch (Exception e) {
            log.warn("[codegen] live regeneration skipped (generation failed): {}", e.toString());
            return;
        }
        tRender = (System.nanoTime() - tr) / 1_000_000;
        long tp = System.nanoTime();
        pushBlocks(panel, "page", result.pageBlocks);
        pushBlocks(panel, "step", result.stepBlocks);
        pushBlocks(panel, "assert", result.assertBlocks);
        tPush = (System.nanoTime() - tp) / 1_000_000;
        long regenTotal = tVerify + tRender + tPush;
        if (regenTotal > 500) {
            // 慢轮（verify/render/push 任一瓶颈，>500ms 首推警戒线）：保留完整明细 INFO，逐轮可见。
            log.info("[codegen][regen] generated (skipVerify={}): verify={}ms render={}ms push={}ms total={}ms (slow)",
                    skipVerify, tVerify, tRender, tPush, regenTotal);
        } else {
            // 正常轮：并入汇总桶，避免高频 INFO 刷爆 IDEA 控制台（见 regenLogBucket）。
            regenLogBucket((int) regenTotal);
        }
    }

    /**
     * 组装产物：<b>Page 按页面归集字段</b>，<b>Step / Assert 按会话（一次 Start→Pause）出方法</b>。
     *
     * <h3>一步 = 一次 Start→Pause</h3>
     * 会话内按 {@link Pick#seq} 排序即该步真实操作顺序。同一会话内操作了多个页面时，该 step 方法体
     * 会同时实例化多个页面对象（元素字段仍各归各的 Page 类）。
     *
     * <h3>切页触发器</h3>
     * 会话内一旦活动页变化，就把「变化前在上一页的最后一个操作」升级为
     * {@code from.waitForNewPage(() -> from.trigger.action)} —— 触发元素不会在来源页被重复点击。
     */
    private static MultiPageCodegen.MultiPageResult assembleMultiPage(List<Pick> picks, CodegenRecorder recorder,
                                                                    List<DialogOp> dialogOps, String[] nlsFiles) {
        // 1) 每个「标签页 + 拾取 URL」唯一目标（URL 派生，碰撞则追加 _2 / _3）。
        //    同一个标签页内发生导航（A→B）会产生两个不同的键，因此 A、B 各自产出独立的 Page 类。
        Map<PageUrl, List<Pick>> byKey = new LinkedHashMap<>();
        for (Pick p : picks) byKey.computeIfAbsent(new PageUrl(p.page, p.url), k -> new ArrayList<>()).add(p);
        Map<PageUrl, CodegenTarget> targets = resolveTargets(byKey.keySet());

        // 2) 每个键按真实操作序列排序的 entries（字段命名基准，跨会话合并）
        List<String> globalKeys = recorder.orderedFieldKeys();
        Map<PageUrl, List<CodegenEntry>> ordered = new LinkedHashMap<>();
        for (Map.Entry<PageUrl, List<Pick>> en : byKey.entrySet()) {
            List<CodegenEntry> es = en.getValue().stream().map(p -> p.entry).collect(Collectors.toList());
            List<String> keys = globalKeys.stream()
                    .filter(k -> es.stream().anyMatch(e -> e.sigKey.equals(k))).collect(Collectors.toList());
            ordered.put(en.getKey(), orderBySequence(es, keys));
        }

        // 3) 每个键规划（字段名 / 动作 / fill 占位）：Page、Step、Assert 共用同一份，字段名严格一致
        Map<String, String> fillValues = fillValuesFromRecorder(recorder);
        Map<PageUrl, CodegenFieldPlanner.Plan> plans = new LinkedHashMap<>();
        List<MultiPageCodegen.PageUnit> pageUnits = new ArrayList<>();
        for (PageUrl key : byKey.keySet()) {
            CodegenFieldPlanner.Plan plan = CodegenFieldPlanner.plan(ordered.get(key), nlsFiles, fillValues);
            plans.put(key, plan);
            pageUnits.add(new MultiPageCodegen.PageUnit(targets.get(key), ordered.get(key), plan));
        }

        // 4) 按会话分组（未点 Start 的历史拾取归入第 1 个会话），会话号升序
        Map<Integer, List<Pick>> picksBySession = new LinkedHashMap<>();
        for (Pick p : picks) {
            picksBySession.computeIfAbsent(currentSession(p.session), k -> new ArrayList<>()).add(p);
        }
        Map<Integer, List<DialogOp>> dialogsBySession = new LinkedHashMap<>();
        for (DialogOp d : dialogOps) {
            dialogsBySession.computeIfAbsent(currentSession(d.session), k -> new ArrayList<>()).add(d);
        }
        List<Integer> sessionKeys = new ArrayList<>(picksBySession.keySet());
        sessionKeys.sort(Comparator.naturalOrder());

        // 5) 每个会话 → 一个 SessionUnit；方法序号从 1 连续编号（空会话自然不出方法）
        List<MultiPageCodegen.SessionUnit> sessions = new ArrayList<>();
        int stepNo = 0;
        for (Integer key : sessionKeys) {
            stepNo++;
            sessions.add(buildSession(stepNo, picksBySession.get(key),
                    dialogsBySession.getOrDefault(key, List.of()), targets, plans, fillValues));
        }

        return MultiPageCodegen.build(pageUnits, sessions, nlsFiles);
    }

    /**
     * 单个会话 → 一个 step 单元：动作序列（元素操作 / frame / dialog / 切页）+ 断言条目。
     *
     * @param index        方法序号（1 起）
     * @param sessionPicks 本会话拾取（未排序）
     * @param dialogs      本会话 dialog
     */
    private static MultiPageCodegen.SessionUnit buildSession(int index, List<Pick> sessionPicks, List<DialogOp> dialogs,
                                                            Map<PageUrl, CodegenTarget> targets,
                                                            Map<PageUrl, CodegenFieldPlanner.Plan> plans,
                                                            Map<String, String> fillValues) {
        List<Pick> timeline = new ArrayList<>(sessionPicks);
        timeline.sort(Comparator.comparingLong(p -> p.seq));

        // 本会话涉及的页面类（按首次出现顺序）——方法体内按此顺序实例化页面对象。
        // 注意：同一标签页导航（A→B）会产生两个不同的目标，故此处是「目标」列表而非「标签页」列表。
        List<CodegenTarget> pageTargets = new ArrayList<>();
        Set<CodegenTarget> seenTargets = new LinkedHashSet<>();
        for (Pick p : timeline) {
            CodegenTarget t = targets.get(new PageUrl(p.page, p.url));
            if (t != null && seenTargets.add(t)) pageTargets.add(t);
        }

        // 切页 / 同标签页导航 判定：
        //  · 跨标签页（p.page 变化）⇒ 变化前在上一页的最后一个操作升级为 waitForNewPage 触发器
        //    （from.waitForNewPage(() -> from.trigger.action)），新页由框架接管为当前页；
        //  · 同标签页、URL 变化（A→B）⇒ 不算切页，不生成 waitForNewPage，仅在该步骤处追加「已导航」注释
        //    （用户选择：直接 getPage(B.class) 继续，不等待），避免同页内跳转被误判成弹新窗。
        Set<Long> triggerIds = new LinkedHashSet<>();
        Map<Long, CodegenTarget> triggerToPage = new LinkedHashMap<>();
        PageUrl currentKey = null;
        Pick lastOnKey = null;
        for (Pick p : timeline) {
            PageUrl key = new PageUrl(p.page, p.url);
            if (currentKey == null) {
                currentKey = key;
                lastOnKey = p;
            } else if (!key.equals(currentKey)) {
                if (lastOnKey != null && p.page != currentKey.page) {
                    // 仅跨标签页才升级为 waitForNewPage 触发器；同标签页导航不在此处理
                    triggerIds.add(lastOnKey.id);
                    triggerToPage.put(lastOnKey.id, targets.get(key));
                }
                currentKey = key;
                lastOnKey = p;
            } else {
                lastOnKey = p;
            }
        }

        // 动作序列：拾取 + dialog 合并后按全局序号排序（保证 dialog 落在真实发生位置）
        List<SessionItem> items = new ArrayList<>();
        for (Pick p : timeline) items.add(new SessionItem(p.seq, p, null));
        for (DialogOp d : dialogs) items.add(new SessionItem(d.seq, null, d));
        items.sort(Comparator.comparingLong(i -> i.seq));

        List<MultiPageCodegen.SessionAction> actions = new ArrayList<>();
        Map<CodegenTarget, List<String>> frameByTarget = new LinkedHashMap<>();
        // dialog 没有 URL 快照，按「所属标签页的首个目标」解析（dialog 落在某个标签页上，取该页第一个 URL 的目标）。
        Map<Page, CodegenTarget> pageToTarget = new LinkedHashMap<>();
        for (Map.Entry<PageUrl, CodegenTarget> e : targets.entrySet()) {
            pageToTarget.putIfAbsent(e.getKey().page, e.getValue());
        }
        // 目标 → 规划：Page / Step / Assert 共用同一份 plan，字段名严格一致（按目标对齐，而非按 URL 字符串）。
        Map<CodegenTarget, CodegenFieldPlanner.Plan> planByTarget = new LinkedHashMap<>();
        for (Map.Entry<PageUrl, CodegenFieldPlanner.Plan> e : plans.entrySet()) {
            planByTarget.put(targets.get(e.getKey()), e.getValue());
        }
        // 同一元素在本步被反复拾取（如连点同一按钮）→ 只保留首次动作，避免 step 出现重复 click / fill。
        Set<String> usedSig = new HashSet<>();
        Pick prevPick = null;
        for (SessionItem item : items) {
            if (item.dialog != null) {
                CodegenTarget t = pageToTarget.get(item.dialog.page);
                if (t != null) actions.add(MultiPageCodegen.SessionAction.dialog(t, item.dialog.accept));
                prevPick = null;
                continue;
            }
            Pick p = item.pick;
            CodegenTarget t = targets.get(new PageUrl(p.page, p.url));
            if (t == null) { prevPick = p; continue; }

            // 同标签页导航（A→B）提示：与前一个拾取同页但不同目标 ⇒ 页面发生了 URL 跳转。
            // 不生成 waitForNewPage，仅在步骤体插入「已导航」注释（用户选择：直接 getPage(B.class) 继续，不等待）。
            if (prevPick != null) {
                CodegenTarget prevT = targets.get(new PageUrl(prevPick.page, prevPick.url));
                if (prevT != null && !prevT.equals(t) && p.page.equals(prevPick.page)) {
                    actions.add(MultiPageCodegen.SessionAction.navigation(prevT, t));
                }
            }
            prevPick = p;

            // 每个页类独立跟踪当前 frame：进入 iframe 时生成 switchToFrame（回到主帧不生成，字段本身带 frame 前缀）
            List<String> currentFrame = frameByTarget.getOrDefault(t, List.of());
            if (!p.entry.framePath.equals(currentFrame)) {
                if (!p.entry.framePath.isEmpty()) {
                    String inner = p.entry.framePath.get(p.entry.framePath.size() - 1);
                    actions.add(MultiPageCodegen.SessionAction.frameSwitch(
                            t, inner, String.join(" :: ", p.entry.framePath)));
                }
                frameByTarget.put(t, p.entry.framePath);
            }

            CodegenFieldSpec spec = fieldSpecFor(planByTarget.get(t), p.entry);
            if (spec == null) continue;

            if (triggerIds.contains(p.id)) {
                usedSig.add(p.entry.sigKey);
                actions.add(MultiPageCodegen.SessionAction.pageSwitch(
                        t, spec.fieldName, spec.action, triggerToPage.get(p.id)));
                continue;
            }
            // 同元素去重（保留首次）：连点同一元素不再产生重复动作
            if (!usedSig.add(p.entry.sigKey)) continue;
            boolean filled = fillValues != null && fillValues.get(p.entry.sigKey) != null;
            String todo = ("fill".equals(spec.verb) && !filled)
                    ? "replace the empty placeholder with the real input value" : "";
            actions.add(MultiPageCodegen.SessionAction.element(t, spec.fieldName, spec.action, todo));
        }

        // 断言：本会话每页类的元素（保持会话内操作顺序、按 sigKey 去重）
        List<MultiPageCodegen.PageEntries> asserts = new ArrayList<>();
        for (CodegenTarget tgt : pageTargets) {
            List<CodegenEntry> es = new ArrayList<>();
            Set<String> seen = new LinkedHashSet<>();
            for (Pick p : timeline) {
                CodegenTarget t = targets.get(new PageUrl(p.page, p.url));
                if (t != null && t.equals(tgt) && p.entry != null && seen.add(p.entry.sigKey)) es.add(p.entry);
            }
            if (!es.isEmpty()) {
                asserts.add(new MultiPageCodegen.PageEntries(tgt, es, planByTarget.get(tgt)));
            }
        }

        return new MultiPageCodegen.SessionUnit(index, pageTargets.isEmpty() ? null : pageTargets.get(0),
                pageTargets, actions, asserts);
    }

    /** 会话时间线上的一项：一次拾取或一个 dialog。 */
    private static final class SessionItem {
        final long seq;
        final Pick pick;
        final DialogOp dialog;

        SessionItem(long seq, Pick pick, DialogOp dialog) {
            this.seq = seq;
            this.pick = pick;
            this.dialog = dialog;
        }
    }

    /**
     * 分组键：<b>标签页 + 该次拾取的 URL</b>。
     *
     * <p>生成粒度从「标签页」改为「URL」后，同一标签页内发生导航（A→B）会产生两个不同的键，
     * 从而派生出两个不同的 Page 类（{@code APage} / {@code BPage}），旧类不再被吞掉。
     * 不同标签页若落在同一 URL，仍视为不同键（类名靠 {@link #resolveTargets} 的 _2 去重）。
     */
    private static final class PageUrl {
        final Page page;
        final String url;

        PageUrl(Page page, String url) {
            this.page = page;
            this.url = url;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof PageUrl)) return false;
            PageUrl that = (PageUrl) o;
            return page == that.page && java.util.Objects.equals(url, that.url);
        }

        @Override
        public int hashCode() {
            return 31 * System.identityHashCode(page) + java.util.Objects.hashCode(url);
        }
    }

    /** 安全取页面 URL：页面已关闭 / 取不到时返回 null（走兜底类名），不抛异常。 */
    private static String safeUrl(Page page) {
        if (page == null) return null;
        try {
            return page.url();
        } catch (Exception ignore) {
            return null;
        }
    }

    /** URL 派生目标，并对重名页面追加 _2 / _3 去重。 */
    private static Map<PageUrl, CodegenTarget> resolveTargets(java.util.Collection<PageUrl> keys) {
        Map<PageUrl, CodegenTarget> out = new LinkedHashMap<>();
        Set<String> used = new LinkedHashSet<>();
        for (PageUrl key : keys) {
            // 关键点：类名由【拾取时】的 URL 派生，而非 Stop 时的当前 URL——否则同标签页导航后
            // 旧 URL 的元素会被并入最终 URL 的类，旧 Page 类名消失。
            CodegenTarget base = CodegenTarget.of(CodegenTarget.DEFAULT_PACKAGE, key.url);
            String name = base.pageClassName;
            int n = 2;
            while (used.contains(name)) {
                name = base.pageClassName + "_" + n;
                n++;
            }
            used.add(name);
            out.put(key, name.equals(base.pageClassName) ? base : CodegenTarget.ofNamed(base.packageName, name));
        }
        return out;
    }

    /** 当前会话号（拾取 / dialog 打标用）；尚未点过 Start 时归入第 1 个会话。 */
    private static int currentSession(AtomicInteger sessionSeq) {
        return Math.max(1, sessionSeq.get());
    }

    /** 归一化会话号：历史数据与边界情况一律归入第 1 个会话。 */
    private static int currentSession(int session) {
        return Math.max(1, session);
    }

    private static CodegenFieldSpec fieldSpecFor(CodegenFieldPlanner.Plan plan, CodegenEntry entry) {
        if (plan == null || plan.fields == null) return null;
        for (CodegenFieldSpec f : plan.fields) {
            if (f.entry == entry) return f;
        }
        return null;
    }

    /**
     * 「生成前补做 role 复核」的唯一入口。
     *
     * <p>设计意图：{@code page.getByRole(...).count()} 在按钮/链接密集的大页面上要 10~90ms，压在点击响应里
     * 会明显发涩。拾取阶段只取 AX 语义（零成本），把引擎复核对齐到生成阶段（单线程、用户无感知地晚几十毫秒），
     * 从而<b>产物与"拾取时同步复核"逐字一致</b>，不牺牲任何正确性。
     *
     * <p><b>必须按"单个 / 一组"分别判定</b>（判定规则见 {@link RoleVerificationPolicy}）：单元素要求恰好命中 1 个；
     * 一组元素（将生成 {@code PageElementList}）要求命中数<b>等于组内拾取数</b>——多一个少一个都说明组被污染
     * （不可见元素被 role 引擎排除、或页面还有未拾取的同类元素），此时 {@code get(i)} 会错位。
     *
     * <p>复核不通过 ⇒ 该条目回退 CSS，并推面板就地修订该行（不新增行），随后重生成代码。
     * 复核不可判定（{@code -1}，CDP/页面异常）按"放行"处理，只告警不阻断——环境抖动不该把可靠产物
     * 退化成结构 CSS。
     */
    private static void verifyPendingRoles(CodegenPanelHandle panel, List<Pick> picks) {
        if (picks == null || picks.isEmpty()) return;

        // 组判定与字段规划同口径（同一 base-sig + 序号互异 ≥2 ⇒ 将合成一个 PageElementList）。
        List<CodegenEntry> all = new ArrayList<>();
        for (Pick pk : picks) {
            if (pk.entry != null) all.add(pk.entry);
        }
        Map<String, Integer> groupSizes = groupMemberCounts(all);

        // 按 base-sig 聚合待复核条目：同组必须一起判定、一起回退，否则同组内策略不一致。
        Map<String, List<Pick>> pendingByBase = new LinkedHashMap<>();
        for (Pick pk : picks) {
            if (!pk.rolePending) continue;
            pk.rolePending = false;   // 先消欠账：复核本身不会抛（countRoleMatches 内部按不可判定返回 -1）
            if (pk.entry == null) continue;
            pendingByBase.computeIfAbsent(CodegenFieldPlanner.baseSigOf(pk.entry.sigKey),
                    k -> new ArrayList<>()).add(pk);
        }

        for (Map.Entry<String, List<Pick>> en : pendingByBase.entrySet()) {
            Integer members = groupSizes.get(en.getKey());
            if (members != null && members > 1) {
                if (hasNonRoleMember(picks, en.getKey())) {
                    // 组内已有条目被回退（上一轮复核 / 本就不属于 role 策略）：整组必须一致，
                    // 否则字段规划取首条注解时会生成自相矛盾的产物。
                    log.warn("[codegen][cdp] group role strategy mismatch (base={}) — 整组统一回退 CSS",
                            en.getKey());
                    for (Pick pk : en.getValue()) {
                        reviseToCss(panel, pk, -1, "group already has non-role member");
                    }
                    continue;
                }
                verifyGroup(panel, en.getKey(), en.getValue(), members);
            } else {
                for (Pick pk : en.getValue()) {
                    verifySingle(panel, pk);
                }
            }
        }
    }

    /** 每个「组」的成员数（组内互异序号数）；非组条目不出现在结果里。 */
    private static Map<String, Integer> groupMemberCounts(List<CodegenEntry> entries) {
        Set<String> listBases = CodegenFieldPlanner.listGroupBaseSigs(entries);
        Map<String, Set<Integer>> idxByBase = new LinkedHashMap<>();
        for (CodegenEntry e : entries) {
            String base = CodegenFieldPlanner.baseSigOf(e.sigKey);
            if (!listBases.contains(base)) continue;
            idxByBase.computeIfAbsent(base, k -> new LinkedHashSet<>()).add(e.index);
        }
        Map<String, Integer> out = new LinkedHashMap<>();
        for (Map.Entry<String, Set<Integer>> en : idxByBase.entrySet()) {
            out.put(en.getKey(), en.getValue().size());
        }
        return out;
    }

    /** 同组内是否已有条目不是 role 策略（本轮回退过 / 角色本身不可定位）。 */
    private static boolean hasNonRoleMember(List<Pick> picks, String base) {
        for (Pick pk : picks) {
            CodegenEntry e = pk.entry;
            if (e == null || !base.equals(CodegenFieldPlanner.baseSigOf(e.sigKey))) continue;
            if (!LocatorStrategy.ROLE.equals(e.strategy) && !LocatorStrategy.ROLE_WITH_NAME.equals(e.strategy)) {
                return true;
            }
        }
        return false;
    }

    /** 单元素：严格模式要求恰好 1 个命中。 */
    private static void verifySingle(CodegenPanelHandle panel, Pick pk) {
        CodegenEntry entry = pk.entry;
        int hits = pk.extractor.countRoleMatches(entry);
        if (hits < 0) {
            log.warn("[codegen][cdp] deferred role verification undeterminable for #{} (role={}, name='{}') — "
                    + "keeping ROLE strategy", pk.id, entry.role, entry.name);
            return;
        }
        if (RoleVerificationPolicy.keepRole(hits, 1)) {
            log.debug("[codegen][cdp] deferred role verification passed for #{} (role={}, name='{}')",
                    pk.id, entry.role, entry.name);
            return;
        }
        reviseToCss(panel, pk, hits, "expected exactly 1 hit");
    }

    /**
     * 一组元素：定位器本就该命中整组，故要求命中数与拾取数严格相等。
     *
     * <p>不相等 ⇒ 组被污染（含不可见元素被 role 引擎排除 / 页面还有未拾取的同类元素），
     * {@code get(i)} 索引不可信：整组一起回退 CSS，避免同组策略不一致。
     */
    private static void verifyGroup(CodegenPanelHandle panel, String base, List<Pick> group, int members) {
        Pick head = group.get(0);
        CodegenEntry entry = head.entry;
        int hits = head.extractor.countRoleMatches(entry);
        if (hits < 0) {
            log.warn("[codegen][cdp] group role verification undeterminable (base={}, picked={}, role={}, "
                    + "name='{}') — keeping ROLE strategy", base, members, entry.role, entry.name);
            return;
        }
        if (RoleVerificationPolicy.keepRole(hits, members)) {
            log.debug("[codegen][cdp] group role verification passed (base={}, picked={}, hits={})",
                    base, members, hits);
            return;
        }
        log.warn("[codegen][cdp] group role locator mismatched (base={}, picked={}, hits={}) — "
                        + "组内元素数与引擎命中数不一致（不可见元素被排除 / 页面有未拾取的同类元素），"
                        + "索引不可信，整组回退 CSS",
                base, members, hits);
        for (Pick pk : group) {
            reviseToCss(panel, pk, hits, "group picked=" + members);
        }
    }

    /** 回退 CSS 并推面板就地修订该行（不新增行）。 */
    private static void reviseToCss(CodegenPanelHandle panel, Pick pk, int hits, String because) {
        CodegenEntry entry = pk.entry;
        CodegenEntry revised = RoleRevision.toCss(entry);
        pk.entry = revised;
        if (panel != null) {
            panel.onPickedRevised(pk.id, revised, "hits=" + hits + ", " + because);
        }
        log.info("[codegen][cdp] role locator rejected for #{} (hits={}, role={}, name='{}', {}) — "
                        + "strategy revised to CSS ('{}')",
                pk.id, hits, entry.role, entry.name, because, revised.selector);
    }

    private static void pushTab(CodegenPanelHandle panel, String tab, java.util.function.Supplier<String> generator) {
        try {
            panel.onCode(tab, generator.get());
        } catch (Exception e) {
            log.warn("[codegen] live {} generation failed: {}", tab, e.toString());
        }
    }

    /** 推送按页拆分的代码块（供面板在 Tab 内做「页内分页面切换器」）。 */
    private static void pushBlocks(CodegenPanelHandle panel, String tab,
                                   List<com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen.codegen.MultiPageCodegen.CodeBlock> blocks) {
        try {
            panel.onCodeBlocks(tab, blocks);
        } catch (Exception e) {
            log.warn("[codegen] live {} block generation failed: {}", tab, e.toString());
        }
    }
}
