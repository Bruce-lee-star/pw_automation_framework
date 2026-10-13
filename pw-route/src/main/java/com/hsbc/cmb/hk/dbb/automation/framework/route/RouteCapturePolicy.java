package com.hsbc.cmb.hk.dbb.automation.framework.route;

import com.hsbc.cmb.hk.dbb.automation.framework.common.config.ConfigKeys;
import com.hsbc.cmb.hk.dbb.automation.framework.common.config.ConfigSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * 路由采集策略（<b>不可变值对象</b>）—— 三项采集配置的<b>唯一解析点与冻结快照</b>。
 *
 * <p>层序不在本类硬编码：由 {@link ConfigKeys} 对 {@code route.capture.*} 三键的**声明式层序**
 * （{@link ConfigSource#ENV_FIRST_PRECEDENCE}：环境变量 → 系统属性 → 配置文件属性 → 默认值）
 * 决定，逐层回退、透明解密、来源层上报全部复用 {@link ConfigSource} —— 框架内只有一套解析实现。
 *
 * <p><b>生命周期</b>：由装配方（{@code RouteRuntimeImpl}）在构造 sink 时 {@link #resolveNow()}
 * 解析一次并<b>冻结</b>。请求侧与响应侧两个采集闸门共用同一快照，杜绝"同一次 exchange 两次判定
 * 看到不同配置代"的口径漂移；运行期改配置需新建 runtime/context 才生效（不做热刷新，
 * 热切换会重新引入跨代窗口）。
 *
 * <p>空白语义：空白值视为「未命中」继续下一层，避免空白的系统属性<b>遮蔽配置文件层</b>。
 */
public final class RouteCapturePolicy {

    private static final Logger LOGGER = LoggerFactory.getLogger(RouteCapturePolicy.class);

    private final boolean allTypes;
    private final int inlineMaxBytes;
    private final int gzipMaxBytes;
    /** MONITOR 响应体断言上限（字节）：超限的 body 不再整体物化解析，降级为仅 status/headers 断言。 */
    private final int assertBodyMaxBytes;
    /** 四项生效值的来源层（可观测/诊断；不含敏感值语义，均为 boolean/int）。 */
    private final List<ConfigSource.Resolved> provenance;

    private RouteCapturePolicy(boolean allTypes, int inlineMaxBytes, int gzipMaxBytes,
                               int assertBodyMaxBytes, List<ConfigSource.Resolved> provenance) {
        this.allTypes = allTypes;
        this.inlineMaxBytes = inlineMaxBytes;
        this.gzipMaxBytes = gzipMaxBytes;
        this.assertBodyMaxBytes = assertBodyMaxBytes;
        this.provenance = provenance;
    }

    /** 是否采集 MONITOR 之外的 DELAY / MODIFY / MOCK（显式 {@code .capture()} 始终强制采集）。 */
    public boolean allTypes() {
        return allTypes;
    }

    /** 响应体内联阈值（字节）：不超过即原字节持内存。 */
    public int inlineMaxBytes() {
        return inlineMaxBytes;
    }

    /** 响应体文本型 gzip 上限（字节）：不超过即 gzip 持内存，否则落盘临时文件。 */
    public int gzipMaxBytes() {
        return gzipMaxBytes;
    }

    /** MONITOR 响应体断言上限（字节）：超限的 body 不再整体物化解析（降级仅 status/headers，inconclusive）。 */
    public int assertBodyMaxBytes() {
        return assertBodyMaxBytes;
    }

    /** 四项生效值的来源层（键 + 生效值 + 层），供诊断/日志（返回不可变快照）。 */
    public List<ConfigSource.Resolved> provenance() {
        return List.copyOf(provenance);
    }

    /**
     * 唯一解析点：按登记键的声明层序解析三项并冻结为不可变策略。
     *
     * @return 冻结后的采集策略
     */
    public static RouteCapturePolicy resolveNow() {
        ConfigSource.Resolved allTypes = resolve(ConfigKeys.ROUTE_CAPTURE_ALL_TYPE.key(),
                Boolean.toString(RouteConfig.DEFAULT_CAPTURE_ALL_TYPES));
        ConfigSource.Resolved inline = resolve(ConfigKeys.ROUTE_CAPTURE_INLINE_MAX_BYTES.key(),
                Integer.toString(RouteConfig.DEFAULT_CAPTURE_INLINE_MAX_BYTES));
        ConfigSource.Resolved gzip = resolve(ConfigKeys.ROUTE_CAPTURE_GZIP_MAX_BYTES.key(),
                Integer.toString(RouteConfig.DEFAULT_CAPTURE_GZIP_MAX_BYTES));
        ConfigSource.Resolved assertBody = resolve(ConfigKeys.ROUTE_MONITOR_ASSERT_BODY_MAX_BYTES.key(),
                Integer.toString(RouteConfig.DEFAULT_ASSERT_BODY_MAX_BYTES));
        RouteCapturePolicy policy = new RouteCapturePolicy(
                Boolean.parseBoolean(allTypes.value()),
                positiveInt(inline, RouteConfig.DEFAULT_CAPTURE_INLINE_MAX_BYTES),
                positiveInt(gzip, RouteConfig.DEFAULT_CAPTURE_GZIP_MAX_BYTES),
                positiveInt(assertBody, RouteConfig.DEFAULT_ASSERT_BODY_MAX_BYTES),
                List.of(allTypes, inline, gzip, assertBody));
        LOGGER.info("[Route] capture policy frozen: allTypes={} (layer={}), inlineMaxBytes={} (layer={}),"
                        + " gzipMaxBytes={} (layer={}), assertBodyMaxBytes={} (layer={})",
                policy.allTypes, allTypes.layer(), policy.inlineMaxBytes, inline.layer(),
                policy.gzipMaxBytes, gzip.layer(), policy.assertBodyMaxBytes, assertBody.layer());
        return policy;
    }

    /** 显式「空白视为未命中」的解析（不遮蔽配置文件层）。 */
    private static ConfigSource.Resolved resolve(String key, String defaultValue) {
        return ConfigSource.resolveWithLayer(key, defaultValue,
                ConfigSource.precedenceFor(key), true);
    }

    /** 正整数：命中但 ≤0 / 非法 ⇒ WARN 并回落该键默认值（配置错误不让采集失败）。 */
    private static int positiveInt(ConfigSource.Resolved resolved, int fallback) {
        String raw = resolved.value();
        if (raw == null) {
            return fallback;
        }
        try {
            int parsed = Integer.parseInt(raw.trim());
            if (parsed > 0) {
                return parsed;
            }
            LOGGER.warn("[Route] capture config invalid: {}={} (layer={}) is not > 0 -> fallback {}",
                    resolved.key(), raw, resolved.layer(), fallback);
            return fallback;
        } catch (NumberFormatException e) {
            LOGGER.warn("[Route] capture config invalid: {}={} (layer={}) is not an integer -> fallback {}",
                    resolved.key(), raw, resolved.layer(), fallback);
            return fallback;
        }
    }
}
