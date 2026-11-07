package com.hsbc.cmb.hk.dbb.automation.framework.agent.impact;

import java.util.Objects;

/**
 * 代码层落点：一次变更在「被测工程源码/资源」里可能必须修改的位置。
 *
 * <p><b>为什么需要它</b>：本工程的 feature 步骤是粗粒度业务动作
 * （如 {@code Given logon DBB "O63_SIT1" environment as user "..."}），
 * 元素级变更（按钮改名/文案调整）<b>不需要改 feature</b>，真正要改的是
 * {@code @RoleElement}/{@code @Element} 声明与 NLS 资源。
 * 只做 feature 分诊会把「必须改」判成 0 条 —— 结论对但没用，故补这一层。</p>
 *
 * @param kind       落点类型（页面对象声明 / 资源文件条目）
 * @param file       相对扫描根的路径（{@code \} 已归一为 {@code /}）
 * @param line        1 起行号，便于直接跳转
 * @param identifier 声明的标识：{@code name=}/{@code key=} 取值、选择器原文、NLS 键
 * @param snippet     命中处原文摘要（已折叠空白、截断，不含整行以上的上下文）
 * @param stability   选择器稳定性（{@link SelectorFacts.Stability#label()}；非选择器声明为空串）
 * @param advice      稳定化建议（含随机值时的替代写法；稳定时为空串）
 */
public record CodeTarget(Kind kind, String file, int line, String identifier, String snippet,
                         String stability, String advice) {

    /** 落点类型。 */
    public enum Kind {
        PAGE_OBJECT("页面对象"),
        RESOURCE("资源文件");

        private final String label;

        Kind(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    public CodeTarget {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(file, "file");
        identifier = identifier == null ? "" : identifier;
        snippet = snippet == null ? "" : snippet;
        stability = stability == null ? "" : stability;
        advice = advice == null ? "" : advice;
        if (line < 1) {
            throw new IllegalArgumentException("line must be >= 1: " + line);
        }
    }

    /** 非选择器声明（{@code @RoleElement}、NLS 条目）用：无稳定性信息。 */
    public CodeTarget(Kind kind, String file, int line, String identifier, String snippet) {
        this(kind, file, line, identifier, snippet, "", "");
    }

    /** 是否携带稳定化建议（含随机值时）。 */
    public boolean hasAdvice() {
        return !advice.isEmpty();
    }
}
