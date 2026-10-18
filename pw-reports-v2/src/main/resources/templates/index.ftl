<#--
  index / dashboard —— 对齐 docs/template-design/index.html 的新设计（英文文案）。
  服务端渲染：所有行、环形图、进度条均直接输出最终 HTML（离线 file:// 打开也有完整内容），
  筛选器只做行的显隐，不重新渲染。

  信息架构（本次调整）：
    · 顶部 KPI：一眼结论；
    · Statistics 单个模块：自动化构成 / 结果分布 / 耗时分布（三图并列，不再分散成多张卡）；
    · Environment：运行环境；
    · Feature overview：Feature → Scenario 下钻；
    · All scenarios：可筛选/排序的明细表。

  数据模型（见 AggregateBuilder.indexModel）：
    count/passCount/failCount/errorCount/pendingCount/skippedCount、
    autoCount/manualCount/autoPct/manualPct/passPct/failPct/errorPct/pendingPct/skippedPct/issuePct、
    mixSegments/resultSegments（环形图分段：key/color/value）、
    durationBuckets（耗时直方图：label/count/pct）、
    env（运行环境行 k/v，空值渲染为 —）、browsers（可为空）、
    groups（feature 分组，含 scenarioCount/autoCount/manualCount/passCount/
            failCount/errorCount/pendingCount/issueCount/passRate/link 与 scenarios）、
    totalDurationText、tags、projectName、reportVersion。
-->
<#import "macros.ftl" as m/>
<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8"/>
    <meta name="viewport" content="width=device-width, initial-scale=1"/>
    <#if baseHref?? && baseHref?has_content><base href="${baseHref}"></#if>
    <title>${projectName?html} · Test Report</title>
    <link rel="icon" type="image/svg+xml" href="assets/favicon.svg"/>
    <link rel="stylesheet" href="assets/report-v2.css"/>
    <style>
        th.sortable { user-select: none; }
        th.sortable:hover { color: var(--primary); }
    </style>
</head>
<body>

<div class="topbar">
  <h1>Test Report <span style="font-weight:400;font-size:13px;opacity:.85">· ${projectName?html}</span></h1>
  <div class="meta">${groups?size} features · ${count} scenarios<br>reportVersion ${reportVersion} · ${totalDurationText}</div>
</div>

<div class="wrap">

  <!-- KPI -->
  <div class="kpis">
    <div class="kpi" style="--c:var(--primary)">
      <div class="label">Scenarios</div><div class="num">${count}</div>
      <div class="pct">across ${groups?size} features</div>
    </div>
    <div class="kpi" style="--c:var(--primary)">
      <div class="label">Automated</div><div class="num">${autoCount}</div>
      <div class="pct">${autoPct}%</div>
    </div>
    <div class="kpi" style="--c:var(--manual)">
      <div class="label">Manual</div><div class="num" style="color:var(--manual)">${manualCount}</div>
      <div class="pct">${manualPct}%</div>
    </div>
    <div class="kpi" style="--c:var(--success)">
      <div class="label">Pass rate</div><div class="num" style="color:var(--success)">${passPct}%</div>
      <div class="pct">${passCount} / ${count}</div>
    </div>
    <div class="kpi" style="--c:var(--failure)">
      <div class="label">Failed / Error</div><div class="num" style="color:var(--failure)">${failCount + errorCount}</div>
      <div class="pct">${failCount} failed · ${errorCount} error</div>
    </div>
  </div>

  <!-- 统计信息：构成 / 结果 / 耗时，合并为一个模块 -->
  <div class="card">
    <h3><span class="icon"></span>Statistics<span class="aside">composition · results · execution time</span></h3>
    <div class="stats-grid">
      <div class="stat-block">
        <div class="stat-title">Automated vs Manual</div>
        <div class="donut-box">
          <@m.donut mixSegments count "${autoPct}%" "automated"/>
          <ul class="legend">
            <li><span class="dot" style="background:#3b82f6"></span>Automated<span class="v">${autoCount}</span><span class="p">${autoPct}%</span></li>
            <li><span class="dot" style="background:#0d9488"></span>Manual<span class="v">${manualCount}</span><span class="p">${manualPct}%</span></li>
          </ul>
        </div>
      </div>
      <div class="stat-block">
        <div class="stat-title">Result distribution</div>
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
      <div class="stat-block">
        <div class="stat-title">Duration distribution</div>
        <@m.histogram durationBuckets count/>
      </div>
    </div>
  </div>

  <!-- 运行环境 -->
  <div class="card">
    <h3><span class="icon"></span>Environment</h3>
    <div class="env-grid">
      <#list env as e>
        <div class="env-item"><span class="k">${e.k?html}</span><span class="v"><#if e.v??>${e.v?html}<#else>—</#if></span></div>
      </#list>
    </div>
    <#if browsers?has_content>
      <div class="sub" style="margin-top:12px;grid-column:1/-1">Browsers</div>
      <div class="browser-bar" style="grid-column:1/-1">
        <#list browsers as b><i style="width:${100 / browsers?size}%;background:#3b82f6"></i></#list>
      </div>
      <div class="browser-legend" style="grid-column:1/-1">
        <#list browsers as b><span>${b.name?html} <#if b.version?has_content><b>${b.version?html}</b></#if></span></#list>
      </div>
    </#if>
  </div>

  <!-- Feature 总览 -->
  <div class="card">
    <h3><span class="icon"></span>Feature overview<span class="aside">Feature → Scenario · click to open</span></h3>
    <table class="tbl">
      <thead><tr>
        <th>Feature</th><th class="num-cell">Scenarios</th><th>Automated / Manual</th>
        <th class="num-cell">Passed</th><th class="num-cell">Issues</th><th>Pass rate</th><th style="width:90px"></th>
      </tr></thead>
      <tbody>
      <#list groups as g>
        <tr class="clickable" onclick="location.href='${g.link}'">
          <td class="link-cell"><span class="link">${g.feature?html}</span><span class="link-arrow">→</span></td>
          <td class="num-cell">${g.scenarioCount}</td>
          <td><span class="badge b-auto">${g.autoCount} automated</span> <span class="badge b-manual">${g.manualCount} manual</span></td>
          <td class="num-cell">${g.passCount}</td>
          <td class="num-cell">
            <#if g.issueCount gt 0>
              <#if g.failCount gt 0><span style="color:var(--failure)">${g.failCount} failed</span>
              <#elseif g.errorCount gt 0><span style="color:var(--error)">${g.errorCount} error</span>
              <#else><span style="color:var(--pending)">${g.pendingCount} pending</span></#if>
            <#else>0</#if>
          </td>
          <td>
            <div class="bar-mini">
              <i style="width:${g.passRate}%;background:var(--success)"></i><i style="width:${100 - g.passRate}%;background:var(--failure)"></i>
            </div>${g.passRate}%
          </td>
          <td><a class="btn sm" href="${g.link}">Open →</a></td>
        </tr>
      </#list>
      </tbody>
    </table>
  </div>

  <!-- 全部 Scenario -->
  <div class="card">
    <h3><span class="icon"></span>All scenarios (${count})<span class="aside">click a row to open its detail page</span></h3>

    <div class="toolbar">
      <label class="sub" for="flt-result">Result:</label>
      <select id="flt-result">
        <option value="all">All</option><option value="bad">Issues only</option><option value="pass">Passed only</option>
      </select>
      <label class="sub" for="flt-type" style="margin-left:8px">Type:</label>
      <select id="flt-type">
        <option value="all">All</option><option value="auto">Automated</option><option value="manual">Manual</option>
      </select>
      <label class="sub" for="flt-feat" style="margin-left:8px">Feature:</label>
      <select id="flt-feat">
        <option value="all">All features</option>
        <#list groups as g><option value="${g.feature?html}">${g.feature?html}</option></#list>
      </select>
      <span class="sub" id="shown" style="margin-left:10px"></span>
    </div>

    <table class="tbl">
      <thead><tr>
        <th style="width:100px">Result</th><th>Scenario</th><th style="width:110px">Type</th>
        <th style="width:150px">Feature</th><th class="num-cell sortable" id="sort-duration" style="width:90px;cursor:pointer" title="Sort by duration">Duration &#8597;</th>
      </tr></thead>
      <tbody id="sc-body">
      <#list groups as g>
        <#list g.scenarios as s>
        <tr class="sc-row clickable" data-result="${s.resultKey}" data-auto="<#if s.auto>1<#else>0</#if>" data-feature="${g.feature?html}" data-duration="${s.durationMs?c}" onclick="location.href='${s.link}'">
          <td><span class="badge b-${s.resultKey}">${s.resultLabel}</span></td>
          <td class="link-cell"><span class="link">${s.name?html}</span><span class="link-arrow">→</span><#if s.isOutline> <span class="sub">(${s.exampleCount} examples)</span></#if></td>
          <td><#if s.auto><span class="badge b-auto">automated</span><#else><span class="badge b-manual">manual</span></#if></td>
          <td class="sub">${g.feature?html}</td>
          <td class="num-cell sub"><#if s.durationSec?has_content>${s.durationSec}s<#else>—</#if></td>
        </tr>
        </#list>
      </#list>
      </tbody>
    </table>
  </div>

  <footer>Test report · reportVersion ${reportVersion} · all scenarios visible · offline capable (no server required)</footer>
</div>

<script>
(function () {
    var r = document.getElementById('flt-result');
    var t = document.getElementById('flt-type');
    var f = document.getElementById('flt-feat');
    var rows = [].slice.call(document.querySelectorAll('#sc-body tr.sc-row'));
    var shownEl = document.getElementById('shown');
    function apply() {
        var rv = r.value, tv = t.value, fv = f.value, shown = 0;
        rows.forEach(function (tr) {
            var res = tr.getAttribute('data-result');
            var auto = tr.getAttribute('data-auto');
            var ok = true;
            if (rv === 'bad' && ['PASS', 'SKIPPED', 'IGNORED'].indexOf(res) >= 0) { ok = false; }
            if (rv === 'pass' && res !== 'PASS') { ok = false; }
            if (tv === 'auto' && auto !== '1') { ok = false; }
            if (tv === 'manual' && auto !== '0') { ok = false; }
            if (fv !== 'all' && tr.getAttribute('data-feature') !== fv) { ok = false; }
            tr.style.display = ok ? '' : 'none';
            if (ok) { shown++; }
        });
        shownEl.textContent = 'Showing ' + shown + ' / ' + rows.length;
    }
    [r, t, f].forEach(function (el) { el.addEventListener('change', apply); });
    apply();
})();
(function () {
    var th = document.getElementById('sort-duration');
    var body = document.getElementById('sc-body');
    if (!th || !body) { return; }
    var asc = null;
    th.addEventListener('click', function () {
        asc = (asc === null) ? true : !asc;
        var rows = [].slice.call(body.querySelectorAll('tr.sc-row'));
        rows.sort(function (a, b) {
            var x = Number(a.getAttribute('data-duration') || 0);
            var y = Number(b.getAttribute('data-duration') || 0);
            return asc ? x - y : y - x;
        });
        rows.forEach(function (row) { body.appendChild(row); });
        th.innerHTML = 'Duration ' + (asc ? '&#8593;' : '&#8595;');
    });
})();
</script>
</body>
</html>
