package com.hsbc.cmb.hk.dbb.automation.framework.web.page.scan;

/**
 * CDP 捕获层开关（CDP 捕获层改造 Phase 1）。
 *
 * <p>是否用 CDP {@code Overlay.setInspectMode} 替代浏览器侧 JS 注入式拾取。
 * 该机制与 {@code page.pause()} 的 Inspector 完全一致——用户点选元素时由 CDP 直接上报
 * 被点节点的 backendNodeId，Java 侧据其反查 role / 可访问名 / 属性 / 几何盒模型，
 * 从而在「拾取元素目标」上与 page.pause 天然对齐，无需再靠 JS 注入的启发式上溯。
 *
 * <p>灰度默认关闭（与改造方案一致，JS 注入作为兜底）：
 * 设 {@code -Ddbb.codegen.cdp-capture.enabled=true} 即启用 CDP 捕获，获得 page.pause 对齐的拾取。
 * 启用后 {@code registerFrameInjection} 不再向页面注入 JS 拾取库，改为每页建立
 * {@code CDPSession} 并进入 inspect 模式。
 */
final class RolePickerCdpConfig {

    /** 系统属性名：是否启用 CDP 捕获层。 */
    static final String PROP_ENABLED = "dbb.codegen.cdp-capture.enabled";

    private RolePickerCdpConfig() {
    }

    /** 是否启用 CDP 捕获（默认 false，灰度；JS 注入兜底）。 */
    static boolean isEnabled() {
        return Boolean.getBoolean(PROP_ENABLED);
    }
}
