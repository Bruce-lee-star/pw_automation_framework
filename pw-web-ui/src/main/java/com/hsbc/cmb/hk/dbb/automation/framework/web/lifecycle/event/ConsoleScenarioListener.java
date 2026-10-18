package com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.event;

/**
 * 控制台逐场景彩色 reporter——对齐 框架 控制台观感：运行开始、**场景开始（哪条 case 正在执行）**、
 * 场景结果（✔ 绿 / ✘ 红 / ⊘ 黄，带耗时）。去 框架 后由本监听器补回"开始执行 + 结果 + 颜色"可视性。
 *
 * <p><b>为何用 System.out 而非 logger</b>：要的是 框架 式干净彩色行（无时间戳/级别前缀），
 * 与 logback 日志流并存在同一 STDOUT，按派发顺序交织；步骤级结果已由 Cucumber 自打 {@code ✔ Given...}，
 * 故本监听器只做<b>场景级</b>与运行级横幅，避免与 Cucumber 步骤输出重复。</p>
 *
 * <p><b>可配置</b>：系统属性 {@code playwright.console.report.enabled}（默认 {@code true}）；
 * CI / 管道场景可 {@code -Dplaywright.console.report.enabled=false} 关闭，避免 ANSI 码入日志。</p>
 *
 * <p><b>安全</b>：失败行只打异常类简名，不打 message（message 可能含 URL/token，详情以脱敏日志为准）。</p>
 *
 * <p><b>线程</b>：场景起始时间用 ThreadLocal 记录；并行场景各线程独立，控制台交织属预期（同 框架）。</p>
 */
public final class ConsoleScenarioListener implements FrameworkLifecycleListener {

    private static final String ENABLE_PROP = "playwright.console.report.enabled";

    /** 运行时读取开关（默认 true），便于测试内即时 toggle 与运行期关闭。 */
    private static boolean enabled() {
        return Boolean.parseBoolean(System.getProperty(ENABLE_PROP, "true"));
    }

    // ANSI（IDEA / 多数终端渲染；关闭开关即不产出任何转义）
    private static final String RESET = "\u001B[0m";
    private static final String GREEN = "\u001B[1;32m";
    private static final String RED = "\u001B[1;31m";
    private static final String YELLOW = "\u001B[1;33m";
    private static final String CYAN = "\u001B[1;36m";

    private final ThreadLocal<Long> scenarioStartNanos = new ThreadLocal<>();

    @Override
    public void onTestRunStarted() {
        if (!enabled()) {
            return;
        }
        line(CYAN, "════════════════════ TEST RUN STARTED ════════════════════");
    }

    @Override
    public void onScenarioStarted(LifecycleScenarioInfo info) {
        if (!enabled()) {
            return;
        }
        scenarioStartNanos.set(System.nanoTime());
        String name = info == null ? "?" : info.name();
        String tags = (info == null || info.tags() == null || info.tags().isEmpty())
                ? "" : "   " + String.join(" ", info.tags());
        line(CYAN, "▶ SCENARIO STARTED : " + name + tags);
    }

    @Override
    public void onScenarioFinished(LifecycleScenarioResult result) {
        if (!enabled()) {
            return;
        }
        String name = (result == null || result.info() == null) ? "?" : result.info().name();
        LifecycleStatus status = result == null ? LifecycleStatus.PASSED : result.status();
        String dur = elapsed();
        switch (status) {
            case PASSED:
                line(GREEN, "✔ SCENARIO PASSED : " + name + dur);
                break;
            case FAILED:
            case AMBIGUOUS:
                line(RED, "✘ SCENARIO FAILED : " + name + dur + causeSuffix(result));
                break;
            case SKIPPED:
            case IGNORED:
                line(YELLOW, "⊘ SCENARIO " + status + " : " + name + dur);
                break;
            case PENDING:
            default:
                line(YELLOW, "⊘ SCENARIO " + status + " : " + name + dur);
                break;
        }
    }

    @Override
    public void onTestRunFinished() {
        if (!enabled()) {
            return;
        }
        line(CYAN, "════════════════════ TEST RUN FINISHED ═══════════════════");
    }

    // ---------------------------------------------------------------- internals

    private static void line(String color, String msg) {
        System.out.println(color + msg + RESET);
    }

    /** 距场景开始的耗时（消费后清除，防跨场景串值）。 */
    private String elapsed() {
        Long start = scenarioStartNanos.get();
        if (start == null) {
            return "";
        }
        scenarioStartNanos.remove();
        return "  (" + (System.nanoTime() - start) / 1_000_000 + " ms)";
    }

    /** 失败行附异常类简名（不含 message，避免泄露 URL/token）。 */
    private static String causeSuffix(LifecycleScenarioResult result) {
        Throwable t = result == null ? null : result.error();
        return t == null ? "" : "  ↳ " + t.getClass().getSimpleName();
    }
}
