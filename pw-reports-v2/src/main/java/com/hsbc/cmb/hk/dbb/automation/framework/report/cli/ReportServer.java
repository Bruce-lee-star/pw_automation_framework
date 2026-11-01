package com.hsbc.cmb.hk.dbb.automation.framework.report.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hsbc.cmb.hk.dbb.automation.framework.report.config.PwReportsConfig;
import com.hsbc.cmb.hk.dbb.automation.framework.report.model.TestOutcome;
import com.hsbc.cmb.hk.dbb.automation.framework.report.model.TestResult;
import com.hsbc.cmb.hk.dbb.automation.framework.report.model.TestStep;
import com.hsbc.cmb.hk.dbb.automation.framework.report.render.AggregateBuilder;
import com.hsbc.cmb.hk.dbb.automation.framework.report.render.CsvWriter;
import com.hsbc.cmb.hk.dbb.automation.framework.report.render.HtmlRenderer;
import com.hsbc.cmb.hk.dbb.automation.framework.report.render.JsonWriter;
import com.hsbc.cmb.hk.dbb.automation.framework.report.render.JunitXmlWriter;
import com.hsbc.cmb.hk.dbb.automation.framework.report.render.StaticAssets;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 多项目持久报告服务。
 *
 * <p>目录布局：
 * <pre>
 *   {root}/{project}/{runId}/index.html, *.json, *.html, screenshots/...
 * </pre>
 *
 * <p>API：
 * <ul>
 *   <li>{@code GET /}                          → project/run 列表</li>
 *   <li>{@code POST /{project}/runs}            → 上传 serenity.zip，返回 runId 和 URL</li>
 *   <li>{@code GET /{project}/{runId}/...}      → 静态文件</li>
 *   <li>{@code POST /{project}/{runId}/api/patch} → 补图</li>
 *   <li>{@code POST /{project}/{runId}/api/mark-success} → 标记用例通过（非破坏性覆盖，可改备注 / 可还原）</li>
 *   <li>{@code POST /{project}/{runId}/api/mark-revert} → 撤销标记，还原回被覆盖前的原始结果</li>
 * </ul>
 */
public final class ReportServer {

    private final Path root;
    private final int port;
    private final ObjectMapper mapper = JsonWriter.mapper();

    public ReportServer(Path root, int port) {
        this.root = root;
        this.port = port;
    }

    public void start() throws IOException {
        Files.createDirectories(root);
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/", this::dispatch);
        server.setExecutor(Executors.newFixedThreadPool(4));
        server.start();
        System.out.println("Report server: http://localhost:" + port + " (root=" + root + ")");
    }

    // ---------------------------------------------------------------- routing

    private void dispatch(HttpExchange ex) throws IOException {
        //  CORS：放行公开 Trace Viewer 跨源拉取本服务托管的 trace.zip（官方 ?trace=<绝对URL> 模式需要）；
        //    预检 OPTIONS 直接 204。本地 HTTP 直连仍受 mixed-content 限制，由模板二态深链退回相对路径。
        applyCors(ex);
        if ("OPTIONS".equalsIgnoreCase(ex.getRequestMethod())) {
            ex.sendResponseHeaders(204, -1);
            ex.close();
            return;
        }
        String path = ex.getRequestURI().getPath();
        // path like /{project}/{runId}/...
        String[] parts = path.split("/", 4);
        // parts: ["", project, runId, rest]
        if (parts.length < 2 || parts[1].isEmpty()) {
            handleIndex(ex);
            return;
        }
        String project = parts[1];

        // POST /{project}/runs — upload zip
        if (parts.length == 3 && "runs".equals(parts[2]) && "POST".equalsIgnoreCase(ex.getRequestMethod())) {
            handleUpload(ex, project);
            return;
        }
        if (parts.length < 3 || parts[2].isEmpty()) {
            ex.sendResponseHeaders(404, -1);
            return;
        }
        String runId = parts[2];
        String rest = parts.length >= 4 ? parts[3] : "";

        // POST /{project}/{runId}/api/patch
        if ("api/patch".equals(rest) && "POST".equalsIgnoreCase(ex.getRequestMethod())) {
            handlePatch(ex, project, runId);
            return;
        }
        // POST /{project}/{runId}/api/mark-success
        if ("api/mark-success".equals(rest) && "POST".equalsIgnoreCase(ex.getRequestMethod())) {
            handleMarkSuccess(ex, project, runId);
            return;
        }
        // POST /{project}/{runId}/api/mark-revert
        if ("api/mark-revert".equals(rest) && "POST".equalsIgnoreCase(ex.getRequestMethod())) {
            handleMarkRevert(ex, project, runId);
            return;
        }
        // static file
        serveStatic(ex, project, runId, rest);
    }

    // ---------------------------------------------------------------- upload

    private void handleUpload(HttpExchange ex, String project) throws IOException {
        try {
            byte[] zipBytes = ex.getRequestBody().readAllBytes();
            // runId 优先取 query param ?build=123，否则用时间戳
            String query = ex.getRequestURI().getQuery();
            String build = null;
            if (query != null) {
                for (String kv : query.split("&")) {
                    if (kv.startsWith("build=")) {
                        build = kv.substring(6).replaceAll("[^a-zA-Z0-9._-]", "");
                    }
                }
            }
            String runId = (build != null && !build.isEmpty())
                    ? build
                    : ZonedDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
                      + "-" + HexFormat.of().formatHex(UUID.randomUUID().toString().getBytes(StandardCharsets.UTF_8), 0, 4);
            Path runDir = root.resolve(project).resolve(runId);
            Files.createDirectories(runDir);

            try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
                ZipEntry e;
                while ((e = zis.getNextEntry()) != null) {
                    if (e.isDirectory()) continue;
                    Path target = runDir.resolve(e.getName()).normalize();
                    if (!target.startsWith(runDir)) continue;
                    Files.createDirectories(target.getParent());
                    Files.copy(zis, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
            }

            String url = "/" + project + "/" + runId + "/";
            writeJson(ex, 200, Map.of("ok", true, "runId", runId, "url", url));
            System.out.println("[upload] " + project + "/" + runId + " (" + zipBytes.length + " bytes)");
        } catch (Exception e) {
            e.printStackTrace();
            writeJson(ex, 500, Map.of("error", e.toString()));
        }
    }

    // ---------------------------------------------------------------- patch

    private void handlePatch(HttpExchange ex, String project, String runId) throws IOException {
        Path runDir = root.resolve(project).resolve(runId);
        try {
            byte[] body = ex.getRequestBody().readAllBytes();
            @SuppressWarnings("unchecked")
            Map<String, Object> patch = mapper.readValue(body, Map.class);
            String pid = (String) patch.get("outcomeId");
            String pnote = (String) patch.getOrDefault("note", "");

            Path existingJson = runDir.resolve(pid).resolve(pid + ".json");
            if (!Files.exists(existingJson)) {
                writeJson(ex, 404, Map.of("error", "outcome not found: " + pid));
                return;
            }
            TestOutcome outcome = mapper.readValue(existingJson.toFile(), TestOutcome.class);
            outcome.getEvidence().add("[manual patch] " + (pnote.isEmpty() ? "via web" : pnote));

            Path shotDir = runDir.resolve(pid).resolve("screenshots");
            Files.createDirectories(shotDir);
            String ts = ZonedDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> shots = (List<Map<String, Object>>) patch.get("screenshots");
            int idx = 1;
            for (Map<String, Object> shot : shots) {
                String name = ts + "-" + String.format(Locale.ROOT, "%02d", idx++) + ".png";
                byte[] bytes = java.util.Base64.getDecoder().decode((String) shot.get("dataBase64"));
                Files.write(shotDir.resolve(name), bytes);
                TestStep s = new TestStep();
                s.setStepId("patch-" + ts + "-" + idx);
                s.setDescription("浏览器补图: " + shot.get("filename"));
                s.setResult(outcome.getResult() != null ? outcome.getResult() : TestResult.SUCCESS);
                s.setDuration(0);
                s.setScreenshot(name);
                outcome.getTestSteps().add(s);
            }

            JsonWriter.writeOutcome(runDir, outcome);
            reindex(runDir);
            writeJson(ex, 200, Map.of("ok", true, "outcomeId", pid, "screenshots", shots.size()));
        } catch (Exception e) {
            e.printStackTrace();
            writeJson(ex, 500, Map.of("error", e.toString()));
        }
    }

    private void handleMarkSuccess(HttpExchange ex, String project, String runId) throws IOException {
        Path runDir = root.resolve(project).resolve(runId);
        try {
            byte[] body = ex.getRequestBody().readAllBytes();
            @SuppressWarnings("unchecked")
            Map<String, Object> req = mapper.readValue(body, Map.class);
            String pid = (String) req.get("outcomeId");
            String note = (String) req.getOrDefault("note", "");

            Path existingJson = runDir.resolve(pid).resolve(pid + ".json");
            if (!Files.exists(existingJson)) {
                writeJson(ex, 404, Map.of("error", "outcome not found: " + pid));
                return;
            }
            TestOutcome outcome = mapper.readValue(existingJson.toFile(), TestOutcome.class);
            //  标记通过：非破坏性覆盖——首次标记时暂存原始结果 / 失败原因，之后可重复调用修改备注
            //  （不清原始），亦可经 /api/mark-revert 还原回原始失败态。失败步骤保留供追溯。
            if (!Boolean.TRUE.equals(outcome.getOverridden())) {
                outcome.setOriginalResult(outcome.getResult());
                outcome.setOriginalTestFailureCause(outcome.getTestFailureCause());
                outcome.setOriginalTestFailureSummary(outcome.getTestFailureSummary());
                outcome.setOriginalTestFailureClassname(outcome.getTestFailureClassname());
                outcome.setOriginalTestFailureMessage(outcome.getTestFailureMessage());
                outcome.setOverridden(true);
            }
            outcome.setResult(TestResult.SUCCESS);
            outcome.setTestFailureCause(null);
            outcome.setTestFailureSummary(null);
            outcome.setTestFailureClassname(null);
            outcome.setTestFailureMessage(null);
            outcome.setOverrideNote(note);
            outcome.getEvidence().add("[marked success]" + (note.isEmpty() ? "" : " " + note));

            JsonWriter.writeOutcome(runDir, outcome);
            reindex(runDir);
            writeJson(ex, 200, Map.of("ok", true, "outcomeId", pid));
        } catch (Exception e) {
            e.printStackTrace();
            writeJson(ex, 500, Map.of("error", e.toString()));
        }
    }

    /**
     * 撤销「标记成功」覆盖：把结果 / 失败原因恢复到被覆盖前的原始态，清除覆盖标记与备注。
     *
     * <p>因标记是非破坏性的（原始态保留在 {@code original*} 字段），此处可无损还原。
     */
    private void handleMarkRevert(HttpExchange ex, String project, String runId) throws IOException {
        Path runDir = root.resolve(project).resolve(runId);
        try {
            byte[] body = ex.getRequestBody().readAllBytes();
            @SuppressWarnings("unchecked")
            Map<String, Object> req = mapper.readValue(body, Map.class);
            String pid = (String) req.get("outcomeId");

            Path existingJson = runDir.resolve(pid).resolve(pid + ".json");
            if (!Files.exists(existingJson)) {
                writeJson(ex, 404, Map.of("error", "outcome not found: " + pid));
                return;
            }
            TestOutcome outcome = mapper.readValue(existingJson.toFile(), TestOutcome.class);
            if (!Boolean.TRUE.equals(outcome.getOverridden())) {
                writeJson(ex, 409, Map.of("error", "outcome not overridden: " + pid));
                return;
            }
            outcome.setResult(outcome.getOriginalResult());
            outcome.setTestFailureCause(outcome.getOriginalTestFailureCause());
            outcome.setTestFailureSummary(outcome.getOriginalTestFailureSummary());
            outcome.setTestFailureClassname(outcome.getOriginalTestFailureClassname());
            outcome.setTestFailureMessage(outcome.getOriginalTestFailureMessage());
            outcome.setOriginalResult(null);
            outcome.setOriginalTestFailureCause(null);
            outcome.setOriginalTestFailureSummary(null);
            outcome.setOriginalTestFailureClassname(null);
            outcome.setOriginalTestFailureMessage(null);
            outcome.setOverridden(false);
            outcome.setOverrideNote(null);
            outcome.getEvidence().add("[reverted to original result]");

            JsonWriter.writeOutcome(runDir, outcome);
            reindex(runDir);
            writeJson(ex, 200, Map.of("ok", true, "outcomeId", pid));
        } catch (Exception e) {
            e.printStackTrace();
            writeJson(ex, 500, Map.of("error", e.toString()));
        }
    }

    private void reindex(Path runDir) throws IOException {
        List<TestOutcome> all = new ArrayList<>();
        //  递归收集：per-case JSON 现位于 <runDir>/<outcomeId>/<outcomeId>.json（2026-10-21 迁移后）
        try (var paths = Files.walk(runDir)) {
            paths.filter(p -> {
                        String name = p.getFileName().toString();
                        return name.endsWith(".json") && !name.startsWith("serenity-summary")
                                && Files.isRegularFile(p);
                    })
                    .forEach(p -> {
                        try {
                            all.add(mapper.readValue(p.toFile(), TestOutcome.class));
                        } catch (Exception ignored) {
                        }
                    });
        }
        for (TestOutcome existing : all) {
            try {
                HtmlRenderer.renderOutcome(runDir, existing);
            } catch (Exception ignored) {
            }
        }
        JsonWriter.writeSummary(runDir, all);
        AggregateBuilder.writeIndex(runDir, all);
        CsvWriter.writeCsv(runDir, all);
        JunitXmlWriter.writeXml(runDir, all);
        StaticAssets.copyAssets(runDir);
    }

    // ---------------------------------------------------------------- static

    private void serveStatic(HttpExchange ex, String project, String runId, String rest) throws IOException {
        Path runDir = root.resolve(project).resolve(runId);
        if (rest.isEmpty()) rest = "index.html";
        Path file = runDir.resolve(rest).normalize();
        if (!file.startsWith(runDir) || !Files.exists(file) || Files.isDirectory(file)) {
            ex.sendResponseHeaders(404, -1);
            return;
        }
        byte[] bytes = Files.readAllBytes(file);
        ex.getResponseHeaders().set("Content-Type", contentType(file.getFileName().toString()));
        //  静态资源（含 trace.zip / 截图 / records）必须放开 CORS：托管场景下 per-case 页的
        //  `?trace=<URL>` 深链由 trace.playwright.dev（或其自托管实例）跨源拉取，
        //  浏览器要求响应带 Access-Control-Allow-Origin。此处覆盖 applyCors 的最小权限源为 `*`
        //  （静态资源只读、无凭证，放行任意源不影响 API 面；API 仍走 dispatch 里的最小权限 CORS）。
        ex.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        ex.sendResponseHeaders(200, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    // ---------------------------------------------------------------- landing

    private void handleIndex(HttpExchange ex) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("<!DOCTYPE html><html><head><meta charset='UTF-8'><title>Report Server</title>")
          .append("<style>body{font-family:sans-serif;max-width:900px;margin:40px auto;padding:0 20px}")
          .append(".proj{margin:24px 0;padding:16px;border:1px solid #e4e3dd;border-radius:8px}")
          .append("a{color:#2563eb;text-decoration:none}li{margin:4px 0}</style></head><body>")
          .append("<h1>📊 Report Server</h1>");

        try (var projects = Files.list(root)) {
            projects.filter(Files::isDirectory).sorted().forEach(p -> {
                String proj = p.getFileName().toString();
                sb.append("<div class='proj'><h2>").append(proj).append("</h2><ul>");
                try (var runs = Files.list(p)) {
                    runs.filter(Files::isDirectory)
                        .sorted(Comparator.reverseOrder())
                        .limit(20)
                        .forEach(r -> {
                            String run = r.getFileName().toString();
                            sb.append("<li><a href='/").append(proj).append("/").append(run).append("/'>")
                              .append(run).append("</a></li>");
                        });
                } catch (IOException ignored) {
                }
                sb.append("</ul></div>");
            });
        }
        sb.append("</body></html>");
        byte[] bytes = sb.toString().getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
        ex.sendResponseHeaders(200, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    // ---------------------------------------------------------------- helpers

    /**
     * 追加 CORS 响应头：放行公开 Trace Viewer（trace.playwright.dev）跨源拉取本服务托管的
     * trace.zip / 报告静态资源——对齐 Playwright 官方远程 trace 模式（{@code ?trace=<可访问URL>} + CORS）。
     * 注意：本地以 HTTP 直连时，公开 Viewer(HTTPS) 拉 HTTP 资源仍会被浏览器 mixed-content 拦截，
     * 故该场景由模板二态深链退回相对路径 + Local Network Access；CORS 主要服务于 HTTPS 托管 / 反代 / CI。
     */
    private static void applyCors(HttpExchange ex) {
        //  最小权限：仅放行 Trace Viewer 的源（默认 https://trace.playwright.dev，可经 pw.report.trace.viewer.base.url 覆盖），
        //    而非任意源（*）。Vary: Origin 防缓存错配。
        ex.getResponseHeaders().set("Access-Control-Allow-Origin", traceViewerOrigin());
        ex.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
        ex.getResponseHeaders().set("Access-Control-Allow-Headers", "*");
        ex.getResponseHeaders().set("Vary", "Origin");
    }

    /** Trace Viewer 源（scheme://host[:port]），取自 {@code pw.report.trace.viewer.base.url}；解析失败回退官方站。 */
    private static String traceViewerOrigin() {
        try {
            java.net.URI uri = java.net.URI.create(PwReportsConfig.traceViewerBaseUrl());
            if (uri.getScheme() != null && uri.getAuthority() != null) {
                return uri.getScheme() + "://" + uri.getAuthority();
            }
        } catch (IllegalArgumentException ignored) {
            // 落到默认官方站
        }
        return "https://trace.playwright.dev";
    }

    private static String contentType(String name) {
        if (name.endsWith(".html")) return "text/html; charset=utf-8";
        if (name.endsWith(".css")) return "text/css; charset=utf-8";
        if (name.endsWith(".js")) return "application/javascript";
        if (name.endsWith(".json")) return "application/json";
        if (name.endsWith(".png")) return "image/png";
        if (name.endsWith(".zip")) return "application/zip";
        return "application/octet-stream";
    }

    private void writeJson(HttpExchange ex, int code, Map<String, Object> body) throws IOException {
        byte[] bytes = mapper.writeValueAsBytes(body);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    // ---------------------------------------------------------------- main

    public static void main(String[] args) throws IOException {
        String root = "target/site/serenity";
        int port = 8080;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--root" -> root = args[++i];
                case "--dir" -> root = args[++i]; // alias
                case "--port" -> port = Integer.parseInt(args[++i]);
            }
        }
        new ReportServer(Paths.get(root), port).start();
    }
}
