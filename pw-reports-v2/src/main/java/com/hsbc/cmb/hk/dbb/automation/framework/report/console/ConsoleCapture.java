package com.hsbc.cmb.hk.dbb.automation.framework.report.console;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * 控制台输出捕获——镜像 Serenity 把 stdout/stderr 按步骤归类的核心可观测能力（R3 同源：脱敏在前）。
 *
 * <p>机制：安装后把 {@code System.out} / {@code System.err} 替换为 tee 流——每个字节仍照常
 * 写回<b>原始</b>流（控制台照常可见），同时按行把内容投递给<b>当前线程栈顶</b>的
 * {@link LineSink}。{@code ReportBus} 在用例/步骤边界压栈、弹栈，从而把输出精准归属到
 * 对应步骤（步骤内的输出归步骤，步骤外、用例内的输出归用例级）。</p>
 *
 * <p>线程模型：{@code System.out/err} 是进程级共享，但行缓冲与 sink 栈都是 {@link ThreadLocal}，
 * 故并行 scenario 各线程互不串扰（Cucumber 不变式：同 scenario 同线程顺序执行）。</p>
 *
 * <p>健壮性（企业级）：
 * <ul>
 *   <li>安装幂等、全局一次；未安装或当前线程无 sink 时只转发、零副作用；</li>
 *   <li>路由全程 try/catch，sink 抛异常不影响业务输出与测试本身；</li>
 *   <li>不关闭原始流（{@code close()} 显式 no-op），避免误伤 JVM 控制台；</li>
 *   <li>行缓冲按线程隔离，无跨线程数据竞争。</li>
 * </ul>
 */
public final class ConsoleCapture {

    /** 单条输出行的消费者：{@code isError=true} 表示来自 {@code System.err}。 */
    @FunctionalInterface
    public interface LineSink {
        void accept(boolean isError, String line);
    }

    private static volatile boolean installed;
    private static volatile PrintStream originalOut;
    private static volatile PrintStream originalErr;

    private static final ThreadLocal<Deque<LineSink>> SINKS = ThreadLocal.withInitial(ArrayDeque::new);

    private ConsoleCapture() {
    }

    /** 安装 tee（幂等）。重复调用只设一次，返回是否已生效安装。 */
    public static synchronized boolean install() {
        if (installed) {
            return true;
        }
        try {
            originalOut = System.out;
            originalErr = System.err;
            System.setOut(new TeePrintStream(originalOut, false));
            System.setErr(new TeePrintStream(originalErr, true));
            installed = true;
            return true;
        } catch (Throwable t) {
            // 任何异常都回退到"不捕获"，绝不影响测试本身
            return false;
        }
    }

    /**
     * 卸载 tee 并还原原始流（仅测试用；生产路径永不调用）。
     * 幂等：未安装时静默 no-op。
     */
    public static synchronized void uninstall() {
        if (!installed) {
            return;
        }
        SINKS.remove();
        try {
            if (originalOut != null) {
                System.setOut(originalOut);
            }
            if (originalErr != null) {
                System.setErr(originalErr);
            }
        } catch (Throwable ignored) {
            // best-effort
        } finally {
            installed = false;
            originalOut = null;
            originalErr = null;
        }
    }

    public static boolean isInstalled() {
        return installed;
    }

    /** 当前线程压入一个 sink（步骤嵌套时栈式叠加，栈顶即最内层步骤）。 */
    /** 当前线程压入一个 sink（步骤嵌套时栈式叠加，栈顶即最内层步骤）。 */
    public static void push(LineSink sink) {
        if (sink == null) {
            return;
        }
        SINKS.get().push(sink);
    }

    /** 弹出当前线程栈顶 sink（与 {@link #push} 配对）。 */
    public static void pop() {
        Deque<LineSink> d = SINKS.get();
        if (!d.isEmpty()) {
            d.pop();
        }
    }

    /** 清空当前线程全部 sink（用例边界防御性回收，防跨用例泄漏）。 */
    public static void clear() {
        SINKS.get().clear();
    }

    /** 把一行投递给当前线程栈顶 sink（无 sink 则丢弃——已照常写回原始流）。 */
    static void route(boolean isError, String line) {
        Deque<LineSink> d = SINKS.get();
        LineSink sink = d.peek();
        if (sink == null) {
            return;
        }
        try {
            sink.accept(isError, line);
        } catch (Throwable ignored) {
            // sink 失败绝不外溢
        }
    }

    // ---------------------------------------------------------------- tee 流

    /** 包装原始 {@link PrintStream} 的拦截输出流：逐字节转发 + 按行路由（缓冲按线程隔离）。 */
    private static final class InterceptingOutputStream extends OutputStream {
        private final OutputStream delegate;
        private final boolean isError;
        private final ThreadLocal<ByteArrayOutputStream> buf =
                ThreadLocal.withInitial(() -> new ByteArrayOutputStream(256));

        InterceptingOutputStream(OutputStream delegate, boolean isError) {
            this.delegate = delegate;
            this.isError = isError;
        }

        @Override
        public void write(int b) throws IOException {
            ByteArrayOutputStream b0 = buf.get();
            if (b == '\n') {
                flushLine(b0);
            } else {
                b0.write(b);
            }
            delegate.write(b);
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            ByteArrayOutputStream b0 = buf.get();
            for (int i = off; i < off + len; i++) {
                int c = b[i] & 0xFF;
                if (c == '\n') {
                    flushLine(b0);
                } else {
                    b0.write(c);
                }
            }
            delegate.write(b, off, len);
        }

        @Override
        public void flush() throws IOException {
            flushLine(buf.get());
            delegate.flush();
        }

        /** close 必须 no-op：绝不能关闭原始 {@code System.out/err}。 */
        @Override
        public void close() throws IOException {
            flush();
            // 故意不调用 delegate.close()
        }

        private void flushLine(ByteArrayOutputStream b0) {
            if (b0.size() == 0) {
                return;
            }
            String line = new String(b0.toByteArray(), StandardCharsets.UTF_8);
            b0.reset();
            if (line.endsWith("\r")) {
                line = line.substring(0, line.length() - 1);
            }
            if (!line.isEmpty()) {
                route(isError, line);
            }
        }
    }

    /** tee PrintStream：autoflush，底层为 {@link InterceptingOutputStream}。 */
    private static final class TeePrintStream extends PrintStream {
        TeePrintStream(PrintStream delegate, boolean isError) {
            super(new InterceptingOutputStream(delegate, isError), true);
        }
    }
}
