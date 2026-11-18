package com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen;

import com.hsbc.cmb.hk.dbb.automation.framework.common.route.PageRefs;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen.cdp.CdpLocatorCounter;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen.cdp.CodegenCdpNodeExtractor;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen.cdp.CodegenCdpSession;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen.cdp.ElementRef;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen.cdp.PreClickSnapshot;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen.codegen.CodegenFieldPlanner;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen.codegen.CodegenFieldSpec;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen.codegen.CodegenTarget;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen.codegen.MultiPageCodegen;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen.engine.CodegenEngine;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen.inject.CodegenScriptInjector;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen.locator.AriaRoles;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen.locator.LocatorStrategy;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen.model.CodegenEntry;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen.model.TriState;
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
import java.util.HashMap;
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
import java.util.regex.Pattern;

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

    /**
     * 录制期原生对话框（confirm / prompt / alert）的策略：整场录制生效，录制开始前在面板选好。
     *
     * <p>Playwright 的默认行为是「不接管、自动 dismiss」：driver 的 {@code DialogManager#dialogDidOpen}
     * 在没有 handler 时调 {@code _close()}，而 {@code _close()} 对非 beforeunload 一律 dismiss；
     * 官方录制器的 handler 也 {@code return false} 交给平台关闭。本项目默认取
     * {@link #ACCEPT}（业务主流程是「确认」）——这是产品选择，不是与官方对齐。
     *
     * <p>两种默认值都只能录到一半：dismiss 录取消分支，accept 录确认分支。需要覆盖相反分支时，
     * 切换策略后整条重录，而不是在同一条用例里混合（那会产出互相矛盾的产物）。
     */
    enum DialogPolicy { ACCEPT, DISMISS }

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

    /**
     * 最近一次成功生成的三栏产物快照，专供「导出代码」命令使用（P0-6）。
     *
     * <p>面板进程单实例常驻、生成在单线程执行器上串行发生，故用 {@link AtomicReference} 保证
     * 跨线程可见性即可；这里只存「最后一份」，不参与任何拾取/打分/去重决策。
     */
    private static final AtomicReference<MultiPageCodegen.MultiPageResult> LAST_GENERATED =
            new AtomicReference<>();

    private CodegenLauncher() {}

    /**
     * 一次拾取的稳定身份。
     *
     * <p><b>为何需要 {@code id} 而不能只用 {@code sigKey}</b>：sigKey 由 role+name+序号构成，
     * 而"位于不同容器但 role+name 相同"的两个元素会产生<b>完全相同的 sigKey</b>（各自在容器内都是第 0 个）。
     * 它们恰恰是 List 最典型的来源，若用 sigKey 作行标识会互相覆盖、无法分别勾选/高亮，
     * 故每次拾取都分配一个单调递增的唯一 id。
     */
    static final class Pick {
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
        /**
         * {@code true} = 仅代表「对已拾取元素的又一次操作」：不进 Elements 表、不参与字段规划，
         * 只进入动作时间线（否则「勾 → 取消」「同字段两次不同值提交」会被当作重复拾取吞掉）。
         */
        final boolean actionOnly;
        /** 触发该条目的录制动作类型（{@code click} / {@code fill} / ...），用于按本次操作生成动作。 */
        final String actionType;
        /**
         * 本次动作的录制值（fill 的输入值 / press 的按键）。动作生成据此产出<b>当次</b>的
         * {@code fill("A")}，而不是字段模板里被后续输入覆盖成的末值（逐键合并时会被更新，故非 final）。
         */
        volatile String actionValue;
        /**
         * change 事件已为这条动作「盖章」（失焦 / 选中完成）。之后出现的<b>非增量</b>新值
         * 必须另起一条动作，避免「填 A → 失焦 → 填 B」被合并成一条、丢掉第一次提交的语义。
         */
        volatile boolean fillCommitted;
        /** 本次动作携带的值的个数（multiple select = 选中项数；其余 = 1），用于产物 TODO 提示。 */
        volatile int valueCount = 1;
        /**
         * 页面侧元素身份（{@code docId}）：元素所属<b>文档</b>的随机标识（注入脚本每次文档加载生成）。
         *
         * <p>与 {@link #eid} 一起构成「同一文档里的同一个节点」，是
         * {@link CodegenLauncher#matchFieldKey} 判定「这次操作的是不是已拾取过的那个元素」的<b>唯一权威依据</b>。
         * 空串 = 该条目没有身份信息（旧版注入脚本 / 单测构造），此时才回退到 role+name 判据。
         */
        volatile String docId = "";
        /** 页面侧元素身份（{@code eid}）：元素在本文档内的编号（WeakMap 绑定节点，节点回收即释放）。 */
        volatile String eid = "";

        /**
         * 登记页面侧元素身份。
         *
         * <p>为什么必须成对登记：{@code eid} 只在<b>同一个文档</b>内唯一。导航后 eid 会从 1 重新分配，
         * 只比 eid 会把「上一页的第 1 个元素」和「新页的第 1 个元素」误判成同一个。
         *
         * @param docId 文档标识（页面侧回传）
         * @param eid   元素标识（页面侧回传）
         */
        void identify(String docId, String eid) {
            this.docId = docId == null ? "" : docId;
            this.eid = eid == null ? "" : eid;
        }

        /** 是否带有可用的页面侧身份（两段都非空才可用）。 */
        boolean identified() {
            return !docId.isEmpty() && !eid.isEmpty();
        }

        Pick(long id, long seq, int session, CodegenEntry entry, Page page, String url,
              CodegenCdpNodeExtractor extractor, boolean rolePending) {
            this(id, seq, session, entry, page, url, extractor, rolePending, false, "click");
        }

        Pick(long id, long seq, int session, CodegenEntry entry, Page page, String url,
              CodegenCdpNodeExtractor extractor, boolean rolePending,
              boolean actionOnly, String actionType) {
            this.id = id;
            this.seq = seq;
            this.session = session;
            this.entry = entry;
            this.page = page;
            this.url = url;
            this.extractor = extractor;
            this.rolePending = rolePending;
            this.actionOnly = actionOnly;
            this.actionType = actionType == null ? "click" : actionType;
        }
    }

    /**
     * 拾取入库回调：把「本次解析出的条目 + 页面侧元素身份」落成一条 {@link Pick}。
     *
     * <p>单独定义而不是用 {@link java.util.function.BiFunction}：元素身份（docId/eid）必须与条目
     * <b>同时</b>入库——分开传会让「孪生元素消歧」与「同一元素判定」拿到不一致的数据。
     */
    @FunctionalInterface
    private interface PickSink {
        /**
         * @param page    元素所属被测页
         * @param entry   解析结果
         * @param docId   页面侧文档标识（可为空 = 无身份信息）
         * @param eid     页面侧元素标识（可为空）
         * @param pickUrl 点击捕获阶段记录的 {@code location.href}（导航前 URL）；为空时回退到 {@code page.url()}
         * @return 新建的 Pick（{@code entry == null} 时返回 {@code null}）
         */
        Pick add(Page page, CodegenEntry entry, String docId, String eid, String pickUrl);
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
            private volatile CodegenCdpNodeExtractor extractor;
            // 拾取不再有 per-page 线程：所有页的拾取 / 解析任务统一提交到 startSession 作用域的共享单线程引擎
            // CodegenEngine（见 submitPick）；此处仅保留 per-page 的 CDP 会话 / 抽取器数据结构。
            /** CDP 拾取通道是否已开启（幂等标记）。 */
            volatile boolean pickChannelOn = false;
            /**
             * hover 预览序号：鼠标在元素间快速移动时，引擎队列会积压多个预览请求；
             * 只有序号等于最新值的那一个才真正解析，其余直接丢弃（rAF 已合并同帧，这里再防跨帧积压）。
             */
            private final AtomicLong hoverSeq = new AtomicLong(0);
            /**
             * 悬浮已裁决的 entry（按页面侧 cssSelector 索引）。点击同一元素直接消费同一条结论，
             * 保证「悬浮 tooltip 显示的」与「点击落库 / 回放使用的」逐字一致。
             */
            /** 悬浮裁决结论 + 写入时刻（用于 TTL 过期：导航后旧文档的同名 nth 路径不能误命中新页面元素）。 */
            private static final class HoverVerdict {
                final CodegenEntry entry;
                final long atNanos;

                HoverVerdict(CodegenEntry entry) {
                    this.entry = entry;
                    this.atNanos = System.nanoTime();
                }
            }

            private final Map<String, HoverVerdict> hoverVerdicts = new java.util.concurrent.ConcurrentHashMap<>();
            /** 悬浮裁决有效期 10s：超过即视为过期（用户早该移开鼠标 / 页面已导航）。 */
            private static final long HOVER_VERDICT_TTL_NANOS = java.util.concurrent.TimeUnit.SECONDS.toNanos(10);

            /** 写入悬浮裁决结论（TTL 计时开始）。 */
            void putHoverVerdict(String key, CodegenEntry entry) {
                if (key == null || key.isEmpty() || entry == null) return;
                hoverVerdicts.put(key, new HoverVerdict(entry));
            }

            /**
             * 消费悬浮裁决结果：命中且未过期则移除（一次性）。
             *
             * <p>鼠标移动过程中悬浮解析可能仍在跑，点击先于它完成属正常竞态——此时返回 null，
             * 点击走自己的裁决路径，结论仍由同一个 {@link #adjudicateRole} 得出，不会表里不一。
             * TTL 防止导航后旧文档的同名 nth 路径选择器误命中新页面元素（悬浮结论属于旧 DOM）。
             */
            CodegenEntry consumeHoverVerdict(String cssSelector) {
                if (cssSelector == null || cssSelector.isEmpty()) return null;
                HoverVerdict v = hoverVerdicts.remove(cssSelector);
                if (v == null) return null;
                if (System.nanoTime() - v.atNanos > HOVER_VERDICT_TTL_NANOS) {
                    log.debug("[codegen][hover] verdict expired (TTL) for '{}' - discarding stale DOM conclusion",
                            cssSelector);
                    return null;
                }
                return v.entry;
            }

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
                        log.warn("[codegen][cdp] newCDPSession attempt {}/5 failed on {}: {} - retrying in 100ms",
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
                // 点击优先于悬浮预览：递增序号使队列中尚未执行的 hover 预览全部失效，
                // 避免用户点下去后先等一次预览抽取（实测单次要 20~360ms）才轮到本次拾取。
                hoverSeq.incrementAndGet();
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
             * 悬浮预览（对标 {@code page.pause()} 的「悬浮即显示代码」）。
             *
             * <p>只把最新序号的请求交由共享拾取引擎解析（派发线程绝不发 CDP），产出的文本来自
             * 唯一算法源：{@link CodegenScorer 打分器}选出的定位器 + role 引擎实时复核。因此悬浮
             * 看到的就是点击会落库、回放会使用的同一条定位器，而不是 JS 侧另算的近似值。
             */
            void submitHoverPreview(Map<String, Object> payload) {
                final long seq = hoverSeq.incrementAndGet();
                String selector = payload.get("selector") instanceof String ? (String) payload.get("selector") : null;
                if (selector == null || selector.isEmpty()) return;
                @SuppressWarnings("unchecked")
                List<Integer> framePath = framePathOf(payload);
                try {
                    engine.submit(CodegenEngine.Kind.PICK, page, () -> {
                        if (seq != hoverSeq.get()) return;   // 只处理最新一次悬浮，丢弃队列中已过期的预览
                        try {
                            cdpSession();
                            ElementRef ref = session.elementBySelector(selector, List.of(), framePath);
                            if (ref == null) {
                                log.debug("[codegen][hover] selector unresolved '{}' - no tooltip", selector);
                                return;
                            }
                            CodegenEntry raw = extractor.extract(ref, null);
                            if (raw == null) {
                                log.debug("[codegen][hover] extract null for '{}' - no tooltip", selector);
                                return;
                            }
                            // 悬浮即裁决：与点击共用同一判据，结论写入缓存供点击直接消费。
                            CodegenEntry decided = adjudicateRole(raw, null, null, extractor, "hover preview");
                            // key 必须与点击 payload 的 hoverKey 同源——都是 hoverSelectorOf(target) 的 nth 路径。
                            putHoverVerdict(selector, decided);
                            // tooltip 显示的必须是最终定位器本身（与点击落库 / 回放逐字一致）。
                            final String text = decided.selector;
                            if (text == null || text.isEmpty()) return;
                            // extract() 已完成 Java 侧 NLS 反查（data-i18n / 后代 key → nlsKey），
                            // 把结果显示成附注：悬浮即可看到最终代码会用的 i18n key，与产物注解逐字对齐。
                            String note = (decided.nlsKey == null || decided.nlsKey.isEmpty())
                                    ? "" : "NLS: " + decided.nlsKey;
                            page.evaluate("a => { try { if (window.__CODEGEN_SET_LOCATOR__) "
                                            + "window.__CODEGEN_SET_LOCATOR__(a.t, a.n); } catch (e) {} }",
                                    Map.of("t", text, "n", note));
                            log.debug("[codegen][hover] tooltip sent: '{}' note='{}'", text, note);
                        } catch (Throwable t) {
                            log.debug("[codegen][hover] preview failed on {}: {}", page.url(), t.getMessage());
                        }
                    });
                } catch (Exception e) {
                    log.debug("[codegen][hover] preview submit failed: {}", e.getMessage());
                }
            }

            /**
             * 解析被点元素。
             *
             * <p><b>只用「点击事件目标」给出的定位事实，不做坐标反查</b>：坐标随滚动 / 重排 / 动画漂移，
             * 且 CDP 的 {@code DOM.getNodeForLocation} 只在主 frame 做 hit-test——跨源 iframe 内必然打空，
             * 一旦命中错位元素还会产出死选择器（历史事故：{@code #alttext-container}）。
             *
             * <p><b>优先页面侧选择器（逐 frame 求值）</b>：cssSelector 是相对元素<b>自身文档</b>算出来的，
             * 经 {@link com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen.cdp.CdpFrameLocator}
             * 在每个 frame（含跨源 iframe）的执行上下文里求值即可命中，并顺带返回元素所属 frameId——
             * 这是跨 frame 穿透（{@code frame=SELa :: SELb :: <内层 css>}）的必要输入。
             *
             * <p>元素若在 <b>open shadow root</b> 内，{@code document.querySelector} 穿不透 shadow 边界，
             * 必须由 {@code shadowHosts}（页面侧给出的宿主链，outermost-first）拼成链式表达式求值。
             *
             * <p>仅限共享引擎线程调用（会话与通道已由 {@link #submitPick} 包装内建好）。
             */
            CodegenEntry pickAt(String type, Object cssSelector, String xpath,
                                List<String> shadowHosts, List<Integer> framePath, List<String> robustXpaths,
                                PreClickSnapshot preClick, String hoverKey, Map<String, Object> clickInfo) {
                cdpSession();   // 防御性保证会话已建（正常由 submitPick 包装已建）
                String selector = cssSelector instanceof String ? (String) cssSelector : null;
                ElementRef ref = session.elementBySelector(selector, shadowHosts, framePath);
                if (ref != null) {
                    log.debug("[codegen][cdp] resolved by selector ({}) '{}' shadowHosts={} -> frame={}",
                            type, selector, shadowHosts, ref.frameId());
                }
                // 稳健 XPath 兜底（父级 / 子级属性锚定）：优先于绝对路径，仅在 CSS 全部失效时尝试。
                if (ref == null && robustXpaths != null) {
                    for (String rx : robustXpaths) {
                        if (rx == null || rx.isBlank()) continue;
                        ElementRef r = session.elementBySelector("xpath=" + rx, List.of(), framePath);
                        if (r != null) {
                            ref = r;
                            log.info("[codegen][cdp] resolved by anchored xpath ({}) '{}' -> frame={}",
                                    type, rx, ref.frameId());
                            break;
                        }
                    }
                }
                // cssSel 缺失/未命中：用「点击捕获的 ev.target 绝对 XPath」兜底——它来自点击瞬间算好的 facts，
                // 稳定且不受"弹窗关闭 / DOM 重渲染"影响。**没有坐标兜底**：不再按点击坐标回查元素。
                if (ref == null && xpath != null && !xpath.isBlank()) {
                    String xpathSel = "xpath=" + xpath;
                    ref = session.elementBySelector(xpathSel, List.of(), framePath);
                    if (ref != null) {
                        log.info("[codegen][cdp] resolved by xpath ({}) '{}' -> frame={}", type, xpath, ref.frameId());
                    } else if (selector != null) {
                        log.debug("[codegen][cdp] selector '{}' missed and xpath fallback '{}' also missed"
                                + " - type={}, page={}", selector, xpath, type, page.url());
                    } else {
                        log.debug("[codegen][cdp] no cssSelector; xpath fallback '{}' missed"
                                + " - type={}, page={}", xpath, type, page.url());
                    }
                }
                if (ref == null) {
                    // 点击即导航元素（如 Cancel）：旧 DOM 已被新文档替换，selector/xpath 全部落空。
                    // 兜底①：悬浮已裁决的条目（hoverKey 命中）——与 tooltip 显示逐字一致。
                    CodegenEntry hovered = consumeHoverVerdict(hoverKey);
                    if (hovered != null) {
                        log.info("[codegen][cdp] recovered nav pick from hover verdict ({}) '{}' "
                                        + "(old DOM navigated away)", type, hovered.selector);
                        return hovered;
                    }
                    // 兜底②：用点击捕获阶段同步采集的完整 facts 直接构造条目（见 extractFromClickInfo）。
                    // 这是「导航按钮不丢」的关键——info 是导航前算好的快照，不依赖当前 DOM。
                    CodegenEntry fromFacts = extractor.extractFromClickInfo(clickInfo, preClick, framePath);
                    if (fromFacts != null) return fromFacts;
                    // 两条兜底都失败：必须可见，静默失败时用户只看到「点了没反应」。
                    log.warn("[codegen][cdp] pick failed ({}) on {} (selector='{}', xpath='{}', shadowHosts={}) - "
                                    + "cssSel and ev.target xpath both unresolved, and no pre-click facts to recover: "
                                    + "page may have navigated / element removed / dynamic rewrite",
                            type, page.url(), selector, xpath, shadowHosts);
                    return null;
                }
                try {
                    // 直接把定位结果（含 frame 归属）交给抽取：省掉一次 Page.getFrameTree，
                    // 并让子 frame 元素跳过必然失败的页级 role 复核（大页面上该复核 10~90ms）。
                    // preClick 是 window 捕获阶段同步采集的「点击前」事实：抽取跑在拾取引擎线程上，
                    // 此时页面自身 click 处理器已把提交类按钮变成 loading，名字不可再用（见 PreClickSnapshot）。
                    // 悬浮已裁决的条目直接复用同一条结论——tooltip 显示的与落库/回放的逐字一致（page.pause 语义）。
                    CodegenEntry hovered = consumeHoverVerdict(hoverKey);
                    if (hovered != null) {
                        log.debug("[codegen][cdp] reused hover-adjudicated locator ({}) '{}'", type, hovered.selector);
                        return hovered;
                    }
                    return extractor.extract(ref, preClick);
                } catch (Exception e) {
                    log.warn("[codegen][cdp] extract failed ({}) for backendNodeId={}: {}",
                            type, ref.backendNodeId(), e.getMessage());
                    return null;
                }
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
            /**
             * 录制期原生对话框的默认动作（整场生效）。handler 内<b>同步</b>执行、不等待面板，
             * 因此不会阻塞 Playwright 的派发线程；切换策略只影响之后弹出的对话框。
             */
            final AtomicReference<DialogPolicy> dialogPolicy = new AtomicReference<>(DialogPolicy.ACCEPT);

            PageSession of(Page p) {
                return map.computeIfAbsent(p, k -> new PageSession(k, lang, nls));
            }
            /** 开启某页的拾取能力（幂等）；失败返回 false 让面板显示 "start failed"。 */
            boolean enablePick(Page p) {
                return of(p).openPickChannel();
            }
            /**
             * 接管原生对话框（alert / confirm / prompt / beforeunload），按会话级策略<b>同步</b>放行。
             *
             * <p>必须注册 handler，否则 Playwright 会在无人接管时自动 {@code dismiss}（见
             * {@link DialogPolicy} 的说明）——那样「确认」分支永远录不到。接入策略后：
             * accept 录确认分支、dismiss 录取消分支；{@code beforeunload} 恒 accept（dismiss 会取消导航，
             * 页面停在原地，后续拾取/录制全部失效）。
             *
             * <p>handler 内只做一次同步的 accept/dismiss，不等待面板回传，因此不会冻结派发线程；
             * 三键 UI（每次弹窗暂停等用户选）会让每次弹窗都打断录制，不做。
             */
            void installDialogAutoDismiss(Page page) {
                if (page == null || dialogHandlers.containsKey(page)) return;
                Consumer<Dialog> h = dialog -> {
                    boolean accept = acceptForDialog(dialogPolicy.get(), dialog.type());
                    try {
                        if (accept) {
                            dialog.accept();
                        } else {
                            dialog.dismiss();
                        }
                        // 记录<b>真实</b>动作（不再恒 true）：步骤里据此生成 acceptAlert() / dismissAlert()。
                        dialogOps.add(new DialogOp(page, dialog.type(), accept, EVENT_SEQ.incrementAndGet(),
                                currentSession(sessionSeq)));
                    } catch (Throwable t) {
                        // 同一弹窗可能被 context 级 / 其它监听器先关掉 → "No dialog is showing"，忽略即可。
                        log.debug("[codegen][dialog] dialog already handled / {} failed on {}: {}",
                                accept ? "accept" : "dismiss", page.url(), t.getMessage());
                    }
                };
                page.onDialog(h);
                dialogHandlers.put(page, h);
                log.debug("[codegen][dialog] handler installed on {} (policy={})", page.url(), dialogPolicy.get());
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
                    log.debug("[codegen][follow] new page {} opened without opener (manual tab) - register + dialog only, no follow/enable",
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
                        log.warn("[codegen][follow] inject recorder/bridge into new page failed: {} - {}",
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
                                log.warn("[codegen][follow] new page {} not DOM-ready within 1.5s - pick enabled lazily on first click",
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
                    log.warn("[codegen][follow] PICK BINDING UNREACHABLE on page {} - this page's "
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
        // 入参带页面侧元素身份：① 落库到 Pick（后续「同一元素」判定用）；② sigKey 冲突消歧（孪生元素）。
        final PickSink addPick = (pg, entry, docId, eid, pickUrl) -> {
            if (entry == null) return null;
            PageSession ps = sessions.of(pg);
            // 孪生元素（同 role+name、同组内序号）会算出相同 sigKey：字段规划按 sigKey 去重，
            // 不去消歧的话第二个元素在产物里会被当「同元素重复点选」直接丢弃（用户看到「生成了但只有一个字段」）。
            CodegenEntry effective = disambiguateSigKey(entry, docId, eid, picked);
            // 悬浮已裁决的条目（roleHits 不再是 UNVERIFIED）不再欠复核——tooltip 显示的就是这条结论。
            boolean roleAlreadyAdjudicated = effective.roleHits != CodegenEntry.ROLE_HITS_UNVERIFIED;
            // URL 归属：优先用点击捕获阶段记录的 location.href（导航型元素点击后立即跳页，异步落库时
            // page.url() 已是新页面——Back/Cancel 会被错误归到 logon 页、Step 代码出现在错误的 Page）。
            String pickUrlSafe = (pickUrl != null && !pickUrl.isEmpty())
                    ? pickUrl : safeUrl(pg);
            Pick pk = new Pick(PICK_SEQ.incrementAndGet(), EVENT_SEQ.incrementAndGet(), currentSession(sessionSeq),
                    effective, pg, pickUrlSafe, ps.extractor(),
                    !roleAlreadyAdjudicated && ps.extractor().requiresDeferredRoleVerification(effective));
            pk.identify(docId, eid);
            picked.add(pk);
            if (panelRef[0] == null) {
                log.warn("[codegen][cdp] picked #{} but panelRef not ready (panel null)", pk.id);
                verifyRoleAtPickTime(null, pk);
                return pk;
            }
            panelRef[0].onPicked(pk.id, effective);
            // 点击当场完成 role 唯一性裁决：命中恰好 1 才保留 role，否则就地回退 CSS。
            // 与 page.pause 语义一致——显示 / 落库 / 回放三者同源，不再拖到生成期才发现多命中。
            verifyRoleAtPickTime(panelRef[0], pk);
            // 实时增量生成：每次拾取后立即刷新 Page/Step/Assert，无需等到按 Stop 才看到代码。
            requestLiveRegeneration(regenExecutor, panelRef, picked, recorder, dialogOps, nlsFiles, lang, sessions.stopped);
            log.debug("[codegen][cdp] picked #{} name='{}' role='{}' selector='{}'",
                    pk.id, pk.entry.name, pk.entry.role, pk.entry.selector);
            return pk;
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
                        // 选择器反查必然失败（没有坐标兜底），导致新页拾取静默无显示。
                        boolean anyEnabled = false;
                        int enabledPages = 0;
                        for (Page p : sessions.map.keySet()) {
                            if (sessions.enablePick(p)) {
                                anyEnabled = true;
                                enabledPages++;
                            }
                        }
                        if (!anyEnabled) {
                            throw new IllegalStateException("pick channel could not be enabled on any page (see logs)");
                        }
                        // 恢复拾取必须留痕：此前成功路径一行日志都没有，用户点了「继续拾取」也无法从日志确认
                        // （排查「点了按钮却始终不生成代码」时，无法区分"没点/点了没生效/点了但状态没切"）。
                        log.info("[codegen] pick resumed (start): channel enabled on {} page(s); "
                                + "subsequent clicks are collected again", enabledPages);
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
                case "export": {
                    // 产物出口（P0-6）：把最近一次定稿的三个栏逐块写成 .java 文件。
                    // 默认落 <cwd>/target/codegen/<时间戳>/；CI 可用 -Dcodegen.export.dir=<绝对路径>
                    // 指到工程外，避免全新检出时产物被冲掉。
                    MultiPageCodegen.MultiPageResult snapshot = LAST_GENERATED.get();
                    if (snapshot == null) {
                        if (panelRef[0] != null) panelRef[0].setStatus("nothing to export yet");
                        break;
                    }
                    try {
                        Path root = Path.of(System.getProperty("codegen.export.dir", "target/codegen"));
                        Path dir = allocateExportDir(root);
                        CodegenExporter.export(snapshot, dir);
                        String msg = "exported to " + dir.toAbsolutePath();
                        if (panelRef[0] != null) panelRef[0].setStatus(msg);
                        log.info("[codegen] {}", msg);
                    } catch (Exception e) {
                        log.warn("[codegen] export failed: {}", e.toString());
                        if (panelRef[0] != null) panelRef[0].setStatus("export failed: " + e.getMessage());
                    }
                    break;
                }
                case "dialogPolicy": {
                    // 会话级策略：整场录制生效。切换只影响之后弹出的对话框，
                    // 需要覆盖相反分支时整条重录（一条用例里不混两种策略）。
                    DialogPolicy policy = parseDialogPolicy(cmd.size() > 1 ? String.valueOf(cmd.get(1)) : "accept");
                    sessions.dialogPolicy.set(policy);
                    if (panelRef[0] != null) {
                        panelRef[0].setStatus("dialog: " + policy.name().toLowerCase(java.util.Locale.ROOT));
                    }
                    log.info("[codegen] dialog policy = {}", policy);
                    break;
                }
                case "group":
                    handleGroup(picked, cmd, panelRef[0]);
                    requestLiveRegeneration(regenExecutor, panelRef, picked, recorder, dialogOps, nlsFiles, lang, sessions.stopped);
                    break;
                case "stop": {
                    sessions.pickOn.set(false);
                    // 同一会话内重复 Stop 去重：Stop 后除非经过 Start 续拾（会复位 stopped），否则内容不会变化，
                    // 用户连点 Stop 不应刷出 N 份相同产物。已定稿则只刷新状态条、跳过重生成。
                    if (sessions.stopped.get()) {
                        log.debug("[codegen] stop received but already finalized (no resume since last stop) - skipping re-generation");
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
                    log.info("[codegen] session ended by panel 'End Session'; panel process stays resident for next inspect");
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

        // 录制/拾取事件桥：注入脚本在 DOM 捕获阶段上报真实操作（click/fill）及其定位事实（选择器 / XPath / frame 路径）。
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
            // 悬浮预览：与 page.pause 一致，鼠标停在元素上就显示最终会用的 locator。
            // 只登记请求，CDP 解析交给共享引擎（见 submitHoverPreview），绝不阻塞消息派发线程。
            if ("hover".equals(String.valueOf(payload.get("type")))) {
                if (sessions.pickOn.get() && ps != null) ps.submitHoverPreview(payload);
                return null;
            }
            Object infoObj = payload.get("info");
            @SuppressWarnings("unchecked")
            Map<String, Object> info = infoObj instanceof Map ? (Map<String, Object>) infoObj : null;
            // 未拾取 / 该页没有拾取会话 / 该元素已在 Elements 里：只录制，省掉一次 CDP 往返
            // （输入框连续输入会连发 fill 事件，这里必须短路，否则每次按键都要查一遍节点）。
            String matchedKey = matchFieldKey(info, picked);
            if (!sessions.pickOn.get() || ps == null || matchedKey != null) {
                if (sessions.pickOn.get() && ps == null) {
                    log.warn("[codegen][bridge] no pick session for page {} - page is not in the pick channel (inactive page?)",
                            src != null ? src.url() : "null");
                }
                // 暂停态下点击「尚未拾取」的元素只会进动作时间线（无元素可绑定则丢弃），页面侧没有任何反馈。
                // 这是「点了一次按钮却什么都没发生」最常见的原因，留一行可检索的痕迹便于区分
                // 「事件没到 Java」与「到了但按暂停语义主动丢弃」。
                if (!sessions.pickOn.get() && matchedKey == null
                        && "click".equals(String.valueOf(payload.get("type")))) {
                    log.debug("[codegen] click dropped: picking is paused and element is not collected yet ({})",
                            info != null ? info.get("cssSelector") : "unknown");
                }
                recordAction.accept(payload, null);
                // 已拾取元素不再重复 CDP 拾取，但「第二次操作」必须进入动作时间线：
                // 否则勾→取消、同组 radio 改选、同字段两次不同值提交都只剩第一步（P0-3）。
                // 逐键连续 fill 不新增条目（末次值已由 recorder 覆盖），避免每个按键一条动作。
                if (sessions.pickOn.get() && matchedKey != null && src != null) {
                    Pick existing = findPickBySig(picked, matchedKey);
                    if (existing != null) {
                        String repeatType = String.valueOf(payload.get("type"));
                        String currentValue = String.valueOf(payload.getOrDefault("value", ""));
                        String semantic = String.valueOf(payload.getOrDefault("semantic", ""));
                        // 键盘动作把按键编进动作标记，供生成侧产出 press("Enter") 等可回放调用；
                        // file 单独标记：生成侧要出 uploadFile 占位而不是 fill(fakepath)。
                        String actionTag = "press".equals(repeatType)
                                ? "press:" + currentValue
                                : "file".equals(semantic) ? "file" : repeatType;
                        int valueCount = payload.get("valueCount") instanceof Number
                                ? ((Number) payload.get("valueCount")).intValue() : 1;
                        Pick lastFill = lastFillPick(picked, matchedKey);
                        // 逐键输入合并、重新填写切分（P1 值维度）：
                        // ① 值相同（含 change 在失焦时重报同一值）→ 合并，避免一次选择留两条等价动作；
                        // ② 未盖章且值互为前缀（打字/退格）→ 合并，末次值覆盖；
                        // ③ 其余（先填 A 再填 B、失焦后重填）→ 新增一条动作，值各按当次操作生成。
                        // file 不是「输入」，不参与输入合并：每次选择文件都是一次独立动作。
                        boolean fillEvent = "fill".equals(repeatType) && !"file".equals(semantic);
                        boolean sameValue = lastFill != null && currentValue.equals(lastFill.actionValue);
                        boolean continuousFill = fillEvent && lastFill != null
                                && (sameValue || (!lastFill.fillCommitted
                                        && isIncremental(lastFill.actionValue, currentValue)));
                        if (continuousFill) {
                            lastFill.actionValue = currentValue;
                        } else {
                            CodegenEntry repeatEntry = resolveCheckedState(existing.entry, info, repeatType);
                            // URL 归属与首次拾取一致：用点击捕获阶段的 url，导航型元素重复操作时才不会归到新页面。
                            String repeatUrl = payload.get("url") instanceof String
                                    ? (String) payload.get("url") : safeUrl(src);
                            Pick np = new Pick(PICK_SEQ.incrementAndGet(), EVENT_SEQ.incrementAndGet(),
                                    currentSession(sessionSeq), repeatEntry, src, repeatUrl,
                                    existing.extractor, false, true, actionTag);
                            np.actionValue = fillEvent ? currentValue : null;
                            np.valueCount = Math.max(1, valueCount);
                            // 重复动作同样要带上元素身份：否则「第三次点同一元素」只能靠 role+name 反查，
                            // 又会把同 role+name 的孪生元素并进来。
                            np.identify(docIdOf(info), eidOf(info));
                            picked.add(np);
                        }
                        // change = 「这次填写完成」的语义盖章：下一次出现非增量值必须另起一条动作。
                        if (fillEvent && "change".equals(semantic)) {
                            Pick committed = continuousFill ? lastFill : lastFillPick(picked, matchedKey);
                            if (committed != null) committed.fillCommitted = true;
                        }
                    }
                }
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
                // 只记选择器事实，不记坐标：定位链路里已经没有坐标这一环。
                log.debug("[codegen][bridge] click on {} (selector='{}', fingerprint={})",
                        ps.page.url(), info != null ? info.get("cssSelector") : null, fingerprint);
            }
            final Page eventPage = ps.page;
            final PageSession pickSession = ps;
            // 元素已在 Elements 里时不会走到这里（上面已短路），故本次一定能拿到新条目；
            // 万一解析失败（e == null），仍按 info 反查已拾取元素兜底，保证动作不丢。
            pickSession.submitPick(fingerprint, () -> {
                CodegenEntry e = null;
                try {
                    // 执行期暂停闸门（而非只判入队时刻）：点击事件进 engine 队列后，可能排队到「暂停」
                    // 之后才真正执行（队列里还有前一次 CDP 往返）。只判入队会让暂停后仍有元素陆续落进
                    // Elements，用户观感就是「点了暂停还在拾取」。这里再判一次：
                    // 已暂停 ⇒ 动作照常录制（与暂停语义一致：动作仍进 Step 时间线），但不新增 Elements 条目。
                    // 代价：暂停瞬间仍在队列里的那次点击不会成为新元素（若确实需要，重新点该元素即可）。
                    if (!sessions.pickOn.get()) {
                        log.debug("[codegen] pick skipped (paused before execution): {}", fingerprint);
                        recordAction.accept(payload, null);
                        return;
                    }
                    // P0-1 新页拾取兜底：极少数情况下（外网页加载慢、注入脚本尚未就绪 / addInitScript 尚未生效）
                    // 页面上 __CODEGEN__ 缺失，录制器与拾取辅助函数不可用 → 拾取静默失效。拾取执行前先自检，
                    // 缺失则同线程补注后再解析（不额外起线程、不阻塞派发线程）。注入脚本首行有幂等保护，重复求值安全。
                    ensureInstrumented(eventPage);
                    e = pickSession.pickAt(actionType,
                            info != null ? info.get("cssSelector") : null,
                            info != null ? (String) info.get("xpath") : null,
                            shadowHostsOf(info), framePathOf(payload), robustXpathsOf(info),
                            preClickSnapshot(info), payload.get("hoverKey") instanceof String
                                    ? (String) payload.get("hoverKey") : null, info);
                    // addPick 内部按 (docId, eid) 落库元素身份 + 消歧孪生元素的 sigKey：
                    // 后续对同一元素的重复操作（再点一次 / 继续输入）靠身份命中本条，
                    // 而不是靠 role+name 反查（那样会把页面里同 role+name 的孪生元素并成一条）。
                    if (e != null) {
                        addPick.add(eventPage, resolveCheckedState(e, info, actionType), docIdOf(info), eidOf(info),
                                payload.get("url") instanceof String ? (String) payload.get("url") : null);
                    }
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
                                    + "DOM +{} but bridge +{} on {} - instrumentation not firing on this page "
                                    + "(click listener or its window.__codegenEvent call is broken).",
                                    skewTicks[0], deltaDom, deltaBridge, p.url());
                            skewTicks[0] = 0;
                        } else {
                            log.debug("[codegen][pump] transient DOM/bridge skew (likely message ordering): "
                                    + "DOM +{} bridge +{} on {} - will re-check next tick", deltaDom, deltaBridge, p.url());
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
        log.info("[codegen][pump] event pump started (120ms, separate thread) - drives Playwright message dispatch");

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
            log.info("[codegen] panel window closed by user - ending codegen session (panel process terminated, resident service degraded)");
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
        ctx.onPage(p -> sessions.followNewPage(p));

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
            if (closeWatchdog != null) { try { closeWatchdog.shutdownNow(); } catch (Exception e) { log.debug("[codegen] closeWatchdog shutdown failed (session teardown, ignored)", e); } }
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
            throw new IllegalStateException("[codegen] NLS bundles were provided but the current language is not set - "
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
            panel.setStatus("grouped: 需要 >=2 个元素");
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
            log.warn("[codegen] grouped {} elements whose role/name differ - the list locator is derived from "
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
                    + " - run the inspector against chromium (remove @firefox/@edge overrides for that scenario)");
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

    /** 同一元素最近一条动作型 fill（逐键输入合并到这条上；没有则返回 null，表示本次应新建动作）。 */
    private static Pick lastFillPick(List<Pick> picked, String sigKey) {
        Pick last = null;
        for (Pick p : picked) {
            if (p.actionOnly && p.entry.sigKey.equals(sigKey)) last = p;
        }
        return (last != null && "fill".equals(last.actionType)) ? last : null;
    }

    /**
     * 两次值是否属于「同一段输入」：相等，或一方是另一方的前缀（打字追加 / 退格删除）。
     *
     * <p>用前缀关系而不是「值相同才合并」，是因为逐键输入每键值都不同；若按后者判，
     * 一个 6 位账号会产出 6 条动作。反过来，「填 A → 清空 → 填 B」中 A 与 B 互不为前缀，
     * 会正确切分成两条。
     */
    static boolean isIncremental(String previous, String current) {
        if (previous == null || current == null) return false;
        return previous.equals(current) || current.startsWith(previous) || previous.startsWith(current);
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
     * 从注入脚本回传的元素事实里取 open shadow 宿主链（outermost-first）。
     *
     * <p>缺失 / 非数组一律返回空列表（旧版注入脚本没有该字段时退化为普通查找）。
     * <b>不静默丢弃空项</b>：链上任一层为空即说明事实不可信，原样带下去由
     * {@code CdpFrameLocator} 判定为不可用——丢掉中间层会去错误的 root 里找元素。
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
     * 从事件事实取稳健 XPath 兜底列表（借父级 / 子级属性锚定，由注入脚本生成，已逐一校验唯一）。
     * 缺失 / 非数组 / 含非字符串项一律回落空列表（保守：无锚定 XPath 时退回绝对 XPath / 失败日志）。
     */
    static List<String> robustXpathsOf(Map<String, Object> info) {
        if (info == null) return List.of();
        Object raw = info.get("robustXpaths");
        if (!(raw instanceof List<?> items)) return List.of();
        List<String> paths = new ArrayList<>(items.size());
        for (Object item : items) {
            if (item instanceof String s && !s.isBlank()) paths.add(s);
        }
        return paths;
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
     * 把录制事件里的元素事实匹配到某个已拾取条目，返回其 sigKey；找不到返回 {@code null}
     * （该动作被忽略，不污染产物）。
     *
     * <p><b>判据顺序（缺一不可）</b>：
     * <ol>
     *   <li><b>页面侧元素身份</b>（{@code docId + eid}）：同一文档里的同一个节点。最强判据——
     *       元素结构路径被页面改写（loading 态、列表插入兄弟节点）时它仍然相同；</li>
     *   <li><b>CSS 选择器精确相等</b>：兼容没有身份信息的事件（旧注入脚本 / 单测构造），
     *       同时覆盖「同一元素、路径未变」的常规情况；</li>
     *   <li><b>role+name 完全相等</b>：<b>仅在双方都没有身份信息时</b>兜底。</li>
     * </ol>
     *
     * <p><b>为什么 role+name 不能作为常规判据（真实缺陷）</b>：页面里普遍存在「孪生按钮」——
     * 同一表单上下两处 {@code <button><span data-i18n=...>Continue</span></button>}、各分区各一个
     * Next/Cancel。它们的 role+name 完全一致，一旦用 role+name 反查，<b>用户点第二个按钮时会被判定为
     * 「已在 Elements 里」而直接短路</b>：既不生成新定位、也不留任何日志（旧实现只有 DEBUG 级的
     * 「[bridge] click」日志，且该日志在短路之后才打印）——现象就是「第二个 Continue 按钮点了没反应、
     * 面板里永远只有一条」。这条判据因此必须收敛到「无身份信息的历史载荷」上。
     */
    static String matchFieldKey(Map<String, Object> info, List<Pick> picked) {
        if (info == null) return null;
        String docId = docIdOf(info);
        String eid = eidOf(info);
        boolean identified = !docId.isEmpty() && !eid.isEmpty();
        if (identified) {
            for (Pick p : picked) {
                if (docId.equals(p.docId) && eid.equals(p.eid)) return p.entry.sigKey;
            }
        }
        String css = str(info, "cssSelector", "");
        for (Pick p : picked) {
            CodegenEntry e = p.entry;
            if (e.cssSelector != null && e.cssSelector.equals(css)) return e.sigKey;
        }
        if (!identified) {
            // 历史载荷（无页面侧身份）才允许按 role+name 兜底，避免孪生元素被并成一条。
            String role = str(info, "role", "generic");
            String name = str(info, "name", "");
            for (Pick p : picked) {
                CodegenEntry e = p.entry;
                if (Objects.equals(e.role, role) && Objects.equals(e.name, name)) return e.sigKey;
            }
        }
        return null;
    }

    /** 事件载荷里的文档标识（页面侧 {@code __codegenDocId}）；缺失返回空串。 */
    static String docIdOf(Map<String, Object> info) {
        return info == null ? "" : str(info, "docId", "");
    }

    /** 事件载荷里的元素标识（页面侧 WeakMap 分配的 eid）；缺失返回空串。 */
    static String eidOf(Map<String, Object> info) {
        return info == null ? "" : str(info, "eid", "");
    }

    /**
     * 孪生元素的 {@code sigKey} 消歧：同一 sigKey 已被<b>另一个元素</b>占用时，追加 {@code @n} 后缀。
     *
     * <p><b>为什么必须做</b>：{@code sigKey} = {@code role|name#组内序号}，两处「同名同角色」的按钮
     * （各在自己的父容器里都是第 0 个）会算出完全相同的 sigKey。而 {@code CodegenFieldPlanner} 的第一步
     * 就是「按 sigKey 精确去重（同元素重复点选只留首条）」——于是<b>第二个孪生元素在产物里被当成重复条目直接丢弃</b>，
     * 用户看到的就是「两个 Continue、只生成了一个定位器」。
     *
     * <p>消歧只针对「同 sigKey 但<b>不同元素</b>」：同一元素（docId+eid 相同）的再次拾取保持原 sigKey
     * （那本来就是重复条目，理应去重）。后缀形如 {@code @2/@3}，与组内序号 {@code #n} 互不干扰：
     * {@code CodegenFieldPlanner#baseSigOf} 只剥末尾 {@code #数字}，故 {@code #0@2} 不会被误判成一组。
     *
     * @param entry  本次解析出的条目
     * @param docId  本次元素的文档标识（空 = 无身份信息，退回原 sigKey）
     * @param eid    本次元素的元素标识（空 = 无身份信息）
     * @param picked 已拾取列表
     * @return 需要消歧时返回替换了 sigKey 的副本，否则原样返回
     */
    static CodegenEntry disambiguateSigKey(CodegenEntry entry, String docId, String eid, List<Pick> picked) {
        if (entry == null || entry.sigKey == null || entry.sigKey.isEmpty()) return entry;
        if (picked == null || picked.isEmpty()) return entry;
        boolean identified = docId != null && !docId.isEmpty() && eid != null && !eid.isEmpty();
        if (!identified) return entry;   // 无身份信息 ⇒ 无法区分孪生，保持旧行为（交字段规划去重）
        // 该元素自己在 Elements 里已有条目（重试/重复拾取）：保持原 sigKey，由字段规划按「同元素」去重。
        for (Pick p : picked) {
            if (p.entry == null) continue;
            if (p.identified() && docId.equals(p.docId) && eid.equals(p.eid)) return entry;
        }
        int occupants = 0;
        for (Pick p : picked) {
            if (p.entry == null) continue;
            if (entry.sigKey.equals(twinBaseOf(p.entry.sigKey))) occupants++;
        }
        if (occupants == 0) return entry;
        String rewritten = entry.sigKey + "@" + (occupants + 1);
        log.info("[codegen][cdp] twin element detected (sigKey '{}' already taken by another element) - "
                + "signature rewritten to '{}' so both elements keep their own field", entry.sigKey, rewritten);
        return entry.withSigKey(rewritten);
    }

    /** 去掉 {@code @n} 消歧后缀（{@link #disambiguateSigKey} 产出）后的 sigKey；无后缀则原样返回。 */
    static String twinBaseOf(String sigKey) {
        if (sigKey == null || sigKey.isEmpty()) return "";
        int at = sigKey.lastIndexOf('@');
        if (at <= 0 || at == sigKey.length() - 1) return sigKey;
        for (int i = at + 1; i < sigKey.length(); i++) {
            if (!Character.isDigit(sigKey.charAt(i))) return sigKey;
        }
        return sigKey.substring(0, at);
    }

    /**
     * 对话框默认动作：{@code beforeunload} 恒 accept（dismiss 等于取消导航，页面停在原地，
     * 后续拾取与录制全部失效），其余类型按会话策略。纯函数，便于不依赖 Playwright 的单测覆盖。
     */
    static boolean acceptForDialog(DialogPolicy policy, String dialogType) {
        if ("beforeunload".equals(dialogType)) return true;
        return policy != DialogPolicy.DISMISS;
    }

    /** 面板值解析：只认 {@code dismiss}（大小写不敏感），其余（含 null / 拼错）一律回落到默认 accept。 */
    static DialogPolicy parseDialogPolicy(String raw) {
        String v = raw == null ? "" : raw.trim();
        return "dismiss".equalsIgnoreCase(v) ? DialogPolicy.DISMISS : DialogPolicy.ACCEPT;
    }

    /**
     * 为本次导出一个唯一目录：毫秒级时间戳，同毫秒已存在则追加 {@code -2/-3}…
     *
     * <p>旧实现只到秒，同一秒内连点两次导出会落进同一目录，第二份直接覆盖第一份（评审 N-06）。
     * 抽成可注入时间的纯函数，便于不依赖真实时钟验证「已存在则加序号」分支。
     */
    static Path allocateExportDir(Path root) {
        return allocateExportDir(root, java.time.LocalDateTime.now());
    }

    static Path allocateExportDir(Path root, java.time.LocalDateTime now) {
        String stamp = now.format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS"));
        Path dir = root.resolve(stamp);
        int n = 2;
        while (java.nio.file.Files.exists(dir)) {
            dir = root.resolve(stamp + "-" + n++);
        }
        return dir;
    }

    /**
     * 从注入脚本 click 事件的 {@code info}（window <b>捕获阶段</b>采集，早于页面自身处理器）提取
     * 「点击前」快照。
     *
     * <p>唯一用途是纠正「点击会改写自己」的控件名字（提交按钮点一下就变 loading）。是否采信由
     * {@code PickNamePolicy} 判定：只有元素自身文案在捕获与抽取之间确实变了，才用点击前的名字。
     *
     * @param info 事件载荷里的元素事实；{@code null} / 缺字段时返回 {@code null}（无证据）
     */
    static PreClickSnapshot preClickSnapshot(Map<String, Object> info) {
        if (info == null) return null;
        Object name = info.get("name");
        Object text = info.get("text");
        return PreClickSnapshot.of(name instanceof String ? (String) name : null,
                text instanceof String ? (String) text : null);
    }

    /**
     * 勾选态「终态」解析（P0-1）。
     *
     * <p>注入侧 {@code infoOf} 在 click <b>捕获阶段</b>执行，{@code info.checked} 必然是<b>点击前</b>状态；
     * 抽取侧 {@code Runtime.callFunctionOn} 在独立的拾取执行器线程上发出，与页面默认 toggle 之间存在
     * 跨线程时序竞态，直接采信抽取快照既可能拿到旧值也可能拿到新值。因此对 checkbox / radio 的 click
     * 一律按动作语义推导终态：checkbox 取反、radio 恒选中（点击 radio 必然选中）。
     * 缺少注入侧证据（旧脚本 / 跨源 iframe）时保留抽取快照，行为退化为既有实现而不是丢字段。
     *
     * @param entry      CDP 抽取得到的条目
     * @param info       注入侧 click 事件携带的元素事实（含点击前 checked）
     * @param actionType 录制动作类型（{@code click} / {@code fill} / ...）
     * @return 勾选态已按动作后终态修正的派生条目；非目标场景原样返回
     */
    static CodegenEntry resolveCheckedState(CodegenEntry entry, Map<String, Object> info, String actionType) {
        if (entry == null || info == null || !"click".equals(actionType)) return entry;
        String role = entry.role == null ? "" : entry.role.toLowerCase(java.util.Locale.ROOT);
        boolean checkbox = "checkbox".equals(role);
        if (!checkbox && !"radio".equals(role)) return entry;
        Object before = info.get("checked");
        if (!(before instanceof Boolean)) return entry;
        boolean after = checkbox ? !((Boolean) before) : true;
        return entry.withChecked(after);
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
     * 此时录制器/拾取辅助函数不可用，若不补注则拾取静默失效。注意：本方法只能挽救「监听已挂但辅助函数缺失」
     * 的情形；若点击发生在监听挂载之前（极窄窗口），该次点击已丢失，本方法为后续点击兜底。
     */
    private static void ensureInstrumented(Page page) {
        try {
            Object ok = page.evaluate("() => !!(window.__CODEGEN__ && window.__CODEGEN__.recorder)");
            if (!Boolean.TRUE.equals(ok)) {
                log.warn("[codegen][bridge] instrumentation missing on {} - lazy re-injecting codegen-inject.js",
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
    /**
     * 本轮渲染期间是否有「新请求到达、但本轮没渲染到」——决定是否必须补投一轮。
     *
     * <p>抽成纯函数是为了把并发不变量变成可锁定的契约：{@code renderedSeq} 是本轮最后一次
     * 读取到的请求序号，{@code currentSeq} 是结束时的实时序号。相等 = 已收敛，大于 = 漏了一轮。
     * 包级私有仅供同包测试与 {@link #requestLiveRegeneration} 使用。
     */
    static boolean regenerationStale(long renderedSeq, long currentSeq) {
        return currentSeq > renderedSeq;
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
                // 本轮「已渲染到的请求序号」，用于 finally 判断是否漏掉了期间到达的请求。
                long renderedSeq = REGEN_SEQ.get();
                try {
                    if (nlsLang != null) {
                        com.hsbc.cmb.hk.dbb.automation.framework.common.context.LanguageState
                                .setLanguage(nlsLang);
                    }
                    long started = renderedSeq;
                    // 首推：跳过全量复核（verify 是 CDP 往返、首推瓶颈），先把代码推上面板（快，目标 <500ms）。
                    long tFirst = System.nanoTime();
                    generateMultiPage(panelRef[0], picks, recorder, dialogOps, nlsFiles, stopped, /*skipVerify=*/true);
                    long firstMs = (System.nanoTime() - tFirst) / 1_000_000;
                    if (firstMs > 500) {
                        log.warn("[codegen][regen] first push (skipVerify) took {} ms (>500ms) - "
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
                    // 【必须在合并之后重新求 pending】首推之前算出的 anyPending 是过期快照：
                    // 若首推期间到达一条 rolePending 拾取，合并重推已把它画到面板上，
                    // 但旧快照为 false ⇒ 这一轮不会补复核 ⇒ 面板会一直显示未校验的 @RoleElement 直到 Stop。
                    boolean anyPending = picks.stream().anyMatch(p -> p.rolePending);
                    // 首推完成后，若有未复核 role，后台补复核 + 重推修订（不阻塞首推；verify 是 CDP 往返，
                    // 落在首推路径之外，用户先把代码看到、再被修订覆盖）。Stop 定稿走 skipVerify=false 已含复核。
                    if (anyPending && !stopped.get()) {
                        long tBack = System.nanoTime();
                        generateMultiPage(panelRef[0], picks, recorder, dialogOps, nlsFiles, stopped, /*skipVerify=*/false);
                        // 后台 verify+repush 并入明细桶：高频时不再逐条 INFO（汇总条里已含耗时），
                        // 慢轮在 generateMultiPage 内以 (slow) INFO 单独呈现。
                        regenLogBucket((int) ((System.nanoTime() - tBack) / 1_000_000));
                    }
                    renderedSeq = REGEN_SEQ.get();
                } catch (Throwable t) {
                    log.warn("[codegen][regen] regeneration failed: {}", t.toString());
                } finally {
                    REGEN_INFLIGHT.set(false);
                    // 【补触发】requestLiveRegeneration 的早退注释说"已在跑的任务兜底"，
                    // 但只在任务尚未越过合并判断时成立。若新请求恰好落在 merge/verify 之后、
                    // INFLIGHT 置回 false 之前，它只 increment 了 REGEN_SEQ 就返回，永远不会被渲染。
                    // 这里按序号差补投一轮；补投任务自身看不到新的序号变化 ⇒ 收敛，不会自旋。
                    if (!stopped.get() && regenerationStale(renderedSeq, REGEN_SEQ.get())) {
                        requestLiveRegeneration(regenExecutor, panelRef, picks, recorder,
                                dialogOps, nlsFiles, nlsLang, stopped);
                    }
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
        LAST_GENERATED.set(result);   // 供「导出代码」命令落盘（P0-6）
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
        // 0) 视图分流：字段视图（每元素一次，供 Page 类 / plan / 断言字段名）与动作视图
        //    （含「对已拾取元素的后续操作」，供 Step 动作时间线）。二者不能混用，否则
        //    「勾 → 取消」「同字段两次不同值提交」会因 sigKey 相同而被吞掉（P0-3）。
        //    字段视图里的 checked 取末次状态：断言必须校验最终态，而不是第一次操作后的态。
        List<Pick> allPicks = new ArrayList<>(picks);
        allPicks.sort(Comparator.comparingLong(p -> p.seq));
        Map<String, CodegenEntry> lastStateBySig = new LinkedHashMap<>();
        for (Pick p : allPicks) {
            if (p.entry != null && p.entry.sigKey != null) lastStateBySig.put(p.entry.sigKey, p.entry);
        }
        List<Pick> fieldPicks = new ArrayList<>();
        for (Pick p : allPicks) {
            if (p.actionOnly || p.entry == null || p.entry.sigKey == null) continue;
            CodegenEntry latest = lastStateBySig.get(p.entry.sigKey);
            CodegenEntry view = (latest != null && latest.checked != p.entry.checked)
                    ? p.entry.withChecked(latest.checked) : p.entry;
            fieldPicks.add(new Pick(p.id, p.seq, p.session, view, p.page, p.url,
                    p.extractor, p.rolePending));
        }

        // 1) 每个「拾取 URL 的 path」唯一目标（键见 PageUrl：去 query / 去 hash、忽略标签页身份）。
        //    path 变化（A→B）仍是两个键、两个 Page 类；hash/query 变化、同 URL 多标签页都归入同一个类，
        //    不再派生 XPage_2 / XPage_3。只有「不同 path 派生出同名类」才追加 _2 / _3。
        Map<PageUrl, List<Pick>> byKey = new LinkedHashMap<>();
        for (Pick p : fieldPicks) byKey.computeIfAbsent(new PageUrl(p.page, p.url), k -> new ArrayList<>()).add(p);
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
        // 文本语义定位白名单（问题②）：label 等无 ARIA role 的元素，命中 NLS 且文本唯一时才生成
        // key-only @RoleElement。必须在生成前用真实引擎复核——拾取热路径不做这次计数（一次 10~90ms）。
        Set<String> textSemanticSigs = resolveTextSemanticSigs(fieldPicks);
        Map<PageUrl, CodegenFieldPlanner.Plan> plans = new LinkedHashMap<>();
        List<MultiPageCodegen.PageUnit> pageUnits = new ArrayList<>();
        for (PageUrl key : byKey.keySet()) {
            CodegenFieldPlanner.Plan plan =
                    CodegenFieldPlanner.plan(ordered.get(key), nlsFiles, fillValues, textSemanticSigs);
            plans.put(key, plan);
            pageUnits.add(new MultiPageCodegen.PageUnit(targets.get(key), ordered.get(key), plan));
        }

        // 4) 按会话分组（未点 Start 的历史拾取归入第 1 个会话），会话号升序。
        //    动作视图包含重复操作，Step 方法体会据此产出多步；字段视图只用于上面的 Page / plan。
        Map<Integer, List<Pick>> picksBySession = new LinkedHashMap<>();
        for (Pick p : allPicks) {
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
     * 计算「NLS 文本唯一定位」白名单（问题②，2026-11-11）。
     *
     * <p>条件（缺一不可）：
     * <ul>
     *   <li>条目带 NLS key（多语言环境下才有跨语言稳定标识）；</li>
     *   <li>主文档元素（页级定位对 iframe / shadow 内元素不可靠）；</li>
     *   <li>唯一性判定<b>按最终产物的形态计数</b>：role 可定位时按 {@code role + 可访问名} 计
     *       （产物是 {@code @RoleElement(role=…, key=…)}，运行期走 {@code getByRole}），
     *       否则才退回 {@code getByText}（产物是 key-only 文本策略）；</li>
     *   <li>命中数必须恰好 <b>1</b>。未通过一律不纳入 NLS（宁可退 CSS）：
     *       不做唯一性校验会在 strict mode 下多命中失败（用户规则④）。</li>
     * </ul>
     *
     * <p><b>为什么不能一律用 {@code getByText}</b>（2026-11-15 用户反馈「页上只有一个可见的 Continue，
     * 面板却不显示 NLS」）：{@code getByText} 统计的是<b>整个 DOM</b>，把 SPA 里 {@code display:none}
     * 的其它页面、隐藏弹窗里的同名按钮全部算进去（本例 "Continue" 命中 4~5 处），于是明明运行期
     * {@code getByRole(button, name)} 只会命中唯一可见按钮，却被判成"文本不唯一"而降级成 CSS。
     * 判定口径必须与产物同源，而不是比产物更严。
     *
     * <p>同一「role + 文案」只计数一次（生成期页面静态）；异常/不可判定一律视为不通过。
     */
    private static Set<String> resolveTextSemanticSigs(Collection<Pick> picks) {
        if (picks == null || picks.isEmpty()) {
            return Set.of();
        }
        Map<String, Boolean> byKey = new HashMap<>();
        Set<String> out = new HashSet<>();
        for (Pick p : picks) {
            CodegenEntry e = p.entry;
            if (e == null || e.nlsKey == null || e.nlsKey.isEmpty()) continue;
            if (e.name == null || e.name.isBlank()) continue;
            if (!e.framePath.isEmpty() || !e.shadowHosts.isEmpty()) continue;
            // 缓存键含 role：同名不同角色的唯一定性本来就不同（如 label "Continue" vs button "Continue"）。
            String cacheKey = e.role + "\u0000" + e.name;
            boolean unique = byKey.computeIfAbsent(cacheKey, k -> isTextSemanticUnique(p, e));
            if (unique) {
                out.add(e.sigKey);
            } else {
                log.debug("[codegen] NLS text not unique for key={} name='{}' role={} - keeping CSS fallback",
                        e.nlsKey, e.name, e.role);
            }
        }
        return out;
    }

    /** 唯一性判定：role 可定位 ⇒ 按引擎的 role+name 命中数；否则退回页级文本计数。只信 {@code == 1}。 */
    private static boolean isTextSemanticUnique(Pick p, CodegenEntry e) {
        if (AriaRoles.isLocatable(e.role) && p.extractor != null) {
            int roleHits = p.extractor.countRoleMatches(e.role, e.name);
            if (roleHits >= 0) {
                return roleHits == 1;
            }
        }
        return countText(p.page, e.name) == 1;
    }

    /** 页级文本计数；异常/不可判定返回 -1（调用方只信 {@code == 1}）。 */
    private static int countText(Page page, String text) {
        try {
            return page.getByText(text, new Page.GetByTextOptions().setExact(true)).count();
        } catch (Exception ex) {
            log.debug("[codegen] text uniqueness check failed for '{}': {}", text, ex.getMessage());
            return -1;
        }
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
        // 双击合并：注入侧已跳过 detail>1 的 click，时间线上仍是 [click, dblclick]，不合并会生成
        // click(); doubleClick();（比录制多一次单击）。必须在动作生成前收口。
        timeline = mergeDoubleClicks(timeline);

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
        Page currentPickPage = null;
        Pick lastOnKey = null;
        for (Pick p : timeline) {
            PageUrl key = new PageUrl(p.page, p.url);
            // 切页判据用【标签页身份】，不能用分组键：同一 URL 的新标签页现在与旧页共用一个键
            // （本来就是同一个 Page 类），但它仍是一次真实切页，必须保留 waitForNewPage 触发器，
            // 否则「点链接开同 URL 新标签」的操作会被并入旧页、产物里凭空少一次切页。
            boolean tabChanged = currentPickPage != null && p.page != currentPickPage;
            if (currentKey == null) {
                currentKey = key;
                lastOnKey = p;
            } else if (tabChanged || !key.equals(currentKey)) {
                if (tabChanged && lastOnKey != null) {
                    // 仅跨标签页才升级为 waitForNewPage 触发器；同标签页导航不在此处理
                    triggerIds.add(lastOnKey.id);
                    triggerToPage.put(lastOnKey.id, targets.get(key));
                }
                currentKey = key;
                lastOnKey = p;
            } else {
                lastOnKey = p;
            }
            currentPickPage = p.page;
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
        for (Pick p : timeline) {
            CodegenTarget t = targets.get(new PageUrl(p.page, p.url));
            if (t != null) pageToTarget.putIfAbsent(p.page, t);
        }
        // 目标 → 规划：Page / Step / Assert 共用同一份 plan，字段名严格一致（按目标对齐，而非按 URL 字符串）。
        Map<CodegenTarget, CodegenFieldPlanner.Plan> planByTarget = new LinkedHashMap<>();
        for (Map.Entry<PageUrl, CodegenFieldPlanner.Plan> e : plans.entrySet()) {
            planByTarget.put(targets.get(e.getKey()), e.getValue());
        }
        // 去重键 = 元素 + 动作签名：只有「完全相同的纯 click 连点」才丢弃。
        // 勾↔取消、radio 改选、同字段两次不同值提交都是不同动作，必须全部保留（P0-3）。
        Map<String, String> lastActionBySig = new HashMap<>();
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
            // 动作按「本次操作」生成：checkbox 连续勾选→取消时，两次点击必须分别产出
            // check()/uncheck()，不能复用字段规划里那份固定的动作模板。
            String action = actionForPick(spec, p);
            String signature = spec.verb + "|" + action;

            if (triggerIds.contains(p.id)) {
                lastActionBySig.put(p.entry.sigKey, signature);
                actions.add(MultiPageCodegen.SessionAction.pageSwitch(
                        t, spec.fieldName, spec.action, triggerToPage.get(p.id)));
                continue;
            }
            String previous = lastActionBySig.put(p.entry.sigKey, signature);
            if (isRepeatedClick(previous, signature, spec.verb, p.actionType)) continue;   // 仅纯连点去重
            boolean filled = fillValues != null && fillValues.get(p.entry.sigKey) != null;
            String todo = ("fill".equals(spec.verb) && !filled)
                    ? "replace the empty placeholder with the real input value" : "";
            if ("file".equals(p.actionType)) {
                // fakepath 不可用：产物给可编译的 uploadFile 占位 + TODO，不让假路径蒙混过关。
                todo = "replace the placeholder with a real classpath/file path, "
                        + "e.g. uploadFile(\"files/xxx.pdf\")";
            } else if (p.valueCount > 1) {
                todo = "multiple select: only the first of " + p.valueCount
                        + " values is generated; add the rest manually";
            }
            actions.add(MultiPageCodegen.SessionAction.element(t, spec.fieldName, action, todo));
        }

        // 断言：本会话每页类的元素（保持会话内操作顺序、按 sigKey 去重）。
        // 必须放 plan.fields 里的「同一个 entry 对象」：AssertClassGenerator.specOf 按引用比较；
        // 该对象已按末次勾选态修正，因此断言期望值自然取最终状态（P0-3）。
        List<MultiPageCodegen.PageEntries> asserts = new ArrayList<>();
        for (CodegenTarget tgt : pageTargets) {
            List<CodegenEntry> es = new ArrayList<>();
            Set<String> seen = new LinkedHashSet<>();
            CodegenFieldPlanner.Plan plan = planByTarget.get(tgt);
            Map<String, CodegenEntry> canonical = new LinkedHashMap<>();
            if (plan != null && plan.fields != null) {
                for (CodegenFieldSpec f : plan.fields) {
                    if (f.entry != null && f.entry.sigKey != null) canonical.put(f.entry.sigKey, f.entry);
                }
            }
            for (Pick p : timeline) {
                CodegenTarget t = targets.get(new PageUrl(p.page, p.url));
                if (t == null || !t.equals(tgt) || p.entry == null || p.entry.sigKey == null) continue;
                if (!seen.add(p.entry.sigKey)) continue;
                CodegenEntry e = canonical.get(p.entry.sigKey);
                if (e != null) es.add(e);
            }
            if (!es.isEmpty()) {
                asserts.add(new MultiPageCodegen.PageEntries(tgt, es, plan));
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
     * 分组键：<b>该次拾取的 URL（归一化：去 query / 去 hash）</b>，与标签页身份无关。
     *
     * <p>页面类的语义是「一个 URL path 一个类」，故键必须与
     * {@link CodegenTarget#pageClassNameFromUrl(String)} 的派生口径一致——那边同样只看 path。
     * 键与派生口径一旦不一致，就会出现「同一页面被拆成 {@code XPage} / {@code XPage_2} / {@code XPage_3}」：
     * <ul>
     *   <li>同一标签页内换成不同 path（A→B）⇒ 两个键、两个类，旧类不会被吞掉；</li>
     *   <li>SPA 只改 hash（{@code /reset-password#/triage → #/password}）或换 query 参数
     *       ⇒ <b>同一个键</b>，字段全部并入同一个 Page 类，不再派生 {@code _2}；</li>
     *   <li>同一 URL 出现在多个标签页（或 Page 对象被框架重建）⇒ <b>同一个键</b>，
     *       页面类型相同，重复类名只会制造噪声。</li>
     * </ul>
     *
     * <p>真正需要 {@code _2} 的情形只剩「不同 path 恰好派生出同名类」（{@code /logon} 与 {@code /Logon}），
     * 由 {@link #resolveTargets} 处理。
     *
     * <p>URL 不可读（页面已关闭 / 取不到）时退回标签页身份，避免把互不相关的「未知页」并成一个类。
     */
    static final class PageUrl {
        final Page page;
        final String url;
        /** 归一化键（去 query / 去 hash / 去尾斜杠）；{@code url == null} 时为 null。 */
        private final String key;

        PageUrl(Page page, String url) {
            this.page = page;
            this.url = url;
            this.key = urlKey(url);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof PageUrl)) return false;
            PageUrl that = (PageUrl) o;
            if (key != null && that.key != null) return key.equals(that.key);
            return page == that.page;
        }

        @Override
        public int hashCode() {
            return key != null ? key.hashCode() : 31 * System.identityHashCode(page);
        }
    }

    /**
     * 页面分组用的 URL 归一化：去掉 query（跟踪参数 / 表单态）与 hash（SPA 路由态）以及尾部斜杠。
     *
     * <p>刻意保留 host 与 path 的大小写：{@code /Logon} 与 {@code /logon} 是两个真实路径，
     * 各自成类（重名时由 {@link #resolveTargets} 追加 {@code _2}）。
     *
     * @return 归一化键；{@code url == null} 时返回 {@code null}（调用方退回标签页身份）
     */
    static String urlKey(String url) {
        if (url == null) return null;
        String s = url.trim();
        int cut = s.length();
        int q = s.indexOf('?');
        if (q >= 0) cut = Math.min(cut, q);
        int h = s.indexOf('#');
        if (h >= 0) cut = Math.min(cut, h);
        while (cut > 0 && s.charAt(cut - 1) == '/') cut--;   // 尾斜杠不是不同的页面
        String normalized = s.substring(0, cut);
        // M3：path 里的语言段（/en_US/logon vs /zh_HK/logon）同属一个逻辑页面，必须剥掉，
        // 否则切语言会分裂成两个 Page 类 / 两组 Step。
        return stripPathLocale(normalized);
    }

    /** path 里的语言/地区段（/en_US/、/zh-HK/、/tc/…）；只在两段 '/' 之间匹配，避免误伤业务名。 */
    private static final Pattern PATH_LOCALE_SEGMENT = Pattern.compile(
            "/(?:en|zh|tc|sc|hk|cn|tw|ja|jp|ko|kr|fr|de|es|pt|it|nl|ru|ar|th|vi|id|ms|hi|tr|pl|sv|da|fi|no|cs|el|he|hu|ro|sk|uk)"
                    + "(?:[-_](?:us|gb|uk|hk|cn|tw|sg|au|ca|in|jp|kr|de|fr|es|pt|br|mx|it|nl|ru|ae|sa|th|vn|id|my|tr|pl|hans|hant))?(?=/|$)",
            Pattern.CASE_INSENSITIVE);

    /** 剥掉 URL path 中的语言段（保留 host 与其余业务 path）。 */
    static String stripPathLocale(String url) {
        if (url == null || url.isEmpty()) return url;
        // 只处理 path 部分（scheme://host 之后的 '/…'），避免把域名里的 en/zh 子域误剥。
        int scheme = url.indexOf("://");
        int pathStart = scheme >= 0 ? url.indexOf('/', scheme + 3) : url.indexOf('/');
        if (pathStart < 0) return url;
        String prefix = url.substring(0, pathStart);
        String path = url.substring(pathStart);
        java.util.regex.Matcher m = PATH_LOCALE_SEGMENT.matcher(path);
        String stripped = m.replaceAll("");
        // 重复剥（如 /en_US/zh-HK/logon 这种叠的语言段，虽罕见但保守处理一次即可）。
        return prefix + stripped;
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

    /** URL 派生目标；只有「不同 path 派生出同名类」才追加 {@code _2} / {@code _3}。 */
    private static Map<PageUrl, CodegenTarget> resolveTargets(java.util.Collection<PageUrl> keys) {
        Map<PageUrl, CodegenTarget> out = new LinkedHashMap<>();
        List<PageUrl> ordered = new ArrayList<>(keys);
        List<String> names = assignClassNames(ordered);
        for (int i = 0; i < ordered.size(); i++) {
            PageUrl key = ordered.get(i);
            // 关键点：类名由【拾取时】的 URL 派生，而非 Stop 时的当前 URL——否则同标签页导航后
            // 旧 URL 的元素会被并入最终 URL 的类，旧 Page 类名消失。
            CodegenTarget base = CodegenTarget.of(CodegenTarget.DEFAULT_PACKAGE, key.url);
            String name = names.get(i);
            out.put(key, name.equals(base.pageClassName) ? base : CodegenTarget.ofNamed(base.packageName, name));
        }
        return out;
    }

    /**
     * 为若干分组键分配页面类名（保持入参顺序）。
     *
     * <p>「同一 URL」在分组阶段就已合并成一个键（{@link PageUrl} 按归一化 URL 判等），
     * 所以这里不会因为「同一页面被拾取多次 / 落在多个标签页」而追加 {@code _2}：
     * 后缀只在<b>不同 URL 派生出同名类</b>时才出现（{@code /logon} 与 {@code /Logon}）。
     */
    static List<String> assignClassNames(List<PageUrl> keys) {
        List<String> out = new ArrayList<>(keys.size());
        Set<String> used = new LinkedHashSet<>();
        for (PageUrl key : keys) {
            String base = CodegenTarget.of(CodegenTarget.DEFAULT_PACKAGE, key.url).pageClassName;
            String name = base;
            int n = 2;
            while (used.contains(name)) {
                name = base + "_" + n;
                n++;
            }
            used.add(name);
            out.add(name);
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

    /**
     * 双击合并：把「紧邻的 click + dblclick（同元素）」压成一次 {@code doubleClick()}。
     *
     * <p>浏览器双击序列是 click(detail=1) → click(detail=2) → dblclick。注入侧已按 {@code detail > 1}
     * 跳过第二下 click，但第一下会正常拾取；不合并的话产物是 {@code click(); doubleClick();}，
     * 回放比录制多一次单击。这里在动作生成前丢弃紧邻的那次 click，保留 dblclick 条目
     * （它与 click 携带同一元素事实，且 {@link #actionForPick} 会为 dblclick 生成双击动作）。
     *
     * <p>纯函数、不依赖浏览器：便于回归测试。
     */
    static List<Pick> mergeDoubleClicks(List<Pick> picks) {
        if (picks == null || picks.size() < 2) return picks;
        List<Pick> out = new ArrayList<>(picks.size());
        for (int i = 0; i < picks.size(); i++) {
            Pick p = picks.get(i);
            Pick next = i + 1 < picks.size() ? picks.get(i + 1) : null;
            if (isDoubleClickLead(p, next)) continue;
            out.add(p);
        }
        return out;
    }

    /** 是否为「双击的第一下 click」：紧随其后是同一页面、同一元素的 dblclick。 */
    private static boolean isDoubleClickLead(Pick p, Pick next) {
        if (p == null || next == null) return false;
        if (!"click".equals(p.actionType) || !"dblclick".equals(next.actionType)) return false;
        if (p.entry == null || next.entry == null || p.entry.sigKey == null) return false;
        if (!p.entry.sigKey.equals(next.entry.sigKey)) return false;
        return Objects.equals(p.page, next.page) && Objects.equals(p.url, next.url);
    }

    /**
     * 仅「完全相同的纯 click 连点」才去重；其余动作（勾↔取消、radio 改选、两次不同值提交）
     * 一律保留（P0-3）。抽成纯函数便于回归测试，不依赖 Playwright / 面板。
     */
    static boolean isRepeatedClick(String previous, String current, String verb) {
        return isRepeatedClick(previous, current, verb, null);
    }

    /**
     * 「同一动作签名紧邻重复」的纯连点去重。
     *
     * <p>只对<b>左键单击</b>成立：右键 / 双击是用户的离散明确动作（右击两次 = 两次 {@code rightClick()}），
     * 去重会把第二次静默吞掉。此前只按字段模板的 {@code verb} 判断，而右键的 verb 同样是 {@code click}，
     * 因此「右键两次」会被误当成连点去重。
     */
    static boolean isRepeatedClick(String previous, String current, String verb, String actionType) {
        if ("contextmenu".equals(actionType) || "dblclick".equals(actionType)) return false;
        return previous != null && previous.equals(current) && "click".equals(verb);
    }

    /**
     * 按「本次操作」而非「字段的固定模板」生成动作：checkbox 连续勾选→取消时，
     * 两次点击必须分别产出 {@code check()} / {@code uncheck()}。
     */
    static String actionForPick(CodegenFieldSpec spec, Pick p) {
        if (spec == null || p == null || p.entry == null) return spec == null ? "" : spec.action;
        String role = p.entry.role == null ? "" : p.entry.role.toLowerCase(java.util.Locale.ROOT);
        // 取单项前缀统一走 spec.listTarget()：自动成组的序号是用户实际点的那一项，绝不能在各分支各写 get(0)。
        String target = spec.listTarget();
        if ("checkbox".equals(role) && "click".equals(p.actionType)) {
            return target + (p.entry.checked ? "check()" : "uncheck()");
        }
        // 右键 / 双击：字段模板只承载 click 语义，这两个动作必须由本次录制动作决定。
        // （右键走 contextmenu 事件，不会与 click 冲突；双击的时间线合并见 dblclick 说明。）
        if ("contextmenu".equals(p.actionType)) {
            return target + "rightClick()";
        }
        if ("dblclick".equals(p.actionType)) {
            return target + "doubleClick()";
        }
        // 文件选择：fakepath 不可回放，产物给可编译的 uploadFile 占位 + TODO，由人工填资源路径。
        if ("file".equals(p.actionType)) {
            return target + "uploadFile(\"TODO_REPLACE_WITH_RESOURCE_NAME\")";
        }
        // 按本次操作值生成 fill / selectByValue：字段模板里存的是「末值」，
        // 无法代表历史上每一次填写（填 A 提交 → 填 B 提交，两次的实参必须各自不同）。
        if ("fill".equals(p.actionType) && p.actionValue != null) {
            if ("select".equals(p.entry.tag)) {
                return p.actionValue.isEmpty() ? spec.action
                        : target + "selectByValue(\"" + CodegenFieldPlanner.escapeJava(p.actionValue) + "\")";
            }
            if ("fill".equals(spec.verb)) {
                return target + "fill(\"" + CodegenFieldPlanner.escapeJava(p.actionValue) + "\")";
            }
        }
        // 键盘语义事件（Enter / Escape / Tab）：按本次按键生成可回放的 press(...)，
        // 而不是复用字段模板里的 fill/click。按键名限定为字母，杜绝拼接注入。
        if (p.actionType != null && p.actionType.startsWith("press:")) {
            String key = p.actionType.substring("press:".length());
            // 允许 "Control+Enter" 这类组合键；仍只接受字母与 '+'，杜绝拼入引号/分号。
            if (key.matches("[A-Za-z+]+")) {
                return target + "press(\"" + key + "\")";
            }
        }
        return spec.action;
    }

    private static CodegenFieldSpec fieldSpecFor(CodegenFieldPlanner.Plan plan, CodegenEntry entry) {
        if (plan == null || plan.fields == null || entry == null) return null;
        for (CodegenFieldSpec f : plan.fields) {
            if (f.entry == entry) return f;   // 快路径：同一对象（常规首次拾取）
        }
        // 动作型条目 / 勾选态派生条目是原条目的不可变副本，引用不同但 sigKey 相同；
        // plan 里每个 sigKey 唯一，故按 sigKey 回查即可，不必放宽成"按名字猜"。
        for (CodegenFieldSpec f : plan.fields) {
            if (f.entry != null && java.util.Objects.equals(f.entry.sigKey, entry.sigKey)) return f;
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
     * <p>复核不通过<b>且此刻的 DOM 仍是拾取时的 DOM</b> ⇒ 该条目回退 CSS，并推面板就地修订该行（不新增行），
     * 随后重生成代码；若页面已经推进（拾取元素已消失/被隐藏），此刻的命中数说明不了运行期那一步，
     * 则<b>保留 role</b> 并标为未校验（判据见 {@link RoleVerificationPolicy#fallBackToCssAfterMismatch}）。
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
                    log.warn("[codegen][cdp] group role strategy mismatch (base={}) - falling back to CSS for the whole group",
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
            // 引擎不可用 ≠ 确认唯一：显式记 UNDETERMINABLE，生成侧据此产出 TODO，而不是静默放行。
            pk.entry = entry.withRoleHits(CodegenEntry.ROLE_HITS_UNDETERMINABLE);
            log.warn("[codegen][cdp] deferred role verification undeterminable for #{} (role={}, name='{}') - "
                    + "keeping ROLE strategy but flagging it as unverified", pk.id, entry.role, entry.name);
            return;
        }
        if (RoleVerificationPolicy.keepRole(hits, 1)) {
            // 复核通过才第一次知道 role 定位器的真实命中数：回填 roleHits（CSS 的 matchCount 不动，两者独立）。
            pk.entry = entry.withRoleHits(hits);
            log.debug("[codegen][cdp] deferred role verification passed for #{} (role={}, name='{}', hits={})",
                    pk.id, entry.role, entry.name, hits);
            return;
        }
        if (keepRoleBecausePageMovedOn(pk, entry, hits)) return;
        reviseToCss(panel, pk, hits, "expected exactly 1 hit");
    }

    /**
     * 复核不通过时，先判断「此刻的页面还是拾取时的页面吗」。
     *
     * <p>多步向导（HSBC 重置密码）里，用户点了 Continue 之后上一步的按钮就被 {@code display:none} 隐藏；
     * 等到生成时再复核，命中 0 只是"页面翻页了"，与定位器可用性无关。此时<b>保留 role</b> 并把
     * {@code roleHits} 标成不可判定（生成侧会补 {@code TODO(review)} 注释），绝不改写成结构 CSS。
     *
     * @return {@code true} 表示已按「保留 role + 未校验」处置，调用方不得再回退 CSS
     */
    private static boolean keepRoleBecausePageMovedOn(Pick pk, CodegenEntry entry, int hits) {
        TriState intact = (pk.extractor == null)
                ? TriState.UNKNOWN : pk.extractor.isPickStateIntact(entry);
        if (RoleVerificationPolicy.fallBackToCssAfterMismatch(hits, intact)) return false;
        pk.entry = entry.withRoleHits(CodegenEntry.ROLE_HITS_UNDETERMINABLE);
        log.warn("[codegen][cdp] role locator unverifiable for #{} (hits={}, role={}, name='{}', "
                        + "pickStateIntact={}) - the page has moved on since the pick, "
                        + "so this count does not reflect the step where the locator will be used; "
                        + "keeping ROLE strategy and flagging it as unverified instead of degrading to CSS",
                pk.id, hits, entry.role, entry.name, intact);
        return true;
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
            for (Pick pk : group) {
                if (pk.entry != null) pk.entry = pk.entry.withRoleHits(CodegenEntry.ROLE_HITS_UNDETERMINABLE);
            }
            log.warn("[codegen][cdp] group role verification undeterminable (base={}, picked={}, role={}, "
                    + "name='{}') - keeping ROLE strategy but flagging it as unverified",
                    base, members, entry.role, entry.name);
            return;
        }
        if (RoleVerificationPolicy.keepRole(hits, members)) {
            // 组内成员共享同一个 role 定位器，命中数一致，逐条回填 roleHits。
            for (Pick pk : group) {
                if (pk.entry != null) pk.entry = pk.entry.withRoleHits(hits);
            }
            log.debug("[codegen][cdp] group role verification passed (base={}, picked={}, hits={})",
                    base, members, hits);
            return;
        }
        if (RoleVerificationPolicy.fallBackToCssAfterMismatch(hits, groupPickStateIntact(group))) {
            log.warn("[codegen][cdp] group role locator mismatched (base={}, picked={}, hits={}) - "
                            + "group element count differs from engine hits (invisible elements excluded / unpicked peers present), "
                            + "index unreliable, falling back to CSS for the whole group",
                    base, members, hits);
            for (Pick pk : group) {
                reviseToCss(panel, pk, hits, "group picked=" + members);
            }
            return;
        }
        // 整组已不在拾取态（多步向导翻页会把上一步的按钮一起 display:none）：命中 0 只说明"现在不是那一步"，
        // 组内索引语义仍然成立。整组统一保留 role 并标未校验，避免把一组可靠定位器整体退化成结构 CSS。
        for (Pick pk : group) {
            if (pk.entry != null) pk.entry = pk.entry.withRoleHits(CodegenEntry.ROLE_HITS_UNDETERMINABLE);
        }
        log.warn("[codegen][cdp] group role locator unverifiable (base={}, picked={}, hits={}, role={}, name='{}') - "
                        + "the page has moved on since the pick, so this count does not reflect the step where the "
                        + "locator will be used; keeping ROLE strategy for the whole group and flagging it as "
                        + "unverified instead of degrading to CSS",
                base, members, hits, entry.role, entry.name);
    }

    /**
     * 一组元素此刻是否仍全部处于拾取态（仍存在且可见）。
     *
     * <p>判据取"全部未变"才算未变：只要有一个成员已消失/被隐藏，就说明页面已经推进，此刻的命中数
     * 不能代表定位器在运行期那一步的表现；而只要有一个成员不可判定，整组也就不能断言"状态未变"，
     * 故返回 {@link TriState#UNKNOWN}（与单元素的保守口径一致）。
     *
     * @return {@link TriState#TRUE}=整组状态未变；{@link TriState#FALSE}=至少一个成员已变；
     *         {@link TriState#UNKNOWN}=无法完整判定
     */
    private static TriState groupPickStateIntact(List<Pick> group) {
        boolean undeterminable = false;
        for (Pick pk : group) {
            TriState state = (pk.extractor == null || pk.entry == null)
                    ? TriState.UNKNOWN : pk.extractor.isPickStateIntact(pk.entry);
            if (state.isFalse()) return TriState.FALSE;
            if (state.isUnknown()) undeterminable = true;
        }
        return undeterminable ? TriState.UNKNOWN : TriState.TRUE;
    }

    /**
     * 点击当场完成 role 唯一性裁决（对标 page.pause 的「悬浮即校验、点击即所用」）。
     *
     * <p>历史实现把复核推迟到生成前，导致「拾取时 modal 未开 → role 唯一；生成时 modal 已开 →
     * hits=2 回退到一条已漂移的位置链」。现在点击落库前就用 Playwright 引擎复核一次：
     * <ul>
     *   <li>{@code hits == 1} ⇒ 保留 role 并回填真实命中数；</li>
     *   <li>{@code hits < 0} ⇒ 不可判定，保留 role 但标注未校验（生成侧补 TODO）；</li>
     *   <li>其余（0 / >1）⇒ 先看是不是"页面已翻页"（可回归用旧值判断），否则当场回退 CSS。</li>
     * </ul>
     * 复核失败即清掉 {@code rolePending}，生成期的 {@code verifyPendingRoles} 不会再重复复核。
     */
    private static void verifyRoleAtPickTime(CodegenPanelHandle panel, Pick pk) {
        if (pk == null || !pk.rolePending || pk.extractor == null) return;
        pk.rolePending = false;   // 先消欠账：无论结论如何，这次点击已完成复核
        CodegenEntry entry = pk.entry;
        int hits = pk.extractor.countRoleMatches(entry);
        if (hits < 0) {
            pk.entry = entry.withRoleHits(CodegenEntry.ROLE_HITS_UNDETERMINABLE);
            log.warn("[codegen][cdp] role verification undeterminable at pick time for #{} (role={}, name='{}')"
                    + " - keeping ROLE but flagging it as unverified", pk.id, entry.role, entry.name);
            return;
        }
        if (RoleVerificationPolicy.keepRole(hits, 1)) {
            pk.entry = entry.withRoleHits(hits);
            log.debug("[codegen][cdp] role verified at pick time for #{} (role={}, name='{}', hits=1)",
                    pk.id, entry.role, entry.name);
            return;
        }
        // 0 命中且拾取元素此刻已不可见 ⇒ 多半是"页面翻页了"，保留 role 并标 TODO，
        // 不当成定位器失效（与生成期兜底同一启发式）。>1 命中必回退 CSS。
        if (keepRoleBecausePageMovedOn(pk, entry, hits)) return;
        reviseToCss(panel, pk, hits, "expected exactly 1 hit (pick-time verification)");
    }

    /**
     * role 唯一性裁决的<b>共用判据</b>：悬浮预览与点击落库必须走这同一条路径，结论才不会表里不一。
     *
     * <p>返回裁决后的 entry：
     * <ul>
     *   <li>role 唯一 ⇒ 原 entry 回填 {@code roleHits=命中数}；</li>
     *   <li>引擎不可判定 ⇒ 原 entry 标 {@code UNDETERMINABLE}（保留 role）；</li>
     *   <li>不唯一 ⇒ 回退 CSS，标 {@code NOT_APPLICABLE} 并用 CSS 引擎重新计数。</li>
     * </ul>
     * 悬浮 tooltip 显示的就是本方法返回的 {@code selector}，点击直接缓存消费同一条。
     *
     * @param entry     待裁决的条目（悬浮解析结果 / 点击拾取结果）
     * @param panel     仅回退时需要推面板就地修订；悬浮预览传 null
     * @param pk        仅回退时需要（拿到 id 做面板通知）；悬浮预览传 null
     * @param extractor role / CSS 计数用的 CDP 抽取器（两个调用点都非 null）
     * @param context   日志上下文（"hover preview" / "pick-time verification"）
     */
    private static CodegenEntry adjudicateRole(CodegenEntry entry, CodegenPanelHandle panel, Pick pk,
                                               CodegenCdpNodeExtractor extractor, String context) {
        if (entry == null || extractor == null) return entry;
        // 非 role 策略（纯 CSS / 锚定 XPath）无需裁决。
        if (!LocatorStrategy.ROLE.equals(entry.strategy)
                && !LocatorStrategy.ROLE_WITH_NAME.equals(entry.strategy)) return entry;
        int hits = extractor.countRoleMatches(entry);
        if (hits < 0) {
            log.debug("[codegen][{}] role verification undeterminable (role={}, name='{}') - keeping ROLE",
                    context, entry.role, entry.name);
            return entry.withRoleHits(CodegenEntry.ROLE_HITS_UNDETERMINABLE);
        }
        if (RoleVerificationPolicy.keepRole(hits, 1)) {
            return entry.withRoleHits(hits);
        }
        // 回退 CSS：与 reviseToCss 同一构造（重新计数 + 标不适用），悬浮/点击逐字一致。
        int cssHits = extractor.countCssMatches(entry.cssSelector);
        CodegenEntry revised = RoleRevision.toCss(entry)
                .withMatchCount(cssHits)
                .withRoleHits(CodegenEntry.ROLE_HITS_NOT_APPLICABLE);
        log.info("[codegen][{}] role rejected (hits={}, role={}, name='{}') - revised to CSS '{}' (engineHits={})",
                context, hits, entry.role, entry.name, revised.selector, cssHits);
        if (panel != null && pk != null) panel.onPickedRevised(pk.id, revised, "hits=" + hits + ", " + context);
        return revised;
    }

    /** 回退 CSS 并推面板就地修订该行（不新增行）。 */
    private static void reviseToCss(CodegenPanelHandle panel, Pick pk, int hits, String because) {
        CodegenEntry entry = pk.entry;
        // 回退 CSS 后「最终定位器」变成了这条 CSS：用 CSS 引擎重新计数（拾取时的是快照，页面可能已变），
        // 并把 roleHits 标为不适用，使 effectiveMatchCount() 自动改读 matchCount。
        int cssHits = pk.extractor == null ? -1 : pk.extractor.countCssMatches(entry.cssSelector);
        CodegenEntry revised = RoleRevision.toCss(entry)
                .withMatchCount(cssHits)
                .withRoleHits(CodegenEntry.ROLE_HITS_NOT_APPLICABLE);
        pk.entry = revised;
        if (panel != null) {
            panel.onPickedRevised(pk.id, revised, "hits=" + hits + ", " + because);
        }
        log.info("[codegen][cdp] role locator rejected for #{} (hits={}, role={}, name='{}', {}) - "
                        + "strategy revised to CSS ('{}', engineHits={})",
                pk.id, hits, entry.role, entry.name, because, revised.selector, cssHits);
        // 唯一性的最终裁判是 Playwright 引擎（page.locator(...).count()），不是页面侧 document.querySelectorAll：
        // 前者穿透 open shadow root、按 frame 语义计数；后者只看当前 document。两者不一致时以引擎为准，
        // 并且必须显式告警——否则用户会在回放阶段才发现「这条链一个也命中不到」。
        if (cssHits == 0) {
            log.warn("[codegen][cdp] revised CSS for #{} matches 0 element(s) right now: '{}' "
                    + "- 该定位器当前失效（位置链随页面推进已漂移），回放前务必人工复核", pk.id, revised.selector);
        } else if (cssHits > 1) {
            log.warn("[codegen][cdp] revised CSS for #{} matches {} element(s) right now: '{}' "
                    + "- Playwright strict mode 会抛错，回放前务必收窄", pk.id, cssHits, revised.selector);
        }
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
