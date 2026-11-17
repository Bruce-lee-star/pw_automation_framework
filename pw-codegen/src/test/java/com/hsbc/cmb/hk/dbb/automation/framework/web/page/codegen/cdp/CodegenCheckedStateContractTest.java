package com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen.cdp;

import com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen.LocatorCoreScript;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen.LocatorCoreScript;
import org.junit.Test;

import java.lang.reflect.Field;

import static org.junit.Assert.assertTrue;

/**
 * 勾选态事实的「双端对称性」契约（评审 N-02 / T13）。
 *
 * <p>注入脚本（{@code codegen-inject.js}）与 CDP 自包含函数（{@code CodegenCdpNodeExtractor}）
 * 是两份独立维护的 JS：只要有一端漏了 {@code aria-checked} 兜底，
 * {@code <div role="checkbox" aria-checked="true">} 就会被读成未选中，产物动作与断言同时反向。
 * 这里不执行 JS（无浏览器），但锁住两端必须同时具备同一兜底，防止未来只改一端。
 */
public class CodegenCheckedStateContractTest {

    @Test
    public void injectedFactsReadAriaChecked() throws Exception {
        String js = LocatorCoreScript.inject();
        assertTrue("注入脚本必须提供 checkedStateOf",
                js.contains("function checkedStateOf("));
        assertTrue("注入脚本必须读取 aria-checked", js.contains("aria-checked"));
        assertTrue("factsOf 必须改用 checkedStateOf，不能回退到裸 el.checked",
                js.contains("checked: checkedStateOf(el)"));
    }

    @Test
    public void cdpFactsReadTheSameFallback() throws Exception {
        Field f = CodegenCdpNodeExtractor.class.getDeclaredField("SELF_CONTAINED_GET_INFO_FN");
        f.setAccessible(true);
        String js = (String) f.get(null);

        assertTrue("CDP 抽取函数必须提供同名的 checkedStateOf",
                js.contains("function checkedStateOf("));
        assertTrue("CDP 抽取函数必须读取 aria-checked", js.contains("aria-checked"));
        assertTrue("CDP factsOf 必须改用 checkedStateOf",
                js.contains("checked: checkedStateOf(x)"));
    }

}
