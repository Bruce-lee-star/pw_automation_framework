package com.hsbc.cmb.hk.dbb.automation.framework.web.page.scan;

import com.hsbc.cmb.hk.dbb.automation.framework.web.page.scan.model.PickMode;
import com.microsoft.playwright.Page;
import org.junit.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

/**
 * CG-C2 回归：{@code pageNames} 为空 / 页面全部关闭时的<b>空集安全</b>（2026-10-10 新增）。
 *
 * <p><b>要防的缺陷</b>：调用方原先写 {@code pageNames.keySet().iterator().next()}，
 * 在"没有存活页面"的合法时刻（stop 命令、命令异常降级路径）会抛
 * {@link java.util.NoSuchElementException} —— 而该异常位于面板主循环内，
 * 会把异常升级成"点了停止却卡住/没反应"（主循环撕裂 → finally 静默关面板）。
 * 现统一改为 {@link RoleElementPicker#anyLivePage(Map)} + {@link RoleElementPicker#setPickMode}
 * 的 null 容忍契约。</p>
 *
 * <p>本模块测试类路径<b>没有 Mockito</b>（只有 JUnit），故用 {@link Proxy} 动态实现 {@link Page} 接口
 * 来伪造"存活/已关闭"两种页面。</p>
 */
public class RolePickerPageSelectionTest {

    @Test
    public void anyLivePage_isNullWhenNothingTracked() {
        assertNull("null map 必须安全", RoleElementPicker.anyLivePage(null));
        assertNull("空 map 必须安全（此前 iterator().next() 直接抛 NSE）",
                RoleElementPicker.anyLivePage(new ConcurrentHashMap<Page, String>()));

        Map<Page, String> withNullKey = new LinkedHashMap<>();
        withNullKey.put(null, "SomePage");
        assertNull("key 为 null 时不得返回该条目", RoleElementPicker.anyLivePage(withNullKey));
    }

    @Test
    public void anyLivePage_skipsClosedPages() {
        Page live = fakePage(false);
        Page closed = fakePage(true);

        Map<Page, String> onlyClosed = new LinkedHashMap<>();
        onlyClosed.put(closed, "ClosedPage");
        assertNull("只剩已关闭页面时返回 null（避免后续对死页 evaluate）",
                RoleElementPicker.anyLivePage(onlyClosed));

        Map<Page, String> withLive = new LinkedHashMap<>();
        withLive.put(closed, "ClosedPage");
        withLive.put(live, "LivePage");
        assertSame("应返回那个仍存活的页面", live, RoleElementPicker.anyLivePage(withLive));
    }

    /** {@code setPickMode} 的 null 容忍契约：无存活页面时必须安全返回，不触碰 context / 静态表。 */
    @Test
    public void setPickMode_toleratesNullPage() {
        RoleElementPicker.setPickMode(null, PickMode.IDLE, new ConcurrentHashMap<Page, String>());
        RoleElementPicker.setPickMode(null, PickMode.MANUAL, null);
    }

    /** 用动态代理伪造 Page：只实现本测试用到的 isClosed/equals/hashCode/toString。 */
    private static Page fakePage(boolean closed) {
        InvocationHandler handler = (proxy, method, args) -> {
            switch (method.getName()) {
                case "isClosed":
                    return closed;
                case "hashCode":
                    return System.identityHashCode(proxy);
                case "equals":
                    return proxy == (args == null || args.length == 0 ? null : args[0]);
                case "toString":
                    return closed ? "fakeClosedPage" : "fakeLivePage";
                default:
                    return null;
            }
        };
        return (Page) Proxy.newProxyInstance(
                RolePickerPageSelectionTest.class.getClassLoader(), new Class<?>[]{Page.class}, handler);
    }
}
