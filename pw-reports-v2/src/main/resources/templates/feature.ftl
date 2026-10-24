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
    <style>
        /* feature 页头部（KPI 条）——与 overview 的 .kpis 同源，仅列数改为 4 */
        .feature-title { font-size:20px; margin:12px 0 16px; }
        .kpis-4 { grid-template-columns:repeat(4,1fr); }
        @media (max-width:1020px) { .kpis-4 { grid-template-columns:repeat(2,1fr); } }
    </style>
</head>
<body>

<div class="topbar">
  <#-- 三栏与 per-case / overview 完全一致：左=返回、中=报告名、右=空占位（保持标题真正居中）。
       2026-10-22 用户要求：feature 顶部不要 "route_testbed_e2e · 18 scenarios" 与 "reportVersion 1"
       ——概要改由下方 KPI 条承载（与 overview 同款），版本只留页脚。 -->
  <div class="topbar-side"><a href="index.html">← back to overview</a></div>
  <div class="topbar-title">${projectName?html}</div>
  <#-- 2026-10-22 用户要求"三个 report 都要在顶部显示生成/开始/结束时间"：
       左侧保留返回链接，时间信息放右侧（与 index 左右对称、不挤压标题）。 -->
  <div class="topbar-side is-right">
    <div class="topbar-stack">
      <span class="topbar-ver">Generated at ${run.generatedAt?html}</span>
      <span class="topbar-ver">Started ${run.startedAt?html} · Ended ${run.endedAt?html}</span>
      <#-- CI / 构建链接：仅 Jenkins / GitLab CI 运行（存在 BUILD_URL 或 JOB_NAME+BUILD_NUMBER）时渲染。
           企业级状态条风格 pill；有构建 URL 才包 <a>（target=_blank + rel=noopener 防反向标签页劫持）。 -->
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

  <#-- 头部改为 KPI 条（2026-10-22 用户反馈"上方不好看、且与下方统计挤在一起"）：
       原方案是「左标题 + 右一个 24px 大号通过率」，大号数字悬在卡片右侧既空又压不住版面，
       还和下面的 Statistics 卡贴在一起看不出分层。改用与 overview 完全同款的 KPI 磁贴，
       概要一眼可读，且与 Statistics 之间有明确的卡片分隔。 -->
  <div class="card">
    <div class="crumbs"><a href="index.html">Overview</a><span class="sep">›</span><b>${featureName?html}</b></div>
    <h2 class="feature-title">${featureName?html}</h2>
    <div class="kpis kpis-4">
      <div class="kpi" style="--c:var(--primary)">
        <div class="label">Scenarios</div><div class="num">${scenarioCount}</div>
        <div class="pct">in this feature</div>
      </div>
      <div class="kpi" style="--c:var(--success)">
        <div class="label">Passed</div><div class="num" style="color:var(--success)">${passCount}</div>
        <div class="pct">${passPct}% of scenarios</div>
      </div>
      <div class="kpi" style="--c:var(--failure)">
        <div class="label">Issues</div><div class="num" style="color:var(--failure)">${issueCount}</div>
        <div class="pct">${failCount} failed · ${errorCount} error · ${pendingCount} pending</div>
      </div>
      <div class="kpi" style="--c:var(--success)">
        <div class="label">Pass rate</div><div class="num" style="color:var(--success)">${passRate}%</div>
        <div class="pct">${passCount} / ${scenarioCount}</div>
      </div>
    </div>
  </div>

  <!-- 统计信息：构成 / 结果 / 耗时，合并为一个模块 -->
  <div class="card">
    <h3><span class="icon"></span>Statistics<span class="aside">composition · results · execution time</span></h3>
    <div class="stats-grid">
      <div class="stat-block">
        <div class="stat-title">Automated vs Manual</div>
        <#-- 与右侧 Result distribution 用同一套排版（dist-list 行 + stack-bar 进度条），
             两块才能行对齐、都有进度条（2026-10-22 用户要求）。 -->
        <div class="donut-box">
          <@m.donut mixSegments scenarioCount "${autoPct}%" "automated"/>
          <ul class="dist-list">
            <li><span class="dot" style="background:#3b82f6"></span>Automated<span class="n">${autoCount}</span><span class="pct2">${autoPct}%</span></li>
            <li><span class="dot" style="background:#0d9488"></span>Manual<span class="n">${manualCount}</span><span class="pct2">${manualPct}%</span></li>
          </ul>
        </div>
        <div class="stack-bar">
          <#if scenarioCount gt 0>
            <i style="width:${autoPct}%;background:#3b82f6"></i>
            <i style="width:${manualPct}%;background:#0d9488"></i>
          </#if>
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
    <#-- 执行耗时汇总（Total / Clock / Average / Max / Min）：2026-10-22 用户反馈 feature 缺这块 -->
    <@m.timingSummary timings/>
  </div>

  <div class="card">
    <h3><span class="icon"></span>Scenarios (${scenarioCount})<span class="aside">click a row to open its detail page</span></h3>
    <table class="tbl">
      <thead>
        <tr>
          <th style="width:100px">Result</th><th>Scenario</th><th style="width:110px">Type</th>
          <#-- Duration 可排序：点击在升/降序间切换 -->
          <#-- 宽度 80 → 130：表头筛选行在该列放「命中数 + Reset」两个控件（2026-10-22） -->
          <th class="num-cell sortable" id="sort-duration" style="width:130px;cursor:pointer" title="Sort by duration">Duration &#8597;</th>
        </tr>
        <#-- 表头筛选（2026-10-22 用户要求）：Result / Scenario / Type 直接在列头筛选，
             去掉原先与表格脱节、且缺少 Scenario 维度的上方工具栏。
             同日晚些调整：Scenario 与 index 的 Feature 一致改为「列出全部名字、选一个」，
             并在最右列提供 Reset（一键清空所有条件）。 -->
        <tr class="filter-row">
          <th>
            <select id="flt-result" aria-label="Filter by result">
              <option value="all">All results</option>
              <option value="bad">Issues only</option>
              <option value="pass">Passed only</option>
            </select>
          </th>
          <th>
            <select id="flt-scenario" aria-label="Filter by scenario">
              <option value="all">All scenarios</option>
              <#list scenarios as s><option value="${s.name?html}">${s.name?html}</option></#list>
            </select>
          </th>
          <th>
            <select id="flt-type" aria-label="Filter by type">
              <option value="all">All types</option>
              <option value="auto">Automated</option>
              <option value="manual">Manual</option>
            </select>
          </th>
          <th class="num-cell">
            <div class="flt-actions">
              <span class="sub flt-count" id="shown"></span>
              <button type="button" class="flt-reset" id="flt-reset">Reset</button>
            </div>
          </th>
        </tr>
      </thead>
      <tbody id="sc-body">
      <#list scenarios as s>
        <tr class="sc-row clickable" data-result="${s.resultKey}" data-auto="<#if s.auto>1<#else>0</#if>" data-name="${s.name?html}" data-duration="${s.durationMs?c}">
          <td><span class="badge b-${s.resultKey}">${s.resultLabel}</span></td>
          <#-- 用真实锚点承载整行跳转：内联 onclick 会被 Jenkins 的 CSP/sandbox 拦掉，
               点击整行毫无反应（2026-10-23 用户反馈）。锚点不依赖 JS、可键盘聚焦。 -->
          <td class="link-cell"><a class="row-link" href="${s.link}"><span class="link">${s.name?html}</span><span class="link-arrow">→</span><#if s.isOutline> <span class="sub">(${s.exampleCount} examples)</span></#if></a></td>
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
/* 表头筛选：Result（结果）/ Scenario（下拉选具体场景）/ Type（自动化类型）+ Reset。
   与 index.html 的 Feature overview 同一套交互与视觉（列内筛选、末尾 Reset）。 */
(function () {
    var r = document.getElementById('flt-result');
    var q = document.getElementById('flt-scenario');
    var t = document.getElementById('flt-type');
    var resetEl = document.getElementById('flt-reset');
    var rows = [].slice.call(document.querySelectorAll('#sc-body tr.sc-row'));
    var shownEl = document.getElementById('shown');
    var selects = [r, q, t];
    function apply() {
        var rv = r ? r.value : 'all';
        var qv = q ? q.value : 'all';
        var tv = t ? t.value : 'all';
        var shown = 0;
        rows.forEach(function (tr) {
            var res = tr.getAttribute('data-result');
            var auto = tr.getAttribute('data-auto');
            var ok = true;
            if (rv === 'bad' && ['PASS', 'SKIPPED', 'IGNORED'].indexOf(res) >= 0) { ok = false; }
            if (rv === 'pass' && res !== 'PASS') { ok = false; }
            if (tv === 'auto' && auto !== '1') { ok = false; }
            if (tv === 'manual' && auto !== '0') { ok = false; }
            if (qv !== 'all' && tr.getAttribute('data-name') !== qv) { ok = false; }
            tr.style.display = ok ? '' : 'none';
            if (ok) { shown++; }
        });
        if (shownEl) { shownEl.textContent = shown + ' / ' + rows.length; }
        if (resetEl) { resetEl.disabled = rv === 'all' && qv === 'all' && tv === 'all'; }
    }
    selects.forEach(function (el) {
        if (!el) { return; }
        el.addEventListener('change', apply);
    });
    if (resetEl) {
        resetEl.addEventListener('click', function () {
            selects.forEach(function (el) { if (el) { el.value = 'all'; } });
            apply();
        });
    }
    apply();
})();
/* Duration 列排序：升/降序切换、按 data-duration 数值比较。 */
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
