package com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle;

import com.hsbc.cmb.hk.dbb.automation.framework.common.route.RouteEvidenceRegistry;
import com.hsbc.cmb.hk.dbb.automation.framework.common.route.RouteEvidenceSink;
import org.junit.Test;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * {@link RouteEvidenceSink} SPI 装配守卫。
 *
 * <p><b>回归背景</b>：SPI 文件曾指向已删除的 {@code lifecycle.serenity.RouteEvidenceSink}。
 * {@link RouteEvidenceRegistry} 对 ServiceLoader 的 {@code ServiceConfigurationError} 做 fail-open
 * 吞掉（只打 DEBUG）后 {@code sink=null}，于是所有 route 抓包证据（capture / monitor）被
 * <b>静默丢弃</b>：报告页“Route Captured”永远为空，日志里也无任何告警，极难定位。
 *
 * <p>本测试锁定「SPI 必须解析到现存实现类」，任何类重命名/迁移忘记同步 SPI 都会立即变红。
 */
public class RouteEvidenceSinkSpiTest {

    @Test
    public void spiResolvesToExistingImplementation() {
        RouteEvidenceSink sink = RouteEvidenceRegistry.get();
        assertNotNull("RouteEvidenceSink SPI 未解析到实现（检查 META-INF/services 指向的类是否存在）", sink);
        assertTrue("SPI 应解析到 lifecycle.bridge.RouteEvidenceSink，实际=" + sink.getClass().getName(),
                sink instanceof com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.bridge.RouteEvidenceSink);
    }
}
