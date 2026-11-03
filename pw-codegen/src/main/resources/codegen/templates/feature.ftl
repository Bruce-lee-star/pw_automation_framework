<#ftl output_format="plainText">
# ${generatedMarker}
<#if tagLine?has_content>${tagLine}
</#if>Feature: ${featureName}
<#list scenarios as s>

  Scenario: ${s.name}
<#list s.steps as st>    ${st.keyword} ${st.text}
</#list></#list>