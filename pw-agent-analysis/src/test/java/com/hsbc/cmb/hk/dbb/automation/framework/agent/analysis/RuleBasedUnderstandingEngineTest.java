package com.hsbc.cmb.hk.dbb.automation.framework.agent.analysis;

import com.hsbc.cmb.hk.dbb.automation.framework.agent.analysis.engine.AssetDraftBundle;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.analysis.engine.BehaviorEntry;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.analysis.engine.ElementInventoryEntry;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.analysis.engine.RuleBasedUnderstandingEngine;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.analysis.observation.ManualPageObservationProvider;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.analysis.observation.ObservedElement;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.analysis.observation.PageObservation;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.analysis.requirement.RequirementContext;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.analysis.requirement.RequirementEntry;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.analysis.requirement.RequirementId;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.analysis.requirement.StaticRequirementSource;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.LocatorSpec;
import org.junit.Test;

import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/** 规则理解引擎：确定性产出元素清单 / 行为模型 / 空联动（设计 §4.6）。 */
public class RuleBasedUnderstandingEngineTest {

    private static ObservedElement elem(String role, String name) {
        return new ObservedElement(role, name, name, true, true,
                List.of(LocatorSpec.role(role, name)), Map.of());
    }

    @Test
    public void producesInventoryAndBehaviors() {
        PageObservation obs = new PageObservation("https://x/transfer", "p-transfer",
                List.of(elem("checkbox", "I agree to the terms"),
                        elem("textbox", "Reason"),
                        elem("button", "Submit")),
                "manual");

        RequirementEntry req = new RequirementEntry(new RequirementId("REQ-101"),
                "Transfer amount", List.of("user must agree to terms before submit"), List.of(), "transfer.feature");
        RequirementContext ctx = new RequirementContext(List.of(req), "transfer.feature");

        AssetDraftBundle bundle = new RuleBasedUnderstandingEngine().analyze(ctx, obs);

        assertEquals(3, bundle.inventory().entries().size());
        assertEquals(3, bundle.behaviors().entries().size());
        assertNotNull(bundle.linkage());

        // 需求映射：checkbox 名称含 "agree" → 命中 REQ-101 验收标准
        ElementInventoryEntry agree = bundle.inventory().entries().get(0);
        assertEquals("REQ-101@1.0", agree.requirementId());

        // 行为推断
        BehaviorEntry checkbox = bundle.behaviors().entries().get(0);
        assertEquals(com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.Command.ActionType.CLICK, checkbox.action());
        assertEquals("toggle", checkbox.expectedResultCategory());

        BehaviorEntry reason = bundle.behaviors().entries().get(1);
        assertEquals(com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.Command.ActionType.FILL, reason.action());

        // 无需求关键词命中的元素标记 incomplete
        assertTrue(bundle.inventory().incompleteCount() >= 1);
    }
}
