package com.hsbc.cmb.hk.dbb.automation.framework.report.migration;

import java.util.List;

/**
 * 跨报告格式归一化的用例视图——pw-reports 与 Serenity 两侧都映射成它再比对，
 * 使四一致门禁（Phase 0）与具体 JSON schema 解耦。
 */
public record CanonicalOutcome(
        String outcomeId,
        String feature,
        String name,
        String result,
        long duration,
        List<String> stepTexts
) {
}
