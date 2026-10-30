package com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen;

import com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen.locator.LocatorStrategy;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen.model.CodegenEntry;

/**
 * role 条目的「复核失败 ⇒ 回退 CSS」修订。
 *
 * <p>「拾取只取 AX 语义、生成前用 Playwright 引擎补校验」链路的产物收敛点：
 * 复核失败时不能改动 {@code sigKey}（录制动作按它绑定字段，改了会与拾取条目失联），只切换策略与选择器。
 */
final class RoleRevision {

    private RoleRevision() {
    }

    /**
     * 生成 CSS 策略的等价条目。
     *
     * <p>保留 {@code role/name/score/index/framePath/shadowHosts/nlsKey}：
     * <ul>
     *   <li>{@code sigKey} 稳定 ⇒ 录制动作仍能匹配到该字段；</li>
     *   <li>{@code score} 保留拾取时的分值（复核不重算 CSS 候选分；面板该列仅作可读性提示，不参与生成）；</li>
     *   <li>{@code framePath/shadowHosts} 保留 ⇒ 生成器仍会拼出 {@code frame=… :: <css>} / {@code host >>> <css>}。</li>
     * </ul>
     *
     * @param entry 原条目（可为 {@code null}，返回 {@code null}）
     * @return CSS 策略条目；{@code cssSelector} 为空时退化为 {@code html}（与抽取阶段兜底一致）
     */
    static CodegenEntry toCss(CodegenEntry entry) {
        if (entry == null) return null;
        String css = (entry.cssSelector == null || entry.cssSelector.isEmpty()) ? "html" : entry.cssSelector;
        return new CodegenEntry(entry.sigKey, entry.role, entry.name, LocatorStrategy.CSS, entry.score,
                css, css, entry.nlsKey, entry.index, entry.framePath, entry.shadowHosts);
    }
}
