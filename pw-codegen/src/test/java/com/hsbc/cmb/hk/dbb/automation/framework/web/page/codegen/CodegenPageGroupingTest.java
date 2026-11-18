package com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen;

import com.microsoft.playwright.Page;
import org.junit.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;

/**
 * 页面类归集粒度（用户 2026-11-16 反馈）：<b>URL 变化才派生新类，相同 URL 不再派生 {@code _2/_3}</b>。
 *
 * <p>现场：HSBC 重置密码是 SPA，同一次录制里 URL 依次是
 * {@code /reset-password#/triage}、{@code #/password}，页面类名又只取 path 末段（{@code ResetPasswordPage}），
 * 于是「同一个页面的不同步骤」被拆成 {@code ResetPasswordPage} / {@code ResetPasswordPage_2}：
 * 字段被劈成两半，Step/Assert 也跟着分裂。
 *
 * <p>本类固化三件事：分组键按「去 query / 去 hash 的 URL」判等（与类名派生口径一致）、
 * 同一 URL 出现在多个标签页或 Page 对象被重建时仍是同一个键、以及 {@code _2} 只在
 * 「不同 path 恰好派生出同名类」这种真实碰撞时出现。
 */
public class CodegenPageGroupingTest {

    private static final String RESET = "https://h/portalserver/reset-password";

    /** SPA 只改 hash ⇒ 同一个页面类。 */
    @Test
    public void hashStateIsNotANewPage() {
        CodegenLauncher.PageUrl triage = key(fakePage(RESET + "#/triage"), RESET + "#/triage");
        CodegenLauncher.PageUrl password = key(fakePage(RESET + "#/password"), RESET + "#/password");

        assertEquals("SPA 换 hash 不是换页面", triage, password);
        assertEquals("equals 成立则 hashCode 必须一致", triage.hashCode(), password.hashCode());
    }

    /** query 参数（跟踪参数 / 表单态）同样不构成新页面。 */
    @Test
    public void queryStringIsNotANewPage() {
        assertEquals(key(fakePage(RESET + "?lang=en"), RESET + "?lang=en"),
                key(fakePage(RESET), RESET));
        assertEquals(key(fakePage("/search?type=A"), "/search?type=A"),
                key(fakePage("/search?type=B"), "/search?type=B"));
    }

    /** 同一 URL 落在不同标签页（Page 对象不同）⇒ 同一个页面类。 */
    @Test
    public void sameUrlOnAnotherTabIsNotANewPage() {
        Page tab1 = fakePage(RESET);
        Page tab2 = fakePage(RESET);

        assertEquals("页面类型由 URL 决定，与标签页身份无关",
                key(tab1, RESET), key(tab2, RESET));
    }

    /** 尾斜杠只是写法差异，不是不同页面。 */
    @Test
    public void trailingSlashIsIgnored() {
        assertEquals(key(fakePage(RESET + "/"), RESET + "/"), key(fakePage(RESET), RESET));
    }

    /** path 真的变了 ⇒ 必须仍然各自成类（导航不能被吞掉）。 */
    @Test
    public void differentPathIsANewPage() {
        assertNotEquals(key(fakePage("https://h/portalserver/logon"), "https://h/portalserver/logon"),
                key(fakePage(RESET), RESET));
    }

    /** URL 不可读（页面已关闭）时才退回标签页身份：不同未知页不能被并成一个类。 */
    @Test
    public void unreadableUrlFallsBackToTabIdentity() {
        Page a = fakePage(null);
        Page b = fakePage(null);

        assertEquals(key(a, null), key(a, null));
        assertNotEquals("两个取不到 URL 的页面不能合并", key(a, null), key(b, null));
    }

    /** 同一次会话里三重拾取同一 URL ⇒ 分组键只有一个，类名不带任何后缀。 */
    @Test
    public void sameUrlNeverProducesNumberedSuffix() {
        Page tab1 = fakePage(RESET + "#/triage");
        Page tab2 = fakePage(RESET + "#/password");
        Page tab3 = fakePage(RESET);

        // 与 CodegenLauncher 相同的归集方式：URL 键 → 目标
        Map<CodegenLauncher.PageUrl, String> byKey = new LinkedHashMap<>();
        byKey.put(key(tab1, RESET + "#/triage"), "");
        byKey.put(key(tab2, RESET + "#/password"), "");
        byKey.put(key(tab3, RESET), "");

        assertEquals("同一个 URL 只能有一个分组键", 1, byKey.size());
        assertEquals(List.of("ResetPasswordPage"),
                CodegenLauncher.assignClassNames(new ArrayList<>(byKey.keySet())));
    }

    /** 真正的碰撞（不同 path 派生出同名类）才追加 _2：这是唯一保留后缀的场景。 */
    @Test
    public void onlyRealNameCollisionsGetNumberedSuffix() {
        List<CodegenLauncher.PageUrl> keys = List.of(
                key(fakePage("https://h/logon"), "https://h/logon"),
                key(fakePage("https://h/Logon"), "https://h/Logon"));

        assertEquals(List.of("LogonPage", "LogonPage_2"), CodegenLauncher.assignClassNames(keys));
    }

    /** {@code urlKey} 归一化细节。 */
    @Test
    public void urlKeyNormalization() {
        assertNull("取不到 URL 时返回 null（调用方退回标签页身份）", CodegenLauncher.urlKey(null));
        assertEquals(RESET, CodegenLauncher.urlKey(RESET + "#/triage"));
        assertEquals(RESET, CodegenLauncher.urlKey(RESET + "?lang=zh&x=1"));
        assertEquals(RESET, CodegenLauncher.urlKey(RESET + "/"));
        assertEquals(RESET, CodegenLauncher.urlKey("  " + RESET + "  "));
        assertNotEquals("path 大小写是真实差异",
                CodegenLauncher.urlKey("https://h/logon"), CodegenLauncher.urlKey("https://h/Logon"));
    }

    /** M3：path 里的语言段不构成新页面——切语言不得分裂成两个 Page 类。 */
    @Test
    public void pathLocaleSegmentIsNotANewPage() {
        assertEquals("下划线语言段",
                CodegenLauncher.urlKey("https://h/en_US/logon"),
                CodegenLauncher.urlKey("https://h/logon"));
        assertEquals("连字符语言段",
                CodegenLauncher.urlKey("https://h/zh-HK/reset-password"),
                CodegenLauncher.urlKey("https://h/reset-password"));
        assertEquals("两字母语言段",
                CodegenLauncher.urlKey("https://h/fr/portalserver/logon"),
                CodegenLauncher.urlKey("https://h/portalserver/logon"));
        assertEquals("业务 path 中的语言样词不得误剥（如 /zhongwen/ 不是语言码）",
                CodegenLauncher.urlKey("https://h/zhongwen/logon"),
                "https://h/zhongwen/logon");
    }

    // ------------------------------------------------------------------

    private static CodegenLauncher.PageUrl key(Page page, String url) {
        return new CodegenLauncher.PageUrl(page, url);
    }

    /** 无需浏览器的 Page 替身：只实现拿到 URL 的那个方法。 */
    private static Page fakePage(String url) {
        return (Page) Proxy.newProxyInstance(Page.class.getClassLoader(), new Class<?>[]{Page.class},
                (proxy, method, args) -> {
                    if ("url".equals(method.getName())) return url;
                    if ("toString".equals(method.getName())) return "FakePage(" + url + ")";
                    if ("hashCode".equals(method.getName())) return System.identityHashCode(proxy);
                    if ("equals".equals(method.getName())) return proxy == args[0];
                    return null;
                });
    }
}
