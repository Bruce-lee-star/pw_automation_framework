package com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen.locator.candidate;

import com.hsbc.cmb.hk.dbb.automation.framework.agent.nls.NlsText;

/**
 * 把「观测事实」+「业务断言结果」组装成 {@link CandidateEvidence}（设计 §4.4 / P3）。
 *
 * <h2>为什么要单独一层</h2>
 * <p>观测是浏览器侧的事（{@link EvidenceProbe}），断言是业务侧的事（测试/录制会话），
 * 两者来源不同、失败原因不同。这里只做<b>合并与判定</b>，因此可以完全脱离浏览器做单元测试——
 * "文案比较规则"这种最容易出错的逻辑，不该需要启动一个 Chromium 才能验证。</p>
 *
 * <h2>文案匹配为什么不允许"命中即一致"</h2>
 * <p>最容易犯的错是把 {@code matches == 1} 当成"文案也对"。对 ROLE 定位来说，名本来就参与过滤，
 * 这个近似侥幸成立；但换成 {@code testid} / {@code css} 就完全错了——同一个 testid 被复用到别的
 * 业务元素上时，命中数仍是 1。所以这里坚持<b>真的把可访问名读出来比一遍</b>；
 * 定位符没声明期望名（无从比较）时判 {@code false}，而不是默认通过。</p>
 */
public final class CandidateEvidenceCollector {

    private CandidateEvidenceCollector() {
    }

    /**
     * 组装证据。
     *
     * @param candidate          候选（决定期望名）
     * @param observation        观测事实；{@code null} 视为未观测
     * @param businessAssertion  业务断言描述（必填，空则视为未提供）
     * @param assertionPassed    业务断言结果；{@code null} 表示未执行
     * @param traceRef           证据出处（trace / 会话 id / 截图为证的文件名）
     */
    public static CandidateEvidence collect(LocatorCandidate candidate,
                                            StructuralObservation observation,
                                            String businessAssertion,
                                            Boolean assertionPassed,
                                            String traceRef) {
        if (candidate == null) {
            throw new IllegalArgumentException("candidate 不可为空");
        }
        StructuralObservation obs = observation == null
                ? StructuralObservation.unobserved("探针未返回观测结果")
                : observation;

        boolean textMatched = obs.observed() && namesMatch(obs.expectedName(), obs.actualName());

        CandidateEvidence.Builder builder = CandidateEvidence.builder()
                .matches(obs.matches())
                .visible(obs.visible())
                .enabled(obs.enabled())
                .textMatched(textMatched)
                .businessAssertion(businessAssertion)
                .detail(composeDetail(obs, textMatched, traceRef));
        // 未执行（null）必须保持 null，不能因为拆箱被压成 false 或 true：
        // false 会变成"断言失败"，true 会变成"通过"，两者都在伪造结论。
        if (assertionPassed != null) {
            builder.assertionPassed(assertionPassed);
        }
        return builder.build();
    }

    /**
     * 期望名 vs 实际可访问名。
     *
     * <p>期望名若是模板（{@code {{var}}}，如 "Welcome, {{user}}"），按正则匹配——真实值由页面注入，
     * 静态比较必然失败。归一化统一走 {@link NlsText#visibleText}：剥标签、解实体、折空白，
     * 与 NLS 侧的可见文本定义保持一致（否则 {@code &amp;} / 多余空格会造成假不一致）。</p>
     */
    static boolean namesMatch(String expected, String actual) {
        if (expected == null || expected.isBlank()) {
            return false;
        }
        String actualNormalized = NlsText.visibleText(actual == null ? "" : actual);
        if (NlsText.isTemplate(expected)) {
            return NlsText.pattern(expected).matcher(actualNormalized).matches();
        }
        String expectedNormalized = NlsText.visibleText(expected);
        return !actualNormalized.isEmpty() && expectedNormalized.equals(actualNormalized);
    }

    private static String composeDetail(StructuralObservation obs, boolean textMatched, String traceRef) {
        StringBuilder sb = new StringBuilder();
        if (!obs.observed()) {
            sb.append("未完成观测");
            if (obs.detail() != null && !obs.detail().isBlank()) {
                sb.append("：").append(obs.detail());
            }
            return sb.toString();
        }
        sb.append("命中 ").append(obs.matches()).append(" 个");
        sb.append("；").append(obs.visible() ? "可见" : "不可见");
        sb.append("；").append(obs.enabled() ? "可操作" : "不可操作");
        sb.append("；文案").append(textMatched ? "一致" : "不一致");
        sb.append("（期望=").append(quote(obs.expectedName()))
          .append("，实际=").append(quote(obs.actualName())).append("）");
        if (obs.detail() != null && !obs.detail().isBlank()) {
            sb.append("；").append(obs.detail());
        }
        if (traceRef != null && !traceRef.isBlank()) {
            sb.append("；trace=").append(traceRef.trim());
        }
        return sb.toString();
    }

    private static String quote(String s) {
        return s == null ? "<未声明>" : "'" + s + "'";
    }
}
