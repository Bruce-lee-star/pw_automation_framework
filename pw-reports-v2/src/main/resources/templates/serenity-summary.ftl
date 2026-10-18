<#import "macros.ftl" as m/>
<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<#if baseHref?? && baseHref?has_content><base href="${baseHref}"></#if>
<title>Test Summary Report</title>
<link rel="icon" type="image/svg+xml" href="assets/favicon.svg"/>
<link rel="stylesheet" href="assets/report-v2.css"/>
</head>
<body>

<div class="topbar">
  <h1>Test Summary <a href="index.html" style="color:#fff;text-decoration:none;font-size:14px;font-weight:400">← back to overview</a></h1>
  <div class="meta">${count} scenarios · ${featureCount} features<br>${totalDurationText}</div>
</div>

<div class="wrap">

  <div class="kpis">
    <div class="kpi" style="--c:var(--primary)">
      <div class="label">Scenarios</div><div class="num">${count}</div>
      <div class="pct">across ${featureCount} features</div>
    </div>
    <div class="kpi" style="--c:var(--success)">
      <div class="label">Passed</div><div class="num" style="color:var(--success)">${passCount}</div>
      <div class="pct">${passPct}%</div>
    </div>
    <div class="kpi" style="--c:var(--failure)">
      <div class="label">Failed</div><div class="num" style="color:var(--failure)">${failCount}</div>
      <div class="pct">${failPct}%</div>
    </div>
    <div class="kpi" style="--c:var(--error)">
      <div class="label">Error</div><div class="num" style="color:var(--error)">${errorCount}</div>
      <div class="pct">${errorPct}%</div>
    </div>
    <div class="kpi" style="--c:var(--pending)">
      <div class="label">Pending / Skipped</div><div class="num" style="color:var(--pending)">${pendingCount + skippedCount}</div>
      <div class="pct">${pendingCount} pending · ${skippedCount} skipped</div>
    </div>
  </div>

  <div class="grid-2">
    <div class="card">
      <h3><span class="icon"></span>Result distribution</h3>
      <div class="donut-box">
        <@m.donut resultSegments count "${passPct}%" "pass rate"/>
        <ul class="dist-list">
          <li><span class="dot" style="background:#16a34a"></span>Passed<span class="n">${passCount}</span><span class="pct2">${passPct}%</span></li>
          <li><span class="dot" style="background:#dc2626"></span>Failed<span class="n">${failCount}</span><span class="pct2">${failPct}%</span></li>
          <li><span class="dot" style="background:#ea580c"></span>Error<span class="n">${errorCount}</span><span class="pct2">${errorPct}%</span></li>
          <li><span class="dot" style="background:#d97706"></span>Pending<span class="n">${pendingCount}</span><span class="pct2">${pendingPct}%</span></li>
          <li><span class="dot" style="background:#94a3b8"></span>Skipped<span class="n">${skippedCount}</span><span class="pct2">${skippedPct}%</span></li>
        </ul>
      </div>
      <div class="stack-bar">
        <#if count gt 0>
          <i style="width:${passPct}%;background:#16a34a"></i>
          <i style="width:${failPct}%;background:#dc2626"></i>
          <i style="width:${errorPct}%;background:#ea580c"></i>
          <i style="width:${pendingPct}%;background:#d97706"></i>
          <i style="width:${skippedPct}%;background:#94a3b8"></i>
        </#if>
      </div>
    </div>

    <div class="card">
      <h3><span class="icon"></span>Automated vs Manual</h3>
      <div class="donut-box">
        <@m.donut mixSegments count "${autoPct}%" "automated"/>
        <ul class="legend">
          <li><span class="dot" style="background:#3b82f6"></span>Automated<span class="v">${autoCount}</span><span class="p">${autoPct}%</span></li>
          <li><span class="dot" style="background:#0d9488"></span>Manual<span class="v">${manualCount}</span><span class="p">${manualPct}%</span></li>
        </ul>
      </div>
    </div>
  </div>

  <div class="card">
    <h3><span class="icon"></span>Features</h3>
    <table class="tbl">
      <thead><tr><th>Feature</th><th class="num-cell">Scenarios</th><th class="num-cell">Issues</th></tr></thead>
      <tbody>
      <#list groups as g>
        <tr class="clickable" onclick="location.href='index.html'">
          <td class="link-cell"><span class="link">${g.feature?html}</span><span class="link-arrow">→</span></td>
          <td class="num-cell">${g.scenarios?size}</td>
          <td class="num-cell">${g.scenarios?filter(s -> s.isFailure)?size}</td>
        </tr>
      </#list>
      </tbody>
    </table>
  </div>

  <div class="card">
    <h3><span class="icon"></span>Scenarios (${count})<span class="aside">click a scenario to open its detail page</span></h3>
    <table class="tbl">
      <thead><tr><th style="width:100px">Result</th><th>Scenario</th><th style="width:150px">Feature</th><th class="num-cell" style="width:80px">Duration</th></tr></thead>
      <tbody>
      <#list groups as g>
        <#list g.scenarios as s>
        <tr class="clickable" onclick="location.href='${s.link}'">
          <td><span class="badge b-${s.resultKey}">${s.resultLabel}</span></td>
          <td><a href="${s.link}">${s.name?html}</a><#if s.isOutline> <span class="sub">(${s.exampleCount} examples)</span></#if></td>
          <td class="sub">${g.feature?html}</td>
          <td class="num-cell sub"><#if s.durationSec?has_content>${s.durationSec}s<#else>—</#if></td>
        </tr>
        </#list>
      </#list>
      </tbody>
    </table>
  </div>

  <#if failureList?has_content>
  <div class="card">
    <h3><span class="icon"></span>Failures (${failureList?size})<span class="aside">first line of each failure message</span></h3>
    <table class="tbl">
      <thead><tr><th style="width:100px">Result</th><th>Scenario</th><th style="width:180px">Feature</th><th>Message</th></tr></thead>
      <tbody>
      <#list failureList as f>
        <tr>
          <td><span class="badge b-${f.result}"><#if f.result == "SUCCESS">Passed<#elseif f.result == "FAILURE">Failed<#elseif f.result == "ERROR">Error<#elseif f.result == "PENDING">Pending<#else>${f.result}</#if></span></td>
          <td><a href="${f.link}">${f.name?html}</a></td>
          <td class="sub">${f.feature?html}</td>
          <td class="sub">${f.message?html}</td>
        </tr>
      </#list>
      </tbody>
    </table>
  </div>
  </#if>

  <#if tags?has_content>
  <div class="card">
    <h3><span class="icon"></span>Tags</h3>
    <div class="chips">
      <#list tags as t><span class="chip tag">${t.tag?html} · ${t.count}</span></#list>
    </div>
  </div>
  </#if>

  <footer>Test summary · ${count} scenarios · ${totalDurationText}</footer>
</div>
</body>
</html>
