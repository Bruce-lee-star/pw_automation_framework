<#--
  自绘 SVG 图标宏——不引入（也不拷贝）任何第三方字体/图标库，离线 file:// 下同样可用。
  颜色一律 currentColor：徽标底色有色、文字为白，图标自然跟随。

  用法（扩展名与被消费模板同为 .ftl，保证 output format 一致）：
    <#import "icons.ftl" as ico/>
    <@ico.badge s.result/>    结果徽标（图标 + 文字）
    <@ico.result s.result/>   仅结果图标
    <@ico.chevron/>           折叠箭头（父级加 .is-collapsed 时旋转）
    <@ico.camera/>            该步骤含截图
    <@ico.clip/>              附件 / 补传截图
    <@ico.play/>              外部播放（Playwright Trace）
-->
<#macro result r="UNKNOWN">
<#switch (r!"UNKNOWN")?upper_case>
  <#case "SUCCESS">
<svg class="ico" viewBox="0 0 16 16" aria-hidden="true" focusable="false"><path d="M4.6 8.4l2.2 2.2 4.6-4.8" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"/></svg>
    <#break>
  <#case "FAILURE">
<svg class="ico" viewBox="0 0 16 16" aria-hidden="true" focusable="false"><path d="M5.4 5.4l5.2 5.2M10.6 5.4l-5.2 5.2" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round"/></svg>
    <#break>
  <#case "ERROR">
<svg class="ico" viewBox="0 0 16 16" aria-hidden="true" focusable="false"><path d="M8 4.2v4.6" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round"/><circle cx="8" cy="11.6" r="1.1" fill="currentColor"/></svg>
    <#break>
  <#case "PENDING">
<svg class="ico" viewBox="0 0 16 16" aria-hidden="true" focusable="false"><circle cx="8" cy="8" r="5.6" fill="none" stroke="currentColor" stroke-width="1.6"/><path d="M8 4.4V8l2.6 1.5" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round"/></svg>
    <#break>
  <#case "SKIPPED"><#case "IGNORED"><#case "UNDEFINED">
<svg class="ico" viewBox="0 0 16 16" aria-hidden="true" focusable="false"><path d="M4.9 8h6.2" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round"/></svg>
    <#break>
  <#default>
<svg class="ico" viewBox="0 0 16 16" aria-hidden="true" focusable="false"><path d="M8 4.2v4.6" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round"/><circle cx="8" cy="11.6" r="1.1" fill="currentColor"/></svg>
</#switch>
</#macro>
<#macro badge r="UNKNOWN" label="">
<span class="badge result-${(r!"UNKNOWN")?lower_case}"><@result r/><span class="badge-text">${(label!(r!"UNKNOWN"))?html}</span></span>
</#macro>
<#macro chevron>
<svg class="ico chevron" viewBox="0 0 16 16" aria-hidden="true" focusable="false"><path d="M6 4l4 4-4 4" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"/></svg>
</#macro>
<#macro camera>
<svg class="ico" viewBox="0 0 16 16" aria-hidden="true" focusable="false"><rect x="2" y="4.6" width="12" height="8.4" rx="1.6" fill="none" stroke="currentColor" stroke-width="1.4"/><circle cx="8" cy="8.8" r="2.2" fill="none" stroke="currentColor" stroke-width="1.4"/><path d="M6 4.6l1-1.6h2l1 1.6" fill="none" stroke="currentColor" stroke-width="1.4" stroke-linejoin="round"/></svg>
</#macro>
<#macro clip>
<svg class="ico" viewBox="0 0 16 16" aria-hidden="true" focusable="false"><path d="M11.4 6.6 7 11a2.2 2.2 0 0 1-3.1-3.1l5-5a1.5 1.5 0 0 1 2.1 2.1l-5 5" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"/></svg>
</#macro>
<#macro play>
<svg class="ico" viewBox="0 0 16 16" aria-hidden="true" focusable="false"><path d="M5.5 3.6v8.8l7-4.4-7-4.4Z" fill="currentColor"/></svg>
</#macro>
