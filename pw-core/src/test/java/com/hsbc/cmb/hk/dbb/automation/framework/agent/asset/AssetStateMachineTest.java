package com.hsbc.cmb.hk.dbb.automation.framework.agent.asset;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** 资产状态机迁移合法性（设计 §4.6 状态机）。 */
public class AssetStateMachineTest {

    private final AssetStateMachine sm = new AssetStateMachine.DefaultAssetStateMachine();

    @Test
    public void happyPathDraftToActive() {
        assertEquals(AssetState.PENDING_REVIEW, sm.transition(AssetState.DRAFT, AssetStateMachine.Event.SUBMIT));
        assertEquals(AssetState.APPROVED, sm.transition(AssetState.PENDING_REVIEW, AssetStateMachine.Event.APPROVE));
        assertEquals(AssetState.ACTIVE, sm.transition(AssetState.APPROVED, AssetStateMachine.Event.ACTIVATE));
    }

    @Test
    public void deprecateFromAnyActiveState() {
        assertEquals(AssetState.DEPRECATED, sm.transition(AssetState.ACTIVE, AssetStateMachine.Event.DEPRECATE));
        assertEquals(AssetState.DEPRECATED, sm.transition(AssetState.PENDING_REVIEW, AssetStateMachine.Event.DEPRECATE));
    }

    @Test
    public void returnForReviewAllowed() {
        assertEquals(AssetState.PENDING_REVIEW, sm.transition(AssetState.APPROVED, AssetStateMachine.Event.RETURN_FOR_REVIEW));
    }

    @Test
    public void deprecatedIsTerminal() {
        assertFalse(sm.canTransition(AssetState.DEPRECATED, AssetStateMachine.Event.ACTIVATE));
        assertFalse(sm.canTransition(AssetState.DEPRECATED, AssetStateMachine.Event.DEPRECATE));
    }

    @Test
    public void illegalTransitionThrows() {
        assertFalse(sm.canTransition(AssetState.DRAFT, AssetStateMachine.Event.ACTIVATE));
        try {
            sm.transition(AssetState.DRAFT, AssetStateMachine.Event.ACTIVATE);
            fail("expected IllegalStateException");
        } catch (IllegalStateException e) {
            assertTrue(e.getMessage().contains("Illegal"));
        }
    }
}
