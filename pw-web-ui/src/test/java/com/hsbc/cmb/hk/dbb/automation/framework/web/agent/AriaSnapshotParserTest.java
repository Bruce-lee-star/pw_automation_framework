package com.hsbc.cmb.hk.dbb.automation.framework.web.agent;

import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.Snapshot;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.SnapshotBounds;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.SnapshotEnvelope;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/** §4.2 有界 ARIA 快照解析（纯逻辑、浏览器无关）：四道上界 + 三态 + 版本绑定。 */
public class AriaSnapshotParserTest {

    private static final String PAGE = "https://app/p";

    private static AriaSnapshotParser.Result parse(String raw, SnapshotBounds b, long version) {
        return AriaSnapshotParser.parse(raw, PAGE, "ctx-1", "page-1", "main", version, b);
    }

    private static String aria(int buttons) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < buttons; i++) {
            sb.append("- button \"B").append(i).append("\"\n");
        }
        return sb.toString();
    }

    @Test
    public void normalSnapshotIsCompleteAndBoundToVersion() {
        AriaSnapshotParser.Result r = parse(
                "- textbox \"Username\"\n- button \"Submit\" [disabled]\n- /url: https://app/p\n",
                SnapshotBounds.DEFAULT, 7L);
        assertFalse(r.truncated());
        Snapshot s = r.snapshot();
        SnapshotEnvelope e = s.envelope();
        assertNotNull(e);
        assertEquals(SnapshotEnvelope.Status.COMPLETE, e.status());
        assertEquals(7L, e.snapshotVersion());
        assertEquals("ctx-1", e.contextId());
        assertEquals("page-1", e.pageId());
        assertEquals("main", e.frameId());
        assertEquals(2, e.nodeCount());
        assertTrue(e.byteCount() > 0);
        assertTrue(s.isComplete());
        assertNotNull(s.byRef("role=textbox;name=Username"));
        assertFalse("disabled 按钮须标为不可用", s.byRef("role=button;name=Submit").enabled());
    }

    @Test
    public void nullRawIsEmptyCompleteObservation() {
        AriaSnapshotParser.Result r = parse(null, SnapshotBounds.DEFAULT, 1L);
        assertFalse(r.truncated());
        assertEquals(SnapshotEnvelope.Status.COMPLETE, r.snapshot().envelope().status());
        assertEquals(0, r.snapshot().envelope().nodeCount());
    }

    @Test
    public void maxNodesTruncatesAndReportsReason() {
        AriaSnapshotParser.Result r = parse(aria(5), new SnapshotBounds(32, 2, 100_000, 2_000), 1L);
        assertTrue(r.truncated());
        assertTrue(r.truncationReason().startsWith("MAX_NODES"));
        SnapshotEnvelope e = r.snapshot().envelope();
        assertEquals(SnapshotEnvelope.Status.TRUNCATED, e.status());
        assertEquals(2, e.nodeCount());
        assertFalse(r.snapshot().isComplete());
    }

    @Test
    public void maxBytesTruncatesAndReportsReason() {
        AriaSnapshotParser.Result r = parse(aria(20), new SnapshotBounds(32, 1000, 24, 2_000), 1L);
        assertTrue(r.truncated());
        assertTrue(r.truncationReason().startsWith("MAX_BYTES"));
        assertEquals(SnapshotEnvelope.Status.TRUNCATED, r.snapshot().envelope().status());
    }

    @Test
    public void maxDepthTruncatesAndReportsReason() {
        // 缩进 6 空格 → 深度 4；上界 2 即触顶
        String raw = "- list \"L\"\n  - listitem \"I\"\n      - button \"Deep\"\n";
        AriaSnapshotParser.Result r = parse(raw, new SnapshotBounds(2, 1000, 100_000, 2_000), 1L);
        assertTrue(r.truncated());
        assertTrue(r.truncationReason().startsWith("MAX_DEPTH"));
        assertEquals(SnapshotEnvelope.Status.TRUNCATED, r.snapshot().envelope().status());
    }

    @Test
    public void timeoutTruncatesAndReportsReason() {
        // 注入时钟：第 1 次取 deadline 基准，第 2 次即跨过时限 → 确定性触发，不依赖机器速度
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        java.util.function.LongSupplier clock = () -> calls.getAndIncrement() == 0 ? 0L : 2_000_000L;
        AriaSnapshotParser.Result r = AriaSnapshotParser.parse(
                aria(3), PAGE, "ctx-1", "page-1", "main", 1L,
                new SnapshotBounds(32, 1000, 100_000, 1), clock);
        assertTrue("时限上界须产生 TRUNCATED 而非静默完整", r.truncated());
        assertEquals("TIMEOUT(1ms)", r.truncationReason());
        assertEquals(SnapshotEnvelope.Status.TRUNCATED, r.snapshot().envelope().status());
        assertEquals(0, r.snapshot().envelope().nodeCount());
    }

    @Test
    public void versionsAreDistinctPerParse() {
        Snapshot a = parse(aria(1), SnapshotBounds.DEFAULT, 1L).snapshot();
        Snapshot b = parse(aria(1), SnapshotBounds.DEFAULT, 2L).snapshot();
        assertEquals(1L, a.envelope().snapshotVersion());
        assertEquals(2L, b.envelope().snapshotVersion());
    }
}
