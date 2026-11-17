package com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen.model;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 三态布尔：{@code Boolean} 适配与三个判定必须互斥且覆盖全部取值。
 *
 * <p>存在理由：{@code Boolean} 返回 {@code null} 会触发 SpotBugs {@code NP_BOOLEAN_RETURN_NULL}(High)，
 * 而"不知道"在 codegen 里是真实且必须保留的语义（无 extractor / 计数失败 / 页面导航中）。
 * 换成枚举后，调用方漏处理 UNKNOWN 会在编译期暴露，而不是在运行期把 UNKNOWN 悄悄当成 FALSE。
 */
public class TriStateTest {

    @Test
    public void booleanAdapterMapsNullToUnknown() {
        assertEquals(TriState.UNKNOWN, TriState.of(null));
        assertEquals(TriState.TRUE, TriState.of(Boolean.TRUE));
        assertEquals(TriState.FALSE, TriState.of(Boolean.FALSE));
    }

    @Test
    public void predicatesAreMutuallyExclusiveAndNeverTreatUnknownAsFalse() {
        assertTrue(TriState.TRUE.isTrue());
        assertFalse(TriState.TRUE.isFalse());
        assertFalse(TriState.TRUE.isUnknown());

        assertTrue(TriState.FALSE.isFalse());
        assertFalse(TriState.FALSE.isTrue());
        assertFalse(TriState.FALSE.isUnknown());

        assertTrue(TriState.UNKNOWN.isUnknown());
        assertFalse("UNKNOWN 不得被当成 TRUE", TriState.UNKNOWN.isTrue());
        assertFalse("UNKNOWN 不得被当成 FALSE", TriState.UNKNOWN.isFalse());
    }
}
