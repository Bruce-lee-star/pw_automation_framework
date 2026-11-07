package com.hsbc.cmb.hk.dbb.automation.framework.web.agent;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.asset.AssetDraft;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.asset.AssetId;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.asset.AssetRepository;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.asset.AssetState;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.asset.AssetType;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.asset.Provenance;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.asset.StepPlanApprovalBinding;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.asset.StoredAsset;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.asset.VerificationReport;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.asset.VerificationScope;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.BrowserCommandChannel;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.Command;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.CommandResult;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.LocatorSpec;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.Snapshot;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.SnapshotExpectation;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.StepPlan;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.VerifiableStepPlan;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.scope.ParamConstraint;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.scope.ParamScope;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.harness.IsolationHarness;

import org.junit.Test;

import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * T8 验收：{@link AssetStepPlanExecutor} 的审批绑定前置校验。
 *
 * <p>覆盖三条：① 已审批资产的内容被篡改 → 拒绝执行，错误信息含两侧 digest；
 * ② 已审批且内容一致 → 正常执行并回写报告；③ 未审批（DRAFT）资产 → 不做绑定校验，
 * 行为与 T8 之前逐字一致（零回归）。全程浏览器无关（假 harness / 假通道）。</p>
 */
public class AssetStepPlanExecutorApprovalTest {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .setVisibility(PropertyAccessor.FIELD, JsonAutoDetect.Visibility.ANY)
            .setVisibility(PropertyAccessor.GETTER, JsonAutoDetect.Visibility.NONE)
            .setVisibility(PropertyAccessor.IS_GETTER, JsonAutoDetect.Visibility.NONE);

    private static final AssetId ID = new AssetId("asset:step_plan:1", 1);

    /** 合法且摘要可计算的 STEP_PLAN 载体；value 变化即「计划被篡改」。 */
    private static String content(String value) throws Exception {
        LocatorSpec loc = LocatorSpec.roleOfKey("textbox", "user.key");
        Command navigate = new Command(Command.ActionType.NAVIGATE, null,
                Map.of("url", "https://app/p"), List.of());
        Command fill = new Command(Command.ActionType.FILL, loc, Map.of("value", value),
                List.of(SnapshotExpectation.forRef(loc.canonical()).exists(true).enabled(true).build()));
        ParamScope scope = new ParamScope(Map.of(
                "url", ParamConstraint.allowedValues("https://app/p"),
                "value", ParamConstraint.allowedValues(value)));
        StepPlan plan = new StepPlan(1, "plan-x", List.of(navigate, fill), scope,
                List.of(ParamScope.EMPTY, ParamScope.EMPTY));
        return MAPPER.writeValueAsString(new VerifiableStepPlan(plan, "https://app/p"));
    }

    /** 固定快照通道；满足 FILL 目标 exists+enabled 期望。 */
    private static final class FixedChannel implements BrowserCommandChannel {
        private final Snapshot snapshot = new Snapshot("https://app/p", List.of(
                new Snapshot.ElementState(LocatorSpec.roleOfKey("textbox", "user.key").canonical(),
                        "textbox", "用户名", true, true, "")));
        @Override public Snapshot snapshot() { return snapshot; }
        @Override public CommandResult execute(Command command) { return CommandResult.ok(snapshot); }
    }

    /** 只在被 acquire 时计数，用于证明「拒绝发生在任何执行动作之前」。 */
    private static final class CountingHarness implements IsolationHarness {
        int acquired;
        @Override public BrowserCommandChannel acquire(String targetUrl) {
            acquired++;
            return new FixedChannel();
        }
        @Override public void release(BrowserCommandChannel channel) { }
    }

    /** 可控资产仓库：直接返回预设 StoredAsset，记录是否发生回写。 */
    private static final class FixedRepository implements AssetRepository {
        private final StoredAsset asset;
        VerificationReport recorded;
        FixedRepository(StoredAsset asset) { this.asset = asset; }
        @Override public StoredAsset get(AssetId id) { return asset; }
        @Override public StoredAsset recordVerification(AssetId id, VerificationReport report) {
            this.recorded = report;
            return asset;
        }
        @Override public AssetId save(AssetDraft draft) { throw new UnsupportedOperationException(); }
        @Override public StoredAsset submit(AssetId id, String actor) { throw new UnsupportedOperationException(); }
        @Override public StoredAsset approve(AssetId id, String approver) { throw new UnsupportedOperationException(); }
        @Override public StoredAsset activate(AssetId id) { throw new UnsupportedOperationException(); }
        @Override public StoredAsset deprecate(AssetId id, String reason) { throw new UnsupportedOperationException(); }
        @Override public List<StoredAsset> queryByRequirement(String requirementId) {
            throw new UnsupportedOperationException();
        }
    }

    private static StoredAsset asset(String approvedDigest, String contentJson) {
        return new StoredAsset(ID, AssetType.STEP_PLAN, "REQ-1", AssetState.APPROVED,
                Provenance.manual("p"), contentJson, null, 1L, 2L, approvedDigest);
    }

    @Test
    public void tamperedApprovedPlanIsRejectedWithBothDigests() throws Exception {
        String approvedContent = content("alice");
        String approved = StepPlanApprovalBinding.digestOf(approvedContent);
        String tamperedContent = content("mallory");
        String current = StepPlanApprovalBinding.digestOf(tamperedContent);
        assertNotNull(approved);
        assertTrue("篡改后摘要必须不同", !approved.equals(current));

        FixedRepository repo = new FixedRepository(asset(approved, tamperedContent));
        CountingHarness harness = new CountingHarness();

        try {
            new AssetStepPlanExecutor(repo, harness).run(ID, new VerificationScope("https://app/p", 1));
            fail("已审批内容被篡改必须拒绝执行");
        } catch (IllegalStateException expected) {
            String msg = expected.getMessage();
            assertTrue("含已审批摘要：" + msg, msg.contains(approved));
            assertTrue("含当前摘要：" + msg, msg.contains(current));
        }
        assertEquals("拒绝必须发生在 acquire 之前（不启动靶场）", 0, harness.acquired);
        assertTrue("拒绝时不得回写验证报告", repo.recorded == null);
    }

    @Test
    public void intactApprovedPlanRunsAndWritesBackReport() throws Exception {
        String json = content("alice");
        FixedRepository repo = new FixedRepository(
                asset(StepPlanApprovalBinding.digestOf(json), json));
        CountingHarness harness = new CountingHarness();

        VerificationReport report = new AssetStepPlanExecutor(repo, harness)
                .run(ID, new VerificationScope("https://app/p", 1));

        assertEquals(VerificationReport.Status.PASS, report.status());
        assertEquals(1, harness.acquired);
        assertEquals(report, repo.recorded);
    }

    @Test
    public void draftAssetWithoutBindingStillRuns() throws Exception {
        String json = content("alice");
        // approvedDigest=null：DRAFT 未审批，T8 之前的行为不得改变
        StoredAsset draft = new StoredAsset(ID, AssetType.STEP_PLAN, "REQ-1", AssetState.DRAFT,
                Provenance.manual("p"), json, null, 1L, 2L, null);
        FixedRepository repo = new FixedRepository(draft);
        CountingHarness harness = new CountingHarness();

        VerificationReport report = new AssetStepPlanExecutor(repo, harness)
                .run(ID, new VerificationScope("https://app/p", 1));

        assertEquals(VerificationReport.Status.PASS, report.status());
        assertEquals(1, harness.acquired);
        assertNotNull(repo.recorded);
    }
}
