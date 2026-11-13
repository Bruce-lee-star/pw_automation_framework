package com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 实时增量生成的合并/补触发不变量（评审「实时路径竞态」）。
 *
 * <p>完整链路需要面板 + CDP 会话才能跑，这里锁定其中最关键的纯判定：
 * 「本轮渲染期间到达的请求必须被识别为 stale 并补投一轮」，
 * 以及「补投轮自身看不到更新的序号 ⇒ 收敛，不会自旋」。
 */
public class CodegenLauncherRegenTest {

    @Test
    public void requestsArrivedDuringInFlightRoundMustTriggerCompensation() {
        assertTrue("首推期间新到达 1 次拾取", CodegenLauncher.regenerationStale(5, 6));
        assertTrue("合并/复核期间又到达多次", CodegenLauncher.regenerationStale(5, 9));
    }

    @Test
    public void compensatedRoundConvergesWithoutSpinning() {
        assertFalse("已渲染到最新序号 ⇒ 无需补投", CodegenLauncher.regenerationStale(6, 6));
        assertFalse("补投轮读到的就是自己刚写的序号 ⇒ 终止", CodegenLauncher.regenerationStale(9, 9));
        assertFalse("序号回退/异常输入不得误判为 stale", CodegenLauncher.regenerationStale(10, 9));
    }
}
