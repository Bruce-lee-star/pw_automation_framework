package com.hsbc.cmb.hk.dbb.automation.framework.common.route;

import org.junit.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;

/**
 * 2026-10-16 Bug 修复锁定：{@link RouteLifecycleComposite} 必须把 {@code contextBeingClosed} 意图
 * <b>透传</b>给 primary 与 additions（2 参 {@code stopContextEngine}），不得退化成 1 参。
 *
 * <p>背景：Composite 曾只覆写 1 参 {@code stopContextEngine(Object)}，2 参调用落到
 * {@code EngineControl} 默认方法 → 退化为 1 参，意图被丢弃。后果：底层 runtime 收不到"即将关闭"信号，
 * context 关闭时仍逐条 unroute（多余同步协议往返 + 竞态）且关闭走优雅等待（每场景至多 5s）。
 * 本用例钉住 2 参直达 children。</p>
 *
 * <p>pw-core 无 Mockito 依赖，故用 JDK 动态代理做 recording fake（只关心 stopContextEngine 的调用形态）。</p>
 */
public class RouteLifecycleCompositeTest {

    /** 记录收到的 stopContextEngine 调用形态（"1arg" / "2arg:true" / "2arg:false"）。 */
    private static final class Recorder {
        final List<String> calls = new ArrayList<>();
    }

    private static RouteLifecycle recording(Recorder rec) {
        InvocationHandler h = (proxy, method, args) -> {
            if ("stopContextEngine".equals(method.getName())) {
                rec.calls.add((args != null && args.length == 2) ? "2arg:" + args[1] : "1arg");
            }
            Class<?> rt = method.getReturnType();
            if (rt == boolean.class) {
                return false;
            }
            if (rt == int.class) {
                return 0;
            }
            return null;
        };
        return (RouteLifecycle) Proxy.newProxyInstance(
                RouteLifecycle.class.getClassLoader(), new Class<?>[]{RouteLifecycle.class}, h);
    }

    @Test
    public void closedIntent_propagates2argTrueToPrimaryAndAdditions() {
        Recorder pr = new Recorder();
        Recorder ar = new Recorder();
        RouteLifecycle composite = RouteLifecycleComposite.of(recording(pr), List.of(recording(ar)));
        Object ctx = new Object();

        composite.stopContextEngine(ctx, true);

        assertEquals("primary 必须收到 2 参 true（不得退化成 1 参）", List.of("2arg:true"), pr.calls);
        assertEquals("addition 必须收到 2 参 true", List.of("2arg:true"), ar.calls);
    }

    @Test
    public void falseIntent_propagates2argFalse() {
        Recorder pr = new Recorder();
        RouteLifecycle composite = RouteLifecycleComposite.of(recording(pr), List.of());

        composite.stopContextEngine(new Object(), false);

        assertEquals("primary 必须收到 2 参 false", List.of("2arg:false"), pr.calls);
    }
}
