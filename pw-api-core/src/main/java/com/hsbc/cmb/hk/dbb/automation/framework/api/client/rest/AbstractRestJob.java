package com.hsbc.cmb.hk.dbb.automation.framework.api.client.rest;

import com.hsbc.cmb.hk.dbb.automation.framework.api.config.ConfigProvider;
import com.hsbc.cmb.hk.dbb.automation.framework.api.config.ApiFrameworkConfig;
import com.hsbc.cmb.hk.dbb.automation.framework.api.security.SecurityAudit;
import com.hsbc.cmb.hk.dbb.automation.framework.api.utility.EnvironmentUtils;
import com.hsbc.cmb.hk.dbb.automation.framework.api.core.entity.Entity;
import com.hsbc.cmb.hk.dbb.automation.framework.api.domain.enums.ConfigKeys;
import com.hsbc.cmb.hk.dbb.automation.framework.common.reporting.ApiOperationKind;
import com.hsbc.cmb.hk.dbb.automation.framework.common.reporting.ReportingBridgeRegistry;
import com.typesafe.config.Config;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.restassured.RestAssured;
import io.restassured.config.HttpClientConfig;
import io.restassured.config.LogConfig;
import io.restassured.config.RestAssuredConfig;
import io.restassured.config.SSLConfig;
import com.hsbc.cmb.hk.dbb.automation.framework.api.logging.SanitizingPrintStream;
import io.restassured.http.Headers;
import io.restassured.response.ValidatableResponse;
import io.restassured.specification.RequestSpecification;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Function;

public abstract class AbstractRestJob {

    private static final Logger logger = LoggerFactory.getLogger(AbstractRestJob.class);

    /** 上报到报告的请求/响应体预览上限（字符）——防止大文件/二进制灌进 JSON 与页面。 */
    private static final int BODY_PREVIEW_MAX_CHARS = 512;

    /** JSON 美化专用 mapper（只读树 → pretty string，不做类型绑定，容错最好）。 */
    private static final ObjectMapper JSON_MAPPER = new ObjectMapper();

    private static RestAssuredConfig restAssuredConfig;

    private ValidatableResponse validatableResponse;

    static {
        // 仅做纯对象装配（ConfigProvider / RestAssuredConfig），**不**提前触碰 RestAssured 全局默认配置副作用 —— 原因见
        // ensureSslPolicyApplied()：副作用一旦留在静态块，"类一加载"就等于"强写 RestAssured.config"，干扰纯 JUnit 单测。
        initializeRestAssuredConfig();
    }

    /** SSL 策略是否已应用（惰性幂等标志，见 {@link #ensureSslPolicyApplied()}）。 */
    private static volatile boolean sslPolicyApplied;

    /**
     * 应用 SSL/TLS 证书校验策略（安全基线）。
     * <p>
     * 默认【严格校验】（RestAssured 原生默认行为：校验证书链 + 主机名）。
     * 仅当配置显式声明 {@code http.ssl.relax-validation=true} 时才放宽。
     * <p>
     * 原实现经 {@code SerenityRest} 无条件放宽 TLS 校验，对所有经
     * RestAssured 发出的 API 调用永久关闭证书校验（MITM 风险），
     * 并令 {@link ApiFrameworkConfig#isSslRelaxValidation()}（默认 false）形同虚设。
     * <p>
     * 说明：RestAssured 5.x 未提供 {@code useStrictHTTPSValidation()}，恢复严格校验的
     * 正确方式是显式装配一个默认 {@link SSLConfig} 并写回 RestAssured 的默认 config。
     * <p>
     * 安全审计：放宽时登记 {@link SecurityAudit#recordRelaxedTls(String, String)}（可观测、
     * 可被测试断言）；若检测到生产（prod）环境仍放宽，则 fail-fast 拒绝启动，防止生产
     * 流量暴露于中间人攻击。
     */
    private static void applySslPolicy() {
        boolean relax = ApiFrameworkConfig.isSslRelaxValidation();
        SSLConfig sslConfig = relax
                ? SSLConfig.sslConfig().relaxedHTTPSValidation()
                : SSLConfig.sslConfig();
        RestAssured.config = RestAssured.config().sslConfig(sslConfig);

        if (relax) {
            String environment = EnvironmentUtils.currentEnvironment().getEnvironmentName();
            SecurityAudit.recordRelaxedTls(environment, ApiFrameworkConfig.HTTP_SSL_RELAX_VALIDATION.key());
            if (SecurityAudit.isProductionEnvironment(environment)) {
                throw new IllegalStateException(
                        "Refusing to start: TLS certificate validation is RELAXED in a PRODUCTION-like "
                                + "environment (env=" + environment + "). Never enable http.ssl.relax-validation "
                                + "for production-like runs - this exposes API traffic to MITM.");
            }
        }
    }

    /**
     * 惰性应用 SSL/TLS 策略（幂等、线程安全）。
     *
     * <p><b>为什么不放在静态块</b>：{@link #applySslPolicy()} 会写回 {@code RestAssured.config}
     * （RestAssured 的静态全局配置）。若放在静态块，则「类一加载」就强写该全局配置，可能干扰纯 JUnit 单测
     * （如只验证重试编排的 {@code AbstractRestJobRetryLoopTest}）。把该副作用推迟到「真正要发请求」的唯一入口后，
     * 单测覆写 {@link #buildRequestSpecification(Entity)} 即可完全绕开，本类因而可在纯 JUnit 下测试。</p>
     *
     * <p>策略放宽时的安全审计与生产 fail-fast 语义不变，仅触发时点从「类加载」变为「首次发请求」。</p>
     */
    private static void ensureSslPolicyApplied() {
        if (sslPolicyApplied) {
            return;
        }
        synchronized (AbstractRestJob.class) {
            if (!sslPolicyApplied) {
                applySslPolicy();
                sslPolicyApplied = true;
            }
        }
    }

    public abstract void perform(Entity entity);

    public static RestAssuredConfig getRestAssuredConfig() {
        return restAssuredConfig;
    }

    public static void setRestAssuredConfig(RestAssuredConfig restAssuredConfig) {
        AbstractRestJob.restAssuredConfig = restAssuredConfig;
    }

    public ValidatableResponse getValidatableResponse() {
        return validatableResponse;
    }

    public void setValidatableResponse(ValidatableResponse validatableResponse) {
        this.validatableResponse = validatableResponse;
    }

    /**
     * 剥离服务器返回的 HTML 包裹标签（如 &lt;html&gt;&lt;body&gt;...&lt;/body&gt;&lt;/html&gt;）
     * 提取 body 中间的实际内容（纯 JSON / XML / 文本等）
     */
    protected static String stripHtmlWrapper(String rawBody) {
        if (rawBody == null || rawBody.isEmpty()) {
            return rawBody;
        }
        String trimmed = rawBody.trim();
        // 纯 JSON，无需处理
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            return trimmed;
        }
        // 提取 <body>...</body> 中间内容
        int bodyStart = trimmed.indexOf("<body>");
        int bodyEnd = trimmed.indexOf("</body>");
        if (bodyStart != -1 && bodyEnd != -1) {
            return trimmed.substring(bodyStart + 6, bodyEnd).trim();
        }
        return trimmed;
    }

    // ==================== Rest*Job 模板方法（消除 5 份近拷贝） ====================

    /**
     *  RestGetJob / RestPostJob / RestPutJob / RestPatchJob / RestDeleteJob
     * 五个实现类原先<b>逐字相同</b>，唯一差异是调用哪个 HTTP 方法（get/post/put/patch/delete）。
     * 每处约 40 行重复代码意味着任何一处修复（如新增 header 处理、代理逻辑）都要同步改 5 遍，
     * 漏改即产生行为不一致。这里上提为模板方法，子类只负责提供 HTTP 动作。
     *
     * @param entity  请求实体
     * @param invoker 具体 HTTP 动作，如 {@code spec -> spec.when().get(endpoint).then()}
     */
    /**
     * 执行请求（默认非幂等，不重试 —— 与历史行为一致，避免对非幂等写操作误重试）。
     * 幂等请求（GET/PUT/DELETE）请改用 {@link #execute(Entity, Function, boolean)} 并传 {@code true}。
     */
    protected void execute(Entity entity,
                           Function<RequestSpecification, ValidatableResponse> invoker) {
        execute(entity, invoker, false);
    }

    /**
     * 执行请求（P-3：幂等 + 网络类错误 + 5xx 重试，绝不重试 4xx）。
     *
     * @param entity     请求实体
     * @param invoker    具体 HTTP 动作
     * @param idempotent 是否幂等（GET/PUT/DELETE=true；POST/PATCH=false）。仅当幂等时才对
     *                   连接超时 / 网络异常 / 5xx 重试，防止对非幂等写操作重复提交。
     */
    protected void execute(Entity entity,
                           Function<RequestSpecification, ValidatableResponse> invoker,
                           boolean idempotent) {
        long startedAt = System.nanoTime();
        ValidatableResponse response;
        try {
            response = executeWithRetry(entity, invoker, idempotent);
        } catch (RuntimeException e) {
            //  失败同样要进报告：业务在报告里看到的“缺少 API 调用”往往正是失败那次（如 getotp 500）。
            reportApiOperation(entity, elapsedMillis(startedAt), null, e);
            throw e;
        }
        if (entity.isApiRequestResponseLogsEnabled()) {
            response.log().all();
        }
        applyResponse(entity, response);
        reportApiOperation(entity, elapsedMillis(startedAt), entity.getResponseCode(), null);
    }

    /** HTTP 方法名（子类覆写，仅用于报告展示；默认 {@code HTTP} 保证不影响既有实现）。 */
    protected String httpMethod() {
        return "HTTP";
    }

    private static long elapsedMillis(long startedAtNanos) {
        return Math.max(0L, (System.nanoTime() - startedAtNanos) / 1_000_000L);
    }

    /**
     * 把一次 API 调用上报到测试报告（<b>尽力而为</b>，任何异常都不影响用例结果）。
     *
     * <p>动机（2026-10-20 用户反馈）：{@code getotp.htm} 这类经 RestAssured 发出的请求不在浏览器内，
     * Playwright 的 route 抓包看不到它，报告里只出现浏览器请求、缺少 API 调用记录。这里在
     * “所有 Rest*Job 共同的唯一出口”上报 method / url / 状态码 / 耗时，由
     * {@code ReportingBridge} 落到 per-case 的 {@code apiOperations}（按发生步骤归位）。
     *
     * <p><b>行格式（管道分隔的多行块）</b>——报告侧按此前缀拆成结构化卡片（方法徽标 / URL /
     * 状态 / 耗时 + Request/Response 分区），单行纯文本既不分方法也没法分区
     * （2026-10-20 用户反馈"没有区分 request method、request headers 等信息"）：
     * <pre>
     * REST|GET|https://host/getotp.htm?type=1|200|5771 ms
     * REQH|Request headers|Accept=application/json
     * REQB|Request body|&lt;none&gt;
     * RESH|Response headers|Content-Type=text/html
     * RESB|Response body|{"token":"..."}
     * </pre>
     * 失败时状态列为 {@code FAILED}，并以 {@code ERR|Error|...} 行替代两个 Response 行。
     *
     * <p>脱敏由报告侧（{@code ScrubGate}）统一完成：URL query 与 header 中的
     * token/key/password 会被替换为 {@code [SCRUBBED]}，因此这里可携带完整 URL
     * （业务需要靠它定位环境）。响应体仍做长度截断，防止把大文件/二进制灌进报告。
     */
    private void reportApiOperation(Entity entity, long elapsedMs, Integer status, Throwable failure) {
        try {
            StringBuilder block = new StringBuilder(256);
            block.append(ApiOperationKind.REST).append('|')
                    .append(httpMethod()).append('|')
                    .append(cell(resolveRequestUrl(entity))).append('|')
                    .append(failure != null ? "FAILED" : String.valueOf(status == null ? "-" : status))
                    .append('|').append(elapsedMs).append(" ms");
            block.append("\nREQH|Request headers|").append(cell(describeKeys(entity.getRequestHeaders())));
            block.append("\nREQB|Request body|").append(cell(describeBody(entity.getRequestPayload())));
            if (failure != null) {
                block.append("\nERR|Error|").append(cell(failure.getClass().getSimpleName()
                        + (StringUtils.isNotBlank(failure.getMessage()) ? ": " + failure.getMessage() : "")));
            } else {
                block.append("\nRESH|Response headers|")
                        .append(cell(describeEntries(entity.getResponseHeaders())));
                block.append("\nRESB|Response body|").append(cell(describeBody(entity.getResponsePayload())));
            }
            ReportingBridgeRegistry.get().recordApiOperation(block.toString());
        } catch (Throwable t) {
            logger.debug("API 调用上报失败（忽略，不影响用例）: {}", t.toString());
        }
    }

    /**
     * 单元格消毒：{@code |} 是行内分隔符，内容里的管道符编码为 {@code %7C}
     * （URL 的合法编码）以防破坏分栏。
     *
     * <p><b>换行必须保留</b>：JSON 美化后的请求/响应体含换行（缩进排版），
     * 折叠成空格就失去"按 JSON 显示"的意义。多行 value 由报告侧
     * {@code ApiCallBlock} 的"行首 KEY 识别"解析兼容，不会破坏结构。
     */
    private static String cell(String value) {
        if (value == null) {
            return "";
        }
        return value.indexOf('|') < 0 ? value : value.replace("|", "%7C");
    }

    /**
     * 单值 map（请求头）→ 只列键名（值可能含敏感信息，不在此展开），{@code "; "} 分隔。
     *
     * <p>刻意<b>不加</b> {@code [ ]} 包裹：报告侧 ScrubGate 的头脱敏正则会一路吃到行尾，
     * 外层括号会被吞掉留下“没闭合”的残串（2026-10-20 实测）。
     */
    private static String describeKeys(Map<String, Object> map) {
        if (map == null || map.isEmpty()) {
            return "<none>";
        }
        return String.join("; ", map.keySet());
    }

    /**
     * 键值 map（响应头）→ {@code k=v; k=v}，值交给报告侧 ScrubGate 脱敏。
     *
     * <p>两个刻意的选择：① 分隔符用 {@code "; "} 而非 {@code ", "}——HTTP 头值自身常含逗号
     * （如 {@code Access-Control-Allow-Methods=GET, POST, OPTIONS}），逗号分隔会把一头拆成
     * 看上去像三头的歧义文本；② 不加外层 {@code [ ]}，理由同 {@link #describeKeys}。
     */
    private static String describeEntries(Map<String, ?> map) {
        if (map == null || map.isEmpty()) {
            return "<none>";
        }
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (Map.Entry<String, ?> entry : map.entrySet()) {
            if (!first) {
                sb.append("; ");
            }
            String value = String.valueOf(entry.getValue());
            String pretty = prettyJson(value);
            sb.append(entry.getKey()).append('=').append(pretty != null ? pretty : value);
            first = false;
        }
        return sb.toString();
    }

    /** 请求/响应体：空白 → {@code <none>}；JSON 则 pretty-print；否则截断到上限并标注原始长度。 */
    private static String describeBody(String body) {
        if (StringUtils.isBlank(body)) {
            return "<none>";
        }
        String pretty = prettyJson(body);
        String shown = pretty != null ? pretty : body;
        if (shown.length() <= BODY_PREVIEW_MAX_CHARS) {
            return shown;
        }
        return shown.substring(0, BODY_PREVIEW_MAX_CHARS)
                + "… [truncated, total " + body.length() + " chars]";
    }

    /**
     * JSON 美化：请求/响应体（以及 header 值）只要是合法 JSON 就按 2 空格缩进排版，
     * 便于人读；非 JSON 返回 {@code null}（调用方回落原文）。
     *
     * <p>刻意不引入新依赖：{@code pw-api-core} 已在用 Jackson（{@code BaseStep}）。
     */
    private static String prettyJson(String raw) {
        String trimmed = raw.strip();
        if (trimmed.isEmpty() || (trimmed.charAt(0) != '{' && trimmed.charAt(0) != '[')) {
            return null;
        }
        try {
            return JSON_MAPPER.readTree(trimmed).toPrettyString();
        } catch (Exception e) {
            return null; //  不是 JSON（或非法 JSON）——按原文展示，绝不因排版失败丢内容
        }
    }

    /** 还原请求 URL（baseUri + basePath + endpoint + query）用于报告展示。 */
    static String resolveRequestUrl(Entity entity) {
        StringBuilder url = new StringBuilder(96);
        if (entity.getBaseUri() != null) {
            url.append(entity.getBaseUri());
        }
        if (StringUtils.isNotBlank(entity.getBasePath())) {
            url.append(entity.getBasePath());
        }
        if (StringUtils.isNotBlank(entity.getEndpoint())) {
            url.append(entity.getEndpoint());
        }
        Map<String, Object> query = entity.getQueryParams();
        if (query != null && !query.isEmpty()) {
            url.append('?');
            boolean first = true;
            for (Map.Entry<String, Object> entry : query.entrySet()) {
                if (!first) {
                    url.append('&');
                }
                url.append(entry.getKey()).append('=').append(entry.getValue());
                first = false;
            }
        }
        return url.toString();
    }

    /**
     * P-3 重试内核：仅在幂等且发生「连接超时 / 网络异常 / 5xx」时重试，
     * 4xx（客户端错误）立即返回不重试。重试次数 / 退避间隔取自
     * {@link ApiFrameworkConfig#getRetryCount()} / {@link ApiFrameworkConfig#getRetryDelay()}。
     */
    ValidatableResponse executeWithRetry(Entity entity,
                                          Function<RequestSpecification, ValidatableResponse> invoker,
                                          boolean idempotent) {
        int maxAttempts = ApiFrameworkConfig.getRetryCount();
        long delayMs = ApiFrameworkConfig.getRetryDelay();
        ValidatableResponse last = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                RequestSpecification requestSpecification = buildRequestSpecification(entity);
                // 请求转储只在首次尝试输出：重试每轮都会重新构建规格，逐次打印会让同一请求在报告里
                // 出现多份（2026-10-18 用户反馈 "Method 出现两次"）；重试事实由下方 WARN 记录。
                if (attempt == 1) {
                    logRequestOnce(requestSpecification, entity);
                }
                ValidatableResponse response = invoker.apply(requestSpecification);
                int status = response.extract().statusCode();
                if (shouldRetry(status, idempotent, attempt, maxAttempts)) {
                    logger.warn("API 请求返回 5xx（{}），幂等方法第 {}/{} 次将重试（退避 {}ms）: {}",
                            status, attempt, maxAttempts, delayMs, entity.getBaseUri());
                    sleepQuietly(delayMs);
                    last = response;
                    continue;
                }
                return response;
            } catch (Exception e) {
                if (idempotent && attempt < maxAttempts) {
                    logger.warn("API 请求失败（{}），幂等方法第 {}/{} 次将重试（退避 {}ms）: {}",
                            e.getMessage(), attempt, maxAttempts, delayMs, entity.getBaseUri());
                    sleepQuietly(delayMs);
                    last = null;
                    continue;
                }
                throw e;
            }
        }
        return last;
    }

    /**
     * 重试退避等待：框架主代码禁用 {@code Thread.sleep}（ArchUnit {@code frameworkCodeMustNotCallThreadSleep}），
     * 退避需「可中断且不抛受检异常」，故改用 {@link LockSupport#parkNanos(long)}。
     * park 在中断时立即返回且<b>保留中断标志</b>（语义等价于 {@code Thread.sleep} + 恢复中断）。
     */
    private static void sleepQuietly(long millis) {
        if (millis <= 0) {
            return;
        }
        LockSupport.parkNanos(millis * 1_000_000L);
        if (Thread.currentThread().isInterrupted()) {
            logger.debug("API 重试退避被中断（parkNanos 提前返回，中断标志保留）");
        }
    }

    /**
     * 重试判定（P-3 内核规则，包可见便于单测）：
     * 仅当<b>幂等</b>且响应为 <b>5xx</b> 且<b>未达最大尝试次数</b>时重试；
     * 4xx（客户端错误）与非幂等写操作（POST/PATCH）一律不重试，避免重复提交或无效重试。
     */
    boolean shouldRetry(int statusCode, boolean idempotent, int attempt, int maxAttempts) {
        return idempotent && statusCode >= 500 && statusCode <= 599 && attempt < maxAttempts;
    }

    /** 构建请求规格（base 信息 → 参数 → body → 代理）。请求日志由调用方统一输出，见 {@link #logRequestOnce}。 */
    protected RequestSpecification buildRequestSpecification(Entity entity) {
        ensureSslPolicyApplied();
        final RequestSpecification requestSpecification = RestAssured.given()
                .baseUri(entity.getBaseUri())
                .basePath(entity.getBasePath())
                .config(AbstractRestJob.getRestAssuredConfig())
                .headers(entity.getRequestHeaders())
                .pathParams(entity.getPathParams())
                .queryParams(entity.getQueryParams())
                .formParams(entity.getFormParams())
                .cookies(entity.getCookies());

        if (StringUtils.isNotBlank(entity.getRequestPayload())) {
            requestSpecification.body(entity.getRequestPayload());
        }

        if (StringUtils.isNotBlank(entity.getProxyHost())) {
            requestSpecification.proxy(entity.getProxyHost(), entity.getProxyPort(), entity.getProxySchema());
        }

        return requestSpecification;
    }

    /**
     * 输出请求转储（受 {@code api.request.response.logging.enabled} 控制）。
     *
     * <p>抽成独立方法是为了把「构建」与「日志」解耦：规格每次重试都要重建，而同一请求的转储
     * 只需输出一次，否则重试会让报告里出现多份完全相同的 “Request method/URI” 块。</p>
     */
    private static void logRequestOnce(RequestSpecification spec, Entity entity) {
        if (spec != null && entity.isApiRequestResponseLogsEnabled()) {
            spec.log().all();
        }
    }

    /** 将响应回写到 Entity（状态码、cookie、body、headers）。 */
    protected void applyResponse(Entity entity, ValidatableResponse response) {
        this.setValidatableResponse(response);
        entity.setResponseCode(response.extract().statusCode());
        entity.setResponseCookies(response.extract().response().cookies());
        entity.setResponsePayload(stripHtmlWrapper(response.extract().response().body().asString()));
        Headers headers = response.extract().response().headers();
        if (headers != null) {
            Map<String, String> responseHeader = new HashMap<>();
            headers.forEach(it -> responseHeader.put(it.getName(), it.getValue()));
            entity.setResponseHeaders(responseHeader);
        }
    }

    private static void initializeRestAssuredConfig() {
        final Config config = ConfigProvider.getConfig();
        int httpConnectTimeout;
        int httpSocketTimeout;

        // Priority: System Property > ApiFrameworkConfig > Default value
        Optional<String> opt = Optional.ofNullable(System.getProperty(ConfigKeys.HTTP_CONNECTION_TIMEOUT.toString()));
        httpConnectTimeout = opt
            .map(Integer::parseInt)
            .orElse(ApiFrameworkConfig.getConnectionTimeout());

        opt = Optional.ofNullable(System.getProperty(ConfigKeys.HTTP_SOCKET_TIMEOUT.toString()));
        httpSocketTimeout = opt
            .map(Integer::parseInt)
            .orElse(ApiFrameworkConfig.getSocketTimeout());

        // P-3：连接复用（RestAssured 默认每请求新建客户端，握手开销大）。
        //
        //  ⚠️【重要】不可再经 httpClientFactory 注入自建 HttpClient：
        //    rest-assured 的 Groovy 实现（RequestSpecificationImpl.applyPathParamsAndSendRequest
        //    → DefaultTypeTransformation.castToType）会把该实例强转为
        //    org.apache.http.impl.client.AbstractHttpClient（HttpClient 4.3 之前的抽象类），
        //    而 4.3+ 交由 HttpClients.custom().build() 产出的是 InternalHttpClient（并非其子类）
        //    → 每个请求抛 GroovyCastException: Cannot cast ... InternalHttpClient ... to ... AbstractHttpClient，
        //      整批 API 用例全红（实测 134 次）。
        //
        //  改用「由 rest-assured 自身创建客户端」：不设置 httpClientFactory、也不强制单实例复用，
        //    只配置超时与脱敏日志。客户端构造路径完全交给 rest-assured，既避开上述 Groovy 强转，
        //    也避开 reuseHttpClientInstance() 的另一个坑（单连接被响应占用未释放时，后续请求抛
        //    "Invalid use of BasicClientConnManager: connection still allocated" —— 实测 9/12 用例因此失败）。
        //
        //  P-3 权衡：放弃了"显式连接池（max-total/per-route）"的调优；连接复用改由 rest-assured
        //    默认行为提供。连接池调优与 rest-assured 的 Groovy 客户端强转在本依赖版本组合下互斥，
        //    以「API 用例可用」优先（原先 134 次强转导致整批用例全红，属功能性缺陷，优先级高于吞吐调优）。
        final RestAssuredConfig restAssuredConfig = RestAssuredConfig.config()
                .httpClient(HttpClientConfig.httpClientConfig()
                        .setParam("http.connection.timeout", httpConnectTimeout)
                        .setParam("http.socket.timeout", httpSocketTimeout))
                .logConfig(new LogConfig(
                        new SanitizingPrintStream(System.out), true));
        setRestAssuredConfig(restAssuredConfig);
    }
}
