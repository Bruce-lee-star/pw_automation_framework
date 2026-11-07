package com.hsbc.cmb.hk.dbb.automation.framework.agent.runtime;

import org.junit.After;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * {@link AgentRuntimeContext} 生命周期编排契约：顺序、逆序退出、部分进入失败回滚、
 * 异常不掩盖、默认 empty 零回归，以及 {@link AgentRuntimeContexts} 的 SPI/注入 seam。
 */
public class AgentRuntimeContextTest {

    /** 记录角色名 + 事件，用于断言顺序。 */
    private static final class Recorder implements AgentRuntimeParticipant {
        private final String name;
        private final List<String> events;
        private final boolean failOnOpen;
        private final boolean failOnClose;
        private final RuntimeException injected;

        Recorder(String name, List<String> events) {
            this(name, events, false, false, null);
        }

        Recorder(String name, List<String> events, boolean failOnOpen, boolean failOnClose,
                 RuntimeException injected) {
            this.name = name;
            this.events = events;
            this.failOnOpen = failOnOpen;
            this.failOnClose = failOnClose;
            this.injected = injected;
        }

        @Override
        public void onScopeOpen(AgentRuntimeScope scope) {
            events.add("open:" + name);
            if (failOnOpen) {
                throw injected == null ? new IllegalStateException("open failed: " + name) : injected;
            }
        }

        @Override
        public void onScopeClose(AgentRuntimeScope scope, Throwable failure) {
            events.add("close:" + name + (failure == null ? "" : "(failed)"));
            if (failOnClose) {
                throw injected == null ? new IllegalStateException("close failed: " + name) : injected;
            }
        }
    }

    @After
    public void resetRegistry() {
        AgentRuntimeContexts.reset();
    }

    private static AgentRuntimeContext contextOf(AgentRouteControl route, AgentSessionControl session,
                                                 AgentConcurrencyControl concurrency,
                                                 AgentMonitorControl monitor) {
        return new AgentRuntimeContext(route, session, concurrency, monitor);
    }

    @Test
    public void emptyContextIsNoOpAndKeepsResult() {
        AgentRuntimeContext ctx = AgentRuntimeContext.empty();
        assertSame(AgentRouteControl.NO_OP, ctx.route());
        assertSame(AgentSessionControl.NO_OP, ctx.session());
        assertSame(AgentConcurrencyControl.NO_OP, ctx.concurrency());
        assertSame(AgentMonitorControl.NO_OP, ctx.monitor());

        assertEquals("ok", ctx.runInScope(AgentRuntimeScope.of("file:///x"), () -> "ok"));
    }

    @Test
    public void scopeEntersInFixedOrderAndExitsInReverse() {
        List<String> events = new ArrayList<>();
        AgentRuntimeContext ctx = contextOf(
                route(events, "route"), session(events, "session"),
                concurrency(events, "concurrency"), monitor(events, "monitor"));

        ctx.runInScope(AgentRuntimeScope.of("plan-a", "file:///x", "sit_alice"), () -> events.add("body"));

        assertEquals(List.of(
                "open:concurrency", "open:session", "open:route", "open:monitor",
                "body",
                "close:monitor", "close:route", "close:session", "close:concurrency"), events);
    }

    @Test
    public void enterFailureRollsBackOnlySuccessfullyOpenedSlotsAndRethrows() {
        List<String> events = new ArrayList<>();
        IllegalStateException boom = new IllegalStateException("route open failed");
        AgentRuntimeContext ctx = contextOf(
                adapt(new Recorder("route", events, true, false, boom)),
                session(events, "session"),
                concurrency(events, "concurrency"),
                monitor(events, "monitor"));

        try {
            ctx.runInScope(AgentRuntimeScope.of("file:///x"), () -> {
                events.add("body");
                return null;
            });
            fail("进入失败必须原样抛出且执行体不得运行");
        } catch (IllegalStateException e) {
            assertSame(boom, e);
        }

        assertEquals(List.of(
                "open:concurrency", "open:session", "open:route",
                "close:session(failed)", "close:concurrency(failed)"), events);
        assertFalse("执行体不得在部分进入失败时运行", events.contains("body"));
    }

    @Test
    public void bodyFailureIsRethrownAndCloseFailuresAreSuppressed() {
        List<String> events = new ArrayList<>();
        IllegalStateException bodyFailure = new IllegalStateException("body failed");
        AgentRuntimeContext ctx = contextOf(
                adapt(new Recorder("route", events, false, true, null)),
                session(events, "session"),
                concurrency(events, "concurrency"),
                monitor(events, "monitor"));

        try {
            ctx.runInScope(AgentRuntimeScope.of("file:///x"), () -> {
                throw bodyFailure;
            });
            fail("执行体异常必须原样重抛");
        } catch (IllegalStateException e) {
            assertSame("不得用退出异常掩盖执行体失败", bodyFailure, e);
            assertEquals(1, e.getSuppressed().length);
            assertTrue(e.getSuppressed()[0].getMessage().contains("close failed: route"));
        }

        assertEquals("一个槽位退出失败不得阻断其余释放", List.of(
                "open:concurrency", "open:session", "open:route", "open:monitor",
                "close:monitor(failed)", "close:route(failed)",
                "close:session(failed)", "close:concurrency(failed)"), events);
    }

    @Test
    public void closeFailureOnSuccessIsThrownWithOthersSuppressed() {
        List<String> events = new ArrayList<>();
        AgentRuntimeContext ctx = contextOf(
                adapt(new Recorder("route", events, false, true, null)),
                adaptSession(new Recorder("session", events, false, true, null)),
                concurrency(events, "concurrency"),
                monitor(events, "monitor"));

        try {
            ctx.runInScope(AgentRuntimeScope.of("file:///x"), () -> "ok");
            fail("正常完成的退出异常不得被静默吞掉");
        } catch (IllegalStateException e) {
            assertTrue(e.getMessage().contains("close failed: route"));
            assertEquals(1, e.getSuppressed().length);
        }

        assertEquals(4, events.stream().filter(s -> s.startsWith("close:")).count());
    }

    @Test
    public void scopeCarriesIdentityToParticipants() {
        List<AgentRuntimeScope> seen = new ArrayList<>();
        AgentSessionControl session = new AgentSessionControl() {
            @Override
            public void onScopeOpen(AgentRuntimeScope scope) {
                seen.add(scope);
            }
        };
        AgentRuntimeContext ctx = contextOf(
                AgentRouteControl.NO_OP, session, AgentConcurrencyControl.NO_OP, AgentMonitorControl.NO_OP);

        ctx.runInScope(AgentRuntimeScope.of("plan-7", "https://app/p", "sit_bob"), () -> null);

        assertEquals(1, seen.size());
        assertEquals("plan-7", seen.get(0).planId());
        assertEquals("https://app/p", seen.get(0).targetUrl());
        assertEquals("sit_bob", seen.get(0).sessionKey());
        assertNull(AgentRuntimeScope.of("file:///x").sessionKey());
    }

    @Test
    public void nullRolesAndArgumentsAreRejected() {
        try {
            new AgentRuntimeContext(null, AgentSessionControl.NO_OP,
                    AgentConcurrencyControl.NO_OP, AgentMonitorControl.NO_OP);
            fail("null route must be rejected");
        } catch (NullPointerException expected) {
            assertTrue(expected.getMessage().contains("route"));
        }
        AgentRuntimeContext ctx = AgentRuntimeContext.empty();
        try {
            ctx.runInScope(null, () -> "x");
            fail("null scope must be rejected");
        } catch (NullPointerException expected) {
            assertTrue(expected.getMessage().contains("scope"));
        }
        try {
            new AgentRuntimeScope(null, null, null);
            fail("null targetUrl must be rejected");
        } catch (NullPointerException expected) {
            assertTrue(expected.getMessage().contains("targetUrl"));
        }
    }

    @Test
    public void registryInstallAndResetProvideReplaceableSeam() {
        AgentRuntimeContexts.reset();
        // pw-core 模块自身不注册任何贡献者（SPI 文件在 pw-web-ui），故必须精确回落 empty。
        assertSame("无 SPI 贡献者时回落 empty",
                AgentRuntimeContext.empty(), AgentRuntimeContexts.current());

        List<String> events = new ArrayList<>();
        AgentRuntimeContext injected = contextOf(
                route(events, "route"), AgentSessionControl.NO_OP,
                AgentConcurrencyControl.NO_OP, AgentMonitorControl.NO_OP);
        AgentRuntimeContexts.install(injected);
        assertSame(injected, AgentRuntimeContexts.current());
        AgentRuntimeContexts.current().runInScope(AgentRuntimeScope.of("file:///x"), () -> null);
        assertEquals(List.of("open:route", "close:route"), events);

        AgentRuntimeContexts.reset();
        assertFalse("reset 后不得继续持有注入替身", AgentRuntimeContexts.current() == injected);
    }

    // ---- 便捷构造 ----

    private static AgentRouteControl route(List<String> events, String name) {
        return adapt(new Recorder(name, events));
    }

    private static AgentSessionControl session(List<String> events, String name) {
        return adaptSession(new Recorder(name, events));
    }

    private static AgentConcurrencyControl concurrency(List<String> events, String name) {
        return adaptConcurrency(new Recorder(name, events));
    }

    private static AgentMonitorControl monitor(List<String> events, String name) {
        return adaptMonitor(new Recorder(name, events));
    }

    private static AgentRouteControl adapt(Recorder r) {
        return new AgentRouteControl() {
            @Override public void onScopeOpen(AgentRuntimeScope s) { r.onScopeOpen(s); }
            @Override public void onScopeClose(AgentRuntimeScope s, Throwable f) { r.onScopeClose(s, f); }
        };
    }

    private static AgentSessionControl adaptSession(Recorder r) {
        return new AgentSessionControl() {
            @Override public void onScopeOpen(AgentRuntimeScope s) { r.onScopeOpen(s); }
            @Override public void onScopeClose(AgentRuntimeScope s, Throwable f) { r.onScopeClose(s, f); }
        };
    }

    private static AgentConcurrencyControl adaptConcurrency(Recorder r) {
        return new AgentConcurrencyControl() {
            @Override public void onScopeOpen(AgentRuntimeScope s) { r.onScopeOpen(s); }
            @Override public void onScopeClose(AgentRuntimeScope s, Throwable f) { r.onScopeClose(s, f); }
        };
    }

    private static AgentMonitorControl adaptMonitor(Recorder r) {
        return new AgentMonitorControl() {
            @Override public void onScopeOpen(AgentRuntimeScope s) { r.onScopeOpen(s); }
            @Override public void onScopeClose(AgentRuntimeScope s, Throwable f) { r.onScopeClose(s, f); }
        };
    }
}
