package com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen.codegen;

import com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen.model.CodegenEntry;

import java.util.ArrayList;
import java.util.List;

/**
 * 装配层：把「按会话拾取到的元素」翻译成三份产物（Page / Step / Assert）。
 *
 * <h2>产物模型：会话 = 一个 step</h2>
 * <p>用户的录制习惯是「点开始拾取 → 操作一串元素 → 点停止」，这个 <b>Start→Pause 区间就是一步</b>。
 * 因此：
 * <ul>
 *   <li><b>Page</b>：按「<b>拾取时的 URL</b>」归集字段——同一标签页内导航（A→B）会产出两个独立
 *       Page 类（{@code APage} / {@code BPage}），旧类不被吞掉；同 URL 跨会话合并、字段名稳定；</li>
 *   <li><b>Step</b>：<b>一个会话 = 一个 {@code @Step} 方法</b>（{@code step1} / {@code step2} …）；</li>
 *   <li><b>Assert</b>：同理，一个会话 = 一个 {@code @Step} 方法（{@code assert1} / {@code assert2} …）。</li>
 * </ul>
 *
 * <h2>同一步跨多页 / 同页导航怎么办</h2>
 * <p>一个会话里可能既操作了 A 页又操作了 B 页（例如点「个人中心」后进入新页再填表单）。
 * 此时该 step 方法<b>在方法体内同时实例化多个页面类</b>（局部变量），元素操作分别落到各自的
 * 页面对象上；跨标签页切换处生成 {@code aPage.waitForNewPage(() -> aPage.trigger.click())}，
 * 与 v2 的 {@code acceptNewPage} 语义一致（新页由框架接管为当前页，页面对象自动跟随）。
 * <b>同一标签页内的 URL 跳转（A→B）不算切页</b>：不生成 {@code waitForNewPage}，只在步骤体插入
 * 一条「已导航」注释，页面对象在方法顶部已实例化、直接复用即可（用户选择：不引入等待语义）。
 *
 * <p>本类只做<b>纯装配</b>（不依赖 Playwright）：Playwright 侧的页面/元素信息由
 * {@code CodegenLauncher} 先折算成 {@link PageUnit} / {@link SessionUnit} 再传进来，便于单测。
 */
public final class MultiPageCodegen {

    private MultiPageCodegen() {
    }

    /** 一页的 Page 类输入：元素字段按页归集（跨会话合并），{@code plan} 与 Step/Assert 同源。 */
    public static final class PageUnit {
        public final CodegenTarget target;
        /** 该页全部元素，按真实操作顺序（字段命名基准）。 */
        public final List<CodegenEntry> entries;
        /** 字段规划（Page 类 / Step 动作 / Assert 断言共用，保证字段名一致）。 */
        public final CodegenFieldPlanner.Plan plan;

        public PageUnit(CodegenTarget target, List<CodegenEntry> entries, CodegenFieldPlanner.Plan plan) {
            this.target = target;
            this.entries = entries == null ? List.of() : new ArrayList<>(entries);
            this.plan = plan;
        }
    }

    /** 会话内某一页的断言输入（只含本会话拾取的元素，保持操作顺序）。 */
    public static final class PageEntries {
        public final CodegenTarget target;
        public final List<CodegenEntry> entries;
        /** 该页的全局字段规划（保证断言里的字段名与 Page 类一致）。 */
        public final CodegenFieldPlanner.Plan plan;

        public PageEntries(CodegenTarget target, List<CodegenEntry> entries, CodegenFieldPlanner.Plan plan) {
            this.target = target;
            this.entries = entries == null ? List.of() : new ArrayList<>(entries);
            this.plan = plan;
        }
    }

    /** 会话内动作种类。 */
    public enum ActionKind {
        /** 元素操作（{@code page.field.action}）。 */
        ELEMENT,
        /** 切 iframe（{@code page.switchToFrame("…")}）。 */
        FRAME_SWITCH,
        /** 处理浏览器 dialog（{@code page.acceptAlert()/dismissAlert()}）。 */
        DIALOG,
        /** 跨页切换：{@code from.waitForNewPage(() -> from.trigger.action)}。 */
        PAGE_SWITCH,
        /** 同标签页导航提示：不切页、不等待，仅注释「已导航到 X 页」（如需要可加 waitForLoadState）。 */
        NAVIGATION
    }

    /** 会话内的一条动作。 */
    public static final class SessionAction {
        public final ActionKind kind;
        /** 动作所属页面（PAGE_SWITCH 时为<b>来源页</b>）。 */
        public final CodegenTarget target;
        /** ELEMENT / PAGE_SWITCH 的元素字段名（与 Page 类同源）。 */
        public final String fieldName;
        /** ELEMENT / PAGE_SWITCH 的动作表达式（{@code click()} / {@code fill("…")} …）。 */
        public final String action;
        /** 非空时在动作上方生成 {@code // TODO(review): …}。 */
        public final String todo;
        /** FRAME_SWITCH：iframe 定位选择器。 */
        public final String frameSelector;
        /** FRAME_SWITCH：frame 路径（仅用于注释可读性）。 */
        public final String framePath;
        /** DIALOG：true=accept，false=dismiss。 */
        public final boolean accept;
        /** PAGE_SWITCH：目标页。 */
        public final CodegenTarget toTarget;
        /** NAVIGATION：提示注释（同标签页 URL 跳转说明）。 */
        public final String note;

        private SessionAction(ActionKind kind, CodegenTarget target, String fieldName, String action,
                              String todo, String frameSelector, String framePath, boolean accept,
                              CodegenTarget toTarget, String note) {
            this.kind = kind;
            this.target = target;
            this.fieldName = fieldName;
            this.action = action;
            this.todo = todo;
            this.frameSelector = frameSelector;
            this.framePath = framePath;
            this.accept = accept;
            this.toTarget = toTarget;
            this.note = note;
        }

        public static SessionAction element(CodegenTarget target, String fieldName, String action, String todo) {
            return new SessionAction(ActionKind.ELEMENT, target, fieldName, action, todo, null, null, false, null, null);
        }

        public static SessionAction frameSwitch(CodegenTarget target, String selector, String path) {
            return new SessionAction(ActionKind.FRAME_SWITCH, target, null, null, null, selector, path, false, null, null);
        }

        public static SessionAction dialog(CodegenTarget target, boolean accept) {
            return new SessionAction(ActionKind.DIALOG, target, null, null, null, null, null, accept, null, null);
        }

        public static SessionAction pageSwitch(CodegenTarget from, String triggerField, String triggerAction,
                                               CodegenTarget toTarget) {
            return new SessionAction(ActionKind.PAGE_SWITCH, from, triggerField, triggerAction, null, null, null,
                    false, toTarget, null);
        }

        /** 同标签页导航提示：在步骤体插入「已导航」注释，不生成任何等待 / 切页代码。 */
        public static SessionAction navigation(CodegenTarget from, CodegenTarget to) {
            String note = "same-tab navigation: now on " + to.pageClassName
                    + " (was " + from.pageClassName + " on the same tab); the page object is re-fetched below — "
                    + "add waitForLoadState(...) here if the new page needs to settle before the next action";
            return new SessionAction(ActionKind.NAVIGATION, null, null, null, null, null, null, false, null, note);
        }
    }

    /**
     * 一次 {@code Start→Pause} 会话：产物里的一个 {@code stepN()} / {@code assertN()} 方法。
     */
    public static final class SessionUnit {
        /** 方法序号（从 1 开始；跳过空会话后连续编号）。 */
        public final int index;
        /** 命名来源（本会话第一个页面），决定 Step/Assert 类名与包名。 */
        public final CodegenTarget owner;
        /** 本会话涉及的页面（按首次出现顺序）——方法体内逐个实例化。 */
        public final List<CodegenTarget> pages;
        /** 本会话的动作序列（元素操作 / frame / dialog / 切页）。 */
        public final List<SessionAction> actions;
        /** 本会话各页的元素（断言用）。 */
        public final List<PageEntries> asserts;

        public SessionUnit(int index, CodegenTarget owner, List<CodegenTarget> pages,
                           List<SessionAction> actions, List<PageEntries> asserts) {
            this.index = index;
            this.owner = owner;
            this.pages = pages == null ? List.of() : new ArrayList<>(pages);
            this.actions = actions == null ? List.of() : new ArrayList<>(actions);
            this.asserts = asserts == null ? List.of() : new ArrayList<>(asserts);
        }
    }

    /** 一个独立的代码块（面板三栏各自的「页内切换器」切换单元）。 */
    public static final class CodeBlock {
        /** 切换器上显示的标签（页面/步骤/断言类名）。 */
        public final String label;
        /** 该块的完整代码文本。 */
        public final String code;

        public CodeBlock(String label, String code) {
            this.label = label;
            this.code = code;
        }
    }

    /** 三份拼接产物 + 供面板三栏渲染的块列表。 */
    public static final class MultiPageResult {
        public final String pageCode;
        public final String stepCode;
        public final String assertCode;
        /** Page 栏：一页一个块（标签 = 页面类名）。 */
        public final List<CodeBlock> pageBlocks;
        /** Step 栏：会话式 Step 类（单块）。 */
        public final List<CodeBlock> stepBlocks;
        /** Assert 栏：会话式 Assert 类（单块）。 */
        public final List<CodeBlock> assertBlocks;

        public MultiPageResult(String pageCode, String stepCode, String assertCode,
                               List<CodeBlock> pageBlocks, List<CodeBlock> stepBlocks, List<CodeBlock> assertBlocks) {
            this.pageCode = pageCode;
            this.stepCode = stepCode;
            this.assertCode = assertCode;
            this.pageBlocks = pageBlocks;
            this.stepBlocks = stepBlocks;
            this.assertBlocks = assertBlocks;
        }
    }

    /**
     * 装配三份产物。
     *
     * @param pages    每页的 Page 类输入（跨会话合并）
     * @param sessions 会话序列（一次 Start→Pause 一项，index 从 1 连续）
     * @param nlsFiles NLS bundle 路径（断言用；null/空表示无 NLS）
     */
    public static MultiPageResult build(List<PageUnit> pages, List<SessionUnit> sessions, String[] nlsFiles) {
        List<PageUnit> pageList = pages == null ? List.of() : pages;
        List<SessionUnit> sessionList = sessions == null ? List.of() : sessions;

        // 1) Page 类：一页一个块（元素字段跨会话合并后仍按页归集）。
        List<CodeBlock> pageBlocks = new ArrayList<>(pageList.size());
        StringBuilder pageCode = new StringBuilder();
        for (PageUnit u : pageList) {
            String code = PageClassGenerator.generate(u.target, u.entries, nlsFiles);
            pageBlocks.add(new CodeBlock(u.target.pageClassName, code));
            if (pageCode.length() > 0) pageCode.append("\n\n");
            pageCode.append(code);
        }

        // 2) Step / Assert：整个录制一个类，一个会话一个方法。
        CodegenTarget header = headerOf(pageList, sessionList);
        String stepCode = "";
        String assertCode = "";
        List<CodeBlock> stepBlocks = new ArrayList<>();
        List<CodeBlock> assertBlocks = new ArrayList<>();
        if (header != null && !sessionList.isEmpty()) {
            stepCode = StepClassGenerator.generateSessions(header, sessionList);
            assertCode = AssertClassGenerator.generateSessions(header, sessionList, nlsFiles);
            stepBlocks.add(new CodeBlock(header.stepClassName, stepCode));
            assertBlocks.add(new CodeBlock(header.assertClassName, assertCode));
        }

        return new MultiPageResult(pageCode.toString(), stepCode, assertCode, pageBlocks, stepBlocks, assertBlocks);
    }

    /** 命名来源：优先本会话第一个页面；无会话时退回第一个页面。 */
    private static CodegenTarget headerOf(List<PageUnit> pages, List<SessionUnit> sessions) {
        for (SessionUnit s : sessions) {
            if (s.owner != null) return s.owner;
        }
        return pages.isEmpty() ? null : pages.get(0).target;
    }
}
