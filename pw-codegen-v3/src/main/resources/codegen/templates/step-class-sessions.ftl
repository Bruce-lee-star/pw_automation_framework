<#ftl output_format="plainText">
// ${generatedMarker}
package ${stepPackageName};

import com.hsbc.cmb.hk.dbb.automation.framework.report.annotation.Step;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.factory.PageObjectFactory;
<#list imports as imp>import ${imp};
</#list>
public class ${stepClassName} {
<#list methods as m>

    @Step
    public void ${m.name}() {
<#list m.bodyLines as l>${l}
</#list>    }
</#list>}
