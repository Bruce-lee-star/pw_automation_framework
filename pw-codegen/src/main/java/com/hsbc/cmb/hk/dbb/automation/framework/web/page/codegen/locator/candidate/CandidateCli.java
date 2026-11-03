package com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen.locator.candidate;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.LocatorSpec;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 候选库<b>命令行</b>（设计 §4.4 / P3）：把「提出 → 验证 → 审批 → 回注」变成可以真正跑一遍的操作面。
 *
 * <h2>为什么需要它</h2>
 * <p>{@link CandidateLibrary} 只是状态机内核。没有 CLI 时，"审批"要么写一段临时 Java，要么手改 JSON——
 * 后者尤其危险：手工把 {@code state} 改成 {@code APPROVED} 就绕过了审批人、应用版本与事件链，
 * 治理规则会退化成文档里的一句话。CLI 是<b>唯一</b>的写入口，所有变更都走库的状态机。
 *
 * <h2>退出码就是 CI 契约</h2>
 * <pre>
 *   0  成功
 *   1  用法 / 输入错误（参数缺失、定位符格式非法、证据缺失）
 *   2  被治理规则拒绝（越级审批、验证未通过、非法状态迁移）
 *   3  未解析到可用的生产定位器（调用方必须停下转人工，不得回退）
 *   4  并发冲突（另一个进程正在改同一个库）
 *   5  I/O 错误
 * </pre>
 * <p>{@code locator} 子命令的 stdout <b>只有一行 canonical 串</b>，可直接被脚本取用；
 * 诊断原因一律走 stderr，避免污染机器可读输出。
 *
 * <h2>并发</h2>
 * <p>每次写操作都先取同目录下的文件锁（{@code <库文件>.lock}），取不到即退出码 4 而<b>不是等待或强写</b>：
 * 两个审批人同时提交时，后写者覆盖先写者会静默丢掉一条审批记录，这比直接报错严重得多。
 */
public final class CandidateCli {

    public static final int EXIT_OK = 0;
    public static final int EXIT_USAGE = 1;
    public static final int EXIT_REJECTED = 2;
    public static final int EXIT_NOT_RESOLVED = 3;
    public static final int EXIT_CONFLICT = 4;
    public static final int EXIT_IO = 5;

    /** 默认库路径：与"资产进仓"的习惯一致，结果可提交评审。 */
    static final Path DEFAULT_LIBRARY = Path.of("locators", "candidates.json");

    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .disableHtmlEscaping()
            .create();

    private CandidateCli() {}

    public static void main(String[] args) {
        PrintStream out = new PrintStream(new FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8);
        PrintStream err = new PrintStream(new FileOutputStream(FileDescriptor.err), true, StandardCharsets.UTF_8);
        System.exit(run(args, out, err));
    }

    /** 可测试入口：返回退出码而不杀进程（{@code main} 才调用 {@link System#exit}）。 */
    public static int run(String[] args, PrintStream out, PrintStream err) {
        if (args == null || args.length == 0) {
            printUsage(err);
            return EXIT_USAGE;
        }
        String command = args[0];
        Options opts;
        try {
            opts = Options.parse(args, 1);
        } catch (IllegalArgumentException e) {
            err.println("参数错误：" + e.getMessage());
            printUsage(err);
            return EXIT_USAGE;
        }

        try {
            switch (command) {
                case "help":
                case "--help":
                case "-h":
                    printUsage(out);
                    return EXIT_OK;
                case "propose":
                    return propose(opts, out, err);
                case "validate":
                    return validate(opts, out, err);
                case "approve":
                    return approve(opts, out, err);
                case "reject":
                    return reject(opts, out, err);
                case "retire":
                    return retire(opts, out, err);
                case "list":
                    return list(opts, out);
                case "show":
                    return show(opts, out);
                case "locator":
                    return locator(opts, out, err);
                case "audit":
                    return audit(opts, out);
                default:
                    err.println("未知命令：" + command);
                    printUsage(err);
                    return EXIT_USAGE;
            }
        } catch (ConflictException e) {
            err.println("并发冲突：" + e.getMessage());
            return EXIT_CONFLICT;
        } catch (IllegalArgumentException e) {
            err.println("错误：" + e.getMessage());
            return EXIT_USAGE;
        } catch (IllegalStateException e) {
            err.println("拒绝：" + e.getMessage());
            return EXIT_REJECTED;
        } catch (IOException e) {
            err.println("I/O 错误：" + e.getMessage());
            return EXIT_IO;
        }
    }

    // ------------------------------------------------------------------
    // 写命令：加载 → 状态机变更 → 原子落盘（全程持锁）
    // ------------------------------------------------------------------

    private static int propose(Options o, PrintStream out, PrintStream err) throws IOException {
        String target = o.require("target", "目标语义引用（如 login.submit）");
        LocatorSpec spec = parseLocator(o.require("locator", "定位符（如 \"role=button;name=Log on\"）"));
        Path lib = o.library();

        try (LibraryLock ignored = LibraryLock.acquire(lib)) {
            CandidateLibrary library = CandidateLibrary.load(lib);
            library.all().stream()
                    .filter(r -> r.state() != CandidateState.RETIRED)
                    .filter(r -> r.candidate().targetRef().equals(target.trim()))
                    .filter(r -> r.candidate().canonical().equals(spec.canonical()))
                    .findFirst()
                    .ifPresent(dup -> err.println("提示：已存在同样的候选 " + dup.id()
                            + "（" + dup.state() + "），本次仍新建一条记录（审批按记录独立进行）"));

            CandidateRecord record = library.propose(LocatorCandidate.of(target, spec)
                    .withExpectedRole(o.get("expected-role"))
                    .withSource(o.get("source"))
                    .withEvidenceRef(o.get("evidence-ref"))
                    .withProposedBy(o.get("by")));
            library.save(lib);

            out.println("已提出候选 " + record.id() + " → " + record.candidate().canonical()
                    + "（PROPOSED，未验证，不可用于生产）");
            out.println("下一步：validate --lib " + lib + " --id " + record.id() + " --evidence <evidence.json>");
            return EXIT_OK;
        }
    }

    private static int validate(Options o, PrintStream out, PrintStream err) throws IOException {
        String id = o.require("id", "候选 id（如 cand-0001）");
        CandidateEvidence evidence = evidenceFrom(o);
        Path lib = o.library();

        try (LibraryLock ignored = LibraryLock.acquire(lib)) {
            CandidateLibrary library = CandidateLibrary.load(lib);
            CandidateRecord record = library.submitForValidation(id, evidence);
            // 失败也要落盘：REJECTED 必须连同证据一起留痕，否则无法追溯"为什么被拒"。
            library.save(lib);

            out.println("候选 " + record.id() + " → " + record.state() + "（v" + record.version() + "）");
            CandidateValidator.Validation v = record.validation();
            if (v != null) {
                for (CandidateValidator.Finding f : v.findings()) {
                    out.printf("  [%s] %-20s %s%n", f.passed() ? "PASS" : "FAIL", f.ruleId(), f.detail());
                }
            }
            if (record.state() == CandidateState.VALIDATED) {
                out.println("下一步：approve --lib " + lib + " --id " + record.id()
                        + " --approver <审批人> --app-version <目标应用版本>");
                return EXIT_OK;
            }
            err.println("候选已置为 REJECTED（终态，不可再次验证；如页面已改版请重新 propose 一条新候选）");
            return EXIT_REJECTED;
        }
    }

    private static int approve(Options o, PrintStream out, PrintStream err) throws IOException {
        String id = o.require("id", "候选 id");
        String approver = o.require("approver", "审批人（审批必须落到具体的人）");
        String appVersion = o.require("app-version", "目标应用版本（审批必须绑定版本）");
        Path lib = o.library();

        try (LibraryLock ignored = LibraryLock.acquire(lib)) {
            CandidateLibrary library = CandidateLibrary.load(lib);
            CandidateRecord record = library.approve(id, approver, appVersion);
            library.save(lib);

            out.println("已审批 " + record.id() + " → APPROVED（approver=" + record.approvedBy()
                    + ", appVersion=" + record.approvalAppVersion() + ", v" + record.version() + "）");
            out.println("回注：locator --lib " + lib + " --target " + record.candidate().targetRef()
                    + " --app-version " + record.approvalAppVersion());
            if (record.validation() != null && record.validation().passed()) {
                err.println("注意：审批只确认「这条定位器可用于该应用版本」，页面改版后须重新取证并重新审批");
            }
            return EXIT_OK;
        }
    }

    private static int reject(Options o, PrintStream out, PrintStream err) throws IOException {
        return transition(o, out, CandidateState.REJECTED, "reject", "驳回");
    }

    private static int retire(Options o, PrintStream out, PrintStream err) throws IOException {
        return transition(o, out, CandidateState.RETIRED, "retire", "失效");
    }

    private static int transition(Options o, PrintStream out, CandidateState target,
                                  String command, String label) throws IOException {
        String id = o.require("id", "候选 id");
        String reason = o.require("reason", label + "原因（必须写明，会进审计链）");
        String actor = o.has("by") ? o.get("by") : currentUser();
        Path lib = o.library();

        try (LibraryLock ignored = LibraryLock.acquire(lib)) {
            CandidateLibrary library = CandidateLibrary.load(lib);
            CandidateRecord record = target == CandidateState.RETIRED
                    ? library.retire(id, reason, actor)
                    : library.reject(id, reason, actor);
            library.save(lib);
            out.println("已" + label + " " + record.id() + " → " + record.state()
                    + "（v" + record.version() + "，操作者=" + actor + "）：" + reason);
            return EXIT_OK;
        }
    }

    /** 审计链上的操作者默认取系统用户，避免一堆 "manual" 出现在事件链里。 */
    private static String currentUser() {
        String user = System.getProperty("user.name");
        return user == null || user.isBlank() ? "cli" : user;
    }

    // ------------------------------------------------------------------
    // 读命令
    // ------------------------------------------------------------------

    private static int list(Options o, PrintStream out) throws IOException {
        CandidateLibrary library = CandidateLibrary.load(o.library());
        String stateFilter = o.get("state");
        String targetFilter = o.get("target");
        CandidateState parsedState = stateFilter == null ? null : parseState(stateFilter);

        List<CandidateRecord> rows = library.all().stream()
                .filter(r -> parsedState == null || r.state() == parsedState)
                .filter(r -> targetFilter == null || r.candidate().targetRef().equals(targetFilter.trim()))
                .toList();

        if (o.flag("json")) {
            List<Map<String, Object>> payload = new ArrayList<>();
            for (CandidateRecord r : rows) {
                payload.add(summaryMap(r));
            }
            out.println(GSON.toJson(payload));
            return EXIT_OK;
        }
        if (rows.isEmpty()) {
            out.println("（没有符合条件的候选）");
            return EXIT_OK;
        }
        out.printf("%-9s %-10s %-22s %-40s %-16s %-12s %s%n",
                "ID", "STATE", "TARGET", "LOCATOR", "APPROVER", "APP-VER", "V");
        for (CandidateRecord r : rows) {
            out.printf("%-9s %-10s %-22s %-40s %-16s %-12s v%d%n",
                    r.id(), r.state(), r.candidate().targetRef(), r.candidate().canonical(),
                    dash(r.approvedBy()), dash(r.approvalAppVersion()), r.version());
        }
        return EXIT_OK;
    }

    private static int show(Options o, PrintStream out) throws IOException {
        String id = o.require("id", "候选 id");
        CandidateLibrary library = CandidateLibrary.load(o.library());
        CandidateRecord r = library.find(id)
                .orElseThrow(() -> new IllegalArgumentException("候选不存在：" + id));

        if (o.flag("json")) {
            out.println(GSON.toJson(detailMap(r)));
            return EXIT_OK;
        }

        out.println(r.id() + "  " + r.state() + "  v" + r.version());
        out.println("  目标语义  : " + r.candidate().targetRef());
        out.println("  定位符    : " + r.candidate().canonical());
        if (r.candidate().expectedRole() != null) {
            out.println("  期望角色  : " + r.candidate().expectedRole());
        }
        if (r.candidate().source() != null || r.candidate().evidenceRef() != null) {
            out.println("  来源      : " + dash(r.candidate().source())
                    + (r.candidate().evidenceRef() == null ? "" : "（证据 " + r.candidate().evidenceRef() + "）"));
        }
        if (r.candidate().proposedBy() != null) {
            out.println("  提出者    : " + r.candidate().proposedBy());
        }
        if (r.validation() == null) {
            out.println("  验证      : 未验证（不得审批）");
        } else {
            out.println("  验证      : " + (r.validation().passed() ? "PASS（5 条规则全部通过）" : "FAIL"));
            for (CandidateValidator.Finding f : r.validation().findings()) {
                out.printf("    [%s] %-20s %s%n", f.passed() ? "PASS" : "FAIL", f.ruleId(), f.detail());
            }
        }
        if (r.evidence() != null) {
            CandidateEvidence e = r.evidence();
            out.println("  证据      : matches=" + e.matches() + " visible=" + e.visible()
                    + " enabled=" + e.enabled() + " textMatched=" + e.textMatched());
            out.println("  业务断言  : " + dash(e.businessAssertion()) + " → "
                    + (e.assertionPassed() == null ? "未执行" : (e.assertionPassed() ? "PASS" : "FAIL")));
            if (e.detail() != null) {
                out.println("  证据细节  : " + e.detail());
            }
        }
        if (r.approvedBy() != null) {
            out.println("  审批      : " + r.approvedBy() + " @ appVersion=" + r.approvalAppVersion());
        }
        if (r.retireReason() != null) {
            out.println("  失效原因  : " + r.retireReason());
        }
        out.println("  事件链：");
        for (CandidateRecord.Event e : r.history()) {
            out.printf("    v%-3d %-10s %-12s %s%n", e.version(), e.state(), dash(e.actor()), dash(e.detail()));
        }
        return EXIT_OK;
    }

    private static int locator(Options o, PrintStream out, PrintStream err) throws IOException {
        String target = o.require("target", "目标语义引用");
        String appVersion = o.require("app-version", "目标应用版本");
        CandidateLibrary library = CandidateLibrary.load(o.library());

        Optional<CandidateRecord> hit = library.productionLocator(target, appVersion);
        if (hit.isEmpty()) {
            err.println("未解析到可用的生产定位器（target=" + target.trim() + ", appVersion=" + appVersion.trim() + "）");
            diagnose(library, target.trim(), appVersion.trim(), err);
            err.println("处理原则：停止并转人工。不得回退到其它候选，也不得降级使用旧版本审批。");
            return EXIT_NOT_RESOLVED;
        }
        // stdout 只留一行 canonical，方便 $(...) 直接取用
        out.println(hit.get().candidate().canonical());
        if (o.flag("verbose")) {
            CandidateRecord r = hit.get();
            err.println("# " + r.id() + " approver=" + r.approvedBy() + " appVersion=" + r.approvalAppVersion());
        }
        return EXIT_OK;
    }

    private static int audit(Options o, PrintStream out) throws IOException {
        Path lib = o.library();
        CandidateLibrary library = CandidateLibrary.load(lib);
        String md = CandidateAuditReport.toMarkdown(library, lib, Instant.now(Clock.systemUTC()));

        String outFile = o.get("out");
        if (outFile == null) {
            out.print(md);
            return EXIT_OK;
        }
        Path path = Path.of(outFile).toAbsolutePath();
        if (path.getParent() != null) {
            Files.createDirectories(path.getParent());
        }
        Files.writeString(path, md, StandardCharsets.UTF_8);
        out.println("审计报告已写入 " + path);
        return EXIT_OK;
    }

    // ------------------------------------------------------------------
    // 内部工具
    // ------------------------------------------------------------------

    /** 回注失败时逐条说明原因——但绝不因此挑一条"最像"的顶上。 */
    private static void diagnose(CandidateLibrary library, String target, String appVersion, PrintStream err) {
        List<CandidateRecord> sameTarget = library.all().stream()
                .filter(r -> r.candidate().targetRef().equals(target))
                .toList();
        if (sameTarget.isEmpty()) {
            err.println("  原因：该目标没有任何登记候选");
            return;
        }
        List<CandidateRecord> approved = sameTarget.stream().filter(CandidateRecord::approved).toList();
        if (approved.isEmpty()) {
            err.println("  原因：该目标有 " + sameTarget.size() + " 条候选，但无一条 APPROVED：");
            for (CandidateRecord r : sameTarget) {
                err.println("    - " + r.id() + " " + r.state());
            }
            return;
        }
        if (approved.size() > 1) {
            err.println("  原因：该版本存在 " + approved.size() + " 条 APPROVED，歧义即失败（不按分数/时间择一）：");
            for (CandidateRecord r : approved) {
                err.println("    - " + r.id() + " " + r.candidate().canonical());
            }
            return;
        }
        err.println("  原因：已审批的候选绑定的是另一个应用版本（审批版本="
                + approved.get(0).approvalAppVersion() + "，请求版本=" + appVersion + "）");
        err.println("  处理：页面改版后须重新取证 → 重新验证 → 重新审批，不复用旧版本审批");
    }

    private static LocatorSpec parseLocator(String canonical) {
        try {
            return LocatorSpec.parse(canonical);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("定位符无法解析：" + canonical
                    + "（期望形态 role=<role>;name=<name> 或 testid=/id=/text=/css=）", e);
        }
    }

    private static CandidateEvidence evidenceFrom(Options o) throws IOException {
        boolean hasItem = o.has("matches") || o.has("visible") || o.has("enabled")
                || o.has("text-matched") || o.has("assertion") || o.has("assertion-passed")
                || o.has("detail");

        if (o.has("evidence")) {
            if (hasItem) {
                throw new IllegalArgumentException("--evidence 与逐项证据参数不可同时使用（避免两份证据互相矛盾）");
            }
            return CandidateEvidenceJson.read(Path.of(o.get("evidence")));
        }
        if (!hasItem) {
            throw new IllegalArgumentException(
                    "必须提供证据：--evidence <file.json> 或逐项参数 "
                            + "(--matches/--visible/--enabled/--text-matched/--assertion/--assertion-passed)；"
                            + "无证据不得验证（fail-closed），也不会把候选写成 REJECTED");
        }

        CandidateEvidence.Builder b = CandidateEvidence.builder()
                .visible(o.bool("visible"))
                .enabled(o.bool("enabled"))
                .textMatched(o.bool("text-matched"))
                .businessAssertion(o.get("assertion"))
                .detail(o.get("detail"));
        if (o.has("matches")) {
            b.matches(o.intValue("matches"));
        }
        if (o.has("assertion-passed")) {
            b.assertionPassed(o.bool("assertion-passed"));
        }
        return b.build();
    }

    private static CandidateState parseState(String raw) {
        try {
            return CandidateState.valueOf(raw.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("未知状态：" + raw + "（可选 PROPOSED/VALIDATED/REJECTED/APPROVED/RETIRED）");
        }
    }

    private static Map<String, Object> summaryMap(CandidateRecord r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.id());
        m.put("state", r.state().name());
        m.put("targetRef", r.candidate().targetRef());
        m.put("locator", r.candidate().canonical());
        m.put("expectedRole", r.candidate().expectedRole());
        m.put("approvedBy", r.approvedBy());
        m.put("appVersion", r.approvalAppVersion());
        m.put("version", r.version());
        return m;
    }

    private static Map<String, Object> detailMap(CandidateRecord r) {
        Map<String, Object> m = summaryMap(r);
        m.put("source", r.candidate().source());
        m.put("evidenceRef", r.candidate().evidenceRef());
        m.put("proposedBy", r.candidate().proposedBy());
        m.put("retireReason", r.retireReason());

        CandidateValidator.Validation v = r.validation();
        if (v != null) {
            Map<String, Object> vm = new LinkedHashMap<>();
            vm.put("passed", v.passed());
            List<Map<String, Object>> findings = new ArrayList<>();
            for (CandidateValidator.Finding f : v.findings()) {
                Map<String, Object> fm = new LinkedHashMap<>();
                fm.put("ruleId", f.ruleId());
                fm.put("passed", f.passed());
                fm.put("detail", f.detail());
                findings.add(fm);
            }
            vm.put("findings", findings);
            m.put("validation", vm);
        }
        CandidateEvidence e = r.evidence();
        if (e != null) {
            Map<String, Object> em = new LinkedHashMap<>();
            em.put("matches", e.matches());
            em.put("visible", e.visible());
            em.put("enabled", e.enabled());
            em.put("textMatched", e.textMatched());
            em.put("businessAssertion", e.businessAssertion());
            em.put("assertionPassed", e.assertionPassed());
            em.put("detail", e.detail());
            m.put("evidence", em);
        }
        List<Map<String, Object>> history = new ArrayList<>();
        for (CandidateRecord.Event ev : r.history()) {
            Map<String, Object> hm = new LinkedHashMap<>();
            hm.put("version", ev.version());
            hm.put("state", ev.state().name());
            hm.put("actor", ev.actor());
            hm.put("detail", ev.detail());
            hm.put("at", ev.at() == null ? null : ev.at().toString());
            history.add(hm);
        }
        m.put("history", history);
        return m;
    }

    private static String dash(String s) {
        return s == null || s.isBlank() ? "-" : s;
    }

    private static void printUsage(PrintStream out) {
        out.println("候选定位器库 CLI（设计 §4.4 / P3）");
        out.println();
        out.println("用法： candidate <command> [--key value ...]");
        out.println();
        out.println("命令：");
        out.println("  propose   提出候选（→ PROPOSED，不做可信度判断）");
        out.println("      --target <ref>           目标语义引用，如 login.submit");
        out.println("      --locator <canonical>    定位符，如 \"role=button;name=Log on\"");
        out.println("      [--expected-role role]   目标语义要求的角色（给定时禁止用 CSS 等结构定位顶替）");
        out.println("      [--source s] [--evidence-ref r] [--by who]");
        out.println("  validate  用观测证据跑五条规则（全通过 → VALIDATED，否则 → REJECTED）");
        out.println("      --id <id>");
        out.println("      --evidence <file.json> | --matches n --visible true --enabled true");
        out.println("                                --text-matched true --assertion \"...\" --assertion-passed true");
        out.println("  approve   人工审批（VALIDATED → APPROVED，唯一的生产入口）");
        out.println("      --id <id> --approver <who> --app-version <version>");
        out.println("  reject    人工驳回（→ REJECTED）      --id <id> --reason \"...\" [--by who]");
        out.println("  retire    失效（→ RETIRED，终态）      --id <id> --reason \"...\" [--by who]");
        out.println("  list      列出候选                     [--state S] [--target ref] [--json]");
        out.println("  show      单条详情（含事件链）          --id <id> [--json]");
        out.println("  locator   回注：取出已审批定位符（stdout 仅一行 canonical）");
        out.println("      --target <ref> --app-version <version> [--verbose]");
        out.println("  audit     Markdown 审计报告            [--out report.md]");
        out.println();
        out.println("公共参数： --lib <file>   候选库文件（默认 " + DEFAULT_LIBRARY + "）");
        out.println();
        out.println("退出码： 0 成功 / 1 用法错误 / 2 被规则拒绝 / 3 未解析到生产定位器 / 4 并发冲突 / 5 I/O 错误");
    }

    // ------------------------------------------------------------------
    // 参数与锁
    // ------------------------------------------------------------------

    /** 极简参数表：{@code --k v}、{@code --k=v}、裸 {@code --flag}（值为 true）。 */
    private static final class Options {
        private final Map<String, String> values = new LinkedHashMap<>();

        static Options parse(String[] args, int from) {
            Options o = new Options();
            for (int i = from; i < args.length; i++) {
                String a = args[i];
                if (!a.startsWith("--")) {
                    throw new IllegalArgumentException("无法识别的参数：" + a + "（选项须以 -- 开头）");
                }
                int eq = a.indexOf('=');
                String key;
                String value;
                if (eq > 2) {
                    key = a.substring(2, eq);
                    value = a.substring(eq + 1);
                } else {
                    key = a.substring(2);
                    if (key.isEmpty()) {
                        throw new IllegalArgumentException("空选项名：" + a);
                    }
                    if (i + 1 < args.length && !args[i + 1].startsWith("--")) {
                        value = args[++i];
                    } else {
                        value = "true";
                    }
                }
                o.values.put(key, value);
            }
            return o;
        }

        boolean has(String key) {
            return values.containsKey(key);
        }

        String get(String key) {
            return values.get(key);
        }

        String require(String key, String description) {
            String v = values.get(key);
            if (v == null || v.isBlank()) {
                throw new IllegalArgumentException("缺少 --" + key + "：" + description);
            }
            // 裸 --key 会被解析成 "true"；取值型参数出现这个值，说明用户漏写了值。
            // 宁可报错也不要让「审批人 = true」这种记录进审计链。
            if ("true".equals(v)) {
                throw new IllegalArgumentException("--" + key + " 缺少取值：" + description);
            }
            return v;
        }

        boolean flag(String key) {
            return bool(key);
        }

        boolean bool(String key) {
            String v = values.get(key);
            if (v == null) {
                return false;
            }
            switch (v.trim().toLowerCase(java.util.Locale.ROOT)) {
                case "true": case "yes": case "y": case "1":
                    return true;
                case "false": case "no": case "n": case "0":
                    return false;
                default:
                    throw new IllegalArgumentException("--" + key + " 需要布尔值（true/false），实际：" + v);
            }
        }

        int intValue(String key) {
            String v = values.get(key);
            try {
                return Integer.parseInt(v.trim());
            } catch (RuntimeException e) {
                throw new IllegalArgumentException("--" + key + " 需要整数，实际：" + v);
            }
        }

        Path library() {
            String v = values.get("lib");
            if (v == null || v.isBlank()) {
                return DEFAULT_LIBRARY;
            }
            if ("true".equals(v)) {
                throw new IllegalArgumentException("--lib 缺少取值：候选库文件路径");
            }
            return Path.of(v);
        }
    }

    /** 写操作的排他锁；拿不到就失败，不排队也不强写。 */
    private static final class LibraryLock implements AutoCloseable {

        private final FileChannel channel;
        private final FileLock lock;

        private LibraryLock(FileChannel channel, FileLock lock) {
            this.channel = channel;
            this.lock = lock;
        }

        static LibraryLock acquire(Path libraryFile) throws IOException {
            Path absolute = libraryFile.toAbsolutePath();
            Path parent = absolute.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            // 锁文件刻意不删除：删除会让"另一个进程刚打开同一 inode"的窗口重新出现。
            Path lockFile = absolute.resolveSibling(absolute.getFileName() + ".lock");
            FileChannel channel = FileChannel.open(lockFile,
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            FileLock lock;
            try {
                lock = channel.tryLock();
            } catch (OverlappingFileLockException e) {
                channel.close();
                throw new ConflictException("另一个线程正在修改 " + libraryFile.toAbsolutePath());
            } catch (IOException e) {
                channel.close();
                throw new ConflictException("无法获取候选库锁：" + lockFile + " - " + e.getMessage());
            }
            if (lock == null) {
                channel.close();
                throw new ConflictException("另一个进程正在修改 " + libraryFile.toAbsolutePath()
                        + "；已放弃本次写入（无锁覆盖会静默丢掉别人的审批记录）");
            }
            return new LibraryLock(channel, lock);
        }

        @Override
        public void close() throws IOException {
            try {
                lock.release();
            } finally {
                channel.close();
            }
        }
    }

    /** 并发冲突（区别于 I/O 错误，便于脚本决定"重试"还是"报错"）。 */
    private static final class ConflictException extends RuntimeException {
        ConflictException(String message) {
            super(message);
        }
    }
}
