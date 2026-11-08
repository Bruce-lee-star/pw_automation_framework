package com.hsbc.cmb.hk.dbb.automation.framework.agent.observation;

import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.LocatorSpec;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 观测到的单个页面元素（设计 §4.6 ② 页面观测 / R1/R7 白名单）。
 *
 * <p>仅携带语义化、脱敏后的字段（角色、可访问名、文本、状态、定位候选、受限属性），
 * 不携带原始 DOM 全文、截图或网络细节。对应 ARIA 快照的逐节点抽象。</p>
 *
 * <p><b>多语言（§4.7）</b>：{@code name} 是<b>当前语言</b>下的可访问名（会随语言变化）；
 * {@code nameKey} 是它的 NLS key（跨语言稳定）。提供方若能从元素属性（如 {@code data-i18n}）
 * 直接拿到 key 就填；拿不到可留空，由 {@code RuleBasedUnderstandingEngine} 用 {@code NlsCatalog} 反查补齐。</p>
 *
 * @param role               无障碍角色（role-first 定位第一梯队）
 * @param name               可访问名（当前语言）
 * @param text               脱敏后的可见文本（仅语义用途）
 * @param enabled            是否可用（对应 aria-disabled）
 * @param visible            是否可见
 * @param locatorCandidates  角色优先定位候选梯队（Role-First）
 * @param attributes         受限属性白名单（如 {@code data-testid}），不得含敏感值
 * @param nameKey            可访问名对应的 NLS key（多语言环境；可为 null）
 */
public record ObservedElement(String role, String name, String text,
                              boolean enabled, boolean visible,
                              List<LocatorSpec> locatorCandidates,
                              Map<String, String> attributes,
                              String nameKey) {

    public ObservedElement {
        locatorCandidates = locatorCandidates == null ? Collections.emptyList() : List.copyOf(locatorCandidates);
        attributes = attributes == null ? Collections.emptyMap() : Map.copyOf(attributes);
        nameKey = (nameKey == null || nameKey.isBlank()) ? null : nameKey;
    }

    @Override
    public List<LocatorSpec> locatorCandidates() {
        return Collections.unmodifiableList(locatorCandidates);
    }

    @Override
    public Map<String, String> attributes() {
        return Collections.unmodifiableMap(attributes);
    }

    /** 单语言环境（无 NLS）便捷构造：不带 key。 */
    public ObservedElement(String role, String name, String text, boolean enabled, boolean visible,
                          List<LocatorSpec> locatorCandidates, Map<String, String> attributes) {
        this(role, name, text, enabled, visible, locatorCandidates, attributes, null);
    }

    /** 主定位符（role-first 梯队 0）。 */
    public LocatorSpec primaryLocator() {
        return locatorCandidates.isEmpty() ? null : locatorCandidates.get(0);
    }

    /** 是否带跨语言稳定的 NLS key。 */
    public boolean hasNlsKey() {
        return nameKey != null;
    }

    /** 稳定的语义键（role + name），用于需求关键词映射与去重。 */
    public String semanticKey() {
        return (role == null ? "?" : role) + ":" + (name == null ? "" : name);
    }
}
