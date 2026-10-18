<#--
  feature 详情页 —— Overview → Feature → Scenario 两级下钻。
  统计区与总览页一致：构成 / 结果 / 耗时（耗时用本 feature 自己的 durationBuckets）。
-->
<#import "macros.ftl" as m/>
<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8"/>
    <meta name="viewport" content="width=device-width, initial-scale=1"/>
    <#if baseHref?? && baseHref?has_content><base href="${baseHref}"></#if>
    <title>${featureName?html} · Feature</title>
    <link rel="icon" type="image/svg+xml" href="assets/favicon.svg"/>
    <link rel="stylesheet" href="assets/report-v2.css"/>
</head>
<body>

<div class="topbar">
  <h1>Feature <a href="index.html" style="color:#fff;text-decoration:none;font-size:14px;font-weight:400">← back to overview</a></h1>
  <div class="meta">${projectName?html}<br>reportVersion ${reportVersion}</div>
</div>

<div class="wrap">

  <div class="card">
    <div class="crumbs"><a href="index.html">Overview</a><span class="sep">›</span><b>${featureName?html}</b></div>
    <div class="feature-head">
      <div>
        <h2>${featureName?html}</h2>
        <div class="chips">
          <span class="chip tag">${scenarioCount} scenarios</span>
          <span class="chip">${passCount} passed</span>
          <#if issueCount gt 0><span class="chip">${issueCount} issues</span></#if>
        </div>
      </div>
      <div style="text-align:right">
        <div class="sub">Pass rate</div>
        <div style="font-size:24px;font-weight:700;color:var(--success)">${passRate}%</div>
        <div class="sub">${passCount} / ${scenarioCount}</div>
      </div>
    </div>
  </div>

  <!-- 统计信息：构成 / 结果 / 耗时，合并为一个模块 -->
  <div class="card">
    <h3><span class="icon"></span>Statistics<span class="aside">composition · results · execution time</span></h3>
    <div class="stats-grid">
      <div class="stat-block">
        <div class="stat-title">Automated vs Manual</div>
        <div class="donut-box">
          <@m.donut mixSegments scenarioCount "${autoPct}%" "automated"/>
          <ul class="legend">
            <li><span class="dot" style="background:#3b82f6"></span>Automated<span class="v">${autoCount}</span><span class="p">${autoPct}%</span></li>
            <li><span class="dot" style="background:#0d9488"></span>Manual<span class="v">${manualCount}</span><span class="p">${manualPct}%</span></li>
          </ul>
        </div>
      </div>
      <div class="stat-block">
        <div class="stat-title">Result distribution</div>
        <div class="donut-box">
          <@m.donut resultSegments scenarioCount "${passRate}%" "pass rate"/>
          <ul class="dist-list">
            <li><span class="dot" style="background:#16a34a"></span>Passed<span class="n">${passCount}</span><span class="pct2">${passPct}%</span></li>
            <li><span class="dot" style="background:#dc2626"></span>Failed<span class="n">${failCount}</span><span class="pct2">${failPct}%</span></li>
            <li><span class="dot" style="background:#ea580c"></span>Error<span class="n">${errorCount}</span><span class="pct2">${errorPct}%</span></li>
            <li><span class="dot" style="background:#d97706"></span>Pending<span class="n">${pendingCount}</span><span class="pct2">${pendingPct}%</span></li>
          </ul>
        </div>
        <div class="stack-bar">
          <#if scenarioCount gt 0>
            <i style="width:${passPct}%;background:#16a34a"></i>
            <i style="width:${failPct}%;background:#dc2626"></i>
            <i style="width:${errorPct}%;background:#ea580c"></i>
            <i style="width:${pendingPct}%;background:#d97706"></i>
          </#if>
        </div>
      </div>
      <div class="stat-block">
        <div class="stat-title">Duration distribution</div>
        <@m.histogram durationBuckets scenarioCount/>
      </div>
    </div>
  </div>

  <div class="card">
    <h3><span class="icon"></span>Scenarios (${scenarioCount})<span class="aside">click a row to open its detail page</span></h3>
    <div class="toolbar">
      <label class="sub" for="flt-result">Result:</label>
      <select id="flt-result">
        <option value="all">All</option><option value="bad">Issues only</option><option value="pass">Passed only</option>
      </select>
      <label class="sub" for="flt-type" style="margin-left:8px">Type:</label>
      <select id="flt-type">
        <option value="all">All</option><option value="auto">Automated</option><option value="manual">Manual</option>
      </select>
      <span class="sub" id="shown" style="margin-left:10px"></span>
    </div>
    <table class="tbl">
      <thead><tr>
        <th style="width:100px">Result</th><th>Scenario</th><th style="width:110px">Type</th>
        <th class="num-cell" style="width:80px">Duration</th>
      </tr></thead>
      <tbody id="sc-body">
      <#list scenarios as s>
        <tr class="sc-row clickable" data-result="${s.resultKey}" data-auto="<#if s.auto>1<#else>0</#if>" onclick="location.href='${s.link}'">
          <td><span class="badge b-${s.resultKey}">${s.resultLabel}</span></td>
          <td class="link-cell"><span class="link">${s.name?html}</span><span class="link-arrow">→</span><#if s.isOutline> <span class="sub">(${s.exampleCount} examples)</span></#if></td>
          <td><#if s.auto><span class="badge b-auto">automated</span><#else><span class="badge b-manual">manual</span></#if></td>
          <td class="num-cell sub"><#if s.durationSec?has_content>${s.durationSec}s<#else>—</#if></td>
        </tr>
      </#list>
      </tbody>
    </table>
  </div>

  <footer>Feature page · reportVersion ${reportVersion}</footer>
</div>

<script>
(function () {
    var r = document.getElementById('flt-result');
    var t = document.getElementById('flt-type');
    var rows = [].slice.call(document.querySelectorAll('#sc-body tr.sc-row'));
    var shownEl = document.getElementById('shown');
    function apply() {
        var rv = r.value, tv = t.value, shown = 0;
        rows.forEach(function (tr) {
            var res = tr.getAttribute('data-result');
            var auto = tr.getAttribute('data-auto');
            var ok = true;
            if (rv === 'bad' && ['PASS', 'SKIPPED', 'IGNORED'].indexOf(res) >= 0) { ok = false; }
            if (rv === 'pass' && res !== 'PASS') { ok = false; }
            if (tv === 'auto' && auto !== '1') { ok = false; }
            if (tv === 'manual' && auto !== '0') { ok = false; }
            tr.style.display = ok ? '' : 'none';
            if (ok) { shown++; }
        });
        shownEl.textContent = 'Showing ' + shown + ' / ' + rows.length;
    }
    [r, t].forEach(function (el) { el.addEventListener('change', apply); });
    apply();
})();
</script>
</body>
</html>
