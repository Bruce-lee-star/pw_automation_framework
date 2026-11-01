package com.hsbc.cmb.hk.dbb.automation.framework.web.page.element;

import com.hsbc.cmb.hk.dbb.automation.framework.web.exceptions.ElementOperationException;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.engine.BasePage;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.PlaywrightException;
import org.junit.Test;

import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * P1-8 回归：
 * <ul>
 *   <li>{@code executeWithRetry} 的 {@code Supplier<Boolean>} 契约 —— action 返回 {@code false}/{@code null}
 *       （未抛异常）必须按逻辑失败收口为 {@link ElementOperationException}，而非静默当作成功；</li>
 *   <li>导航/上下文丢失判据收窄 —— 不再用宽子串 {@code "navigat"}（任何含 navigate 的报文都会触发
 *       额外 DOM 等待 + 重试，放大真实失败耗时），改为 {@code "navigation"}/{@code "navigating"}。</li>
 * </ul>
 */
public class ElementOperationSupportTest {

    private static BasePage nullPage() {
        BasePage bp = mock(BasePage.class);
        when(bp.getPageRaw()).thenReturn(null); // awaitFrameQuiet / waitForDomSettled 均早退
        return bp;
    }

    /** P1-8 契约修正：action 返回 false（未抛异常）→ 此前被静默吞掉，现必须抛 ElementOperationException。 */
    @Test
    public void executeWithRetry_falseReturnIsLogicalFailureNotSilentSuccess() {
        BasePage bp = nullPage();
        Locator loc = mock(Locator.class);
        AtomicBoolean invoked = new AtomicBoolean(false);

        try {
            ElementOperationSupport.executeWithRetry(() -> loc, "#el", bp, () -> {
                invoked.set(true);
                return false; // 逻辑失败，未抛异常
            }, "click");
            fail("expected ElementOperationException for action returning false");
        } catch (ElementOperationException e) {
            assertTrue("action 必须被实际调用", invoked.get());
            assertNotNull("失败异常须带诊断信息", e.getMessage());
            assertNotNull("失败异常须带诊断快照", e.getDiagnosticInfo());
            assertTrue("未发生重试时重试次数应为 0", e.getDiagnosticInfo().retryCount() == 0);
            assertTrue("未重试时总尝试次数应为 1", e.getMessage().contains("failed after 1 attempts"));
            //  逻辑失败文本经 buildRetryException 置于 cause（lastEx）
            assertNotNull("须携带 logical failure 的 cause", e.getCause());
            assertTrue("cause 须标识为 logical failure",
                    e.getCause().getMessage() != null
                            && e.getCause().getMessage().contains("logical failure"));
        }
    }

    @Test
    public void executeWithRetry_reportsOneRetryAfterNavigationFailure() {
        BasePage bp = nullPage();
        Locator loc = mock(Locator.class);
        AtomicBoolean first = new AtomicBoolean(true);
        AtomicBoolean invokedTwice = new AtomicBoolean(false);

        try {
            ElementOperationSupport.executeWithRetry(() -> loc, "#el", bp, () -> {
                if (first.getAndSet(false)) {
                    throw new PlaywrightException("Execution context was destroyed during navigation");
                }
                invokedTwice.set(true);
                return false;
            }, "click");
            fail("expected ElementOperationException after failed navigation retry");
        } catch (ElementOperationException e) {
            assertTrue("导航失败后 action 应重试一次", invokedTwice.get());
            assertNotNull("失败异常须带诊断快照", e.getDiagnosticInfo());
            assertTrue("应报告实际发生的一次重试", e.getDiagnosticInfo().retryCount() == 1);
            assertTrue("应报告两次总尝试", e.getMessage().contains("failed after 2 attempts"));
        }
    }

    /** 与上对应：action 返回 true 仍正常成功（契约正向路径不变）。 */
    @Test
    public void executeWithRetry_trueReturnSucceeds() {
        BasePage bp = nullPage();
        Locator loc = mock(Locator.class);
        AtomicBoolean invoked = new AtomicBoolean(false);

        ElementOperationSupport.executeWithRetry(() -> loc, "#el", bp, () -> {
            invoked.set(true);
            return true;
        }, "click");

        assertTrue("action 必须被实际调用", invoked.get());
    }

    /** P1-8 判据收窄：含宽子串 'navigat' 但非真实导航语义的报文（如 class 名 'navigatetab'）不再判定为导航丢失。 */
    @Test
    public void isNavigationOrContextLoss_rejectsBenignNavigatSubstring() throws Exception {
        assertFalse(navLoss(new PlaywrightException("element with class 'navigatetab' is not found")));
    }

    @Test
    public void isNavigationOrContextLoss_acceptsRealNavigationPhrases() throws Exception {
        assertTrue(navLoss(new PlaywrightException("Timeout 30000ms exceeded during navigation")));
        assertTrue(navLoss(new PlaywrightException("waiting while navigating to url")));
        assertTrue(navLoss(new PlaywrightException("Navigation failed because the page was closed")));
    }

    @Test
    public void isNavigationOrContextLoss_acceptsOtherContextLossSignatures() throws Exception {
        assertTrue(navLoss(new PlaywrightException("Execution context was destroyed, most likely because of a navigation")));
        assertTrue(navLoss(new PlaywrightException("Object doesn't exist: frame@")));
        assertTrue(navLoss(new PlaywrightException("frame was detached")));
    }

    @Test
    public void isNavigationOrContextLoss_rejectsUnrelatedAndNull() throws Exception {
        assertFalse(navLoss(new PlaywrightException("element is not visible")));
        assertFalse(navLoss(new PlaywrightException(null)));
    }

    private static boolean navLoss(PlaywrightException e) throws Exception {
        Method m = ElementOperationSupport.class
                .getDeclaredMethod("isNavigationOrContextLoss", PlaywrightException.class);
        m.setAccessible(true);
        return (boolean) m.invoke(null, e);
    }
}
