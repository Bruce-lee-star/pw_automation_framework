package com.hsbc.cmb.hk.dbb.automation.framework.route;

import com.hsbc.cmb.hk.dbb.automation.framework.common.config.ConfigKeys;
import com.hsbc.cmb.hk.dbb.automation.framework.common.config.ConfigSource;
import org.junit.After;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * {@link RouteCapturePolicy} 的冻结快照 / 层序来源 / 数值兜底 / 防漂移契约。
 *
 * <p>route 三键声明层序为 {@link ConfigSource#ENV_FIRST_PRECEDENCE}：①环境变量层无法在进程内
 * 设置（与 {@code ConfigSource} 既有做法一致，不纳入单测），故本类覆盖 ②系统属性层、③配置文件层
 * （pw-route 单测 classpath 上无 {@code ConfigResolver} 实现 ⇒ 恒为空）与 ④默认值层。
 *
 * <p><b>JVM 全局性</b>：System 属性为进程级共享，本类独占这三个键的读写
 * （{@link #tearDown} 写回默认值，不用 clearProperty）。
 */
public class RouteCapturePolicyTest {

    @After
    public void tearDown() {
        System.setProperty("route.capture.all.type", "false");
        System.setProperty("route.capture.inline.max.bytes", "262144");
        System.setProperty("route.capture.gzip.max.bytes", "8388608");
    }

    @Test
    public void blankLayersFallBackToRouteConfigDefaults() {
        // ②层空白 ⇒ 未命中（RouteCapturePolicy 显式 blankFallsThrough）；③层无实现 ⇒ 落到④默认值
        System.setProperty("route.capture.all.type", "   ");
        RouteCapturePolicy policy = RouteCapturePolicy.resolveNow();
        assertFalse("空白系统属性视为未命中，应回落默认 false", policy.allTypes());
        assertEquals(RouteConfig.DEFAULT_CAPTURE_INLINE_MAX_BYTES, policy.inlineMaxBytes());
        assertEquals(RouteConfig.DEFAULT_CAPTURE_GZIP_MAX_BYTES, policy.gzipMaxBytes());
        assertEquals(ConfigSource.Layer.DEFAULT, layerOf(policy, "route.capture.all.type"));
    }

    @Test
    public void systemPropertyLayerOverridesDefault() {
        System.setProperty("route.capture.all.type", "true");
        RouteCapturePolicy policy = RouteCapturePolicy.resolveNow();
        assertTrue("系统属性层应覆盖默认值", policy.allTypes());
        assertEquals("来源层应为系统属性", ConfigSource.Layer.SYSTEM_PROPERTY,
                layerOf(policy, "route.capture.all.type"));
    }

    @Test
    public void integerKeysReadFromSystemPropertyLayer() {
        System.setProperty("route.capture.inline.max.bytes", "1024");
        System.setProperty("route.capture.gzip.max.bytes", "2048");
        RouteCapturePolicy policy = RouteCapturePolicy.resolveNow();
        assertEquals(1024, policy.inlineMaxBytes());
        assertEquals(2048, policy.gzipMaxBytes());
    }

    @Test
    public void nonPositiveOrIllegalIntFallsBackToDefault() {
        System.setProperty("route.capture.inline.max.bytes", "0");
        assertEquals(RouteConfig.DEFAULT_CAPTURE_INLINE_MAX_BYTES,
                RouteCapturePolicy.resolveNow().inlineMaxBytes());
        System.setProperty("route.capture.inline.max.bytes", "-1");
        assertEquals(RouteConfig.DEFAULT_CAPTURE_INLINE_MAX_BYTES,
                RouteCapturePolicy.resolveNow().inlineMaxBytes());
        System.setProperty("route.capture.inline.max.bytes", "abc");
        assertEquals(RouteConfig.DEFAULT_CAPTURE_INLINE_MAX_BYTES,
                RouteCapturePolicy.resolveNow().inlineMaxBytes());
    }

    @Test
    public void frozenSnapshotIgnoresLaterPropertyChange() {
        System.setProperty("route.capture.all.type", "true");
        RouteCapturePolicy frozen = RouteCapturePolicy.resolveNow();
        assertTrue(frozen.allTypes());
        // 闸门口径一致性：冻结后改配置不影响既有快照（需新建 runtime 才生效）
        System.setProperty("route.capture.all.type", "false");
        assertTrue("冻结快照在配置变更后必须保持不变", frozen.allTypes());
        assertFalse("新建策略才读取新值", RouteCapturePolicy.resolveNow().allTypes());
    }

    @Test
    public void routeKeysDeclareEnvFirstPrecedence() {
        // 声明式层序：只有 route 三键为 ENV 优先，其余键保持框架默认（语义零变化）
        assertEquals(ConfigSource.ENV_FIRST_PRECEDENCE,
                ConfigSource.precedenceFor(ConfigKeys.ROUTE_CAPTURE_ALL_TYPE.key()));
        assertEquals(ConfigSource.ENV_FIRST_PRECEDENCE,
                ConfigSource.precedenceFor(ConfigKeys.ROUTE_CAPTURE_INLINE_MAX_BYTES.key()));
        assertEquals(ConfigSource.ENV_FIRST_PRECEDENCE,
                ConfigSource.precedenceFor(ConfigKeys.ROUTE_CAPTURE_GZIP_MAX_BYTES.key()));
        assertEquals(ConfigSource.DEFAULT_PRECEDENCE,
                ConfigSource.precedenceFor(ConfigKeys.MONITOR_OBSERVE_THREADS.key()));
        assertEquals("未登记键走框架默认层序", ConfigSource.DEFAULT_PRECEDENCE,
                ConfigSource.precedenceFor("route.capture.not.registered"));
    }

    @Test
    public void configKeyDefaultsMatchRouteConfigFallbackConstants() {
        // 防漂移：注册处默认值必须与 RouteConfig 兜底常量同值（两处任一改动都会在此暴露）
        assertEquals(String.valueOf(RouteConfig.DEFAULT_CAPTURE_ALL_TYPES),
                ConfigKeys.ROUTE_CAPTURE_ALL_TYPE.defaultValue());
        assertEquals(RouteConfig.DEFAULT_CAPTURE_INLINE_MAX_BYTES,
                Integer.parseInt(ConfigKeys.ROUTE_CAPTURE_INLINE_MAX_BYTES.defaultValue()));
        assertEquals(RouteConfig.DEFAULT_CAPTURE_GZIP_MAX_BYTES,
                Integer.parseInt(ConfigKeys.ROUTE_CAPTURE_GZIP_MAX_BYTES.defaultValue()));
    }

    /** 取某项生效值的来源层（诊断用 provenance）。 */
    private static ConfigSource.Layer layerOf(RouteCapturePolicy policy, String key) {
        return policy.provenance().stream()
                .filter(r -> key.equals(r.key()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("provenance missing key: " + key))
                .layer();
    }
}
