package com.hsbc.cmb.hk.dbb.automation.framework.agent.impact;

import java.util.List;
import java.util.Objects;

/**
 * 一条存量用例（Gherkin scenario 的结构化视图），供变更影响筛选使用。
 *
 * <p>只保留筛选所需的最小信息：定位（feature 文件 / 行号）、标签、步骤文本。
 * <b>不解析 step definition</b>——那需要 Java 源码静态分析，代价与脆弱性都高；
 * 元素级归属由变更信号（元素名/tag）与步骤文本的确定性匹配承担，
 * 见 {@link CaseImpactScreener}。</p>
 *
 * @param featureFile feature 文件相对路径（如 {@code web/login_dbb.feature}）
 * @param featureName {@code Feature:} 后的名称
 * @param scenario    场景名（{@code Scenario:}/{@code Scenario Outline:} 之后）
 * @param tags        该场景生效的标签（含 feature 级标签），均为 {@code @xxx} 原样
 * @param steps       步骤文本（不含 {@code Given/When/Then} 关键字），保持文件顺序
 * @param line        场景起始行号（1 起，便于直接跳转）
 */
public record FeatureCase(String featureFile, String featureName, String scenario,
                          List<String> tags, List<String> steps, int line) {

    public FeatureCase {
        Objects.requireNonNull(featureFile, "featureFile");
        featureName = featureName == null ? "" : featureName;
        scenario = Objects.requireNonNull(scenario, "scenario");
        tags = List.copyOf(tags);
        steps = List.copyOf(steps);
    }

    /** 用于报告的唯一标识（{@code featureFile:line scenario}）。 */
    public String id() {
        return featureFile + ":" + line + " " + scenario;
    }

    /** 步骤全文（小写，供匹配；避免每处重复拼接）。 */
    public String searchableText() {
        return (featureName + " " + scenario + " " + String.join(" ", steps)).toLowerCase();
    }
}
