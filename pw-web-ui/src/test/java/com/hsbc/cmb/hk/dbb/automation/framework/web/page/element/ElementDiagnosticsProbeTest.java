package com.hsbc.cmb.hk.dbb.automation.framework.web.page.element;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 诊断探测的两条不变式（2026-10-10）。
 *
 * <ol>
 *   <li><b>绝不抛</b>：诊断发生在主异常抛出之前，任何探测异常都必须降级为 {@code false}
 *       （否则"诊断失败"会盖掉"真实失败原因"）；</li>
 *   <li><b>可计时</b>：采集时长可度量（{@code [diagnostic] collect() for [...] took Xms}），
 *       便于核对"诊断没有引入长等待"。</li>
 * </ol>
 */
public class ElementDiagnosticsProbeTest {

    @Test
    public void probe_neverThrows_failuresDegradeToFalse() {
        assertTrue(ElementDiagnosticsCollector.probe(() -> true));
        assertFalse(ElementDiagnosticsCollector.probe(() -> false));
        assertFalse("原生探测抛异常时必须降级为 false，绝不冒泡",
                ElementDiagnosticsCollector.probe(() -> {
                    throw new RuntimeException("boom");
                }));
    }

    @Test
    public void elapsedMs_measuresFromGivenStart() {
        // 5ms 之前开始 ⇒ 至少已过去 4ms（不 sleep，避免 ArchUnit 的 Thread.sleep 规则）
        assertTrue(ElementDiagnosticsCollector.elapsedMs(System.nanoTime() - 5_000_000L) >= 4L);
        assertTrue(ElementDiagnosticsCollector.elapsedMs(System.nanoTime()) >= 0L);
    }

}
