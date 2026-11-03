package com.hsbc.cmb.hk.dbb.automation.framework.agent.channel;

import com.hsbc.cmb.hk.dbb.automation.framework.common.assertion.AssertionFailure;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 快照字段级断言器（验证驱动最小构成块，设计 §4.2 联动断言首步）。
 *
 * <p>纯确定性、浏览器无关：给定执行后 {@link Snapshot} 与一组 {@link SnapshotExpectation}，
 * 按 {@code ref} 定位元素并对各可选字段做<b>绝对态</b>断言，产出 {@link SnapshotVerificationResult}。
 *
 * <p>本类与 {@link com.hsbc.cmb.hk.dbb.automation.framework.agent.asset.LinkageGraphVerifier}
 * 互补：后者校验「触发后是否发生指定变化（APPEAR/DISAPPEAR/ENABLE/…）」，本类校验
 * 「某元素此刻是否等于期望的绝对态（role/name/visible/enabled/text/regex）」。
 * 本类是构成块——不驱动浏览器、不编排 StepPlan、不耦合多语言目录；完整验证驱动
 * （隔离靶场 / 重复稳定性统计）由 {@code LinkageGraphVerifier} 承担，未来可复用本块做字段级断言。</p>
 */
public final class SnapshotVerifier {

    private SnapshotVerifier() {
    }

    public static SnapshotVerificationResult verify(Snapshot snapshot, List<SnapshotExpectation> expectations) {
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(expectations, "expectations");
        List<AssertionFailure> failures = new ArrayList<>();
        int passed = 0;
        for (SnapshotExpectation exp : expectations) {
            Snapshot.ElementState state = snapshot.byRef(exp.ref());
            if (exp.exists()) {
                if (state == null) {
                    failures.add(new AssertionFailure("element missing: " + exp.ref(), "present", "absent"));
                    continue;
                }
            } else {
                if (state != null) {
                    failures.add(new AssertionFailure("element present but expected absent: " + exp.ref(), "absent", "present"));
                } else {
                    passed++; // 期望不存在且确实不存在 → 通过
                }
                continue;
            }
            boolean ok = true;
            if (exp.role() != null && !Objects.equals(exp.role(), state.role())) {
                failures.add(new AssertionFailure("role mismatch @" + exp.ref(), exp.role(), state.role()));
                ok = false;
            }
            if (exp.name() != null && !Objects.equals(exp.name(), state.name())) {
                failures.add(new AssertionFailure("name mismatch @" + exp.ref(), exp.name(), state.name()));
                ok = false;
            }
            if (exp.visible() != null && exp.visible() != state.visible()) {
                failures.add(new AssertionFailure("visible mismatch @" + exp.ref(), exp.visible(), state.visible()));
                ok = false;
            }
            if (exp.enabled() != null && exp.enabled() != state.enabled()) {
                failures.add(new AssertionFailure("enabled mismatch @" + exp.ref(), exp.enabled(), state.enabled()));
                ok = false;
            }
            if (exp.text() != null && !Objects.equals(exp.text(), state.text())) {
                failures.add(new AssertionFailure("text mismatch @" + exp.ref(), exp.text(), state.text()));
                ok = false;
            }
            if (exp.hasTextRegex()) {
                if (state.text() == null || !Pattern.compile(exp.textMatches()).matcher(state.text()).find()) {
                    failures.add(new AssertionFailure("text regex mismatch @" + exp.ref(), exp.textMatches(), state.text()));
                    ok = false;
                }
            }
            if (ok) {
                passed++;
            }
        }
        return new SnapshotVerificationResult(failures.isEmpty(), failures, expectations.size(), passed);
    }

    /** 便捷入口：直接从命令执行结果取快照断言。 */
    public static SnapshotVerificationResult verify(CommandResult result, List<SnapshotExpectation> expectations) {
        Objects.requireNonNull(result, "result");
        return verify(result.snapshot(), expectations);
    }
}
