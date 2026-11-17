package com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen;

import com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen.model.TriState;
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

    /**
     * 复核不通过后的第二道闸门：只有"现在的 DOM 还是拾取时的 DOM"才允许把 role 改写成 CSS。
     *
     * <p>现场（HSBC 重置密码多步向导）：点 Continue 翻到下一步后上一步的按钮被 {@code display:none} 隐藏，
     * 生成时复核命中 0。这条本来完全可用的 role 定位器曾被改写成
     * {@code div:nth-of-type(5) ... button[class*='float-right']}，属把"页面翻页"误判成"定位器不可用"。
     */
    @Test
    public void multiHitIsAlwaysAmbiguousAndFallsBack() {
        assertTrue("命中>1 是真歧义（运行期 strict violation），与元素此刻是否可见无关",
                RoleVerificationPolicy.fallBackToCssAfterMismatch(2, TriState.TRUE));
        assertTrue(RoleVerificationPolicy.fallBackToCssAfterMismatch(2, TriState.FALSE));
        assertTrue(RoleVerificationPolicy.fallBackToCssAfterMismatch(2, TriState.UNKNOWN));
    }

    @Test
    public void zeroHitFallsBackOnlyWhenPickStateIsIntact() {
        assertTrue("0 命中且元素此刻仍可见 ⇒ 命中数是真实事实（名字口径不符/ARIA 隐藏），回退 CSS",
                RoleVerificationPolicy.fallBackToCssAfterMismatch(0, TriState.TRUE));
        assertFalse("0 命中且元素已消失/被隐藏 ⇒ 页面已翻页，保留 role 并标记未校验",
                RoleVerificationPolicy.fallBackToCssAfterMismatch(0, TriState.FALSE));
        assertFalse("状态不可判定（无 extractor / 计数失败）时保守保留 role：误退化比留 TODO 更伤",
                RoleVerificationPolicy.fallBackToCssAfterMismatch(0, TriState.UNKNOWN));
    }

    @Test
    public void undeterminableHitCountIsNotTreatedAsMismatch() {
        // 调用方已在更早的分支处理 hits<0（直接标 UNDETERMINABLE 并放行），此处仅固化"不误伤"。
        assertFalse(RoleVerificationPolicy.fallBackToCssAfterMismatch(-1, TriState.UNKNOWN));
        assertFalse(RoleVerificationPolicy.fallBackToCssAfterMismatch(-1, TriState.FALSE));
    }
}
