package com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen.locator.candidate;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.LocatorSpec;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * <b>版本化候选库</b>（设计 §4.4 / P3）：候选的提出、验证、审批、失效与审计链。
 *
 * <h2>核心铁律：验证通过 ≠ 生产可用</h2>
 * <ul>
 *   <li>{@link #propose} 只产生 {@code PROPOSED}；</li>
 *   <li>{@link #submitForValidation} 通过五条规则也只到 {@code VALIDATED}；</li>
 *   <li>只有 {@link #approve}（<b>必须显式给出审批人与目标应用版本</b>）才到 {@code APPROVED}；</li>
 *   <li>{@link #productionLocator} 只认 {@code APPROVED} 且应用版本一致，<b>其余一律返回空</b>——
 *       没有"降级用次优候选"的路径。</li>
 * </ul>
 *
 * <h2>失败关闭</h2>
 * <p>同一目标语义 + 同一应用版本存在<b>多条</b> APPROVED 时同样返回空（歧义即失败），
 * 不按分数/时间挑一条，避免"猜到错的那个元素"。
 *
 * <h2>可审计</h2>
 * <p>每条记录带递增版本号与事件链（提出/验证/审批/失效，含操作者与时间），
 * 时间来自注入的 {@link Clock}，测试可固定。
 */
public final class CandidateLibrary {

    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .disableHtmlEscaping()
            .create();

    private final CandidateValidator validator;
    private final ApprovalPolicy approvalPolicy;
    private final Clock clock;
    private int seq;
    private final Map<String, CandidateRecord> records = new LinkedHashMap<>();

    public CandidateLibrary() {
        this(new CandidateValidator(), new ApprovalPolicy(), Clock.systemUTC());
    }

    public CandidateLibrary(CandidateValidator validator, Clock clock) {
        this(validator, new ApprovalPolicy(), clock);
    }

    public CandidateLibrary(CandidateValidator validator, ApprovalPolicy approvalPolicy, Clock clock) {
        this.validator = Objects.requireNonNull(validator, "validator");
        this.approvalPolicy = Objects.requireNonNull(approvalPolicy, "approvalPolicy");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** 提出候选（{@code PROPOSED}）；不做任何可信度判断。 */
    public CandidateRecord propose(LocatorCandidate candidate) {
        Objects.requireNonNull(candidate, "candidate");
        String id = String.format("cand-%04d", ++seq);
        CandidateRecord record = new CandidateRecord(id, candidate, Instant.now(clock));
        records.put(id, record);
        return record;
    }

    /**
     * 用一份观测证据跑确定性验证：全通过 → {@code VALIDATED}，否则 {@code REJECTED}。
     *
     * <p>允许对 {@code PROPOSED}/{@code VALIDATED} 重跑（页面改版后重新取证），
     * 但 {@code REJECTED}/{@code APPROVED} 不可再验证——前者须新提候选，后者须先失效。
     */
    public CandidateRecord submitForValidation(String id, CandidateEvidence evidence) {
        CandidateRecord record = get(id);
        if (record.state() != CandidateState.PROPOSED && record.state() != CandidateState.VALIDATED) {
            throw new IllegalStateException("仅 PROPOSED/VALIDATED 候选可验证，当前 " + record.state()
                    + "（已审批候选须先失效再重新验证）");
        }
        CandidateValidator.Validation validation = validator.validate(record.candidate(), evidence);
        Instant now = Instant.now(clock);
        if (validation.passed()) {
            record.markValidated(validation, evidence, now);
        } else {
            record.markRejected(validation, evidence, "验证未通过：见 findings",
                    record.candidate().proposedBy(), now);
        }
        return record;
    }

    /**
     * 人工审批：{@code VALIDATED → APPROVED}，并且必须通过 {@link ApprovalPolicy} 的七条规则。
     *
     * <p>这是<b>唯一</b>能把候选变成生产定位器的入口。审批不是"记一个名字"：
     * 请求必须声明业务后果等级、origin、应用版本与审批人（{@link RiskTier#CRITICAL} 需两人），
     * 通过后签发 {@link ApprovalGrant}，把「谁、批的哪份内容、在哪个环境、有效期到何时」固化下来。
     *
     * @throws IllegalStateException 状态不是 VALIDATED，或策略拒绝（越级审批 / 职责不分离 / 摘要不符 / 有效期越界）
     */
    public CandidateRecord approve(String id, ApprovalRequest request) {
        CandidateRecord record = get(id);
        if (record.state() != CandidateState.VALIDATED) {
            throw new IllegalStateException("仅 VALIDATED 候选可审批，当前 " + record.state()
                    + "：验证通过不会自动升级，未验证候选不得跳过验证直接审批");
        }
        if (request == null) {
            throw new IllegalArgumentException("request 不可为空：审批必须显式声明后果等级、origin 与有效期");
        }
        Instant now = Instant.now(clock);
        ApprovalPolicy.Verdict verdict = approvalPolicy.evaluate(record, request, now);
        if (!verdict.approved()) {
            throw new IllegalStateException("审批被拒绝：" + verdict.summary());
        }
        record.markApproved(verdict.grant(), now);
        return record;
    }

    /** 人工驳回（{@code PROPOSED/VALIDATED → REJECTED}），必须写明原因。 */
    public CandidateRecord reject(String id, String reason) {
        return reject(id, reason, null);
    }

    /**
     * 人工驳回并记录<b>驳回人</b>。
     *
     * <p>{@code actor} 为空时退回提出者（兼容早期调用）；CLI 一律传真实操作者，
     * 否则审计链上"谁驳回了这条候选"就追不到人。
     */
    public CandidateRecord reject(String id, String reason, String actor) {
        CandidateRecord record = get(id);
        if (record.state() != CandidateState.PROPOSED && record.state() != CandidateState.VALIDATED) {
            throw new IllegalStateException("仅 PROPOSED/VALIDATED 候选可驳回，当前 " + record.state());
        }
        requireText(reason, "reason 不可为空：驳回必须留下原因");
        record.markRejected(record.validation(), record.evidence(), "人工驳回：" + reason.trim(),
                actor == null || actor.isBlank() ? null : actor.trim(), Instant.now(clock));
        return record;
    }

    /** 失效（任意非 RETIRED → RETIRED），必须写明原因；失效候选不参与任何回退。 */
    public CandidateRecord retire(String id, String reason) {
        return retire(id, reason, null);
    }

    /** 失效并记录<b>执行人</b>（审计链需要能追到"谁下架的"）。 */
    public CandidateRecord retire(String id, String reason, String actor) {
        CandidateRecord record = get(id);
        if (record.state() == CandidateState.RETIRED) {
            throw new IllegalStateException("候选已失效：" + id);
        }
        requireText(reason, "reason 不可为空：失效必须说明原因");
        record.markRetired(reason.trim(), actor == null || actor.isBlank() ? "manual" : actor.trim(),
                Instant.now(clock));
        return record;
    }

    /**
     * 取出可用于生产的定位器：<b>APPROVED + 凭据有效 + 目标语义一致 + 应用版本一致 + origin 一致 + 唯一命中</b>。
     *
     * <p>凭据有效 = 未过期，且 {@link ApprovalGrant#covers} 的四项绑定逐条对上；
     * 被手工改成 APPROVED、却没有授权凭据的历史记录同样返回空。
     *
     * <p>返回空可能意味着：没有候选、未审批、审批过期、版本或 origin 不匹配、内容摘要已变、或多条审批互相冲突。
     * 无论哪种，调用方都应<b>停止并转人工</b>，不得自行回退到其它候选。
     *
     * @param origin 页面来源/环境（如 {@code https://dbb-uat.example.com} 或 {@code uat}），须与审批时绑定的一致
     */
    public Optional<CandidateRecord> productionLocator(String targetRef, String appVersion, String origin) {
        requireText(targetRef, "targetRef 不可为空");
        requireText(appVersion, "appVersion 不可为空");
        requireText(origin, "origin 不可为空：回注必须指明环境/来源，否则无法核对审批绑定");
        Instant now = Instant.now(clock);
        List<CandidateRecord> hits = records.values().stream()
                .filter(CandidateRecord::approved)
                .filter(r -> r.candidate().targetRef().equals(targetRef.trim()))
                .filter(r -> r.grant() != null
                        && r.grant().covers(origin, appVersion.trim(),
                                ApprovalGrant.planDigestOf(r.candidate()), now))
                .toList();
        return hits.size() == 1 ? Optional.of(hits.get(0)) : Optional.empty();
    }

    /**
     * 回注出口：取出<b>已审批且授权仍有效</b>候选的 {@link LocatorSpec}，供代码生成 / 运行时直接使用。
     *
     * <p>语义与 {@link #productionLocator} 完全一致（过期、版本不符、origin 不符、摘要已变、未审批、歧义都返回空），
     * 只是把"记录"收敛成"定位符"，调用方拿不到审批链之外的信息。
     */
    public Optional<LocatorSpec> approvedLocator(String targetRef, String appVersion, String origin) {
        return productionLocator(targetRef, appVersion, origin).map(r -> r.candidate().spec());
    }

    /**
     * 按保留策略清除过期记录（设计 §4.4 / R9 最小化审计）。
     *
     * <p>只删<b>已过期且宽限期已过</b>的候选；任何仍可使用（授权未过期）的 {@code APPROVED} 记录，
     * 其清除时点永远在未来，因此不会被误删。清除不可逆——CLI 默认干跑，必须显式 {@code --apply} 才落盘。
     *
     * <p>注意：id 计数器 {@code seq} 不回退，被清除的 id 永不复用，避免新记录撞上"已删除记录的历史引用"。
     *
     * @return 本次清除的报告（清除数 / 保留数 / 按状态计数 / 被清除 id）
     */
    public PurgeReport purgeExpired(Instant now) {
        return purgeExpired(RetentionPolicy.defaults(), now);
    }

    /** 用指定策略清除（便于测试注入更短的窗口）。 */
    public PurgeReport purgeExpired(RetentionPolicy policy, Instant now) {
        Objects.requireNonNull(policy, "policy");
        Objects.requireNonNull(now, "now");
        List<String> removedIds = new ArrayList<>();
        Map<CandidateState, Integer> removedByState = new EnumMap<>(CandidateState.class);
        for (Iterator<Map.Entry<String, CandidateRecord>> it = records.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<String, CandidateRecord> entry = it.next();
            CandidateRecord record = entry.getValue();
            if (policy.isExpired(record, now)) {
                it.remove();
                removedIds.add(entry.getKey());
                removedByState.merge(record.state(), 1, Integer::sum);
            }
        }
        return new PurgeReport(removedIds.size(), records.size(), removedByState, removedIds);
    }

    /** 全部记录（插入顺序 = 提出顺序）。 */
    public List<CandidateRecord> all() {
        return List.copyOf(records.values());
    }

    public Optional<CandidateRecord> find(String id) {
        return Optional.ofNullable(records.get(id));
    }

    public CandidateRecord get(String id) {
        CandidateRecord record = records.get(id);
        if (record == null) {
            throw new IllegalArgumentException("候选不存在：" + id);
        }
        return record;
    }

    public int size() {
        return records.size();
    }

    // ------------------------------------------------------------------
    // 持久化（JSON，原子写）
    // ------------------------------------------------------------------

    /** 落盘（UTF-8，先写临时文件再原子替换）。 */
    public void save(Path file) throws IOException {
        Path parent = file.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        String json = GSON.toJson(toDto());
        Path tmp = Files.createTempFile(parent, "candidates-", ".json.tmp");
        Files.writeString(tmp, json, StandardCharsets.UTF_8);
        try {
            Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException atomicFailed) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public static CandidateLibrary load(Path file) throws IOException {
        return load(file, Clock.systemUTC());
    }

    /** 读取候选库（文件不存在时返回空库）。 */
    public static CandidateLibrary load(Path file, Clock clock) throws IOException {
        CandidateLibrary library = new CandidateLibrary(new CandidateValidator(), clock);
        if (!Files.isRegularFile(file)) {
            return library;
        }
        LibraryDto dto;
        try {
            dto = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), LibraryDto.class);
        } catch (JsonParseException e) {
            throw new IOException("候选库 JSON 解析失败: " + file + " - " + e.getMessage(), e);
        }
        if (dto == null) {
            return library;
        }
        library.seq = Math.max(0, dto.seq);
        if (dto.records != null) {
            for (RecordDto rd : dto.records) {
                CandidateRecord record = fromDto(rd);
                library.records.put(record.id(), record);
            }
        }
        return library;
    }

    // ------------------------------------------------------------------
    // JSON 映射
    // ------------------------------------------------------------------

    private LibraryDto toDto() {
        LibraryDto dto = new LibraryDto();
        dto.seq = seq;
        dto.records = new ArrayList<>();
        for (CandidateRecord r : records.values()) {
            RecordDto rd = new RecordDto();
            rd.id = r.id();
            rd.targetRef = r.candidate().targetRef();
            rd.locator = r.candidate().canonical();
            rd.expectedRole = r.candidate().expectedRole();
            rd.source = r.candidate().source();
            rd.evidenceRef = r.candidate().evidenceRef();
            rd.proposedBy = r.candidate().proposedBy();
            rd.state = r.state().name();
            rd.approvedBy = r.approvedBy();
            rd.approvalAppVersion = r.approvalAppVersion();
            rd.approval = toDto(r.grant());
            rd.retireReason = r.retireReason();
            rd.version = r.version();
            rd.validation = toDto(r.validation());
            rd.evidence = toDto(r.evidence());
            rd.history = new ArrayList<>();
            for (CandidateRecord.Event e : r.history()) {
                EventDto ed = new EventDto();
                ed.version = e.version();
                ed.state = e.state().name();
                ed.actor = e.actor();
                ed.detail = e.detail();
                ed.at = e.at() == null ? null : e.at().toString();
                rd.history.add(ed);
            }
            dto.records.add(rd);
        }
        return dto;
    }

    private static ValidationDto toDto(CandidateValidator.Validation v) {
        if (v == null) {
            return null;
        }
        ValidationDto dto = new ValidationDto();
        dto.passed = v.passed();
        dto.findings = new ArrayList<>();
        for (CandidateValidator.Finding f : v.findings()) {
            FindingDto fd = new FindingDto();
            fd.ruleId = f.ruleId();
            fd.passed = f.passed();
            fd.detail = f.detail();
            dto.findings.add(fd);
        }
        return dto;
    }

    private static EvidenceDto toDto(CandidateEvidence e) {
        if (e == null) {
            return null;
        }
        EvidenceDto dto = new EvidenceDto();
        dto.matches = e.matches();
        dto.visible = e.visible();
        dto.enabled = e.enabled();
        dto.textMatched = e.textMatched();
        dto.businessAssertion = e.businessAssertion();
        dto.assertionPassed = e.assertionPassed();
        dto.detail = e.detail();
        return dto;
    }

    private static ApprovalDto toDto(ApprovalGrant g) {
        if (g == null) {
            return null;
        }
        ApprovalDto dto = new ApprovalDto();
        dto.candidateId = g.candidateId();
        dto.riskTier = g.riskTier().name();
        dto.planDigest = g.planDigest();
        dto.origin = g.origin();
        dto.appVersion = g.appVersion();
        dto.approvers = new ArrayList<>(g.approvers());
        dto.note = g.note();
        dto.issuedAt = g.issuedAt().toString();
        dto.expiresAt = g.expiresAt().toString();
        return dto;
    }

    private static ApprovalGrant fromDto(ApprovalDto dto, String recordId) {
        if (dto == null) {
            return null;
        }
        if (dto.candidateId != null && !dto.candidateId.isBlank() && !dto.candidateId.equals(recordId)) {
            throw new IllegalArgumentException(
                    "授权凭据挂在其它候选上（凭据 " + dto.candidateId + "，记录 " + recordId + "）：拒绝加载");
        }
        try {
            return ApprovalGrant.restore(recordId, RiskTier.parse(dto.riskTier), dto.planDigest,
                    dto.origin, dto.appVersion, dto.approvers, dto.note,
                    dto.issuedAt == null ? null : Instant.parse(dto.issuedAt),
                    dto.expiresAt == null ? null : Instant.parse(dto.expiresAt));
        } catch (RuntimeException e) {
            // 凭据字段坏了就是坏了：宁可整库读取失败，也不要悄悄当成"无审批"继续跑。
            throw new IllegalArgumentException("授权凭据解析失败（" + recordId + "）：" + e.getMessage(), e);
        }
    }

    private static CandidateRecord fromDto(RecordDto rd) {
        LocatorCandidate candidate = LocatorCandidate.of(rd.targetRef, LocatorSpec.parse(rd.locator))
                .withExpectedRole(rd.expectedRole)
                .withSource(rd.source)
                .withEvidenceRef(rd.evidenceRef)
                .withProposedBy(rd.proposedBy);

        CandidateValidator.Validation validation = null;
        if (rd.validation != null) {
            List<CandidateValidator.Finding> findings = new ArrayList<>();
            if (rd.validation.findings != null) {
                for (FindingDto fd : rd.validation.findings) {
                    findings.add(new CandidateValidator.Finding(fd.ruleId, fd.passed, fd.detail));
                }
            }
            validation = new CandidateValidator.Validation(findings);
        }

        CandidateEvidence evidence = null;
        if (rd.evidence != null) {
            CandidateEvidence.Builder b = CandidateEvidence.builder()
                    .matches(rd.evidence.matches)
                    .visible(rd.evidence.visible)
                    .enabled(rd.evidence.enabled)
                    .textMatched(rd.evidence.textMatched)
                    .businessAssertion(rd.evidence.businessAssertion)
                    .detail(rd.evidence.detail);
            if (rd.evidence.assertionPassed != null) {
                b.assertionPassed(rd.evidence.assertionPassed);
            }
            evidence = b.build();
        }

        List<CandidateRecord.Event> history = new ArrayList<>();
        if (rd.history != null) {
            for (EventDto ed : rd.history) {
                history.add(new CandidateRecord.Event(ed.version, CandidateState.valueOf(ed.state),
                        ed.actor, ed.detail, ed.at == null ? null : Instant.parse(ed.at)));
            }
        }

        return new CandidateRecord(rd.id, candidate, CandidateState.valueOf(rd.state), validation, evidence,
                fromDto(rd.approval, rd.id), rd.approvedBy, rd.retireReason, rd.version, history);
    }

    private static void requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
    }

    // --- Gson DTO ---

    private static final class LibraryDto {
        int seq;
        List<RecordDto> records;
    }

    private static final class RecordDto {
        String id;
        String targetRef;
        String locator;
        String expectedRole;
        String source;
        String evidenceRef;
        String proposedBy;
        String state;
        String approvedBy;
        String approvalAppVersion;
        ApprovalDto approval;
        String retireReason;
        int version;
        ValidationDto validation;
        EvidenceDto evidence;
        List<EventDto> history;
    }

    /**
     * 授权凭据的落盘形态。
     *
     * <p>{@code approvedBy}/{@code approvalAppVersion} 仍写在记录上，是为了让旧版本工具链读得懂；
     * 但读取时<b>只认</b> {@code approval} 块——只有它带 origin、摘要与到期时间，能支撑回注核对。
     * 旧库里"手工改成 APPROVED 但没有 approval 块"的记录，加载后视为无凭据，不可回注。
     */
    private static final class ApprovalDto {
        String candidateId;
        String riskTier;
        String planDigest;
        String origin;
        String appVersion;
        List<String> approvers;
        String note;
        String issuedAt;
        String expiresAt;
    }

    private static final class ValidationDto {
        boolean passed;
        List<FindingDto> findings;
    }

    private static final class FindingDto {
        String ruleId;
        boolean passed;
        String detail;
    }

    private static final class EvidenceDto {
        int matches;
        boolean visible;
        boolean enabled;
        boolean textMatched;
        String businessAssertion;
        Boolean assertionPassed;
        String detail;
    }

    private static final class EventDto {
        int version;
        String state;
        String actor;
        String detail;
        String at;
    }
}
