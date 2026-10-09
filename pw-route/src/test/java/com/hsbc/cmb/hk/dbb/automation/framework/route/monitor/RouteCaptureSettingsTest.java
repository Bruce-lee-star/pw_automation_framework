package com.hsbc.cmb.hk.dbb.automation.framework.route.monitor;

import com.hsbc.cmb.hk.dbb.automation.framework.common.config.ConfigKeys;
import com.hsbc.cmb.hk.dbb.automation.framework.route.RouteConfig;
import org.junit.After;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * {@link RouteCaptureSettings} 的解析优先级 / 空值语义 / 数值兜底契约。
 *
 * <p><b>覆盖范围</b>：②系统属性层与④默认值层；①环境变量层无法在进程内设置（与
 * {@code FrameworkFlags} 的既有做法一致，未纳入单测），③配置文件 SPI 层在 pw-route 单测
 * classpath 上无实现（恒为空列表），故本类不覆盖 —— 三者顺序由 {@code resolve} 的阅读即知。
 *
 * <p><b>JVM 全局性</b>：System 属性为进程级共享，本类独占这三个键的读写（{@link #tearDown}
 * 写回默认值，不用 clearProperty），避免与 {@code CaptureSinkTest} 的同类用例互相干扰。
 */
public class RouteCaptureSettingsTest {

    @After
    public void tearDown() {
        System.setProperty("route.capture.all.type", "false");
        System.setProperty("route.capture.inline.max.bytes", "262144");
        System.setProperty("route.capture.gzip.max.bytes", "8388608");
    }

    @Test
    public void blankLayersFallBackToRouteConfigDefaults() {
        // ②层空白 ⇒ 未命中；③层空列表 ⇒ 未命中 ⇒ 落到④ RouteConfig 默认值
        System.setProperty("route.capture.all.type", "   ");
        assertFalse("空白系统属性视为未命中，应回落默认 false", RouteCaptureSettings.allTypes());
        assertEquals(RouteConfig.DEFAULT_CAPTURE_INLINE_MAX_BYTES, RouteCaptureSettings.inlineMaxBytes());
        assertEquals(RouteConfig.DEFAULT_CAPTURE_GZIP_MAX_BYTES, RouteCaptureSettings.gzipMaxBytes());
    }

    @Test
    public void systemPropertyLayerOverridesDefault() {
        System.setProperty("route.capture.all.type", "true");
        assertTrue("系统属性层应覆盖默认值", RouteCaptureSettings.allTypes());
    }

    @Test
    public void integerKeysReadFromSystemPropertyLayer() {
        System.setProperty("route.capture.inline.max.bytes", "1024");
        System.setProperty("route.capture.gzip.max.bytes", "2048");
        assertEquals(1024, RouteCaptureSettings.inlineMaxBytes());
        assertEquals(2048, RouteCaptureSettings.gzipMaxBytes());
    }

    @Test
    public void nonPositiveOrIllegalIntFallsBackToDefault() {
        System.setProperty("route.capture.inline.max.bytes", "0");
        assertEquals(RouteConfig.DEFAULT_CAPTURE_INLINE_MAX_BYTES, RouteCaptureSettings.inlineMaxBytes());
        System.setProperty("route.capture.inline.max.bytes", "-1");
        assertEquals(RouteConfig.DEFAULT_CAPTURE_INLINE_MAX_BYTES, RouteCaptureSettings.inlineMaxBytes());
        System.setProperty("route.capture.inline.max.bytes", "abc");
        assertEquals(RouteConfig.DEFAULT_CAPTURE_INLINE_MAX_BYTES, RouteCaptureSettings.inlineMaxBytes());
        System.setProperty("route.capture.inline.max.bytes", "   ");
        assertEquals(RouteConfig.DEFAULT_CAPTURE_INLINE_MAX_BYTES, RouteCaptureSettings.inlineMaxBytes());
    }

    @Test
    public void configKeyDefaultsMatchRouteConfigFallbackConstants() {
        // 防漂移：注册处默认值必须与 RouteConfig 兜底常量同值（两处任一改动都会在此暴露）
        assertEquals(String.valueOf(RouteConfig.DEFAULT_CAPTURE_ALL_TYPES),
                ConfigKeys.ROUTE_CAPTURE_ALL_TYPE.defaultValue());
        assertEquals(String.valueOf(RouteConfig.DEFAULT_CAPTURE_INLINE_MAX_BYTES),
                ConfigKeys.ROUTE_CAPTURE_INLINE_MAX_BYTES.defaultValue());
        assertEquals(String.valueOf(RouteConfig.DEFAULT_CAPTURE_GZIP_MAX_BYTES),
                ConfigKeys.ROUTE_CAPTURE_GZIP_MAX_BYTES.defaultValue());
        assertEquals(RouteConfig.DEFAULT_CAPTURE_INLINE_MAX_BYTES,
                Integer.parseInt(ConfigKeys.ROUTE_CAPTURE_INLINE_MAX_BYTES.defaultValue()));
        assertEquals(RouteConfig.DEFAULT_CAPTURE_GZIP_MAX_BYTES,
                Integer.parseInt(ConfigKeys.ROUTE_CAPTURE_GZIP_MAX_BYTES.defaultValue()));
        // 第②层空白 ⇒ ④ 必须与注册表默认值一致（三级链的最后一级同源）
        System.setProperty("route.capture.gzip.max.bytes", "   ");
        assertEquals(RouteConfig.DEFAULT_CAPTURE_GZIP_MAX_BYTES, RouteCaptureSettings.gzipMaxBytes());
    }
}
