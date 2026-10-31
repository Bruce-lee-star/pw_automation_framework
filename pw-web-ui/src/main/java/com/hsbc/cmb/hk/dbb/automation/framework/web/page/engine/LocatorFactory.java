package com.hsbc.cmb.hk.dbb.automation.framework.web.page.engine;

import com.microsoft.playwright.options.AriaRole;
import com.microsoft.playwright.Frame;
import com.microsoft.playwright.FrameLocator;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.element.RoleElement;

import java.util.regex.Pattern;

/**
 * 定位器工厂（WEB-P1-2 Phase 1）：从 {@link BasePage} 下沉全部 {@code by*} 定位器方法。
 *
 * <p>与 {@link PageFrameShadow} 同源模式：静态工具类、接收 {@code BasePage bp}、经 bp 的
 * 公开 API（{@code getPage} / {@code getCurrentFrame}）与包级 seam（{@code ensurePageValid}）
 * 访问状态。
 *
 * <p>所有 {@code by*} 均为 framework-internal（受 ArchUnit 规则
 * {@code businessCodeMustNotUseInternalByLocators} 约束），业务不得直接调用。
 */
public final class LocatorFactory {

    private LocatorFactory() {
    }

    private static void requireNonNullPage(BasePage bp) {
        if (bp == null) {
            throw new IllegalArgumentException("BasePage instance must not be null when building locators");
        }
    }

    public static Locator byAltText(BasePage bp, String altText) {
        requireNonNullPage(bp);
        bp.ensurePageValid();
        Frame frame = bp.getCurrentFrame();
        Page page = bp.getPage();
        return (frame != null) ? frame.getByAltText(altText) : page.getByAltText(altText);
    }

    /**
     * 跨 frame 穿透定位器前缀：{@code frame=SELa :: SELb :: <内层 css>}。
     * 由 codegen 工具生成（元素位于跨源 iframe 内时），此处解析为
     * {@code page.frameLocator(SELa).frameLocator(SELb).locator(<内层 css>)}。
     */
    static final String FRAME_PIERCE_PREFIX = "frame=";
    private static final String FRAME_PIERCE_SEP = " :: ";

    /**
     * 按 <b>CSS / XPath 选择器</b>定位，返回原生 Playwright {@code Locator}。
     *
     * <p>补齐工厂的"选择器入口"：本类其余 {@code by*} 均为语义定位（{@code getByRole} /
     * {@code getByText} / {@code getByLabel} …），不含 CSS/XPath 通道，导致需要选择器时只能
     * 绕过工厂直取 {@code BasePage.locatorInternal}（跨层调用内部 seam）。补本方法后，
     * <b>全部定位入口（语义 + 选择器）统一收敛于 {@code LocatorFactory}</b>。
     *
     * <p>支持两类上下文穿透前缀：
     * <ul>
     *   <li><b>跨 frame</b>：选择器以 {@code frame=} 开头时，按 {@code :: } 切分为若干 frame 选择器
     *       与最后一个内层元素选择器，解析为 {@code page.frameLocator(SELa).…locator(<内层>)}，
     *       可定位跨源 iframe 内的元素（此时忽略当前 frame 根，始终从主页面下钻）；</li>
     *   <li><b>shadow DOM</b>：非 frame 前缀时，把宿主栈以 {@code >>>} 穿透组合器拼到选择器前缀。</li>
     * </ul>
     * 这两点是 {@code page.locator(selector)} 直连无法提供的，故不可简化为直接取 page。
     *
     * @param bp       页面对象（不得为 null）
     * @param selector CSS / XPath 选择器，或跨 frame 穿透前缀（不得为 null / 空白）
     */
    public static Locator bySelector(BasePage bp, String selector) {
        requireNonNullPage(bp);
        if (selector == null || selector.trim().isEmpty()) {
            throw new IllegalArgumentException("selector must not be null or blank when building locators");
        }
        bp.ensurePageValid();
        // 跨 frame 穿透：frame=SELa :: SELb :: <内层 css>
        if (selector.startsWith(FRAME_PIERCE_PREFIX)) {
            return buildFramePiercingLocator(bp, selector);
        }
        // shadow 上下文高于 iframe/DOM 层：用 >>> 穿透组合器把宿主前缀拼到选择器前。
        String shadowPrefix = bp.shadowPrefix();
        String resolved = shadowPrefix.isEmpty() ? selector : shadowPrefix + selector;
        Frame frame = bp.getCurrentFrame();
        if (frame != null) {
            return frame.locator(resolved);
        }
        return bp.getPage().locator(resolved);
    }

    /**
     * 解析跨 frame 穿透选择器：{@code frame=SELa :: SELb :: <内层 css>} →
     * {@code page.frameLocator(SELa).frameLocator(SELb).locator(<内层 css>)}。
     * 内层选择器仍应用 shadow 前缀（若当前处于 shadow 宿主内）。
     */
    private static Locator buildFramePiercingLocator(BasePage bp, String selector) {
        String body = selector.substring(FRAME_PIERCE_PREFIX.length());
        String[] parts = body.split(FRAME_PIERCE_SEP, -1);
        // 至少需要一个 frame 选择器 + 一个内层元素选择器
        if (parts.length < 2 || parts[parts.length - 1].trim().isEmpty()) {
            throw new IllegalArgumentException(
                    "frame-piercing selector must be 'frame=<frameSel> :: <innerSel>' with at least one frame segment");
        }
        String inner = parts[parts.length - 1];
        String shadowPrefix = bp.shadowPrefix();
        String resolvedInner = shadowPrefix.isEmpty() ? inner : shadowPrefix + inner;
        Page page = bp.getPage();
        FrameLocator chain = page.frameLocator(parts[0].trim());
        for (int i = 1; i < parts.length - 1; i++) {
            chain = chain.frameLocator(parts[i].trim());
        }
        return chain.locator(resolvedInner);
    }

    public static Locator byRole(BasePage bp, AriaRole role) {
        requireNonNullPage(bp);
        bp.ensurePageValid();
        Frame frame = bp.getCurrentFrame();
        Page page = bp.getPage();
        return (frame != null) ? frame.getByRole(role) : page.getByRole(role);
    }

    public static Locator byRole(BasePage bp, AriaRole role, String name) {
        return byRole(bp, role, name, true);
    }

    public static Locator byRole(BasePage bp, AriaRole role, Pattern namePattern) {
        requireNonNullPage(bp);
        bp.ensurePageValid();
        Frame frame = bp.getCurrentFrame();
        Page page = bp.getPage();
        return (frame != null)
                ? frame.getByRole(role, new Frame.GetByRoleOptions().setName(namePattern))
                : page.getByRole(role, new Page.GetByRoleOptions().setName(namePattern));
    }

    public static Locator byRole(BasePage bp, AriaRole role, String name, boolean exact) {
        requireNonNullPage(bp);
        bp.ensurePageValid();
        Frame frame = bp.getCurrentFrame();
        Page page = bp.getPage();
        return (frame != null)
                ? frame.getByRole(role, new Frame.GetByRoleOptions().setName(name).setExact(exact))
                : page.getByRole(role, new Page.GetByRoleOptions().setName(name).setExact(exact));
    }

    public static Locator byRole(BasePage bp, AriaRole role, String name, boolean exact, int level) {
        requireNonNullPage(bp);
        bp.ensurePageValid();
        Frame frame = bp.getCurrentFrame();
        Page page = bp.getPage();
        Page.GetByRoleOptions popts = new Page.GetByRoleOptions().setName(name).setExact(exact);
        Frame.GetByRoleOptions fopts = new Frame.GetByRoleOptions().setName(name).setExact(exact);
        if (level > 0) {
            popts = popts.setLevel(level);
            fopts = fopts.setLevel(level);
        }
        return (frame != null) ? frame.getByRole(role, fopts) : page.getByRole(role, popts);
    }

    public static Locator byRole(BasePage bp, AriaRole role, Pattern namePattern, int level) {
        requireNonNullPage(bp);
        bp.ensurePageValid();
        Frame frame = bp.getCurrentFrame();
        Page page = bp.getPage();
        Page.GetByRoleOptions popts = new Page.GetByRoleOptions().setName(namePattern);
        Frame.GetByRoleOptions fopts = new Frame.GetByRoleOptions().setName(namePattern);
        if (level > 0) {
            popts = popts.setLevel(level);
            fopts = fopts.setLevel(level);
        }
        return (frame != null) ? frame.getByRole(role, fopts) : page.getByRole(role, popts);
    }

    public static Locator byRole(BasePage bp, AriaRole role, String name, boolean exact, int level,
                                 RoleElement.State disabled, RoleElement.State pressed, RoleElement.State expanded) {
        requireNonNullPage(bp);
        bp.ensurePageValid();
        Frame frame = bp.getCurrentFrame();
        Page page = bp.getPage();
        Page.GetByRoleOptions popts = new Page.GetByRoleOptions().setName(name).setExact(exact);
        Frame.GetByRoleOptions fopts = new Frame.GetByRoleOptions().setName(name).setExact(exact);
        if (level > 0) {
            popts = popts.setLevel(level);
            fopts = fopts.setLevel(level);
        }
        if (disabled != null && disabled != RoleElement.State.ANY)  {popts = popts.setDisabled(disabled == RoleElement.State.YES);} 
        if (pressed != null && pressed != RoleElement.State.ANY)  {popts = popts.setPressed(pressed == RoleElement.State.YES);} 
        if (expanded != null && expanded != RoleElement.State.ANY)  {popts = popts.setExpanded(expanded == RoleElement.State.YES);} 
        if (disabled != null && disabled != RoleElement.State.ANY)  {fopts = fopts.setDisabled(disabled == RoleElement.State.YES);} 
        if (pressed != null && pressed != RoleElement.State.ANY)  {fopts = fopts.setPressed(pressed == RoleElement.State.YES);} 
        if (expanded != null && expanded != RoleElement.State.ANY)  {fopts = fopts.setExpanded(expanded == RoleElement.State.YES);} 
        return (frame != null) ? frame.getByRole(role, fopts) : page.getByRole(role, popts);
    }

    public static Locator byRole(BasePage bp, AriaRole role, Pattern namePattern, int level,
                                 RoleElement.State disabled, RoleElement.State pressed, RoleElement.State expanded) {
        requireNonNullPage(bp);
        bp.ensurePageValid();
        Frame frame = bp.getCurrentFrame();
        Page page = bp.getPage();
        Page.GetByRoleOptions popts = new Page.GetByRoleOptions().setName(namePattern);
        Frame.GetByRoleOptions fopts = new Frame.GetByRoleOptions().setName(namePattern);
        if (level > 0) {
            popts = popts.setLevel(level);
            fopts = fopts.setLevel(level);
        }
        if (disabled != null && disabled != RoleElement.State.ANY)  {popts = popts.setDisabled(disabled == RoleElement.State.YES);} 
        if (pressed != null && pressed != RoleElement.State.ANY)  {popts = popts.setPressed(pressed == RoleElement.State.YES);} 
        if (expanded != null && expanded != RoleElement.State.ANY)  {popts = popts.setExpanded(expanded == RoleElement.State.YES);} 
        if (disabled != null && disabled != RoleElement.State.ANY)  {fopts = fopts.setDisabled(disabled == RoleElement.State.YES);} 
        if (pressed != null && pressed != RoleElement.State.ANY)  {fopts = fopts.setPressed(pressed == RoleElement.State.YES);} 
        if (expanded != null && expanded != RoleElement.State.ANY)  {fopts = fopts.setExpanded(expanded == RoleElement.State.YES);} 
        return (frame != null) ? frame.getByRole(role, fopts) : page.getByRole(role, popts);
    }

    public static Locator byTitle(BasePage bp, String title) {
        requireNonNullPage(bp);
        bp.ensurePageValid();
        Frame frame = bp.getCurrentFrame();
        Page page = bp.getPage();
        return (frame != null) ? frame.getByTitle(title) : page.getByTitle(title);
    }

    public static Locator byTestId(BasePage bp, String testId) {
        requireNonNullPage(bp);
        bp.ensurePageValid();
        Frame frame = bp.getCurrentFrame();
        Page page = bp.getPage();
        // G-1 修复：原实现裸拼 testId 存在定位器注入面。主属性 data-testid 走 Playwright 内建
        // getByTestId（自带转义语义，闭合注入面）；其余三种历史兼容属性（data-test-id/data-test/data-qa）
        // 用转义后的 CSS 选择器补充，并经 .or() 合并，保留兼容匹配且不引入注入。
        Locator primary = (frame != null) ? frame.getByTestId(testId) : page.getByTestId(testId);
        String compat = cssAttrEquals("data-test-id", testId) + ","
                + cssAttrEquals("data-test", testId) + ","
                + cssAttrEquals("data-qa", testId);
        Locator compatLoc = (frame != null) ? frame.locator(compat) : page.locator(compat);
        return primary.or(compatLoc);
    }

    /**
     * 构造 CSS「属性等于」选择器，并对属性值中的特殊字符（{@code \} 与 {@code "}）做转义，
     * 避免 {@code testId} 含特殊字符时破坏 selector 而产生定位器注入面。
     * 转义顺序须先转义反斜杠、再转义双引号（等价 Playwright 内建 {@code getByTestId} 的转义语义）。
     */
    private static String cssAttrEquals(String attr, String value) {
        String escaped = value.replace("\\", "\\\\").replace("\"", "\\\"");
        return "[" + attr + "=\"" + escaped + "\"]";
    }

    public static Locator byText(BasePage bp, String text) {
        return byText(bp, text, true);
    }

    public static Locator byText(BasePage bp, String text, boolean exact) {
        requireNonNullPage(bp);
        bp.ensurePageValid();
        Frame frame = bp.getCurrentFrame();
        Page page = bp.getPage();
        return (frame != null)
                ? frame.getByText(text, new Frame.GetByTextOptions().setExact(exact))
                : page.getByText(text, new Page.GetByTextOptions().setExact(exact));
    }

    public static Locator byAltText(BasePage bp, String altText, boolean exact) {
        requireNonNullPage(bp);
        bp.ensurePageValid();
        Frame frame = bp.getCurrentFrame();
        Page page = bp.getPage();
        return (frame != null)
                ? frame.getByAltText(altText, new Frame.GetByAltTextOptions().setExact(exact))
                : page.getByAltText(altText, new Page.GetByAltTextOptions().setExact(exact));
    }

    public static Locator byTitle(BasePage bp, String title, boolean exact) {
        requireNonNullPage(bp);
        bp.ensurePageValid();
        Frame frame = bp.getCurrentFrame();
        Page page = bp.getPage();
        return (frame != null)
                ? frame.getByTitle(title, new Frame.GetByTitleOptions().setExact(exact))
                : page.getByTitle(title, new Page.GetByTitleOptions().setExact(exact));
    }

    public static Locator byPlaceholder(BasePage bp, String placeholder) {
        return byPlaceholder(bp, placeholder, true);
    }

    public static Locator byPlaceholder(BasePage bp, String placeholder, boolean exact) {
        requireNonNullPage(bp);
        bp.ensurePageValid();
        Frame frame = bp.getCurrentFrame();
        Page page = bp.getPage();
        return (frame != null)
                ? frame.getByPlaceholder(placeholder, new Frame.GetByPlaceholderOptions().setExact(exact))
                : page.getByPlaceholder(placeholder, new Page.GetByPlaceholderOptions().setExact(exact));
    }

    public static Locator byLabel(BasePage bp, String label) {
        return byLabel(bp, label, true);
    }

    public static Locator byLabel(BasePage bp, String label, boolean exact) {
        requireNonNullPage(bp);
        bp.ensurePageValid();
        Frame frame = bp.getCurrentFrame();
        Page page = bp.getPage();
        return (frame != null)
                ? frame.getByLabel(label, new Frame.GetByLabelOptions().setExact(exact))
                : page.getByLabel(label, new Page.GetByLabelOptions().setExact(exact));
    }

    public static Locator byText(BasePage bp, Pattern text) {
        requireNonNullPage(bp);
        bp.ensurePageValid();
        Frame frame = bp.getCurrentFrame();
        Page page = bp.getPage();
        return (frame != null) ? frame.getByText(text) : page.getByText(text);
    }

    public static Locator byAltText(BasePage bp, Pattern altText) {
        requireNonNullPage(bp);
        bp.ensurePageValid();
        Frame frame = bp.getCurrentFrame();
        Page page = bp.getPage();
        return (frame != null) ? frame.getByAltText(altText) : page.getByAltText(altText);
    }

    public static Locator byTitle(BasePage bp, Pattern title) {
        requireNonNullPage(bp);
        bp.ensurePageValid();
        Frame frame = bp.getCurrentFrame();
        Page page = bp.getPage();
        return (frame != null) ? frame.getByTitle(title) : page.getByTitle(title);
    }

    public static Locator byPlaceholder(BasePage bp, Pattern placeholder) {
        requireNonNullPage(bp);
        bp.ensurePageValid();
        Frame frame = bp.getCurrentFrame();
        Page page = bp.getPage();
        return (frame != null) ? frame.getByPlaceholder(placeholder) : page.getByPlaceholder(placeholder);
    }

    public static Locator byLabel(BasePage bp, Pattern label) {
        requireNonNullPage(bp);
        bp.ensurePageValid();
        Frame frame = bp.getCurrentFrame();
        Page page = bp.getPage();
        return (frame != null) ? frame.getByLabel(label) : page.getByLabel(label);
    }
}
