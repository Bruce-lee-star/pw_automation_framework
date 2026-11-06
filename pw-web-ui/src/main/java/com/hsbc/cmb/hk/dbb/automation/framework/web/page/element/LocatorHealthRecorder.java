package com.hsbc.cmb.hk.dbb.automation.framework.web.page.element;

import com.hsbc.cmb.hk.dbb.automation.framework.core.lifecycle.ShutdownCoordinator;
import com.hsbc.cmb.hk.dbb.automation.framework.web.listener.CurrentScenario;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 定位健康度采集器（B 方案的「证据层」）：记录<b>用过的选择器</b>与<b>Playwright strict 违例</b>。
 *
 * <h3>为什么要采集</h3>
 * <p>Playwright 的 strict 模式已经强制「一次定位只能命中一个元素」，多命中会抛
 * {@code strict mode violation: locator('...') resolved to N elements}。也就是说
 * 「定位唯一」在运行期早已是既成事实，缺的不是能力，而是把它<b>沉淀为可聚合、可门禁的数据</b>：
 * 现在的表现只是某条用例偶发失败，没人能回答「哪些选择器不稳定、这个 sprint 新增了几个」。</p>
 *
 * <p>本类只做采集（每次元素操作记一次用量；strict 违例记一条证据），<b>不改变任何执行语义</b>：
 * 不额外查 DOM、不抛异常、不影响原异常链路。写盘见 {@link LocatorHealthReportWriter}（JVM 退出时一次）。</p>
 *
 * <p><b>有界</b>：选择器条目 {@value #MAX_SELECTORS}、违例 {@value #MAX_VIOLATIONS}（超出即丢弃并计数），
 * 防止长跑用例把内存撑爆。<b>开关</b>：{@code -Dpw.locator.health.enabled=false} 关闭（默认开）。</p>
 *
 * <p>线程安全（ConcurrentHashMap / 有界并发队列）；幂等 register 关闭钩子。</p>
 */
public final class LocatorHealthRecorder {

    /** 采样选择器条目上界。 */
    public static final int MAX_SELECTORS = 5000;
    /** 违例记录上界。 */
    public static final int MAX_VIOLATIONS = 200;
    /** 关闭开关（系统属性；默认开启）。 */
    public static final String ENABLED_PROPERTY = "pw.locator.health.enabled";

    private static final Logger LOGGER = LoggerFactory.getLogger(LocatorHealthRecorder.class);
    private static final Pattern STRICT_VIOLATION =
            Pattern.compile("(?is)strict mode violation.*?resolved to (\\d+) elements?");

    private static final Map<String, Health> HEALTH = new ConcurrentHashMap<>();
    private static final ConcurrentLinkedDeque<Violation> VIOLATIONS = new ConcurrentLinkedDeque<>();
    private static final Set<String> VIOLATION_KEYS = ConcurrentHashMap.newKeySet();
    private static final AtomicBoolean DROPPED = new AtomicBoolean(false);

    static {
        // 写盘在 JVM 退出时执行一次（与其它诊断同一编排器，单任务超时不影响主流程；同名注册幂等）
        ShutdownCoordinator.register(ShutdownCoordinator.ORDER_DIAGNOSTICS, "locator-health",
                LocatorHealthReportWriter::writeDefaultIfAny);
    }

    private LocatorHealthRecorder() {
    }

    /** 单条选择器健康度。 */
    public record SelectorHealth(String selector, String kind, long uses, long violations, int maxMatchCount) {
    }

    /** 一条 strict 违例证据。 */
    public record Violation(String scenario, String selector, String kind, String operation,
                            int matchCount, String thread, Instant at) {
    }

    /** 是否开启采集（每次实时读系统属性，测试可在运行期 toggle）。 */
    public static boolean enabled() {
        String raw = System.getProperty(ENABLED_PROPERTY);
        return raw == null || !"false".equalsIgnoreCase(raw.trim());
    }

    /** 记一次「用到了该选择器」（不触碰 DOM，纯内存计数）。 */
    public static void recordUse(String selector) {
        if (!enabled() || selector == null || selector.isBlank()) {
            return;
        }
        String key = selector.trim();
        Health health = HEALTH.computeIfAbsent(key, s -> new Health(s, kindOf(s)));
        health.uses.incrementAndGet();
        if (HEALTH.size() > MAX_SELECTORS) {
            DROPPED.set(true);
        }
    }

    /**
     * 记一次操作失败：仅当错误是 <b>strict 违例</b>（一次定位命中多个元素）时留证。
     *
     * <p>其余失败（超时/不存在/导航中断）不在此收口——它们已有既有诊断链路，重复记录只会制造噪音。</p>
     */
    public static void recordFailure(Throwable error, String selector, String operation) {
        if (!enabled() || error == null || selector == null || selector.isBlank()) {
            return;
        }
        int matchCount = strictMatchCount(error);
        if (matchCount <= 0) {
            return;
        }
        String key = selector.trim();
        Health health = HEALTH.computeIfAbsent(key, s -> new Health(s, kindOf(s)));
        health.violations.incrementAndGet();
        health.maxMatchCount.accumulateAndGet(matchCount, Math::max);

        String scenario = CurrentScenario.nameOrUnknown();
        String dedupe = scenario + '|' + key + '|' + operation + '|' + matchCount;
        if (!VIOLATION_KEYS.add(dedupe)) {
            return;
        }
        if (VIOLATIONS.size() >= MAX_VIOLATIONS) {
            VIOLATION_KEYS.remove(dedupe);
            DROPPED.set(true);
            return;
        }
        VIOLATIONS.addLast(new Violation(scenario, key, kindOf(key), operation, matchCount,
                Thread.currentThread().getName(), Instant.now()));
        LOGGER.debug("[locator-health] strict violation: '{}' resolved to {} elements ({})",
                key, matchCount, operation);
    }

    /** 解析 strict 违例消息里的命中数；非 strict 违例返回 0。 */
    public static int strictMatchCount(Throwable error) {
        Throwable current = error;
        int guard = 0;
        while (current != null && guard++ < 8) {
            String message = current.getMessage();
            if (message != null) {
                Matcher m = STRICT_VIOLATION.matcher(message);
                if (m.find()) {
                    try {
                        return Integer.parseInt(m.group(1));
                    } catch (NumberFormatException e) {
                        return 2; // 文案命中但数字异常：按「至少 2 个」记，避免丢证
                    }
                }
            }
            current = current.getCause() == current ? null : current.getCause();
        }
        return 0;
    }

    /** 按选择器快照（violations 降序 → uses 降序），不可变。 */
    public static List<SelectorHealth> snapshot() {
        List<SelectorHealth> out = new ArrayList<>(HEALTH.size());
        for (Health health : HEALTH.values()) {
            out.add(health.toRecord());
        }
        out.sort(Comparator.comparingLong(SelectorHealth::violations).reversed()
                .thenComparing(Comparator.comparingLong(SelectorHealth::uses).reversed())
                .thenComparing(SelectorHealth::selector));
        return List.copyOf(out);
    }

    /** 违例快照（按发生顺序），不可变。 */
    public static List<Violation> violations() {
        return List.copyOf(new LinkedHashSet<>(VIOLATIONS));
    }

    /** 是否发生过截断（条目超上界）。 */
    public static boolean truncated() {
        return DROPPED.get();
    }

    /** 清空（测试/重 init 用）。 */
    public static void reset() {
        HEALTH.clear();
        VIOLATIONS.clear();
        VIOLATION_KEYS.clear();
        DROPPED.set(false);
    }

    /** 选择器类别：role= / xpath / css。 */
    static String kindOf(String selector) {
        String lower = selector.toLowerCase(Locale.ROOT);
        if (lower.startsWith("role=") || lower.startsWith("getbyrole")) {
            return "role";
        }
        if (lower.startsWith("xpath=") || lower.startsWith("//") || lower.startsWith("(")
                || lower.startsWith("./")) {
            return "xpath";
        }
        return "css";
    }

    /** 可变计数器（对外只暴露不可变快照）。 */
    private static final class Health {
        private final String selector;
        private final String kind;
        private final java.util.concurrent.atomic.AtomicLong uses =
                new java.util.concurrent.atomic.AtomicLong();
        private final java.util.concurrent.atomic.AtomicLong violations =
                new java.util.concurrent.atomic.AtomicLong();
        private final java.util.concurrent.atomic.AtomicInteger maxMatchCount =
                new java.util.concurrent.atomic.AtomicInteger();

        private Health(String selector, String kind) {
            this.selector = selector;
            this.kind = kind;
        }

        private SelectorHealth toRecord() {
            return new SelectorHealth(selector, kind, uses.get(), violations.get(), maxMatchCount.get());
        }
    }
}
