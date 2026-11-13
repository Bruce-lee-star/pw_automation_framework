package com.hsbc.cmb.hk.dbb.automation.framework.web.page.element;

import com.hsbc.cmb.hk.dbb.automation.framework.web.page.binding.RoleElementBinder;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.engine.BasePage;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.AriaRole;
import org.junit.Test;
import org.mockito.Mockito;

import java.lang.reflect.Field;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link RoleElement#visible()} / {@link RoleElement#scope()} 接线测试（无浏览器）。
 *
 * <p>{@link RoleElementBinder} 的可见性收敛（{@code Locator.filter(visible)}）与父容器下钻
 *（{@code page.locator(scope).locator(child)}）均为 Playwright 标准 API（编译期已校验），其运行时收敛语义
 * 由 Playwright 保证；本类钉住「注解选项 → 定位器描述串」的映射，确保 Binder 确实把 {@code visible}/{@code scope}
 * 接入了定位器（报告与失败诊断可读、可区分）。</p>
 */
public class RoleElementBindVisibleScopeTest {

    /** 无浏览器 fake BasePage：{@code getPage()} 返回深桩 Page，避免绑定期真正解析 DOM。 */
    static class FakePage extends BasePage {
        @SuppressWarnings("unchecked")
        static final Page MOCK = mock(Page.class, RETURNS_DEEP_STUBS);

        @Override
        public Page getPage() {
            return MOCK;
        }

        @RoleElement(role = AriaRole.BUTTON, name = "Continue")
        public PageElement continueAll;

        @RoleElement(role = AriaRole.BUTTON, name = "Continue", visible = RoleElement.State.YES)
        public PageElement continueVisible;

        @RoleElement(role = AriaRole.BUTTON, name = "Continue", scope = "form.form-a")
        public PageElement continueScoped;
    }

    private static FakePage bind() throws Exception {
        FakePage fp = new FakePage();
        for (Field f : FakePage.class.getDeclaredFields()) {
            RoleElement a = f.getAnnotation(RoleElement.class);
            if (a != null) {
                new RoleElementBinder(fp).bind(f, a);
            }
        }
        return fp;
    }

    @Test
    public void visibleYes_reflectedInDescription_andAnyIsNeutral() throws Exception {
        FakePage fp = bind();
        assertTrue("visible=YES 须体现在描述串（:visible）", fp.continueVisible.getSelector().contains(":visible"));
        assertFalse("visible=ANY 默认不引入 :visible 过滤（零回归）",
                fp.continueAll.getSelector().contains(":visible"));
    }

    @Test
    public void scope_reflectedInDescription() throws Exception {
        FakePage fp = bind();
        assertTrue("scope 须体现在描述串（>scope(...)）", fp.continueScoped.getSelector().contains(">scope(form.form-a)"));
        assertFalse("无 scope 字段不应出现 >scope 标记", fp.continueAll.getSelector().contains(">scope("));
    }
}
