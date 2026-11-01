package com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.event;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 控制台逐场景彩色 reporter 单测——断言"开始执行 + 哪条 case + 成功/失败（带颜色）"输出契约，
 * 以及失败行不泄露异常 message、关闭开关即静默。
 */
public class ConsoleScenarioListenerTest {

    private static final String PROP = "playwright.console.report.enabled";
    private static final String GREEN = "\u001B[1;32m";
    private static final String RED = "\u001B[1;31m";
    private static final String YELLOW = "\u001B[1;33m";

    private final PrintStream originalOut = System.out;
    private ByteArrayOutputStream captured;
    private String saved;

    @Before
    public void setUp() {
        saved = System.getProperty(PROP);
        System.setProperty(PROP, "true");
        captured = new ByteArrayOutputStream();
        System.setOut(new PrintStream(captured, true, StandardCharsets.UTF_8));
    }

    @After
    public void tearDown() {
        System.setOut(originalOut);
        if (saved == null) {
            System.clearProperty(PROP);
        } else {
            System.setProperty(PROP, saved);
        }
    }

    private String out() {
        return new String(captured.toByteArray(), StandardCharsets.UTF_8);
    }

    @Test
    public void printsColoredStartAndPassed() {
        ConsoleScenarioListener l = new ConsoleScenarioListener();
        l.onScenarioStarted(LifecycleScenarioInfo.builder()
                .name("testing logon to dbb-1").tags(List.of("@regression", "@test")).build());
        l.onScenarioFinished(LifecycleScenarioResult.builder()
                .info(LifecycleScenarioInfo.builder().name("testing logon to dbb-1").build())
                .status(LifecycleStatus.PASSED).build());

        String o = out();
        assertTrue(o.contains("SCENARIO STARTED"));
        assertTrue(o.contains("testing logon to dbb-1"));
        assertTrue(o.contains("@test"));
        assertTrue(o.contains("SCENARIO PASSED"));
        assertTrue(o.contains(GREEN)); // 绿色
        assertTrue(o.contains("ms)"));  // 耗时
    }

    @Test
    public void failedPrintsRedAndClassButNotMessage() {
        ConsoleScenarioListener l = new ConsoleScenarioListener();
        l.onScenarioStarted(LifecycleScenarioInfo.builder().name("s").build());
        l.onScenarioFinished(LifecycleScenarioResult.builder()
                .info(LifecycleScenarioInfo.builder().name("s").build())
                .status(LifecycleStatus.FAILED)
                .error(new IllegalStateException("secret-token-123 https://internalhost")).build());

        String o = out();
        assertTrue(o.contains("SCENARIO FAILED"));
        assertTrue(o.contains(RED));                 // 红色
        assertTrue(o.contains("IllegalStateException"));
        assertFalse("失败行不得泄露异常 message", o.contains("secret-token-123"));
        assertFalse("失败行不得泄露异常 message", o.contains("internalhost"));
    }

    @Test
    public void disabledSuppressesAllOutput() {
        System.setProperty(PROP, "false");
        ConsoleScenarioListener l = new ConsoleScenarioListener();
        l.onTestRunStarted();
        l.onScenarioStarted(LifecycleScenarioInfo.builder().name("x").build());
        l.onScenarioFinished(LifecycleScenarioResult.builder()
                .info(LifecycleScenarioInfo.builder().name("x").build())
                .status(LifecycleStatus.PASSED).build());
        l.onTestRunFinished();
        String output = out();
        assertFalse(output.contains("TEST RUN STARTED"));
        assertFalse(output.contains("SCENARIO STARTED"));
        assertFalse(output.contains("SCENARIO PASSED"));
        assertFalse(output.contains("SCENARIO FAILED"));
    }

    @Test
    public void defaultsToEnabledWhenUnset() {
        System.clearProperty(PROP);
        new ConsoleScenarioListener().onTestRunStarted();
        assertTrue(out().contains("TEST RUN STARTED"));
    }

    @Test
    public void skippedPrintsYellow() {
        ConsoleScenarioListener l = new ConsoleScenarioListener();
        l.onScenarioStarted(LifecycleScenarioInfo.builder().name("sk").build());
        l.onScenarioFinished(LifecycleScenarioResult.builder()
                .info(LifecycleScenarioInfo.builder().name("sk").build())
                .status(LifecycleStatus.SKIPPED).build());
        String o = out();
        assertTrue(o.contains("SCENARIO SKIPPED"));
        assertTrue(o.contains(YELLOW)); // 黄
    }
}
