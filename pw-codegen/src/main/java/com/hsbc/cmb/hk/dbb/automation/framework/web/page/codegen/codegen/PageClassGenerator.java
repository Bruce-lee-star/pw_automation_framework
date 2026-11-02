package com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen.codegen;

import com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen.model.CodegenEntry;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 生成 Page 类源码（Tab1）。
 *
 * <h2>模板统一（FreeMarker）</h2>
 * <p>类骨架与字段循环全部由 {@code src/main/resources/codegen/templates/page-class.ftl} 描述
 * （与框架报告侧 pw-reports 共用 FreeMarker {@code 2.3.34}）。本类<b>只装配模型</b>：
 * 去重 / 分组 / 命名 / 定位注解一律取自 {@link CodegenFieldPlanner}（Step、Assert 复用同一份
 * {@link CodegenFieldPlanner.Plan}，三个 Tab 的字段名天然一致），渲染走 {@link CodegenTemplates}。
 * 要调整产物格式（缩进、注解样式、import 取舍、是否生成辅助方法）只改模板，不碰 Java。
 *
 * <h2>输出契约（与 V2 {@code RoleElementPageGenerator} 对齐，下游零影响）</h2>
 * <ul>
 *   <li>继承 {@code AbstractManagedPage}（框架页面基类，RoleElementBinder 据此扫描字段）；</li>
 *   <li>字段为 {@code public PageElement}（一组元素为 {@code public PageElementList}）；</li>
 *   <li>存在角色字段且传入了 NLS 文件时生成类级 {@code @RoleFile}；</li>
 *   <li>命中 NLS key 输出 {@code key=}，否则（无 NLS 或反查不到）输出 {@code name=}
 *       —— 即"没有 NLS 就以 name 定位"；</li>
 *   <li>角色无法定位（role 为 generic / 无可读 name）时回退 {@code @Element("<cssSelector>")}
 *       （CSS 与 XPath 统一走 {@code @Element} 的 value）；</li>
 *   <li>同一定位器被拾取多次（序号互异）合成 {@code PageElementList}。</li>
 * </ul>
 *
 * <p>模板内含 {@code <generated:fields>} 标记块，预留与 V2 一致的"再生成增量合并"（F-15）能力。
 *
 * @see StepClassGenerator Tab2（同一批拾取生成的步骤类）
 * @see AssertClassGenerator Tab3（同一批拾取生成的断言类）
 */
public final class PageClassGenerator {

    private static final String TEMPLATE = "page-class.ftl";

    private PageClassGenerator() {}

    /**
     * @param target 生成目标（包名 + 由 URL 派生的类名，见 {@link CodegenTarget}）
     * @param entries 本次拾取到的元素（按拾取顺序）
     * @param nlsFiles NLS bundle 路径（可变参数；为空则一律 name 定位）
     */
    public static String generate(CodegenTarget target, Collection<CodegenEntry> entries, String[] nlsFiles) {
        if (target == null) {
            throw new IllegalArgumentException("codegen target must not be null");
        }
        CodegenFieldPlanner.Plan plan = CodegenFieldPlanner.plan(entries, nlsFiles);

        List<Map<String, Object>> fieldModels = new ArrayList<>(plan.fields.size());
        for (CodegenFieldSpec f : plan.fields) {
            Map<String, Object> fm = new LinkedHashMap<>();
            fm.put("annotation", f.annotation);
            fm.put("fieldType", f.fieldType);
            fm.put("fieldName", f.fieldName);
            fm.put("comment", f.comment == null ? "" : f.comment);
            fieldModels.add(fm);
        }

        Map<String, Object> model = new LinkedHashMap<>();
        model.put("generatedMarker", CodegenMarkers.GENERATED_MARKER);
        model.put("packageName", target.packageName);
        model.put("pageClassName", target.pageClassName);
        model.put("hasAriaRole", plan.hasAriaRole);
        model.put("hasRole", plan.hasRole);
        model.put("hasElement", plan.hasElement);
        model.put("hasList", plan.hasList);
        model.put("classAnnotation", plan.classAnnotation == null ? "" : plan.classAnnotation);
        model.put("fields", fieldModels);
        return CodegenTemplates.render(TEMPLATE, model);
    }
}
