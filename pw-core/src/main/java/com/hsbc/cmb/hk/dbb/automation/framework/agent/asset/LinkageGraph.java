package com.hsbc.cmb.hk.dbb.automation.framework.agent.asset;

import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.Command;

import java.util.Collections;
import java.util.List;

/**
 * 联动图谱资产（设计 §4.6 ③）。描述：在某元素执行某操作后，哪些目标元素发生状态变化。
 * 是验证驱动（AssetVerifier）的核心输入，回链需求条目（需求映射）。
 */
public final class LinkageGraph {
    private final String requirementId;          // 关联需求（§4.6 需求映射）
    private final List<LinkageRule> rules;

    public LinkageGraph(String requirementId, List<LinkageRule> rules) {
        this.requirementId = requirementId;
        this.rules = rules == null ? Collections.emptyList() : rules;
    }

    public String requirementId() { return requirementId; }
    public List<LinkageRule> rules() { return Collections.unmodifiableList(rules); }

    /** 单条联动规则：触发操作 → 期望目标状态变化。 */
    public static final class LinkageRule {
        private final ElementRef trigger;
        private final Command.ActionType triggerAction;
        /** 触发器携带的参数（FILL 的值 / SELECT 的选项；CLICK 等无需参数时为空）。 */
        private final String triggerValue;
        private final List<ExpectedChange> expectations;

        public LinkageRule(ElementRef trigger, Command.ActionType triggerAction, List<ExpectedChange> expectations) {
            this(trigger, triggerAction, "", expectations);
        }

        public LinkageRule(ElementRef trigger, Command.ActionType triggerAction, String triggerValue, List<ExpectedChange> expectations) {
            this.trigger = trigger;
            this.triggerAction = triggerAction;
            this.triggerValue = triggerValue == null ? "" : triggerValue;
            this.expectations = expectations == null ? Collections.emptyList() : expectations;
        }
        public ElementRef trigger() { return trigger; }
        public Command.ActionType triggerAction() { return triggerAction; }
        public String triggerValue() { return triggerValue; }
        public List<ExpectedChange> expectations() { return Collections.unmodifiableList(expectations); }
    }

    /** 期望的状态变化（如"用途字段出现且必填"）。 */
    public static final class ExpectedChange {
        private final ElementRef target;
        private final ChangeKind kind;
        private final String expectedText;     // TEXT_CHANGED 时使用（字面文案），可为 null
        private final String expectedTextKey;  // TEXT_CHANGED 时使用（NLS key，多语言首选），可为 null

        public ExpectedChange(ElementRef target, ChangeKind kind, String expectedText) {
            this(target, kind, expectedText, null);
        }

        public ExpectedChange(ElementRef target, ChangeKind kind, String expectedText, String expectedTextKey) {
            this.target = target;
            this.kind = kind;
            this.expectedText = expectedText;
            this.expectedTextKey = expectedTextKey;
        }

        /**
         * 多语言断言：期望文本用 <b>NLS key</b> 表达（设计 §4.7）。
         *
         * <p>与 codegen 一致：断言文案也不写死，执行期按当前语言解析；模板值按正则匹配。
         * 这样一条 TEXT_CHANGED 断言可在任意语言下复用。</p>
         */
        public static ExpectedChange textChanged(ElementRef target, String expectedTextKey) {
            return new ExpectedChange(target, ChangeKind.TEXT_CHANGED, null, expectedTextKey);
        }

        public ElementRef target() { return target; }
        public ChangeKind kind() { return kind; }
        public String expectedText() { return expectedText; }
        public String expectedTextKey() { return expectedTextKey; }

        public enum ChangeKind { APPEAR, DISAPPEAR, ENABLE, DISABLE, TEXT_CHANGED, REQUIRED }
    }
}
