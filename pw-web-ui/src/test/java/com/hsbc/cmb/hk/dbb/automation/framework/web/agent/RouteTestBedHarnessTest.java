package com.hsbc.cmb.hk.dbb.automation.framework.web.agent;

import org.junit.After;
import org.junit.Test;

import static org.junit.Assert.fail;

/**
 * RouteTestBed 靶场沙箱护栏单元测试（A 部分）：仅校验目标主机准入策略，
 * 不触发 Playwright 启动，故不依赖真实浏览器。
 */
public class RouteTestBedHarnessTest {

    @After
    public void resetAllowList() {
        // ConfigReader 边界：clearProperty 不保证回落，须显式置默认空串
        System.setProperty(RouteTestBedHarness.ALLOWED_HOSTS_KEY, "");
    }

    @Test
    public void fileSchemeTargetIsAllowed() {
        RouteTestBedHarness.rejectNonSandboxTarget("file:///path/to/fixture.html");
    }

    @Test
    public void loopbackHostIsAllowed() {
        RouteTestBedHarness.rejectNonSandboxTarget("http://localhost:8080/app");
        RouteTestBedHarness.rejectNonSandboxTarget("http://127.0.0.1/x");
        RouteTestBedHarness.rejectNonSandboxTarget("http://[::1]:8080/x");
    }

    @Test
    public void productionHostIsRejected() {
        assertRejected("https://prod.example.com/login");
    }

    @Test
    public void blankOrNullTargetIsRejected() {
        assertRejected("");
        assertRejected(null);
    }

    @Test
    public void malformedTargetIsRejected() {
        assertRejected("not-a-uri:::");
    }

    @Test
    public void allowListHostIsAllowedAndOthersRejected() {
        System.setProperty(RouteTestBedHarness.ALLOWED_HOSTS_KEY, "sandbox.example.com, 10.0.0.5");
        RouteTestBedHarness.rejectNonSandboxTarget("https://sandbox.example.com/x");
        RouteTestBedHarness.rejectNonSandboxTarget("http://10.0.0.5/y");
        assertRejected("https://evil.example.org/z");
    }

    private void assertRejected(String target) {
        try {
            RouteTestBedHarness.rejectNonSandboxTarget(target);
            fail("expected TestBedTargetRejectedException for target=[" + target + "]");
        } catch (TestBedTargetRejectedException expected) {
            // 期望拒绝
        }
    }
}
