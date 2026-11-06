package com.hsbc.cmb.hk.dbb.automation.framework.web.quality;

import com.hsbc.cmb.hk.dbb.automation.framework.web.exceptions.FrameworkException;

import java.util.List;

/**
 * 定位健康度回归异常：由 Cucumber 场景边界门禁（{@code LocatorHealthGateBackend}）
 * 在检测到<b>新增或恶化</b>的选择器时抛出，使<b>对应的场景失败</b>，从而让 Maven 构建红。
 *
 * <p><b>为什么用异常而不是"打日志"</b>：这是质量门禁，必须走框架既有的失败通道
 * （场景失败 → surefire/failsafe 计数 → 非零退出码）。只打日志等于没人拦门。
 *
 * <p><b>为什么挂在场景边界而不是运行收尾</b>：Cucumber 的 {@code TestRunFinished} 处理器一旦抛错会打断
 * <b>同事件的其它处理器</b>（报告插件正是挂在那里），代价是产物丢失；场景边界抛错则由 Cucumber
 * 原生语义计为该场景失败，报告插件照常收尾。
 *
 * <p><b>信息面</b>：{@link #getMessage()} 是给 surefire 报告看的一行摘要（含回归选择器），
 * {@link #regressions()} 供程序化消费（例如后续接入告警/看板）。
 */
public final class LocatorHealthRegressionException extends FrameworkException {

    private static final long serialVersionUID = 1L;

    /** 结构化回归明细（{@link LocatorHealthGate.Regression} 不可序列化，故标记 transient）。 */
    private final transient List<LocatorHealthGate.Regression> regressions;

    /**
     * @param message     可读摘要（进测试报告）
     * @param regressions 回归明细（可为 {@code null}，归一为空列表）
     */
    public LocatorHealthRegressionException(String message, List<LocatorHealthGate.Regression> regressions) {
        super(message);
        this.regressions = regressions == null ? List.of() : List.copyOf(regressions);
    }

    /** 结构化回归明细（不可变，永不为 {@code null}）。 */
    public List<LocatorHealthGate.Regression> regressions() {
        return regressions;
    }
}
