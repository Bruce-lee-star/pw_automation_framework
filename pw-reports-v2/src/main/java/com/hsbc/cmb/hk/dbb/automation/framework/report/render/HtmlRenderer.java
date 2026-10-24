package com.hsbc.cmb.hk.dbb.automation.framework.report.render;

import com.hsbc.cmb.hk.dbb.automation.framework.common.reporting.ApiOperationKind;
import com.hsbc.cmb.hk.dbb.automation.framework.report.config.PwReportsConfig;
import com.hsbc.cmb.hk.dbb.automation.framework.report.console.ConsoleClassifier;
import com.hsbc.cmb.hk.dbb.automation.framework.report.event.ScrubGate;
import com.hsbc.cmb.hk.dbb.automation.framework.report.model.TestOutcome;
import com.hsbc.cmb.hk.dbb.automation.framework.report.model.TestStep;
import freemarker.template.Configuration;
import freemarker.template.Template;
import freemarker.template.TemplateException;
import freemarker.template.TemplateMethodModelEx;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * per-case HTML 渲染（FreeMarker，自有模板）——JSON 之后的降级出口（R4）。
 *
 * <p>降级路径：模板异常 / IO 失败 → {@link #minimalHtml} 兜底，保证用例永远有可看页面；
 * JSON 已在 CaseFinished 时先行落盘，HTML 丢失不损失真相源。
 */
public final class HtmlRenderer {

    private static final Logger log = LoggerFactory.getLogger(HtmlRenderer.class);

    private static final Configuration FREEMARKER = new Configuration(Configuration.VERSION_2_3_34);

    /** 复用的脱敏器（无状态、线程安全）——同时暴露给模板作 {@code ${scrub(text)}}。 */
    private static final ScrubGate SCRUB_GATE = ScrubGate.defaults();

    static {
        FREEMARKER.setClassLoaderForTemplateLoading(HtmlRenderer.class.getClassLoader(), "templates/");
        FREEMARKER.setDefaultEncoding(StandardCharsets.UTF_8.name());
        //  test-outcome.ftl 的 "Play trace" 链接用 ${outcome.tracePath?url}：?url 内置需要 output_encoding
        //    （或 url_escaping_charset）才能工作，否则渲染抛 _TemplateModelException → 整页降级 minimalHtml。
        FREEMARKER.setOutputEncoding(StandardCharsets.UTF_8.name());
        FREEMARKER.setLogTemplateExceptions(false);
        FREEMARKER.setLocale(Locale.ROOT);
        //  模板可直接调 ${scrub(text)}：把 ScrubGate（脱敏唯一实现）暴露给 FreeMarker，
        //   避免在 FTL 里另写一份敏感键正则（两份规则必然漂移）。
        //   典型用途：失败消息/堆栈里内联的 URL（含 key/token/username）。
        FREEMARKER.setSharedVariable("scrub", (TemplateMethodModelEx) args ->
                args.isEmpty() ? "" : SCRUB_GATE.scrubLine(String.valueOf(args.get(0))));
        //  模板可直接调 ${failureLines(text)}：把一条失败消息拆成可读的多行。
        //   框架的元素异常是 "原因 | page=… title=… obstruction=… | cause=…" 的三段式
        //   （见 ElementOperationSupport#buildDiagnosticMessage），整段糊在一行里既看不出重点也没法扫读；
        //   拆行后每段一行，模板再按段加样式。解析放在 Java，模板只负责渲染（与 ApiCallBlock 同源分工）。
        FREEMARKER.setSharedVariable("failureLines", (TemplateMethodModelEx) args ->
                (Object) failureLines(args.isEmpty() ? null : String.valueOf(args.get(0))));
    }

    /**
     * 失败分段符——与 {@code ElementOperationSupport} 构造诊断消息时用的分隔符逐字一致。
     *
     * <p>必须是「空格 + 竖线 + 空格」：消息体里的 HTML 片段也可能含单独竖线，但不会紧邻两侧空格成段。
     */
    static final String FAILURE_SEGMENT_SEPARATOR = " | ";

    /**
     * 把一条失败消息拆成有序的可读段（脱敏后）。
     *
     * <p>规则：按 {@value #FAILURE_SEGMENT_SEPARATOR} 切分，去掉空白段；切不出分段时返回单元素列表
     * （原文照旧，行为与改造前一致）。永不返回 null、绝不抛异常——展示层设施不能成为故障源。
     */
    static List<String> failureLines(String message) {
        if (message == null || message.isBlank()) {
            return List.of();
        }
        String scrubbed = SCRUB_GATE.scrubLine(message);
        List<String> out = new ArrayList<>();
        for (String part : scrubbed.split(java.util.regex.Pattern.quote(FAILURE_SEGMENT_SEPARATOR), -1)) {
            String trimmed = part.strip();
            if (!trimmed.isEmpty()) {
                out.add(trimmed);
            }
        }
        return out.isEmpty() ? List.of(scrubbed.strip()) : List.copyOf(out);
    }

    private HtmlRenderer() {
    }

    /** 渲染并写 {@code <dir>/<outcomeId>/<outcomeId>.html}（per-case 目录，便于截图/trace/录屏与页面同目录）；失败不抛（降级最小 HTML）。 */
    public static Path renderOutcome(Path dir, TestOutcome outcome) {
        //  2026-10-21：HTML 与 JSON 落进 per-case 子目录，与该用例的 screenshots/trace.zip/records 同目录，
        //  资源引用从“../<outcomeId>/...”回到“同目录相对路径”，目录结构自洽、易于取数/删除。
        Path caseDir = dir.resolve(outcome.getOutcomeId());
        Path target = caseDir.resolve(outcome.getOutcomeId() + ".html");
        try {
            Template template = FREEMARKER.getTemplate("test-outcome.ftl");
            String html;
            try (StringWriter sw = new StringWriter()) {
                template.process(model(outcome), sw);
                html = sw.toString();
            }
            Files.createDirectories(caseDir);
            Files.writeString(target, html, StandardCharsets.UTF_8);
        } catch (Throwable t) {
            log.warn("HTML render failed for {}, degrading to minimal page", outcome.getOutcomeId(), t);
            try {
                Files.createDirectories(caseDir);
                Files.writeString(target, minimalHtml(outcome), StandardCharsets.UTF_8);
            } catch (IOException io) {
                log.error("even minimal HTML write failed for {}", outcome.getOutcomeId(), io);
            }
        }
        return target;
    }

    /** 测试/快照门禁用：渲染为字符串（不落盘）。 */
    public static String renderToString(TestOutcome outcome) throws IOException, TemplateException {
        Template template = FREEMARKER.getTemplate("test-outcome.ftl");
        try (StringWriter sw = new StringWriter()) {
            template.process(model(outcome), sw);
            return sw.toString();
        }
    }

    /**
     * 渲染模型：{@code outcome} + 回 Feature 页的 {@code featureName/featureLink}
     * + 控制台分组（{@code consoleGroups} 用例级、{@code stepConsole} 步骤级）。
     */
    static Map<String, Object> model(TestOutcome outcome) {
        Map<String, Object> model = new LinkedHashMap<>();
        model.put("outcome", outcome);
        // 与 index / feature / 邮件报告共用同一项目名（框架配置 project.report.name）
        model.put("projectName", AggregateBuilder.projectName());
        //  顶栏时间信息（2026-10-22 用户要求"三个 report 都要"）：
        //  per-case 页的窗口就是本用例自身的开始/结束时间，generatedAt 为报告生成时刻。
        model.put("run", SummaryHtmlRenderer.runWindow(List.of(outcome)));

        String featureName = outcome.getUserStory() == null ? null : outcome.getUserStory().getStoryName();
        if (featureName != null && !featureName.isBlank()) {
            model.put("featureName", featureName);
            model.put("featureLink", ReportView.featureSlug(featureName) + ".html");
        }

        //  抓包/路由相关行（route/net）归位到发生它的步骤（见 capturedApi + 模板步骤树），
        //  底部 Console log 只保留普通 stdout/stderr（out/err），避免同一内容两处重复。
        model.put("consoleGroups",
                ConsoleClassifier.classifyResidual(mergedStdout(outcome), mergedStderr(outcome)));
        //  步骤级「Route Captured」= 该步骤内<b>实际抓到</b>的 API 调用
        //  （route 抓包上报 + 控制台 net 行），满足「在哪个步骤抓到，就在哪个步骤」。
        //  步骤级「API Request」= 业务经 RestAssured 主动发出的请求（解析成结构化卡片）。
        //  注册日志（route 行）刻意不在此列——它已由步骤内的 Route Register 面板展示。
        Map<String, List<String>> capturedLines = new LinkedHashMap<>();
        Map<String, List<ApiCallBlock>> restApi = new LinkedHashMap<>();
        //  步骤里已展示过的行（含条数）：用例级兜底区据此去重——
        //  同一批抓包可能既有结构化上报、又有控制台镜像，若两处都渲染，
        //  就会出现「步骤下 Route Captured (7)」+「页面底部又一个 Route Captured (6)」的重复观感
        //  （2026-10-21 用户反馈"多了一个 Route Captured"）。
        Map<String, Integer> shownInSteps = new LinkedHashMap<>();
        Set<String> shownRoutes = new java.util.LinkedHashSet<>();
        collectApiPanels(outcome.getTestSteps(), restApi, capturedLines, shownInSteps, shownRoutes);
        model.put("restApi", restApi);
        //  Route Captured 与 API Request 统一为结构化卡片（2026-10-22 用户要求：
        //  两者显示一致，且每条抓包之间有明显的行/卡片区分）。
        model.put("capturedApi", toCapturedBlocksByStep(capturedLines));
        //  用例级兜底（钩子等无步骤上下文时产生的调用），同样按来源拆两个口径；已展示过的不再重复。
        //  去重有两级：① 完全相同文本（控制台镜像 vs 结构化上报）；② <b>同一请求路径</b>——
        //  后者才是关键：步骤里是 {@code NET ...} 短行、用例级是 {@code [Route MONITOR RESULT] ...}
        //  长块，文本永远不同，按路径判重才能消掉"下面又多一块 Route Captured"（2026-10-22 用户反馈）。
        model.put("caseRestApi", toCallBlocks(
                removeAlreadyShown(splitApiLines(outcome.getApiOperations(), false), shownInSteps, shownRoutes)));
        model.put("caseCapturedApi", toCapturedBlocks(
                removeAlreadyShown(splitApiLines(outcome.getApiOperations(), true), shownInSteps, shownRoutes)));
        //  整个运行日志（步骤内 + 步骤外，stdout 全量 + stderr 全量）：扁平兜底视图。
        model.put("consoleLog", flatConsoleLog(outcome));
        model.put("baseHref", PwReportsConfig.baseUrl());
        model.put("traceViewerBaseUrl", PwReportsConfig.traceViewerBaseUrl());
        //  CI / 构建信息（顶栏构建链接）；per-case / feature / index / 邮件四处同源
        model.put("ci", ReportView.ciBuild());
        //  底部 "Screenshots" 标签画廊：该用例全部截图（按文档顺序），附 1-based 步骤序号；
        //  同时给出 文件名→步骤序号 的映射，供步骤树内联截图点击时定位到画廊对应步骤。
        List<Map<String, Object>> screenshots = collectScreenshots(outcome);
        model.put("screenshots", screenshots);
        return model;
    }

    /**
     * 收集用例全部截图（按<b>文档顺序</b>扁平遍历，含嵌套子步骤），附 1-based 步骤序号，
     * 供底部 "Screenshots" 标签画廊展示——用户可在画廊内按步骤序号切换查看任意一张截图。
     */
    static List<Map<String, Object>> collectScreenshots(TestOutcome outcome) {
        List<Map<String, Object>> shots = new ArrayList<>();
        int[] counter = {0};
        walkSteps(outcome.getTestSteps(), shots, counter);
        return shots;
    }

    private static void walkSteps(List<TestStep> steps, List<Map<String, Object>> shots, int[] counter) {
        if (steps == null) {
            return;
        }
        for (TestStep step : steps) {
            counter[0]++;
            String file = step.getScreenshot();
            if (file != null && !file.isBlank()) {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("index", counter[0]);
                entry.put("badge", stepBadge(counter[0]));
                entry.put("file", file);
                entry.put("description", step.getDescription());
                shots.add(entry);
            }
            walkSteps(step.getChildren(), shots, counter);
        }
    }

    /** 截图序号标签：纯数字（2026-10-22 用户反馈 ①②③ 带圈字符"有点丑"，改普通数字更清爽）。 */
    static String stepBadge(int n) {
        return String.valueOf(n);
    }

    /**
     * 整个运行日志（扁平、保持采集顺序）：stdout 全量在前、stderr 全量在后。
     *
     * <p>用于底部 "Console log" 区——用户要求「Console log 为整个运行日志，附在最下面」，
     * 故此处<b>不做</b> {@link ConsoleClassifier} 的类别切分（Route / Network 改为挂到各自步骤下）。
     * 空行跳过，避免日志区出现大片空白。
     */
    private static List<String> flatConsoleLog(TestOutcome outcome) {
        List<String> lines = new ArrayList<>();
        appendNonBlank(lines, mergedStdout(outcome));
        appendNonBlank(lines, mergedStderr(outcome));
        return lines;
    }

    /** 合并「步骤内（按步骤树顺序）+ 步骤外」全部 stdout，供底部 Console log 统一分组展示。 */
    private static List<String> mergedStdout(TestOutcome outcome) {
        List<String> lines = new ArrayList<>();
        collectStepConsole(outcome.getTestSteps(), false, lines);
        appendNonBlank(lines, outcome.getConsoleOutput());
        return lines;
    }

    /** 合并「步骤内（按步骤树顺序）+ 步骤外」全部 stderr。 */
    private static List<String> mergedStderr(TestOutcome outcome) {
        List<String> lines = new ArrayList<>();
        collectStepConsole(outcome.getTestSteps(), true, lines);
        appendNonBlank(lines, outcome.getConsoleError());
        return lines;
    }

    private static void collectStepConsole(List<TestStep> steps, boolean error, List<String> out) {
        if (steps == null) {
            return;
        }
        for (TestStep step : steps) {
            if (step == null) {
                continue;
            }
            appendNonBlank(out, error ? step.getConsoleError() : step.getConsoleOutput());
            collectStepConsole(step.getChildren(), error, out);
        }
    }

    private static void appendNonBlank(List<String> target, List<String> source) {
        if (source == null) {
            return;
        }
        for (String line : source) {
            if (line != null && !line.isBlank()) {
                target.add(line);
            }
        }
    }

    /**
     * 步骤级 API 面板数据：把 {@code apiOperations} 与 console net 行按<b>来源</b>拆进两个 map。
     *
     * <ul>
     *   <li>{@code restApi} —— RestAssured 主动请求（{@link ApiOperationKind#REST} 前缀，
     *       及历史无前缀行）→ 报告页 “API Request”；</li>
     *   <li>{@code capturedApi} —— 页面 route 抓包得到的真实调用
     *       （{@link ApiOperationKind#CAPTURED} 前缀 / {@code [Route <OP>]} 形态，加 console net 行）
     *       → 报告页 “Route Captured”。</li>
     * </ul>
     *
     * <p>拆分只按<b>行首前缀</b>判定（前缀常量由上报方共享，见 {@link ApiOperationKind}），
     * 不改 JSON schema；空列表不进 map，模板据此决定是否渲染面板。
     */
    private static void collectApiPanels(List<TestStep> steps,
                                         Map<String, List<ApiCallBlock>> restApi,
                                         Map<String, List<String>> capturedApi,
                                         Map<String, Integer> shownLines,
                                         Set<String> shownRoutes) {
        if (steps == null) {
            return;
        }
        for (TestStep step : steps) {
            if (step == null) {
                continue;
            }
            if (step.getStepId() != null) {
                List<String> rest = new ArrayList<>();
                List<String> captured = new ArrayList<>();
                appendSplitApiLines(step.getApiOperations(), rest, captured);
                //  同一步骤内，控制台 net 行与结构化 apiOperations 往往是同一批抓包的两种来源；
                //  两者文本形态不同（短行 vs 长块）→ 按<b>请求路径</b>带条数抵扣，只展示一份，
                //  同时保留"同路径确实调了多次"的多重性。
                Map<String, Integer> fromApiOps = new LinkedHashMap<>();
                for (String line : captured) {
                    String key = routeKey(line);
                    if (key != null) {
                        fromApiOps.merge(key, 1, Integer::sum);
                    }
                }
                for (Map<String, Object> group :
                        ConsoleClassifier.classifyCaptured(step.getConsoleOutput(), step.getConsoleError())) {
                    Object groupLines = group.get("lines");
                    if (groupLines instanceof List<?> netLines) {
                        for (Object line : netLines) {
                            if (!(line instanceof String text) || text.isBlank()) {
                                continue;
                            }
                            String key = routeKey(text);
                            if (key != null && consume(fromApiOps, key)) {
                                continue; // 同一请求已由结构化上报展示（信息更全），跳过控制台镜像
                            }
                            captured.add(text);
                        }
                    }
                }
                if (!rest.isEmpty()) {
                    restApi.put(step.getStepId(), toCallBlocks(rest));
                }
                if (!captured.isEmpty()) {
                    capturedApi.put(step.getStepId(), captured);
                }
                //  记入"步骤已展示"账本，供用例级兜底区去重
                for (String line : rest) {
                    shownLines.merge(line, 1, Integer::sum);
                    rememberRoute(shownRoutes, line);
                }
                for (String line : captured) {
                    shownLines.merge(line, 1, Integer::sum);
                    rememberRoute(shownRoutes, line);
                }
            }
            collectApiPanels(step.getChildren(), restApi, capturedApi, shownLines, shownRoutes);
        }
    }

    private static void rememberRoute(Set<String> shownRoutes, String line) {
        String key = routeKey(line);
        if (key != null) {
            shownRoutes.add(key);
        }
    }

    /**
     * 从一条抓包行里提取<b>请求路径</b>作为判重键（{@code /api/v2/auth/assert}）。
     *
     * <p>为什么用路径而非整行：同一批抓包在两个来源里的文本形态不同——
     * 控制台镜像为 {@code NET POST https://host/path -> 200 (86 ms)}，
     * 结构化上报为多行 {@code [Route MONITOR RESULT] https://host/path?*** - {...}}；
     * 整行比较永远不相等，只有"路径"是稳定标识。取路径还一并规避了 host 差异
     * （不同环境域名）与 query 被脱敏成 {@code ***} 的差异。</p>
     *
     * @return 路径键；行内没有 http(s) URL 时返回 {@code null}（该行不参与路径判重）
     */
    private static String routeKey(String line) {
        if (line == null || line.isEmpty()) {
            return null;
        }
        int start = indexOfIgnoreCase(line, "https://");
        int httpStart = indexOfIgnoreCase(line, "http://");
        if (start < 0 || (httpStart >= 0 && httpStart < start)) {
            start = httpStart;
        }
        if (start < 0) {
            return null;
        }
        int end = start;
        while (end < line.length()) {
            char c = line.charAt(end);
            if (Character.isWhitespace(c) || c == '"' || c == '\'' || c == '<' || c == '>' || c == ',') {
                break;
            }
            end++;
        }
        String url = line.substring(start, end);
        int query = url.indexOf('?');
        if (query >= 0) {
            url = url.substring(0, query);
        }
        int scheme = url.indexOf("://");
        if (scheme >= 0) {
            int slash = url.indexOf('/', scheme + 3);
            url = slash >= 0 ? url.substring(slash) : "/";
        }
        while (url.endsWith("*")) {
            url = url.substring(0, url.length() - 1);
        }
        return url.isEmpty() ? null : url;
    }

    private static int indexOfIgnoreCase(String text, String needle) {
        int max = text.length() - needle.length();
        for (int i = 0; i <= max; i++) {
            if (text.regionMatches(true, i, needle, 0, needle.length())) {
                return i;
            }
        }
        return -1;
    }

    /** 行 → 条数（多重集）：相同内容的多次真实调用各有配额，去重时不吞掉重复调用。 */
    private static Map<String, Integer> countLines(List<String> lines) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String line : lines) {
            if (line != null && !line.isBlank()) {
                counts.merge(line, 1, Integer::sum);
            }
        }
        return counts;
    }

    /** 若 {@code budget} 中该行仍有未消耗配额，则消耗一份并返回 {@code true}（表示"已经展示过"）。 */
    private static boolean consume(Map<String, Integer> budget, String line) {
        Integer left = budget.get(line);
        if (left == null || left <= 0) {
            return false;
        }
        budget.put(line, left - 1);
        return true;
    }

    /**
     * 去掉步骤里已经展示过的行，避免同一抓包在页面上出现两次。
     *
     * <p>两级判重：① 同一<b>请求路径</b>已在步骤里出现过 → 直接丢弃（覆盖"短行 vs 长块"两种来源）；
     * ② 完全相同的文本按多重集逐条抵扣（保留"同内容确实调了多次"的多重性）。</p>
     */
    private static List<String> removeAlreadyShown(List<String> lines, Map<String, Integer> shownLines,
                                                   Set<String> shownRoutes) {
        List<String> out = new ArrayList<>();
        for (String line : lines) {
            String key = routeKey(line);
            if (key != null && shownRoutes.contains(key)) {
                continue;
            }
            if (!consume(shownLines, line)) {
                out.add(line);
            }
        }
        return out;
    }

    /** 主动请求行 → 结构化卡片模型（模板只取值，不做解析）。 */
    private static List<ApiCallBlock> toCallBlocks(List<String> lines) {
        List<ApiCallBlock> blocks = new ArrayList<>();
        for (String line : lines) {
            blocks.add(ApiCallBlock.parse(line));
        }
        return List.copyOf(blocks);
    }

    /** 抓包行 → 结构化卡片模型（Route Captured 与 API Request 同构展示）。 */
    private static List<ApiCallBlock> toCapturedBlocks(List<String> lines) {
        List<ApiCallBlock> blocks = new ArrayList<>();
        for (String line : lines) {
            blocks.add(ApiCallBlock.parseCaptured(line));
        }
        return List.copyOf(blocks);
    }

    private static Map<String, List<ApiCallBlock>> toCapturedBlocksByStep(Map<String, List<String>> byStep) {
        Map<String, List<ApiCallBlock>> out = new LinkedHashMap<>();
        byStep.forEach((stepId, lines) -> out.put(stepId, toCapturedBlocks(lines)));
        return out;
    }

    /** 按来源前缀把一批 {@code apiOperations} 行拆到 rest / captured 两个列表。 */
    private static List<String> splitApiLines(List<String> apiOperations, boolean capturedWanted) {
        List<String> rest = new ArrayList<>();
        List<String> captured = new ArrayList<>();
        appendSplitApiLines(apiOperations, rest, captured);
        return capturedWanted ? captured : rest;
    }

    private static void appendSplitApiLines(List<String> apiOperations,
                                            List<String> rest, List<String> captured) {
        if (apiOperations == null) {
            return;
        }
        for (String line : apiOperations) {
            if (line == null || line.isBlank()) {
                continue;
            }
            (isCapturedApiLine(line) ? captured : rest).add(line);
        }
    }

    /**
     * 是否「页面 route 抓包得到的真实调用」。
     *
     * <p>判据（与上报方前缀约定一一对应）：
     * {@link ApiOperationKind#CAPTURED}（{@code NET ...}），或 {@code [Route <OP>] <url>} 形态
     * （<b>不含</b> {@code register}——那是路由注册，已由 Route Register 面板展示）。
     * 其余（含 {@link ApiOperationKind#REST} 与历史无前缀行）一律算主动请求。
     */
    private static boolean isCapturedApiLine(String line) {
        String t = line.strip();
        if (t.startsWith(ApiOperationKind.CAPTURED + " ")) {
            return true;
        }
        return t.startsWith("[Route ") && !t.contains("register");
    }

    /** 渲染降级兜底页——无模板依赖，信息量取 outcome 关键字段。 */
    public static String minimalHtml(TestOutcome outcome) {
        String result = outcome.getResult() == null ? "UNKNOWN" : outcome.getResult().name();
        StringBuilder sb = new StringBuilder();
        sb.append("<!DOCTYPE html><html lang=\"en\"><head><meta charset=\"UTF-8\">")
                .append("<title>").append(esc(outcome.getName())).append("</title>")
                .append("<link rel=\"stylesheet\" href=\"").append(cssHref()).append("\"></head><body>")
                .append("<h1 class=\"result-").append(result.toLowerCase(Locale.ROOT)).append("\">")
                .append(esc(outcome.getName())).append(" — ").append(result).append("</h1>");
        if (outcome.getTestFailureCause() != null) {
            sb.append("<pre class=\"failure\">").append(esc(outcome.getTestFailureCause().getMessage()))
                    .append("</pre>");
        }
        sb.append("<p>Full data: ").append(esc(outcome.getOutcomeId())).append(".json</p>");
        sb.append("</body></html>");
        return sb.toString();
    }

    /** 兜底页与 per-case 页同处 <runDir>/<outcomeId>/：assets 在报告根，本地回退一级；托管用 base 绝对化。 */
    private static String cssHref() {
        String base = PwReportsConfig.baseUrl();
        return (base == null || base.isEmpty()) ? "../assets/report.css" : base + "assets/report.css";
    }

    static String esc(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;");
    }
}
