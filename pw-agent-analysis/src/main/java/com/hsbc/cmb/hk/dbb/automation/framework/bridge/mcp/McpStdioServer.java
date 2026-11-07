package com.hsbc.cmb.hk.dbb.automation.framework.bridge.mcp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

/**
 * IDE 入口协议桥（T13）：MCP over <b>stdio</b>，newline-delimited JSON 帧。
 *
 * <p><b>stdout 被协议独占</b>：本进程里只有 {@link #run} 的一处 {@code out.println} 写协议帧，
 * 全部诊断走 stderr。{@link #main} 还会做两件防御：① 协议输出绑定显式 UTF-8 的
 * {@link FileDescriptor#out}（不依赖 Windows 平台默认编码）；② 构造后立即把全局
 * {@code System.out} 指向 stderr，使任何后续第三方 {@code System.out.println} /
 * 默认 ConsoleAppender 都不会污染 JSON 帧。桥接层自身不 import slf4j，
 * CLI 的 stdout 也已被 {@link SkeletonToolAdapter} 收进内存流。</p>
 *
 * <p><b>框架仍零 LLM / 零凭据 / 零出网</b>：本类不发起补全、不读凭据、不创建任何网络连接；
 * 一次 IDE 会话里的「提示词 → 助手建议 → 门禁校验 → StepPlan」由 client 侧的助手补全，
 * 服务端只做协议桥与确定性校验。唯一服务端配置是已批准来源白名单
 * （{@code -Dpw.mcp.approved.origins} 或 {@code PW_MCP_APPROVED_ORIGINS}），未配置时
 * {@code check} 工具 fail-closed 拒绝。</p>
 *
 * <p>启动方式（IDE MCP client 配置 command/args）：
 * {@code java -cp "<本模块 jar>:<pw-core jar>:<jackson jars>:<slf4j jars>"}
 * {@code -Dpw.mcp.approved.origins=codegen}
 * {@code com.hsbc.cmb.hk.dbb.automation.framework.bridge.mcp.McpStdioServer}。</p>
 */
public final class McpStdioServer {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private McpStdioServer() {
    }

    /** stdio 进程入口：不调用 {@code System.exit}，由 stdin 关闭决定进程生命周期。 */
    public static void main(String[] args) throws IOException {
        PrintStream protocolOut = new PrintStream(
                new FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8);
        PrintStream diagnostics = new PrintStream(
                new FileOutputStream(FileDescriptor.err), true, StandardCharsets.UTF_8);
        System.setOut(diagnostics);
        System.setErr(diagnostics);
        run(System.in, protocolOut, diagnostics);
    }

    /** 可测入口：内存流对跑，不触碰全局 System.out / System.in。 */
    public static int run(InputStream in, PrintStream out, PrintStream err) throws IOException {
        return run(in, out, err, new SkeletonToolAdapter());
    }

    /** 可注入适配层（测试用）。返回已处理帧数，便于断言「notification 不回帧」。 */
    public static int run(InputStream in, PrintStream out, PrintStream err,
                          SkeletonToolAdapter adapter) throws IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        int handled = 0;
        String line;
        while ((line = reader.readLine()) != null) {
            String frameLine = stripBom(line);
            if (frameLine.isBlank()) {
                continue;
            }
            JsonNode frame;
            try {
                JsonNode request = MAPPER.readTree(frameLine);
                if (request == null || !request.isObject()) {
                    frame = McpProtocol.parseError("request must be a JSON object");
                } else {
                    frame = McpProtocol.handle(request, adapter);
                }
            } catch (JsonProcessingException e) {
                frame = McpProtocol.parseError("invalid JSON request");
            } catch (RuntimeException e) {
                err.println("[mcp] internal error: " + e);
                frame = McpProtocol.error(null, McpProtocol.INTERNAL_ERROR,
                        "internal error (see server stderr)");
            }
            if (frame != null) {
                out.println(MAPPER.writeValueAsString(frame));
                out.flush();
                handled++;
            }
        }
        return handled;
    }

    /**
     * 逐行剥离 UTF-8 BOM：Windows 上的 PowerShell 7 默认 {@code $OutputEncoding} 带
     * {@code EF-BB-BF}，管道写入 native stdin 时首帧会带 BOM；MCP 的 {@code initialize}
     * 恰好是第一帧，若不剥离会直接回 {@code -32700} 并丢掉握手。
     */
    private static String stripBom(String line) {
        return !line.isEmpty() && line.charAt(0) == '\uFEFF' ? line.substring(1) : line;
    }
}
