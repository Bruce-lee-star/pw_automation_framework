package com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen.cdp;

/**
 * 拾取时「这个名字可信吗」的判定规则（纯函数，无 IO、无浏览器）。
 *
 * <p>两条规则都源自同一个现场：<b>提交类按钮点一下就变 loading</b>。
 *
 * <h2>① 状态播报区不算标签（{@link #stableName}）</h2>
 * <p>HSBC 的按钮里常驻一个 {@code aria-live} 的 {@code "Loading, please wait."} {@code <span>}，
 * 浏览器算出的可访问名是 {@code "Loading, please wait. Continue"}。直接落进产物会得到
 * {@code name = "Loading, please wait. Continue"} 与字段名 {@code loadingPleaseWaitContinueBtn}——
 * 既不符合用户看到的「Continue」，也会随播报文案变化而失效。
 * 页面侧计算名字时已跳过状态播报区，这里在拿到 AX 权威名后用「AX 名 − 播报文本 == 页面侧名」做校验，
 * 校验通过才收窄；不满足就<b>原样保留 AX 名</b>（AX 仍是权威来源，绝不做模糊猜测）。
 *
 * <h2>② 点击改写了文案 ⇒ 用点击前的名字（{@link #restorePreClickName}）</h2>
 * <p>抽取发生在 click 处理器之后：按钮变 loading 后名字只剩 {@code "Loading, please wait."}，
 * 但这只说明「现在不是那一步」，与定位器可用性无关。只要元素<b>自身文本</b>在捕获与抽取之间发生了变化，
 * 就说明这次点击改写了它——用捕获阶段的名字还原，才对应回放时那一步的元素状态。
 *
 * <p>两条规则都是「有证据才动」，证据不足一律返回原值：宁可产物名字难看，也不能凭空造名字。
 */
final class PickNamePolicy {

    private PickNamePolicy() {
    }

    /**
     * AX 权威名里混入了状态播报文本时，退回收窄后的稳定标签。
     *
     * @param axName       {@code Accessibility.getPartialAXTree} 给出的可访问名（权威）
     * @param strippedName 页面侧计算的、已跳过状态播报区的名字；空表示「没有可用标签」
     * @param statusText   元素内部状态播报区（{@code aria-live} / {@code role=status|alert|log}）的文本
     * @return 收窄后的名字；证据不足时原样返回 {@code axName}
     */
    static String stableName(String axName, String strippedName, String statusText) {
        String ax = trimToNull(axName);
        String stripped = trimToNull(strippedName);
        String status = trimToNull(statusText);
        if (ax == null) return axName;                 // 没有 AX 名：保持原逻辑（回退页面侧推断）
        if (stripped == null || status == null) return axName;   // 没播报区 ⇒ 无从怀疑
        if (stripped.equals(ax)) return axName;
        // 必须是「AX 名 − 播报文本 == 页面侧标签」这种精确拼接形态，否则不猜。
        String remainder = ax.replace(status, "").replaceAll("\\s+", " ").trim();
        return remainder.equals(stripped) ? stripped : axName;
    }

    /**
     * 点击改写了元素自身文案时，用捕获阶段的名字还原。
     *
     * @param name          抽取阶段得到的名字（可能是点击之后的 loading 文案）
     * @param postClickText 抽取阶段读到的元素自身文本
     * @param pre           捕获阶段快照；{@code null} / 空表示无证据
     * @return 还原后的名字；无证据或名字未变时原样返回 {@code name}
     */
    static String restorePreClickName(String name, String postClickText, PreClickSnapshot pre) {
        if (pre == null || pre.isEmpty()) return name;
        String preName = trimToNull(pre.name());
        if (preName == null) return name;
        if (preName.equals(trimToNull(name))) return name;      // 名字没变：无需干预
        if (!ownTextChanged(pre.text(), postClickText)) return name;   // 文案没变：AX 名可信
        return preName;
    }

    /**
     * 元素<b>自身文本</b>是否在捕获与抽取之间发生了变化。
     *
     * <p>只有「捕获时有文本，抽取时变成别的（含变空）」才算变化：捕获阶段没有文本证据时无法断言，
     * 一律返回 {@code false}（不还原），避免把「页面侧与 AX 侧算名口径不同」误当成点击改写。
     */
    private static boolean ownTextChanged(String preText, String postText) {
        String before = trimToNull(preText);
        if (before == null) return false;
        String after = trimToNull(postText);
        return !before.equals(after);
    }

    private static String trimToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
