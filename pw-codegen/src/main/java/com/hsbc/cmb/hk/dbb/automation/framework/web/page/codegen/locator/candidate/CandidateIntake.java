package com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen.locator.candidate;

import java.io.IOException;
import java.nio.file.Path;

/**
 * <b>一键取证入库</b>：提出候选 → 真实页面取证 → 确定性验证 → 落盘（设计 §4.4 / P3 闭环）。
 *
 * <p>此前这条链路要靠人手工拼：先 {@code candidate propose}，再手填
 * {@code --matches/--visible/--enabled/--text-matched} 跑 {@code validate}。手填的那几个数字
 * 不是证据，是<b>填表人的假设</b>——而定位器失效恰恰是自动化维护里最高频的故障。
 * 本类把"取"证据这件事交给探针，人只负责给业务断言（"点完应该看到什么"），
 * 那是唯一机器无法替你判断的部分。</p>
 *
 * <h2>两个刻意的选择</h2>
 * <ol>
 *   <li><b>全程持写锁、最后一次落盘</b>：中途任何一步失败都不会留下"候选已提出但没验证"的
 *       半截状态，也不会读到别人写了一半的台账。</li>
 *   <li><b>观测失败不消耗候选</b>：探针炸了（页面没加载、浏览器崩了）只让候选停在
 *       {@code PROPOSED}，可以重跑再取证。若此时直接判 {@code REJECTED}，而 {@code REJECTED}
 *       又是不可再验证的终态，一次网络抖动就会永久废掉一条本来正确的候选——把"没看清"当成
 *       "看错了"，是这类系统最容易埋的坑。</li>
 * </ol>
 */
public final class CandidateIntake {

    private CandidateIntake() {
    }

    /**
     * 取证并提交验证。
     *
     * @param libraryFile      候选库文件（不存在则新建；写入持排他锁）
     * @param candidate        待验证候选（targetRef + 定位符 + 目标语义）
     * @param probe            取证探针
     * @param businessAssertion 业务断言描述，必填
     * @param assertionPassed  业务断言结果；<b>必须显式给出</b>（{@code null} 视为未执行，入口即拒绝）
     * @param traceRef         证据出处（trace / 会话 id），可空
     * @return 处理结果：{@code VALIDATED} 可提交审批，{@code REJECTED} 有明确失败项，
     *         {@code PROPOSED} 表示取证未完成、可重跑
     */
    public static IntakeResult run(Path libraryFile,
                                   LocatorCandidate candidate,
                                   EvidenceProbe probe,
                                   String businessAssertion,
                                   Boolean assertionPassed,
                                   String traceRef) throws IOException {
        if (libraryFile == null) {
            throw new IllegalArgumentException("libraryFile 不可为空");
        }
        if (candidate == null) {
            throw new IllegalArgumentException("candidate 不可为空");
        }
        if (probe == null) {
            throw new IllegalArgumentException("probe 不可为空：无探针就没有证据，不得验证");
        }
        // fail-closed：断言缺失属于"没给证据"，必须在碰磁盘之前挡住，
        // 否则候选会被打成不可再验证的 REJECTED 终态。
        if (businessAssertion == null || businessAssertion.isBlank()) {
            throw new IllegalArgumentException("业务断言必填：'定位到了'与'点的是对的'是两件事");
        }
        if (assertionPassed == null) {
            throw new IllegalArgumentException("业务断言须显式给出结果（true/false），未执行不得入库");
        }

        try (LibraryLock ignored = LibraryLock.acquire(libraryFile)) {
            CandidateLibrary library = CandidateLibrary.load(libraryFile);
            CandidateRecord record = library.propose(candidate);

            StructuralObservation observation = probe.observe(candidate);
            if (observation == null || !observation.observed()) {
                library.save(libraryFile);
                String why = observation == null ? "探针未返回结果" : observation.detail();
                return new IntakeResult(record.id(), record.state(), null, null,
                        "观测未完成，候选保持 PROPOSED（可重跑取证）：" + why);
            }

            CandidateEvidence evidence = CandidateEvidenceCollector.collect(
                    candidate, observation, businessAssertion, assertionPassed, traceRef);
            record = library.submitForValidation(record.id(), evidence);
            library.save(libraryFile);

            String note = record.state() == CandidateState.VALIDATED
                    ? "验证通过，可提交审批（不等于生产可用）"
                    : "验证未通过：" + record.validation().failures().size() + " 条规则失败";
            return new IntakeResult(record.id(), record.state(), record.validation(), evidence, note);
        }
    }

    /**
     * 处理结果。
     *
     * @param candidateId 候选 id
     * @param state       落盘后的状态
     * @param validation  验证结论（取证未完成时为 {@code null}）
     * @param evidence    入库证据（取证未完成时为 {@code null}）
     * @param note        人话说明
     */
    public record IntakeResult(String candidateId, CandidateState state,
                               CandidateValidator.Validation validation,
                               CandidateEvidence evidence, String note) {

        /** 可提交审批（仍未生产可用）。 */
        public boolean validated() {
            return state == CandidateState.VALIDATED;
        }

        /** 未消耗候选，可重跑取证。 */
        public boolean retryable() {
            return state == CandidateState.PROPOSED;
        }
    }
}
