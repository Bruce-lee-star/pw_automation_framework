package com.hsbc.cmb.hk.dbb.automation.framework.agent.impact;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 变更影响筛选器：给定「这次变更动了什么」（{@link ChangeSignal}）与存量用例（{@link FeatureCase}），
 * 输出每条用例的处置结论与命中证据。
 *
 * <h3>判定口径（确定性、可解释）</h3>
 * <ul>
 *   <li>{@link Verdict#MUST_MAINTAIN}（必须维护）：命中<b>元素名</b>（变更元素出现在用例步骤文本）或
 *       命中<b>标签</b>（如 {@code @login}）；</li>
 *   <li>{@link Verdict#REVIEW}（需复核）：仅命中<b>页面</b>（如 {@code /logon}）或<b>关键词</b>
 *       （如 {@code logon}）——范围更宽，可能是同页但不同元素；</li>
 *   <li>{@link Verdict#NO_CHANGE}（可不动）：未命中任何信号。</li>
 * </ul>
 *
 * <p><b>诚实边界</b>：用例与 UI 元素之间没有机器可读映射（映射在 step definition → page object →
 * {@code @RoleElement} 里），本筛选基于<b>文本匹配</b>。因此 {@code NO_CHANGE} 只表示「没命中」，
 * 不等于「人工确认安全」；报告首页固定带该声明。若要元素级精确归属，需先积累「用例 → 元素」用量索引
 * （运行期记录，属后续增强）。</p>
 *
 * <p>无状态、线程安全。</p>
 */
public final class CaseImpactScreener {

    /** 处置结论。 */
    public enum Verdict {
        MUST_MAINTAIN("必须维护"),
        REVIEW("需复核"),
        NO_CHANGE("可不动");

        private final String label;

        Verdict(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /** 命中证据（信号类型 + 命中的词）。 */
    public record Evidence(String kind, String term) {
        @Override
        public String toString() {
            return kind + ":" + term;
        }
    }

    /** 单条用例的筛选结论。 */
    public record CaseVerdict(FeatureCase caseInfo, Verdict verdict, List<Evidence> evidence) {
        public CaseVerdict {
            Objects.requireNonNull(caseInfo, "caseInfo");
            Objects.requireNonNull(verdict, "verdict");
            evidence = List.copyOf(evidence);
        }

        public String evidenceText() {
            List<String> parts = new ArrayList<>(evidence.size());
            for (Evidence e : evidence) {
                parts.add(e.toString());
            }
            return String.join("; ", parts);
        }
    }

    /** 筛选报告。 */
    public record Report(String changeSummary, List<CaseVerdict> verdicts) {

        public Report {
            changeSummary = changeSummary == null ? "" : changeSummary;
            verdicts = List.copyOf(verdicts);
        }

        public long count(Verdict verdict) {
            return verdicts.stream().filter(v -> v.verdict() == verdict).count();
        }

        /** 按结论排序（必须维护 → 需复核 → 可不动），组内保持文件顺序。 */
        public List<CaseVerdict> sorted() {
            List<CaseVerdict> out = new ArrayList<>(verdicts);
            out.sort((a, b) -> Integer.compare(a.verdict().ordinal(), b.verdict().ordinal()));
            return out;
        }

        /** Markdown 报告（可直接粘进评审单/邮件）。 */
        public String toMarkdown() {
            return toMarkdown(false);
        }

        /**
         * Markdown 报告，可选择只输出需要动作的结论。
         *
         * @param onlyActionable {@code true} 时表格只列「必须维护」与「需复核」；
         *                       统计行仍给全量计数，避免过滤后丢失全景
         */
        public String toMarkdown(boolean onlyActionable) {
            StringBuilder sb = new StringBuilder(1024);
            sb.append("# 用例影响筛选报告\n\n")
                    .append("- 变更信号: ").append(changeSummary).append('\n')
                    .append("- 用例总数: ").append(verdicts.size())
                    .append("（必须维护 ").append(count(Verdict.MUST_MAINTAIN))
                    .append(" / 需复核 ").append(count(Verdict.REVIEW))
                    .append(" / 可不动 ").append(count(Verdict.NO_CHANGE)).append("）\n\n")
                    .append("| 结论 | Feature | Scenario | 位置 | 命中证据 |\n")
                    .append("| --- | --- | --- | --- | --- |\n");
            for (CaseVerdict v : sorted()) {
                if (onlyActionable && v.verdict() == Verdict.NO_CHANGE) {
                    continue;
                }
                sb.append("| ").append(v.verdict().label())
                        .append(" | ").append(cell(v.caseInfo().featureName()))
                        .append(" | ").append(cell(v.caseInfo().scenario()))
                        .append(" | ").append(cell(v.caseInfo().id()))
                        .append(" | ").append(cell(v.evidenceText()))
                        .append(" |\n");
            }
            if (onlyActionable) {
                sb.append("\n> 已过滤 ").append(count(Verdict.NO_CHANGE))
                        .append(" 条「可不动」用例（统计行仍为全量）。\n");
            }
            sb.append("\n> 说明：**可不动**仅表示「未命中任何变更信号」，不等同于人工确认安全；")
                    .append("命中基于步骤文本/tag 的确定性匹配，元素级精确归属需「用例 → 元素」用量索引（后续增强）。\n");
            return sb.toString();
        }

        private static String cell(String value) {
            return value == null ? "" : value.replace("|", "\\|").replace("\n", " ");
        }
    }

    private CaseImpactScreener() {
    }

    /**
     * 执行筛选。
     *
     * @param cases  存量用例（{@link CaseCatalog#scan} 的结果）
     * @param signal 本次变更的信号
     * @throws IllegalArgumentException 信号为空（全空时若继续，会把全部用例判「可不动」，属危险结论）
     */
    public static Report screen(List<FeatureCase> cases, ChangeSignal signal) {
        Objects.requireNonNull(cases, "cases");
        Objects.requireNonNull(signal, "signal");
        if (signal.isEmpty()) {
            throw new IllegalArgumentException("变更信号为空：无法筛选（否则会把全部用例误判为可不动）");
        }
        Map<String, Pattern> patterns = new HashMap<>();
        List<CaseVerdict> verdicts = new ArrayList<>(cases.size());
        for (FeatureCase c : cases) {
            verdicts.add(screenOne(c, signal, patterns));
        }
        return new Report(signal.describe(), verdicts);
    }

    private static CaseVerdict screenOne(FeatureCase c, ChangeSignal signal, Map<String, Pattern> patterns) {
        String text = c.searchableText();
        List<Evidence> evidence = new ArrayList<>();
        boolean strong = false;

        for (String element : signal.elements()) {
            if (containsTerm(text, element, patterns)) {
                evidence.add(new Evidence("element", element));
                strong = true;
            }
        }
        Set<String> caseTags = new java.util.TreeSet<>();
        for (String tag : c.tags()) {
            caseTags.add(tag.toLowerCase(Locale.ROOT));
        }
        for (String tag : signal.tags()) {
            if (caseTags.contains(tag)) {
                evidence.add(new Evidence("tag", tag));
                strong = true;
            }
        }
        for (String page : signal.pages()) {
            if (text.contains(page)) {
                evidence.add(new Evidence("page", page));
            }
        }
        for (String keyword : signal.keywords()) {
            if (containsTerm(text, keyword, patterns)) {
                evidence.add(new Evidence("keyword", keyword));
            }
        }
        Verdict verdict = strong ? Verdict.MUST_MAINTAIN
                : evidence.isEmpty() ? Verdict.NO_CHANGE : Verdict.REVIEW;
        return new CaseVerdict(c, verdict, evidence);
    }

    /**
     * 词边界匹配：ASCII 词要求前后不是字母/数字（避免 {@code go} 命中 {@code google}），
     * CJK 词天然由非 {@code [a-z0-9]} 分隔，等价于子串匹配。
     */
    private static boolean containsTerm(String text, String term, Map<String, Pattern> cache) {
        Pattern p = cache.computeIfAbsent(term,
                t -> Pattern.compile("(?<![a-z0-9])" + Pattern.quote(t) + "(?![a-z0-9])"));
        return p.matcher(text).find();
    }
}
