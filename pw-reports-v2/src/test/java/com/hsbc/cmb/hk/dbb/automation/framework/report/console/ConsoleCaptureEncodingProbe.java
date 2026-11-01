package com.hsbc.cmb.hk.dbb.automation.framework.report.console;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

/** Child JVM entry point used to verify capture independently of the parent JVM's charset. */
public final class ConsoleCaptureEncodingProbe {

    private ConsoleCaptureEncodingProbe() {
    }

    public static void main(String[] args) throws Exception {
        AtomicReference<String> captured = new AtomicReference<>();
        if (!ConsoleCapture.install()) {
            throw new IllegalStateException("ConsoleCapture installation failed");
        }
        ConsoleCapture.push((isError, line) -> captured.set(line));
        try {
            System.out.println("账户处理完成");
        } finally {
            ConsoleCapture.pop();
        }
        String result = captured.get();
        if (result == null) {
            throw new IllegalStateException("No UTF-8 output was captured");
        }
        Files.writeString(Path.of(args[0]), result, StandardCharsets.UTF_8);
        ConsoleCapture.uninstall();
    }
}
