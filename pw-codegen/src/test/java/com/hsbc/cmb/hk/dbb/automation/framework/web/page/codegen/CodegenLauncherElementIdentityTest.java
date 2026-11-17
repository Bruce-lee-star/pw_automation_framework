package com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen;

import com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen.model.CodegenEntry;
import org.junit.Assert;
import org.junit.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 「是不是同一个元素」判定（{@link CodegenLauncher#matchFieldKey}）与孪生元素 sigKey 消歧
 * （{@link CodegenLauncher#disambiguateSigKey}）。
 *
 * <p>回归背景（真实运行日志）：同一表单里两处 {@code <button>Continue</button>}（role=button、
 * name=Continue，各在自己的父容器里都是第 0 个）——第一处拾取成功后，<b>第二处的点击被 role+name
 * 反查判成「已在 Elements 里」而直接短路</b>：既不生成定位、也不留日志。用户看到的就是
 * 「有两个 Continue 按钮，只有第一个生成了定位器」。
 */
public class CodegenLauncherElementIdentityTest {

    /** 构造一条已拾取的 Continue 条目（identity 为空 = 无页面侧身份）。 */
    private static CodegenLauncher.Pick pick(String cssSelector, String docId, String eid) {
        CodegenEntry entry = new CodegenEntry("button|Continue#0", "button", "Continue", "CSS",
                80, "", cssSelector, "button_continue", 0);
        CodegenLauncher.Pick p = new CodegenLauncher.Pick(1L, 1L, 1, entry, null, "https://host/page", null, false);
        p.identify(docId, eid);
        return p;
    }

    private static Map<String, Object> info(String cssSelector, String docId, String eid) {
        Map<String, Object> m = new HashMap<>();
        m.put("role", "button");
        m.put("name", "Continue");
        m.put("tag", "button");
        m.put("cssSelector", cssSelector);
        if (docId != null) m.put("docId", docId);
        if (eid != null) m.put("eid", eid);
        return m;
    }

    @Test
    public void twinButtonWithSameRoleAndName_isNotTreatedAsAlreadyPicked() {
        List<CodegenLauncher.Pick> picked = new ArrayList<>();
        picked.add(pick("#sectionA > button", "doc-1", "e1"));

        // 同一文档里的另一个 Continue 按钮：role/name 相同、路径不同、eid 不同 ⇒ 必须是新元素。
        String key = CodegenLauncher.matchFieldKey(info("#sectionB > button", "doc-1", "e2"), picked);

        Assert.assertNull("同 role+name 的孪生元素绝不能并到第一个条目上", key);
    }

    @Test
    public void sameElementIsMatchedByIdentityEvenWhenSelectorPathChanged() {
        List<CodegenLauncher.Pick> picked = new ArrayList<>();
        picked.add(pick("#sectionA > button", "doc-1", "e1"));

        // 同一节点（eid 相同），但结构路径被页面改写（插入兄弟节点 / loading 重排）⇒ 仍须命中同一条目。
        String key = CodegenLauncher.matchFieldKey(info("div:nth-of-type(9) > button", "doc-1", "e1"), picked);

        Assert.assertEquals("button|Continue#0", key);
    }

    @Test
    public void sameEidInAnotherDocument_isNotTheSameElement() {
        List<CodegenLauncher.Pick> picked = new ArrayList<>();
        picked.add(pick("#sectionA > button", "doc-1", "e1"));

        // 导航后 eid 会从 1 重新分配：只比 eid 就会把新页的第 1 个元素误判成上一页那个。
        String key = CodegenLauncher.matchFieldKey(info("#other > button", "doc-2", "e1"), picked);

        Assert.assertNull(key);
    }

    @Test
    public void legacyPayloadWithoutIdentity_fallsBackToCssThenRoleName() {
        List<CodegenLauncher.Pick> picked = new ArrayList<>();
        picked.add(pick("#sectionA > button", "doc-1", "e1"));

        Assert.assertEquals("旧载荷按 CSS 精确相等匹配", "button|Continue#0",
                CodegenLauncher.matchFieldKey(info("#sectionA > button", null, null), picked));
        Assert.assertEquals("无身份信息时保留 role+name 兜底", "button|Continue#0",
                CodegenLauncher.matchFieldKey(info("#sectionB > button", null, null), picked));
    }

    @Test
    public void disambiguateSigKey_appendsSuffixForTwinElement() {
        List<CodegenLauncher.Pick> picked = new ArrayList<>();
        picked.add(pick("#sectionA > button", "doc-1", "e1"));

        CodegenEntry twin = new CodegenEntry("button|Continue#0", "button", "Continue", "CSS",
                80, "", "#sectionB > button", "button_continue", 0);
        CodegenEntry rewritten = CodegenLauncher.disambiguateSigKey(twin, "doc-1", "e2", picked);

        Assert.assertEquals("button|Continue#0@2", rewritten.sigKey);
        Assert.assertEquals("定位器不得被消歧改写", "#sectionB > button", rewritten.cssSelector);
        Assert.assertEquals("组内序号不得被消歧改写", 0, rewritten.index);
        Assert.assertEquals("原条目不可被就地修改", "button|Continue#0", twin.sigKey);
    }

    @Test
    public void disambiguateSigKey_keepsSigKeyForSameElement() {
        List<CodegenLauncher.Pick> picked = new ArrayList<>();
        picked.add(pick("#sectionA > button", "doc-1", "e1"));

        CodegenEntry same = new CodegenEntry("button|Continue#0", "button", "Continue", "CSS",
                80, "", "#sectionA > button", "button_continue", 0);

        Assert.assertSame("同一元素（同 identity）重复拾取保持原 sigKey，交由字段规划去重",
                same, CodegenLauncher.disambiguateSigKey(same, "doc-1", "e1", picked));
    }

    @Test
    public void disambiguateSigKey_thirdTwinGetsNextSuffix() {
        List<CodegenLauncher.Pick> picked = new ArrayList<>();
        picked.add(pick("#sectionA > button", "doc-1", "e1"));
        picked.add(pick("#sectionB > button", "doc-1", "e2"));

        CodegenEntry third = new CodegenEntry("button|Continue#0", "button", "Continue", "CSS",
                80, "", "#sectionC > button", "button_continue", 0);

        Assert.assertEquals("button|Continue#0@3",
                CodegenLauncher.disambiguateSigKey(third, "doc-1", "e3", picked).sigKey);
    }

    @Test
    public void disambiguateSigKey_withoutIdentityKeepsSigKey() {
        List<CodegenLauncher.Pick> picked = new ArrayList<>();
        picked.add(pick("#sectionA > button", "doc-1", "e1"));

        CodegenEntry legacy = new CodegenEntry("button|Continue#0", "button", "Continue", "CSS",
                80, "", "#sectionB > button", "button_continue", 0);

        Assert.assertSame("无身份信息时不做消歧（无法区分孪生与同元素）", legacy,
                CodegenLauncher.disambiguateSigKey(legacy, "", "", picked));
    }

    @Test
    public void twinBaseOf_stripsOnlyNumericSuffix() {
        Assert.assertEquals("button|Continue#0", CodegenLauncher.twinBaseOf("button|Continue#0@2"));
        Assert.assertEquals("button|Continue#0", CodegenLauncher.twinBaseOf("button|Continue#0"));
        Assert.assertEquals("a@b", CodegenLauncher.twinBaseOf("a@b"));
        Assert.assertEquals("", CodegenLauncher.twinBaseOf(null));
    }
}
