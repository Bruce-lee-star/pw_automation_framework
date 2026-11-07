package com.hsbc.cmb.hk.dbb.automation.framework.agent.impact;

import com.hsbc.cmb.hk.dbb.automation.framework.agent.intent.RecordedSkeleton;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.intent.RecordedStep;
import org.junit.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * {@link ChangeSignal} 契约测试：骨架派生时保留 role、元素名集合派生一致、role 缺失时退化。
 */
public class ChangeSignalTest {

    private static final String PAGE_REF = "https://www.example.com/logon";

    @Test
    public void fromSkeletonKeepsRoleAndName() {
        ChangeSignal signal = ChangeSignal.fromSkeleton(skeleton(
                new RecordedStep("FILL", "role=textbox;name=Username", Map.of("value", "alice")),
                new RecordedStep("CLICK", "role=button;name=Submit", Map.of())));

        assertEquals(Set.of("username", "submit"), signal.elements());
        assertEquals(Set.of(new ElementRef("textbox", "Username"),
                new ElementRef("button", "Submit")), signal.elementRefs());
        assertTrue(signal.describe(), signal.describe().contains("textbox:username"));
        assertEquals(Set.of("/logon"), signal.pages());
        assertEquals(Set.of("logon"), signal.keywords());
    }

    @Test
    public void skeletonWithoutRoleFallsBackToNameOnly() {
        ChangeSignal signal = ChangeSignal.fromSkeleton(skeleton(
                new RecordedStep("CLICK", "name=Submit", Map.of())));

        assertEquals(1, signal.elementRefs().size());
        ElementRef ref = signal.elementRefs().iterator().next();
        assertFalse(ref.hasRole());
        assertEquals("submit", ref.name());
    }

    @Test
    public void explicitNamesHaveNoRoleButStillPopulateElements() {
        ChangeSignal signal = ChangeSignal.of(Set.of(), Set.of("Submit"), Set.of(), Set.of());

        assertEquals(Set.of("submit"), signal.elements());
        assertFalse(signal.elementRefs().iterator().next().hasRole());
    }

    @Test
    public void emptySignalIsReportedAsEmpty() {
        assertTrue(ChangeSignal.of(Set.of(), Set.of(), Set.of(), Set.of()).isEmpty());
        assertFalse(ChangeSignal.of(Set.of(), Set.of("next"), Set.of(), Set.of()).isEmpty());
    }

    private static RecordedSkeleton skeleton(RecordedStep... steps) {
        return new RecordedSkeleton(RecordedSkeleton.SUPPORTED_SCHEMA_VERSION, "codegen", PAGE_REF,
                List.of(steps));
    }
}
