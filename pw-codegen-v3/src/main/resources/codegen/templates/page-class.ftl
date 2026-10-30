<#ftl output_format="plainText">
// ${generatedMarker}
package ${packageName};
<#if hasAriaRole>

import com.microsoft.playwright.options.AriaRole;
</#if>
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.element.PageElement;
<#if hasList>
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.element.PageElementList;
</#if>
<#if hasElement>
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.element.Element;
</#if>
<#if hasRole>
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.element.RoleElement;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.element.RoleFile;
</#if>
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.base.AbstractManagedPage;

<#if classAnnotation?has_content>${classAnnotation}</#if>public class ${pageClassName} extends AbstractManagedPage {

    // <generated:fields>
<#list fields as f><#if f.comment?has_content>${f.comment}
</#if>    ${f.annotation}
    public ${f.fieldType} ${f.fieldName};

</#list>    // </generated:fields>
}
