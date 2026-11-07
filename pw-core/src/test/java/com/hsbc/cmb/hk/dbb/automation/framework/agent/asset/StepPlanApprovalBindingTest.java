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

import org.junit.Test;

import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * {@link StepPlanApprovalBinding} 单测：审批绑定摘要的反算、类型门控、篡改拒绝与 fail-closed 边界。
 *
 * <p>载体 JSON 与生产流水线 / 执行器同一 Jackson 配置（FIELD 可见）；刻意构造与 steps <b>等长</b>的
 * stepScopes，保证 {@link StepPlan#planDigest()} 可计算（不等长属「非可执行计划」，另有护栏用例）。</p>
 */
public class StepPlanApprovalBindingTest {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .setVisibility(PropertyAccessor.FIELD, JsonAutoDetect.Visibility.ANY)
            .setVisibility(PropertyAccessor.GETTER, JsonAutoDetect.Visibility.NONE)
            .setVisibility(PropertyAccessor.IS_GETTER, JsonAutoDetect.Visibility.NONE);

    private final AssetId id = new AssetId("asset:step_plan:1", 1);

    /** 构造合法且可计算摘要的 STEP_PLAN 载体 JSON；fillValue 变化即内容变化。 */
    private static String carrierJson(String fillValue) throws Exception {
        LocatorSpec loc = LocatorSpec.roleOfKey("textbox", "user.key");
        Command navigate = new Command(Command.ActionType.NAVIGATE, null,
                Map.of("url", "https://app/p"), List.of());
        Command fill = new Command(Command.ActionType.FILL, loc,
                Map.of("value", fillValue), List.of());
        ParamScope scope = new ParamScope(Map.of(
                "url", ParamConstraint.allowedValues("https://app/p"),
                "value", ParamConstraint.allowedValues(fillValue)));
        // 等长 stepScopes：planDigest() 逐步读取，长度不足会视为非可执行计划
        StepPlan plan = new StepPlan(1, "plan-x", List.of(navigate, fill), scope,
                List.of(ParamScope.EMPTY, ParamScope.EMPTY));
        return MAPPER.writeValueAsString(new VerifiableStepPlan(plan, "https://app/p"));
    }

    @Test
    public void digestOfReturnsExecutionDomainFingerprint() throws Exception {
        String json = carrierJson("alice");
        String digest = StepPlanApprovalBinding.digestOf(json);

        assertEquals(VerifiableStepPlan.fromJson(json).plan().planDigest(), digest);
        assertEquals("同内容同摘要（稳定、确定性）", digest, StepPlanApprovalBinding.digestOf(json));
    }

    @Test
    public void digestChangesWhenPlanContentChanges() throws Exception {
        String original = StepPlanApprovalBinding.digestOf(carrierJson("alice"));
        String tampered = StepPlanApprovalBinding.digestOf(carrierJson("mallory"));

        assertNotEquals("参数被篡改后摘要必须变化", original, tampered);
    }

    @Test
    public void digestOfNonStepPlanTypeIsNull() throws Exception {
        assertNull(StepPlanApprovalBinding.digestOf(AssetType.LINKAGE_GRAPH, carrierJson("alice")));
        assertNull(StepPlanApprovalBinding.digestOf(AssetType.BEHAVIOR_MODEL, carrierJson("alice")));
    }

    @Test
    public void malformedContentDoesNotThrowAndIsNotBound() {
        assertNull(StepPlanApprovalBinding.digestOf(null));
        assertNull(StepPlanApprovalBinding.digestOf("  "));
        assertNull(StepPlanApprovalBinding.digestOf("not-json"));
        assertNull(StepPlanApprovalBinding.digestOf("{}"));
        assertNull(StepPlanApprovalBinding.digestOf("{\"plan\":null}"));
    }

    @Test
    public void misalignedStepScopesAreNotBoundInsteadOfThrowing() {
        // stepScopes 长度 < steps：planDigest() 会抛 IndexOutOfBounds，绑定层必须收敛为 null
        String json = "{\"plan\":{\"schemaVersion\":1,\"planId\":\"p\",\"steps\":["
                + "{\"action\":\"NAVIGATE\",\"target\":null,\"params\":{\"url\":\"https://app/p\"},"
                + "\"expectations\":[]}],\"scope\":{\"constraints\":{}},\"stepScopes\":[]},"
                + "\"pageRef\":\"https://app/p\"}";
        assertNull(StepPlanApprovalBinding.digestOf(AssetType.STEP_PLAN, json));
    }

    @Test
    public void verifyWithNoBindingIsNoOp() throws Exception {
        String json = carrierJson("alice");
        // 未审批资产（含内容损坏）：不做任何校验，保留既有行为
        StepPlanApprovalBinding.verify(id, null, json);
        StepPlanApprovalBinding.verify(id, null, "not-json");
    }

    @Test
    public void verifyMatchingDigestPassesIncludingCaseInsensitive() throws Exception {
        String json = carrierJson("alice");
        String digest = StepPlanApprovalBinding.digestOf(json);

        StepPlanApprovalBinding.verify(id, digest, json);
        StepPlanApprovalBinding.verify(id, digest.toUpperCase(java.util.Locale.ROOT), json);
    }

    @Test
    public void verifyMismatchRejectsWithBothDigests() throws Exception {
        String approvedJson = carrierJson("alice");
        String approved = StepPlanApprovalBinding.digestOf(approvedJson);
        String tamperedJson = carrierJson("mallory");
        String current = StepPlanApprovalBinding.digestOf(tamperedJson);

        try {
            StepPlanApprovalBinding.verify(id, approved, tamperedJson);
            fail("已审批内容被篡改必须拒绝执行");
        } catch (IllegalStateException expected) {
            String msg = expected.getMessage();
            assertTrue("错误信息须含已审批摘要：" + msg, msg.contains(approved));
            assertTrue("错误信息须含当前摘要：" + msg, msg.contains(current));
            assertTrue("错误信息须含资产标识", msg.contains(id.toString()));
            assertTrue("错误信息须说明原因", msg.contains("篡改"));
        }
    }

    @Test
    public void verifyUncomputableCurrentContentFailsClosed() throws Exception {
        String approved = StepPlanApprovalBinding.digestOf(carrierJson("alice"));

        try {
            StepPlanApprovalBinding.verify(id, approved, "not-json");
            fail("已绑定资产的当前内容无法反算摘要时必须 fail-closed 拒绝");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains(approved));
            assertTrue(expected.getMessage().contains("current=null"));
        }
    }

    @Test
    public void executionDigestIsStableHexFingerprint() throws Exception {
        String digest = StepPlanApprovalBinding.digestOf(carrierJson("alice"));
        assertEquals("SHA-256 前 8 字节 = 16 位十六进制", 16, digest.length());
        assertTrue("须为小写十六进制：" + digest, digest.matches("[0-9a-f]{16}"));
    }
}
