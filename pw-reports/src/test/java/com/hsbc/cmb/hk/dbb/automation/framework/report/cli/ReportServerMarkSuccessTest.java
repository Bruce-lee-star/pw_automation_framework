package com.hsbc.cmb.hk.dbb.automation.framework.report.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hsbc.cmb.hk.dbb.automation.framework.report.model.TestOutcome;
import com.hsbc.cmb.hk.dbb.automation.framework.report.model.TestResult;
import com.hsbc.cmb.hk.dbb.automation.framework.report.render.JsonWriter;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpContext;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpPrincipal;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link ReportServer#handleMarkSuccess} 契约：覆盖结果为 SUCCESS、清空失败原因/堆栈、留痕证据并重渲染。 */
public class ReportServerMarkSuccessTest {

    @Test
    void markSuccessOverridesResultAndClearsFailure() throws Exception {
        Path root = Files.createTempDirectory("rptroot");
        Path runDir = root.resolve("proj").resolve("run1");
        Files.createDirectories(runDir);

        TestOutcome o = new TestOutcome();
        o.setOutcomeId("c1");
        o.setName("c1");
        o.setResult(TestResult.FAILURE);
        o.setTestFailureSummary("boom");
        JsonWriter.writeOutcome(runDir, o);

        ReportServer server = new ReportServer(root, 0);
        FakeExchange ex = new FakeExchange(
                new ObjectMapper().writeValueAsBytes(Map.of("outcomeId", "c1", "note", "manual")));

        invoke(server, ex);
        assertEquals(200, ex.status);

        Path json = runDir.resolve("c1").resolve("c1.json");
        String raw = new String(Files.readAllBytes(json), StandardCharsets.UTF_8);
        TestOutcome after = new ObjectMapper().readValue(json.toFile(), TestOutcome.class);
        assertEquals(TestResult.SUCCESS, after.getResult());
        //  失败原因 / 堆栈应被清空（NON_NULL 省略字段；原始值已暂存到 original* 字段供还原）
        assertFalse(raw.contains("\"testFailureSummary\""), "不应残留 testFailureSummary 字段");
        assertFalse(raw.contains("\"testFailureCause\""), "不应残留 testFailureCause 字段");
        assertTrue(raw.contains("\"originalTestFailureSummary\""), "应暂存 originalTestFailureSummary");
        assertEquals("boom", after.getOriginalTestFailureSummary());
        assertTrue(after.getOverridden());
        assertTrue(after.getEvidence().stream().anyMatch(e -> e.contains("marked success")),
                "应写入标记成功证据");
    }

    @Test
    void markSuccessThenRevertRestoresOriginal() throws Exception {
        Path root = Files.createTempDirectory("rptroot3");
        Path runDir = root.resolve("proj").resolve("run1");
        Files.createDirectories(runDir);

        TestOutcome o = new TestOutcome();
        o.setOutcomeId("c1");
        o.setName("c1");
        o.setResult(TestResult.FAILURE);
        o.setTestFailureSummary("boom");
        JsonWriter.writeOutcome(runDir, o);

        ReportServer server = new ReportServer(root, 0);
        FakeExchange mark = new FakeExchange(
                new ObjectMapper().writeValueAsBytes(Map.of("outcomeId", "c1", "note", "manual")));
        invoke(server, mark);
        assertEquals(200, mark.status);

        FakeExchange revert = new FakeExchange(
                new ObjectMapper().writeValueAsBytes(Map.of("outcomeId", "c1")));
        invokeRevert(server, revert);
        assertEquals(200, revert.status);

        Path json = runDir.resolve("c1").resolve("c1.json");
        TestOutcome after = new ObjectMapper().readValue(json.toFile(), TestOutcome.class);
        //  还原：结果 / 失败摘要回到覆盖前的原始态，覆盖标记清空
        assertEquals(TestResult.FAILURE, after.getResult());
        assertEquals("boom", after.getTestFailureSummary());
        assertFalse(after.getOverridden());
        assertTrue(after.getEvidence().stream().anyMatch(e -> e.contains("reverted to original")),
                "应写入还原证据");
    }

    @Test
    void reMarkUpdatesNoteKeepsOriginal() throws Exception {
        Path root = Files.createTempDirectory("rptroot4");
        Path runDir = root.resolve("proj").resolve("run1");
        Files.createDirectories(runDir);

        TestOutcome o = new TestOutcome();
        o.setOutcomeId("c1");
        o.setName("c1");
        o.setResult(TestResult.FAILURE);
        o.setTestFailureSummary("boom");
        JsonWriter.writeOutcome(runDir, o);

        ReportServer server = new ReportServer(root, 0);
        //  首次标记
        FakeExchange first = new FakeExchange(
                new ObjectMapper().writeValueAsBytes(Map.of("outcomeId", "c1", "note", "first")));
        invoke(server, first);
        assertEquals(200, first.status);
        //  二次标记：只改备注，不破坏 original*
        FakeExchange second = new FakeExchange(
                new ObjectMapper().writeValueAsBytes(Map.of("outcomeId", "c1", "note", "second")));
        invoke(server, second);
        assertEquals(200, second.status);

        Path json = runDir.resolve("c1").resolve("c1.json");
        TestOutcome after = new ObjectMapper().readValue(json.toFile(), TestOutcome.class);
        assertEquals(TestResult.SUCCESS, after.getResult());
        assertEquals("second", after.getOverrideNote());
        assertEquals("boom", after.getOriginalTestFailureSummary());
        assertTrue(after.getOverridden());
    }

    @Test
    void revertWithoutOverrideReturns409() throws Exception {
        Path root = Files.createTempDirectory("rptroot5");
        Path runDir = root.resolve("proj").resolve("run1");
        Files.createDirectories(runDir);

        TestOutcome o = new TestOutcome();
        o.setOutcomeId("c1");
        o.setName("c1");
        o.setResult(TestResult.FAILURE);
        JsonWriter.writeOutcome(runDir, o);

        ReportServer server = new ReportServer(root, 0);
        FakeExchange ex = new FakeExchange(
                new ObjectMapper().writeValueAsBytes(Map.of("outcomeId", "c1")));
        invokeRevert(server, ex);
        assertEquals(409, ex.status);
    }

    @Test
    void markSuccessUnknownOutcomeReturns404() throws Exception {
        Path root = Files.createTempDirectory("rptroot2");
        Path runDir = root.resolve("proj").resolve("run1");
        Files.createDirectories(runDir);

        ReportServer server = new ReportServer(root, 0);
        FakeExchange ex = new FakeExchange(
                new ObjectMapper().writeValueAsBytes(Map.of("outcomeId", "missing", "note", "")));

        invoke(server, ex);
        assertEquals(404, ex.status);
    }

    private static void invoke(ReportServer server, HttpExchange ex) throws Exception {
        Method m = ReportServer.class.getDeclaredMethod(
                "handleMarkSuccess", HttpExchange.class, String.class, String.class);
        m.setAccessible(true);
        m.invoke(server, ex, "proj", "run1");
    }

    private static void invokeRevert(ReportServer server, HttpExchange ex) throws Exception {
        Method m = ReportServer.class.getDeclaredMethod(
                "handleMarkRevert", HttpExchange.class, String.class, String.class);
        m.setAccessible(true);
        m.invoke(server, ex, "proj", "run1");
    }

    /** 最小 {@link HttpExchange} 桩：仅实现 handler 实际用到的方法。 */
    private static final class FakeExchange extends HttpExchange {
        private final ByteArrayInputStream body;
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();
        private final Headers resp = new Headers();
        private int status = -1;

        FakeExchange(byte[] body) {
            this.body = new ByteArrayInputStream(body);
        }

        @Override public Headers getRequestHeaders() { return new Headers(); }
        @Override public Headers getResponseHeaders() { return resp; }
        @Override public URI getRequestURI() { return URI.create("/proj/run1/api/mark-success"); }
        @Override public String getRequestMethod() { return "POST"; }
        @Override public HttpContext getHttpContext() { return null; }
        @Override public void close() { }
        @Override public InputStream getRequestBody() { return body; }
        @Override public OutputStream getResponseBody() { return out; }
        @Override public void sendResponseHeaders(int rCode, long len) { this.status = rCode; }
        @Override public void setStreams(InputStream i, OutputStream o) { }
        @Override public void setAttribute(String name, Object value) { }
        @Override public Object getAttribute(String name) { return null; }
        @Override public HttpPrincipal getPrincipal() { return null; }
        @Override public InetSocketAddress getRemoteAddress() { return null; }
        @Override public int getResponseCode() { return status; }
        @Override public InetSocketAddress getLocalAddress() { return null; }
        @Override public String getProtocol() { return "HTTP/1.1"; }
    }
}
