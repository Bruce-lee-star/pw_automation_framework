package com.hsbc.cmb.hk.dbb.automation.framework.common.logging;

/**
 * 日志脱敏 Converter 的<b>共享重入守卫</b>（框架内部，包级私有）。
 *
 * <p><b>要防的环（2026-11-12 线上 {@code StackOverflowError}）</b>：
 * {@code logback 格式化 → SanitizingMessageConverter → 读取配置开关 → 配置加载器打日志
 * → 又回到 logback 格式化}。日志格式化路径必须是无副作用的纯函数，但"读配置开关"本身
 * 带 IO 和日志副作用，因此即便 {@code FrameworkFlags} 已改为直读系统属性（断掉主环），
 * 仍需一道纵深防御：将来任何人把配置源接回来，或脱敏逻辑内部打日志，都不会再无限递归。
 *
 * <p><b>为什么必须是静态共享</b>：一条日志会依次经过 {@code %msg} 与 {@code %ex} 两个
 * Converter 实例；若各自持有 {@code ThreadLocal}，重入到另一个 Converter 时标记是空的，
 * 守卫形同虚设。共享同一个线程标记才能覆盖整条日志的格式化过程。
 *
 * <p><b>重入时走"安全侧"</b>：{@link #SAFE_DEFAULT} 是 {@code true}（继续脱敏），
 * 绝不是 {@code false}（跳过脱敏）——后者会把明文凭据直接写到控制台与文件，
 * 比多跑一次正则危险得多。
 *
 * @apiNote 仅供 {@code common.logging} 内的 Converter 使用；业务代码不得依赖。
 */
final class SanitizerSwitch {

    /** 重入/开关不可判定时一律按"开启脱敏"处理（安全侧）。 */
    static final boolean SAFE_DEFAULT = true;

    /** 当前线程是否正处于日志格式化中；静态共享，覆盖 %msg + %ex 全链路。 */
    private static final ThreadLocal<Boolean> FORMATTING = new ThreadLocal<>();

    private SanitizerSwitch() {
    }

    /**
     * 进入一次日志格式化。
     *
     * @return {@code true} = 本层是最外层，可以读配置开关，<b>并负责</b>调用 {@link #exit()}；
     *         {@code false} = 已处于格式化中（重入），必须走 {@link #SAFE_DEFAULT}，
     *         <b>且不得</b>调用 {@link #exit()}（标记属于外层，提前清除会重新打开环）
     */
    static boolean enter() {
        if (Boolean.TRUE.equals(FORMATTING.get())) {
            return false;
        }
        FORMATTING.set(Boolean.TRUE);
        return true;
    }

    /** 仅由 {@link #enter()} 返回 {@code true} 的那一层调用。 */
    static void exit() {
        FORMATTING.remove();
    }
}
