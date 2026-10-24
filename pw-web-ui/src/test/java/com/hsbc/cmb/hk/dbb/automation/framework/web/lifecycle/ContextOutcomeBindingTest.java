package com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle;

import com.microsoft.playwright.BrowserContext;
import org.junit.Assert;
import org.junit.Test;
import org.mockito.Mockito;

/**
 * {@link ContextOutcomeBinding} 契约：per-context → outcomeId 绑定在并行 / 收尾线程上精确解析归属，
 * 且 bind/unbind 防泄漏（复用 DownloadRegistry 的 ConcurrentHashMap 范式）。
 */
public class ContextOutcomeBindingTest {

    @Test
    public void bindThenOutcomeIdReturnsBoundId() {
        BrowserContext ctx = Mockito.mock(BrowserContext.class);
        ContextOutcomeBinding.bind(ctx, "c1");
        Assert.assertEquals("c1", ContextOutcomeBinding.outcomeId(ctx));
    }

    @Test
    public void unbindRemovesBinding() {
        BrowserContext ctx = Mockito.mock(BrowserContext.class);
        ContextOutcomeBinding.bind(ctx, "c1");
        ContextOutcomeBinding.unbind(ctx);
        Assert.assertNull(ContextOutcomeBinding.outcomeId(ctx));
    }

    @Test
    public void nullOrEmptyOutcomeIdIgnored() {
        BrowserContext ctx = Mockito.mock(BrowserContext.class);
        ContextOutcomeBinding.bind(ctx, null);
        ContextOutcomeBinding.bind(ctx, "");
        Assert.assertNull(ContextOutcomeBinding.outcomeId(ctx));
        ContextOutcomeBinding.unbind(ctx); // 无绑定时 no-op，不应抛异常
    }

    @Test
    public void distinctContextsKeepDistinctBindings() {
        BrowserContext a = Mockito.mock(BrowserContext.class);
        BrowserContext b = Mockito.mock(BrowserContext.class);
        ContextOutcomeBinding.bind(a, "a");
        ContextOutcomeBinding.bind(b, "b");
        Assert.assertEquals("a", ContextOutcomeBinding.outcomeId(a));
        Assert.assertEquals("b", ContextOutcomeBinding.outcomeId(b));
        ContextOutcomeBinding.unbind(a);
        Assert.assertNull(ContextOutcomeBinding.outcomeId(a));
        Assert.assertEquals("b", ContextOutcomeBinding.outcomeId(b));
        ContextOutcomeBinding.unbind(b);
        Assert.assertNull(ContextOutcomeBinding.outcomeId(b));
    }
}
