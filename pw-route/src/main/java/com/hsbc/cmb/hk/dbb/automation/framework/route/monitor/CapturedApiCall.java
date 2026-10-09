package com.hsbc.cmb.hk.dbb.automation.framework.route.monitor;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParser;
import com.hsbc.cmb.hk.dbb.automation.framework.common.security.SensitiveDataSanitizer;
import com.microsoft.playwright.Request;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 一次被采集 API 调用的完整快照（CAPTURE 能力的报告 DTO，不可变）。
 *
 * <p>与 {@link CapturedExchange}（monitor 断言导向）不同，本类是<b>信息导向</b>：
 * 请求侧（方法/URL/头/请求体预览）+ 响应侧（状态码/头/响应体/耗时）+ 元信息（pattern/记录时刻/超时），
 * 供业务层 dump 消费（诊断 / 报告 / 断言辅助）。
 *
 * <p>安全与容量防线：
 * <ul>
 *   <li><b>敏感头脱敏</b>：authorization / cookie / set-cookie / token 等敏感头值一律打码为
 *       {@link SensitiveDataSanitizer#maskToken()}（与全框架统一掩码一致），只保留键名；</li>
 *   <li><b>体截断</b>：请求体预览 ≤ 2 KiB；响应体受 {@code captureBodyLimitBytes} 约束，
 *       超限截断并置 {@link #responseBodyTruncated()}；</li>
 *   <li><b>防御拷贝</b>：header 快照不可变，杜绝调用方修改内部状态（EI_EXPOSE_REP2）。</li>
 * </ul>
 *
 * @param pattern                命中的规则 pattern
 * @param method                 请求方法（GET / POST / ...）
 * @param url                    请求完整 URL
 * @param requestHeaders         请求头（敏感值已脱敏，不可变）
 * @param requestBodyPreview     请求体预览（≤2 KiB；null=无请求体）
 * @param responseStatus         实际响应状态码（未等到响应为 null，配合 timedOut）
 * @param responseHeaders        响应头（敏感值已脱敏，不可变）
 * @param responseBody           响应体（captureBody 开启且有响应才非 null；受截断上限约束）
 * @param responseBodyTruncated  响应体是否被截断（超出 captureBodyLimitBytes）
 * @param durationMs             请求到响应耗时（毫秒；未响应为 -1）
 * @param timedOut               是否在超时窗口内未等到响应
 * @param recordedAtMillis       记录创建时刻（epoch millis）
 * @param body                   响应体<b>载体</b>（{@link CapturedBody}）：非 null 时优先于
 *                               {@code responseBody} / {@code responseBodyBytes}；采集器路径只在载体中
 *                               留一份（不重复占用堆），派发链证据路径无载体（为 null，沿用文本组件）
 */
public record CapturedApiCall(String pattern, String method, String url,
                              Map<String, String> requestHeaders, String requestBodyPreview,
                              Integer responseStatus, Map<String, String> responseHeaders,
                              String responseBody, boolean responseBodyTruncated,
                              long durationMs, boolean timedOut, long recordedAtMillis,
                              Set<String> channels, String primaryChannel,
                              String contentType, byte[] responseBodyBytes,
                              CapturedBody body) {

    /**
     * 兼容构造（旧 12 参签名）：{@code channels} 为空集、{@code primaryChannel} 为 null。
     *
     * <p>目的：本次只为采集记录增加"分渠道"信息，既有调用点（派发链证据、monitor 结果渲染等）
     * 不需要跟着改签名，避免大范围牵动；需要标注渠道的新调用点走全参构造即可。
     */
    public CapturedApiCall(String pattern, String method, String url,
                           Map<String, String> requestHeaders, String requestBodyPreview,
                           Integer responseStatus, Map<String, String> responseHeaders,
                           String responseBody, boolean responseBodyTruncated,
                           long durationMs, boolean timedOut, long recordedAtMillis) {
        this(pattern, method, url, requestHeaders, requestBodyPreview, responseStatus, responseHeaders,
                responseBody, responseBodyTruncated, durationMs, timedOut, recordedAtMillis,
                Set.of(), null, null, null, null);
    }

    /** 敏感头键（小写比较）：值一律打码。 */
    private static final Set<String> SENSITIVE_HEADERS = Set.of(
            "authorization", "proxy-authorization", "cookie", "set-cookie",
            "x-api-key", "api-key", "proxy-authenticate", "www-authenticate");

    /** 头键包含这些片段（小写）也视为敏感（如 x-auth-token）。 */
    private static final String[] SENSITIVE_HEADER_FRAGMENTS = {
            "token", "secret", "password", "credential"
    };

    /** 日志展示用 JSON 缩进器（线程安全，复用同一实例）。 */
    private static final Gson PRETTY_GSON = new GsonBuilder().setPrettyPrinting().create();

    /**
     * 防御性拷贝构造：只做"不可变快照"，<b>不做脱敏</b>。
     *
     * <p>口径：<b>数据层保真、渲染层脱敏</b>。业务需要拿到真实响应（例如取整响应、改指定节点后
     * 当 mock 回放），故 header 原值必须保留；脱敏只在 {@link #detail()} 等<b>渲染路径</b>做
     * （见 {@code renderDetail} 里对 {@code sanitize(...)} 的调用），日志/报告因此不会泄露。
     */
    public CapturedApiCall {
        requestHeaders = requestHeaders == null ? Map.of()
                : java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(requestHeaders));
        responseHeaders = responseHeaders == null ? Map.of()
                : java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(responseHeaders));
        // 渠道集合同样做防御性拷贝：静态分析（SpotBugs EI_EXPOSE_REP/REP2）对
        // "持有调用方传入的可变集合"会直接拦构建（pw-core 的 CapturedApiCallView 已踩过两次）。
        channels = channels == null ? Set.of()
                : java.util.Collections.unmodifiableSet(new java.util.LinkedHashSet<>(channels));
        // 数组不是不可变类型：不拷贝会被静态分析判为"存了外部可变对象"（EI_EXPOSE_REP2）并拦下构建。
        responseBodyBytes = responseBodyBytes == null ? null : responseBodyBytes.clone();
    }

    /** 只读视图：静态分析要求访问器不得直接返回可变类型字段（与 pw-core 的 CapturedApiCallView 同处理）。 */
    @Override
    public Set<String> channels() {
        return java.util.Collections.unmodifiableSet(channels);
    }

    /**
     * 响应体文本：优先取自载体 {@link #body}（懒解码 + 缓存，避免大 body 一次性物化），
     * 否则回落到 {@code responseBody} 组件（派发链证据只有 preview 文本、没有载体）。
     *
     * <p><b>null 语义</b>：载体与组件同为 null 时返回 {@code null} —— {@code captureBody} 关闭时
     * 必须保持 null（既有消费者依赖此语义），故此处不得触发解码。
     */
    @Override
    public String responseBody() {
        if (body == null) {
            return responseBody;
        }
        java.nio.charset.Charset charset = contentType == null ? null
                : com.hsbc.cmb.hk.dbb.automation.framework.route.util.MediaType.parse(contentType).charset();
        return body.text(charset);
    }

    /**
     * 原始响应体字节的<b>副本</b>（数组是不可变类型的例外：不拷贝等于暴露内部表示，静态分析会拦构建）。
     *
     * <p>仅在开启 {@code captureBody} 时有值；文本型与二进制型都会有值，供"精确回放/自行解析"使用。
     */
    @Override
    public byte[] responseBodyBytes() {
        if (body != null) {
            return body.readAll();      // readAll() 本身返回副本
        }
        return responseBodyBytes == null ? null : responseBodyBytes.clone();
    }

    /** 请求体预览上限（与 {@code CapturedExchange} 对齐）。 */
    private static final int REQUEST_BODY_PREVIEW_MAX = 2048;

    /**
     * 字段式构造入口（响应侧可为空）——供 route 派发链与 monitor 结果证据共用一套渲染。
     */
    public static CapturedApiCall of(String pattern, String method, String url,
                                     Map<String, String> requestHeaders, String requestBodyPreview,
                                     Integer responseStatus, Map<String, String> responseHeaders,
                                     String responseBody, boolean responseBodyTruncated,
                                     long durationMs, boolean timedOut) {
        return new CapturedApiCall(pattern, method, url, requestHeaders, requestBodyPreview,
                responseStatus, responseHeaders, responseBody, responseBodyTruncated,
                durationMs, timedOut, System.currentTimeMillis());
    }

    /**
     * 从 Playwright 请求对象构造快照 —— 供<b>派发链</b>上报"命中/动作"证据（MOCK / MODIFY / DELAY
     * 没有响应侧观测队列，其请求侧信息只能这样取；请求体按 {@value #REQUEST_BODY_PREVIEW_MAX} 字节截断）。
     */
    public static CapturedApiCall ofRequest(String pattern, Request request, Integer responseStatus,
                                            Map<String, String> responseHeaders, String responseBody,
                                            boolean responseBodyTruncated, long durationMs, boolean timedOut) {
        String postData = request == null ? null : request.postData();
        String preview = postData == null ? null
                : (postData.length() > REQUEST_BODY_PREVIEW_MAX
                        ? postData.substring(0, REQUEST_BODY_PREVIEW_MAX) + "...[truncated]" : postData);
        return new CapturedApiCall(pattern,
                request == null ? null : request.method(),
                request == null ? null : request.url(),
                request == null ? Map.of() : request.headers(),
                preview, responseStatus, responseHeaders, responseBody, responseBodyTruncated,
                durationMs, timedOut, System.currentTimeMillis());
    }

    /** 在 {@link #detail()} 尾部追加自定义行（如 rule / applied / result），供路由证据使用。 */
    public String detailWith(String... extraLines) {
        StringBuilder d = new StringBuilder(detail());
        if (extraLines != null) {
            for (String line : extraLines) {
                if (line != null && !line.isEmpty()) {
                    for (String part : line.split("\n", -1)) {
                        d.append('\n').append("  ").append(part);
                    }
                }
            }
        }
        return d.toString();
    }

    /** 覆盖 record accessor：返回不可变拷贝（防调用方修改内部快照）。 */
    @Override
    public Map<String, String> requestHeaders() {
        return Map.copyOf(requestHeaders);
    }

    /** 覆盖 record accessor：返回不可变拷贝（防调用方修改内部快照）。 */
    @Override
    public Map<String, String> responseHeaders() {
        return Map.copyOf(responseHeaders);
    }

    /** 脱敏 + 不可变拷贝；null 视为空 Map。掩码取全框架统一值（{@link SensitiveDataSanitizer#maskToken()}）。 */
    private static Map<String, String> sanitize(Map<String, String> headers) {
        if (headers == null || headers.isEmpty()) {
            return Map.of();
        }
        String mask = SensitiveDataSanitizer.maskToken();
        Map<String, String> copy = new LinkedHashMap<>(headers.size());
        for (Map.Entry<String, String> e : headers.entrySet()) {
            copy.put(e.getKey(), isSensitive(e.getKey()) ? mask : e.getValue());
        }
        return Collections.unmodifiableMap(copy);
    }

    /** 键名（小写）命中敏感集或含敏感片段 → 打码。 */
    private static boolean isSensitive(String key) {
        if (key == null) {
            return false;
        }
        String lower = key.toLowerCase(Locale.ROOT);
        if (SENSITIVE_HEADERS.contains(lower)) {
            return true;
        }
        for (String fragment : SENSITIVE_HEADER_FRAGMENTS) {
            if (lower.contains(fragment)) {
                return true;
            }
        }
        return false;
    }

    /** 单行摘要（供日志 / 报告首行展示）。URL 已脱敏；null 值不出现（不打印 null 占位）。 */
    public String summary() {
        StringBuilder s = new StringBuilder(96);
        if (timedOut) {
            s.append("[timeout] ");
        } else if (responseStatus != null) {
            s.append("[status=").append(responseStatus).append("] ");
        }
        if (method != null) {
            s.append(method).append(' ');
        }
        s.append(SensitiveDataSanitizer.sanitizeUrl(url));
        if (pattern != null && !pattern.isEmpty()) {
            s.append(" (pattern=").append(pattern).append(')');
        }
        return s.toString().trim();
    }

    /**
     * 多行 pretty 明细（供日志展示）：规则 / 请求 / 响应三段式，头逐行、体自动 JSON 缩进。
     *
     * <p><b>绝不抛异常、绝不影响主流程</b>：体是否为 JSON 均可——JSON 体自动缩进，非 JSON 体原样
     * （仅脱敏），解析失败一律回退；任何展示期异常都退化为 {@link #summary()}。</p>
     *
     * <p>头值构造期已脱敏；体先经 {@link SensitiveDataSanitizer#sanitizeBody(String)} 打码，
     * 响应体超限在段内附 {@code [truncated]} 标记。示例：
     * <pre>{@code
     * [Route] captured api {
     *   pattern   : profile/list
     *   request   : POST https://host/.../profile/list
     *   duration  : 417ms
     *   reqHeaders:
     *     accept: application/json
     *     cookie: ***
     *   reqBody:
     *     {
     *       "user": "u"
     *     }
     *   response  : 200
     *   respHeaders:
     *     content-type: application/json
     *   respBody:
     *     {
     *       "updateContctOverlayFlag": "Y"
     *     }
     * }
     * }</pre>
     */
    public String detail() {
        try {
            return renderDetail();
        } catch (RuntimeException e) {
            // 展示失败绝不上抛：退化为摘要，采集/断言等主流程不受影响
            return summary();
        }
    }

    /**
     * pretty 明细渲染（受 {@link #detail()} 的 catch 保护）。
     *
     * <p><b>null / 空的字段整行不显示</b>（不打印 {@code null}、也不打印 {@code (none)} 占位）——
     * 报告里每一行都是"确实有值"的信息；块内文案一律英文（报告可被跨团队直接阅读）。</p>
     */
    private String renderDetail() {
        StringBuilder d = new StringBuilder("{\n");
        appendField(d, "pattern", pattern);
        if (method != null || url != null) {
            appendField(d, "request", (method == null ? "" : method + " ")
                    + SensitiveDataSanitizer.sanitizeUrl(url));
        }
        if (durationMs >= 0) {
            appendField(d, "duration", durationMs + "ms");
        }
        // 渲染期脱敏：记录里是原始值，日志/报告必须打码（sanitize 只在此处使用）
        appendHeaders(d, "reqHeaders", sanitize(requestHeaders));
        appendBody(d, "reqBody", requestBodyPreview, false);
        if (timedOut) {
            appendField(d, "response", "[timeout]");
        } else {
            appendField(d, "response", responseStatus);
        }
        appendHeaders(d, "respHeaders", sanitize(responseHeaders));
        appendBody(d, "respBody", responseBody(), responseBodyTruncated);
        d.append('}');
        return d.toString();
    }

    /** 单字段行（{@code label} 补齐到 10 字符后接 {@code ": "}）；null / 空值整行不输出。 */
    static void appendField(StringBuilder d, String label, Object value) {
        if (label == null || value == null) {
            return;
        }
        String text = String.valueOf(value);
        if (text.isEmpty()) {
            return;
        }
        d.append("  ").append(label);
        for (int i = label.length(); i < FIELD_LABEL_WIDTH; i++) {
            d.append(' ');
        }
        d.append(": ").append(text).append('\n');
    }

    /** 字段名对齐宽度（{@code "  " + label 补齐至该宽度 + ": "}）。 */
    private static final int FIELD_LABEL_WIDTH = 10;

    /** 头段落：逐行 {@code key: value}；整段为空（或值全为 null）时整段不输出。 */
    private static void appendHeaders(StringBuilder d, String label, Map<String, String> headers) {
        if (headers == null || headers.isEmpty()) {
            return;
        }
        d.append("  ").append(label);
        for (int i = label.length(); i < FIELD_LABEL_WIDTH; i++) {
            d.append(' ');
        }
        d.append(":\n");
        for (Map.Entry<String, String> e : headers.entrySet()) {
            if (e.getValue() != null) {
                d.append("    ").append(e.getKey()).append(": ").append(e.getValue()).append('\n');
            }
        }
    }

    /**
     * 证据文本中单个体最多渲染的字符数 —— 与 {@code RouteDispatcher} / {@code CapturedExchange}
     * 既有的证据体上限（4096）同口径，避免同一份报告里两套截断阈值。
     */
    static final int EVIDENCE_BODY_MAX_CHARS = 4096;

    /**
     * 体段落：整体缩进 4 空格（JSON 自动 pretty；空体整段省略）。
     *
     * <p><b>证据有界化</b>：只渲染首 {@value #EVIDENCE_BODY_MAX_CHARS} 字符，并标注总长度 ——
     * 超大响应不再把整份 pretty JSON 灌进日志/报告（这正是"体不撑爆堆"的另一半：
     * 遗留存储形态之外，报告渲染也必须是有界的）。截断在 {@code prettyBody} <b>之前</b>完成，
     * 故也不会为超大 JSON 付出整体格式化开销。原体（{@code responseBody()} /
     * {@code responseBodyBytes()}）与 DTO 其他字段均<b>不受影响</b>。
     */
    private static void appendBody(StringBuilder d, String label, String body, boolean truncated) {
        if (body == null || body.isEmpty()) {
            return;
        }
        boolean bounded = body.length() > EVIDENCE_BODY_MAX_CHARS;
        String rendered = bounded ? body.substring(0, EVIDENCE_BODY_MAX_CHARS) : body;
        d.append("  ").append(label).append(':');
        if (truncated) {
            d.append(" [truncated]");
        }
        if (bounded) {
            d.append(" [evidence limited to first ").append(EVIDENCE_BODY_MAX_CHARS)
                    .append(" of ").append(body.length()).append(" chars]");
        }
        d.append('\n');
        for (String line : prettyBody(rendered).split("\n", -1)) {
            d.append("    ").append(line).append('\n');
        }
    }

    /**
     * 体脱敏 + JSON 缩进。非 JSON 体（HTML / 文本 / 二进制文本等）<b>原样返回</b>（已脱敏），
     * 绝不因"不是 JSON"而报错；脱敏失败时宁可不出内容也不泄露。
     */
    private static String prettyBody(String body) {
        String safe;
        try {
            safe = SensitiveDataSanitizer.sanitizeBody(body);
        } catch (RuntimeException e) {
            return "[body unavailable]";
        }
        String trimmed = safe == null ? "" : safe.trim();
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            try {
                return PRETTY_GSON.toJson(JsonParser.parseString(trimmed));
            } catch (RuntimeException notJson) {
                // 看起来像 JSON 但解析失败 → 回退脱敏原文
                return safe;
            }
        }
        return safe;
    }
}
