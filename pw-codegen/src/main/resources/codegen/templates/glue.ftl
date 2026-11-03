<#ftl output_format="plainText">
// ${generatedMarker}
package ${gluePackageName};

${importBlock}/**
 * Glue 层：仅把 Feature 短语委托给录制的 Steps 类，不含定位器与业务流。
 *
 * <p>本类由 P2「需求 → 代码绑定」生成，请勿手改；需求变更后重新生成。
 */
public class ${glueClassName} {

    private final ${stepsClassName} ${stepsVar} = new ${stepsClassName}();
<#list methods as m>

    @${m.annotation}("${m.phrase}")
    public void ${m.methodName}(${m.methodParams}) {
<#if m.absorbedParams?has_content>        // 录制步骤无参：短语参数由 Steps 内部消化（${m.absorbedParams}）
</#if>        ${stepsVar}.${m.delegateCall};
    }
</#list>
}
