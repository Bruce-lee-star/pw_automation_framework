package com.hsbc.cmb.hk.dbb.automation.framework.agent.analysis;

import com.hsbc.cmb.hk.dbb.automation.framework.agent.analysis.engine.AssetDraftBundle;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.analysis.engine.ElementInventoryEntry;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.analysis.engine.RuleBasedUnderstandingEngine;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.analysis.requirement.RequirementContext;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.analysis.requirement.RequirementEntry;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.analysis.requirement.RequirementId;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.asset.ElementRef;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.LocatorSpec;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.nls.JsonNlsCatalog;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.observation.ObservedElement;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.observation.PageObservation;
import org.junit.Test;

import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

/**
 * 证明 {@code data-i18n} 比"可访问名反查"更精确：当多个 NLS key 映射到<b>同一文案</b>时，
 * 反查只能返回文件中首个匹配的 key（顺序依赖、歧义），而 {@code data-i18n} 直接给出精确 key。
 *
 * <p>场景：{@code other.submit} 与 {@code shared.submit} 文案都是 "Submit"，但语义不同
 * （弹窗提交 vs 登录提交）。</p>
 */
public class DataI18nPrecisionTest {

    private static final String FILE = "nls/ambiguous.nls.json";

    /** 构造一个观测元素（role=button，文案 "Submit"）。 */
    private static ObservedElement button(boolean withI18nKey, String nameKey) {
        return new ObservedElement("button", "Submit", "Submit", true, true,
                List.of(LocatorSpec.role("button", "Submit")), Map.of(),
                withI18nKey ? nameKey : null);
    }

    private static RequirementContext requirement() {
        RequirementEntry req = new RequirementEntry(new RequirementId("REQ-SUBMIT"),
                "提交操作", List.of(), List.of(), "submit.md");
        return new RequirementContext(List.of(req), "req");
    }

    private static PageObservation observation(ObservedElement... els) {
        return new PageObservation("https://bank.example/page", "page", List.of(els), "manual");
    }

    private static ElementRef analyze(ObservedElement el) {
        AssetDraftBundle bundle = new RuleBasedUnderstandingEngine(JsonNlsCatalog.ofLanguage("en", FILE))
                .analyze(requirement(), observation(el));
        ElementInventoryEntry entry = bundle.inventory().entries().get(0);
        return entry.ref();
    }

    @Test
    public void dataI18nDisambiguatesWhileReverseLookupCannot() {
        // 两个语义不同的按钮，文案都是 "Submit"
        ObservedElement loginSubmit = button(true, "shared.submit");   // data-i18n 精确
        ObservedElement dialogSubmit = button(true, "other.submit");   // data-i18n 精确
        ObservedElement loginNoKey = button(false, null);              // 无埋点，靠反查
        ObservedElement dialogNoKey = button(false, null);             // 无埋点，靠反查

        ElementRef rLogin = analyze(loginSubmit);
        ElementRef rDialog = analyze(dialogSubmit);
        ElementRef rLoginNoKey = analyze(loginNoKey);
        ElementRef rDialogNoKey = analyze(dialogNoKey);

        // data-i18n 路径：精确区分两个同文案按钮
        assertEquals("button:shared.submit", rLogin.refId());
        assertEquals("button:other.submit", rDialog.refId());
        assertNotEquals("data-i18n 应区分两个不同语义的 Submit 按钮", rLogin.refId(), rDialog.refId());

        // 反查路径：两个同文案按钮得到同一资产主键（无法区分，歧义）
        assertEquals("反查无法区分两个不同语义的 Submit 按钮",
                rLoginNoKey.refId(), rDialogNoKey.refId());

        // 反查得到的 key 无法同时满足两个语义（此处文件中 other.submit 先于 shared.submit，
        // 故反查统一落到 other.submit，登录按钮被错误归属）
        assertEquals("button:other.submit", rLoginNoKey.refId());
    }
}
