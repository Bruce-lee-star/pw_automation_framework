package com.hsbc.cmb.hk.dbb.automation.framework.agent.channel;

import org.junit.Test;

import java.lang.reflect.Method;

import static org.junit.Assert.assertFalse;

/**
 * R2 红线门禁：{@link BrowserCommandChannel} 契约面<b>不得</b>暴露 evaluate / run_code 等通用脚本入口。
 *
 * <p>agent 执行底座只提供类型化原语（snapshot/execute/executeAll），不感知 LLM、也不提供任意 JS 求值，
 * 以保证运行期零 LLM（R6）且 agent 与业务脚本执行隔离。若有人给契约加回 evaluate，本测试必须失败。</p>
 */
public class BrowserCommandChannelContractTest {

    @Test
    public void contractMustNotExposeEvaluate() {
        for (Method m : BrowserCommandChannel.class.getMethods()) {
            String name = m.getName().toLowerCase();
            assertFalse("R2 red line violated: BrowserCommandChannel must not expose evaluate/run_code, found " + m.getName(),
                    name.contains("evaluate") || name.contains("run_code") || name.contains("runcode"));
        }
    }
}
