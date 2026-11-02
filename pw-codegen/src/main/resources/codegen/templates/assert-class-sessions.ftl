<#ftl output_format="plainText">
// ${generatedMarker}
package ${assertPackageName};

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
<#if hasNlsAssert>
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import com.hsbc.cmb.hk.dbb.automation.framework.web.utils.NLSUtils;
</#if>
import com.hsbc.cmb.hk.dbb.automation.framework.report.annotation.Step;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.factory.PageObjectFactory;
<#list imports as imp>import ${imp};
</#list>
public class ${assertClassName} {
<#if hasNlsAssert>

    /** 本次录制用到的 NLS bundle：bind 一次，避免每个断言重复 bind。 */
    private static final NLSUtils.NlsBundle NLS = ${nlsBindExpr};
</#if>
<#list methods as m>

    @Step
    public void ${m.name}() {
<#list m.bodyLines as l>${l}
</#list>    }
</#list>}
