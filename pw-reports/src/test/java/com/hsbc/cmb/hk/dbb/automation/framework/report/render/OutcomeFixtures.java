package com.hsbc.cmb.hk.dbb.automation.framework.report.render;

import com.hsbc.cmb.hk.dbb.automation.framework.report.model.FailureCause;
import com.hsbc.cmb.hk.dbb.automation.framework.report.model.TestOutcome;
import com.hsbc.cmb.hk.dbb.automation.framework.report.model.TestResult;
import com.hsbc.cmb.hk.dbb.automation.framework.report.model.TestStep;
import com.hsbc.cmb.hk.dbb.automation.framework.report.model.UserStory;

import java.util.List;

/**
 * 渲染门禁（SnapshotGateTest / SchemaContractTest）共用的全量填充 fixture——
 * 覆盖模板与 schema 的每个分支：嵌套步骤、截图、证据、异常、用例级失败双口径、
 * null result（UNKNOWN 分支）、tags、userStory、apiOperations。
 *
 * <p>修改本 fixture 属于模板/契约的故意变更：必须用
 * {@code -Dpw.report.test.write-snapshot=true} 重新冻结 HTML 快照。
 */
final class OutcomeFixtures {

    private OutcomeFixtures() {
    }

    static TestOutcome full() {
        TestOutcome o = new TestOutcome();
        o.setOutcomeId("login-12-user-logs-in-with-valid-credentials");
        o.setName("User logs in with valid credentials");
        o.setUserStory(new UserStory("Authentication", "Authentication", "features/login.feature"));
        o.setTags(List.of("smoke", "login"));
        o.setResult(TestResult.FAILURE);
        o.setStartTime("2026-10-15T10:15:30.123+08:00");
        o.setEndTime("2026-10-15T10:15:31.456+08:00");
        o.setDuration(1333);

        // 根 1：成功步骤，带截图 + 两个子步骤（其中一个带证据）
        TestStep open = step("s1", "open the login page", TestResult.SUCCESS, 210);
        open.setScreenshot("step-001.png");
        open.getChildren().add(step("s2", "enter username", TestResult.SUCCESS, 55));
        TestStep pwd = step("s3", "enter password", TestResult.SUCCESS, 48);
        pwd.getEvidence().add("password field masked");
        open.getChildren().add(pwd);

        // 根 2：失败步骤，带截图 + 异常 + 证据
        TestStep signIn = step("s4", "click sign in", TestResult.FAILURE, 1020);
        signIn.setScreenshot("step-002.png");
        signIn.setException(new FailureCause("expected status 200 but was 401", "FAILURE"));
        signIn.getEvidence().add("POST /api/session?token=*** -> 401");
        //  主动请求（REST，管道分隔多行块 → 渲染成 api-card 结构化卡片）→ 进 API Request 面板
        signIn.getApiOperations().add("REST|POST|https://api.example.com/api/session|401|86 ms\n"
                + "REQH|Request headers|Content-Type=application/json; Accept=application/json\n"
                + "REQB|Request body|{\"user\":\"wp7uat2\"}\n"
                + "RESH|Response headers|Content-Type=application/json\n"
                + "RESB|Response body|{\"error\":\"invalid credentials\"}");
        //  步骤级 route 抓包（页面真实调用）→ 进 Route Captured 面板。
        //  两者同字段、按来源前缀分拣（见 HtmlRenderer.isCapturedApiLine）。
        signIn.getApiOperations().add("NET GET https://api.example.com/profile/list -> 200 (18 ms)");
        //  步骤级 Route 注册：注册发生在哪个步骤就附在哪个步骤下（覆盖步骤级 routeRegistrations 路径）
        signIn.getRouteRegistrations().add(
                "[Route] register POST route for '/api/session' (mock: status=200 intercept=REPLACE)");

        // 根 3：未完成步骤（result=null）——覆盖模板 UNKNOWN 分支
        TestStep unfinished = step("s5", "cleanup session", null, 0);

        o.setTestSteps(List.of(open, signIn, unfinished));
        o.setEvidence(List.of("WARNING: hook failed: teardown timed out"));
        o.setApiOperations(List.of("GET /api/health 200", "POST /api/session 401"));
        o.setRouteRegistrations(List.of(
                "[Route] register GET route for '/api/health' (monitor: timeout=30s)"));
        //  trace 卡片：路径 + 录制窗口 + 时长（由 ScenarioTraceRecorder 随 trace 上报）
        o.setTracePath("login-12-x/trace.zip");
        o.setTraceStartTime("10:15:30.100");
        o.setTraceEndTime("10:16:00.291");
        o.setTraceDurationMs(30191);
        o.setTestFailureCause(new FailureCause("expected status 200 but was 401", "FAILURE"));
        o.setTestFailureClassname("java.lang.AssertionError");
        o.setTestFailureMessage("expected status 200 but was 401");
        o.setTestFailureSummary("java.lang.AssertionError: expected status 200 but was 401\n"
                + "\tat com.hsbc.cmb.hk.dbb.automation.framework.report.render.OutcomeFixtures.full(OutcomeFixtures.java:1)");
        return o;
    }

    static TestStep step(String stepId, String description, TestResult result, long durationMs) {
        TestStep s = new TestStep();
        s.setStepId(stepId);
        s.setDescription(description);
        s.setResult(result);
        s.setDuration(durationMs);
        s.setStartTime("2026-10-15T10:15:30.500+08:00");
        return s;
    }
}
