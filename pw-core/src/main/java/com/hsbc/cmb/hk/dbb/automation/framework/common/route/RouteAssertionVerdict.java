package com.hsbc.cmb.hk.dbb.automation.framework.common.route;

/**
 * 「本 case 已定案、但 Cucumber 尚未感知」的 MONITOR 断言失败登记（core 层，跨模块只读）。
 *
 * <p><b>为什么需要它</b>：MONITOR 断言失败若未能在 glue 步骤方法执行期间由
 * {@link RouteAssertionCheckpoint#checkNow(Object)} 抛出（例如最后一步内完全无框架业务操作），
 * 异常就无法沿 {@code executeStep} 冒泡进 Cucumber 的 {@code TestCaseState}。此后
 * {@code io.cucumber.plugin.event.Result} 已由 {@code state.getStatus()} 构造完毕且
 * <b>不可变</b>（{@code final} 类 + {@code final} 字段），{@code backend.TestCaseState}
 * 又只有只读方法——事件层无法改写 case 结果。本类把这类失败登记下来，由
 * {@code CucumberWithPw} 在 JUnit {@code RunNotifier} 层投影成
 * {@code fireTestFailure}，使 case 判红，而非仅留痕。</p>
 *
 * <p><b>线程模型（与上一轮「零共享状态」评审不冲突）</b>：失败数据的<b>权威归属</b>始终是
 * {@code BrowserContext} 对应的消费式队列；本类<b>不暂存失败数据</b>，只保存已经
 * {@code drain} 出来、尚未被 Cucumber 感知的<b>判决摘要</b>。其生命周期被严格限制在
 * 「{@code TestCaseFinished} 事件 → 紧随其后的 {@code RunNotifier.fireTestFinished}」
 * 这段<b>同一线程、同一同步调用栈</b>内（{@code FeatureRunner.runChild} 用
 * {@code try/finally} 包裹 {@code child.run(notifier)}，两者必然同线程相邻）。
 * 这不依赖「跨多个生命周期事件的派发顺序」，也因此在 Cucumber 并行执行下按线程天然隔离；
 * 场景开始与 {@code fireTestFinished} 都强制 {@link #clear()}，不会串到下一个 case。</p>
 *
 * <p><b>消费式</b>：{@link #takeAndClear()} 取走即清空；{@link #peek()} 只读，供报告插件在同一
 * 事件窗口内判定结果。同一批失败只会投影一次。</p>
 *
 * @apiNote 框架内部 seam；业务代码不得直接调用。
 */
public final class RouteAssertionVerdict {

    private static final String PREFIX =
            "Route MONITOR assertion failed - failing current case:" + System.lineSeparator();

    private static final ThreadLocal<State> STATE = ThreadLocal.withInitial(State::new);

    private RouteAssertionVerdict() {
    }

    /**
     * 登记一批已消费但尚未被 Cucumber 感知的失败摘要。同一 case 多次登记会合并，
     * 最终只投影为一个 {@link AssertionError}。
     *
     * @param report 失败摘要（null / 空白忽略）
     */
    public static void record(String report) {
        if (report == null || report.isBlank()) {
            return;
        }
        State state = STATE.get();
        if (state.body.length() > 0) {
            state.body.append(System.lineSeparator());
        }
        state.body.append(report);
        state.verdict = null;
    }

    /** 只读查看当前 case 的判决；无登记返回 {@code null}。不消费。 */
    public static AssertionError peek() {
        State state = STATE.get();
        if (state.body.length() == 0) {
            return null;
        }
        if (state.verdict == null) {
            state.verdict = new AssertionError(PREFIX + state.body);
        }
        return state.verdict;
    }

    /** 取走并清空当前 case 的判决（消费式幂等）。 */
    public static AssertionError takeAndClear() {
        try {
            return peek();
        } finally {
            clear();
        }
    }

    /** 清除当前线程的登记（场景边界与测试隔离用）。 */
    public static void clear() {
        STATE.remove();
    }

    private static final class State {
        private final StringBuilder body = new StringBuilder();
        private AssertionError verdict;
    }
}
