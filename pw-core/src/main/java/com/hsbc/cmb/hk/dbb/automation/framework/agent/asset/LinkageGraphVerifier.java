package com.hsbc.cmb.hk.dbb.automation.framework.agent.asset;

import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.BrowserCommandChannel;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.Command;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.CommandResult;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.LocatorSpec;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.Snapshot;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.SnapshotExpectation;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.SnapshotVerificationResult;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.SnapshotVerifier;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.harness.IsolationHarness;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.asset.ExpectedChangeMapper;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.nls.NlsCatalog;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.nls.NlsText;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 联动图谱验证驱动的最小可用实现（设计 §3.1.2.1）。
 *
 * <p>编排：在隔离靶场 acquire 通道 → 对每条联动规则执行触发操作 → 采集快照 →
 * 断言目标状态变化 → 跨 repeat 次统计稳定性 → 产出 VerificationReport。
 *
 * <p>不产生 LLM 调用（R6）。真正驱动 Playwright 的 BrowserCommandChannel 与 IsolationHarness
 * 由 pw-web-ui 基于 RouteTestBed 提供；本类只依赖契约（pw-core 内）。</p>
 */
public final class LinkageGraphVerifier implements AssetVerifier {

    private final IsolationHarness harness;
    /** 多语言目录（可选）：用于把资产里的 NLS key 解析成当前语言文案（设计 §4.7）。 */
    private final NlsCatalog nls;

    public LinkageGraphVerifier(IsolationHarness harness) {
        this(harness, null);
    }

    /**
     * @param nls 多语言目录；{@code null} 表示单语言环境（资产里只有字面文案）
     */
    public LinkageGraphVerifier(IsolationHarness harness, NlsCatalog nls) {
        this.harness = harness;
        this.nls = nls;
    }

    @Override
    public VerificationReport verify(LinkageGraph graph, VerificationScope scope) {
        BrowserCommandChannel channel = harness.acquire(scope.targetUrl());
        try {
            int passRuns = 0;
            List<VerificationReport.VerificationItem> lastRunItems = new ArrayList<>();
            final int repeat = scope.repeat();
            for (int run = 0; run < repeat; run++) {
                lastRunItems = verifyOnce(channel, graph, scope.targetUrl());
                if (lastRunItems.stream().allMatch(VerificationReport.VerificationItem::passed)) {
                    passRuns++;
                }
            }
            double stability = (double) passRuns / repeat;
            VerificationReport.Status status = stability >= 1.0
                    ? VerificationReport.Status.PASS
                    : (stability >= 0.5 ? VerificationReport.Status.FLAKY : VerificationReport.Status.FAIL);
            String evidence = "trace://linkage/" + graph.requirementId() + "?runs=" + repeat;
            return new VerificationReport(status, stability, lastRunItems, evidence);
        } finally {
            harness.release(channel);
        }
    }

    private Map<String, String> triggerParams(LinkageGraph.LinkageRule rule) {
        Map<String, String> p = new java.util.HashMap<>();
        String v = rule.triggerValue();
        if (v != null && !v.isEmpty()) {
            switch (rule.triggerAction()) {
                case FILL:   p.put("value", v); break;
                case SELECT: p.put("option", v); break;
                default: break;
            }
        }
        return p;
    }

    private List<VerificationReport.VerificationItem> verifyOnce(BrowserCommandChannel channel, LinkageGraph graph, String targetUrl) {
        List<VerificationReport.VerificationItem> items = new ArrayList<>();
        // 每轮重复都从初始页面状态开始（重新导航），保证各轮独立可重复，
        // 避免上一轮触发造成的状态残留（如勾选/填写）影响本轮断言。
        channel.execute(Command.navigate(targetUrl));
        for (LinkageGraph.LinkageRule rule : graph.rules()) {
            // 角色优先：取触发器第一梯队定位符，并按当前语言解析 NLS key（多语言环境）
            LocatorSpec triggerLoc = localize(rule.trigger().primaryLocator());
            String ruleId = rule.trigger().refId() + ":" + rule.triggerAction();
            // 1) 执行触发操作（已在初始页面状态下）
            Command trigger = new Command(rule.triggerAction(), triggerLoc, triggerParams(rule));
            CommandResult result = channel.execute(trigger);
            if (!result.success()) {
                items.add(new VerificationReport.VerificationItem(ruleId, false,
                        "trigger failed: " + result.message()));
                continue;
            }
            // 2) 对每条期望状态变化做断言（目标同样取角色优先定位符并解析语言）
            Snapshot after = result.snapshot();
            boolean allOk = true;
            String detail = "ok";
            for (LinkageGraph.ExpectedChange exp : rule.expectations()) {
                LocatorSpec targetLoc = localize(exp.target().primaryLocator());
                if (!checkChange(after, exp, targetLoc, exp.target().primaryLocator())) {
                    allOk = false;
                    detail = "change mismatch at " + exp.target().refId() + " expected " + exp.kind();
                    break;
                }
            }
            items.add(new VerificationReport.VerificationItem(ruleId, allOk, detail));
        }
        return items;
    }

    /**
     * 把定位符解析为当前语言可执行的形态。
     *
     * <p>无 NLS 目录 / 无 key 时原样返回；模板值（{@code {{var}}}）保留 key，由执行层按正则处理。</p>
     */
    private LocatorSpec localize(LocatorSpec spec) {
        return spec == null ? null : spec.localize(nls);
    }

    /**
     * 在快照中定位断言目标，三级回退：
     * <ol>
     *   <li>按解析后的 canonical（当前语言文案）精确命中——最常见；</li>
     *   <li>按原始 canonical 命中——快照本身即 key 形态时（未来通道实现）仍可用；</li>
     *   <li>NLS 值为模板时，按 {@code role + 正则} 在快照里扫——静态无法求值，只能匹配实际渲染文本。</li>
     * </ol>
     */
    private Snapshot.ElementState stateOf(Snapshot snapshot, LocatorSpec localized, LocatorSpec original) {
        if (localized == null) {
            return null;
        }
        Snapshot.ElementState state = snapshot.byRef(localized);
        if (state != null) {
            return state;
        }
        if (original != null && !original.canonical().equals(localized.canonical())) {
            state = snapshot.byRef(original);
            if (state != null) {
                return state;
            }
        }
        if (nls != null && original != null && original.nameKey() != null) {
            String raw = nls.value(original.nameKey());
            if (NlsText.isTemplate(raw)) {
                java.util.regex.Pattern p = NlsText.pattern(raw);
                for (Snapshot.ElementState e : snapshot.elements()) {
                    if (e.role() != null && e.role().equalsIgnoreCase(original.role())
                            && e.name() != null && p.matcher(e.name()).find()) {
                        return e;
                    }
                }
            }
        }
        return null;
    }

    /**
     * 把「状态变化」期望转换为绝对态字段断言，复用 {@link SnapshotVerifier} 做字段级比较。
     *
     * <p>元素定位仍走原有三级回退（{@link #stateOf}），因 {@code SnapshotVerifier} 只做按 ref 的
     * 精确字段断言、不感知 NLS 模板；定位到元素后，用其实际 ref 构建单元素快照交给
     * {@code SnapshotVerifier} 断言。语义与重构前一致：APPEAR=可见、DISAPPEAR=不可见
     * （元素已定位存在）、ENABLE/DISABLE/REQUIRED=启用态、TEXT_CHANGED=文本相等/正则；
     * 元素未找到时仅 DISAPPEAR 视为通过。</p>
     */
    private boolean checkChange(Snapshot after, LinkageGraph.ExpectedChange exp,
                               LocatorSpec localized, LocatorSpec original) {
        Snapshot.ElementState state = stateOf(after, localized, original);
        if (state == null) {
            return exp.kind() == LinkageGraph.ExpectedChange.ChangeKind.DISAPPEAR;
        }
        SnapshotExpectation sexp = toExpectation(exp, state.ref());
        if (sexp == null) {
            return false; // TEXT_CHANGED 无可用期望文本等无法判定的情况，保持原 textMatches 语义
        }
        Snapshot single = new Snapshot(after.pageRef(), List.of(state));
        return SnapshotVerifier.verify(single, List.of(sexp)).allPassed();
    }

    /** ExpectedChange（变化语义）→ SnapshotExpectation（绝对态语义）；委托共享映射（单一事实来源）。 */
    private SnapshotExpectation toExpectation(LinkageGraph.ExpectedChange exp, String ref) {
        return ExpectedChangeMapper.toExpectation(exp, ref, nls);
    }
}
