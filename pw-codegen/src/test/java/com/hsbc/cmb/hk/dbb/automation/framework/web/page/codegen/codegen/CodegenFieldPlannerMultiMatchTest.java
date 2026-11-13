package com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen.codegen;

import com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen.locator.LocatorStrategy;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen.model.CodegenEntry;
import org.junit.Assert;
import org.junit.Test;

import java.util.List;
import java.util.Map;

/**
 * 问题⑥：单次拾取时若所有唯一化尝试都失败（最终定位器仍命中多个），必须产出
 * {@code PageElementList}，而不是丢弃这条拾取、也不是硬塞成单元素 {@code PageElement}
 * （后者在运行期 strict mode 下必然多命中失败）。
 */
public class CodegenFieldPlannerMultiMatchTest {

    private static final String CSS = "button[class*=\"row-action\"]";

    /** 命中多个 ⇒ PageElementList（元素组语义），注解仍用同一个多匹配 CSS。 */
    @Test
    public void multiMatchEntry_becomesPageElementList() {
        CodegenEntry e = new CodegenEntry("button|Delete#0", "button", "Delete",
                LocatorStrategy.CSS, 525, CSS, CSS, "", -1, List.of(), List.of(), 3);

        CodegenFieldPlanner.Plan plan = CodegenFieldPlanner.plan(List.of(e), new String[]{}, Map.of());

        CodegenFieldSpec f = plan.fields.get(0);
        Assert.assertEquals("PageElementList", f.fieldType);
        Assert.assertTrue(f.isList);
        Assert.assertTrue(plan.hasList);
        Assert.assertTrue("应保留原定位器（它命中整组）", f.annotation.contains("row-action"));
        Assert.assertTrue("组元素只作用 get(0)，产物必须显式标注 TODO",
                f.comment != null && f.comment.contains("get(0)"));
    }

    /** 0 命中（或计数不可判定）：不得静默给出死定位器，产物须提示人工核对。 */
    @Test
    public void zeroMatchEntry_isFlaggedInGeneratedCode() {
        CodegenEntry e = new CodegenEntry("button|Delete#0", "button", "Delete",
                LocatorStrategy.CSS, 525, CSS, CSS, "", -1, List.of(), List.of(), 0);

        CodegenFieldPlanner.Plan plan = CodegenFieldPlanner.plan(List.of(e), new String[]{}, Map.of());

        CodegenFieldSpec f = plan.fields.get(0);
        Assert.assertEquals("0 命中不得被抬成 1 而丢失信息", 0, f.entry.matchCount);
        Assert.assertTrue("产物必须标注 0 命中", f.comment != null && f.comment.contains("matched 0 element(s)"));
    }

    private static CodegenEntry roleEntry(int cssCount, int roleHits) {
        return new CodegenEntry("button|Delete#0", "button", "Delete",
                LocatorStrategy.ROLE_WITH_NAME, 105, "role=button[name=\"Delete\"]", CSS,
                null, 0, List.of(), List.of(), cssCount, "button", false, roleHits);
    }

    /**
     * 回归 P0-1：<b>两个引擎的计数必须彻底解耦</b>。
     *
     * <p>历史实现把「兜底 CSS 候选」的计数当成 role 条目的命中数：CSS 命中 5、role 引擎实际只命中 1 时，
     * 这里会被判成一组 ⇒ 产出 {@code PageElementList + get(0)}，组语义静默降级（用户以为改一处、
     * 实际只操作第一个），且不触发任何告警。现在 role 条目读 {@code roleHits}，与 CSS 计数无关。
     */
    @Test
    public void roleStrategy_matchCount_must_not_reuse_css_count() {
        CodegenEntry e = roleEntry(5, 1);   // CSS 命中 5，role 复核只命中 1

        CodegenFieldPlanner.Plan plan = CodegenFieldPlanner.plan(List.of(e), new String[]{}, Map.of());

        CodegenFieldSpec f = plan.fields.get(0);
        Assert.assertEquals("role 复核为 1 命中 ⇒ PageElement，不得被 CSS 的 5 带偏",
                "PageElement", f.fieldType);
        Assert.assertFalse(f.isList);
        Assert.assertEquals(1, e.effectiveMatchCount());
    }

    /** 反向：CSS 唯一但 role 引擎命中 3 个 ⇒ 必须判成一组，否则运行期 strict violation。 */
    @Test
    public void roleStrategy_verifiedMultiHits_becomesPageElementList() {
        CodegenEntry e = roleEntry(1, 3);

        CodegenFieldPlanner.Plan plan = CodegenFieldPlanner.plan(List.of(e), new String[]{}, Map.of());

        CodegenFieldSpec f = plan.fields.get(0);
        Assert.assertEquals("PageElementList", f.fieldType);
        Assert.assertTrue(f.isList);
    }

    /** 未复核 / 不可判定：不得静默当成唯一，产物必须显式标注待人工校验。 */
    @Test
    public void roleStrategy_unverified_isFlaggedInsteadOfAssumedUnique() {
        for (int hits : new int[]{CodegenEntry.ROLE_HITS_UNVERIFIED,
                CodegenEntry.ROLE_HITS_UNDETERMINABLE}) {
            CodegenEntry e = roleEntry(5, hits);

            CodegenFieldPlanner.Plan plan = CodegenFieldPlanner.plan(List.of(e), new String[]{}, Map.of());

            CodegenFieldSpec f = plan.fields.get(0);
            Assert.assertEquals("PageElement", f.fieldType);
            Assert.assertFalse(f.isList);
            Assert.assertTrue("未复核必须显式标注: " + hits,
                    f.comment != null && f.comment.contains("NOT verified"));
        }
    }

    /** 唯一命中 ⇒ PageElement（行为不变）。 */
    @Test
    public void singleMatchEntry_staysPageElement() {
        CodegenEntry e = new CodegenEntry("button|Delete#0", "button", "Delete",
                LocatorStrategy.CSS, 525, CSS, CSS, "", -1, List.of(), List.of(), 1);

        CodegenFieldPlanner.Plan plan = CodegenFieldPlanner.plan(List.of(e), new String[]{}, Map.of());

        CodegenFieldSpec f = plan.fields.get(0);
        Assert.assertEquals("PageElement", f.fieldType);
        Assert.assertFalse(f.isList);
    }
}
