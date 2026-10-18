<#--
  per-case HTML —— 对齐 docs/template-design/test-result.html 的新设计（英文文案）。
  数据模型：outcome（TestOutcome）+ featureName/featureLink（回 Feature 页）+
  consoleGroups（用例级控制台分组）+ stepConsole（stepId → 步骤级控制台分组）。
  所有用户可控文本一律 ?html 转义；时长/时间戳由 SnapshotGateTest 规范化后比对。

  结果键归一（SUCCESS→PASS）在模板内用 function 完成，与 ReportView 的口径保持一致：
  徽章类名 b-<KEY>、步骤时间线类名 st-<CLASS>。

  控制台/API 分组：由 ConsoleClassifier 在 Java 侧按行内容分类（Route / Network / Log /
  Console / Error），避免把 route 注册、API 调用、应用日志、普通 stdout 混在一个
  “Standard output” 里（2026-10-18 用户反馈：Standard out 太通用、日志没有归类）。
-->
<#function rkey r = "">
  <#-- 步骤/用例的 result 可能为 null：FreeMarker 会把 null 实参视为“缺失参数”，
       故必须给默认值（空串），再在内部判定为 UNDEFINED，否则渲染直接抛错。
       注意【不能】用 r?is_string 判空：枚举被 BeansWrapper 包装成字符串标量，
       is_string 对枚举同样为 true，会把真实结果误判成空值（曾导致详情页恒显 Undefined）。 -->
  <#if !r?? || r == ""><#return "UNDEFINED"></#if>
  <#return (r.name() == "SUCCESS")?then("PASS", r.name())>
</#function>
<#function keyLabel k>
  <#switch k>
    <#case "PASS"><#return "Passed">
    <#case "FAILURE"><#return "Failed">
    <#case "ERROR"><#return "Error">
    <#case "PENDING"><#return "Pending">
    <#case "SKIPPED"><#return "Skipped">
    <#case "IGNORED"><#return "Ignored">
    <#default><#return "Undefined">
  </#switch>
</#function>
<#function rlabel r = "">
  <#return keyLabel(rkey(r))>
</#function>
<#function sclass r = "">
  <#local k = rkey(r)>
  <#if k == "PASS"><#return "PASS"></#if>
  <#if k == "FAILURE" || k == "ERROR"><#return "FAILURE"></#if>
  <#if k == "PENDING"><#return "PENDING"></#if>
  <#return "SKIP">
</#function>

<#-- 控制台/API 分组面板：每组一个可折叠 details，按 tone 着色（route/net/log/out/err）。

     行渲染分三类（2026-10-18 用户反馈"整片日志很丑"）：
       ① 行首缩进 → 多值参数的续行（RestAssured 的 Query params / Headers 等），缩进到值列；
       ② "Key:<空白>value" → 键值两列（RestAssured log().all() 的请求/响应转储），值列自动换行；
          值为 <none> 的键直接丢弃（2026-10-18 用户反馈：这些行没有信息量）；
       ③ 其余 → 原始等宽行。
     判据刻意收紧到"首字母开头 + 冒号后必须有空白"，避免把 https:// 与时间戳 12:34:56 误判成键值。 -->
<#macro consolePanel groups>
  <#list groups as g>
    <details class="con-group con-${g.tone}">
      <summary>${g.label?html}<span class="con-count">${g.lines?size}</span></summary>
      <ul class="con-lines">
        <#list g.lines as line>
          <#if line?matches("^\\s.*")>
            <li class="con-cont">${line?trim?html}</li>
          <#elseif line?matches("[A-Za-z][A-Za-z0-9 _/.-]*:\\s.*")>
            <#assign kvKey = line?keep_before(":")?trim>
            <#assign kvValue = line?keep_after(":")?trim>
            <#if kvValue?lower_case != "<none>">
            <li class="con-kv"><span class="con-k">${kvKey?html}</span><span class="con-v">${kvValue?html}</span></li>
            </#if>
          <#else>
            <li class="con-raw">${line?html}</li>
          </#if>
        </#list>
      </ul>
    </details>
  </#list>
</#macro>

<#macro stepTree steps>
    <#list steps as step>
        <li class="st-${sclass(step.result)}">
            <span class="node"></span>
            <div class="step-row">
                <span class="step-desc">${step.description?html}</span>
                <span class="step-time"><#if step.duration??>${step.duration} ms</#if></span>
            </div>
            <#if step.dataTable?has_content>
                <table class="tbl step-table">
                    <#list step.dataTable as row>
                        <tr><#list row as cell><td>${cell?html}</td></#list></tr>
                    </#list>
                </table>
            </#if>
            <#if step.docString??><pre class="att-text step-docstring">${step.docString?html}</pre></#if>
            <#if step.screenshot??>
                <div class="shot"><img src="${outcome.outcomeId}/screenshots/${step.screenshot}" alt="screenshot"/></div>
            </#if>
            <#if step.exception??>
                <#-- 失败信息只挂在失败步骤下（不再额外渲染页面级错误卡，避免重复）；
                     堆栈同样归属于该步骤。 -->
                <div class="step-note">${step.exception.errorType!"error"}: ${step.exception.message?html}</div>
                <#if outcome.testFailureSummary??>
                    <details class="step-stack"><summary>Exception details</summary><pre>${outcome.testFailureSummary?html}</pre></details>
                </#if>
            </#if>
            <#if step.evidence?has_content>
                <ul class="evidence"><#list step.evidence as line><li>${line?html}</li></#list></ul>
            </#if>
            <#if step.attachments?has_content>
                <div class="step-actions">
                    <#list step.attachments as att>
                        <details class="attachment">
                            <summary>${att.name?html} <span class="att-type">(${att.contentType})</span></summary>
                            <#if att.contentType == "text/html">
                                <div class="att-html">${att.content}</div>
                            <#else>
                                <pre class="att-text">${att.content?html}</pre>
                            </#if>
                        </details>
                    </#list>
                </div>
            </#if>
            <#-- 步骤级控制台：按类别分组展示（Route / Network / Log / Console / Error），
                 取代原先笼统的 "Standard output / Standard error"。 -->
            <#if step.stepId??>
                <#if stepConsole?? && stepConsole[step.stepId]??>
                    <@consolePanel stepConsole[step.stepId]/>
                </#if>
            </#if>
            <#if step.children?has_content>
                <ul class="steps"><@stepTree step.children/></ul>
            </#if>
        </li>
    </#list>
</#macro>
<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8"/>
    <meta name="viewport" content="width=device-width, initial-scale=1"/>
    <#if baseHref?? && baseHref?has_content><base href="${baseHref}"></#if>
    <title>${outcome.name?html}</title>
    <link rel="icon" type="image/svg+xml" href="assets/favicon.svg"/>
    <link rel="stylesheet" href="assets/report-v2.css"/>
    <style>
        /* 新设计 CSS 未覆盖的局部样式（证据 / 附件 / 截图 / 未定义结果） */
        .evidence { margin: 6px 0; padding-left: 20px; font-family: ui-monospace, SFMono-Regular, Consolas, monospace; font-size: 12px; }
        .attachment { margin: 6px 0; }
        .attachment > summary { cursor: pointer; color: var(--text-2); font-size: 12px; }
        .att-text, .att-html { background: #f8fafc; border: 1px solid var(--border); border-radius: 6px; padding: 8px; font-size: 12px; overflow-x: auto; }
        /* 截图：悬浮放大（2 倍，左上角为锚点，压在其它内容之上，不改变布局） */
        .shot { position: relative; overflow: visible; }
        .shot img {
            max-width: 320px; border: 1px solid var(--border); border-radius: 6px; margin-top: 6px;
            cursor: zoom-in; transition: transform .15s ease; transform-origin: top left;
        }
        .shot img:hover {
            transform: scale(2); z-index: 20; position: relative;
            box-shadow: 0 8px 28px rgba(31, 45, 61, .28);
        }
        .step-note { font-size: 12px; color: var(--failure); margin-top: 4px; }
        .step-actions { margin-top: 4px; }
        .b-UNDEFINED { background: var(--skipped); }
        .step-table { margin: 8px 0 2px; font-size: 12px; }
        .step-table td { padding: 4px 10px; border-bottom: 1px solid var(--border); }
        .step-table tr:first-child td { font-weight: 600; background: #f8fafc; }
        .step-docstring { margin: 8px 0 2px; white-space: pre-wrap; }
    </style>
</head>
<body>

<div class="topbar">
  <h1>Scenario <#if featureLink??><a href="${featureLink}" style="color:#fff;text-decoration:none;font-size:14px;font-weight:400">← back to ${featureName?html}</a><#else><a href="index.html" style="color:#fff;text-decoration:none;font-size:14px;font-weight:400">← back to overview</a></#if></h1>
  <div class="meta"><#if outcome.userStory??>${outcome.userStory.storyName?html}</#if><br>reportVersion ${outcome.reportVersion}</div>
</div>

<div class="wrap">

  <div class="card">
    <div class="crumbs"><a href="index.html">Overview</a><#if featureLink??><span class="sep">›</span><a href="${featureLink}">${featureName?html}</a></#if><span class="sep">›</span><b>${outcome.name?html}</b></div>
    <div class="case-head">
      <#-- result 可能尚未落定（已有失败原因但结果为 null）：此时按“有失败原因即失败”推导，
           避免详情页显示 Undefined 而总览页显示 Failed 的自相矛盾。 -->
      <#assign caseKey = (outcome.result??)?then(rkey(outcome.result), (outcome.testFailureCause??)?then("FAILURE", "UNDEFINED"))>
      <span class="badge b-${caseKey}" style="padding:4px 14px;font-size:14px">${keyLabel(caseKey)}</span>
      <div class="case-title">${outcome.name?html}</div>
    </div>
    <#-- 时长 / 开始时间 / 类型 / tags 独占一行（与标题分行的原因：标题长短不一，混排在 flex 行内会被挤压换行） -->
    <div class="case-meta">
      <span class="chip"><#if outcome.duration gt 0>${(outcome.duration / 1000)?string["0.#"]}s<#else>—</#if></span>
      <#if outcome.startTime??><span class="chip">started ${outcome.startTime?html}</span></#if>
      <span class="chip">${outcome.manual?then('Manual','Automated')}</span>
      <#list outcome.tags![] as tag><span class="chip tag">${tag?html}</span></#list>
    </div>
  </div>

  <#if outcome.tracePath??>
  <div class="trace-bar">
    <span class="tname">Playwright Trace</span>
    <span class="tmeta">${outcome.tracePath?html}</span>
    <#-- 二态深链（对齐 Playwright 官方远程 trace 模式 ?trace=<可访问URL> + CORS）：
         报告已托管（baseHref 存在，通常 CI HTTPS 制品 / 反代）→ 拼绝对 URL，公开 Viewer 跨源拉取（服务端需 CORS，无需 LNA）；
         本地（无 baseHref，HTTP）→ 用相对路径，Viewer 经 referrer + Local Network Access 解析（HTTP 绝对 URL 会撞 mixed-content 被拦）。 -->
    <#if baseHref?? && baseHref?has_content>
      <#assign traceUrl = baseHref + outcome.tracePath>
    <#else>
      <#assign traceUrl = outcome.tracePath>
    </#if>
    <a class="btn-trace" href="${traceViewerBaseUrl!'https://trace.playwright.dev'}/?trace=${traceUrl?url}" target="_blank" rel="noopener">▶ Play trace</a>
  </div>
  </#if>

  <#if outcome.manual && outcome.manualNote??>
  <div class="card manual-panel">
    <div class="sub">Manual execution note</div>
    <div>${outcome.manualNote?html}</div>
  </div>
  </#if>

  <div class="card">
    <h3><span class="icon"></span>Execution steps</h3>
    <ul class="steps" id="steps"><@stepTree outcome.testSteps![]/></ul>
  </div>

  <#if outcome.evidence?has_content>
  <div class="card">
    <h3><span class="icon"></span>Evidence</h3>
    <ul class="evidence"><#list outcome.evidence as line><li>${line?html}</li></#list></ul>
  </div>
  </#if>

  <#if outcome.apiOperations?has_content>
  <div class="card">
    <h3><span class="icon"></span>API operations (${outcome.apiOperations?size})</h3>
    <ul class="api-lines"><#list outcome.apiOperations as line><li>${line?html}</li></#list></ul>
  </div>
  </#if>

  <#if outcome.routeRegistrations?has_content>
  <div class="card">
    <h3><span class="icon"></span>Route registrations (${outcome.routeRegistrations?size})</h3>
    <ul class="route-lines"><#list outcome.routeRegistrations as line><li>${line?html}</li></#list></ul>
  </div>
  </#if>

  <#if outcome.attachments?has_content>
  <div class="card">
    <h3><span class="icon"></span>Attachments (${outcome.attachments?size})</h3>
    <#list outcome.attachments as att>
      <details class="attachment">
        <summary>${att.name?html} <span class="att-type">(${att.contentType})</span></summary>
        <#if att.contentType == "text/html"><div class="att-html">${att.content}</div><#else><pre class="att-text">${att.content?html}</pre></#if>
      </details>
    </#list>
  </div>
  </#if>

  <#-- 用例级控制台：所有用例均展示（含 @Before/@After hook 输出）；步骤级日志仍各自挂在步骤下。 -->
  <#if consoleGroups?has_content>
  <div class="card">
    <h3><span class="icon"></span>Console &amp; API activity</h3>
    <@consolePanel consoleGroups/>
  </div>
  </#if>

  <#-- 浏览器日志：page console（error/warning）+ page-error（去噪后），所有用例均展示 -->
  <#if (outcome.browserConsole?has_content)!false || (outcome.browserErrors?has_content)!false>
  <div class="card">
    <h3><span class="icon"></span>Browser logs</h3>
    <#if (outcome.browserErrors?has_content)!false>
      <div class="sub">Page errors</div>
      <ul class="con-lines">
        <#list outcome.browserErrors as line><li class="con-raw">${line?html}</li></#list>
      </ul>
    </#if>
    <#if (outcome.browserConsole?has_content)!false>
      <div class="sub">Console (error / warning)</div>
      <ul class="con-lines">
        <#list outcome.browserConsole as line><li class="con-raw">${line?html}</li></#list>
      </ul>
    </#if>
  </div>
  </#if>

  <#if (outcome.scenarioOutline!false) && outcome.examples?has_content>
  <div class="card">
    <h3><span class="icon"></span>Examples (${outcome.examples?size})</h3>
    <table class="tbl">
      <thead><tr><th>#</th><th>Result</th><th>Example</th></tr></thead>
      <tbody>
      <#list outcome.examples as ex>
        <tr>
          <td>${ex_index + 1}</td>
          <td><span class="badge b-${rkey(ex.result)}">${rlabel(ex.result)}</span></td>
          <td><a href="${ex.outcomeId}.html">${ex.name?html}<#if ex.exampleValues?has_content> — ${(ex.exampleValues?join(", "))?html}</#if></a></td>
        </tr>
      </#list>
      </tbody>
    </table>
  </div>
  </#if>

  <footer>Scenario page · reportVersion ${outcome.reportVersion} · outcomeId ${outcome.outcomeId}</footer>
</div>

<script>
(function(){
  var OUTCOME_ID = "${outcome.outcomeId}";
  window.__doPatch = function(files) {
    if (!files || !files.length) return;
    var results = [];
    var readers = [];
    for (var i = 0; i < files.length; i++) {
      (function(f) {
        readers.push(new Promise(function(resolve) {
          var r = new FileReader();
          r.onload = function() { results.push({filename: f.name, dataBase64: r.result.split(',')[1]}); resolve(); };
          r.readAsDataURL(f);
        }));
      })(files[i]);
    }
    Promise.all(readers).then(function() {
      var payload = JSON.stringify({outcomeId: OUTCOME_ID, screenshots: results});
      if (location.protocol === 'http:' || location.protocol === 'https:') {
        fetch('api/patch', {method: 'POST', body: payload})
          .then(function(r){ return r.json(); })
          .then(function(d){ if (d.ok) { alert('Uploaded ' + results.length + ' screenshot(s), reloading...'); setTimeout(function(){location.reload()}, 800); } else { alert('Failed: ' + (d.error || 'unknown')); } })
          .catch(function(e){ alert('Upload failed: ' + e); });
      } else {
        var blob = new Blob([payload], {type: 'application/json'});
        var a = document.createElement('a');
        a.href = URL.createObjectURL(blob);
        a.download = OUTCOME_ID + '.patch.json';
        a.click();
        alert('Patch downloaded: ' + a.download + '\nApply locally with ManualCaseRecorder --apply-patch ' + a.download);
      }
    });
  };
})();
</script>
</body>
</html>
