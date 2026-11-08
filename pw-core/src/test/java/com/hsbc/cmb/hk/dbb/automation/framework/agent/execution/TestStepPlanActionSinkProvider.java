package com.hsbc.cmb.hk.dbb.automation.framework.agent.execution;

import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicInteger;

/** pw-core 测试用 SPI 实现（无 Mockito，用 JDK 动态代理），记录创建次数。 */
public final class TestStepPlanActionSinkProvider implements StepPlanActionSinkProvider {

    static final AtomicInteger CREATED = new AtomicInteger();

    @Override
    public StepPlanActionSink create() {
        CREATED.incrementAndGet();
        return (StepPlanActionSink) Proxy.newProxyInstance(
                StepPlanActionSink.class.getClassLoader(),
                new Class<?>[]{StepPlanActionSink.class},
                (proxy, method, args) -> null);
    }
}
