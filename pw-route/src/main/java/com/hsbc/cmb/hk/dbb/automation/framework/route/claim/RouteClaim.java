package com.hsbc.cmb.hk.dbb.automation.framework.route.claim;

import com.microsoft.playwright.Route;

import java.util.concurrent.atomic.AtomicReference;

/**
 * 路由索赔 —— 单个 Route 事件的单所有者状态机。
 *
 * <p>为什么必须 CAS（对应 playwright-java-1.62.0 源码事实）：{@code RouteImpl.handled}
 * 是普通非原子 boolean（RouteImpl.java:34,246-251），并发终结存在 TOCTOU——两个线程同时
 * 通过 {@code startHandling()} 检查就会向驱动发送两条终结命令，第二条在驱动侧抛
 * "Route is already handled!"。框架层必须用原子状态机把「谁有权终结」锁死。
 *
 * <p>状态迁移（全部 CAS，单方向）：
 * <pre>
 *   NEW ──toIoAwait()──▶ IO_AWAIT ──tryTerminal()──▶ HANDLED / RELEASED
 *    └─────────────────────tryTerminal()───────────▶ HANDLED / RELEASED
 * </pre>
 * <ul>
 *   <li>{@code NEW}：已索赔，事件线程尚未决定终结方式；</li>
 *   <li>{@code IO_AWAIT}：已占挂起额度，在调用线程就地延迟终结（DELAY / MOCK intercept）；</li>
 *   <li>{@code HANDLED}：已终结（resume / fulfill / abort 成功）；</li>
 *   <li>{@code RELEASED}：放弃终结（fallback 放行 / 异常逃生）。</li>
 * </ul>
 */
public final class RouteClaim {

    /** 状态（不可直接暴露可变引用）。 */
    enum State { NEW, IO_AWAIT, HANDLED, RELEASED }

    /**
     * 转发状态（volatile，单写多读）：本 claim 是否已<b>向上游 / 真实网络发出业务请求</b>。
     *
     * <p>由唯一所有者线程（mockTask / deliver / mockOrResume）在「请求已真实发出」之后置位：
     * <ul>
     *   <li>MOCK intercept：{@code route.fetch()} 成功取回应答（业务请求已发往上游客栈）；</li>
     *   <li>MODIFY / 纯放行：{@code route.resume()} 成功（浏览器请求已转发真实网络）；</li>
     * </ul>
     * 一旦置位，任何 {@code fallback()}（= 再次放行到真实网络）都必须<b>抑制</b>——
     * 否则会与已发出的 fetch / 已转发的 resume 叠加成<b>重复发送</b>（重复下单 / 重复副作用）。
     * 用于保护异常路径（fulfill 失败、sweep 超时兜底、运行时关闭）不触发二次发送。</p>
     */
    private volatile boolean issued = false;

    /** 终结抢占结果：{@code granted}=是否抢占成功；{@code wasIoAwait}=迁移前是否占用挂起额度。 */
    public static final class TerminalResult {
        private final boolean granted;
        private final boolean wasIoAwait;

        TerminalResult(boolean granted, boolean wasIoAwait) {
            this.granted = granted;
            this.wasIoAwait = wasIoAwait;
        }

        public boolean granted() {
            return granted;
        }

        public boolean wasIoAwait() {
            return wasIoAwait;
        }
    }

    private final Route route;
    private final long createdAtNanos;
    private final AtomicReference<State> state = new AtomicReference<>(State.NEW);

    RouteClaim(Route route) {
        this.route = route;
        this.createdAtNanos = System.nanoTime();
    }

    public Route route() {
        return route;
    }

    long createdAtNanos() {
        return createdAtNanos;
    }

    State state() {
        return state.get();
    }

    public boolean isTerminal() {
        State s = state.get();
        return s == State.HANDLED || s == State.RELEASED;
    }

    public boolean isIoAwait() {
        return state.get() == State.IO_AWAIT;
    }

    /**
     * 标记业务请求已向上游 / 真实网络发出（转发状态保护）。
     *
     * <p>仅由唯一所有者线程调用（置位后不清除）；sweep / closeAll 线程只读。
     * volatile 保证跨线程可见，无需 CAS（无并发写）。</p>
     */
    public void markIssued() {
        this.issued = true;
    }

    /** 是否已发出业务请求（{@code true} 时 fallback 必须抑制，防重复发送）。 */
    public boolean isIssued() {
        return issued;
    }

    /** 占用挂起额度（NEW → IO_AWAIT）。失败表示已被终结或已被占用。 */
    public boolean toIoAwait() {
        return state.compareAndSet(State.NEW, State.IO_AWAIT);
    }

    /**
     * 抢占终结权（原子）。
     *
     * @param handled true=正常终结（HANDLED），false=放弃（RELEASED）
     * @return {@code granted=true} 表示本次调用抢占了终结权（调用方必须执行终结动作）；
     *         {@code wasIoAwait} 指示迁移前是否占用挂起额度（供调用方决定是否释放额度）
     */
    public TerminalResult tryTerminal(boolean handled) {
        while (true) {
            State s = state.get();
            if (s == State.HANDLED || s == State.RELEASED) {
                return new TerminalResult(false, false);
            }
            State target = handled ? State.HANDLED : State.RELEASED;
            if (state.compareAndSet(s, target)) {
                return new TerminalResult(true, s == State.IO_AWAIT);
            }
        }
    }

    @Override
    public String toString() {
        return "RouteClaim{url='" + route.request().url() + "', state=" + state.get() + '}';
    }
}
