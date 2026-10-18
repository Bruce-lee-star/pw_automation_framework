<#--
  共享宏：环形图（与 docs/template-design/assets/report.js 的 donut 算法一致）。
  服务端直接输出 SVG：r=48 → 周长 C=2πr≈301.5929；每段长度 = value/total*C；
  段间留 3px 间隙（draw=len-3，最小 1），用 stroke-dasharray + 累积 stroke-dashoffset 实现，
  rotate(-90 60 60) 使起点回到 12 点方向。value<=0 的段不绘制（与 JS 版一致）。
-->
<#macro donut segments total centerMain centerSub>
  <#if total gt 0>
    <#local C = 301.5929>
    <#local acc = 0>
    <div class="donut-wrap">
      <svg viewBox="0 0 120 120" class="donut-svg">
        <#list segments as s>
          <#if s.value gt 0>
            <#local len = (s.value / total) * C>
            <#local draw = len - 3>
            <#if draw lt 1><#local draw = 1></#if>
            <circle cx="60" cy="60" r="48" fill="none" stroke="${s.color}" stroke-width="17"
                    stroke-linecap="round"
                    stroke-dasharray="${draw?string["0.##"]} ${(C - draw)?string["0.##"]}"
                    stroke-dashoffset="${(-acc)?string["0.##"]}"
                    transform="rotate(-90 60 60)"></circle>
            <#local acc = acc + len>
          </#if>
        </#list>
      </svg>
      <div class="donut-center"><b>${centerMain}</b><span>${centerSub}</span></div>
    </div>
  </#if>
</#macro>

<#--
  耗时分布直方图（index / feature 共用）。
  buckets: [{label, count, pct}]，pct = 相对“最高桶”的百分比（0–100），用于柱高。
  total:   场景总数（轴脚注用）。

  设计要点（替代旧版 flex 直方图，修掉“一根柱子 + 横贯假底线”的观感）：
    · 绘图区固定高度 + 25/50/75/100% 网格线，读起来像图表；
    · 零值桶渲染为 2px 灰桩 + 灰色 0，不再出现横贯的“假底线”；
    · 非零柱渐变 + 圆角 + hover 提亮；极小占比给 5% 最小可见高度。
-->
<#macro histogram buckets total>
  <div class="hist2">
    <#list buckets as b>
      <#local zero = (b.count == 0)>
      <#local h = b.pct>
      <#if (zero == false) && (h lt 5)><#local h = 5></#if>
      <div class="hist2-col" title="${b.label}: ${b.count} scenario(s)">
        <span class="hist2-count<#if zero> zero</#if>">${b.count}</span>
        <div class="hist2-plot">
          <div class="hist2-bar<#if zero> empty</#if>" style="height:${zero?then('0', h?c)}%"></div>
        </div>
        <span class="hist2-label">${b.label}</span>
      </div>
    </#list>
  </div>
  <div class="hist2-axis"><span>Execution time</span><span>${total} scenario(s)</span></div>
</#macro>
