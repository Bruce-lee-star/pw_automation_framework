package com.hsbc.cmb.hk.dbb.automation.framework.web.page.element;

import com.microsoft.playwright.Locator;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.engine.BasePage;
import org.junit.Test;

import java.util.Map;
import java.util.function.Supplier;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link PageElementList#visibleSize()} / {@link PageElementList#visibleSizeStrict()} 接线测试（mock）。
 *
 * <p>钉住「列表统计 → 框架 {@link ElementVisibility} 严格可见性探针」的调用契约：
 * 遍历每个匹配项并逐元素调用 {@code ElementVisibility.isVisible / isVisibleStrict}，其返回值决定计数。
 * 宽松档（{@code isVisible}）不计 opacity:0；严格档（{@code isVisibleStrict}）额外排除 —— 通过
 * {@code ElementVisibility} 传入的 {@code opts.strict} 区分两种语义。</p>
 */
public class PageElementListVisibleSizeTest {

    @Test
    public void visibleSize_countsOnlyTrulyVisible_viaElementVisibility() {
        BasePage bp = mock(BasePage.class);
        Locator base = mock(Locator.class);
        when(base.count()).thenReturn(3);
        when(base.nth(anyInt())).thenAnswer(inv -> {
            int i = inv.getArgument(0);
            Locator nth = mock(Locator.class);
            // 仅第 0 个真正可见（其余为隐藏副本）
            when(nth.evaluate(anyString(), any())).thenReturn(i == 0);
            return nth;
        });

        PageElementList list = new PageElementList((Supplier<Locator>) () -> base, "continue-list", bp);
        assertEquals(1, list.visibleSize());
    }

    @Test
    public void visibleSizeStrict_excludesOpacityZero_whileLooseCountsIt() {
        BasePage bp = mock(BasePage.class);
        Locator base = mock(Locator.class);
        when(base.count()).thenReturn(3);
        when(base.nth(anyInt())).thenAnswer(inv -> {
            int i = inv.getArgument(0);
            Locator nth = mock(Locator.class);
            // 第 0、1 个宽松可见；第 1 个 opacity:0，严格档须排除
            when(nth.evaluate(anyString(), any())).thenAnswer(eval -> {
                Object opts = eval.getArgument(1);
                boolean strict = opts instanceof Map
                        && Boolean.TRUE.equals(((Map<?, ?>) opts).get("strict"));
                if (strict) {
                    return i == 0;            // 严格档：仅第 0 个可见
                }
                return i == 0 || i == 1;      // 宽松档：第 0、1 个可见
            });
            return nth;
        });

        PageElementList list = new PageElementList((Supplier<Locator>) () -> base, "continue-list", bp);
        assertEquals("宽松档把 opacity:0 也计入", 2, list.visibleSize());
        assertEquals("严格档排除 opacity:0", 1, list.visibleSizeStrict());
    }
}
