package com.hsbc.cmb.hk.dbb.automation.framework.api.core.step;

import com.hsbc.cmb.hk.dbb.automation.framework.core.context.ScenarioContext;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * {@link ApiTestContext} 与 {@link ApiTestContextResolver} 的单元测试。
 *
 * <p>覆盖 init/baseStep/clear/场景戳,以及 resolver 的 name/reset/isDirty 各分支,
 * 保证 pw-api-core 覆盖率门禁(0.40)不被本次新增类拉低。
 *
 * <p>注意:{@link ApiTestContext#init()} 依赖当前线程已绑定用例({@link ScenarioContext#begin(String)}),
 * 否则场景戳写入 null 会触发 NPE;生产路径下步骤均在 {@code @Before} 绑定用例后执行,故测试需显式绑定。
 */
public class ApiTestContextLifecycleTest {

    @Before
    public void setUp() {
        ApiTestContext.clear();
        ScenarioContext.endCurrent();
    }

    @After
    public void tearDown() {
        ApiTestContext.clear();
        ScenarioContext.endCurrent();
    }

    @Test
    public void baseStep_beforeInit_throwsIllegalStateWithHint() {
        try {
            ApiTestContext.baseStep();
            fail("expected IllegalStateException");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("not been initialized"));
        }
    }

    @Test
    public void baseStepScenarioId_nullWhenUninitialized() {
        assertNull(ApiTestContext.baseStepScenarioId());
    }

    @Test
    public void init_default_storesAndReturnsSameInstance() {
        ScenarioContext.begin("s-default");
        try {
            BaseStep created = ApiTestContext.init();
            assertNotNull(created);
            assertSame(created, ApiTestContext.baseStep());
        } finally {
            ScenarioContext.end("s-default");
        }
    }

    @Test
    public void init_withNullEntity_usesDynamicConfiguration() {
        ScenarioContext.begin("s-null-entity");
        try {
            BaseStep created = ApiTestContext.init((String) null);
            assertNotNull(created);
            assertSame(created, ApiTestContext.baseStep());
        } finally {
            ScenarioContext.end("s-null-entity");
        }
    }

    @Test
    public void init_withNullEntityAndEnv_usesDynamicConfiguration() {
        ScenarioContext.begin("s-null-env");
        try {
            BaseStep created = ApiTestContext.init(null, "dev");
            assertNotNull(created);
            assertSame(created, ApiTestContext.baseStep());
        } finally {
            ScenarioContext.end("s-null-env");
        }
    }

    @Test
    public void clear_removesBaseStepAndStamp() {
        ScenarioContext.begin("s-clear");
        try {
            ApiTestContext.init();
            ApiTestContext.clear();
            assertNull(ApiTestContext.baseStepScenarioId());
            try {
                ApiTestContext.baseStep();
                fail("expected IllegalStateException after clear");
            } catch (IllegalStateException expected) {
                // expected
            }
        } finally {
            ScenarioContext.end("s-clear");
        }
    }

    @Test
    public void baseStepScenarioId_recordsBoundScenarioId() {
        ScenarioContext.begin("scenario-A");
        try {
            ApiTestContext.init();
            assertEquals("scenario-A", ApiTestContext.baseStepScenarioId());
        } finally {
            ScenarioContext.end("scenario-A");
        }
    }

    @Test
    public void resolver_name_isApiBaseStep() {
        assertEquals("api.baseStep", new ApiTestContextResolver().name());
    }

    @Test
    public void resolver_reset_clearsState() {
        ScenarioContext.begin("s-reset");
        try {
            ApiTestContext.init();
            new ApiTestContextResolver().reset();
            assertNull(ApiTestContext.baseStepScenarioId());
        } finally {
            ScenarioContext.end("s-reset");
        }
    }

    @Test
    public void resolver_isDirty_falseWhenNoBaseStep() {
        assertFalse(new ApiTestContextResolver().isDirty());
    }

    @Test
    public void resolver_isDirty_falseWhenScenarioStampMatches() {
        ScenarioContext.begin("scenario-B");
        try {
            ApiTestContext.init();
            assertFalse(new ApiTestContextResolver().isDirty());
        } finally {
            ScenarioContext.end("scenario-B");
        }
    }
}
