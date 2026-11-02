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
  CI / 构建徽标：顶栏构建链接的内部内容（Jenkins / GitLab CI 通用）。
  ci: { ciName, job, number, commitShort, url }；有 url 时外层包 <a>，否则 <span>。
-->
<#macro ciBadge ci>
  <span class="tb-ci">${ci.ciName?html}</span>
  <#if ci.job??><span class="tb-job">${ci.job?html}<#if ci.number??> #${ci.number?html}</#if></span></#if>
  <#if ci.commitShort??><span class="tb-commit">${ci.commitShort?html}</span></#if>
  <#if ci.url??><span class="tb-ext" aria-hidden="true">↗</span></#if>
</#macro>

<#--
  耗时分布直方图（index / feature 共用，2026-10-22 重绘：用户反馈“柱状图还是不好看”）。
  buckets: [{label, count, pct}]，total: 场景总数（轴脚注用）。

  重绘要点：
    · 柱高按「桶计数 / 最大桶计数」缩放（最高柱=100%），不再用占总场景比例导致次柱矮到看不见；
    · 左侧 y 轴刻度（最大值 / 中间值 / 0），与绘图区网格线对齐；
    · 数值标签贴在【柱顶上方】，不再固定在列顶——旧版次柱只有 5% 高时数字悬在柱顶上方一大截；
    · 零值桶只留 2px 灰桩、不显示数字；柱宽 46%（上限 40px）+ 圆角，桶间有呼吸感。
-->
<#macro histogram buckets total>
  <#assign maxCount = 0>
  <#list buckets as b><#if (b.count > maxCount)><#assign maxCount = b.count></#if></#list>
  <div class="hist2">
    <div class="hist2-scale">
      <#if maxCount == 0>
        <span>0</span>
      <#else>
        <span>${maxCount}</span>
        <span>${(maxCount / 2)?round}</span>
        <span>0</span>
      </#if>
    </div>
    <div class="hist2-body">
      <div class="hist2-tracks">
        <#list buckets as b>
          <#local zero = (b.count == 0)>
          <#local h = (maxCount == 0)?then(0, (b.count * 100 / maxCount))>
          <#if (zero == false) && (h lt 6)><#local h = 6></#if>
          <div class="hist2-col" title="${b.label}: ${b.count} scenario(s)">
            <div class="hist2-bar<#if zero> empty</#if>" style="height:${zero?then('2px', h?c + '%')}">
              <#if zero == false><span class="hist2-val">${b.count}</span></#if>
            </div>
          </div>
        </#list>
      </div>
      <div class="hist2-labels">
        <#list buckets as b><span>${b.label}</span></#list>
      </div>
    </div>
  </div>
  <div class="hist2-axis"><span>Execution time</span><span>${total} scenario(s)</span></div>
</#macro>

<#--
  执行耗时汇总行（Total / Clock / Average / Max / Min），index / feature 共用。
  timings 由 Java 侧 SummaryHtmlRenderer.timings(...) 计算并格式化，模板只展示。
-->
<#macro timingSummary timings>
  <div class="timing-row">
    <div class="timing-cell"><span class="k">Total</span><span class="v">${timings.total?html}</span></div>
    <div class="timing-cell"><span class="k">Clock</span><span class="v">${timings.clock?html}</span></div>
    <div class="timing-cell"><span class="k">Average</span><span class="v">${timings.avg?html}</span></div>
    <div class="timing-cell"><span class="k">Max</span><span class="v">${timings.max?html}</span></div>
    <div class="timing-cell"><span class="k">Min</span><span class="v">${timings.min?html}</span></div>
  </div>
</#macro>
