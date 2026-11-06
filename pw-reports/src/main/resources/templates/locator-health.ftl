<#--
  locator-health.html —— 定位健康度独立报告（2026-11-06 用户要求：
  <b>不并入 index / email-report</b>，主报告保持干净，本页单独产出）。

  数据来源：pw-core 的 LocatorHealthReportSink SPI（pw-web-ui 采集）。
  Java 侧无采集数据时不调用本模板（对齐 monitor-failures：文件存在即有内容）。

  模型（见 LocatorHealthHtmlRenderer）：
    projectName、run（generatedAt/startedAt/endedAt）、ci、baseHref、
    health（selectorsTotal/violationsTotal/truncated/selectors/violations）、
    topUsage（按用量降序的前 10 条，Java 侧已排序）。
-->
<#import "macros.ftl" as m/>
<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8"/>
    <meta name="viewport" content="width=device-width, initial-scale=1"/>
    <#if baseHref?? && baseHref?has_content><base href="${baseHref}"></#if>
    <title>Locator health · ${projectName?html}</title>
    <link rel="icon" type="image/svg+xml" href="assets/favicon.svg"/>
    <link rel="stylesheet" href="assets/report-v2.css"/>
</head>
<body>

<div class="topbar">
  <#-- 与 overview / feature / per-case 同款三栏：左=返回、中=报告名、右=生成时间（+CI 徽标） -->
  <div class="topbar-side"><a href="index.html">← back to overview</a></div>
  <div class="topbar-title">${projectName?html}</div>
  <div class="topbar-side is-right">
    <div class="topbar-stack">
      <span class="topbar-ver">Generated at ${run.generatedAt?html}</span>
      <#if ci?? && ci.present>
        <#if ci.url??>
          <a class="topbar-build" href="${ci.url?html}" target="_blank" rel="noopener noreferrer"
             title="Open build in ${ci.ciName?html}"><@m.ciBadge ci/></a>
        <#else>
          <span class="topbar-build"><@m.ciBadge ci/></span>
        </#if>
      </#if>
    </div>
  </div>
</div>

<div class="wrap">

  <div class="card">
    <h3><span class="icon"></span>Locator health<span class="aside">strict-mode evidence · this run</span></h3>
    <p class="hint">
      Selectors sampled <b>${health.selectorsTotal}</b> ·
      strict violations <b>${health.violationsTotal}</b><#if health.truncated>
        · <span class="lh-warn">sampling truncated</span></#if>
    </p>
    <p class="hint">
      Playwright's strict mode already refuses to act on a locator that resolves to more than one element;
      this page records those events, so unstable selectors become visible instead of appearing as one-off
      red cases. Selectors that are intentionally multi-match should use <code>.nth(i)</code> or a list
      element (<code>PageElementList</code>).
    </p>
  </div>

  <div class="card">
    <h3><span class="icon"></span>Strict violations<span class="aside">one event = one locator resolved to many</span></h3>
    <#if (health.violationsTotal gt 0)>
      <table class="tbl">
        <thead>
          <tr>
            <th>Scenario</th><th>Selector</th><th>Kind</th>
            <th class="num-cell">Matches</th><th>Operation</th><th>Thread</th><th>At</th>
          </tr>
        </thead>
        <tbody>
          <#list health.violations as v>
          <tr>
            <td>${v.scenario?html}</td>
            <td class="lh-code"><code>${v.selector?html}</code></td>
            <td>${v.kind?html}</td>
            <td class="num-cell">${v.matchCount}</td>
            <td>${v.operation?html}</td>
            <td>${v.thread?html}</td>
            <td>${v.at?html}</td>
          </tr>
          </#list>
        </tbody>
      </table>
    <#else>
      <p class="hint">No strict-mode violations in this run — every selector resolved to a single element.</p>
    </#if>
  </div>

  <div class="card">
    <h3><span class="icon"></span>Most used selectors<span class="aside">top 10 by usage</span></h3>
    <#if topUsage?has_content>
      <table class="tbl">
        <thead>
          <tr>
            <th>Selector</th><th>Kind</th>
            <th class="num-cell">Uses</th><th class="num-cell">Violations</th>
            <th class="num-cell">Max matches</th>
          </tr>
        </thead>
        <tbody>
          <#list topUsage as e>
          <tr>
            <td class="lh-code"><code>${e.selector?html}</code></td>
            <td>${e.kind?html}</td>
            <td class="num-cell">${e.uses}</td>
            <td class="num-cell"><#if (e.violations gt 0)>${e.violations}<#else>—</#if></td>
            <td class="num-cell"><#if (e.maxMatchCount gt 0)>${e.maxMatchCount}<#else>—</#if></td>
          </tr>
          </#list>
        </tbody>
      </table>
    <#else>
      <p class="hint">No selector samples were collected in this run.</p>
    </#if>
  </div>

  <footer>Locator health · generated from runtime selector evidence</footer>
</div>

</body>
</html>
