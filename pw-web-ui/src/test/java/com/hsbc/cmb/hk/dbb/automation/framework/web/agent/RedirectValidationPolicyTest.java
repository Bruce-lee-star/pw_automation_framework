package com.hsbc.cmb.hk.dbb.automation.framework.web.agent;

import org.junit.After;
import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * R3 每跳重定向校验策略单元测试（浏览器无关，不触发 Playwright）。
 *
 * <p>覆盖：沙箱主机判定（file / loopback / allow-list / fail-closed）、非法输入降级，
 * 以及逐跳重定向链的逃逸检测（首跳逃逸 / 中间跳逃逸 / 全链沙箱 / 空链 fail-closed）。</p>
 */
public class RedirectValidationPolicyTest {

    @After
    public void resetAllowList() {
        // ConfigReader 边界：clearProperty 不保证回落，须显式置默认空串
        System.setProperty(RouteTestBedHarness.ALLOWED_HOSTS_KEY, "");
    }

    // ===== isSandboxUrl：允许的类别 =====

    @Test
    public void fileSchemeIsSandbox() {
        assertTrue(RedirectValidationPolicy.isSandboxUrl("file:///path/to/fixture.html"));
    }

    @Test
    public void loopbackHostsAreSandbox() {
        assertTrue(RedirectValidationPolicy.isSandboxUrl("http://localhost:8080/app"));
        assertTrue(RedirectValidationPolicy.isSandboxUrl("http://127.0.0.1/x"));
        assertTrue(RedirectValidationPolicy.isSandboxUrl("http://[::1]:8080/x"));
    }

    @Test
    public void allowedHostIsSandboxAndOthersDenied() {
        System.setProperty(RouteTestBedHarness.ALLOWED_HOSTS_KEY, "sandbox.example.com, 10.0.0.5");
        assertTrue(RedirectValidationPolicy.isSandboxUrl("https://sandbox.example.com/x"));
        assertTrue(RedirectValidationPolicy.isSandboxUrl("http://10.0.0.5/y"));
        assertFalse(RedirectValidationPolicy.isSandboxUrl("https://evil.example.org/z"));
    }

    // ===== isSandboxUrl：fail-closed 与非法输入 =====

    @Test
    public void blankOrNullIsDenied() {
        assertFalse(RedirectValidationPolicy.isSandboxUrl(""));
        assertFalse(RedirectValidationPolicy.isSandboxUrl(null));
    }

    @Test
    public void malformedUrlIsDenied() {
        assertFalse(RedirectValidationPolicy.isSandboxUrl("not-a-uri:::"));
    }

    @Test
    public void productionHostDeniedWhenAllowListEmpty() {
        // allow-list 默认空：非 file / 非 loopback 一律拒绝（fail-closed）
        assertFalse(RedirectValidationPolicy.isSandboxUrl("https://prod.example.com/login"));
    }

    // ===== firstEscapeHop：逐跳逃逸检测 =====

    @Test
    public void allSandboxChainHasNoEscape() {
        List<String> chain = Arrays.asList(
                "http://localhost:8080/start",
                "http://localhost:8080/step2",
                "http://localhost:8080/end");
        assertEquals(-1, RedirectValidationPolicy.firstEscapeHop(chain));
    }

    @Test
    public void middleHopEscapeDetected() {
        List<String> chain = Arrays.asList(
                "http://localhost:8080/start",
                "https://evil.example.org/exfiltrate",
                "http://localhost:8080/end");
        assertEquals(1, RedirectValidationPolicy.firstEscapeHop(chain));
    }

    @Test
    public void firstHopEscapeDetected() {
        List<String> chain = Arrays.asList(
                "https://evil.example.org/start",
                "http://localhost:8080/next");
        assertEquals(0, RedirectValidationPolicy.firstEscapeHop(chain));
    }

    @Test
    public void nullChainIsEscapeFailClosed() {
        assertEquals(0, RedirectValidationPolicy.firstEscapeHop(null));
    }

    @Test
    public void guardModeDefaultsToWarn() {
        System.setProperty(RedirectValidationPolicy.REDIRECT_GUARD_KEY, "");
        assertEquals(RedirectValidationPolicy.RedirectGuardMode.WARN, RedirectValidationPolicy.guardMode());
        System.setProperty(RedirectValidationPolicy.REDIRECT_GUARD_KEY, "anything");
        assertEquals(RedirectValidationPolicy.RedirectGuardMode.WARN, RedirectValidationPolicy.guardMode());
    }

    @Test
    public void guardModeStrictWhenExplicit() {
        System.setProperty(RedirectValidationPolicy.REDIRECT_GUARD_KEY, "strict");
        assertEquals(RedirectValidationPolicy.RedirectGuardMode.STRICT, RedirectValidationPolicy.guardMode());
    }

    @Test
    public void chainWithAllowedHostAndEscape() {
        System.setProperty(RouteTestBedHarness.ALLOWED_HOSTS_KEY, "sandbox.example.com");
        List<String> chain = Arrays.asList(
                "https://sandbox.example.com/a",
                "http://127.0.0.1/b",
                "https://evil.example.org/c");
        assertEquals(2, RedirectValidationPolicy.firstEscapeHop(chain));
    }
}
