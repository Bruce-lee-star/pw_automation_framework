package com.hsbc.cmb.hk.dbb.automation.framework.web.agent;

import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.LocatorRoleVocabulary;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.LocatorSpec;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.nls.NlsCatalog;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.nls.NlsText;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.AriaRole;

import java.util.regex.Pattern;

/**
 * Role-First 定位梯队 → Playwright {@link Locator} 的<b>唯一</b>翻译实现（设计 §4.5）。
 *
 * <p>抽成独立类是为了让"翻译"只有一份：命令通道（{@link PlaywrightChannel}）、候选取证探针
 * （codegen 侧 {@code PlaywrightEvidenceProbe}）都必须走同一条翻译路径。若各写一份，
 * 出现"探测时命中 1 个、执行时命中 0 个"这类鬼故事时根本无从对账——而候选验证的全部意义
 * 就在于"探测的就是将来执行的那条"。</p>
 *
 * <p><b>多语言</b>：ROLE 梯队的名若以 NLS key 表达（{@code nameKey}），先按当前语言解析成文案
 * 再 {@code getByRole(role,{name})}；值含模板（{@code {{var}}}）时改用 {@code setName(Pattern)}。
 * 这与 codegen 的 {@code Nls.t(key)} 完全同义，故资产可跨语言复用。</p>
 *
 * <p><b>映射契约（T11）</b>：ROLE 分支的 {@link AriaRole} 常量名一律经
 * {@link LocatorRoleVocabulary#requireAriaConstant(String)} 取得，与 codegen 生成
 * {@code @RoleElement(role = AriaRole.X)} 的口径同源。这里的 {@code require} 是刻意选择的
 * fail-closed：历史资产里带 CDP AX 别名（{@code image}）或连字符（{@code menuitem-checkbox}）
 * 时按同一规范归一，未知角色则显式拒绝——静默换成另一种角色会定位到错误元素且无从对账。</p>
 */
public final class LocatorResolver {

    private LocatorResolver() {
    }

    /**
     * 解析为页面上的 Playwright 定位器。
     *
     * @param page 目标页面
     * @param spec 与浏览器无关的定位描述
     * @param nls  多语言目录（可空：单语言场景只有字面文案）
     * @return 未取值的 {@link Locator}（可能命中 0..n 个元素，由调用方决定如何判定）
     */
    public static Locator resolve(Page page, LocatorSpec spec, NlsCatalog nls) {
        if (page == null) {
            throw new IllegalArgumentException("page 不可为空");
        }
        if (spec == null) {
            throw new IllegalArgumentException("spec 不可为空");
        }
        switch (spec.strategy()) {
            case ROLE: {
                AriaRole role = AriaRole.valueOf(LocatorRoleVocabulary.requireAriaConstant(spec.role()));
                Page.GetByRoleOptions opts = new Page.GetByRoleOptions();
                Pattern namePattern = null;
                String name = spec.name();
                if (spec.nameKey() != null && nls != null) {
                    String raw = nls.value(spec.nameKey());
                    if (NlsText.isTemplate(raw)) {
                        namePattern = NlsText.pattern(raw);
                    } else {
                        name = nls.visibleText(spec.nameKey());
                    }
                }
                // 字面名里也可能带模板占位符（资产直接存了 NLS 原始值，或快照里就是 "Welcome, {{user}}"）。
                // 把它当普通字符串去比必然 0 命中——而且不报错，只是"突然找不到元素"，
                // 排查成本极高。带 {{}} 的名字一律按正则匹配。
                if (namePattern == null && NlsText.isTemplate(name)) {
                    namePattern = NlsText.pattern(name);
                }
                if (namePattern != null) {
                    opts = opts.setName(namePattern);
                } else if (name != null && !name.isEmpty()) {
                    opts = opts.setName(name);
                }
                Locator l = page.getByRole(role, opts);
                int nth = spec.nth();
                return nth == 0 ? l : l.nth(nth);
            }
            case TEST_ID:
                return page.getByTestId(spec.testId());
            case ID:
                return page.locator("#" + spec.id());
            case TEXT:
                return page.getByText(spec.text());
            case CSS:
                return page.locator(spec.css());
            default:
                throw new IllegalArgumentException("Unsupported locator strategy: " + spec.strategy());
        }
    }

    /**
     * 定位符里声明的「期望可访问名」——字面文案，或 {@code nameKey} 在当前语言下的解析结果。
     *
     * <p>模板（{@code {{var}}}）原样返回：真实值由页面注入，无法静态求值，比较方须按正则处理。
     * 返回 {@code null} 表示该定位符<b>没有</b>声明任何期望名——此时"文案是否一致"无从判断，
     * 按 fail-closed 处理，不得当成"一致"。</p>
     */
    public static String expectedName(LocatorSpec spec, NlsCatalog nls) {
        if (spec == null) {
            return null;
        }
        if (spec.name() != null && !spec.name().isEmpty()) {
            return spec.name();
        }
        if (spec.nameKey() != null && nls != null) {
            String raw = nls.value(spec.nameKey());
            return raw == null ? null : raw;
        }
        return null;
    }
}
