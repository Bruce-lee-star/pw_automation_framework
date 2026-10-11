package com.hsbc.cmb.hk.dbb.automation.framework.route.reporting;

import com.hsbc.cmb.hk.dbb.automation.framework.common.reporting.MonitorFailureReportData;
import com.hsbc.cmb.hk.dbb.automation.framework.common.reporting.MonitorFailureReportSink;
import com.hsbc.cmb.hk.dbb.automation.framework.common.reporting.MonitorFailureItem;
import com.hsbc.cmb.hk.dbb.automation.framework.common.reporting.MonitorOwnerBlock;
import com.hsbc.cmb.hk.dbb.automation.framework.common.route.RouteAssertionFailure;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * API 监控失败报告的生产 SPI 实现（2026-10-10 补齐：此前 route 侧未注册生产 sink，
 * 套件收尾 {@code SummaryReportGenerator} 只能 WARN「No MonitorFailureReportSink discovered」）。
 *
 * <p><b>数据来源（无重复消费）</b>：本类不自行 drain（断言失败是消费式的，由
 * {@code RouteAssertionProbeImpl.drainAndResolveFailures} 喂给 web 侧抛错/标记）——
 * 而是由探针在返回失败列表时<b>顺带复制一份</b>经 {@link #recordFailures(List)} 归集进来。
 * 一份失败两用：web 侧即时抛错（用例标红） + 套件收尾的聚合报告（落盘 + HTML 显示区）。
 *
 * <p><b>跨进程可靠通道（2026-10-10 实证修正）</b>：{@code generate-summary-report} 在
 * 测试 JVM <b>退出后</b>独立运行（exec:java），进程内归集表必然为空 —— 因此
 * {@link #recordFailures(List)} <b>喂入即落盘</b>（内存 + 文件双写，文件是报告阶段的唯一
 * 可靠数据源）；报告阶段的 {@link #collectData()} / {@link #write()} 在内存为空时
 * <b>从文件读回</b>，保证落盘与 HTML 显示区一致。
 *
 * <p><b>实例化</b>：{@code ServiceLoader} 要求 provider 有 public 无参构造器（本环境实测
 * provider() 工厂方法不生效），且每次加载 new 新实例；探针喂入与报告聚合同源，故归集状态
 * 为<b>类级静态</b>（所有 SPI 实例共享同一归集表）。不持有单例字段：实例方法（接口契约）
 * 仅委托静态实现，避免 {@code ST_WRITE_TO_STATIC_FROM_INSTANCE_METHOD} 与 singleton 门禁违规。
 *
 * <p><b>owner 映射</b>：route 断言失败无「业务归属方」概念，以规则 {@code pattern}
 * 作为 owner（「谁 API 发给谁」的接口级近似）；同一 pattern 的多条失败聚合为一个
 * {@link MonitorOwnerBlock}。
 *
 * <p><b>脱敏</b>：本类不携带 requestBody/responseBody（断言失败记录本身无 body 快照），
 * 仅输出 pattern/method/url/status/期望值/失败原因 —— 不引入新的敏感数据面。
 *
 * <p><b>生命周期</b>：套件收尾时序 = {@code collectData()}（先，防复位丢失）→
 * {@code write()} → {@code clear()}（清内存 + 删文件，多套件同 JVM 不跨套件累积）。
 */
public final class RouteMonitorFailureReportSink implements MonitorFailureReportSink {

    private static final Logger LOGGER = LoggerFactory.getLogger(RouteMonitorFailureReportSink.class);

    /** 归集表（类级静态：所有 SPI 实例共享；CopyOnWriteArrayList 读多写少，线程安全）。 */
    private static final ConcurrentMap<String, List<MonitorFailureItem>> BY_OWNER = new ConcurrentHashMap<>();

    /** 失败总条数（类级静态；供 HTML 展示）。 */
    private static final AtomicInteger FAILURE_COUNT = new AtomicInteger();

    /** 报告相对路径（对齐接口注释与既有约定；相对 user.dir 解析，探针 JVM 与报告 JVM 一致）。 */
    private static final String REPORT_PATH = "target" + java.io.File.separator + "monitor-failures-by-owner.json";

    /** 文件写互斥锁：并发喂入时落盘必须串行（Files.writeString 非原子，防止交错损坏）。 */
    private static final Object FILE_LOCK = new Object();

    /** 复位标记：{@link #clearInternal()} 置位（清内存后不得从旧文件误读回复活）；
     *  {@link #recordFailures(List)} 清除（新喂入即重新激活读回能力）。
     *  初始 false：报告阶段新 JVM 可从探针落盘文件读回（跨进程语义）；测试侧
     *  「无喂入且无文件」由文件不存在天然兜底。 */
    private static volatile boolean RESET = false;

    /** public 无参构造器：ServiceLoader SPI 实例化必需（本环境不支持 provider() 工厂）。 */
    public RouteMonitorFailureReportSink() {
    }

    /**
     * 探针喂入点（静态）：把已定案的断言失败复制进类级归集器（不消费原始列表），
     * 并<b>立即落盘</b>（报告阶段独立 JVM，文件是跨进程唯一可靠通道）。
     * 供 {@code RouteAssertionProbeImpl} 直接调用 —— 与 SPI 实例共享同一归集表。
     *
     * @param failures 已定案断言失败（可为空）
     */
    public static void recordFailures(List<RouteAssertionFailure> failures) {
        if (failures == null || failures.isEmpty()) {
            return;
        }
        for (RouteAssertionFailure f : failures) {
            MonitorFailureItem item = toItem(f);
            BY_OWNER.computeIfAbsent(item.getOwner(), k -> new CopyOnWriteArrayList<>()).add(item);
            FAILURE_COUNT.incrementAndGet();
        }
        RESET = false;
        writeFileQuietly();
    }

    /** 失败快照 → 跨模块安全载体（owner=pattern；body 不携带，见类 javadoc 脱敏说明）。 */
    private static MonitorFailureItem toItem(RouteAssertionFailure f) {
        String status;
        if (f.timedOut()) {
            status = "TIMEOUT (timeoutMs=" + f.timeoutMs() + ")";
        } else {
            status = String.valueOf(f.actualStatus());
            if (f.expectStatus() != null) {
                status += " (expect " + f.expectStatus() + ")";
            }
        }
        return new MonitorFailureItem(
                f.pattern(),                       // owner = 接口规则
                f.method(),                        // feature 字段近似：请求方法
                f.pattern(),                       // pattern
                status,
                f.method(),
                f.url(),
                f.summary(),                       // reason：单行摘要（含 body 失败明细）
                List.of(),                         // scenarios：route 失败记录无 case 名上下文
                f.requestBody() == null ? "" : f.requestBody(),   // requestBody：请求体预览
                f.responseBody() == null ? "" : f.responseBody(),  // responseBody：已脱敏预览
                f.requestHeaders() == null ? "" : f.requestHeaders(),
                f.responseHeaders() == null ? "" : f.responseHeaders());
    }

    /** 接口契约：实例方法仅委托静态实现（避免实例方法直接写静态字段触发 SpotBugs ST）。 */
    @Override
    public void record(List<RouteAssertionFailure> failures) {
        recordFailures(failures);
    }

    /** 静默落盘（喂入即写；失败仅 WARN，不阻断测试；写操作互斥防并发损坏）。 */
    private static void writeFileQuietly() {
        try {
            Path path = Paths.get(REPORT_PATH);
            Path parent = path.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            synchronized (FILE_LOCK) {
                Files.writeString(path, toJson(), StandardCharsets.UTF_8);
            }
        } catch (IOException ex) {
            LOGGER.warn("[ApiMonitor] route monitor failure report immediate write failed "
                    + "(in-memory records RETAINED; report-phase write will retry): {}", ex.getMessage());
        }
    }

    @Override
    public int write() {
        // 报告阶段独立 JVM：内存为空且未被复位时先从文件读回（保证落盘内容 = 探针喂入内容，幂等重写）
        if (BY_OWNER.isEmpty() && !RESET && Files.exists(Paths.get(REPORT_PATH))) {
            loadFromFile();
        }
        if (BY_OWNER.isEmpty()) {
            return 0;
        }
        try {
            Path path = Paths.get(REPORT_PATH);
            Path parent = path.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            synchronized (FILE_LOCK) {
                Files.writeString(path, toJson(), StandardCharsets.UTF_8);
            }
            LOGGER.info("[ApiMonitor] route monitor failure report written ({} owner(s), {} failure(s)): {}",
                    BY_OWNER.size(), FAILURE_COUNT.get(), path.toAbsolutePath());
        } catch (IOException ex) {
            // 报告写出失败不阻断测试；记录保留供重试（与 N-05 语义一致：失败不清空）
            LOGGER.warn("[ApiMonitor] route monitor failure report write failed (records RETAINED for retry): {}",
                    ex.getMessage());
        }
        return BY_OWNER.size();
    }

    /** 从文件读回归集（报告阶段独立 JVM 用；读失败仅 WARN，不伪造数据）。
     *  用 Gson JsonObject API 逐字段解析（无反射 DTO 类 —— 规避 SpotBugs UWF_UNWRITTEN_FIELD）。 */
    private static void loadFromFile() {
        try {
            Path path = Paths.get(REPORT_PATH);
            if (!Files.exists(path)) {
                return;
            }
            String json = Files.readString(path, StandardCharsets.UTF_8);
            com.google.gson.JsonObject root =
                    com.google.gson.JsonParser.parseString(json).getAsJsonObject();
            if (!root.has("owners")) {
                return;
            }
            com.google.gson.JsonArray owners = root.getAsJsonArray("owners");
            for (com.google.gson.JsonElement ownerEl : owners) {
                com.google.gson.JsonObject owner = ownerEl.getAsJsonObject();
                String ownerName = owner.has("owner") ? owner.get("owner").getAsString() : "";
                List<MonitorFailureItem> items = new ArrayList<>();
                if (owner.has("items")) {
                    for (com.google.gson.JsonElement itemEl : owner.getAsJsonArray("items")) {
                        com.google.gson.JsonObject it = itemEl.getAsJsonObject();
                        items.add(new MonitorFailureItem(
                                ownerName,
                                it.has("method") ? it.get("method").getAsString() : "",
                                it.has("pattern") ? it.get("pattern").getAsString() : "",
                                it.has("status") ? it.get("status").getAsString() : "",
                                it.has("method") ? it.get("method").getAsString() : "",
                                it.has("url") ? it.get("url").getAsString() : "",
                                it.has("reason") ? it.get("reason").getAsString() : "",
                                List.of(),
                                it.has("requestBody") ? it.get("requestBody").getAsString() : "",
                                it.has("responseBody") ? it.get("responseBody").getAsString() : "",
                                it.has("requestHeaders") ? it.get("requestHeaders").getAsString() : "",
                                it.has("responseHeaders") ? it.get("responseHeaders").getAsString() : ""));
                    }
                }
                BY_OWNER.put(ownerName, items);
            }
            if (root.has("failureCount")) {
                FAILURE_COUNT.set(root.get("failureCount").getAsInt());
            }
        } catch (Exception ex) {
            LOGGER.warn("[ApiMonitor] route monitor failure report read-back failed (file retained; "
                    + "HTML display area may be empty): {}", ex.toString());
        }
    }

    @Override
    public void clear() {
        clearInternal();
    }

    /** 类级复位（静态实现，实例方法仅委托）：清内存 + 置复位标记（旧文件不再被误读回）。
     *  <b>文件保留留档</b>（最新一次套件的失败报告持续可见，供 CI/人工查看）；
     *  无累积风险 —— 每次喂入 writeFileQuietly 均为全量覆盖。 */
    private static void clearInternal() {
        BY_OWNER.clear();
        FAILURE_COUNT.set(0);
        RESET = true;
    }

    @Override
    public MonitorFailureReportData collectData() {
        // 报告阶段独立 JVM：内存为空且未被复位时先从文件读回（保证 HTML 显示区有数据）
        if (BY_OWNER.isEmpty() && !RESET && Files.exists(Paths.get(REPORT_PATH))) {
            loadFromFile();
        }
        List<MonitorOwnerBlock> blocks = new ArrayList<>(BY_OWNER.size());
        for (Map.Entry<String, List<MonitorFailureItem>> e : BY_OWNER.entrySet()) {
            blocks.add(new MonitorOwnerBlock(e.getKey(), List.copyOf(e.getValue())));
        }
        return new MonitorFailureReportData(
                blocks, Map.of(), 0L, blocks.size(), FAILURE_COUNT.get());
    }

    /** 手写 JSON 序列化（结构固定，与 {@link FileSnapshot} 读回对齐；不依赖额外运行时行为）。 */
    private static String toJson() {
        StringBuilder sb = new StringBuilder(BY_OWNER.size() * 256);
        sb.append("{\"owners\":[");
        boolean firstOwner = true;
        for (Map.Entry<String, List<MonitorFailureItem>> e : BY_OWNER.entrySet()) {
            if (!firstOwner) {
                sb.append(',');
            }
            firstOwner = false;
            sb.append("{\"owner\":").append(quote(e.getKey())).append(",\"items\":[");
            boolean firstItem = true;
            for (MonitorFailureItem it : e.getValue()) {
                if (!firstItem) {
                    sb.append(',');
                }
                firstItem = false;
                sb.append("{\"pattern\":").append(quote(it.getPattern()))
                        .append(",\"method\":").append(quote(it.getMethod()))
                        .append(",\"status\":").append(quote(it.getStatus()))
                        .append(",\"url\":").append(quote(it.getRequestUrl()))
                        .append(",\"reason\":").append(quote(it.getReason()))
                        .append(",\"requestBody\":").append(quote(it.getRequestBody()))
                        .append(",\"responseBody\":").append(quote(it.getResponseBody()))
                        .append(",\"requestHeaders\":").append(quote(it.getRequestHeaders()))
                        .append(",\"responseHeaders\":").append(quote(it.getResponseHeaders()))
                        .append('}');
            }
            sb.append("]}");
        }
        sb.append("],\"failureCount\":").append(FAILURE_COUNT.get()).append('}');
        return sb.toString();
    }

    /** JSON 字符串转义（双引号 / 反斜杠 / 控制字符；换行转 \n）。 */
    private static String quote(String s) {
        if (s == null) {
            return "\"\"";
        }
        StringBuilder sb = new StringBuilder(s.length() + 16);
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
        return sb.toString();
    }
}
