<#-- monitor-failures.html — API 监控失败独立报告（对齐旧版 2026-10-11 拆出语义）。
     与旧版差异：无 owner 维度（2026-10-16 决策），按 feature 分组；
     Java 侧无数据时不调用本模板（文件存在即有失败）。 -->
<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<#if baseHref?? && baseHref?has_content><base href="${baseHref}"></#if>
<title>API Monitor Failure Report</title>
<style>
  * { box-sizing: border-box; }
  body { font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, "Helvetica Neue", Arial, sans-serif;
         -webkit-font-smoothing: antialiased; font-size: 14px; line-height: 1.5;
         background-color: #f6f6f6; margin: 0; padding: 20px; width: 100%; min-height: 100vh; }
  .container { margin: 0 auto; width: 95%; max-width: 1200px; padding: 0; }
  .main { background: #fff; border-radius: 8px; box-shadow: 0 2px 12px rgba(0,0,0,0.08); width: 100%; overflow: hidden; }
  .wrapper { padding: 24px; }
  h1 { font-size: 22px; font-weight: 600; margin: 0 0 4px 0; color: #222; }
  .subtitle { color: #6c757d; font-size: 13px; margin: 0 0 20px 0; }
  .stat-bar { background: #fff; border: 1px solid #dee2e6; border-radius: 8px;
              padding: 16px 24px; margin-bottom: 20px; display: flex; gap: 48px; align-items: center; }
  .stat-bar .num { font-size: 24px; font-weight: 700; line-height: 1; color: #f44336; }
  .stat-bar .num.neutral { color: #222; }
  .stat-bar .label { font-size: 12px; color: #6c757d; margin-top: 4px; }
  .feature-block { margin-bottom: 24px; }
  .feature-block h2 { font-size: 16px; font-weight: 600; color: #495057; margin: 0; padding: 10px 16px;
                      background: linear-gradient(180deg, #f8f9fa 0%, #e9ecef 100%);
                      border: 1px solid #dee2e6; border-bottom: none; border-radius: 6px 6px 0 0; }
  details.fail-item { border: 1px solid #dee2e6; border-top: none; margin: 0; overflow: hidden; }
  details.fail-item + details.fail-item { border-top: 1px solid #dee2e6; }
  details.fail-item:last-child { border-radius: 0 0 6px 6px; }
  details.fail-item summary { padding: 10px 16px; cursor: pointer; font-size: 13px; background: #fff;
                              list-style-position: inside; }
  details.fail-item summary:hover { background: #f8f9fa; }
  details.fail-item .detail { padding: 12px 16px; background: #fff; border-top: 1px solid #e9ecef; }
  details.fail-item pre { background: #f8f9fa; border: 1px solid #dee2e6; border-radius: 6px;
                          padding: 10px 12px; font-size: 12px; white-space: pre-wrap; word-break: break-word; margin: 0; }
  .scenario { color: #0d6efd; text-decoration: none; font-size: 12px; }
  .scenario:hover { text-decoration: underline; }
  .footer { padding: 16px 24px; text-align: center; color: #999999; font-size: 12px; }
</style>
</head>
<body>
<div class="container">
  <div class="main">
    <div class="wrapper">
      <h1>API Monitor Failure Report</h1>
      <p class="subtitle">API monitor failures collected during this run (grouped by feature)</p>
      <div class="stat-bar">
        <div><div class="num">${totalFailures}</div><div class="label">Failures</div></div>
        <div><div class="num neutral">${affectedScenarios}</div><div class="label">Scenarios affected</div></div>
      </div>
      <#list groups as g>
        <div class="feature-block">
          <h2>${g.feature}</h2>
          <#list g.items as item>
            <details class="fail-item">
              <summary>${item.line}</summary>
              <div class="detail">
                <pre>${item.line}</pre>
                <p>Scenario: <a class="scenario" href="${item.link}">${item.scenario}</a></p>
              </div>
            </details>
          </#list>
        </div>
      </#list>
    </div>
    <div class="footer">Report produced by pw-reports</div>
  </div>
</div>
</body>
</html>
