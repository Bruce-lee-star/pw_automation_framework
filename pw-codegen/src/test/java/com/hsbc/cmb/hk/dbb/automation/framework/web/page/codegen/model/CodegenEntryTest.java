package com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen.model;

import com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen.locator.LocatorStrategy;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/**
 * {@link CodegenEntry} 的不可变契约：两套定位引擎的计数各自独立，且所有 copy 方法都必须完整保留。
 *
 * <p>回归评审 P0-1：CSS 计数与 role 计数混用会产出「看起来对、跑起来错」的产物。
 */
public class CodegenEntryTest {

    private static CodegenEntry entry(String strategy, int matchCount, int roleHits) {
        return new CodegenEntry("button|Save#0", "button", "Save", strategy, 105,
                "role=button[name=\"Save\"]", "button:nth-of-type(1)", null, 0,
                List.of(), List.of(), matchCount, "button", false, roleHits);
    }

    /** 历史构造（不带 roleHits）默认 NOT_APPLICABLE，既有调用方行为零变化。 */
    @Test
    public void legacyConstructorDefaultsToNotApplicable() {
        CodegenEntry e = new CodegenEntry("button|Save#0", "button", "Save", LocatorStrategy.CSS,
                105, "css", "css", null, 0, List.of(), List.of(), 3);

        assertEquals(CodegenEntry.ROLE_HITS_NOT_APPLICABLE, e.roleHits);
        assertEquals(3, e.effectiveMatchCount());
        assertFalse(e.isRoleStrategy());
    }

    /** 最终计数必须按「实际生效的定位引擎」选源，两个引擎互不换算。 */
    @Test
    public void effectiveMatchCount_selectsTheEngineActuallyUsed() {
        CodegenEntry role = entry(LocatorStrategy.ROLE_WITH_NAME, 5, 1);
        assertEquals("role 策略读 roleHits，不得被 CSS 的 5 带偏", 1, role.effectiveMatchCount());
        assertEquals(1, role.effectiveMatchCount(true));
        assertEquals("调用方明确按 CSS 渲染时改读 matchCount", 5, role.effectiveMatchCount(false));

        CodegenEntry css = entry(LocatorStrategy.CSS, 3, CodegenEntry.ROLE_HITS_NOT_APPLICABLE);
        assertEquals(3, css.effectiveMatchCount());
    }

    /** 未复核 / 不可判定是负数，绝不能被调用方当成「恰好 1 个」。 */
    @Test
    public void unverifiedAndUndeterminableAreNeverUnique() {
        assertTrue(entry(LocatorStrategy.ROLE, 5, CodegenEntry.ROLE_HITS_UNVERIFIED)
                .effectiveMatchCount() < 0);
        assertTrue(entry(LocatorStrategy.ROLE, 5, CodegenEntry.ROLE_HITS_UNDETERMINABLE)
                .effectiveMatchCount() < 0);
    }

    /** 所有不可变 copy 都必须携带 roleHits（漏一个就会在复核链上悄悄丢状态）。 */
    @Test
    public void immutableCopiesPreserveRoleHits() {
        CodegenEntry e = entry(LocatorStrategy.ROLE, 5,
                CodegenEntry.ROLE_HITS_UNVERIFIED).withRoleHits(2);

        assertEquals(2, e.withMatchCount(9).roleHits);
        assertEquals(2, e.withChecked(true).roleHits);
        assertEquals(2, e.withLocator(LocatorStrategy.CSS, "css", "css").roleHits);
        assertEquals("回退 CSS 后 effectiveMatchCount 自动改读 matchCount",
                5, e.withLocator(LocatorStrategy.CSS, "css", "css").effectiveMatchCount());
        assertEquals(2, e.toMap().get("roleHits"));
    }

    /** 非法 roleHits 是编程错误，必须立刻抛语义化异常，而不是留下一个永远判错的条目。 */
    @Test
    public void invalidRoleHitsIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> entry(LocatorStrategy.ROLE, 1, Integer.MIN_VALUE));
    }

    /**
     * 所有 copy 都必须携带 {@code autoList}：复核链上任何一次 {@code withXxx} 丢了它，
     * 自动成组的 {@code get(index)} 就会静默退化成 {@code get(0)}（回放点错选项）。
     */
    @Test
    public void immutableCopiesPreserveAutoList() {
        CodegenEntry e = new CodegenEntry("option|Country#4", "option", "Country", LocatorStrategy.CSS,
                500, "ul > li[role=\"option\"]", "ul > li[role=\"option\"]", null, 4,
                List.of(), List.of(), 10, "li", false, CodegenEntry.ROLE_HITS_NOT_APPLICABLE, true);

        assertTrue(e.withMatchCount(9).autoList);
        assertTrue(e.withRoleHits(1).autoList);
        assertTrue(e.withLocator(LocatorStrategy.CSS, "css", "css").autoList);
        assertTrue(e.withChecked(true).autoList);
        assertEquals("面板载荷需带出该标记", true, e.toMap().get("autoList"));
        assertFalse("历史构造默认不是自动成组", entry(LocatorStrategy.CSS, 1, 0).autoList);
    }
}
