<#--
  邮件用单页报告（email-report.html）——自包含单文件，专为邮件发送设计。

  与 index.ftl 的关键差异（邮件客户端约束）：
  · 不引用任何外部资源：无 <link>、无 <script>、无 assets/*.css、无 favicon、无图片；
  · 样式全部写在本文件 <style> 内，并对关键元素再写 inline style
    （Gmail / Outlook 会剥离部分 <style>，故颜色、间距不能只靠 class）；
  · 表格布局：Outlook 桌面端用 Word 引擎渲染，不支持 flex / grid / SVG，
    故环形图退化为分段条，布局一律 table + inline style；
  · 无任何交互：邮件客户端会剥离 <script>，筛选/折叠一律不做，内容服务端渲染完成。

  模型见 EmailReportRenderer.emailModel（复用 SummaryHtmlRenderer.summaryModel 口径）。
-->
<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8"/>
    <meta name="viewport" content="width=device-width, initial-scale=1"/>
    <title>${projectName?html} · Test Report</title>
    <style>
        body { margin:0; padding:0; background:#f1f5f9; }
        body, td, th { font-family:Arial, Helvetica, sans-serif; color:#1f2d3d; font-size:13px; line-height:1.5; }
        table { border-collapse:collapse; }
        .hdr { background:#1f2d3d; padding:18px 22px; }
        .hdr h1 { margin:0; font-size:19px; font-weight:bold; color:#ffffff; }
        .hdr .meta { color:#cbd5e1; font-size:12px; text-align:right; }
        .card { background:#ffffff; border:1px solid #e2e8f0; }
        .card-h { background:#f8fafc; padding:10px 16px; font-weight:bold; font-size:13px; border-bottom:1px solid #e2e8f0; }
        .sec { padding:16px; }
        .kpi { background:#ffffff; border:1px solid #e2e8f0; text-align:center; padding:14px 6px; }
        .kpi .n { font-size:24px; font-weight:bold; }
        .kpi .l { font-size:11px; color:#64748b; text-transform:uppercase; letter-spacing:.4px; }
        th.th { background:#f8fafc; text-align:left; padding:8px 10px; font-size:12px; color:#475569; border-bottom:1px solid #e2e8f0; }
        td.td { padding:8px 10px; border-bottom:1px solid #eef2f7; vertical-align:top; }
        .num { text-align:right; }
        .foot { padding:14px 16px; font-size:11px; color:#94a3b8; }
        .msg { font-family:Consolas, Menlo, monospace; font-size:12px; color:#b91c1c; }
        .name { font-weight:600; }
        .sub { color:#64748b; font-size:12px; }
        .ok { background:#f0fdf4; border:1px solid #bbf7d0; color:#15803d; padding:12px 16px; font-weight:600; }
        /* 运行环境：与 index.ftl 的 .env-grid 对齐（邮件客户端只能用 table 模拟卡片网格） */
        .env-card { background:#f8fafc; border:1px solid #e2e8f0; }
        .env-k { padding:7px 11px 2px; font-size:10px; color:#64748b; text-transform:uppercase; letter-spacing:.4px; }
        .env-v { padding:0 11px 9px; font-size:13px; font-weight:600; color:#1f2d3d; }
        .env-sub { margin-top:14px; padding-bottom:6px; font-size:11px; font-weight:700; letter-spacing:.06em; text-transform:uppercase; color:#64748b; }
        .browser-bar { border-radius:5px; overflow:hidden; }
        .browser-legend { font-size:12px; color:#64748b; }
        .browser-legend b { color:#1f2d3d; }
    </style>
</head>
<body>
<table role="presentation" width="100%" cellpadding="0" cellspacing="0" border="0" bgcolor="#f1f5f9">
<tr>
<td align="center" style="padding:16px 8px;">

<table role="presentation" width="680" cellpadding="0" cellspacing="0" border="0">

    <!-- 头部 -->
    <tr>
        <td class="hdr" bgcolor="#1f2d3d">
            <table role="presentation" width="100%" cellpadding="0" cellspacing="0" border="0">
                <tr>
                    <td><h1>${projectName?html} — Test Report</h1></td>
                    <td class="meta">${generatedAt?html}</td>
                </tr>
            </table>
        </td>
    </tr>
    <tr><td height="12" style="font-size:0;line-height:12px;">&nbsp;</td></tr>

    <!-- KPI -->
    <tr>
        <td>
            <table role="presentation" width="100%" cellpadding="0" cellspacing="0" border="0">
                <tr>
                    <td class="kpi" width="25%" bgcolor="#ffffff"><div class="n">${count}</div><div class="l">Scenarios</div></td>
                    <td class="kpi" width="25%" bgcolor="#ffffff"><div class="n" style="color:#16a34a">${passCount}</div><div class="l">Passed</div></td>
                    <td class="kpi" width="25%" bgcolor="#ffffff"><div class="n" style="color:#dc2626">${failCount + errorCount}</div><div class="l">Failed / Error</div></td>
                    <td class="kpi" width="25%" bgcolor="#ffffff"><div class="n" style="color:#2563eb">${passPct?c}%</div><div class="l">Pass rate</div></td>
                </tr>
            </table>
        </td>
    </tr>
    <tr><td height="12" style="font-size:0;line-height:12px;">&nbsp;</td></tr>

    <!-- 结果分布 -->
    <tr>
        <td class="card">
            <div class="card-h">Result distribution</div>
            <div class="sec">
                <table role="presentation" width="100%" cellpadding="0" cellspacing="0" border="0" bgcolor="#e2e8f0">
                    <tr>
                    <#if count gt 0>
                        <#if (passPct > 0)><td width="${passPct?c}%" bgcolor="#16a34a" style="height:12px;font-size:0;line-height:12px;">&nbsp;</td></#if>
                        <#if (failPct > 0)><td width="${failPct?c}%" bgcolor="#dc2626" style="height:12px;font-size:0;line-height:12px;">&nbsp;</td></#if>
                        <#if (errorPct > 0)><td width="${errorPct?c}%" bgcolor="#ea580c" style="height:12px;font-size:0;line-height:12px;">&nbsp;</td></#if>
                        <#if (pendingPct > 0)><td width="${pendingPct?c}%" bgcolor="#d97706" style="height:12px;font-size:0;line-height:12px;">&nbsp;</td></#if>
                        <#if (skippedPct > 0)><td width="${skippedPct?c}%" bgcolor="#94a3b8" style="height:12px;font-size:0;line-height:12px;">&nbsp;</td></#if>
                    <#else>
                        <td style="height:12px;font-size:0;line-height:12px;">&nbsp;</td>
                    </#if>
                    </tr>
                </table>
                <table role="presentation" width="100%" cellpadding="0" cellspacing="0" border="0" style="margin-top:10px;">
                    <tr>
                        <th class="th">Result</th>
                        <th class="th num">Count</th>
                        <th class="th num">Share</th>
                    </tr>
                    <tr>
                        <td class="td"><span style="display:inline-block;width:9px;height:9px;background:#16a34a;">&nbsp;</span> Passed</td>
                        <td class="td num">${passCount}</td>
                        <td class="td num">${passPct?c}%</td>
                    </tr>
                    <tr>
                        <td class="td"><span style="display:inline-block;width:9px;height:9px;background:#dc2626;">&nbsp;</span> Failed</td>
                        <td class="td num">${failCount}</td>
                        <td class="td num">${failPct?c}%</td>
                    </tr>
                    <tr>
                        <td class="td"><span style="display:inline-block;width:9px;height:9px;background:#ea580c;">&nbsp;</span> Error</td>
                        <td class="td num">${errorCount}</td>
                        <td class="td num">${errorPct?c}%</td>
                    </tr>
                    <tr>
                        <td class="td"><span style="display:inline-block;width:9px;height:9px;background:#d97706;">&nbsp;</span> Pending</td>
                        <td class="td num">${pendingCount}</td>
                        <td class="td num">${pendingPct?c}%</td>
                    </tr>
                    <tr>
                        <td class="td"><span style="display:inline-block;width:9px;height:9px;background:#94a3b8;">&nbsp;</span> Skipped</td>
                        <td class="td num">${skippedCount}</td>
                        <td class="td num">${skippedPct?c}%</td>
                    </tr>
                </table>
            </div>
        </td>
    </tr>
    <tr><td height="12" style="font-size:0;line-height:12px;">&nbsp;</td></tr>

    <!-- 失败明细（邮件收件人最关心的可执行信息） -->
    <tr>
        <td class="card">
            <div class="card-h">Failures (${failures?size})</div>
            <div class="sec">
            <#if failures?has_content>
                <table role="presentation" width="100%" cellpadding="0" cellspacing="0" border="0">
                    <tr>
                        <th class="th" width="70">Result</th>
                        <th class="th">Scenario</th>
                        <th class="th" width="150">Feature</th>
                    </tr>
                    <#list failures as f>
                    <tr>
                        <td class="td"><span style="background:#dc2626;color:#ffffff;padding:2px 7px;font-size:11px;">${f.resultLabel?html}</span></td>
                        <td class="td">
                            <div class="name">${f.name?html}</div>
                            <#if f.message?has_content><div class="msg">${f.message?html}</div></#if>
                        </td>
                        <td class="td sub">${f.feature?html}</td>
                    </tr>
                    </#list>
                </table>
            <#else>
                <div class="ok">All scenarios passed — no failures in this run.</div>
            </#if>
            </div>
        </td>
    </tr>
    <tr><td height="12" style="font-size:0;line-height:12px;">&nbsp;</td></tr>

    <!-- Feature 汇总 -->
    <tr>
        <td class="card">
            <div class="card-h">Feature summary (${featureCount})</div>
            <div class="sec">
            <#if features?has_content>
                <table role="presentation" width="100%" cellpadding="0" cellspacing="0" border="0">
                    <tr>
                        <th class="th">Feature</th>
                        <th class="th num" width="80">Scenarios</th>
                        <th class="th num" width="70">Passed</th>
                        <th class="th num" width="70">Issues</th>
                        <th class="th" width="120">Pass rate</th>
                    </tr>
                    <#list features as ft>
                    <tr>
                        <td class="td">${ft.feature?html}</td>
                        <td class="td num">${ft.scenarioCount}</td>
                        <td class="td num">${ft.passCount}</td>
                        <td class="td num"><#if ft.issueCount gt 0><span style="color:#dc2626">${ft.issueCount}</span><#else>0</#if></td>
                        <td class="td">
                            <table role="presentation" width="100%" cellpadding="0" cellspacing="0" border="0" bgcolor="#e2e8f0">
                                <tr>
                                    <#if (ft.passPct > 0)><td width="${ft.passPct?c}%" bgcolor="#16a34a" style="height:8px;font-size:0;line-height:8px;">&nbsp;</td></#if>
                                    <#if (ft.passPct < 100)><td width="${(100 - ft.passPct)?c}%" bgcolor="#dc2626" style="height:8px;font-size:0;line-height:8px;">&nbsp;</td></#if>
                                </tr>
                            </table>
                            <span class="sub">${ft.passPct?c}%</span>
                        </td>
                    </tr>
                    </#list>
                </table>
            <#else>
                <div class="sub">No features in this run.</div>
            </#if>
            </div>
        </td>
    </tr>
    <tr><td height="12" style="font-size:0;line-height:12px;">&nbsp;</td></tr>

    <!-- 运行环境（与 index.ftl 的 .env-grid 对齐：两列卡片网格；邮件客户端无 grid/SVG/JS，用 table 模拟） -->
    <tr>
        <td class="card">
            <div class="card-h">Environment</div>
            <div class="sec">
                <table role="presentation" width="100%" cellpadding="0" cellspacing="0" border="0">
                <#list env as e>
                    <#if e?index % 2 == 0><tr></#if>
                    <td width="50%" valign="top" style="padding:4px;">
                        <table role="presentation" width="100%" cellpadding="0" cellspacing="0" border="0" class="env-card">
                            <tr><td class="env-k">${e.k?html}</td></tr>
                            <tr><td class="env-v"><#if e.v??>${e.v?html}<#else>—</#if></td></tr>
                        </table>
                    </td>
                    <#if e?index % 2 == 1 || e?is_last>
                        <#if e?index % 2 == 0><td width="50%"></td></#if>
                        </tr>
                    </#if>
                </#list>
                </table>
                <#if browsers?has_content>
                <div class="env-sub">Browsers</div>
                <table role="presentation" width="100%" cellpadding="0" cellspacing="0" border="0" class="browser-bar" bgcolor="#e2e8f0">
                    <tr>
                    <#list browsers as b><td width="${(100 / browsers?size)?round}%" bgcolor="#3b82f6" style="height:10px;font-size:0;line-height:10px;">&nbsp;</td></#list>
                    </tr>
                </table>
                <table role="presentation" width="100%" cellpadding="0" cellspacing="0" border="0" class="browser-legend">
                    <tr>
                    <#list browsers as b><td><#if b.version?has_content><b>${b.name?html} ${b.version?html}</b><#else>${b.name?html}</#if></td></#list>
                    </tr>
                </table>
                </#if>
            </div>
        </td>
    </tr>
    <tr><td height="12" style="font-size:0;line-height:12px;">&nbsp;</td></tr>

    <!-- 页脚 -->
    <tr>
        <td class="card">
            <div class="foot">
                ${projectName?html} · reportVersion ${reportVersion} · duration ${totalDurationText?html}<br/>
                Self-contained single-file report — no external CSS/JS (email safe).
            </div>
        </td>
    </tr>

</table>

</td>
</tr>
</table>
</body>
</html>
