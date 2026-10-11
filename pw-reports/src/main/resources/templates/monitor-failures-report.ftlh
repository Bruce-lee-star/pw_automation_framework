<#-- API monitor failure standalone report (split from summary-report on 2026-10-11).
     Visual style aligned with serenity-summary.html: white card + shadow + same palette/tables.
     Each failure is collapsible via <details>: one-line summary when collapsed, details when expanded.
     Java side does not emit this file when there is no data. -->
<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<title>API Monitor Failure Report</title>
<style media="all" type="text/css">
    * { box-sizing: border-box; }
    body {
        font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, "Helvetica Neue", Arial, sans-serif;
        -webkit-font-smoothing: antialiased;
        font-size: 14px; line-height: 1.5;
        background-color: #f6f6f6; margin: 0; padding: 20px;
        width: 100%; min-height: 100vh;
    }
    .container { margin: 0 auto !important; width: 95% !important; max-width: 1200px !important; padding: 20px !important; }
    .main {
        background: #fff; border-radius: 8px;
        box-shadow: 0 2px 12px rgba(0,0,0,0.08);
        width: 100%; overflow: hidden;
    }
    .wrapper { padding: 24px; }
    h1 { font-size: 22px; font-weight: 600; margin: 0 0 4px 0; color: #222; }
    .subtitle { color: #6c757d; font-size: 13px; margin: 0 0 20px 0; }

    /* Top stat bar (white card, low visual weight) */
    .stat-bar {
        background: #fff; border: 1px solid #dee2e6; border-radius: 8px;
        padding: 16px 24px; margin-bottom: 20px;
        display: flex; gap: 48px; align-items: center;
    }
    .stat-bar .num { font-size: 24px; font-weight: 700; line-height: 1; color: #222; }
    .stat-bar .num.has-failures { color: #f44336; }
    .stat-bar .label { font-size: 12px; color: #6c757d; margin-top: 4px; }

    /* Endpoint group */
    .owner-block { margin-bottom: 24px; }
    .owner-block h2 {
        font-size: 16px; font-weight: 600; color: #495057;
        margin: 0; padding: 10px 16px;
        background: linear-gradient(180deg, #f8f9fa 0%, #e9ecef 100%);
        border: 1px solid #dee2e6; border-bottom: none; border-radius: 6px 6px 0 0;
    }
    .owner-block h2 code {
        background: #fff; padding: 2px 8px; border-radius: 4px;
        font-size: 13px; border: 1px solid #dee2e6;
    }

    /* Collapsible failure item */
    details.fail-item {
        border: 1px solid #dee2e6; border-top: none;
        margin: 0; overflow: hidden;
    }
    details.fail-item + details.fail-item { border-top: 1px solid #dee2e6; }
    details.fail-item:last-child { border-radius: 0 0 6px 6px; }
    details.fail-item summary {
        cursor: pointer; padding: 12px 16px; font-size: 14px;
        list-style: none; display: flex; align-items: center; gap: 10px;
        user-select: none; transition: background 0.15s;
    }
    details.fail-item summary::-webkit-details-marker { display: none; }
    details.fail-item summary::before {
        content: "▸"; color: #adb5bd; font-size: 12px; transition: transform 0.15s;
    }
    details.fail-item[open] summary::before { transform: rotate(90deg); }
    details.fail-item summary:hover { background: #f8f9fa; }
    details.fail-item[open] summary { background: #f8f9fa; border-bottom: 1px solid #dee2e6; }
    .sum-method {
        font-weight: 600; color: #495057; min-width: 48px;
        font-family: "SFMono-Regular", Consolas, monospace; font-size: 13px;
    }
    .sum-status { color: #f44336; font-weight: 600; min-width: 80px; }
    .sum-reason { color: #6c757d; flex: 1; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; font-size: 13px; }
    .sum-count {
        flex-shrink: 0; background: #fef5f5; color: #f44336;
        border: 1px solid #f5c6c6;
        border-radius: 10px; padding: 2px 10px; font-size: 12px; font-weight: 600;
    }

    /* Expanded detail table */
    table.detail { width: 100%; border-collapse: collapse; }
    table.detail td {
        border: none; border-top: 1px solid #f1f3f5;
        padding: 8px 16px; font-size: 13px; vertical-align: top;
        word-wrap: break-word; overflow-wrap: break-word;
    }
    table.detail td.label {
        width: 160px; font-weight: 500; background: #fafbfc; color: #6c757d;
        white-space: nowrap;
    }
    table.detail td .url { word-break: break-all; padding: 2px 0; color: #007bff; }
    table.detail td .url + .url { border-top: 1px dashed #e9ecef; margin-top: 4px; padding-top: 6px; }

    /* Body blocks (always visible) */
    .body-block { padding: 8px 16px 12px 16px; background: #fcfcfd; border-top: 1px solid #f1f3f5; }
    .body-label { font-size: 12px; font-weight: 600; color: #495057; margin-bottom: 4px; }
    .body-block pre {
        margin: 0; padding: 8px 10px; background: #f6f8fa; border: 1px solid #e9ecef;
        border-radius: 4px; font-size: 12px; overflow-x: auto; white-space: pre-wrap; word-break: break-all;
        font-family: "SFMono-Regular", Consolas, monospace;
    }
    .body-block .none { color: #ced4da; font-size: 12px; }

    /* Data loss warning */
    .data-loss {
        border: 2px solid #f44336; border-radius: 8px;
        padding: 16px 20px; background: #fef5f5; margin-bottom: 20px;
    }
    .data-loss h3 { margin: 0 0 8px 0; color: #f44336; font-size: 15px; }
    .data-loss table { width: 100%; border-collapse: collapse; margin-top: 8px; }
    .data-loss table td { padding: 4px 8px; border-bottom: 1px solid #f5c6c6; font-size: 13px; }
    .data-loss table td.num { text-align: right; font-weight: 600; color: #f44336; }
    .footer { text-align: center; color: #adb5bd; font-size: 12px; padding: 16px; }
</style>
</head>
<body>
<div class="container">
  <div class="main">
    <div class="wrapper">
      <h1>API Monitor Failure Report</h1>
      <p class="subtitle">Grouped by endpoint &middot; same-signature failures deduplicated &middot; click a row to expand details &middot; generated alongside serenity-summary.html</p>

      <div class="stat-bar">
        <div><div class="num has-failures">${report.failureCount?c}</div><div class="label">Assertion failures</div></div>
        <div><div class="num">${report.ownerCount?c}</div><div class="label">Endpoints affected</div></div>
      </div>

<#list dedupedOwners as ownerBlock>
      <div class="owner-block">
        <h2>Endpoint: <code>${ownerBlock.owner}</code></h2>
<#list ownerBlock.items as item>
        <details class="fail-item">
          <summary>
            <span class="sum-method">${item.method!}</span>
            <span class="sum-status">${item.status!'?'}</span>
            <span class="sum-reason">${item.reason!}</span>
            <span class="sum-count">${item.count?c}x</span>
          </summary>
          <table class="detail">
            <tr><td class="label">HTTP method</td><td>${item.method!}</td></tr>
            <tr><td class="label">Response status</td><td style="color:#f44336;font-weight:600;">${item.status!'?'}</td></tr>
            <tr><td class="label">Request URL</td><td>
<#list item.urls as url>
              <div class="url">${url}</div>
</#list>
            </td></tr>
            <tr><td class="label">Failure reason</td><td>${item.reason!}</td></tr>
          </table>

          <div class="body-block">
            <div class="body-label">Request body</div>
<#if item.requestBody?? && item.requestBody?length &gt; 0>
            <pre>${item.requestBody?no_esc}</pre>
<#else>
            <span class="none">(none)</span>
</#if>
          </div>
          <div class="body-block">
            <div class="body-label">Response body</div>
<#if item.responseBody?? && item.responseBody?length &gt; 0>
            <pre>${item.responseBody?no_esc}</pre>
<#else>
            <span class="none">(none)</span>
</#if>
          </div>
        </details>
</#list>
      </div>
</#list>

<#if report.dataLossByCategory?has_content>
      <div class="data-loss">
        <h3>&#9888; Data integrity warning (API monitor data loss)</h3>
        <p>A total of <strong>${report.totalDataLoss?c}</strong> monitor records failed to persist or were dropped this run; some monitor data is missing.</p>
        <table>
<#list report.dataLossByCategory as cat, count>
          <tr><td><code>${cat!}</code></td><td class="num">${count?c} records</td></tr>
</#list>
        </table>
        <p style="margin-top:8px;color:#999;font-size:12px;">Troubleshooting: check monitor.db.* configuration, database availability, Hikari pool, and batch writer logs.</p>
      </div>
</#if>
    </div>
    <div class="footer">Report produced by Serenity BDD &middot; API Monitor</div>
  </div>
</div>
</body>
</html>
