package com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.scope;

import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.Command;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.LocatorSpec;

import org.junit.Test;

import java.util.Map;

import static org.junit.Assert.fail;

public class ParamScopeGateTest {

    private static final LocatorSpec USER = LocatorSpec.parse("role=textbox;name=username");

    @Test
    public void noScopePassesAnything() {
        ParamScopeGate.check(null, Command.fill(USER, "whatever"));
        ParamScopeGate.check(ParamScope.EMPTY, Command.navigate("https://evil.example.com"));
    }

    @Test
    public void undeclaredParamRejected() {
        ParamScope scope = new ParamScope(Map.of("option", ParamConstraint.allowedValues("a", "b")));
        assertThrowsViolation(() -> ParamScopeGate.check(scope, Command.fill(USER, "alice")));
    }

    @Test
    public void secretRefIsExempt() {
        ParamScope scope = new ParamScope(Map.of("value", ParamConstraint.allowedValues("alice")));
        Command withSecret = new Command(Command.ActionType.FILL, USER,
                Map.of("value", "alice", "secretRef", "vault://x"));
        ParamScopeGate.check(scope, withSecret); // 不抛：secretRef 豁免
    }

    @Test
    public void valueOutOfAllowSetRejected() {
        ParamScope scope = new ParamScope(Map.of("value", ParamConstraint.allowedValues("alice", "bob")));
        assertThrowsViolation(() -> ParamScopeGate.check(scope, Command.fill(USER, "carol")));
    }

    @Test
    public void valueInAllowSetPasses() {
        ParamScope scope = new ParamScope(Map.of("value", ParamConstraint.allowedValues("alice", "bob")));
        ParamScopeGate.check(scope, Command.fill(USER, "alice"));
    }

    @Test
    public void regexMismatchRejected() {
        ParamScope scope = new ParamScope(Map.of("value", ParamConstraint.matchingRegex("^[0-9]+$")));
        assertThrowsViolation(() -> ParamScopeGate.check(scope, Command.fill(USER, "abc")));
    }

    @Test
    public void regexMatchPasses() {
        ParamScope scope = new ParamScope(Map.of("value", ParamConstraint.matchingRegex("^[0-9]+$")));
        ParamScopeGate.check(scope, Command.fill(USER, "123"));
    }

    @Test
    public void commandWithoutConstrainedParamsPasses() {
        ParamScope scope = new ParamScope(Map.of("value", ParamConstraint.allowedValues("alice")));
        ParamScopeGate.check(scope, Command.click(USER)); // 无 params
    }

    private static void assertThrowsViolation(ThrowingRunnable r) {
        try {
            r.run();
            fail("expected ParamScopeViolationException");
        } catch (ParamScopeViolationException expected) {
            // 期望的 fail-closed 门禁拒绝
        }
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run();
    }
}
