package com.hsbc.cmb.hk.dbb.automation.framework.agent.impact;

import java.util.List;
import java.util.Objects;

/**
 * 变更影响报告：<b>用例侧</b>（{@link CaseImpactScreener.Report}）+ <b>代码侧</b>（{@link CodeTarget}）。
 *
 * <p>两层缺一不可：本工程 feature 步骤是粗粒度业务动作，元素级变更往往「feature 不需要改」，
 * 只给用例分诊会得出「必须维护 0 条」这种正确但无用的结论；代码层落点才指出真正要改的行。</p>
 *
 * @param cases       用例侧分诊结果
 * @param codeTargets 代码层落点（可为空：未给 {@code --sources} 或未命中）
 */
public record ImpactReport(CaseImpactScreener.Report cases, List<CodeTarget> codeTargets) {

    public ImpactReport {
        Objects.requireNonNull(cases, "cases");
        codeTargets = List.copyOf(codeTargets == null ? List.of() : codeTargets);
    }

    /**
     * 完整 Markdown 报告：用例表格 + 代码层落点表格 + 固定边界声明。
     *
     * @param onlyActionable {@code true} 时用例表格只列「必须维护/需复核」
     */
    public String toMarkdown(boolean onlyActionable) {
        StringBuilder sb = new StringBuilder(cases.toMarkdown(onlyActionable));
        sb.append("\n## 代码层落点（页面对象 / 资源）\n\n");
        if (codeTargets.isEmpty()) {
            sb.append("未命中任何 `@RoleElement`/`@Element` 声明或资源键值。")
                    .append("若变更确属元素级，请确认 `--sources` 是否覆盖被测工程源码与资源目录。\n");
        } else {
            sb.append("| 类型 | 位置 | 标识 | 稳定性 | 片段 / 建议 |\n")
                    .append("| --- | --- | --- | --- | --- |\n");
            for (CodeTarget target : codeTargets) {
                sb.append("| ").append(target.kind().label())
                        .append(" | ").append(cell(target.file() + ":" + target.line()))
                        .append(" | ").append(cell("`" + target.identifier() + "`"))
                        .append(" | ").append(cell(target.hasAdvice() ? target.stability() : "—"))
                        .append(" | ").append(cell(detail(target)))
                        .append(" |\n");
            }
            sb.append("\n> 说明：这些是**极可能要改**的代码位置（元素变更通常改这里、不改 feature），")
                    .append("仍属词面命中，需人工确认；用 NLS 键间接引用（常量/拼接）的情形可能未被发现。")
                    .append("\n> 「含随机值」表示选择器里出现 UUID/时间戳/框架生成 id、class 等每次渲染都会变的值：")
                    .append("应改用前缀 `^=`、后缀 `$=`、包含 `*=`、多属性组合，或父级稳定属性 + 元素 tag/role 限定。\n");
        }
        return sb.toString();
    }

    private static String detail(CodeTarget target) {
        if (!target.hasAdvice()) {
            return target.snippet();
        }
        return target.snippet() + " — 建议: " + target.advice();
    }

    private static String cell(String value) {
        return value == null ? "" : value.replace("|", "\\|").replace("\n", " ");
    }
}
