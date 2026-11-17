package com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen.cdp;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/**
 * 拾取名字的两条判据：① 状态播报区不算标签；② 点击改写了文案就用点击前的名字。
 *
 * <p>两者都源自同一个现场：HSBC 重置密码等多步向导里，提交按钮点一下立刻变 loading，
 * 而拾取抽取是异步的（页面 click 处理器早已跑完）。
 */
public class PickNamePolicyTest {

    // ------------------------------------------------------------------
    // ① 状态播报区（aria-live）文本混进 AX 名
    // ------------------------------------------------------------------

    @Test
    public void statusAnnouncementIsStrippedFromAxName() {
        // 现场：按钮里常驻 aria-live 的 "Loading, please wait."，浏览器算出的全局名是两者拼接。
        assertEquals("Continue", PickNamePolicy.stableName(
                "Loading, please wait. Continue", "Continue", "Loading, please wait."));
    }

    @Test
    public void nameWithoutStatusRegionIsLeftToAx() {
        // 没有播报区：AX 名是权威来源，一个字都不改。
        assertEquals("Sign in", PickNamePolicy.stableName("Sign in", "Sign", ""));
        assertEquals("Sign in", PickNamePolicy.stableName("Sign in", "Sign", null));
    }

    @Test
    public void axNameIsKeptWhenRemainderDoesNotEqualPageSideLabel() {
        // 播报文本确实在 AX 名里，但删掉后剩下的不是页面侧标签 ⇒ 不是「播报 + 标签」形态，不猜。
        assertEquals("Loading, please wait. Save", PickNamePolicy.stableName(
                "Loading, please wait. Save", "Continue", "Loading, please wait."));
        // AX 名里根本没有这段播报文本（页面侧术语与 AX 不同）⇒ 不改。
        assertEquals("Continue", PickNamePolicy.stableName("Continue", "Continue now", "Loading"));
    }

    @Test
    public void missingEvidenceKeepsAxName() {
        assertNull(PickNamePolicy.stableName(null, "Continue", "Loading"));
        assertEquals("Continue", PickNamePolicy.stableName("Continue", null, "Loading"));
        assertEquals("Loading, please wait.", PickNamePolicy.stableName("Loading, please wait.", "", "Loading, please wait."));
    }

    // ------------------------------------------------------------------
    // ② 点击改写了元素自身文案 ⇒ 用捕获阶段的名字
    // ------------------------------------------------------------------

    @Test
    public void loadingStateFallsBackToPreClickName() {
        PreClickSnapshot pre = PreClickSnapshot.of("Continue", "Loading, please wait. Continue");
        assertEquals("Continue", PickNamePolicy.restorePreClickName(
                "Loading, please wait.", "Loading, please wait.", pre));
    }

    @Test
    public void unchangedOwnTextKeepsExtractedName() {
        // 文案没变 ⇒ 抽取阶段的 AX 名可信（页面侧与 AX 侧算名口径不同，不能因此覆盖权威值）。
        PreClickSnapshot pre = PreClickSnapshot.of("Continue", "Continue");
        assertEquals("Continue", PickNamePolicy.restorePreClickName("Continue", "Continue", pre));
        assertEquals("Continue to next page",
                PickNamePolicy.restorePreClickName("Continue to next page", "Continue", pre));
    }

    @Test
    public void sameNameIsNeverRewritten() {
        PreClickSnapshot pre = PreClickSnapshot.of("Continue", "Loading, please wait. Continue");
        assertEquals("Continue", PickNamePolicy.restorePreClickName("Continue", "Loading, please wait.", pre));
    }

    @Test
    public void noEvidenceKeepsExtractedName() {
        assertEquals("Loading, please wait.",
                PickNamePolicy.restorePreClickName("Loading, please wait.", "Loading, please wait.", null));
        assertEquals("Loading, please wait.", PickNamePolicy.restorePreClickName(
                "Loading, please wait.", "Loading, please wait.", PreClickSnapshot.EMPTY));
        // 捕获阶段没拿到自身文本 ⇒ 无法断言「文案被点击改写」，不还原。
        assertEquals("Loading, please wait.", PickNamePolicy.restorePreClickName(
                "Loading, please wait.", "Loading, please wait.", PreClickSnapshot.of("Continue", null)));
    }

    @Test
    public void textDisappearingCountsAsRewrite() {
        // 点完按钮只剩 spinner：自身文本变空也是「被点击改写」。
        assertEquals("Continue", PickNamePolicy.restorePreClickName(
                "", null, PreClickSnapshot.of("Continue", "Continue")));
    }
}
