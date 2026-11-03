package com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen.codegen.binding;

import com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen.codegen.binding.FeatureGlueGenerator.FeatureGluePlan;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen.codegen.binding.FeatureGlueGenerator.FeatureGlueResult;
import org.junit.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * {@link BindingPlanJson} 契约测试：JSON → 计划 → 产物，以及必填字段 / 类型写法的拒错。
 */
public class BindingPlanJsonTest {

    private static String fixture() throws Exception {
        try (InputStream in = BindingPlanJsonTest.class
                .getResourceAsStream("/codegen/binding/role_element_login.binding.json")) {
            if (in == null) {
                throw new IllegalStateException("fixture 缺失");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    public void fixture_parses_into_runnable_artifacts() throws Exception {
        FeatureGlueResult r = FeatureGlueGenerator.generate(BindingPlanJson.parse(fixture()));

        assertEquals("role_element_login.feature", r.featureFileName);
        assertTrue(r.featureContent.contains("\n@regression @login\n"));
        assertTrue(r.featureContent.contains("\n    When I log on as \"alice\" with role \"maker\"\n"));
        assertTrue(r.glueContent.contains("@When(\"I log on as {string} with role {string}\")"));
        assertTrue(r.glueContent.contains("steps.logon(username, role);"));
    }

    @Test
    public void json_round_trip_is_stable() throws Exception {
        FeatureGluePlan first = BindingPlanJson.parse(fixture());
        String json = BindingPlanJson.toJson(first);
        FeatureGluePlan second = BindingPlanJson.parse(json);

        FeatureGlueResult a = FeatureGlueGenerator.generate(first);
        FeatureGlueResult b = FeatureGlueGenerator.generate(second);
        assertEquals("toJson → parse 后产物必须字节一致", a.featureContent, b.featureContent);
        assertEquals(a.glueContent, b.glueContent);
        assertEquals(a.featureFileName, b.featureFileName);
        assertEquals(a.glueFileName, b.glueFileName);
        assertTrue("序列化保留 version", json.contains("\"version\": 1"));
        assertTrue("序列化保留 forward", json.contains("\"forward\": true"));
    }

    @Test
    public void missing_feature_name_is_rejected() {
        assertRejected("{\"scenarios\": []}", "featureName");
    }

    @Test
    public void unknown_param_type_is_rejected() {
        assertRejected("""
                {
                  "featureName": "Bad type",
                  "stepsClass": "LoginSteps",
                  "scenarios": [{"name": "S", "steps": [
                    {"keyword": "When", "phrase": "I send {string}", "params": [{"name": "v", "type": "uuid"}], "args": ["x"]}
                  ]}]
                }
                """, "不支持的参数类型");
    }

    @Test
    public void step_without_keyword_is_rejected() {
        assertRejected("""
                {
                  "featureName": "No keyword",
                  "stepsClass": "LoginSteps",
                  "scenarios": [{"name": "S", "steps": [{"phrase": "I open the login page"}]}]
                }
                """, "keyword");
    }

    @Test
    public void scenario_without_name_is_rejected() {
        assertRejected("""
                {
                  "featureName": "No scenario name",
                  "stepsClass": "LoginSteps",
                  "scenarios": [{"steps": [{"keyword": "Given", "phrase": "I open the login page"}]}]
                }
                """, "scenario");
    }

    @Test
    public void delegate_without_forward_keeps_no_arg_call() {
        FeatureGluePlan plan = BindingPlanJson.parse("""
                {
                  "featureName": "No forward",
                  "gluePackage": "com.example.glue",
                  "stepsClass": "LoginSteps",
                  "scenarios": [{"name": "S", "steps": [
                    {"keyword": "When", "phrase": "I log on as {string}",
                     "params": [{"name": "username", "type": "string"}],
                     "args": ["alice"], "delegate": "step1"}
                  ]}]
                }
                """);

        FeatureGlueResult r = FeatureGlueGenerator.generate(plan);
        assertTrue(r.glueContent.contains("steps.step1();"));
        assertTrue("参数被吸收时给出注释", r.glueContent.contains("短语参数由 Steps 内部消化（username）"));
    }

    private static void assertRejected(String json, String expectedFragment) {
        try {
            BindingPlanJson.parse(json);
            fail("应当拒绝非法计划 JSON");
        } catch (IllegalArgumentException e) {
            assertTrue("异常信息应包含 [" + expectedFragment + "]，实际: " + e.getMessage(),
                    e.getMessage().contains(expectedFragment));
        }
    }
}
