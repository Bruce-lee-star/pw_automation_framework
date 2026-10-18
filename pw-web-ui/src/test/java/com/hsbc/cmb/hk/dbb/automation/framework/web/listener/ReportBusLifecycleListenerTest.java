package com.hsbc.cmb.hk.dbb.automation.framework.web.listener;

import com.hsbc.cmb.hk.dbb.automation.framework.report.event.ReportBus;
import com.hsbc.cmb.hk.dbb.automation.framework.report.model.TestOutcome;
import com.hsbc.cmb.hk.dbb.automation.framework.report.model.TestResult;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * A 项：{@link ReportBusLifecycleListener} 把 web / 框架-bus 生命周期桥接进 ReportBus。
 *
 * <p>门控：受 {@code pw.report.generic.bridge.enabled} 与 {@link ReportBus#getSource()} 双重控制。
 * 本测试显式开启开关并切到 GENERIC 源，验证 GENERIC 路产出 outcome；再切到 CUCUMBER 源验证 no-op
 * （杜绝与 PwReportPlugin 重复计数）。
 */
public class ReportBusLifecycleListenerTest {

    private static final String BRIDGE_FLAG = "pw.report.generic.bridge.enabled";

    private final ReportBusLifecycleListener listener = new ReportBusLifecycleListener();
    private final ReportBus bus = ReportBus.getDefault();
    private ReportBus.ReportSource savedSource;

    @Before
    public void setUp() {
        savedSource = bus.getSource();
        bus.setSource(ReportBus.ReportSource.GENERIC);
        System.setProperty(BRIDGE_FLAG, "true");
    }

    @After
    public void tearDown() {
        bus.setSource(savedSource);
        System.clearProperty(BRIDGE_FLAG);
    }

    @Test
    public void genericRunProducesOneOutcome() {
        int before = bus.sessionCount();
        listener.beforeScenario("Login as user");
        listener.beforeStep("open page");
        listener.afterStep("open page");
        listener.afterScenario("Login as user", false);

        assertEquals("GENERIC 路应产出 1 个 outcome", before + 1, bus.sessionCount());
        TestOutcome o = outcomeByName("Login as user");
        assertNotNull(o);
        assertEquals(TestResult.SUCCESS, o.getResult());
        assertNotNull(o.getUserStory());
        assertEquals("generic", o.getUserStory().getStoryName());
    }

    @Test
    public void failedScenarioMarksOutcomeFailureAndAttachesEvidence() {
        listener.beforeScenario("failing scenario");
        listener.onFailure("failing scenario", new IllegalStateException("boom"));
        listener.afterScenario("failing scenario", true);

        TestOutcome o = outcomeByName("failing scenario");
        assertNotNull(o);
        assertEquals(TestResult.FAILURE, o.getResult());
        assertTrue("onFailure 应挂失败证据", o.getEvidence().stream().anyMatch(e -> e.contains("boom")));
    }

    @Test
    public void cucumberRunDoesNotDoubleCount() {
        bus.setSource(ReportBus.ReportSource.CUCUMBER);
        int before = bus.sessionCount();
        listener.beforeScenario("cucumber scenario");
        listener.beforeStep("step-1");
        listener.afterStep("step-1");
        listener.afterScenario("cucumber scenario", false);

        assertEquals("CUCUMBER 路下监听器应为 no-op", before, bus.sessionCount());
    }

    @Test
    public void stepNestingUnderCaseHandle() {
        listener.beforeScenario("nested");
        listener.beforeStep("parent");
        listener.beforeStep("child");
        listener.afterStep("child");
        listener.afterStep("parent");
        listener.afterScenario("nested", false);

        TestOutcome o = outcomeByName("nested");
        assertNotNull(o);
        // 原生步骤栈嵌套：父步归层为 1 个顶层步，其下挂 1 个子步
        assertEquals(1, o.getTestSteps().size());
        assertEquals(1, o.getTestSteps().get(0).getChildren().size());
    }

    private TestOutcome outcomeByName(String name) {
        List<TestOutcome> all = bus.outcomeSnapshots();
        return all.stream().filter(o -> name.equals(o.getName())).reduce((a, b) -> b).orElse(null);
    }
}
