package com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen.codegen;

import com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen.locator.LocatorStrategy;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen.model.CodegenEntry;
import org.junit.Assert;
import org.junit.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 问题②：label 这类在 {@code AriaRole} 枚举里没有对应常量的元素，命中 NLS 且<b>文本定位唯一</b>时，
 * 必须优先生成 key-only {@code @RoleElement(key=...)}（文本语义策略），而不是被迫退化成 CSS。
 *
 * <p>唯一性由调用方（{@code CodegenLauncher}）用真实引擎 {@code getByText(text, exact).count()} 校验，
 * 本类只验证"白名单命中才生成、未命中一律退 CSS"的规划契约。
 */
public class CodegenFieldPlannerTextSemanticTest {

    private static final String[] NLS = {"nls/login.nls.json"};

    private static final String LABEL_CSS = "label[class*=\"field-label\"]";

    /** 无 ARIA role（label）+ 命中 NLS + 已校验文本唯一 ⇒ 生成 key-only @RoleElement，且引 @RoleFile。 */
    @Test
    public void verifiedUniqueNlsText_emitsKeyOnlyRoleElement() {
        CodegenEntry e = entry("label|User name#0", "username_label", "User name");

        CodegenFieldPlanner.Plan plan = CodegenFieldPlanner.plan(
                List.of(e), NLS, Map.of(), Set.of(e.sigKey));

        CodegenFieldSpec f = plan.fields.get(0);
        Assert.assertEquals("@RoleElement(key = \"username_label\")", f.annotation);
        Assert.assertFalse("key-only 不需要 AriaRole 常量 import", plan.hasAriaRole);
        Assert.assertTrue("有 key 就必须输出 @RoleFile，否则运行期解析不到文案", plan.hasRole);
        Assert.assertTrue("文本语义也算语义定位成功", f.roleLocatable);
    }

    /** 未通过文本唯一性校验（不在白名单）⇒ 保持 CSS，绝不生成可能多命中的 key-only 定位器。 */
    @Test
    public void unverifiedNlsText_staysCss() {
        CodegenEntry e = entry("label|User name#0", "username_label", "User name");

        CodegenFieldPlanner.Plan plan = CodegenFieldPlanner.plan(
                List.of(e), NLS, Map.of(), Set.of());

        CodegenFieldSpec f = plan.fields.get(0);
        Assert.assertTrue("未通过校验必须退回 @Element", f.annotation.startsWith("@Element("));
        Assert.assertTrue("CSS 内容应保留（内层引号会被转义成单引号）",
                f.annotation.contains("field-label"));
        Assert.assertFalse(plan.hasRole);
        Assert.assertFalse(f.roleLocatable);
    }

    /** 子 frame 元素：页级 getByText 看不到它，即使误入白名单也不得生成 key-only。 */
    @Test
    public void frameElementNeverUsesTextSemantic() {
        CodegenEntry e = new CodegenEntry("label|User name#0", "label", "User name",
                LocatorStrategy.CSS, CodegenScorerCssScore.CSS_TAG, LABEL_CSS, LABEL_CSS,
                "username_label", -1, List.of("iframe#login"));

        CodegenFieldPlanner.Plan plan = CodegenFieldPlanner.plan(
                List.of(e), NLS, Map.of(), Set.of(e.sigKey));

        CodegenFieldSpec f = plan.fields.get(0);
        Assert.assertTrue("跨 frame 必须走 @Element + 穿透前缀", f.annotation.startsWith("@Element(\"frame="));
    }

    private static CodegenEntry entry(String sigKey, String nlsKey, String name) {
        return new CodegenEntry(sigKey, "label", name, LocatorStrategy.CSS,
                CodegenScorerCssScore.CSS_TAG, LABEL_CSS, LABEL_CSS, nlsKey, -1);
    }

    /** 收敛分数常量引用，避免测试里散落魔法数字。 */
    private static final class CodegenScorerCssScore {
        static final int CSS_TAG = 530;
    }
}
