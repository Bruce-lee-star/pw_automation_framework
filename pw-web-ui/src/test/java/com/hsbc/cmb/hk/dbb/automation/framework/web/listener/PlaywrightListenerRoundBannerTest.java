package com.hsbc.cmb.hk.dbb.automation.framework.web.listener;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * 轮次横幅判据单测（2026-10-09 修正的验收）。
 *
 * <p>背景：原实现把"testRunFinished 之后**任意** suite 开始"一律记为
 * {@code RERUN STARTING - Round N (Maven Failsafe rerunFailingTestsCount)}，于是
 * ① 另一个 IT 套件开始（如 surefire 阶段之后再跑 failsafe，或另一个测试类/RestAssured 套件）、
 * ② 另一个插件阶段，都被误报成"失败重跑"，实际排障中被带偏（Maven 汇总明明写着 Failures: 0）。
 *
 * <p>修正后的判据：**同名 story 再现** ⇒ 同一套件被再次执行（才可能是 failsafe 重跑）；
 * **新 story** ⇒ 只是另一个套件开始，**不是**重跑。本测试直接断言该判据（不依赖日志捕获）。
 *
 * <p>夹具说明：{@code currentRunNumber} 是静态状态，故用 {@link PlaywrightListener#resetRoundStateForTest()}
 * 归零，并通过真实的 {@code testRunFinished()} 推进轮次（不碰内部实现细节）。
 */
public class PlaywrightListenerRoundBannerTest {

    private PlaywrightListener listener;

    @Before
    public void setUp() {
        PlaywrightListener.resetRoundStateForTest();
        listener = new PlaywrightListener();
    }

    @After
    public void tearDown() {
        PlaywrightListener.resetRoundStateForTest();
    }

    /** 首轮（尚未有任何 testRunFinished）不得报"新一轮"。 */
    @Test
    public void firstRunDoesNotAnnounceANewRound() {
        assertEquals("首轮不该出现轮次横幅",
                PlaywrightListener.RoundBanner.NONE, listener.classifyRoundStart("Story A"));
    }

    /** 同一轮内重复调用只允许打一次横幅（其余返回 NONE）。 */
    @Test
    public void bannerIsConsumedOnlyOncePerRound() {
        listener.testRunFinished();   // 结束首轮 ⇒ 计数 > 0

        assertEquals("本轮首次 ⇒ 语义为'新套件'",
                PlaywrightListener.RoundBanner.NEW_SUITE, listener.classifyRoundStart("Story A"));
        assertEquals("同一轮第二次 ⇒ 横幅已消费",
                PlaywrightListener.RoundBanner.NONE, listener.classifyRoundStart("Story A"));
    }

    /** 核心：同名 story 再现 ⇒ 同一套件被再次执行（才可能是 failsafe 重跑）。 */
    @Test
    public void sameSuiteAgainIsReportedAsReExecution() {
        listener.testRunFinished();
        assertEquals("第一次见到该 story ⇒ 新套件",
                PlaywrightListener.RoundBanner.NEW_SUITE, listener.classifyRoundStart("Story A"));

        listener.testRunFinished();   // 该轮结束，进入下一轮
        assertEquals("同名 story 再现 ⇒ 同一套件被再次执行（不是'新套件'）",
                PlaywrightListener.RoundBanner.SAME_SUITE_RE_EXECUTED, listener.classifyRoundStart("Story A"));
    }

    /** 核心：不同 story 出现 ⇒ 只是另一个套件开始，必须明确不是重跑。 */
    @Test
    public void differentSuiteIsNotReportedAsReExecution() {
        listener.testRunFinished();
        assertEquals(PlaywrightListener.RoundBanner.NEW_SUITE, listener.classifyRoundStart("Story A"));

        listener.testRunFinished();
        assertEquals("另一个套件开始 ⇒ 不得被当作 failsafe 重跑",
                PlaywrightListener.RoundBanner.NEW_SUITE, listener.classifyRoundStart("Story B"));

        // 与上一条形成对照：B 之后又回到 A ⇒ 才是"同名再现"
        listener.testRunFinished();
        assertEquals(PlaywrightListener.RoundBanner.SAME_SUITE_RE_EXECUTED,
                listener.classifyRoundStart("Story A"));
    }

    /** 入参不可判定时不得误报（null 安全）。 */
    @Test
    public void nullStoryNameIsNotAnAnnouncement() {
        listener.testRunFinished();
        assertEquals("story 名为 null ⇒ 不可判定，不得报任何一轮",
                PlaywrightListener.RoundBanner.NONE, listener.classifyRoundStart(null));
    }
}
