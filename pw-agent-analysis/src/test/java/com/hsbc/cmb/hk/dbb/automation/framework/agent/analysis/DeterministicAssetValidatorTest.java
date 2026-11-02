package com.hsbc.cmb.hk.dbb.automation.framework.agent.analysis;

import com.hsbc.cmb.hk.dbb.automation.framework.agent.analysis.engine.AssetDraftBundle;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.analysis.engine.BehaviorEntry;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.analysis.engine.BehaviorModel;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.analysis.validation.DeterministicAssetValidator;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.analysis.engine.ElementInventory;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.analysis.engine.ElementInventoryEntry;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.analysis.engine.RuleBasedUnderstandingEngine;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.analysis.observation.ObservedElement;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.analysis.observation.PageObservation;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.analysis.requirement.RequirementContext;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.analysis.requirement.RequirementEntry;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.analysis.requirement.RequirementId;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.analysis.validation.AssetValidator;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.analysis.validation.ValidationReport;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.Command;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.LocatorSpec;
import org.junit.Test;

import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** 确定性校验器：定位候选完整性/唯一性 + 联动目标存在性（设计 §4.6 ④）。 */
public class DeterministicAssetValidatorTest {

    private static ObservedElement elem(String role, String name) {
        return new ObservedElement(role, name, name, true, true,
                List.of(LocatorSpec.role(role, name)), Map.of());
    }

    private static AssetDraftBundle goodBundle() {
        PageObservation obs = new PageObservation("u", "p",
                List.of(elem("checkbox", "agree"), elem("textbox", "reason")), "m");
        RequirementEntry req = new RequirementEntry(new RequirementId("REQ-1"), "t", List.of(), List.of(), "f");
        return new RuleBasedUnderstandingEngine().analyze(new RequirementContext(List.of(req), "f"), obs);
    }

    @Test
    public void validBundlePasses() {
        AssetValidator v = new DeterministicAssetValidator();
        ValidationReport r = v.validate(goodBundle());
        assertTrue(r.passed());
    }

    @Test
    public void duplicateLocatorFails() {
        // 两个元素共享同一 role+name → 主定位符重复 → ERROR
        ObservedElement a = elem("textbox", "reason");
        ObservedElement b = elem("textbox", "reason");
        PageObservation obs = new PageObservation("u", "p", List.of(a, b), "m");
        RequirementEntry req = new RequirementEntry(new RequirementId("REQ-1"), "t", List.of(), List.of(), "f");
        AssetDraftBundle bundle = new RuleBasedUnderstandingEngine().analyze(new RequirementContext(List.of(req), "f"), obs);

        ValidationReport r = new DeterministicAssetValidator().validate(bundle);
        assertFalse(r.passed());
        assertTrue(r.issues().stream().anyMatch(i -> i.severity() == ValidationReport.Issue.Severity.ERROR
                && i.message().contains("Duplicate")));
    }

    @Test
    public void incompleteMappingIsWarningNotError() {
        // 无需求上下文 → 全部 incomplete（warning），仍通过
        PageObservation obs = new PageObservation("u", "p", List.of(elem("textbox", "reason")), "m");
        AssetDraftBundle bundle = new RuleBasedUnderstandingEngine()
                .analyze(new RequirementContext(List.of(), "f"), obs);
        ValidationReport r = new DeterministicAssetValidator().validate(bundle);
        assertTrue(r.passed());
        assertTrue(r.issues().stream().anyMatch(i -> i.severity() == ValidationReport.Issue.Severity.WARNING));
    }
}
