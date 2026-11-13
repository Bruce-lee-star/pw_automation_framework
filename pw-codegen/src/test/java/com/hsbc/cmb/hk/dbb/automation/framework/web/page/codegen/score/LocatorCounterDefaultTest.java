package com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen.score;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * {@link LocatorCounter} 接口默认语义：三态必须与 {@code CdpLocatorCounter#countByRole} 统一。
 *
 * <p>回归评审 P0-2：桩实现默认返回 {@code 1} 会把「复核根本没跑成」伪装成「确认唯一」，
 * 连 warn 都不会留下。改为 {@code -1} 后 {@code RoleVerificationPolicy.keepRole} 同样放行，
 * 不阻断拾取，但语义诚实。
 */
public class LocatorCounterDefaultTest {

    /** 只实现 count() 的纯 CSS 桩：countByRole 应如实报告不可判定。 */
    @Test
    public void cssOnlyStubReportsRoleCountAsUndeterminable() {
        LocatorCounter stub = css -> 1;

        assertEquals("复核没跑成 ≠ 确认唯一", -1, stub.countByRole("button", "Save"));
        assertEquals("纯 CSS 计数不受影响", 1, stub.count("[id=\"save\"]"));
    }

    /** 显式覆写的实现优先，接口默认值不得覆盖真实复核结果。 */
    @Test
    public void explicitOverrideWins() {
        LocatorCounter stub = new LocatorCounter() {
            @Override
            public int count(String selector) {
                return 1;
            }

            @Override
            public int countByRole(String role, String name) {
                return 2;
            }
        };

        assertEquals(2, stub.countByRole("button", "Save"));
    }
}
