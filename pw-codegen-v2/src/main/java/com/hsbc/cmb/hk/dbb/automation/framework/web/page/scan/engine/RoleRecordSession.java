package com.hsbc.cmb.hk.dbb.automation.framework.web.page.scan.engine;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.scan.model.RoleEntry;

/**
 * 录制会话 API（Phase 1：人操作 / 机器驱动皆可）。
 *
 * <p>把"在页面上的一系列有序操作"收口为可复用服务，最终经现有生成器三件套产出：
 * <ul>
 *   <li>{@code Page} 类（所有被操作元素去重后的 {@code @RoleElement} / {@code PageElement} 字段）；</li>
 *   <li>{@code Step} 类（每个交互动作 = 一个 {@code stepN()} 方法，含 frame/shadow 切换与 {@code operationFor} 语义）；</li>
 *   <li>{@code Assertion} 类（断言动作 = 一个 {@code assertStepN()} 方法）。</li>
 * </ul>
 *
 * <h2>与现有通道的关系</h2>
 * 本类<b>不重新发明生成逻辑</b>：仅把采集到的 {@link RoleEntry} 按顺序组织成生成器期望的入参
 * （{@code allEntries} + {@code List<List<RoleEntry>> steps}），其余交给
 * {@link RoleElementPageGenerator} / {@link RoleElementStepGenerator} / {@link RoleElementAssertionGenerator}。
 * 因此产物与"拾取面板手动拾取"逐字节等价。
 *
 * <h2>AI 录制（Phase 2）入口</h2>
 * 本 API 同时是后续 AI 编排层的底层契约：AI 每决定一步动作，就调用 {@code recordXxx(...)}
 * （元素由 {@link RolePickerCdpSession} 捕获得到，与 {@code page.pause} Inspector 同源），
 * 结束时 {@link #generate()} 出码。文本类参数（{@code recordType}）由调用方脱敏后传入。
 *
 * <h2>操作语义边界</h2>
 * 操作类型由 {@link RoleEntry} 的标志位驱动（与 {@link RoleElementStepGenerator#operationFor} 一致）：
 * <ul>
 *   <li>{@link ActionType#CLICK}：默认 {@code click()}；</li>
 *   <li>{@link ActionType#TYPE}：置 {@code value} → 文本框/placeholder 角色产出 {@code fill("...")}，
 *       非文本角色退化为 {@code click()}（调用方应确保只对文本类元素调用）；</li>
 *   <li>{@link ActionType#HOVER}：置 {@code hover} → {@code hover()}；</li>
 *   <li>{@link ActionType#RIGHT_CLICK}：置 {@code rightClick} → {@code rightClick()}；</li>
 *   <li>{@link ActionType#ASSERT_VISIBLE}：不进入交互步骤，单独产出 {@code assertThat(...isVisible(), equalTo(true))}。</li>
 * </ul>
 * 受 {@link RoleEntry} 现有字段约束（{@code checked/optionText/pressKey/dragDstKey} 为构造期 final），
 * checkbox 勾选、下拉选择、按键、拖拽等高级语义暂不在 Phase 1 收录，留待 Phase 2 经派生副本补齐。
 *
 * <h2>实例边界（重要）</h2>
 * 每个 {@code recordXxx} 接受的 {@link RoleEntry} 建议为<b>本次拾取得到的独立实例</b>
 * （AI / 人每次点选都产生新实例，天然满足）。同一实例被以<b>不同操作类型</b>多次记录时，
 * 后写入的操作标志（hover / rightClick / value）会覆盖先前设置的值——
 * 这是 Phase 1 已知边界（{@code operationFor} 先判 {@code rightClick} 后判 {@code hover}），
 * 后续经"每动作派生独立副本"彻底消除。
 */
public final class RoleRecordSession {

    /** 受支持的动作类型（见类注释"操作语义边界"）。 */
    public enum ActionType {
        CLICK, TYPE, HOVER, RIGHT_CLICK, ASSERT_VISIBLE
    }

    /** 一条被记录的动作：类型 + 目标元素 + 可选文本参数（TYPE 用）。 */
    public static final class RecordedAction {
        public final ActionType type;
        public final RoleEntry target;
        public final String param;
        RecordedAction(ActionType type, RoleEntry target, String param) {
            this.type = type;
            this.target = target;
            this.param = param;
        }
    }

    /** 生成产物聚合。 */
    public static final class GeneratedArtifact {
        public final String pageSource;
        public final String stepSource;
        public final String assertionSource;
        GeneratedArtifact(String pageSource, String stepSource, String assertionSource) {
            this.pageSource = pageSource;
            this.stepSource = stepSource;
            this.assertionSource = assertionSource;
        }
        /** 三步产物是否全部非空（至少应含页面类与步骤类）。 */
        public boolean isComplete() {
            return pageSource != null && !pageSource.isEmpty()
                    && stepSource != null && !stepSource.isEmpty();
        }
    }

    private final String packageName;
    private final String pageClassName;
    private final String stepClassName;
    private final String[] nlsFiles;
    private final List<RecordedAction> actions = new ArrayList<>();

    private RoleRecordSession(String packageName, String pageClassName, String stepClassName, String[] nlsFiles) {
        this.packageName = Objects.requireNonNull(packageName, "packageName");
        this.pageClassName = Objects.requireNonNull(pageClassName, "pageClassName");
        this.stepClassName = Objects.requireNonNull(stepClassName, "stepClassName");
        this.nlsFiles = (nlsFiles == null) ? new String[0] : nlsFiles;
    }

    /**
     * 创建录制会话。
     *
     * @param packageName    生成类所在包（如 {@code com.hsbc...tests.pages}）
     * @param pageClassName  页面类名（如 {@code LoginPage}）
     * @param stepClassName  步骤类名（如 {@code LoginPageSteps}）
     * @param nlsFiles       类级 {@code @RoleFile} 路径（可多选；不传则退化为可访问名定位）
     */
    public static RoleRecordSession create(String packageName, String pageClassName,
                                          String stepClassName, String... nlsFiles) {
        return new RoleRecordSession(packageName, pageClassName, stepClassName, nlsFiles);
    }

    /** 记录一次点击（默认 {@code click()} 语义）。 */
    public void recordClick(RoleEntry target) {
        requireValid(target);
        actions.add(new RecordedAction(ActionType.CLICK, target, null));
    }

    /** 记录一次输入。文本会写入元素的 {@code value}，使文本类角色产出 {@code fill("...")}。 */
    public void recordType(RoleEntry target, String text) {
        requireValid(target);
        if (text != null) {
            target.setValue(text);
        }
        actions.add(new RecordedAction(ActionType.TYPE, target, text));
    }

    /** 记录一次悬停。 */
    public void recordHover(RoleEntry target) {
        requireValid(target);
        target.setHover(true);
        actions.add(new RecordedAction(ActionType.HOVER, target, null));
    }

    /** 记录一次右键点击。 */
    public void recordRightClick(RoleEntry target) {
        requireValid(target);
        target.setRightClick(true);
        actions.add(new RecordedAction(ActionType.RIGHT_CLICK, target, null));
    }

    /** 记录一次可见性断言（不进入交互步骤，单独产出断言方法）。 */
    public void recordAssertVisible(RoleEntry target) {
        requireValid(target);
        actions.add(new RecordedAction(ActionType.ASSERT_VISIBLE, target, null));
    }

    /** 已记录动作数。 */
    public int size() {
        return actions.size();
    }

    /** 会话是否空（无动作）。 */
    public boolean isEmpty() {
        return actions.isEmpty();
    }

    /** 清空所有已记录动作。 */
    public void clear() {
        actions.clear();
    }

    /**
     * 产出代码：页面类 + 步骤类（+ 断言类）。
     *
     * <p>交互动作（CLICK/TYPE/HOVER/RIGHT_CLICK）进入步骤；ASSERT_VISIBLE 进入断言类；
     * 所有动作的目标元素去重后进入页面类字段。
     */
    public GeneratedArtifact generate() {
        if (actions.isEmpty()) {
            throw new IllegalStateException("录制会话为空：至少记录一个动作才能 generate()");
        }
        // 去重收集所有目标元素（按 locatorKey 身份）。
        LinkedHashMap<String, RoleEntry> allByKey = new LinkedHashMap<>();
        for (RecordedAction a : actions) {
            allByKey.putIfAbsent(RoleElementPageGenerator.locatorKey(a.target), a.target);
        }
        List<RoleEntry> allEntries = new ArrayList<>(allByKey.values());

        // 交互动作 → 每动作一个 step（含单个目标元素）；按记录顺序分配 seq 以保序。
        List<List<RoleEntry>> steps = new ArrayList<>();
        int order = 0;
        for (RecordedAction a : actions) {
            if (a.type == ActionType.ASSERT_VISIBLE) continue;
            a.target.setSeq(++order);
            steps.add(List.of(a.target));
        }

        String pageSource = RoleElementPageGenerator.generate(allEntries, packageName, pageClassName, nlsFiles);
        String stepSource = RoleElementStepGenerator.generate(steps, allEntries, packageName, pageClassName, stepClassName);

        // 断言动作 → assertStepN 方法。
        String assertionSource = null;
        List<List<String>> assertLines = new ArrayList<>();
        Map<String, String> keyToField = buildKeyToField(allEntries);
        String pageVar = RoleElementAssertionGenerator.pageVarOf(pageClassName);
        for (RecordedAction a : actions) {
            if (a.type != ActionType.ASSERT_VISIBLE) continue;
            String field = keyToField.get(RoleElementPageGenerator.locatorKey(a.target));
            if (field != null) {
                assertLines.add(List.of(RoleElementAssertionGenerator.assertStatement(pageVar, field)));
            }
        }
        if (!assertLines.isEmpty()) {
            assertionSource = RoleElementAssertionGenerator.renderClass(packageName, pageClassName, assertLines);
        }
        return new GeneratedArtifact(pageSource, stepSource, assertionSource);
    }

    private static Map<String, String> buildKeyToField(List<RoleEntry> allEntries) {
        Map<String, String> map = new LinkedHashMap<>();
        for (RoleElementPageGenerator.GeneratedField f : RoleElementPageGenerator.assignFields(allEntries)) {
            map.put(RoleElementPageGenerator.locatorKey(f.entry), f.fieldName);
        }
        return map;
    }

    private static void requireValid(RoleEntry target) {
        if (target == null) {
            throw new IllegalArgumentException("录制动作的目标 RoleEntry 不能为 null");
        }
    }
}
