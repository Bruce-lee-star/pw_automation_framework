package com.hsbc.cmb.hk.dbb.automation.framework.common.logging;

import ch.qos.logback.classic.pattern.MessageConverter;
import ch.qos.logback.classic.spi.ILoggingEvent;

import com.hsbc.cmb.hk.dbb.automation.framework.common.config.FrameworkFlags;
import com.hsbc.cmb.hk.dbb.automation.framework.common.security.SensitiveDataSanitizer;

/**
 * 日志出口强制脱敏 Converter —— D2-4。
 *
 * <p><b>要解决的问题</b>：此前脱敏"靠调用方自觉" —— 谁打印日志谁负责先 {@code sanitize}，
 * 只要有一处忘记（新增日志、第三方库、异常信息拼接），敏感值就直接落到控制台与文件里。
 * 在金融级场景，这种"靠人不出错"的防线不成立。
 *
 * <p><b>本类的做法</b>：接管 logback 的 {@code msg} 转换词（在 {@code logback.xml} 中
 * {@code <conversionRule conversionWord="msg" converterClass="..."/>}），
 * 使<b>所有</b>经 {@code %msg} / {@code %m} 输出的日志消息在写出前统一经
 * {@link SensitiveDataSanitizer#sanitizeLogMessage(String)}（行内 {@code key[:=]value} + Bearer/JWT 自由文本两级）—— 调用方<b>无法绕过</b>。
 *
 * <p><b>与"调用方自觉"的区别 vs 运维总开关</b>：
 * <ul>
 *   <li>本 Converter <b>默认开启</b>，业务代码无从选择，这就是"出口强制"；</li>
 *   <li>{@link #ENABLED_KEY} 是<b>运维级</b>总开关（默认 true），仅在极端排障 /
 *       性能应急时由运维统一关闭 —— 它不属于"调用方自觉"，关闭需显式动作且影响全进程。</li>
 * </ul>
 *
 * <p><b>健壮性（CORE-C2，与异常栈出口对齐）</b>：脱敏本身是正则处理，若其抛异常，本类<b>不回退原文</b>，
 * 而输出 {@code [LOG SUPPRESSED: sanitizer failure]} —— 与 {@link SanitizingThrowableConverter} 方向一致：
 * 日志出口与异常栈出口同为"失败即抑制"，宁可丢失该条消息细节，也不让明文凭据落盘。
 *
 * <p><b>防递归（2026-11-12 修复，勿删）</b>：日志格式化期间调用方可能再次打日志
 * （典型：读取配置开关 → {@code FileConfigResolver} 加载时打 INFO → 又回到 {@code %msg}），
 * 形成无限递归直到 {@code StackOverflowError}。本类经 {@link SanitizerSwitch} 共享守卫：
 * 重入时<b>不再读任何配置</b>，直接按"开启脱敏"的安全路径处理。
 * 此外 {@code FrameworkFlags} 已改为直读系统属性/环境变量，不再经过会打日志的文件配置层。
 * 两道防线缺一不可：前者断边，后者兜住将来任何把副作用接回来的改动。
 *
 * @apiNote 需在 logback 配置中注册；仅作用于 {@code msg}（消息体），不改写异常栈 {@code %ex}。
 */
public class SanitizingMessageConverter extends MessageConverter {

    /**
     * 运维级总开关（默认 <b>true</b> = 强制脱敏）。
     * <p>仅在性能应急等特殊场景由运维统一关闭；业务代码不应依赖它。
     */
    public static final String ENABLED_KEY = "framework.log.sanitize.enabled";

    @Override
    public String convert(ILoggingEvent event) {
        String message = super.convert(event);
        if (message == null || message.isEmpty()) {
            return message;
        }
        boolean outermost = SanitizerSwitch.enter();
        try {
            if (!sanitizationEnabled(outermost)) {
                return message;
            }
            try {
                return sanitize(message);
            } catch (Exception e) {
                //  CORE-C2：与异常栈出口一致——失败即抑制，绝不回退明文（回退原文 = 明文凭据落盘）
                return SanitizingThrowableConverter.suppressedMarker();
            }
        } finally {
            // 只有最外层设置过标记，才由它清除；重入层提前清除会重新打开递归环。
            if (outermost) {
                SanitizerSwitch.exit();
            }
        }
    }

    /**
     * 开关判定：重入 / 不可判定时一律走安全侧（继续脱敏）。
     *
     * <p>{@code LinkageError} 也要接住：裁剪 classpath 下 Logback 或配置类缺席是 {@code Error}
     * 而非 {@code Exception}，让它逃出 {@code convert()} 会中断整条日志链路。
     */
    private boolean sanitizationEnabled(boolean mayReadConfig) {
        if (!mayReadConfig) {
            return SanitizerSwitch.SAFE_DEFAULT;
        }
        try {
            return FrameworkFlags.isEnabled(ENABLED_KEY, true);
        } catch (Exception | LinkageError e) {
            return SanitizerSwitch.SAFE_DEFAULT;
        }
    }

    /**
     * 实际脱敏入口（protected，可被测试子类覆写以模拟脱敏失败，验证 CORE-C2 抑制路径）。
     */
    protected String sanitize(String message) {
        return SensitiveDataSanitizer.sanitizeLogMessage(message);
    }
}
