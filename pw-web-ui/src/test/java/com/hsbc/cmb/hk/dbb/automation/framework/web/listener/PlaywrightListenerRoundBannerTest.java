package com.hsbc.cmb.hk.dbb.automation.framework.web.listener;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * 轮次横幅判据单测（DEV-V5：逻辑已从 ThucydidesStepsListenerAdapter 迁至 LifecycleOrchestrator）。
 *
 * <p>判据：<b>同名 feature 再现</b> ⇒ 同一套件被再次执行（才可能是 failsafe 重跑）；
 * <b>新 feature</b> ⇒ 只是另一个套件开始，<b>不是</b>重跑。首轮（尚未有任何 onTestRunFinished）不报横幅。</p>
 *
 * <p>夹具说明：{@code currentRunNumber} 为进程级静态状态，用
 * {@link LifecycleOrchestrator#resetRoundStateForTest()} 归零，并以 {@link LifecycleOrchestrator#advanceRun()}
 * 推进轮次。</p>
 */
public class PlaywrightListenerRoundBannerTest {

    @Before
    public void setUp() {
        LifecycleOrchestrator.resetRoundStateForTest();
    }

    @After
    public void tearDown() {
        LifecycleOrchestrator.resetRoundStateForTest();
    }

    /** 首轮（尚未有任何 onTestRunFinished）不得报"新一轮"。 */
    @Test
    public void firstRunDoesNotAnnounceANewRound() {
        assertEquals("首轮不该出现轮次横幅",
                LifecycleOrchestrator.RoundBanner.NONE, LifecycleOrchestrator.classifyRoundStart("Story A"));
    }

    /** 同一轮内重复调用只允许打一次横幅（其余返回 NONE）。 */
    @Test
    public void bannerIsConsumedOnlyOncePerRound() {
        LifecycleOrchestrator.advanceRun();   // 结束首轮 ⇒ 计数 > 0

        assertEquals("本轮首次 ⇒ 语义为'新套件'",
                LifecycleOrchestrator.RoundBanner.NEW_SUITE, LifecycleOrchestrator.classifyRoundStart("Story A"));
        assertEquals("同一轮第二次 ⇒ 横幅已消费",
                LifecycleOrchestrator.RoundBanner.NONE, LifecycleOrchestrator.classifyRoundStart("Story A"));
    }

    /** 核心：同名 story 再现 ⇒ 同一套件被再次执行（才可能是 failsafe 重跑）。 */
    @Test
    public void sameSuiteAgainIsReportedAsReExecution() {
        LifecycleOrchestrator.advanceRun();
        assertEquals("第一次见到该 story ⇒ 新套件",
                LifecycleOrchestrator.RoundBanner.NEW_SUITE, LifecycleOrchestrator.classifyRoundStart("Story A"));

        LifecycleOrchestrator.advanceRun();   // 该轮结束，进入下一轮
        assertEquals("同名 story 再现 ⇒ 同一套件被再次执行（不是'新套件'）",
                LifecycleOrchestrator.RoundBanner.SAME_SUITE_RE_EXECUTED, LifecycleOrchestrator.classifyRoundStart("Story A"));
    }

    /** 核心：不同 story 出现 ⇒ 只是另一个套件开始，必须明确不是重跑。 */
    @Test
    public void differentSuiteIsNotReportedAsReExecution() {
        LifecycleOrchestrator.advanceRun();
        assertEquals(LifecycleOrchestrator.RoundBanner.NEW_SUITE, LifecycleOrchestrator.classifyRoundStart("Story A"));

        LifecycleOrchestrator.advanceRun();
        assertEquals("另一个套件开始 ⇒ 不得被当作 failsafe 重跑",
                LifecycleOrchestrator.RoundBanner.NEW_SUITE, LifecycleOrchestrator.classifyRoundStart("Story B"));

        // 与上一条形成对照：B 之后又回到 A ⇒ 才是"同名再现"
        LifecycleOrchestrator.advanceRun();
        assertEquals(LifecycleOrchestrator.RoundBanner.SAME_SUITE_RE_EXECUTED,
                LifecycleOrchestrator.classifyRoundStart("Story A"));
    }

    /** 入参不可判定时不得误报（null 安全）。 */
    @Test
    public void nullStoryNameIsNotAnAnnouncement() {
        LifecycleOrchestrator.advanceRun();
        assertEquals("story 名为 null ⇒ 不可判定，不得报任何一轮",
                LifecycleOrchestrator.RoundBanner.NONE, LifecycleOrchestrator.classifyRoundStart(null));
    }
}
