package com.hsbc.cmb.hk.dbb.automation.framework.web.page.scan;

import com.google.gson.reflect.TypeToken;
import com.microsoft.playwright.Frame;
import com.microsoft.playwright.Page;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import com.hsbc.cmb.hk.dbb.automation.framework.web.page.scan.model.PickMode;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.scan.model.PickerResult;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.scan.model.PickerAction;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.scan.model.PickSnapshot;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.scan.model.StepRec;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.scan.model.PageOp;

import static com.hsbc.cmb.hk.dbb.automation.framework.web.page.scan.RolePickerBridgeRegistry.GSON;
import static com.hsbc.cmb.hk.dbb.automation.framework.web.page.scan.RoleElementPicker.MAP_STRING_OBJECT_TYPE;
import static com.hsbc.cmb.hk.dbb.automation.framework.web.page.scan.RoleElementPicker.asString;
import static com.hsbc.cmb.hk.dbb.automation.framework.web.page.scan.RoleElementPicker.syncPanelToBrowser;
import static com.hsbc.cmb.hk.dbb.automation.framework.web.page.scan.RoleElementPicker.anyLivePage;
import static com.hsbc.cmb.hk.dbb.automation.framework.web.page.scan.RoleElementPicker.setPickMode;
import static com.hsbc.cmb.hk.dbb.automation.framework.web.page.scan.RoleElementPicker.mergeFramePicksToMain;
import static com.hsbc.cmb.hk.dbb.automation.framework.web.page.scan.RoleElementPicker.readPickStateJson;
import static com.hsbc.cmb.hk.dbb.automation.framework.web.page.scan.RoleElementPicker.safeOrigin;
import static com.hsbc.cmb.hk.dbb.automation.framework.web.page.scan.RoleElementPicker.lastPathSegment;
import static com.hsbc.cmb.hk.dbb.automation.framework.web.page.scan.RoleElementPicker.fillCode;
import static com.hsbc.cmb.hk.dbb.automation.framework.web.page.scan.RoleElementPicker.stop;
import static com.hsbc.cmb.hk.dbb.automation.framework.web.page.scan.RoleElementPicker.start;
import static com.hsbc.cmb.hk.dbb.automation.framework.web.page.scan.RoleElementPicker.pickerEval;
import static com.hsbc.cmb.hk.dbb.automation.framework.web.page.scan.RolePickerSnapshotParser.readPickSnapshot;
import static com.hsbc.cmb.hk.dbb.automation.framework.web.page.scan.RolePickerSnapshotParser.stopAndRead;
import static com.hsbc.cmb.hk.dbb.automation.framework.web.page.scan.RolePickerSnapshotParser.parsePickSnapshot;
/**
 * 拾取命令引擎：承载 {@code RoleElementPicker#runPickerCommand} 的面板命令处理与代码生成编排。
 *
 * <p>T5-1 拆分产物：原实现整段位于 {@code RoleElementPicker}，拆分后门面类仅保留 1 行委托，
 * 对外公开行为完全不变。</p>
 *
 * <p>2026-10-10：修复本类 javadoc 的历史乱码（GBK/UTF-8 误读导致整段合并成一行），仅改注释。</p>
 */
public final class RolePickerCommandEngine {

    private static final Logger log = LoggerFactory.getLogger(RolePickerCommandEngine.class);
    private static PickerResult cmdStart(RolePickerContext ctx, Page page) {
        AtomicBoolean active = ctx.active;
        String[] nlsFiles = ctx.nlsFiles;
        ConcurrentHashMap<Page, String> pageNames = ctx.pageNames;
        LinkedHashMap<String, RoleEntry> javaPickBySig = ctx.javaPickBySig;
        // 多实例：会话级"开始"作用于所有已打开页面，使各页面板同步显示 ⏹ 停止
        // （active.get() 是会话权威开关，followPage / onFrameNavigated 据此决定是否重启监听）。
        active.set(true);
        // 【关键修复】清空 Java 侧内存态中元素的序号（保留元素本身），使第二轮拾取序号从 1 开始
        // 用户需求：重新拾取时保留已在页面元素列表中的元素，但序号从 1 重新开始
        // 浏览器侧 start 脚本也会同步清空 __rolePicks 中每个元素的 _pickNos，保持 Java/浏览器状态一致
        synchronized (javaPickBySig) {
            for (RoleEntry e : javaPickBySig.values()) {
                if (e != null) {
                    e.setPickNos(new java.util.ArrayList<>());  // 清空序号为 []（保留元素，语义与面板删除一致）
                }
            }
        }
        // 【修复"重新开始拾取后，面板上的页面元素全部消失"】
        // 按上面的设计语义：重新开始拾取【保留元素、只清序号】——因此元素必须在面板上继续可见（序号显示为
        // [-]）。但 start 会重置浏览器侧的列表/渲染与序号计数器，而回灌有 ETag 判重（只比较 Java 权威态是否
        // 变化），一旦判定"无需回灌"，新状态就永远不会写回浏览器 ⇒ 面板空白（用户表现："重新拾取，页面元素
        // 都不见了"）。此处在清完序号后【作废该页 ETag 并立刻全量回灌一次】，把"元素仍在、序号归零"如实
        // 呈现在面板上（与导航换文档时 invalidateSync 的处理同源，见 RolePickerPageTracker#refreshPageClass）。
        try {
            if (page != null && !page.isClosed()) {
                RolePickerPanelSync.invalidateSync(page);
                syncPanelToBrowser(page, null, javaPickBySig);
            }
        } catch (Exception ignoreResync) { RolePickerQuiet.ignore("RolePickerCommandEngine#cmdStartResync", ignoreResync); }
        // 进入手动拾取模式（当前唯一模式：点击页面只拾取被点元素）。
        setPickMode(anyLivePage(pageNames), PickMode.MANUAL, pageNames);
        // 反向查表只构建一次（避免对每个被跟踪页面重复读 nls 文件），减少点击"开始"的延迟。
        String startNls = RolePickerNlsCache.buildNlsReverseJson(Arrays.asList(nlsFiles));
        for (Page p : pageNames.keySet()) {
            if (!p.isClosed()) { log.info("[picker][start] calling start for page {}", p.url()); start(p, startNls); }
        }
        // 注意：开始拾取不做自动避开导航——用户有时也需要拾取 leftmenu/topbar 等全局区域；
        // 手动拾取全页可点（不再有录制根容器/区域扫描的限定——见 RoleElementPicker.start 的 javadoc）。
        return new PickerResult(PickerAction.CONTINUE, null, null,
                "RoleElement Picker：点击元素拾取 role/name，按 ESC 结束");
    }

    private static PickerResult cmdAbort(RolePickerContext ctx, Page page) {
        AtomicBoolean active = ctx.active;
        active.set(false);
        stop(page);
        return new PickerResult(PickerAction.ABORT, null, null, null);
    }

    private static PickerResult cmdDone(RolePickerContext ctx, Page page) {
        // 面板『✕ 关闭 / ⏹ 停止』按钮发出的明确关闭命令：停止拾取并结束 openPanel 阻塞循环
        // （主循环对 DONE 抛 PickerAbortedException，中止调用方后续自动登录等代码）。
        stop(page);
        return new PickerResult(PickerAction.DONE, null, null, null);
    }

    private static PickerResult cmdUnknown(RolePickerContext ctx, Page page) {
        // 【关键修复】未知/未识别命令（含 JSON 命令解析异常后落空、未命中 type 的对象命令）
        // 绝不能再 stop + 返回 DONE，否则会误关面板。未知命令一律忽略、继续会话。
        return new PickerResult(PickerAction.CONTINUE, null, null, null);
    }


    private static PickerResult cmdRefreshCode(RolePickerContext ctx, Page page) {
        LinkedHashMap<String, RoleEntry> javaPickBySig = ctx.javaPickBySig;
        String packageName = ctx.packageName;
        String pageClassName = ctx.pageClassName;
        String stepClassName = ctx.stepClassName;
        String[] nlsFiles = ctx.nlsFiles;
        ConcurrentHashMap<Page, String> pageNames = ctx.pageNames;
        ConcurrentHashMap<Page, String> snapshots = ctx.snapshots;
        // 面板「删除」按钮触发：元素已从 window.__rolePicks / window.__steps / javaPickBySig 移除，
        // 但「页面类」「步骤代码」两个 Tab 里展示的仍是删除前生成好的旧代码文本
        // （代码是生成时一次性写入 textarea 的快照，不会自动跟随数据变化）。
        // 故此处按最新状态整体重算并回填，使已生成代码中该元素的字段声明与 step 引用一并消失。
        // 与 "package" 同一套生成链路，仅不设置 __pendingJump（不跳转 Tab，留在当前视图）。
        PickSnapshot snap = null;
        try { snap = readPickSnapshot(page); } catch (Exception ignore) { RolePickerQuiet.ignore("RolePickerCommandEngine", ignore); }
        if (snap == null) snap = new PickSnapshot("", new ArrayList<>(), new ArrayList<>(), new ArrayList<>());
        // 与 package 一致：以 Java 侧内存态为准（对导航/关闭导致的浏览器端状态清空免疫）。
        // 【修复"删除元素后步骤代码括号数字不变"】删除后浏览器端 window.__steps 可能仍残留指向已删元素的旧 step，
        // 而 snapWithAutoStep 在 snap.steps 非空时会短路返回旧 steps，导致 step 数/序号不随删除更新。
        // 故此处【不沿用旧 snap.steps】，始终基于当前 javaPickBySig 重新生成 steps：
        //   - 全删空时 javaPickBySig 为空 → snap.steps 置空 → snapWithAutoStep 返回空 step（步骤代码显示"还没有任何 step"）；
        //   - 删部分时 javaPickBySig 含剩余元素 → snap.steps 置空 → snapWithAutoStep 按剩余元素序号重新拆 step，
        //     step 数量与括号序号随删除实时变化。
        // 注：手动模式主流程按点击序号拆 step，删除后重拆符合预期；若用户曾手动"封装为步骤"分组，删除后分组会被重置为按序号。
        synchronized (javaPickBySig) {
            if (!javaPickBySig.isEmpty()) {
                List<RoleEntry> snapshotEntries = new ArrayList<>(javaPickBySig.values());
                snap = new PickSnapshot(snap.pageClass, snapshotEntries, new ArrayList<>(), snap.ops);
            } else {
                snap = new PickSnapshot(snap.pageClass, new ArrayList<>(), new ArrayList<>(), new ArrayList<>());
            }
        }
        // manual-mode fallback: start->stop whole session = one step; if packaged keep selection order.
        snap = RolePickerCodeAssembler.snapWithAutoStep(snap);
        // 【修复"删除后整页重新扫描一直为 0"】
        // 旧实现在生成页面类前按会话级已删集合永久剔除已删元素，导致用户删除后重新整页扫描、
        // 新识别出的元素即便已重新入库 javaPickBySig，生成时仍被剔除，表现为"再扫描一直都是 0"。
        // 删除语义仅为"从当前内存态移除"（已被 collectDeleteKeys 的 ① ② ③ 兜底 + 源头清空 iframe
        // 残留完整覆盖），不应永久封杀该元素。故此处不按任何"已删集合"剔除（该会话级状态已按 N-18 删除），以 javaPickBySig
        // 当前内容为准直接生成——重新扫描即可正常出现代码。
        LinkedHashMap<String, String> codePage = RolePickerCodeAssembler.buildPageClassCode(snap.entries, packageName, pageClassName, nlsFiles);
        LinkedHashMap<String, String> codeStep = RolePickerCodeAssembler.buildStepCode(snap, packageName, stepClassName);
        LinkedHashMap<String, String> codeAssert = RolePickerCodeAssembler.buildAssertCode(snap, packageName);
        String refreshMsg = "已删除选中元素，页面类 " + snap.entries.size() + " 个字段"
                + (codeStep.isEmpty() ? "，当前无步骤代码" : "");
        // 【必须在此处直接回填】元素被删空时 codePage/codeStep 均为空 map，
        // 若交给主循环处理会因「两者都为空」落入 else 分支只更新状态栏，
        // 旧代码将永远残留在 Tab 上（删到一个不剩却还显示着完整页面类）。
        // 这里显式回填空内容，确保代码区随之清空，不留幽灵代码。
        for (Page p : pageNames.keySet()) {
            if (!p.isClosed()) fillCode(p, codePage, codeStep, codeAssert, refreshMsg);
        }
        // 已自行回填，故返回 null 代码体避免主循环重复 fillCode；
        // statusMsg 仍需返回（而非 null），否则 else 分支会用 null 覆盖掉状态栏文案。
        return new PickerResult(PickerAction.CONTINUE, null, null, refreshMsg);
    }

    private static PickerResult cmdStop(RolePickerContext ctx, Page page) {
        AtomicBoolean active = ctx.active;
        String[] nlsFiles = ctx.nlsFiles;
        String packageName = ctx.packageName;
        String pageClassName = ctx.pageClassName;
        String stepClassName = ctx.stepClassName;
        ConcurrentHashMap<Page, String> pageNames = ctx.pageNames;
        ConcurrentHashMap<Page, String> snapshots = ctx.snapshots;
        LinkedHashMap<String, RoleEntry> javaPickBySig = ctx.javaPickBySig;
        active.set(false);
        log.info("[picker][stop] stop command received; stopping {} tracked page(s)", pageNames.size());
        // 多实例：停止作用于所有已打开页面，使各页面板同步回 ▶ 开始
        // （否则某页仍显示停止却已失活，造成"点了没反应"的错觉）。
        // 企业级优化：命令来源页用 stopAndRead 把"去激活 + 收尾当前 step + 读回全部拾取态"
        // 合并为 1 次 Java↔浏览器往返；其余被跟踪页仅去激活（stop），避免重复生成代码与多余读快照。
        // （原实现对命令页额外发一次收尾 evaluate + 一次 readPickSnapshot，与 stop() 共 3 次串行往返，
        //  现已合并进 stopAndRead，点击"停止"的端到端延迟显著下降。）
        PickSnapshot snap = null;
        // 折叠已关闭页（如"跳转到新页面后直接关闭"的根页）缓存的 step/op：停止生成时这些页被跳过，
        // 若不折叠，其在 onClose else 分支补登记的 closeCurrentPage 步骤会丢失。
        List<StepRec> closedSteps = new ArrayList<>();
        List<PageOp> closedOps = new ArrayList<>();
        for (Page p : pageNames.keySet()) {
            if (p.isClosed()) {
                String cached = snapshots.get(p);
                if (cached != null && !cached.isEmpty()) {
                    try {
                        java.util.Map<String, Object> cm = GSON.fromJson(cached, MAP_STRING_OBJECT_TYPE);
                        PickSnapshot cs = parsePickSnapshot(cm);
                        closedSteps.addAll(cs.steps);
                        closedOps.addAll(cs.ops);
                    } catch (Exception ignore) { RolePickerQuiet.ignore("RolePickerCommandEngine", ignore); }
                }
                continue;
            }
            try {
                if (p == page) snap = stopAndRead(p);
                else stop(p);
            } catch (Exception stopEx) {
                // 密集导航（onFrameNavigated/整页跳转）时命令来源页的 execution context 可能正在
                // 销毁/重建，stopAndRead/stop 的 page.evaluate 会抛 "Execution context was destroyed"。
                // 不向上冒泡撕裂主循环会话：标记已失活并降级为读内存态，保证"停止"在任何导航瞬间都生效，
                // 不再出现"点了停止却卡住/没反应"的假死（active 已被 active.get()=false 复位）。
                log.warn("[picker][stop] evaluate failed while stopping page {} (ignorable during navigation): {}",
                        p.url(), stopEx.getMessage());
                try { p.evaluate(RolePickerScripts.SET_PICK_STOPPED_JS); } catch (Exception ignore) { RolePickerQuiet.ignore("RolePickerCommandEngine", ignore); }
            }
        }
        if (snap == null) snap = readPickSnapshot(page);   // 兜底：命令页不在跟踪集合时
        // 跨域页停止时，主框架 page.evaluate 可能因导航竞态返回空/抛异常（snap.entries 为空）；
        // 此处再从各 frame 的 window.__rolePicks 逐帧兜底读取（与 stop() 的跨 frame 聚合同口径），
        // 确保跨域主框架页自身已被拾取的元素不丢失（修复"跨域页拾取完停止未生成 step"）。
        if (snap == null || snap.entries.isEmpty()) {
            try {
                List<RoleEntry> fEntries = new ArrayList<>();
                List<StepRec> fSteps = new ArrayList<>();
                List<PageOp> fOps = new ArrayList<>();
                for (com.microsoft.playwright.Frame f : page.frames()) {
                    try {
                        // 与 stopAndRead/readPickSnapshot 同口径：直接读各 frame 的 window.__rolePicks/__steps/__ops
                        PickSnapshot fs = parsePickSnapshot(pickerEval(f, RolePickerScripts.PICK_STATE_READER_JS));
                        fEntries.addAll(fs.entries); fSteps.addAll(fs.steps); fOps.addAll(fs.ops);
                    } catch (Exception fe) { /* 单 frame 失败忽略，继续其它 frame */ }
                }
                if (!fEntries.isEmpty()) {
                    PickSnapshot fb = new PickSnapshot(snap == null ? "" : snap.pageClass, fEntries, fSteps, fOps);
                    // 内存态(javaPickBySig)优先；仅当内存态也为空时才用跨 frame 浏览器兜底态。
                    boolean javaEmpty;
                    synchronized (javaPickBySig) { javaEmpty = javaPickBySig.isEmpty(); }
                    snap = javaEmpty ? fb : snap;
                }
            } catch (Exception ff) {
                log.warn("[picker][stop] cross-frame fallback snapshot read failed @ {} : {}", page.url(), ff.getMessage());
            }
        }
        // 状态外置（对齐 page.pause）：优先用 Java 侧内存态（javaPickBySig）作为已拾元素权威来源，
        // O(1) 取回、且对导航/关闭导致的浏览器端状态清空免疫；内存为空（回传桥未触发等异常）时
        // 退回浏览器读快照兜底。steps/ops 仍来自浏览器单次往返（stopAndRead 已合并），保证多页 step 序列正确。
        synchronized (javaPickBySig) {
            if (!javaPickBySig.isEmpty()) {
                List<RoleEntry> memEntries = new ArrayList<>(javaPickBySig.values());
                snap = new PickSnapshot(snap.pageClass, memEntries, snap.steps, snap.ops);
            }
        }
        // 【用 Java 侧本轮累积重建当前轮 step】浏览器的 __currentStep 只装"当前文档"的拾取，
        // 一轮内每次导航 / 弹出新页都会换文档 ⇒ 停止时浏览器 __steps 里只剩最后一个文档的那几条，
        // 本轮其余操作全部丢失（现场：一轮内跨 3 个文档拾了 32 个操作，产物 step1 只剩最后 4 行）。
        // ctx.roundPicks 由 __roleOnPick 逐次累积（跨文档 / 跨弹窗，元素带各自 pageClass 与 nos），
        // 才是"本轮"的权威来源 —— 用它整体替换 snap.steps（一个 step = 一轮，生成器按各元素 nos 展开）。
        try {
            java.util.List<RoleEntry> roundList;
            synchronized (ctx.roundPicks) { roundList = new ArrayList<>(ctx.roundPicks.values()); }
            if (!roundList.isEmpty()) {
                java.util.List<RoleEntry> ordered = new ArrayList<>(roundList);
                ordered.sort(java.util.Comparator.comparingInt(e -> {
                    java.util.List<Integer> nos = e.getPickNos();
                    return (nos != null && !nos.isEmpty() && nos.get(0) != null) ? nos.get(0) : Integer.MAX_VALUE;
                }));
                String roundCls = (snap != null && snap.pageClass != null && !snap.pageClass.isEmpty())
                        ? snap.pageClass
                        : (ordered.get(0).getPageClass() != null ? ordered.get(0).getPageClass() : "");
                // 【本轮号表】必须在下方清空 pickNos 之前固化，并与 step 一并封存：
                // 历史 step 的号不能依赖元素身上的 pickNos（每轮停止都会清）。
                java.util.Map<String, java.util.List<Integer>> curNos = new java.util.LinkedHashMap<>();
                for (RoleEntry e : ordered) {
                    String k = RoleElementPageGenerator.groupIdentityOf(e);
                    if (k == null || k.isEmpty()) continue;
                    java.util.List<Integer> nos = e.getPickNos();
                    if (nos != null && !nos.isEmpty()) curNos.put(k, new java.util.ArrayList<>(nos));
                }
                // 【追加而非替换】已封存的各轮 step 必须保留，否则第二轮会把第一轮的步骤整个覆盖掉
                //（现场：第二轮停止后产物只剩 step2、step1 消失）。
                java.util.List<StepRec> allRounds = new ArrayList<>();
                if (ctx.sealedSteps != null) allRounds.addAll(ctx.sealedSteps);
                allRounds.add(new StepRec(roundCls, ordered, curNos));
                snap = new PickSnapshot(
                        (snap != null && snap.pageClass != null) ? snap.pageClass : "",
                        (snap != null && snap.entries != null) ? snap.entries : ordered,
                        allRounds,
                        (snap != null && snap.ops != null) ? snap.ops : new ArrayList<>());
            }
        } catch (Exception re) {
            log.warn("[picker][stop] rebuild current-round step from roundPicks failed: {}", re.getMessage());
        }
        // manual-mode (not packaged) fallback: start->stop whole session = one step.
        snap = RolePickerCodeAssembler.snapWithAutoStep(snap);
        // 合入已关闭页的步骤/操作（含其补登记的 closeCurrentPage），避免关闭步骤在停止时被跳过而丢失。
        if (!closedSteps.isEmpty() || !closedOps.isEmpty()) {
            List<StepRec> mergedSteps = new ArrayList<>(snap.steps);
            mergedSteps.addAll(closedSteps);
            List<PageOp> mergedOps = new ArrayList<>(snap.ops);
            mergedOps.addAll(closedOps);
            snap = new PickSnapshot(snap.pageClass, snap.entries, mergedSteps, mergedOps);
        }
        // 【实时预览与"停止"生成完全一致】把本次停止后的权威 steps（浏览器 __steps，已含刚封存的当轮）
        // 缓存进会话上下文，并清空"当轮进行中"的拾取。这样「边拾边生成」（handleIdle）可用
        // 「已封存 steps + 当轮进行中 step」构造 steps，使【步骤代码】与【断言】两个 Tab 在"边拾边预览"
        // 阶段就与停止生成一致（每轮一个 step / 一个 assertStep、跨轮累积），不再由 snapWithAutoStep
        // 把全部元素兜底成 1 步（那正是"预览只有 1 个 step / 1 个 assertStep"的来源）。
        try {
            ctx.sealedSteps = new ArrayList<>(snap.steps);
            synchronized (ctx.roundPicks) { ctx.roundPicks.clear(); }
        } catch (Exception ignore) { RolePickerQuiet.ignore("RolePickerCommandEngine", ignore); }
        // 关键修复（修复"开始→停止→再开始→停止，第一次的步骤代码丢失"）：
        // 整页跳转时 onFrameNavigated→applyPickState 会用 snapshots 里的 Java 恢复态【整体覆盖】
        // window.__steps（见 applyPickState：window.__steps = s.steps || []）。而快照此前只在"空闲刷新"时更新，
        // 且 run1 拾取期间 __steps 为空、空闲刷新把恢复态停在"空 steps"；若 run2 中发生整页跳转，
        // 就用这份过期恢复态把第一次的 step 整体覆盖丢失。此处把本次停止后的最新态【立即回写】Java 恢复态，
        // 使任何后续跳转恢复时都含已有 step（含第一次），彻底消除该时序窗口。
        try { snapshots.put(page, readPickStateJson(page)); } catch (Exception ignore) { RolePickerQuiet.ignore("RolePickerCommandEngine", ignore); }
        // 多页面：当前页 window 持有全部被跟踪页面的拾取（跟随新页时搬运、关闭弹窗时合并回父页），
        // 每条 pick/step 都带 _pageClass 标签，据此分组到对应 Page 类；steps 跨页引用也归到对应页。
        String curClass = snap.pageClass;
        LinkedHashMap<String, List<RoleEntry>> entriesByPage = new LinkedHashMap<>();
        LinkedHashMap<String, List<List<RoleEntry>>> stepsByPage = new LinkedHashMap<>();
        List<RoleEntry> allEntries = new ArrayList<>();
        int totalSteps = 0;
        int droppedDirty = 0;
        for (RoleEntry e : snap.entries) {
            if (e == null) continue;
            // 【过滤脏条目】sigKey 为空的条目既不能去重也不能定位（现场：停止清单里出现
            // sigKey=null / pageClass=null 的重复条目，与 i18n:user_name / i18n:password 重复且无号可依）。
            // 留着会在生成物里产生无名字段与 role:: 悬空引用（进而触发"生成前对账丢弃引用"的告警），
            // 故在生成前直接剔除 —— 与对账同一意图，只是更早、更干净。
            if (e.getSigKey() == null || e.getSigKey().isEmpty()) { droppedDirty++; continue; }
            String pc = (e.getPageClass() == null || e.getPageClass().isEmpty()) ? curClass : e.getPageClass();
            entriesByPage.computeIfAbsent(pc, k -> new ArrayList<>()).add(e);
            allEntries.add(e);
        }
        if (droppedDirty > 0) {
            log.info("[picker][stop] dropped {} dirty entry/entries without sigKey (cannot be deduped or located)", droppedDirty);
        }
        // 【每轮号表】必须在下方"清空 pickNos"之前收集：元素实例跨轮共享，本轮号一旦清空/被下一轮覆盖，
        // step1 就无号可展开（现场：第二轮停止后第一轮的操作整段消失）。号随 step 走 ⇒ 彻底按轮隔离。
        LinkedHashMap<String, List<java.util.Map<String, java.util.List<Integer>>>> stepRoundNosByPage =
                new LinkedHashMap<>();
        for (StepRec st : snap.steps) {
            String pc = (st.pageClass == null || st.pageClass.isEmpty()) ? curClass : st.pageClass;
            stepsByPage.computeIfAbsent(pc, k -> new ArrayList<>()).add(st.picks);
            entriesByPage.computeIfAbsent(pc, k -> new ArrayList<>());   // 保证 steps 的页在 entries 中也有类
            java.util.Map<String, java.util.List<Integer>> roundNos = new java.util.LinkedHashMap<>();
            if (st.roundNos != null && !st.roundNos.isEmpty()) {
                // 已封存轮次：直接用它自带的那轮号表（元素身上的 pickNos 早被清空了）
                roundNos.putAll(st.roundNos);
            } else if (st.picks != null) {
                for (RoleEntry e : st.picks) {
                    if (e == null) continue;
                    String k = RoleElementPageGenerator.groupIdentityOf(e);
                    if (k == null || k.isEmpty()) continue;
                    java.util.List<Integer> nos = e.getPickNos();
                    if (nos != null && !nos.isEmpty()) roundNos.put(k, new java.util.ArrayList<>(nos));
                }
            }
            stepRoundNosByPage.computeIfAbsent(pc, k -> new ArrayList<>()).add(roundNos);
            totalSteps++;
        }
        // 页面级操作（如关闭页面）按所属页归类，生成 closeCurrentPage() 等步骤。
        LinkedHashMap<String, List<String>> opsByPage = new LinkedHashMap<>();
        for (PageOp op : snap.ops) {
            String pc = (op.pageClass == null || op.pageClass.isEmpty()) ? curClass : op.pageClass;
            opsByPage.computeIfAbsent(pc, k -> new ArrayList<>()).add(op.op);
            // 注意：页面级操作（closeCurrentPage / switchNewPage 等）已在各页视图内联为 step 内的一行代码，
            // 不是独立 step，故不再把 op 计入 totalSteps，否则"封装为 1 个 step + 1 个内联跳转操作"会误显为 2 个 step。
        }
        if (entriesByPage.isEmpty()) {
            // 诊断：跨域/导航竞态下出现"未拾取到元素"时，记录内存态与浏览器侧 picks 数量，便于定位是否漏拾。
            int jsMem = javaPickBySig.size();
            int browserPicks = 0;
            try { browserPicks = ((List<?>) pickerEval(page, RolePickerScripts.READ_PICK_COUNT_JS)).size(); } catch (Exception ignoreB) { RolePickerQuiet.ignore("RolePickerCommandEngine#readPickCount", ignoreB); }
            log.warn("[picker][stop] no elements picked @ {} : in-memory javaPickBySig={}, browser __rolePicks={}, current page origin={}",
                    page.url(), jsMem, browserPicks, safeOrigin(page.url()));
            // 停止即回 IDLE，面板按钮复位为"▶ 开始拾取"。
            // 停止即回 IDLE，面板按钮复位为"▶ 开始拾取"。
            setPickMode(anyLivePage(pageNames), PickMode.IDLE, pageNames);
            return new PickerResult(PickerAction.CONTINUE, null, null, "未拾取到元素");
        }
        // 按 pageClass 分别生成页面类（含"仅 step/ops 无元素 pick"的页，也产出空字段类，保证步骤视图引用不悬空）
        LinkedHashMap<String, String> codePage = new LinkedHashMap<>();
        for (Map.Entry<String, List<RoleEntry>> e : entriesByPage.entrySet()) {
            codePage.put(e.getKey(), RoleElementPageGenerator.generate(e.getValue(), packageName, e.getKey(), nlsFiles));
        }
        // 传入【每轮号表】：step k 只认第 k 轮的序号，彻底避免跨轮覆盖（第一轮操作不再丢失）。
        LinkedHashMap<String, String> codeStep =
                RolePickerCodeAssembler.buildStepCode(snap, packageName, stepClassName, stepRoundNosByPage);
        // 【修复"停止后「断言」Tab 为空（没有生成断言代码）」】本路径原先只生成 page/step，且用 PickerResult 的
        // 兼容构造（assertByPage=null）⇒ 面板「断言」Tab 被渲染成"（暂无生成）"，用户表现为"没生成断言"。
        // 断言与步骤同源（都由 snap.steps 派生），此处一并生成并回填。
        LinkedHashMap<String, String> codeAssert = RolePickerCodeAssembler.buildAssertCode(snap, packageName);
        // 【diag-stop】停止并生成代码前，列印全部 entry 的最终 pickNos（生成器即据此按号展开 click）。
        log.info("[picker][diag-stop] ===== before buildStepCode: {} entries =====", allEntries.size());
        for (RoleEntry e : allEntries) {
            // 【诊断】带上 index/count/list：成组（list）元素的 .get(index) 依赖 index 是"定位器匹配集内的序号"、
            // count 是该匹配集总数（口径 = page.pause 的 locator.nth）。若这里 index=-1 或 count=1，
            // 说明 index/count 在合并链路上被丢了（旧路径正是因此拼不出 .nth(i)、退化成多行重复 click）。
            log.info("[picker][diag-stop] entry sigKey={} strategy={} pageClass={} pickNos={} index={} count={} list={}",
                    e.getSigKey(), e.getStrategy(), e.getPageClass(), e.getPickNos(), e.getIndex(), e.getCount(), e.isList());
            if (e.isList() && e.getCount() > 1 && e.getIndex() >= e.getCount()) {
                log.warn("[picker][diag-stop] list index {} out of range (count={}) for {} — 该拾取的 index/count 口径可能不一致，运行期 get() 会越界",
                        e.getIndex(), e.getCount(), e.getSigKey());
            }
        }
        // 停止拾取后重置（必须在 buildStepCode 之后，生成已基于累积 pickNos 完成）：
        // 清空 Java 权威内存态每个 entry 的 pickNos，并让浏览器侧 window.__rolePicks 的
        // _pickNos/_pickSeq 归零，使面板干净回退到 [-]，且下一次 start 时全局动作序号从 1 重新计数。
        synchronized (javaPickBySig) {
            // 【必须先快照再清空】本轮序号清空后，历史 step（step1/step2…）将无号可展开操作，
            // 产物里会整段丢失上一轮的 click/fill —— 现场实证：第二轮停止时前 20 条 pickNos=[]。
            // 故清空前把本轮序号存进 committedPickNos，生成历史 step 时由 pickNosOf 回退取用。
            for (RoleEntry e : javaPickBySig.values()) {
                java.util.List<Integer> roundNos = e.getPickNos();
                if (roundNos != null && !roundNos.isEmpty()) {
                    e.setCommittedPickNos(new java.util.ArrayList<>(roundNos));
                }
                e.setPickNos(new java.util.ArrayList<>());
            }
        }
        try {
            // 【修复"stop→start 第二轮序号错乱（如 user_name 拿到旧号而非从续接点递增）】
            // 原逻辑只 delete p._pickNos + p._pickSeq=0，但 __rolePicks 数组、__rolePickSigs（key 去重表）、
            // __sigToPick（sigKey→pick 对象）、以及上一轮缓存的旧 pick 对象（__pickSeq/_pickNos 残留）都仍保留。
            // start 时设计上"保留既有 picks、从既有重建去重表"，于是第二轮点同一元素会【复用旧 pick 对象】
            // （其旧 _pickSeq/_pickNos 仍在），导致序号回退/重复。
            // 修复：stop 时彻底清空浏览器侧全部拾取注册状态（与"下一轮从 1 重新连续"语义一致），
            // 停止拾取：保留元素列表（__rolePicks），但清空本轮动作序号与选择集，
            // 使下一轮「开始拾取」的序号从 1 重新连续（面板序号体系已移除，此处只剩序号基线语义）。
            pickerEval(page, RolePickerScripts.CLEAR_PICKS_JS);
        } catch (Exception ignore) { RolePickerQuiet.ignore("RolePickerCommandEngine", ignore); }
        int matched = 0;
        for (RoleEntry e : allEntries) {
            if (e.getResolvedKey() != null) matched++;
        }
        String nlsInfo = (nlsFiles != null && nlsFiles.length > 0)
                ? "（nls=" + (nlsFiles.length == 1 ? nlsFiles[0] : nlsFiles.length + " 个文件")
                    + "，已反查 " + matched + " 个 key）" : "";
        // 停止即回 IDLE，面板按钮复位为"▶ 开始拾取"，页面点击不再拾取。
        setPickMode(anyLivePage(pageNames), PickMode.IDLE, pageNames);
        String assertInfo = codeAssert.isEmpty() ? "" : "，断言类 " + codeAssert.size() + " 个（按页）";
        return new PickerResult(PickerAction.CONTINUE, codePage, codeStep, codeAssert,
                "已生成 " + entriesByPage.size() + " 个页面类 / " + allEntries.size()
                        + " 个页面字段 / " + totalSteps + " 个 step" + assertInfo + nlsInfo);
    }

    static PickerResult runPickerCommand(RolePickerContext ctx, Page page, String cmd) {
        // 纯字符串命令（start/stop/done...）走下方 switch；面板「列表」开关等 JSON 对象命令在此先拦截处理。
        // 注：面板序号体系（含 {type:'repickNos'} 对象命令）已整体移除，Java 侧不再有任何序号编辑入口。
        // 【已移除「扫描整页」与「区域扫描」】scan / scanRegion / regionScanned / regionDone 四类命令
        // 不再有分支处理：它们会把整页/整区的候选元素批量走「拾取记录」链路（每次记录分配一个全局
        // 递增拾取号），使仅有 8 个元素的会话累积出上百个序号、并因 Java 侧"只增不减"的并集合并不断
        // 放大，最终生成结果错乱（详见 panel-core-a.js 工具栏注释）。这四类命令现落入 default
        // （cmdUnknown）被安全忽略，会话不中断。
        // 【JSON 对象命令】面板「列表」开关等对象命令（{...}）经 __rolePickerCmd 投递、桥侧 GSON 规范化为
        // JSON 字符串，先于纯字符串 switch 拦截；未识别的 type 返回 null，交回既有 switch/default，行为不变。
        if (cmd == null) {
            // 防御（spotbugs NP_NULL_ON_SOME_PATH）：空命令走 cmdUnknown（安全忽略），绝不落入 switch NPE。
            return cmdUnknown(ctx, page);
        }
        if (cmd.startsWith("{")) {
            PickerResult jsonResult = tryHandleJsonCommand(ctx, page, cmd);
            if (jsonResult != null) return jsonResult;
        }
        switch (cmd) {
            case RolePickerConstants.CMD_START: return cmdStart(ctx, page);
            case RolePickerConstants.CMD_REFRESH_CODE: return cmdRefreshCode(ctx, page);
            case RolePickerConstants.CMD_STOP: return cmdStop(ctx, page);
            case RolePickerConstants.CMD_ABORT: return cmdAbort(ctx, page);
            case RolePickerConstants.CMD_DONE: return cmdDone(ctx, page);
            default: return cmdUnknown(ctx, page);
        }
    }

    /**
     * JSON 对象命令拦截（对象命令经 {@code __rolePickerCmd} 投递、桥侧 {@code GSON.toJson} 规范化）。
     * 目前仅处理面板「列表」开关（{@link RolePickerConstants#CMD_TYPE_MARK_LIST}）；其余 type 返回 null，
     * 交回既有 switch/default 处理，保持零行为变更。
     */
    private static PickerResult tryHandleJsonCommand(RolePickerContext ctx, Page page, String cmd) {
        try {
            Map<String, Object> m = GSON.fromJson(cmd, MAP_STRING_OBJECT_TYPE);
            if (m == null) return null;
            if (RolePickerConstants.CMD_TYPE_MARK_LIST.equals(String.valueOf(m.get("type")))) {
                return cmdMarkList(ctx, page, m);
            }
            return null;
        } catch (Exception ex) {
            log.warn("[picker] failed to parse JSON panel command (ignored): {}", ex.getMessage());
            return null;
        }
    }

    /**
     * 面板「列表」开关：把某条拾取在 Java 权威内存态中标记/取消标记为「一组元素（列表）」。
     *
     * <p>命令形如 {@code {type:'markList', list:<bool>, pick:{...精简 pick...}}}（pick 字段与删除回传同源：
     * {@code strategy/_sig/_sigKey/_pageClass/role/key/name/index/selector/css/...}）。用与入库完全一致的
     * {@link RolePickerPickParser#pickDedupKey} 精确定位内存态条目（与删除链路同口径），缺省再按固化
     * {@code sigKey} 兜底命中；置位后回灌面板，使「列表」开关的视觉状态与 Java 权威态一致。
     * 标记只影响<b>代码生成形态</b>（{@code PageElementList} + {@code x.get(index)}），不改动去重键/序号等既有语义。
     */
    private static PickerResult cmdMarkList(RolePickerContext ctx, Page page, Map<String, Object> m) {
        try {
            LinkedHashMap<String, RoleEntry> map = ctx.javaPickBySig;
            boolean list = Boolean.parseBoolean(String.valueOf(m.get("list")));
            boolean found = false;
            Object pickRaw = m.get("pick");
            if (pickRaw instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<Object, Object> pm = (Map<Object, Object>) pickRaw;
                RoleEntry e = RolePickerPickParser.parsePick(pm);
                if (e != null) {
                    String key = RolePickerPickParser.pickDedupKey(pm, e);
                    synchronized (map) {
                        RoleEntry existing = (key == null || key.isEmpty()) ? null : map.get(key);
                        if (existing == null && e.getSigKey() != null && !e.getSigKey().isEmpty()) {
                            // 兜底：按固化 sigKey 精确命中（历史数据键形式不一致时）
                            for (RoleEntry v : map.values()) {
                                if (e.getSigKey().equals(v.getSigKey())) { existing = v; break; }
                            }
                        }
                        if (existing != null) {
                            existing.setList(list);
                            found = true;
                            log.info("[picker] markList applied: sigKey={} list={}", existing.getSigKey(), list);
                        }
                    }
                }
            }
            if (!found) log.warn("[picker] markList target not found (list={})", list);
            // 回灌面板：作废 ETag 后按 Java 权威态重写，使「列表」开关状态即时反映。
            try { RolePickerPanelSync.invalidateSync(page); } catch (Exception ignore) { RolePickerQuiet.ignore("RolePickerCommandEngine", ignore); }
            try { syncPanelToBrowser(page, null, ctx.javaPickBySig); } catch (Exception ignore) { RolePickerQuiet.ignore("RolePickerCommandEngine", ignore); }
            return new PickerResult(PickerAction.CONTINUE, null, null, null, found ? "已切换列表标记（list=" + list + "）" : "未找到要标记的元素");
        } catch (Exception ex) {
            log.warn("[picker] markList failed: {}", ex.getMessage());
            return new PickerResult(PickerAction.CONTINUE, null, null, null, "列表标记失败：" + ex.getMessage());
        }
    }}
