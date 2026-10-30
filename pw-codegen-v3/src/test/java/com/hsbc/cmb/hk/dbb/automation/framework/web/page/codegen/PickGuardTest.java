package com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen;

import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * {@link PickGuard} 契约：同元素并发去重、失败冷却（只压 fill）、指纹口径。
 *
 * <p>背景（真实日志）：一次拾取 100~200ms 内到达的同元素事件会被重复排入（Elements 出现 #2/#3、#4/#5）；
 * 拾取失败的元素因未进入已拾取列表，会被每个输入事件反复重试（同一密码框 260ms 内 13 次）。
 */
public class PickGuardTest {

    private static final String FP = "#userName";

    @Test
    public void sameElementInFlightIsDeduplicated() {
        PickGuard guard = new PickGuard();

        assertTrue("首次应放行", guard.begin(FP, "click"));
        assertFalse("首次拾取未返回前不应再排一次", guard.begin(FP, "click"));

        guard.finish(FP, true);
        assertTrue("拾取结束后可再次抢占（重复由「已在 Elements」短路）", guard.begin(FP, "click"));
    }

    @Test
    public void failedFillEntersCooldownButClickBypasses() {
        PickGuard guard = new PickGuard(60_000L);

        assertTrue(guard.begin(FP, "fill"));
        guard.finish(FP, false);

        assertFalse("失败后连续输入不应再跑 CDP", guard.begin(FP, "fill"));
        assertTrue("click 是用户明确意图，必须允许重试", guard.begin(FP, "click"));
    }

    @Test
    public void cooldownExpires() throws InterruptedException {
        PickGuard guard = new PickGuard(30L);
        guard.begin(FP, "fill");
        guard.finish(FP, false);

        Thread.sleep(60L);

        assertTrue("冷却过期后应可重试", guard.begin(FP, "fill"));
    }

    @Test
    public void successClearsPreviousFailure() {
        PickGuard guard = new PickGuard(60_000L);
        guard.finish(FP, false);
        assertTrue(guard.coolingDown(FP));

        guard.finish(FP, true);

        assertFalse("成功应清掉历史失败", guard.coolingDown(FP));
    }

    @Test
    public void nullFingerprintAlwaysAllowed() {
        PickGuard guard = new PickGuard(60_000L);

        assertTrue(guard.begin(null, "fill"));
        guard.finish(null, false);

        assertTrue("无法识别指纹时不应阻断拾取", guard.begin(null, "fill"));
    }

    @Test
    public void fingerprintPrefersCssSelectorThenRoleNameTag() {
        Map<String, Object> withCss = new HashMap<>();
        withCss.put("cssSelector", "#userName");
        withCss.put("role", "textbox");
        assertEquals("#userName", PickGuard.fingerprint(withCss));

        Map<String, Object> withoutCss = new HashMap<>();
        withoutCss.put("cssSelector", "");
        withoutCss.put("role", "textbox");
        withoutCss.put("name", "Username");
        withoutCss.put("tag", "input");
        assertEquals("textbox|Username|input", PickGuard.fingerprint(withoutCss));

        Map<String, Object> noRole = new HashMap<>();
        noRole.put("name", "Username");
        assertEquals("generic|Username|", PickGuard.fingerprint(noRole));

        assertNull(PickGuard.fingerprint(null));
    }
}
