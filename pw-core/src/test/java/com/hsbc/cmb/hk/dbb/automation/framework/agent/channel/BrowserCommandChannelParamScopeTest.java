package com.hsbc.cmb.hk.dbb.automation.framework.agent.channel;

import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.scope.ParamConstraint;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.scope.ParamScope;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.scope.ParamScopeViolationException;

import org.junit.Test;

import java.util.Map;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class BrowserCommandChannelParamScopeTest {

    private static final LocatorSpec USER = LocatorSpec.parse("role=textbox;name=username");

    /** 最小伪实现：记录 {@code execute(Command)} 是否真正被调用（验证门禁不误执行）。 */
    private static final class FakeChannel implements BrowserCommandChannel {
        boolean executed;

        @Override
        public Snapshot snapshot() {
            return null;
        }

        @Override
        public CommandResult execute(Command command) {
            executed = true;
            return null; // 伪实现，仅验证委托链路
        }
    }

    @Test
    public void wiringRejectsOnScopeViolationAndDoesNotExecute() {
        FakeChannel ch = new FakeChannel();
        ParamScope scope = new ParamScope(Map.of("value", ParamConstraint.allowedValues("alice")));
        try {
            ch.execute(Command.fill(USER, "carol"), scope);
            fail("expected ParamScopeViolationException");
        } catch (ParamScopeViolationException expected) {
            // 门禁 fail-closed 拒绝
        }
        assertFalse("越界命令不应被真正执行", ch.executed);
    }

    @Test
    public void wiringDelegatesWhenScopeOk() {
        FakeChannel ch = new FakeChannel();
        ParamScope scope = new ParamScope(Map.of("value", ParamConstraint.allowedValues("alice")));
        ch.execute(Command.fill(USER, "alice"), scope);
        assertTrue("合规命令应被委托执行", ch.executed);
    }

    @Test
    public void wiringEmptyScopePasses() {
        FakeChannel ch = new FakeChannel();
        ch.execute(Command.fill(USER, "anything"), ParamScope.EMPTY);
        assertTrue(ch.executed);
    }

    @Test
    public void wiringBatchDelegatesEachStep() {
        FakeChannel ch = new FakeChannel();
        ParamScope scope = new ParamScope(Map.of("value", ParamConstraint.allowedValues("alice")));
        ch.executeAll(java.util.List.of(Command.fill(USER, "alice"), Command.click(USER)), scope);
        assertTrue(ch.executed);
    }
}
