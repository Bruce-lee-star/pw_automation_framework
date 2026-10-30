package com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * role 复核判定：Playwright 严格模式下「单个 = 恰好 1 命中」「一组 = 命中数等于组内拾取数」。
 *
 * <p>为什么这条规则必须单独固化：把 {@code hits != 1} 一律当成"不唯一"会误杀本应作为
 * {@code PageElementList} 的 role 定位器；而放宽成 {@code hits >= 2} 又会放过"组内混入不可见元素 /
 * 页面还有未拾取同类元素"的污染场景（{@code get(i)} 索引错位）。
 */
public class RoleVerificationPolicyTest {

    @Test
    public void singleElementNeedsExactlyOneHit() {
        assertTrue(RoleVerificationPolicy.keepRole(1, 1));
        assertFalse("0 命中 ⇒ 引擎侧不成立，必须回退 CSS", RoleVerificationPolicy.keepRole(0, 1));
        assertFalse("多命中 ⇒ strict mode violation，必须回退 CSS", RoleVerificationPolicy.keepRole(2, 1));
    }

    @Test
    public void groupNeedsHitCountEqualToPickedMembers() {
        assertTrue("组内 3 个元素全被命中 ⇒ 索引可信", RoleVerificationPolicy.keepRole(3, 3));
        assertFalse("少命中 ⇒ 组内有元素不可见/不属于该 role，get(i) 会错位",
                RoleVerificationPolicy.keepRole(2, 3));
        assertFalse("多命中 ⇒ 页面还有未拾取的同类元素，get(i) 取错元素",
                RoleVerificationPolicy.keepRole(4, 3));
    }

    @Test
    public void undeterminableIsAlwaysKept() {
        assertTrue("不可判定（-1）放行：环境抖动不该把可靠产物退化成结构 CSS",
                RoleVerificationPolicy.keepRole(-1, 1));
        assertTrue(RoleVerificationPolicy.keepRole(-1, 5));
    }

    @Test
    public void nonPositiveExpectedDegradesToSingleElementSemantics() {
        assertTrue(RoleVerificationPolicy.keepRole(1, 0));
        assertFalse(RoleVerificationPolicy.keepRole(2, 0));
    }
}
