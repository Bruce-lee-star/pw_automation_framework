package com.hsbc.cmb.hk.dbb.automation.framework.web.agent;

import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.BrowserCommandChannel;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.CommandResult;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.SnapshotExpectation;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.StepDriver;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.StepVerifier;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.StepPlan;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.StepVerificationResult;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.harness.IsolationHarness;

import java.util.List;

/**
 * 把 pw-core 的「授权执行 + 执行后联动断言」闭环接到真实浏览器执行底座（完整驱动引擎专项）。
 *
 * <p>组合三块已落地的最小构成：
 * <ol>
 *   <li>{@link IsolationHarness}（此处为 {@link RouteTestBedHarness} 的 Playwright 真实通道）—— 隔离靶场；</li>
 *   <li>{@link StepDriver} —— 参数范围门禁闭环（治理预检 + 逐步过门禁边界）；</li>
 *   <li>{@link StepVerifier} —— 执行后绝对态字段断言。</li>
 * </ol>
 * 本类不重新实现执行或断言，只做<b>编排</b>：经 harness 取真实通道 → 跑 {@code StepDriver}/{@code StepVerifier} → 释放通道。
 * 门禁 fail-closed 在通道边界默认方法内自动生效（{@code BrowserCommandChannel.execute(Command, ParamScope)}）。</p>
 *
 * <p>通道边界接线点：{@code PlaywrightChannel} 实现 {@code BrowserCommandChannel}，其 2-arg {@code execute(Command, ParamScope)}
 * 默认方法内置 {@code ParamScopeGate} 核对——故本闭环对接真通道时，参数范围门禁<b>零改动</b>即生效。</p>
 */
public final class StepPlanRunner {

    private final IsolationHarness harness;

    public StepPlanRunner(IsolationHarness harness) {
        this.harness = harness;
    }

    /** 授权执行 + 执行后联动断言（单次执行、自带断言；推荐入口）。 */
    public StepVerificationResult run(StepPlan plan, List<List<SnapshotExpectation>> expectations, String targetUrl) {
        BrowserCommandChannel channel = null;
        try {
            channel = harness.acquire(targetUrl);
            return StepVerifier.verify(plan, channel, expectations);
        } finally {
            if (channel != null) {
                harness.release(channel);
            }
        }
    }

    /** 仅授权执行（不带断言），返回每步结果。 */
    public List<CommandResult> execute(StepPlan plan, String targetUrl) {
        BrowserCommandChannel channel = null;
        try {
            channel = harness.acquire(targetUrl);
            return StepDriver.execute(plan, channel);
        } finally {
            if (channel != null) {
                harness.release(channel);
            }
        }
    }
}
