package com.hsbc.cmb.hk.dbb.automation.framework.agent.asset;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.Command;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.LocatorSpec;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.StepPlan;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.VerifiableStepPlan;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.scope.ParamConstraint;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.scope.ParamScope;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

/**
 * 资产仓库审批绑定（T8）：{@code approve()} 记录 STEP_PLAN 内容摘要，其它迁移不解绑，
 * 文件型仓库落盘后重载不丢绑定。覆盖 {@link InMemoryAssetRepository} 与 {@link JsonFileAssetRepository}。
 */
public class AssetApprovalBindingTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .setVisibility(PropertyAccessor.FIELD, JsonAutoDetect.Visibility.ANY)
            .setVisibility(PropertyAccessor.GETTER, JsonAutoDetect.Visibility.NONE)
            .setVisibility(PropertyAccessor.IS_GETTER, JsonAutoDetect.Visibility.NONE);

    private static String stepPlanContent(String value) throws Exception {
        LocatorSpec loc = LocatorSpec.roleOfKey("textbox", "user.key");
        Command navigate = new Command(Command.ActionType.NAVIGATE, null,
                Map.of("url", "https://app/p"), List.of());
        Command fill = new Command(Command.ActionType.FILL, loc, Map.of("value", value), List.of());
        ParamScope scope = new ParamScope(Map.of(
                "url", ParamConstraint.allowedValues("https://app/p"),
                "value", ParamConstraint.allowedValues(value)));
        StepPlan plan = new StepPlan(1, "plan-x", List.of(navigate, fill), scope,
                List.of(ParamScope.EMPTY, ParamScope.EMPTY));
        return MAPPER.writeValueAsString(new VerifiableStepPlan(plan, "https://app/p"));
    }

    private static AssetDraft stepPlanDraft(String value) throws Exception {
        return new AssetDraft(AssetType.STEP_PLAN, "REQ-1", stepPlanContent(value),
                Provenance.manual("page:sha256:abc"));
    }

    @Test
    public void inMemoryApprovalBindsDigestAndOtherTransitionsPreserveIt() throws Exception {
        InMemoryAssetRepository repo = new InMemoryAssetRepository();
        String content = stepPlanContent("alice");
        AssetId id = repo.save(new AssetDraft(AssetType.STEP_PLAN, "REQ-1", content,
                Provenance.manual("p")));

        assertNull("DRAFT 未审批，不绑定", repo.get(id).approvedDigest());

        repo.submit(id, "analyst");
        assertNull("PENDING_REVIEW 尚未审批，不绑定", repo.get(id).approvedDigest());

        StoredAsset approved = repo.approve(id, "lead");
        String bound = approved.approvedDigest();
        assertNotNull("APPROVED 必须绑定执行域摘要", bound);
        assertEquals(bound, StepPlanApprovalBinding.digestOf(content));
        assertEquals(AssetState.APPROVED, approved.state());

        repo.activate(id);
        assertEquals("ACTIVATE 不得解绑", bound, repo.get(id).approvedDigest());

        VerificationReport report = new VerificationReport(
                VerificationReport.Status.PASS, 1.0, List.of(), "trace://x");
        repo.recordVerification(id, report);
        assertEquals("回写验证报告不得解绑", bound, repo.get(id).approvedDigest());
    }

    @Test
    public void nonStepPlanApprovalStaysUnbound() {
        InMemoryAssetRepository repo = new InMemoryAssetRepository();
        AssetId id = repo.save(new AssetDraft(AssetType.LINKAGE_GRAPH, "REQ-2", "{}",
                Provenance.manual("p")));
        repo.submit(id, "analyst");

        StoredAsset approved = repo.approve(id, "lead");

        assertEquals(AssetState.APPROVED, approved.state());
        assertNull("非 STEP_PLAN 资产不参与执行域审批绑定", approved.approvedDigest());
    }

    @Test
    public void jsonFileRepositoryPersistsBindingAcrossReload() throws Exception {
        Path root = tmp.newFolder().toPath();
        JsonFileAssetRepository repo = new JsonFileAssetRepository(root);
        String content = stepPlanContent("alice");
        AssetId id = repo.save(new AssetDraft(AssetType.STEP_PLAN, "REQ-3", content,
                Provenance.manual("p")));
        repo.submit(id, "analyst");
        String bound = repo.approve(id, "lead").approvedDigest();
        assertNotNull(bound);

        JsonFileAssetRepository reloaded = new JsonFileAssetRepository(root);
        StoredAsset loaded = reloaded.get(id);
        assertEquals(AssetState.APPROVED, loaded.state());
        assertEquals("落盘重载后审批绑定摘要必须无损", bound, loaded.approvedDigest());
        assertEquals(content, loaded.contentJson());
    }

    @Test
    public void draftAssetHelperProducesComputableDigest() throws Exception {
        // 护栏：测试载体本身必须是「可计算摘要」的合法计划，否则绑定会静默退化为 null
        assertNotNull(StepPlanApprovalBinding.digestOf(stepPlanDraft("alice").contentJson()));
    }
}
