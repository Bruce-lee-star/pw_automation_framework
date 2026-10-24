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
</head>
<body>

<div class="topbar">
  <#-- 三栏与 per-case / feature 完全一致：左=概要、中=报告名（project.report.name）、右=版本
       （2026-10-22 用户要求：overview/feature 的 report name 与 per case html 对齐）。 -->
  <#-- 2026-10-22 用户要求：时间信息（生成/开始/结束）放顶栏【最右边】；
       左侧留空占位以维持三栏 flex、保证报告名真正居中。 -->
  <div class="topbar-side"></div>
  <div class="topbar-title">${projectName?html}</div>
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

  <!-- KPI -->
  <div class="kpis">
    <#-- Features 卡（2026-10-22 用户要求"feature 的统计增加到顶部卡片"）：
         feature 级统计（Features 数）并入 KPI 行，Feature overview 下不再重复放统计卡。 -->
    <div class="kpi" style="--c:var(--primary)">
      <div class="label">Features</div><div class="num">${groups?size}</div>
      <div class="pct">executed in this run</div>
    </div>
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
        <#-- 与右侧 Result distribution 用同一套排版（dist-list 行 + stack-bar 进度条），
             两块才能行对齐、都有进度条（2026-10-22 用户要求）。 -->
        <div class="donut-box">
          <@m.donut mixSegments count "${autoPct}%" "automated"/>
          <ul class="dist-list">
            <li><span class="dot" style="background:#3b82f6"></span>Automated<span class="n">${autoCount}</span><span class="pct2">${autoPct}%</span></li>
            <li><span class="dot" style="background:#0d9488"></span>Manual<span class="n">${manualCount}</span><span class="pct2">${manualPct}%</span></li>
          </ul>
        </div>
        <div class="stack-bar">
          <#if count gt 0>
            <i style="width:${autoPct}%;background:#3b82f6"></i>
            <i style="width:${manualPct}%;background:#0d9488"></i>
          </#if>
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
    <#-- Environment 全宽横带（2026-10-22 二次调整 + 三次：Browsers 并入同行）。
         每项 label 上 / value 下（与 timing-row 同风格），项间细分割线；
         宽度 1280 → 1440（同轮用户要求“页面宽度再增大一些”），feature 名很长时更从容。 -->
    <div class="env-band">
      <div class="env-items">
        <#list env as e>
          <div class="env-item">
            <span class="k">${e.k?html}</span>
            <span class="v"><#if e.v??>${e.v?html}<#else>—</#if></span>
          </div>
        </#list>
        <#-- Browsers 与环境项同行（2026-10-22 用户要求“browser 和 OS 同行”）：
             单浏览器时色条无信息量，只展示名称/版本；多浏览器才显示按占比的细分段条。 -->
        <#if browsers?has_content>
          <div class="env-item env-item-browsers">
            <span class="k">Browsers</span>
            <span class="v">
              <#list browsers as b>${b.name?html}<#if b.version?has_content> ${b.version?html}</#if><#if b?has_next> · </#if></#list>
            </span>
            <#if (browsers?size > 1)>
              <div class="browser-bar">
                <#list browsers as b><i style="width:${100 / browsers?size}%;background:#3b82f6"></i></#list>
              </div>
            </#if>
          </div>
        </#if>
      </div>
    </div>
    <#-- 执行耗时汇总（Total / Clock / Average / Max / Min）：2026-10-22 用户反馈 index 缺这块 -->
    <@m.timingSummary timings/>
  </div>

  <!-- Feature 总览 -->
  <div class="card">
    <#-- 2026-10-22 用户要求：移除信息密度低的「All scenarios」明细表（case 多时非常难看），
         Feature overview 成为唯一入口，因此在这里补齐筛选。
         同日再调整：Feature 不再用输入框（用户要「列出所有 feature 名字，选一个看」），
         且筛选全部下沉到表头列内（与 per-feature 页同一套视觉），末尾提供 Reset。 -->
    <h3><span class="icon"></span>Feature overview<span class="aside">Feature → Scenario · click to open</span></h3>

    <table class="tbl">
      <thead>
        <tr>
          <th>Feature</th><th class="num-cell">Scenarios</th><th>Automated / Manual</th>
          <th class="num-cell">Passed</th><th class="num-cell">Issues</th><th>Pass rate</th>
          <th class="num-cell" style="width:130px"></th>
        </tr>
        <tr class="filter-row">
          <th>
            <select id="fo-name" aria-label="Filter by feature">
              <option value="all">All features</option>
              <#list groups as g><option value="${g.feature?html}">${g.feature?html}</option></#list>
            </select>
          </th>
          <th></th>
          <th>
            <select id="fo-type" aria-label="Filter by type">
              <option value="all">All types</option>
              <option value="manual">Has manual</option>
              <option value="auto">Fully automated</option>
            </select>
          </th>
          <th></th>
          <th>
            <select id="fo-issues" aria-label="Filter by issues">
              <option value="all">Any issues</option>
              <option value="has">With issues</option>
              <option value="none">All passed</option>
            </select>
          </th>
          <th>
            <select id="fo-rate" aria-label="Filter by pass rate">
              <option value="all">Any rate</option>
              <option value="lt100">&lt; 100%</option>
              <option value="lt50">&lt; 50%</option>
              <option value="full">100%</option>
            </select>
          </th>
          <th>
            <div class="flt-actions">
              <span class="sub flt-count" id="fo-shown"></span>
              <button type="button" class="flt-reset" id="fo-reset">Reset</button>
            </div>
          </th>
        </tr>
      </thead>
      <tbody id="fo-body">
      <#list groups as g>
        <tr class="fo-row clickable" data-name="${g.feature?html}" data-issues="${g.issueCount}"
            data-manual="${g.manualCount}" data-auto="${g.autoCount}" data-rate="${g.passRate}">
          <#-- 用真实锚点承载整行跳转：Jenkins 对归档 HTML 的 CSP/sandbox 会拦掉内联 onclick，
               点击整行会毫无反应（2026-10-23 用户反馈）。锚点不依赖 JS、可键盘聚焦。 -->
          <td class="link-cell"><a class="row-link" href="${g.link}"><span class="link">${g.feature?html}</span><span class="link-arrow">→</span></a></td>
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

  <footer>Test report · reportVersion ${reportVersion} · offline capable (no server required)</footer>
</div>

<script>
/* Feature overview 表头筛选（2026-10-22）：Feature（下拉选具体 feature）/ 类型 / 问题 / 通过率 + Reset。
   移除 All scenarios 明细表后，这里是定位 feature 的唯一入口；纯前端过滤、无依赖。 */
(function () {
    var body = document.getElementById('fo-body');
    if (!body) { return; }
    var nameEl = document.getElementById('fo-name');
    var typeEl = document.getElementById('fo-type');
    var issuesEl = document.getElementById('fo-issues');
    var rateEl = document.getElementById('fo-rate');
    var resetEl = document.getElementById('fo-reset');
    var shownEl = document.getElementById('fo-shown');
    var rows = [].slice.call(body.querySelectorAll('tr.fo-row'));
    var selects = [nameEl, typeEl, issuesEl, rateEl];

    function apply() {
        var fv = nameEl ? nameEl.value : 'all';
        var tv = typeEl ? typeEl.value : 'all';
        var iv = issuesEl ? issuesEl.value : 'all';
        var rv = rateEl ? rateEl.value : 'all';
        var shown = 0;
        rows.forEach(function (tr) {
            var ok = true;
            if (fv !== 'all' && tr.getAttribute('data-name') !== fv) { ok = false; }
            var issues = Number(tr.getAttribute('data-issues') || 0);
            if (iv === 'has' && issues <= 0) { ok = false; }
            if (iv === 'none' && issues > 0) { ok = false; }
            var manual = Number(tr.getAttribute('data-manual') || 0);
            var auto = Number(tr.getAttribute('data-auto') || 0);
            if (tv === 'manual' && manual <= 0) { ok = false; }
            if (tv === 'auto' && !(manual === 0 && auto > 0)) { ok = false; }
            var rate = Number(tr.getAttribute('data-rate') || 0);
            if (rv === 'lt100' && rate >= 100) { ok = false; }
            if (rv === 'lt50' && rate >= 50) { ok = false; }
            if (rv === 'full' && rate < 100) { ok = false; }
            tr.style.display = ok ? '' : 'none';
            if (ok) { shown++; }
        });
        if (shownEl) { shownEl.textContent = shown + ' / ' + rows.length; }
        if (resetEl) { resetEl.disabled = fv === 'all' && tv === 'all' && iv === 'all' && rv === 'all'; }
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
</script>
</body>
</html>
