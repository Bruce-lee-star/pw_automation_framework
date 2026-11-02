package com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen;

import com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen.codegen.CodegenTarget;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen.codegen.PageClassGenerator;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen.model.CodegenEntry;
import org.junit.Assert;
import org.junit.Test;

import java.util.Arrays;
import java.util.List;

/**
 * 「标记为一组」场景：不同容器里 role+name 相同的元素，sigKey 完全相同（各自在容器内都是第 0 个），
 * 自动 base-sig 分组识别不到（序号互不异）⇒ 必须由用户在面板显式标记成组。
 */
public class CodegenLauncherGroupTest {

    private static final CodegenTarget TARGET = CodegenTarget.of("com.example.pages", "https://host/app/page");

    /** 两个"不同容器、同 role+name"的元素：sigKey 一模一样、index 都是 0。 */
    private static CodegenEntry sameSig(String css) {
        return new CodegenEntry("button|Submit#0", "button", "Submit", "ROLE", 100, "role=button", css, null, 0);
    }

    @Test
    public void sameSigKeyPicks_groupedExplicitly_becomeOnePageElementList() {
        List<CodegenEntry> members = Arrays.asList(
                sameSig("#formA > button:nth-of-type(1)"),
                sameSig("#formB > button:nth-of-type(1)"));

        // 标记前：sigKey 相同 ⇒ 去重后只剩一条，产物是一个普通 PageElement
        String before = PageClassGenerator.generate(TARGET, members, new String[]{});
        Assert.assertTrue(before.contains("public PageElement submit;"));
        Assert.assertFalse(before.contains("PageElementList"));

        // 标记后：共享 base-sig + 互异序号 ⇒ 合成一个 PageElementList
        List<CodegenEntry> grouped = CodegenLauncher.groupAsList(members);
        Assert.assertEquals(2, grouped.size());
        Assert.assertEquals("button|Submit#0", grouped.get(0).sigKey);
        Assert.assertEquals("button|Submit#1", grouped.get(1).sigKey);
        Assert.assertEquals(0, grouped.get(0).index);
        Assert.assertEquals(1, grouped.get(1).index);

        String after = PageClassGenerator.generate(TARGET, grouped, new String[]{});
        Assert.assertTrue(after.contains("public PageElementList submit;"));
        Assert.assertEquals(1, count(after, "public PageElementList"));
        Assert.assertFalse(after.contains("public PageElement submit;"));
    }

    @Test
    public void groupAsListWithLessThanTwoMembers_returnsInput() {
        List<CodegenEntry> one = List.of(sameSig("#a"));
        Assert.assertSame(one, CodegenLauncher.groupAsList(one));
    }

    private static int count(String s, String sub) {
        int c = 0, i = 0;
        while ((i = s.indexOf(sub, i)) >= 0) { c++; i += sub.length(); }
        return c;
    }
}
