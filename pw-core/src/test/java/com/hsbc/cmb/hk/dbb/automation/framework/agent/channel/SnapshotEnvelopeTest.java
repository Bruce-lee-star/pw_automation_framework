package com.hsbc.cmb.hk.dbb.automation.framework.agent.channel;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** §4.2 快照协议信封与 fail-closed 验证门禁（浏览器无关）。 */
public class SnapshotEnvelopeTest {

    @Test(expected = IllegalArgumentException.class)
    public void zeroNodesRejected() {
        new SnapshotBounds(10, 0, 10, 10);
    }

    @Test(expected = IllegalArgumentException.class)
    public void zeroBytesRejected() {
        new SnapshotBounds(10, 10, 0, 10);
    }

    @Test(expected = IllegalArgumentException.class)
    public void zeroDepthRejected() {
        new SnapshotBounds(0, 10, 10, 10);
    }

    @Test(expected = IllegalArgumentException.class)
    public void negativeTimeoutRejected() {
        new SnapshotBounds(10, 10, 10, -1);
    }

    @Test
    public void defaultBoundsArePositive() {
        SnapshotBounds b = SnapshotBounds.DEFAULT;
        assertTrue(b.maxDepth() > 0 && b.maxNodes() > 0 && b.maxBytes() > 0 && b.timeoutMillis() > 0);
    }

    @Test
    public void completeEnvelopeCarriesVersionBinding() {
        SnapshotEnvelope e = SnapshotEnvelope.complete("ctx-1", "page-1", "main", 7L, 3, 128);
        assertEquals(SnapshotEnvelope.CURRENT_SCHEMA, e.schemaVersion());
        assertEquals("ctx-1", e.contextId());
        assertEquals("page-1", e.pageId());
        assertEquals("main", e.frameId());
        assertEquals(7L, e.snapshotVersion());
        assertEquals(SnapshotEnvelope.Status.COMPLETE, e.status());
        assertNull(e.detail());
        assertEquals(3, e.nodeCount());
        assertTrue(e.isComplete());
    }

    @Test
    public void truncatedAndUnsupportedAreNotComplete() {
        SnapshotEnvelope t = SnapshotEnvelope.truncated("c", "p", "main", 1L, "MAX_NODES(2)", 2, 64);
        assertEquals(SnapshotEnvelope.Status.TRUNCATED, t.status());
        assertEquals("MAX_NODES(2)", t.detail());
        assertFalse(t.isComplete());

        SnapshotEnvelope u = SnapshotEnvelope.unsupported("c", "p", "main", 1L, "ARIA_COLLECTION_FAILED");
        assertEquals(SnapshotEnvelope.Status.UNSUPPORTED, u.status());
        assertFalse(u.isComplete());
    }

    @Test
    public void legacySnapshotWithoutEnvelopeStaysCompatible() {
        Snapshot s = new Snapshot("url", List.of());
        assertNull(s.envelope());
        assertTrue("旧式精简快照不改变既有语义", s.isComplete());
    }

    @Test
    public void verifierFailsClosedOnTruncatedSnapshotForAbsenceExpectation() {
        SnapshotEnvelope e = SnapshotEnvelope.truncated("c", "p", "main", 1L, "MAX_BYTES(8)", 0, 8);
        Snapshot s = new Snapshot("url", List.of(), e);
        SnapshotVerificationResult r = SnapshotVerifier.verify(s,
                List.of(SnapshotExpectation.forRef("role=button;name=Go").exists(false).build()));
        assertFalse("部分观测下不能据「未收录」断言元素不存在", r.allPassed());
        assertTrue(r.failures().get(0).getMessage().contains("absence not proven"));
    }

    @Test
    public void verifierFailsClosedOnUnsupportedSnapshotForPresenceExpectation() {
        SnapshotEnvelope e = SnapshotEnvelope.unsupported("c", "p", "main", 1L, "ARIA_COLLECTION_FAILED");
        Snapshot s = new Snapshot("url", List.of(), e);
        SnapshotVerificationResult r = SnapshotVerifier.verify(s,
                List.of(SnapshotExpectation.forRef("role=button;name=Go").exists(true).build()));
        assertFalse(r.allPassed());
        assertTrue(r.failures().get(0).getMessage().contains("absence not proven"));
    }

    @Test
    public void completeSnapshotKeepsLegacyAbsenceSemantics() {
        SnapshotEnvelope e = SnapshotEnvelope.complete("c", "p", "main", 1L, 0, 0);
        Snapshot s = new Snapshot("url", List.of(), e);
        SnapshotVerificationResult r = SnapshotVerifier.verify(s,
                List.of(SnapshotExpectation.forRef("role=button;name=Go").exists(false).build()));
        assertTrue("完整快照下 absent 期望仍应通过", r.allPassed());
    }
}
