package com.hsbc.cmb.hk.dbb.automation.framework.web.page.scan.engine;

import com.google.gson.reflect.TypeToken;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Frame;
import com.microsoft.playwright.Page;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.element.RoleElement;
import com.hsbc.cmb.hk.dbb.automation.framework.web.utils.NLSUtils;
import com.hsbc.cmb.hk.dbb.automation.framework.common.config.ConfigRegistry;
import com.hsbc.cmb.hk.dbb.automation.framework.common.config.ConfigReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.microsoft.playwright.Frame;
import java.util.*;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.scan.model.*;

// 快照解析簇（T5-1 拆分）：readPickSnapshot/stopAndRead/parsePickSnapshot（关闭页 step/op 恢复，readback 非 JS 捕获）
// 已下沉至 RolePickerSnapshotParser，此处桥接保持 runPickerCommand / getSteps 的原调用形态（行为零回归）。
import static com.hsbc.cmb.hk.dbb.automation.framework.web.page.scan.engine.RolePickerSnapshotParser.readPickSnapshot;
import static com.hsbc.cmb.hk.dbb.automation.framework.web.page.scan.engine.RolePickerSnapshotParser.stopAndRead;
import static com.hsbc.cmb.hk.dbb.automation.framework.web.page.scan.engine.RolePickerSnapshotParser.parsePickSnapshot;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.scan.model.RoleEntry;

/**
 * 交互式拾取器：像 {@code page.pause()} 一样，在浏览器里点击想要的元素，
 * 自动抓取被点元素的 a11y role / name，再交给 {@link RoleElementPageGenerator} 生成 {@code @RoleElement} 代码。
 *
 * <p>与整页 {@code dumpAccessibilityRoles} 不同，本类只采集"用户主动点击"的元素，
 * 适合页面导航/列表很冗杂、只需其中少数几个控件的场景。
 *
 * <h3>原理</h3>
 * 在页面注入捕获阶段的 click / keydown 监听：点击任意元素即拦截其默认行为（避免跳转），
 * 用 {@code element.computedRole}/{@code element.computedName}（带回退）取出 a11y 信息并暂存；
 * 按 ESC 结束拾取。测试线程在 {@code pick(...)} 内阻塞等待，用户在真实浏览器中点选。
 *
 * <h3>前置</h3>
 * 须在<b>有头（headed）</b>浏览器中运行，且页面已导航到目标页。
 *
 * <pre>
 * Page pw = loginPage.getPage();
 * List&lt;RoleEntry&gt; picks = RoleElementPicker.pick(pw);
 * RoleElementPageGenerator.write(picks, "src/test/java",
 *         "com.hsbc...tests.pages", "LoginPage", "nls/login.nls.json");
 * // 或一步到位：
 * RoleElementPicker.pickAndWrite(pw, "src/test/java",
 *         "com.hsbc...tests.pages", "LoginPage", "nls/login.nls.json");
 * </pre>
 */
public final class RoleElementPicker {

    static final Logger log = LoggerFactory.getLogger(RoleElementPicker.class);

    /**
     * 上下文级"当前会话"桥（对齐 {@code page.pause()} 的 DebugController/Recorder 解耦）：
     * 命令桥/拾取桥不再逐页 {@code exposeFunction}，而是对 {@link BrowserContext} 一次性
     * {@code exposeBinding}——context 下所有当前与未来页面（弹窗/新标签页）、每次导航后的新文档
     * 都自动持有绑定，由 {@code BindingCallback.Source#page()} 天然区分"哪个页面发起"。
     * 同名绑定不可重复注册（重复会抛 {@code PlaywrightException}），故每个 context 仅注册一次；
     * 二次打开（同一 context 再次 {@code openPanel}）只更新下方 Map 指向的"当前会话"队列/状态，
     * 回调动态读取，避免命令/拾取被投递到已失效的旧会话队列。
     */

    /** 在 BasePage.closeCurrentPage 调 page.close() 前调用：标记本页为"框架主动关闭"。 */
    public static void markFrameworkClose(Page page) {
        RolePickerSessionState.markFrameworkClose(page);
    }

    /**
     * 彻底清理指定 BrowserContext 在所有静态 Map 中的状态。
     * <p>
     * 调用时机：BrowserContext 关闭之后（PlaywrightManager.closeContext 钩子 / @AfterMethod / hook）。
     * 否则长跑测试中累积的 CTX_* Map 会导致内存泄漏、上下文复活后命中陈旧状态。
     * </p>
     */
    public static void cleanupContext(BrowserContext ctx) {
        RolePickerSessionState.cleanupContext(ctx);
    }

    /** 清理指定 Page 的 page-level 状态（frameNavigated 跟踪）。 */
    public static void cleanupPage(Page page) {
        RolePickerSessionState.cleanupPage(page);
    }

    /** 清空所有静态 Map —— 主要用于 JVM 关闭或测试集群重置。 */
    public static void clearAll() {
        RolePickerSessionState.clearAll();
    }

    // 同页并发 evaluate 串行化（T5-1 ⑤）：Playwright Page 非线程安全，主循环线程与 Playwright 事件线程
    // 可能在同一 Page 上并发调用 evaluate。按 Page 加锁串行化，避免协议层交错/覆盖；
    // 锁仅在 evaluate 调用期间持有，evaluate 返回后才触发导航回调，回调不会在持锁期间运行，故不会自死锁。
    //
    //  CT2-09 补全（doc 21：机制已补齐，但"仍为强引用键 + 依赖受 isCodegenEnabled() 门控的清理钩子 + 无弱引用兜底"）：
    //    改为【弱引用键】的同步 Map。清理钩子（releaseEvalLocksForPage / ForContext，接在 cleanupPage
    //    与 context close 上）负责"应清能清"；弱键兜底负责"任何路径漏清也不会变成永久泄漏"——
    //    典型缺口：cleanupContext 受 isCodegenEnabled() 门控，若运行中开启 codegen、收尾前把配置关掉，
    //    清理即被绕过，强键下这些 Page/Frame 会永久驻留并阻止其回收（长跑套件累积）。
    //  安全性：调用方在 pickerEval 期间始终【强持有】该 Page/Frame 引用 → 键不会被中途回收，
    //    "同一 Page 拿到同一把锁"的串行化保证不受影响；值是无反向引用的裸 Object，不会阻止键回收。
    //  代价：evalLockOf 查表走单一监视器（仅"查表"期间持锁，不含 evaluate 本身），对 codegen 工具可忽略。
    private static final Map<Object, Object> EVAL_LOCKS = Collections.synchronizedMap(new WeakHashMap<>());
    private static Object evalLockOf(Object key) { return EVAL_LOCKS.computeIfAbsent(key, k -> new Object()); }

    /** CT2-09：当前 evaluate 串行锁条目数（可观测 —— 非零且持续增长即说明清理钩子未被触达）。 */
    static int evalLockCount() { return EVAL_LOCKS.size(); }

    /**
     * CT2-09：释放与指定 Page 相关的 evaluate 串行锁（键可能是该 Page 本身，也可能是其上的 Frame）。
     *
     * <p>{@code EVAL_LOCKS} 以 Page/Frame <b>强引用</b>为键且此前<b>只增不减</b>：长跑套件下每个
     * 用过 {@code pickerEval} 的页面都会永久占用一条记录，并阻止已关闭的 Page 被回收。
     */
    static void releaseEvalLocksForPage(Page page) {
        if (page == null) {
            return;
        }
        EVAL_LOCKS.keySet().removeIf(k -> {
            if (k == null || k == page) {
                return true;
            }
            try {
                return k instanceof Frame && ((Frame) k).page() == page;
            } catch (Exception ignore) {
                return true; // 句柄已失效，保守清理
            }
        });
    }

    /** CT2-09：释放属于指定 Context 的全部 evaluate 串行锁（context 关闭钩子入口）。 */
    static void releaseEvalLocksForContext(BrowserContext ctx) {
        if (ctx == null) {
            return;
        }
        EVAL_LOCKS.keySet().removeIf(k -> {
            if (k == null) {
                return true;
            }
            try {
                if (k instanceof Page) {
                    return ((Page) k).context() == ctx;
                }
                if (k instanceof Frame) {
                    return ((Frame) k).page().context() == ctx;
                }
                return false;
            } catch (Exception ignore) {
                return true; // 句柄已失效，保守清理
            }
        });
    }

    /** CT2-09：清空全部 evaluate 串行锁（JVM 关闭 / 集群重置）。 */
    static void clearEvalLocks() {
        EVAL_LOCKS.clear();
    }
    /** §11 修复：page.evaluate 无超时且锁内执行，页面卡死会永久持锁、拖死同页所有 evaluate。
     *  改为提交独立 daemon 线程 + 有界 Future.get；超时即抛语义化异常并释放锁，不阻塞后续调用。
     *
     *  <p>【默认值从 30s 降到 10s】30s 只是当年"evaluate 完全没有超时"的兜底，对交互式工具没有意义：
     *  30s 前的那次点击早已过去；而 pickerEval 是<b>持锁等待</b>的，一次 30s 超时会把整轮主循环
     *  （页类刷新 / 计数器垫高 / 面板回灌）一起卡死 —— 现场三症状（回上一页后页类错、序号又从 1、
     *  元素恢复慢）正由此产生。故：本常量（用户显式命令，如 开始/停止并生成）取 10s；主循环里那些
     *  <b>幂等、每轮重做</b>的调用一律走 {@link #pickerEvalSoft} 的 2s。两者都可用系统属性覆盖。 */
    private static final long PICKER_EVAL_TIMEOUT_MS =
            Integer.getInteger("codegen.picker.eval.timeout.ms", 10_000);

    static Object pickerEval(Page page, String script) {
        return evalWithTimeout(evalLockOf(page), () -> page.evaluate(script));
    }
    static Object pickerEval(Page page, String script, Object arg) {
        return evalWithTimeout(evalLockOf(page), () -> page.evaluate(script, arg));
    }
    static Object pickerEval(Frame frame, String script) {
        return evalWithTimeout(evalLockOf(frame), () -> frame.evaluate(script));
    }
    static Object pickerEval(Frame frame, String script, Object arg) {
        return evalWithTimeout(evalLockOf(frame), () -> frame.evaluate(script, arg));
    }

    /**
     * 「可牺牲/幂等」evaluate 的短超时（默认 2s，可由 {@code -Dcodegen.picker.eval.softTimeout.ms} 覆盖）。
     *
     * <p>用于主循环里那些**每轮都会重做、丢掉也无害**的动作（边拾边生成的代码回填、面板回灌等）：
     * 页面在整页导航瞬间 evaluate 必然抖动甚至挂住，若这些动作沿用 30s 默认超时，会**在按页 eval 锁内
     * 长期占用**，把同一轮里的关键动作（页类刷新、计数器垫高、监听重挂）一起拖住。实测现场：一次
     * auto-generate step 的 evaluate 超时 30s ⇒ 主循环停摆整整 30s ⇒ 期间返回上一页拾取的元素被贴上旧页类、
     * 且新文档计数器无从垫高又从 1 起号（用户反馈"元素恢复很慢 / 又从 1 编号 / 元素归到了上一页"）。
     */
    private static final long PICKER_EVAL_SOFT_TIMEOUT_MS =
            Integer.getInteger("codegen.picker.eval.softTimeout.ms", 2_000);

    /** 短超时版 evaluate：只用于"幂等、可重做"的调用点，避免慢/挂页把主循环拖住。 */
    static Object pickerEvalSoft(Page page, String script) {
        return evalWithTimeout(evalLockOf(page), () -> page.evaluate(script), PICKER_EVAL_SOFT_TIMEOUT_MS);
    }

    static Object pickerEvalSoft(Page page, String script, Object arg) {
        return evalWithTimeout(evalLockOf(page), () -> page.evaluate(script, arg), PICKER_EVAL_SOFT_TIMEOUT_MS);
    }

    /** 短超时版（Frame 重载）：用于主循环里"每轮重做、丢掉无害"的子 frame 读取/注入。 */
    static Object pickerEvalSoft(Frame frame, String script) {
        return evalWithTimeout(evalLockOf(frame), () -> frame.evaluate(script), PICKER_EVAL_SOFT_TIMEOUT_MS);
    }

    /**
     * 自定义超时版（Frame 重载）：用于「协议级读回子 frame 拾取」这类<b>纯兜底</b>动作。
     *
     * <p>拾取数据本身已由 {@code __roleOnPick}（binding + console 两路）**事件上行**，读回只是兜底，
     * 故它必须比常规软超时（2s）更短：页面上的广告/跟踪 frame 常因脚本阻塞而 evaluate 挂住，
     * 逐个吃满 2s 会累积成十几到几十秒的占用（现场"新页面回灌 10~20s"的主因）。
     */
    static Object pickerEvalSoft(Frame frame, String script, long timeoutMs) {
        return evalWithTimeout(evalLockOf(frame), () -> frame.evaluate(script), timeoutMs);
    }

    static Object pickerEvalSoft(Frame frame, String script, Object arg) {
        return evalWithTimeout(evalLockOf(frame), () -> frame.evaluate(script, arg), PICKER_EVAL_SOFT_TIMEOUT_MS);
    }

    private static Object evalWithTimeout(Object lock, Supplier<Object> eval) {
        return evalWithTimeout(lock, eval, PICKER_EVAL_TIMEOUT_MS);
    }

    private static Object evalWithTimeout(Object lock, Supplier<Object> eval, long timeoutMs) {
        FutureTask<Object> task = new FutureTask<>(eval::get);
        Thread t = new Thread(task, "picker-eval");
        t.setDaemon(true);
        synchronized (lock) {
            t.start();
            try {
                return task.get(timeoutMs, TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                throw new IllegalStateException(
                        "pickerEval timed out after " + timeoutMs + "ms (page may be hung)", e);
            } catch (Exception e) {
                throw new IllegalStateException("pickerEval failed", e);
            }
        }
    }

    /**
     * 取任意一个<b>仍存活</b>的被跟踪页面；没有则返回 {@code null}。
     *
     * <p><b>要防的缺陷（CG-C2）</b>：调用方原先写 {@code pageNames.keySet().iterator().next()}，
     * 一旦 {@code pageNames} 为空（stop / 命令异常路径下页面已全部关闭或尚未登记）就抛
     * {@link java.util.NoSuchElementException}，而该异常会撕裂面板主循环 —— 表现为"点了停止却卡住/没反应"。
     * 本方法已容忍 null/空 pageNames（内部 early-return），
     * 故此处只负责"安全地挑一个页面或 null"。</p>
     */
    static Page anyLivePage(Map<Page, String> pageNames) {
        if (pageNames == null || pageNames.isEmpty()) {
            return null;
        }
        for (Page p : pageNames.keySet()) {
            if (p != null && !p.isClosed()) {
                return p;
            }
        }
        return null;
    }
    // 上下文级初始化脚本守卫：面板脚本每 context 仅注册一次；拾取脚本按 nls 内容变化才追加注册
    // （addInitScript 无法撤销，重复注册会累积执行；同 nls 幂等跳过，不同 nls 追加后"后注册者后执行"覆盖生效）。








    // 【已移除】registerContextInitScripts(BrowserContext)：
    // 它把面板引导脚本与面板重建脚本经 addInitScript 注册到【被测页】的 context，
    // 使每个被测页/每次导航都重建一份面板 DOM。面板搬到独立窗口（RolePickerPanelHost）后，
    // 被测页不再承担任何面板注入——且按 CDP 探针结论，注入式面板在 inspect 期间必然
    // 「被抓成拾取元素 + 停止按钮点不动」。面板脚本改由 RolePickerPanelHost#open 注入面板窗口。




    /**
     * 判断是否处于 CI 运行环境（如 Jenkins / GitHub Actions / GitLab CI / TeamCity）。
     * 拾取面板是本地开发辅助工具，会在 {@link #openPanel}/{@link #start} 内等待人工拾取，
     * 在 CI 自动化中不应打开（否则测试会卡住等待人工交互）。通过常见 CI 环境变量判断。
     */
    static boolean isCiRun() {
        return System.getenv("JENKINS_URL") != null
                || System.getenv("WORKSPACE") != null
                || System.getenv("BUILD_NUMBER") != null
                || "true".equalsIgnoreCase(System.getenv("CI"))
                || System.getenv("GITLAB_CI") != null
                || System.getenv("GITHUB_ACTIONS") != null
                || System.getenv("TEAMCITY_VERSION") != null;
    }

    /**
     * CT2-25：交互式等待（拾取完成 / 代码面板关闭）的超时上界（毫秒）。
     *
     * <p>原实现两处 {@code waitForFunction} 用 {@code setTimeout(0)} = <b>永不超时</b>：
     * 用户遗忘拾取会话时，调用线程被<b>永久阻塞</b>（只能杀进程）。现改为读
     * {@link ConfigRegistry#PICKER_WAIT_TIMEOUT_MS}（默认 30 分钟）；超时后走既有 catch 分支
     * —— 拾取降级为「生成已拾取子集」、代码面板静默结束，不再无限挂起。
     *
     * <p>配置值 {@code ≤0} 时返回 0（维持「不超时」原语义），作为受控本地调试的逃生门。
     */
    private static double pickerWaitTimeoutMs() {
        String raw = ConfigReader.resolve(ConfigRegistry.PICKER_WAIT_TIMEOUT_MS.key(),
                ConfigRegistry.PICKER_WAIT_TIMEOUT_MS.defaultValue());
        try {
            double v = Double.parseDouble(raw.trim());
            return v > 0 ? v : 0;
        } catch (Exception e) {
            return Double.parseDouble(ConfigRegistry.PICKER_WAIT_TIMEOUT_MS.defaultValue());
        }
    }

    /**
     * 是否启用 codegen（页面对象自动生成）的运行时协作。
     * 默认关闭：框架热路径（页面关闭 / Context 关闭）不会触碰 codegen，
     * 避免对自动化测试引入无关开销与依赖。开发者在本地有头浏览器中需要使用
     * 拾取器时，设置系统属性 {@code -Ddbb.codegen.enabled=true} 即可激活。
     */
    public static boolean isCodegenEnabled() {
        return Boolean.getBoolean("dbb.codegen.enabled");
    }

    /** 反序列化任意拾取态 JSON 的精确泛型类型，避免 {@code fromJson(x, Map.class)} 的未检查转换 */
    static final java.lang.reflect.Type MAP_STRING_OBJECT_TYPE =
            new TypeToken<java.util.Map<String, Object>>() {}.getType();



    /**
     * 统一处理面板命令（start/stop/abort/done）：开始拾取、收集并生成代码、终止、关闭。
     * 结果由调用方用 {@code setStatus/fillCode} 呈现到面板 UI。
     *
     * @return 含后续动作与（stop 时的）生成代码
     */
    static PickerResult runPickerCommand(RolePickerContext ctx, Page page, String cmd) {
        return RolePickerCommandEngine.runPickerCommand(ctx, page, cmd);
    }

    /**
     * 用户在面板点击『终止运行』时抛出，用于中断调用方后续代码执行。
     * 调用方可按需捕获以决定是失败退出还是降级处理。
     */
    public static final class PickerAbortedException extends RuntimeException {
        public PickerAbortedException(String message) {
            super(message);
        }
    }

    private RoleElementPicker() {}

    /** 开启拾取模式（手动控制起止时可单独调用，不预加载 nls 反向查表） */
    public static void start(Page page) {
        if (isCiRun()) {
            log.info("[picker] CI runtime detected, skipping picker mode (start).");
            return;
        }
        start(page, "{}");
    }

    /**
     * 开启拾取模式，并预加载 nls 反向查表（JSON：规范化文本值 → key）。
     * 拾取交互角色元素时，会用 a11y name 反查该表，命中则直接用真实 nls key。
     *
     * @param page              Playwright Page
     * @param nlsReverseJson    nls 反向查表 JSON；为 null/空/"{}" 时退化为不反查（回退 slug）
     *
     * <p>2026-10-10：删除原 3 参重载 {@code start(Page, String, String rootSelector)}（「区域扫描」遗留，
     * 全仓零调用方），方法体已内联至本重载；注入参数不再包含 {@code root} 键 —— JS 侧
     * {@code apply-pick-state-js.js} 已把 {@code window.__rolePickRoot === undefined} 归一为
     * {@code null}，故行为与旧版显式传 {@code null} 完全一致。</p>
     */
    public static void start(Page page, String nlsReverseJson) {
        // CI 环境：拾取模式是本地开发工具，自动化测试里不应开启并等待人工拾取，直接跳过。
        if (isCiRun()) {
            log.info("[picker] CI runtime detected, skipping picker mode (start).");
            return;
        }
        // V2（CDP-only）：元素捕获由下方每页 CDPSession 的 Overlay.inspectNodeRequested 通道承担。
        // 注：面板自举脚本【不再】注册到被测页的 context——面板已搬到独立窗口
        // （RolePickerPanelHost），被测页不再注入任何面板脚本。
        log.info("[picker] Picker mode enabled (CDP capture): click elements in the browser to pick, "
                + "press ESC or click Stop in the panel to finish.");
        // CDP 捕获层（V2 默认且唯一的拾取通道）：每页建立 CDPSession 并进入 inspect 模式，
        // 与 page.pause 的 Inspector 同源；用户在页面上点选经由 Overlay.inspectNodeRequested 上报
        // backendNodeId，由 RolePickerCaptureDispatcher 反查为 RoleEntry 并写入权威态 javaPickBySig。
        // （原 JS 注入捕获通道已退役：registerFrameInjection / RolePickerScripts.START_INJECT_JS /
        //  __roleOnPick 浏览器上行 / RolePickerCdpConfig 开关均不再使用。）
        try {
            RolePickerCdpSession cdpSession = new RolePickerCdpSession(page);
            RolePickerCaptureDispatcher dispatcher = new RolePickerCaptureDispatcher();
            cdpSession.enableAndInspect(ev -> dispatcher.onInspect(cdpSession, ev));
            RolePickerSessionState.CTX_CDP_SESSIONS.put(page, cdpSession);
        } catch (Exception e) {
            log.warn("[picker][cdp] failed to start CDP capture on {}; element picking unavailable: {}", page.url(), e.getMessage());
        }
        // 【修复"点开始拾取按钮不变为停止按钮"】V2 的 cmdStart 未向面板推送激活态，__roleMode 停在 'idle'
        // （或仅靠点击时的乐观翻转、被面板重建打回）⇒ 按钮一直显示"▶ 开始拾取"。
        // 与停止路径（stopAndRead 注入 STOP_SCRIPT）对称：置 __roleMode='manual' + __rolePickSessionOn='1'
        // 并 __roleRefreshToggle()，使按钮权威地翻为"⏹ 停止拾取"（幂等；无面板时脚本内部自动跳过）。
        try { pickerEval(page, RolePickerScripts.SET_PICK_ACTIVE_AND_RENDER_JS); }
        catch (Exception ignore) { RolePickerQuiet.ignore("RoleElementPicker#activate", ignore); }
        // 【面板独立窗口】面板 UI 的按钮态（⏹ 停止拾取）与列表渲染在面板窗口侧，需同步置位一次。
        // 被测页那一次仍必须执行：它置的 window.__rolePickActive 正是 ESC 热键的闸（见 INSTALL_STOP_HOTKEY_JS）。
        try { RolePickerPanelHost.eval(RolePickerScripts.SET_PICK_ACTIVE_AND_RENDER_JS); }
        catch (Exception ignore) { RolePickerQuiet.ignore("RoleElementPicker#activatePanel", ignore); }
        // 【ESC 热键停止 —— 唯一确定可达的停止控制点】探针实测：inspect 期间被测页 frame 树内指针事件
        // 一律被浮层吞掉（mousemove 收不到 / click 触发不了 onclick / 子 iframe 也会被穿透抓取），
        // 故「面板上的停止按钮」在拾取中必然点不到；键盘事件不受影响 ⇒ ESC 等同点击「⏹ 停止拾取」。
        try { pickerEval(page, RolePickerScripts.INSTALL_STOP_HOTKEY_JS); }
        catch (Exception ignore) { RolePickerQuiet.ignore("RoleElementPicker#stopHotkey", ignore); }
    }

    /**
     * 把 nls 文件构建成"规范化后的文本值 → key"的反向查表 JSON（覆盖所有语言），
     * 供浏览器拾取时把 a11y name 反查为对应 nls key。文件缺失/解析失败时返回 "{}"（退化为不反查）。
     *
     * @param nlsFile nls 文件路径（classpath 相对或文件系统绝对），如 "nls/login.nls.json"
     */
    /**
     * 合并多个 nls 文件构建反向查表（覆盖所有语言）：精确表与模板表均跨文件合并，
     * 同一规范化文本/正则源以首个文件优先（putIfAbsent）。供拾取时把 a11y name 反查为对应 key，
     * 从而支持「一个页面用到多个 nls json」的场景。
     */

    /** 关闭拾取模式，清理注入的监听与提示条 */
    public static void stop(Page page) {
        // CI 环境：拾取模式本就不会开启（start/openPanel 均跳过），此处不注入任何代码。
        if (isCiRun()) {
            log.info("[picker] CI runtime detected, skipping stop injection (stop).");
            return;
        }
        // V2（CDP-only）：不再注入 JS 停止脚本；改为关闭每页 CDPSession（退出 inspect 模式、释放渲染进程通道）。
        // 面板（JS 渲染）自身的状态机由 RolePickerPanelController 经面板命令驱动，无需此处 JS 注入复位。
        try {
            RolePickerCdpSession cdp = RolePickerSessionState.CTX_CDP_SESSIONS.remove(page);
            if (cdp != null) {
                try { cdp.disable(); } catch (Exception ignore) { RolePickerQuiet.ignore("RoleElementPicker", ignore); }
                try { cdp.close(); } catch (Exception ignore) { RolePickerQuiet.ignore("RoleElementPicker", ignore); }
            }
        } catch (Exception ignore) { RolePickerQuiet.ignore("RoleElementPicker", ignore); }
    }

    /**
     * 读取当前已拾取的元素列表（不阻塞、不生成）。
     * 可在 {@link #start} / {@link #stop} 之间多次调用，实时查看进度。
     */
    /**
     * 读取当前已拾取的元素列表（不阻塞、不生成）。
     * V2（CDP-only）：直接读 Java 权威内存态 {@code ctx.javaPickBySig}（由 CDP 捕获经
     * {@link RolePickerCaptureDispatcher#onInspect} 即时写入），不再遍历各 frame 的
     * {@code window.__rolePicks}（JS 注入捕获通道已退役）。
     */
    public static List<RoleEntry> getEntries(Page page) {
        RolePickerContext ctx = RolePickerPageTracker.contextFor(page.context());
        if (ctx == null) return new ArrayList<>();
        synchronized (ctx.javaPickBySig) {
            return new ArrayList<>(ctx.javaPickBySig.values());
        }
    }


    /**
     * 把 Java 权威拾取内存态（javaPickBySig）同步到浏览器面板展示数组 window.__rolePicks 并触发渲染。
     * 修复"Java 内存态增长、浏览器实时面板空白"：浏览器侧 window.__rolePicks 因去重 / iframe / 导航时序
     * 未被可靠填充，导致点击时面板列表看不到已拾元素。此处以 Java 侧为准，保证面板实时反映已拾内容。
     * 仅用于面板展示；代码生成仍走 javaPickBySig（见 runPickerCommand），不受影响。
     */
    static void mergeFramePicksToMain(Page page, LinkedHashMap<String, RoleEntry> javaPickBySig) {
        RolePickerPanelSync.mergeFramePicksToMain(page, javaPickBySig);
    }

    static void syncPanelToBrowser(Page page, LinkedHashSet<String> pageClasses, LinkedHashMap<String, RoleEntry> state) {
        RolePickerPanelSync.syncPanelToBrowser(page, pageClasses, state);
    }

    /**
     * 自愈式保活：会话处于拾取中时，确保某页的点击捕获监听确实已挂载。
     * 任意页面变化（整文档替换、frame 内部跳转、onFrameNavigated 未覆盖到的边界情形）导致监听被静默丢弃后，
     * 主循环空闲期据此重挂 START_SCRIPT，保证"页面如何变化都能继续拾取"（用户核心需求）。
     * 重挂同时补注入 nls 反向查表，使导航后拾取仍能把 a11y name 反查为 key。
     * 幂等：监听已存活则零成本返回，不会重复挂载（START_SCRIPT 内部 __rolePickActive 早退 + 此处先探测）。
     */
    /**
     * 自愈式保活（V2 改造）：CDP 捕获由每页 {@link RolePickerCdpSession} 的 inspect 模式常驻承载，
     * 会话开启即持续捕获、无需 Java 端按轮重挂 JS 监听，故本方法在 V2 下为空操作（保留签名以兼容调用点）。
     */
    static void ensurePickingActive(Page page, String nlsReverseJson, String[] nlsFiles) {
        // no-op in CDP-only mode
    }















    /**
     * 读取当前已拾取的「step 序列」（不阻塞、不生成）。
     * 每个内层 List 是一次「开始 → 停止」拾取出的元素；外层 List 为 step 顺序。
     * 兼容多页面格式：每条 step 可能是数组（旧格式）或 {pageClass, picks} 对象（新格式）。
     */
    /**
     * 读取当前已拾取的「step 序列」（不阻塞、不生成）。
     * V2（CDP-only）：直接读 {@code ctx.sealedSteps}（由停止/封存逻辑写入的权威 step 列表），
     * 不再从浏览器侧 {@code window.__steps} 读取（JS 注入捕获通道已退役）。
     */
    public static List<List<RoleEntry>> getSteps(Page page) {
        RolePickerContext ctx = RolePickerPageTracker.contextFor(page.context());
        if (ctx == null) return new ArrayList<>();
        List<List<RoleEntry>> result = new ArrayList<>();
        for (StepRec st : ctx.sealedSteps) {
            result.add(st.picks);
        }
        return result;
    }




    /**
     * 定位器唯一型策略：i18n/id/css/text/title/placeholder/label/testid/altText 等，
     * 仅靠 RoleEntry 的 role/name 不足以区分多个匹配（如同一页多个同名 i18n 文本、多个 text=... 按钮），
     * 须用「去索引的稳定 locatorKey」作为内存态去重键（语义与 RoleElementPageGenerator.locatorKey 对齐，均不含 #index）。
     */
    static final java.util.Set<String> LOCATOR_IDENTITY_STRATEGIES = java.util.Collections.unmodifiableSet(
            new java.util.HashSet<>(java.util.Arrays.asList(
            RolePickerConstants.STRATEGY_I18N, RolePickerConstants.STRATEGY_ID, RolePickerConstants.STRATEGY_CSS,
            RolePickerConstants.STRATEGY_TEXT, RolePickerConstants.STRATEGY_TITLE, RolePickerConstants.STRATEGY_PLACEHOLDER,
            RolePickerConstants.STRATEGY_LABEL, RolePickerConstants.STRATEGY_TEST_ID, RolePickerConstants.STRATEGY_ALT_TEXT)));

    /**
     * 判断 javaPickBySig 权威内存态（mem）是否需要替换浏览器读取的步骤 pick（p）。
     * 覆盖四类增强字段，任一存在差异即替换（幂等，只往"更完整/点击后"方向收敛）：
     *   · checked/setCheckedTarget：final 不可变，无法 merge——若 mem 已反映"点击后状态"而 p 是"点击前状态"，
     *     必须整体替换，否则生成 setCheckedTarget 为点击前值，回放时 checkbox 勾选结果与用户点击后的页面状态相反
     *     （表现为"点击未勾选 checkbox 却生成 setChecked(false)，没选择上"）。
     *   · framePath：mem 含 backfill（iframe 内 console 通道），p 可能为空 → 需替换以生成 switchToFrame。
     *   · dialog / popup：mem 含 Java onDialog/onPopup 双保险标记，p 可能缺失。
     */


    /**
     * 阻塞式拾取：开启模式 → 等待用户点击（ESC 结束或超时）→ 关闭模式 → 返回拾取列表。
     * 期间测试线程会等待，用户在真实（有头）浏览器中点击目标元素。
     *
     * @return 已拾取的 {@link RoleEntry} 列表（可能为空的草稿）
     */
    /**
     * 阻塞式拾取：开启模式 → 等待用户点击（ESC 结束或超时）→ 关闭模式 → 返回拾取列表。
     * 期间测试线程会等待，用户在真实（有头）浏览器中点击目标元素。
     *
     * @return 已拾取的 {@link RoleEntry} 列表（可能为空的草稿）
     */
    public static List<RoleEntry> pick(Page page) {
        // 显式传空 String[]（非 varargs 调用，类型精确），避免 null 传给 String... 触发 imprecise varargs 警告
        return pick(page, new String[0]);
    }

    /**
     * 阻塞式拾取：开启模式（预加载 nls 反向查表）→ 等待用户点击 → 关闭 → 返回列表。
     * 若 {@code nlsFile} 非 null，拾取交互角色元素时会用 a11y name 反查 nls key，
     * 命中则生成的 {@code @RoleElement} 直接复用真实 key，未命中回退 slug。
     *
     * <p>CT2-25：等待用户操作带<b>可配置上界</b>（{@code picker.wait.timeout.ms}，默认 30 分钟）；
     * 超时即降级为「生成已拾取子集」，不再像原实现那样永久阻塞。
     *
     * @param page      Playwright Page（须已导航到目标页，且为 headed 浏览器）
     * @param nlsFiles   nls 文件路径（classpath 相对或文件系统绝对）；null 表示不反查
     * @return 已拾取的 {@link RoleEntry} 列表（可能为空的草稿）
     */
    public static List<RoleEntry> pick(Page page, String... nlsFiles) {
        // CI 环境：拾取是本地开发工具，不应开启/注入或阻塞等待人工拾取，直接返回空列表。
        if (isCiRun()) {
            log.info("[picker] CI runtime detected, skipping pick.");
            return new ArrayList<>();
        }
        String reverse = RolePickerNlsCache.buildNlsReverseJson(Arrays.asList(nlsFiles));
        start(page, reverse);
        try {
            // V2（CDP-only）：不再依赖 JS 注入通道的 WAIT_PICK_DONE_JS（ESC 置位）。改为等待面板「停止」命令
            // 把会话 active 置 false（见 RolePickerCommandEngine.cmdStop）；超时上界仍取 picker.wait.timeout.ms，
            // 超时即降级为「生成已拾子集」。无会话上下文时（未 openPanel）直接跳过等待，返回已拾子集。
            RolePickerContext ctx = RolePickerPageTracker.contextFor(page.context());
            long deadline = System.currentTimeMillis() + (long) pickerWaitTimeoutMs();
            while (ctx != null && ctx.active.get() && System.currentTimeMillis() < deadline) {
                Thread.sleep(200);
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.warn("[picker] Pick wait ended (timeout or interruption); generating the already-picked subset.");
        }
        List<RoleEntry> entries = getEntries(page);
        stop(page);
        log.info("[picker] picked {} element(s).", entries.size());
        return entries;
    }

    /** 拾取并直接生成源码字符串（不落盘） */
    public static String pickAndGenerate(Page page, String packageName,
                                         String pageClassName, String... nlsFiles) {
        List<RoleEntry> entries = pick(page, nlsFiles);
        if (entries.isEmpty()) {
            log.warn("[picker] no elements picked; no code generated.");
            return "";
        }
        return RoleElementPageGenerator.generate(entries, packageName, pageClassName, nlsFiles);
    }

    /** 拾取并打印生成的源码到日志 */
    public static void pickAndDump(Page page, String packageName,
                                   String pageClassName, String... nlsFiles) {
        List<RoleEntry> entries = pick(page, nlsFiles);
        if (entries.isEmpty()) {
            log.warn("[picker] no elements picked; no code generated.");
            return;
        }
        RoleElementPageGenerator.dump(entries, packageName, pageClassName, nlsFiles);
    }

    /** 拾取并直接写入文件（outputDir 为源码根，如 src/test/java） */
    public static void pickAndWrite(Page page, String outputDir, String packageName,
                                    String pageClassName, String... nlsFiles) {
        List<RoleEntry> entries = pick(page, nlsFiles);
        if (entries.isEmpty()) {
            log.warn("[picker] no elements picked; no code generated.");
            return;
        }
        RoleElementPageGenerator.write(entries, outputDir, packageName, pageClassName, nlsFiles);
    }

    /**
     * 在页面上弹出一个可复制的代码面板（类似 {@code page.pause()} 的浮层）。
     * 面板含只读代码框 + 「复制代码」/「关闭」按钮；点「关闭」后本方法返回。
     * 阻塞等待用户关闭（CT2-25：带可配置上界 {@code picker.wait.timeout.ms}，默认 30 分钟；
     * 超时或页面跳转即结束，不再无限挂起）。
     *
     * @param code 要展示/复制的源码
     */
    public static void showCode(Page page, String code) {
        // CI 环境：不注入任何拾取/代码面板脚本。
        if (isCiRun()) {
            log.info("[picker] CI runtime detected, skipping code panel (showCode).");
            return;
        }
        // 【面板独立窗口】代码浮层同样要显示/等待在面板窗口里（面板已是唯一 UI 宿主）。
        Page panel = RolePickerPanelHost.page();
        if (panel == null) {
            log.warn("[picker] code panel skipped: panel window is not available.");
            return;
        }
        RolePickerPanelHost.eval(RolePickerScripts.SET_PICKER_CODE_JS,
                RolePickerScripts.args(RolePickerConstants.STATE_KEY_CODE, code));
        RolePickerPanelHost.eval(RolePickerScripts.SHOW_PANEL_SCRIPT);
        log.info("[picker] Code panel opened: click 'Copy Code' to copy, click 'Close' to finish.");
        try {
            panel.waitForFunction(RolePickerScripts.WAIT_CODE_PANEL_CLOSED_JS, null,
                    new Page.WaitForFunctionOptions().setTimeout(pickerWaitTimeoutMs()));
        } catch (Exception e) {
            log.warn("[picker] code panel wait ended (timeout or page navigation).");
        }
    }

    /**
     * 一站式：点选元素 → 生成代码 → 在页面弹出可复制的代码面板。
     * 最贴近 {@code page.pause()} 的体验：拾取完直接弹框，复制即用。
     */
    public static void pickAndShow(Page page, String packageName,
                                   String pageClassName, String... nlsFiles) {
        List<RoleEntry> entries = pick(page, nlsFiles);
        if (entries.isEmpty()) {
            log.warn("[picker] no elements picked; no code generated.");
            return;
        }
        String code = RoleElementPageGenerator.generate(entries, packageName, pageClassName, nlsFiles);
        showCode(page, code);
    }

    /**
     * 打开一个常驻控制面板（类似 {@code page.pause()} 的 inspector），由图标控件驱动整个拾取流程：
     * <ul>
     *   <li>▶/⏹ 切换控件：空闲时显示"开始拾取"（▶，绿），点后进入点选模式并在页面点击目标元素；
     *       拾取中自动变为"停止拾取"（⏹，橙），再点即退出点选并按已点元素生成 {@code @RoleElement} 代码填入面板</li>
     *   <li>📋 复制代码：一键复制面板中的代码</li>
     *   <li>⏻ 终止运行：抛出 {@link PickerAbortedException}，中断调用方后续代码</li>
     *   <li>✕ 关闭面板：标题栏右上角的 X 图标（对齐 {@code page.pause()} 的 inspector 关闭），退出面板</li>
     * </ul>
     * 调用后本方法会阻塞，直到用户关闭面板或终止运行。
     *
     * @param page           Playwright Page（须已导航到目标页，且为 headed 浏览器）
     * @param packageName    生成类的包名
     * @param pageClassName  生成类名
     * @param stepClassName  步骤类名（Tab2 生成用；通常取 {@code pageClassName + "Steps"}）
     * @param nlsFiles       类级 {@code @RoleFile} 路径（可变参数）
     * @throws PickerAbortedException 用户点击『终止运行』时
     */
    /**
     * 一站式：打开拾取面板（简化重载）。
     *
     * <p>只接收 {@code page} 与 NLS 文件，<b>page / steps 类名由当前页面 URL 自动派生</b>，
     * 与框架对弹窗/导航产生的新页面的命名规则一致（{@link #pageClassNameFromUrl} +
     * {@code GLOBAL_URL_TO_CLASS}：取 URL path 末段清洗为 {@code XxxPage}，同一 URL 在多次运行间稳定复用）。
     * 生成类的默认包名沿用 {@code com.hsbc.cmb.hk.dbb.automation.tests}（可在外部直接调用完整重载覆盖）。</p>
     *
     * <p>调用示例：
     * <pre>{@code
     *     RoleElementPicker.openPanel(page, "nls/NLS_footer.json", "nls/NLS_idv_logon.json");
     * }</pre>
     *
     * @param page       Playwright Page（须已导航到目标页，且为 headed 浏览器）
     * @param nlsFiles   类级 {@code @RoleFile} 路径（可变参数，至少 1 个）
     * @throws PickerAbortedException 用户点击『终止运行』时
     */
    public static void openPanel(Page page, String... nlsFiles) {
        if (nlsFiles == null || nlsFiles.length == 0) {
            throw new IllegalArgumentException("[picker] openPanel 至少需要 1 个 NLS 文件路径参数");
        }
        // page / steps 类名由 URL 决定，与弹窗/导航新页面同源派生。
        final String pageClassName = RolePickerClassNameResolver.pageClassNameFromUrl(page.url(), RolePickerClassNameResolver.values());
        // 【步骤类命名】Step 类不复用 Page 类的 "XxxPage"+Steps（会拼成 XxxPageSteps），
        // 而是去掉 Page 类后缀的 "Page" 再拼 "Steps"，即页面类 LogonPage → 步骤类 LogonSteps。
        final String stepBase = pageClassName.endsWith("Page")
                ? pageClassName.substring(0, pageClassName.length() - 4) : pageClassName;
        final String stepClassName = stepBase + "Steps";
        final String packageName = "com.hsbc.cmb.hk.dbb.automation.tests";
        openPanel(page, packageName, pageClassName, stepClassName, nlsFiles);
    }

    public static void openPanel(Page page, String packageName,
                                 String pageClassName, String stepClassName, String... nlsFiles) {
        RolePickerPanelController.openPanel(page, packageName, pageClassName, stepClassName, nlsFiles);
    }

    /**
     * 注册"弹窗跟随"：当页面弹出新标签页（target=_blank）时，把原页面的拾取状态
     * （__rolePicks / __steps / __currentStep / __rolePickSigs）转移到新页面，
     * 并让 {@code current[0]} 指向新页面继续拾取。面板是注入式 docked（同窗口），
     * 故需在弹窗页也重建面板；新页面自身若再弹窗会递归注册，支持多级弹窗。
     */
    static void registerPopupFollow(RolePickerContext ctx, Page page, Page parent) {
        // 面板会话编排逻辑已下沉至 RolePickerPanelController（T5-1 拆分），此处仅保留转发 facade，
        // 供 RolePickerPageTracker 等既有调用方零改动复用。
        RolePickerPanelController.registerPopupFollow(ctx, page, parent);
    }

    /** 由页面 URL 派生 Page 类名：取 path 最后一个 '/' 之后、'?'（及 '#'）之前的片段，
     *  清洗为首字母大写的合法 Java 标识符后加 "Page"。
     *  特殊情况：片段为空（根路径 / 仅域名 / 结尾斜杠）→ 退回 "Index"；
     *  与 used 中已有类名重复时追加 2/3… 去重。 */

    /** 安全提取 URL 的 origin（protocol//host[:port]），用于跨域判断。无法解析时返回空串。 */
    static String safeOrigin(String url) {
        if (url == null) return "";
        try {
            java.net.URI u = java.net.URI.create(url);
            String scheme = u.getScheme();
            if (scheme == null) return "";
            String host = u.getHost();
            if (host == null) return "";
            int port = u.getPort();
            if (port == -1 || port == u.toURL().getDefaultPort()) {
                return scheme + "://" + host;
            }
            return scheme + "://" + host + ":" + port;
        } catch (Exception e) {
            return "";
        }
    }
    
    /** 取文件路径/URL 的最后一段（去掉所有路径分隔符前缀），用于 iframe src 与 frame.url() 的模糊匹配。 */
    static String lastPathSegment(String s) {
        if (s == null || s.isEmpty()) return "";
        String v = s.replace('\\', '/');
        int slash = v.lastIndexOf('/');
        String seg = slash >= 0 ? v.substring(slash + 1) : v;
        int q = seg.indexOf('?');
        if (q >= 0) seg = seg.substring(0, q);
        int h = seg.indexOf('#');
        if (h >= 0) seg = seg.substring(0, h);
        return seg;
    }

    /** 把任意片段清洗为合法 Java 类名的"主体"（首字母大写；- _ . 空格 / 作单词边界；其余字符丢弃）。 */

    /** 移除常驻面板，并还原 docked 预留的右侧页面空间。
     *  页面可能已关闭（如关闭的是根页面导致会话结束、或会话收尾时页面已被回收），
     *  故对 evaluate 整体容错，避免 TargetClosedError 冒泡污染测试 step（之前"关闭新页面后 STEP ERROR"的根因）。 */
    static void closePanel(Page page) {
        // 【面板独立窗口】面板已搬出被测页，CLOSE_PANEL_JS 改为作用于面板窗口：
        // 它移除的点击/悬停/按键捕获监听与 active 复位本就装在面板文档里，与被测页无关。
        // （page 参数保留仅为兼容既有调用点签名；面板全局唯一，不再逐页各关一份。）
        try {
            RolePickerPanelHost.eval(RolePickerScripts.CLOSE_PANEL_JS);
        } catch (Exception ignore) {
            RolePickerQuiet.ignore("RoleElementPicker#closePanel", ignore);
        }
    }

    /**
     * 从页面读取当前拾取会话状态（picks / steps / currentStep / sigs / active）的 JSON 字符串，
     * 用于跨页面（弹窗打开/关闭）搬运。
     * 关键点：用浏览器内 JSON.stringify 直接产出字符串返回，而不是让 Playwright 把返回对象
     * 反序列化成 Java 对象再 GSON 重序列化——后者在遇到某些返回形态时会抛"无法序列化"异常，
     * 被 catch 成空串，进而把弹窗/父页的既有拾取整体清空（这是之前"回到原页全没了"的根因）。
     * 返回字符串永远可序列化，彻底规避该问题；页面已关闭或异常时返回空集 JSON。
     */
    static String readPickStateJson(Page page) {
        try {
            Object res = pickerEval(page, RolePickerScripts.READ_PICK_STATE_JSON_JS);
            if (res instanceof String) return (String) res;
        } catch (Exception ignore) { /* 页面已关闭等：忽略，返回空集 */ }
        return "{\"picks\":[],\"steps\":[],\"currentStep\":[],\"sigs\":{},\"active\":false}";
    }

    /**
     * 短超时版 {@link #readPickStateJson(Page)}（2s 上限）：用于"新页/打开器可能繁忙"的关键路径
     * （典型：onPopup 跟随新页时读取打开器状态）。
     *
     * <p>为何需要：默认 {@code pickerEval} 超时为 10s；弹窗弹出瞬间打开器页可能短暂繁忙，
     * 读打开器状态的 evaluate 会干等满 10s（内部吞异常故不报错、但会阻塞），把紧随其后的
     * 新页面板重建 / 库注入一并拖延——用户表现即"打开的新页面长时间没有面板、拾取不了"。
     * 失败/超时同样返回空集 JSON（调用方按"无状态"处理，Java 权威态仍会经面板回灌补齐）。
     */
    static String readPickStateJsonSoft(Page page) {
        try {
            Object res = pickerEvalSoft(page, RolePickerScripts.READ_PICK_STATE_JSON_JS);
            if (res instanceof String) return (String) res;
        } catch (Exception ignore) { /* 页面已关闭/繁忙：忽略，返回空集 */ }
        return "{\"picks\":[],\"steps\":[],\"currentStep\":[],\"sigs\":{},\"active\":false}";
    }

    /** 状态 JSON 是否包含至少一个已拾取元素（用于决定是否回写父页，避免误清空父页已有拾取） */
    static boolean hasPicks(String stateJson) {
        try {
            Map<?, ?> m = RolePickerBridgeRegistry.GSON.fromJson(stateJson, MAP_STRING_OBJECT_TYPE);
            Object p = m == null ? null : m.get("picks");
            return p instanceof List && !((List<?>) p).isEmpty();
        } catch (Exception ignore) { return false; }
    }

    /** 状态 JSON 是否为"全空"（picks / steps / currentStep 均为空），用于快照更新时识别导航空窗期。 */
    static boolean isEmptyState(String stateJson) {
        try {
            Map<?, ?> m = RolePickerBridgeRegistry.GSON.fromJson(stateJson, MAP_STRING_OBJECT_TYPE);
            if (m == null) return true;
            Object p = m.get("picks");
            Object s = m.get("steps");
            Object c = m.get("currentStep");
            boolean empty = (p == null || !(p instanceof List) || ((List<?>) p).isEmpty())
                    && (s == null || !(s instanceof List) || ((List<?>) s).isEmpty())
                    && (c == null || !(c instanceof List) || ((List<?>) c).isEmpty());
            return empty;
        } catch (Exception ignore) { return false; }
    }


    /**
     * 更新面板顶部状态文字。
     *
     * <p>【面板独立窗口·尽力而为】状态条是面板 UI 的纯展示，非会话关键数据。面板页是独立轻量文档，
     * 正常情况下为毫秒级；但拾取期间面板窗口多在后台、其 renderer 主线程偶发被占满（如 rAF 在后台窗口
     * 退化为 setTimeout 密集调度、或被测页交互抖动），此时 {@code page.evaluate} 会短暂挂起。
     * 此处<b>绝不允许</b>用一个 cosmetic 更新去持锁等待 10s 硬超时并抛出 {@link IllegalStateException}——
     * 那会直接撕裂 {@code openPanel} 命令循环、让整个登录 scenario 失败（实测现场：面板能正常回填元素，
     * 却因状态条 eval 超时把 scenario 判红）。故改用短超时 {@code evalSoft} 并吞掉一切异常：拿不到就跳过本轮，
     * 下一轮重投即可。这与 355 行 {@code INVOKE_AFTER_FILL_JUMP} 的防御口径一致。
     */
    static void setStatus(Page page, String msg) {
        try {
            RolePickerPanelHost.evalSoft(RolePickerScripts.SET_STATUS_MSG_JS,
                    RolePickerScripts.args(RolePickerConstants.STATE_KEY_MSG, msg));
            RolePickerPanelHost.evalSoft(RolePickerScripts.UPDATE_STATUS_DOM_JS);
        } catch (Exception ignore) {
            RolePickerQuiet.ignore("RoleElementPicker#setStatus", ignore);
        }
    }

    /** 把按页生成的页面类/步骤代码分别写入面板的多 Tab，并更新状态 */
    static void fillCode(Page page, LinkedHashMap<String, String> pageClassByPage, LinkedHashMap<String, String> stepByPage, String msg) {
        fillCode(page, pageClassByPage, stepByPage, null, msg);
    }

    /**
     * 把按页生成的页面类/步骤代码/断言类分别写入面板的「页面类」「步骤代码」「断言」Tab，并更新状态。
     *
     * @param assertByPage 断言类：pageClass → 源码（与步骤同源同序，仅含勾选元素）；null/空则「断言」Tab 显示"（暂无生成）"
     */
    static void fillCode(Page page, LinkedHashMap<String, String> pageClassByPage,
                         LinkedHashMap<String, String> stepByPage,
                         LinkedHashMap<String, String> assertByPage, String msg) {
        fillCode(page, pageClassByPage, stepByPage, assertByPage, msg, false);
    }

    /**
     * 同 {@link #fillCode(Page, LinkedHashMap, LinkedHashMap, LinkedHashMap, String)}，
     * 但 {@code soft=true} 时改用【短超时】evaluate。
     *
     * <p>用途：面板主循环的"边拾边生成"回填。该动作**每轮都会重做、失败也无害**，而页面在整页导航瞬间
     * evaluate 可能长时间挂起——实测一次 30s 超时就把整轮关键动作（页类刷新、计数器垫高、监听重挂）
     * 一起拖住，用户表现为"返回上一页后元素恢复很慢 / 序号又从 1 开始 / 新拾元素被归到上一页"。
     * 用户点「⏹ 停止并生成」仍走默认超时（那一次必须成功）。
     */
    static void fillCode(Page page, LinkedHashMap<String, String> pageClassByPage,
                         LinkedHashMap<String, String> stepByPage,
                         LinkedHashMap<String, String> assertByPage, String msg, boolean soft) {
        // 步骤与断言合并为同一次 page.evaluate 同步渲染（FILL_CODE_ASSERT_JS 内部依次调
        // __fillCodeTabs / __fillAssertTabs）。原先分两次独立 evaluate，在 SPA / 后台 iframe 持续繁忙的页面上
        // 两次调用之间存在 hang 窗口：第一次（步骤）抢在 hang 前成功、第二次（断言）常撞软超时被丢弃，
        // 表现为"步骤实时生成、断言不生成"。合并后两步渲染原子更新，消除该不对称。
        // 【为自举留档】把本次生成的代码（按页）缓存进会话上下文：跨源新页读不到原 origin 的
        // localStorage 代码缓存，自举时据此把三个代码 Tab 一并填充，避免"新页只见元素、代码空白"。
        try {
            RolePickerContext pc = RolePickerPageTracker.contextFor(page.context());
            if (pc != null) {
                pc.lastPageByPage = (pageClassByPage == null) ? new LinkedHashMap<>() : new LinkedHashMap<>(pageClassByPage);
                pc.lastStepByPage = (stepByPage == null) ? new LinkedHashMap<>() : new LinkedHashMap<>(stepByPage);
                pc.lastAssertByPage = (assertByPage == null) ? new LinkedHashMap<>() : new LinkedHashMap<>(assertByPage);
                pc.lastCodeMsg = (msg == null) ? "" : msg;
            }
        } catch (Exception ignore) { RolePickerQuiet.ignore("RoleElementPicker#cacheCode", ignore); }
        Object combinedArgs = RolePickerScripts.args(
                "pageByPage", pageClassByPage == null ? new LinkedHashMap<String, String>() : pageClassByPage,
                "stepByPage", stepByPage == null ? new LinkedHashMap<String, String>() : stepByPage,
                "assertByPage", assertByPage == null ? new LinkedHashMap<String, String>() : assertByPage,
                "msg", msg == null ? "" : msg);
        // 【面板独立窗口】代码/断言 Tab 位于面板窗口：evaluate 目标为面板页。
        // 该页只有面板 UI（无被测页脚本），故不受被测页主线程拥堵影响，"边拾边生成"不再被拖住。
        if (soft) {
            RolePickerPanelHost.evalSoft(RolePickerScripts.FILL_CODE_ASSERT_JS, combinedArgs);
        } else {
            RolePickerPanelHost.eval(RolePickerScripts.FILL_CODE_ASSERT_JS, combinedArgs);
        }
    }

    static String asString(Object o) {
        return o == null ? null : o.toString();
    }

}
