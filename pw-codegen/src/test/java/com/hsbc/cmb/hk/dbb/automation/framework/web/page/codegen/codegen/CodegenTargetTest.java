package com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen.codegen;

import org.junit.Assert;
import org.junit.Test;

/**
 * 生成目标（包名 + 由 URL 派生的类名）必须稳定、可编译、三个 Tab 一致。
 *
 * <p>类名<b>不接受调用方传入</b>，只能由 URL 派生：同一页面多次拾取得到同一个类名，
 * 产物可 diff / 可评审，也天然保证 Page / Step / Assert 三份产物的名字互相对应。
 */
public class CodegenTargetTest {

    @Test
    public void derivesFromLastPathSegment() {
        Assert.assertEquals("LogonPage", CodegenTarget.pageClassNameFromUrl("https://host/portalserver/logon"));
        Assert.assertEquals("LogonPage", CodegenTarget.pageClassNameFromUrl("https://host/portalserver/logon?lang=zh"));
        Assert.assertEquals("LogonPage", CodegenTarget.pageClassNameFromUrl("https://host/portalserver/logon#top"));
    }

    @Test
    public void stripsExtensionAndCamelCases() {
        Assert.assertEquals("AccountSummaryPage",
                CodegenTarget.pageClassNameFromUrl("https://host/app/account-summary.jsp"));
        Assert.assertEquals("AccountSummaryPage",
                CodegenTarget.pageClassNameFromUrl("https://host/app/account_summary.do"));
    }

    @Test
    public void fallsBackForRootOrEmptyUrl() {
        Assert.assertEquals("IndexPage", CodegenTarget.pageClassNameFromUrl("https://host/"));
        Assert.assertEquals("IndexPage", CodegenTarget.pageClassNameFromUrl("https://host"));
        Assert.assertEquals("GeneratedPage", CodegenTarget.pageClassNameFromUrl(""));
        Assert.assertEquals("GeneratedPage", CodegenTarget.pageClassNameFromUrl(null));
    }

    @Test
    public void alwaysProducesCompilableJavaIdentifier() {
        String[] urls = {
                "https://host/app/1mar-sun",      // 前导数字
                "https://host/app/",              // 末段为空
                "https://host/app/%%%",           // 全是符号
                "https://host/app/MyPage"         // 已带 Page 后缀，不再重复
        };
        for (String u : urls) {
            String name = CodegenTarget.pageClassNameFromUrl(u);
            Assert.assertTrue("非法 Java 标识符: " + name + " (url=" + u + ")",
                    Character.isJavaIdentifierStart(name.charAt(0)));
            for (char c : name.toCharArray()) {
                Assert.assertTrue("非法字符 " + c + " in " + name, Character.isJavaIdentifierPart(c));
            }
            Assert.assertTrue(name.endsWith("Page"));
            Assert.assertFalse("不应出现 PagePage", name.endsWith("PagePage"));
        }
        Assert.assertEquals("MyPage", CodegenTarget.pageClassNameFromUrl("https://host/app/MyPage"));
    }

    /** Page / Step / Assert 的命名必须同源：去掉 Page 后缀后拼角色后缀，pageVar 为首字母小写的页面类名。 */
    @Test
    public void stepAndAssertNamesDeriveFromPageClass() {
        CodegenTarget t = CodegenTarget.of("com.example.pages", "https://host/portalserver/logon?a=1");
        Assert.assertEquals("com.example.pages", t.packageName);
        Assert.assertEquals("LogonPage", t.pageClassName);
        Assert.assertEquals("com.example.pages.steps", t.stepPackageName);
        Assert.assertEquals("LogonSteps", t.stepClassName);
        Assert.assertEquals("com.example.pages.assertions", t.assertPackageName);
        Assert.assertEquals("LogonAssertions", t.assertClassName);
        Assert.assertEquals("logonPage", t.pageVar);
    }

    /** 兜底类名也不能产出 {@code GeneratedSteps} 之外的怪名：base 截断不得把类名截空。 */
    @Test
    public void fallbackClassStillYieldsUsableNames() {
        CodegenTarget t = CodegenTarget.of(null, "");
        Assert.assertEquals(CodegenTarget.DEFAULT_PACKAGE, t.packageName);
        Assert.assertEquals("GeneratedPage", t.pageClassName);
        Assert.assertEquals("GeneratedSteps", t.stepClassName);
        Assert.assertEquals("GeneratedAssertions", t.assertClassName);
    }

    @Test
    public void blankPackageFallsBackToDefault() {
        Assert.assertEquals(CodegenTarget.DEFAULT_PACKAGE, CodegenTarget.of("   ", "https://host/a").packageName);
    }
}
