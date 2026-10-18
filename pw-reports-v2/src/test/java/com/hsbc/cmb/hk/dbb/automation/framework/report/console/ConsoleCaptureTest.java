package com.hsbc.cmb.hk.dbb.automation.framework.report.console;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ConsoleCapture} 机制测试——tee 转发 + 按行路由到当前线程 sink。
 *
 * <p>每个用例结束还原 System.out/err（{@link ConsoleCapture#uninstall()}），避免全局替换
 * 泄漏到同 JVM 其它测试类。
 */
class ConsoleCaptureTest {

    @AfterEach
    void tearDown() {
        ConsoleCapture.clear();
        ConsoleCapture.uninstall();
    }

    @Test
    @DisplayName("install 幂等：重复调用只生效一次")
    void installIsIdempotent() {
        assertThat(ConsoleCapture.install()).isTrue();
        assertThat(ConsoleCapture.install()).isTrue();
        assertThat(ConsoleCapture.isInstalled()).isTrue();
    }

    @Test
    @DisplayName("安装后 stdout/stderr 行被路由到当前线程 sink，且原始流仍照常输出")
    void routesLinesToSink() {
        assertThat(ConsoleCapture.install()).isTrue();
        List<String> out = new CopyOnWriteArrayList<>();
        List<String> err = new CopyOnWriteArrayList<>();
        ConsoleCapture.push((isError, line) -> (isError ? err : out).add(line));
        try {
            System.out.println("hello-stdout");
            System.err.println("hello-stderr");
            System.out.println("first-line");
            System.out.println("second-line");
        } finally {
            ConsoleCapture.pop();
        }
        assertThat(out).contains("hello-stdout");
        assertThat(err).contains("hello-stderr");
        assertThat(out).contains("first-line").contains("second-line");
    }

    @Test
    @DisplayName("无 sink 时只转发、不抛异常（零副作用）")
    void noSinkIsNoOp() {
        assertThat(ConsoleCapture.install()).isTrue();
        // 当前线程无 sink，下列调用不得抛
        System.out.println("bare-out");
        System.err.println("bare-err");
    }

    @Test
    @DisplayName("sink 抛异常不影响调用方与原始流")
    void sinkExceptionIsSwallowed() {
        assertThat(ConsoleCapture.install()).isTrue();
        ConsoleCapture.push((isError, line) -> {
            throw new RuntimeException("boom");
        });
        try {
            System.out.println("after-bad-sink");
        } finally {
            ConsoleCapture.pop();
        }
        // 到这里没抛即通过
    }
}
