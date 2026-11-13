package com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen;

import com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen.locator.LocatorStrategy;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen.model.CodegenEntry;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * {@link RoleRevision} 契约：ASYNC 复核失败时把条目回退成 CSS，但<b>身份字段必须原样保留</b>。
 *
 * <p>为什么不能只 new 一个 CSS 条目了事：{@code sigKey} 是录制动作与拾取条目的绑定键，改了就会
 * "Elements 有这条、Recorded Steps 却丢了动作"；{@code framePath/shadowHosts} 丢了则生成器不再拼
 * {@code frame=… :: <css>} / {@code host >>> <css>}，跨 frame、shadow 元素直接跑不通。
 */
public class RoleRevisionTest {

    private static final CodegenEntry ROLE_ENTRY = new CodegenEntry(
            "button|Save#0", "button", "Save", LocatorStrategy.ROLE_WITH_NAME, 105,
            "role=button[name=\"Save\"]", "#save", "login.save", -1,
            List.of("#outerFrame"), List.of("#host"));

    @Test
    public void revisionKeepsIdentityAndSwitchesStrategyToCss() {
        CodegenEntry revised = RoleRevision.toCss(ROLE_ENTRY);

        assertEquals("sigKey 必须稳定（录制动作靠它绑定字段）", "button|Save#0", revised.sigKey);
        assertEquals(LocatorStrategy.CSS, revised.strategy);
        assertEquals("CSS 策略下 selector 必须换成唯一化 CSS", "#save", revised.selector);
        assertEquals("#save", revised.cssSelector);
        assertEquals("role/name 保留：面板与 NLS 反查仍需要", "button", revised.role);
        assertEquals("Save", revised.name);
        assertEquals("login.save", revised.nlsKey);
        assertEquals(-1, revised.index);
        assertEquals(List.of("#outerFrame"), revised.framePath);
        assertEquals(List.of("#host"), revised.shadowHosts);
    }

    @Test
    public void emptyCssFallsBackToHtml() {
        CodegenEntry entry = new CodegenEntry("button|Save#0", "button", "Save", LocatorStrategy.ROLE, 105,
                "role=button[name=\"Save\"]", "", null, -1);

        assertEquals("与抽取阶段兜底一致：绝不给生成器一个空选择器", "html",
                RoleRevision.toCss(entry).selector);
    }

    /**
     * 回退 CSS 只改定位器，不得丢掉生成侧语义字段。
     *
     * <p>回归 P0-2：历史 {@code toCss} 走 11 参旧构造 → matchCount 强制 1、tag=null、checked=false，
     * 结果原生 {@code <select>} 退化成 {@code click()}、checkbox 反转、组元素退成单元素。
     */
    @Test
    public void revisionKeepsTagCheckedAndMatchCount() {
        CodegenEntry entry = new CodegenEntry("combobox|Province#0", "combobox", "Province",
                LocatorStrategy.ROLE, 100, "role=combobox[name=\"Province\"]", "#prov", null, 0,
                List.of(), List.of(), 3, "SELECT", true);

        CodegenEntry revised = RoleRevision.toCss(entry);

        assertEquals(LocatorStrategy.CSS, revised.strategy);
        assertEquals("#prov", revised.selector);
        assertEquals("原生 select 的 tag 必须保留", "select", revised.tag);
        assertTrue("勾选态必须保留", revised.checked);
        assertEquals("组元素命中数必须保留（否则退成单元素）", 3, revised.matchCount);
        assertEquals("sigKey 仍须稳定（动作靠它绑定字段）", entry.sigKey, revised.sigKey);
    }

    @Test
    public void nullEntryIsTolerated() {
        assertNull(RoleRevision.toCss(null));
    }

    @Test
    public void revisionDoesNotMutateOriginal() {
        CodegenEntry revised = RoleRevision.toCss(ROLE_ENTRY);
        assertNotNull("toCss 必须返回派生副本而非 null", revised);

        assertTrue("原条目必须是不可变记录，不能就地改策略",
                LocatorStrategy.ROLE_WITH_NAME.equals(ROLE_ENTRY.strategy));
    }
}
