package com.hsbc.cmb.hk.dbb.automation.framework.agent.channel;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/** T6/I-05：快照树 + 稳定结构指纹（snapshotDigest）。 */
public class SnapshotDigestTest {

    private static Snapshot.ElementState node(String ref, int depth, String frameId,
                                             List<Snapshot.ElementState> children) {
        return new Snapshot.ElementState(ref, "button", ref, true, true, "",
                depth, frameId, children, false, null);
    }

    @Test
    public void samePageTwoSnapshotsShareDigestIgnoringEnvelopeMetadata() {
        Snapshot.ElementState child = node("role=button;name=B", 2, "main", List.of());
        Snapshot.ElementState root = node("role=button;name=A", 1, "main", List.of(child));
        Snapshot s1 = new Snapshot("https://app/p",
                List.of(root), SnapshotEnvelope.complete("c1", "p1", "main", 7L, 2, 100));
        // 同页面、同结构，但版本/采集时间/字节不同 → 指纹必须相同（排除时间戳/序号）
        Snapshot s2 = new Snapshot("https://app/p",
                List.of(root), SnapshotEnvelope.complete("c1", "p1", "main", 99L, 2, 999));
        assertEquals("同页两次快照指纹应一致", s1.snapshotDigest(), s2.snapshotDigest());
        assertNotNull(s1.snapshotDigest());
        assertEquals(64, s1.snapshotDigest().length()); // SHA-256 十六进制
    }

    @Test
    public void changingOneAttributeChangesDigest() {
        Snapshot.ElementState child = node("role=button;name=B", 2, "main", List.of());
        Snapshot.ElementState root = node("role=button;name=A", 1, "main", List.of(child));
        Snapshot.ElementState rootChanged = node("role=button;name=A-x", 1, "main", List.of(child));
        Snapshot s1 = new Snapshot("https://app/p", List.of(root), null);
        Snapshot s2 = new Snapshot("https://app/p", List.of(rootChanged), null);
        assertNotEquals("改一个属性后指纹必须变化", s1.snapshotDigest(), s2.snapshotDigest());
    }

    @Test
    public void perNodeFrameIdAffectsDigest() {
        Snapshot.ElementState a = node("role=button;name=A", 1, "main", List.of());
        Snapshot.ElementState b = node("role=button;name=A", 1, "frame-2", List.of());
        Snapshot s1 = new Snapshot("https://app/p", List.of(a), null);
        Snapshot s2 = new Snapshot("https://app/p", List.of(b), null);
        assertNotEquals("per-node frameId 不同应使指纹不同", s1.snapshotDigest(), s2.snapshotDigest());
    }

    @Test
    public void boundedTruncationYieldsDifferentNonNilDigest() {
        // 完整树（含深层节点）
        Snapshot.ElementState deep = node("role=button;name=Deep", 2, "main", List.of());
        Snapshot.ElementState root = node("role=button;name=A", 1, "main", List.of(deep));
        Snapshot complete = new Snapshot("https://app/p", List.of(root), null);
        // 截断树（仅根，深层未收录）—— 模拟 MAX_DEPTH 触顶
        Snapshot truncated = new Snapshot("https://app/p", List.of(
                node("role=button;name=A", 1, "main", List.of())), null);
        assertNotNull(complete.snapshotDigest());
        assertNotNull(truncated.snapshotDigest());
        assertNotEquals("截断（缺子树）与完整指纹必须不同", complete.snapshotDigest(), truncated.snapshotDigest());
    }

    @Test
    public void treeStructureIsExposedAndByRefIncludesDescendants() {
        Snapshot.ElementState child = node("role=button;name=B", 2, "main", List.of());
        Snapshot.ElementState root = node("role=button;name=A", 1, "main", List.of(child));
        Snapshot s = new Snapshot("https://app/p", List.of(root), null);
        assertEquals(1, s.roots().size());
        assertEquals(2, s.elements().size()); // 根 + 后代
        assertEquals(2, s.byRef("role=button;name=B").depth());
        assertEquals(1, s.byRef("role=button;name=A").depth());
        assertTrue("根应携带子节点", s.byRef("role=button;name=A").children().size() == 1);
        assertFalse(s.byRef("role=button;name=B").unsupported());
    }

    @Test
    public void legacyFlatListStillFlattensIntoByRef() {
        // 旧式平铺构造（无 children）向后兼容
        Snapshot.ElementState a = new Snapshot.ElementState("role=button;name=A", "button", "A", true, true, "");
        Snapshot.ElementState b = new Snapshot.ElementState("role=button;name=B", "button", "B", true, true, "");
        Snapshot s = new Snapshot("https://app/p", List.of(a, b), null);
        assertEquals(0, s.roots().get(0).depth());
        assertEquals(2, s.elements().size());
        assertNotNull(s.snapshotDigest());
    }
}
