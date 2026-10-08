package com.hsbc.cmb.hk.dbb.automation.framework.common.route;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * API 采集记录（只读视图）。
 *
 * <p>本类型定义在 pw-core，作为"业务读取 API 采集信息"的跨模块载体：pw-route 负责把自身的
 * {@code CapturedApiCall} 转换成它，pw-web-ui 只依赖本类型（pw-web-ui 不依赖 pw-route，
 * 故 SPI 不能直接暴露 pw-route 的 DTO）。
 *
 * <p>{@code channels} 标注该记录是哪个渠道产生的：{@code MOCK} / {@code MODIFY} / {@code MONITOR} /
 * {@code DELAY}（同一规则可命中多个渠道，如 {@code .mock().delay()}）。
 *
 * @param pattern              命中的 URL pattern
 * @param method               HTTP 方法
 * @param url                  实际请求 URL
 * @param requestHeaders       请求头（只读视图）
 * @param requestBodyPreview   请求体预览（可能截断）
 * @param responseStatus       响应状态码；响应未到达时为 null
 * @param responseHeaders      响应头（只读视图）
 * @param responseBody         响应体（仅在开启 captureBody 时读取，可能截断）
 * @param responseBodyTruncated 响应体是否被截断
 * @param durationMs           耗时（毫秒）
 * @param timedOut             是否超时未定案
 * @param recordedAtMillis     记录时刻（epoch millis）
 * @param channels             产生该记录的渠道集合
 */
public record CapturedApiCallView(
        String pattern,
        String method,
        String url,
        Map<String, String> requestHeaders,
        String requestBodyPreview,
        Integer responseStatus,
        Map<String, String> responseHeaders,
        String responseBody,
        boolean responseBodyTruncated,
        long durationMs,
        boolean timedOut,
        long recordedAtMillis,
        Set<String> channels) {

    /**
     * 紧凑构造：对可变集合做防御性拷贝并包成不可修改视图。
     *
     * <p>必要性：本类型是跨模块传递的只读快照，若直接持有/返回调用方传入的 {@code Map}/{@code Set}，
     * 调用方后续修改会反噬快照，静态分析（SpotBugs EI_EXPOSE_REP / EI_EXPOSE_REP2）也会直接拦下构建。
     * 用 {@code Collections.unmodifiable*} 而非 {@code Map.copyOf}/{@code Set.copyOf}：后者遇到
     * null 键值会抛 NPE，而响应头等字段允许 null 值。
     */
    public CapturedApiCallView {
        requestHeaders = requestHeaders == null ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(requestHeaders));
        responseHeaders = responseHeaders == null ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(responseHeaders));
        channels = channels == null ? Set.of()
                : Collections.unmodifiableSet(new LinkedHashSet<>(channels));
    }

    /**
     * 访问器一律返回不可修改视图。
     *
     * <p>紧凑构造已做防御性拷贝，但字段静态类型仍是可变集合，静态分析（SpotBugs EI_EXPOSE_REP）
     * 会判定"直接返回可变类型字段"为暴露内部表示，故三个集合访问器显式包一层 unmodifiable。
     */
    @Override
    public Map<String, String> requestHeaders() {
        return Collections.unmodifiableMap(requestHeaders);
    }

    @Override
    public Map<String, String> responseHeaders() {
        return Collections.unmodifiableMap(responseHeaders);
    }

    @Override
    public Set<String> channels() {
        return Collections.unmodifiableSet(channels);
    }

    /** 业务侧常用判据：该记录是否由指定渠道产生（渠道名见类注释）。 */
    public boolean fromChannel(String channel) {
        return channel != null && channels != null && channels.contains(channel);
    }
}
