package com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.event;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * 抖动闸门契约测试（{@link PageInteractionMonitor#awaitQuietSince(long, long, long)}）。
 *
 * <p>钉住三件事，防止"闸门"退化成"盲等"或"无限等"：</p>
 * <ol>
 *   <li>无抖动记录时<b>零等待</b>（正常路径不能被拖慢）；</li>
 *   <li>刚抖动过时<b>至少等满一个静默窗口</b>（否则闸门形同虚设）；</li>
 *   <li>抖动不停时<b>有硬上界</b>（绝不无限等 —— 上界到点即放行，由元素操作自身
 *       actionability 等待 + 导航丢失重试兜底）。</li>
 * </ol>
 */
public class PageInteractionMonitorChurnGateTest {

    private static final long QUIET_MS = 200L;
    private static final long MAX_WAIT_MS = 3_000L;

    @Test
    public void noRecordedChurn_returnsImmediately() {
        long waited = PageInteractionMonitor.awaitQuietSince(0L, QUIET_MS, MAX_WAIT_MS);

        assertEquals("未记录抖动时必须零等待", 0L, waited);
    }

    @Test
    public void recentChurn_waitsForTheQuietWindow() {
        long started = System.nanoTime();
        long waited = PageInteractionMonitor.awaitQuietSince(System.nanoTime(), QUIET_MS, MAX_WAIT_MS);
        long elapsedMs = (System.nanoTime() - started) / 1_000_000L;

        assertTrue("刚抖动过时必须至少等到静默窗口（实测 elapsed=" + elapsedMs + "ms）",
                elapsedMs >= QUIET_MS - 50);
        assertTrue("不得超出上界（实测 elapsed=" + elapsedMs + "ms）", elapsedMs < MAX_WAIT_MS + 500);
        assertTrue("返回值应反映实际等待（实测 waited=" + waited + "ms）", waited >= QUIET_MS - 50);
    }

    @Test
    public void ceilingIsRespected_whenChurnNeverStops() {
        long started = System.nanoTime();
        PageInteractionMonitor.awaitQuietSince(System.nanoTime(), 10_000L, 300L);
        long elapsedMs = (System.nanoTime() - started) / 1_000_000L;

        assertTrue("抖动不停时必须在 ~300ms 上界返回（实测 " + elapsedMs + "ms）", elapsedMs < 2_000L);
    }
}
