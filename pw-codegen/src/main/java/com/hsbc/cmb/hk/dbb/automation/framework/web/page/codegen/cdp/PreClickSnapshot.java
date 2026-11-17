package com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen.cdp;

/**
 * 点击<b>捕获阶段</b>（页面自身 click 处理器执行之前）记下的元素快照。
 *
 * <h2>为什么需要它</h2>
 * <p>拾取是由「用户点击元素」触发的，但真正的 CDP 抽取（{@code DOM.resolveNode} + {@code Accessibility.getPartialAXTree}）
 * 是<b>异步</b>的：等它落到拾取引擎线程执行时，页面的 click 处理器早已跑完。于是任何「点一下就改自己文案」的控件，
 * 抽到的都是<b>点击之后</b>的可访问名——典型是提交类按钮立即进入 loading 态：
 *
 * <pre>
 * 点击前：{@code <button><span aria-live="polite">Loading, please wait.</span>Continue</button>} → name "Continue"
 * 点击后：{@code <button disabled><span aria-live="polite">Loading, please wait.</span></button>} → name "Loading, please wait."
 * </pre>
 *
 * <p>后者的产物是 {@code @RoleElement(role = BUTTON, name = "Loading, please wait.")} 与
 * {@code loadingPleaseWaitBtn}——回放时那一步的按钮文案其实是 {@code "Continue"}，定位器直接打空。
 * 而注入脚本的 click 监听注册在 window <b>捕获</b>阶段，比页面自身处理器更早执行，因此它同步采集的
 * {@code name} / {@code text} 才是「这一步真正要操作的那个元素」的样子。
 *
 * <h2>与 {@code info.checked} 同源</h2>
 * <p>{@code CodegenLauncher#resolveCheckedState} 早就用同一份捕获阶段事实纠正勾选态（跨线程时序竞态），
 * 本记录只是把同一证据用于名字。
 *
 * @param name 点击前由页面侧计算的元素名（{@code factsOf.name}，已剔除状态播报区文本）
 * @param text 点击前的元素自身文本（{@code factsOf.text}，保留状态播报区，用于判断「文案是否被点击改写」）
 */
public record PreClickSnapshot(String name, String text) {

    /** 空快照：旧版注入脚本 / 事件未携带 {@code info} 时使用，语义等价于「无证据」。 */
    public static final PreClickSnapshot EMPTY = new PreClickSnapshot(null, null);

    /** 归一化：{@code null} 与空白都收敛成 {@code null}，下游只判「有没有证据」。 */
    public static PreClickSnapshot of(String name, String text) {
        return new PreClickSnapshot(blankToNull(name), blankToNull(text));
    }

    public boolean isEmpty() {
        return name == null && text == null;
    }

    private static String blankToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
