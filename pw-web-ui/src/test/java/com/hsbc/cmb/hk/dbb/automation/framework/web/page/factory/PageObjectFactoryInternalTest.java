package com.hsbc.cmb.hk.dbb.automation.framework.web.page.factory;

import com.hsbc.cmb.hk.dbb.automation.framework.core.context.ScenarioContext;
import com.hsbc.cmb.hk.dbb.automation.framework.core.context.TestContextHolder;
import org.junit.After;
import org.junit.Test;

import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * PageObjectFactory 内部正确性回归（P1-10 / P1-11 / P1-12）。
 */
public class PageObjectFactoryInternalTest {

    /** 纯 POJO 充当被测 PageObject（无需浏览器，含公共无参构造以支持反射回退）。 */
    public static class DummyPage {
    }

    @After
    public void tearDown() {
        ScenarioContext.endCurrent();
        TestContextHolder.resetForCurrentThread();
        PageObjectFactory.resetConfig();
        PageObjectFactory.clearAll();
    }

    private static PageObjectFactory.CreationConfig config(PageObjectFactory.LifecycleStrategy strategy) {
        return new PageObjectFactory.Builder().lifecycle(strategy).build();
    }

    /**
     * P1-10：显式传 strategy（与默认 THREAD_ISOLATED 不同）触发的「临时 config 重建」分支，必须连同
     * customSuppliers / postCreateHooks / properties 一并迁移。旧实现只迁 lifecycle + lazy →
     * 经 Builder.customSupplier()/postCreateHook() 配置的注入与钩子被静默丢弃。
     */
    @Test
    public void explicitStrategyPreservesCustomSupplierAndHook() {
        AtomicBoolean supplierUsed = new AtomicBoolean(false);
        AtomicBoolean hookRan = new AtomicBoolean(false);

        PageObjectFactory.CreationConfig cfg = PageObjectFactory.builder()
                .customSupplier(DummyPage.class, () -> {
                    supplierUsed.set(true);
                    return new DummyPage();
                })
                .postCreateHook(o -> hookRan.set(true))
                .build();
        PageObjectFactory.setConfig(cfg);

        // 显式 SINGLETON（≠ 默认 THREAD_ISOLATED）→ 触发 P1-10 重建分支
        PageObjectFactory.getPage(DummyPage.class, PageObjectFactory.LifecycleStrategy.SINGLETON);

        assertTrue("P1-10：显式 strategy 下自定义 Supplier 必须被使用（旧实现静默丢弃）", supplierUsed.get());
        assertTrue("P1-10：显式 strategy 下 postCreateHook 必须执行（旧实现静默丢弃）", hookRan.get());
    }

    /**
     * P1-11：只读 API（hasInstance / getInstanceCount / getStatistics / getRegisteredPageClasses）不得
     * 为当前线程凭空创建 ThreadContext 并登记进 TestContextHolder。
     */
    @Test
    public void readOnlyApisDoNotCreateContext() {
        TestContextHolder.resetForCurrentThread();
        assertNull("前置：当前应无上下文", TestContextHolder.peek());

        PageObjectFactory.hasInstance(DummyPage.class);
        PageObjectFactory.getInstanceCount();
        PageObjectFactory.getStatistics();
        Set<Class<?>> classes = PageObjectFactory.getRegisteredPageClasses();
        // getRegisteredPageClasses 返回空集也是合法结果（纯粹读数），关键是不得创建上下文
        assertFalse("读取结果不应含未创建的 DummyPage", classes.contains(DummyPage.class));

        assertNull("P1-11：只读 API 不得创建/登记 ThreadContext", TestContextHolder.peek());
    }

    /**
     * P1-12：请求作用域实例按 scenario:<id> 写入；若收尾时刻场景身份已解绑，endRequestScope 的键口径会
     * 翻转为 thread:<tid>，写入条目将永久滞留（静态 Map 随用例数增长）。修复后 endRequestScope 用写入时
     * 记录的键精确删除，不受场景解绑影响。
     */
    @Test
    public void endRequestScopeRemovesEntryEvenIfScenarioUnbound() {
        PageObjectFactory.register(DummyPage.class, DummyPage::new);
        ScenarioContext.begin("p112-scn");
        PageObjectFactory.getPage(DummyPage.class, config(PageObjectFactory.LifecycleStrategy.REQUEST_SCOPED));
        assertTrue("前置：写入后应包含该类型", PageObjectFactory.getRegisteredPageClasses().contains(DummyPage.class));

        ScenarioContext.endCurrent(); // 模拟收尾先于 endRequestScope：场景身份解绑
        PageObjectFactory.endRequestScope();

        assertFalse("P1-12：收尾时场景已解绑，旧实现键口径翻转→条目滞留；新实现应精确删除",
                PageObjectFactory.getRegisteredPageClasses().contains(DummyPage.class));
    }
}
