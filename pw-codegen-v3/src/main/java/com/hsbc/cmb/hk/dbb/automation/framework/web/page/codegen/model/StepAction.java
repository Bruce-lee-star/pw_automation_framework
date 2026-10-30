package com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen.model;

/**
 * 一个「生成步骤动作」：页面步骤方法体内的<b>一行</b>操作。
 *
 * <p>多页面 / 多 frame / dialog 改造后，步骤方法体不再只是「元素 click/fill」，还要插入：
 * <ul>
 *   <li>{@link Kind#ELEMENT} —— 普通元素操作（{@code page.field.click()} / {@code page.field.fill("…")}）；</li>
 *   <li>{@link Kind#FRAME_SWITCH} —— 切换到某个 iframe（框架 {@code switchToFrame(String)}）；</li>
 *   <li>{@link Kind#DIALOG} —— 处理原生对话框（框架 {@code acceptAlert()} / {@code dismissAlert()}）。</li>
 * </ul>
 *
 * <p>页面切换（popup / 新标签页）动作不出现在单个页面步骤里，而是由「场景流程」Step 类统一编排
 * （{@code waitForNewPage(...)}），故此处不含页面切换类型。
 *
 * <p>纯数据、不可变、无业务依赖，便于单测。
 */
public final class StepAction {

    public enum Kind { ELEMENT, FRAME_SWITCH, DIALOG }

    public final Kind kind;
    /** ELEMENT：字段名（与 Page 类生成的字段名逐字一致）。 */
    public final String fieldName;
    /** ELEMENT：动作表达式（已含实参，如 {@code click()} / {@code fill("alice")}）。 */
    public final String action;
    /** ELEMENT：占位值提示（空 fill 时给出 TODO，已回填真实值则为空串）。 */
    public final String todo;
    /** FRAME_SWITCH：最内层 iframe 的选择器（传给 {@code switchToFrame}）。 */
    public final String frameSelector;
    /** FRAME_SWITCH：完整 frame 穿透路径（仅作注释，便于人读）。 */
    public final String framePath;
    /** DIALOG：{@code true}=accept，{@code false}=dismiss。 */
    public final boolean accept;

    private StepAction(Kind kind, String fieldName, String action, String todo,
                       String frameSelector, String framePath, boolean accept) {
        this.kind = kind;
        this.fieldName = fieldName;
        this.action = action;
        this.todo = todo;
        this.frameSelector = frameSelector;
        this.framePath = framePath;
        this.accept = accept;
    }

    public static StepAction element(String fieldName, String action, String todo) {
        return new StepAction(Kind.ELEMENT, fieldName, action, todo, null, null, false);
    }

    public static StepAction frameSwitch(String frameSelector, String framePath) {
        return new StepAction(Kind.FRAME_SWITCH, null, null, null, frameSelector, framePath, false);
    }

    public static StepAction dialog(boolean accept) {
        return new StepAction(Kind.DIALOG, null, null, null, null, null, accept);
    }
}
