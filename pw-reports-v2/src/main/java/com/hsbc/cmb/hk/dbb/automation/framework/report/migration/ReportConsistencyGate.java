package com.hsbc.cmb.hk.dbb.automation.framework.report.migration;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 双跑四一致门禁（Tier2 #7 / 迁移 Phase 0）：给定 pw-reports 报告目录与
 * Serenity 基线目录，比对四个维度并产出 {@link GateReport}。
 *
 * <p>四维度：
 * <ol>
 *   <li>{@link GateViolation.Dimension#COVERAGE}——两目录用例集合对称差（按
 *       {@code feature::name} 联结）；任意一侧多出/少了一例 → WARN；</li>
 *   <li>{@link GateViolation.Dimension#RESULT}——同例 result 不一致 → ERROR（阻断）；</li>
 *   <li>{@link GateViolation.Dimension#STEP_TREE}——步骤树（扁平描述序列）不一致
 *       → WARN（仅两侧均有步骤明细时比，否则跳过）；</li>
 *   <li>{@link GateViolation.Dimension#SUMMARY}——count / failures 计数不一致 → ERROR。</li>
 * </ol>
 *
 * <p>联结键用 {@code feature::name}：pw-reports 的 feature 取 userStory.storyName，
 * Serenity 取 feature 名；若两侧命名不一致需在消费方做映射（本门禁只做格式归一）。
 *
 * <p>用法：CI 中先以 serenity-cucumber 跑一遍得基线目录，再以 pw-reports 跑同套
 * 得对比目录，调用 {@link #compare(Path, Path)}，{@link GateReport#failed()} 为真则
 * 阻断切换。
 */
public final class ReportConsistencyGate {

    private ReportConsistencyGate() {
    }

    public static GateReport compare(Path pwDir, Path serenityDir) {
        return compare(new PwReportSource().load(pwDir), new ManagedReport().load(serenityDir));
    }

    public static GateReport compare(List<CanonicalOutcome> pw, List<CanonicalOutcome> serenity) {
        List<GateViolation> violations = new ArrayList<>();
        Map<String, CanonicalOutcome> pwByKey = keyed(pw);
        Map<String, CanonicalOutcome> seByKey = keyed(serenity);

        for (String k : pwByKey.keySet()) {
            if (!seByKey.containsKey(k)) {
                violations.add(GateViolation.warn(GateViolation.Dimension.COVERAGE,
                        "present in pw-reports but missing in serenity: " + k, k));
            }
        }
        for (String k : seByKey.keySet()) {
            if (!pwByKey.containsKey(k)) {
                violations.add(GateViolation.warn(GateViolation.Dimension.COVERAGE,
                        "present in serenity but missing in pw-reports: " + k, k));
            }
        }

        for (Map.Entry<String, CanonicalOutcome> e : pwByKey.entrySet()) {
            CanonicalOutcome s = seByKey.get(e.getKey());
            if (s == null) {
                continue;
            }
            CanonicalOutcome p = e.getValue();
            if (!norm(p.result()).equals(norm(s.result()))) {
                violations.add(GateViolation.error(GateViolation.Dimension.RESULT,
                        "result mismatch pw=" + p.result() + " serenity=" + s.result(), p.outcomeId()));
            }
            if (!p.stepTexts().isEmpty() && !s.stepTexts().isEmpty()
                    && !p.stepTexts().equals(s.stepTexts())) {
                violations.add(GateViolation.warn(GateViolation.Dimension.STEP_TREE,
                        "step tree differs", p.outcomeId()));
            }
        }

        long pwCount = pw.size();
        long seCount = serenity.size();
        if (pwCount != seCount) {
            violations.add(GateViolation.error(GateViolation.Dimension.SUMMARY,
                    "outcome count mismatch pw=" + pwCount + " serenity=" + seCount, null));
        }
        long pwFail = pw.stream().filter(o -> isFailure(o.result())).count();
        long seFail = serenity.stream().filter(o -> isFailure(o.result())).count();
        if (pwFail != seFail) {
            violations.add(GateViolation.error(GateViolation.Dimension.SUMMARY,
                    "failure count mismatch pw=" + pwFail + " serenity=" + seFail, null));
        }
        return new GateReport(violations);
    }

    private static Map<String, CanonicalOutcome> keyed(List<CanonicalOutcome> list) {
        Map<String, CanonicalOutcome> m = new LinkedHashMap<>();
        for (CanonicalOutcome o : list) {
            m.put(joinKey(o.feature(), o.name()), o);
        }
        return m;
    }

    private static String joinKey(String feature, String name) {
        return (feature == null ? "" : feature) + "::" + (name == null ? "" : name);
    }

    private static String norm(String result) {
        return result == null ? "" : result.toUpperCase(java.util.Locale.ROOT);
    }

    private static boolean isFailure(String result) {
        String r = norm(result);
        return r.equals("FAILURE") || r.equals("ERROR") || r.equals("COMPROMISED");
    }
}
