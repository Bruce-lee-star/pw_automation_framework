<#--
  邮件用单页报告（email-report.html）——自包含单文件，视觉对齐 v2 设计系统（report-v2.css）：
  顶部横幅（品牌 + 运行概览 + 结果分段条）→ 全绿横幅（无失败时）→ 6 个 KPI 卡（含 Features）→ Summary
  （分布条 + 图例 + 运行时间 + 耗时）→ 查看/下载 → Test Failure Overview
  → Full Failure List → Full Test Results → 页脚。

  2026-10-22 用户决定删除的两节：Functional Coverage（feature 统计并入顶部 KPI 卡，避免重复）、
  Environment（用户反馈 Summary 加环境列后"不是很好看"，邮件不再展示环境信息）。

  邮件客户端约束（Outlook 用 Word 引擎、Gmail 剥离 script/部分 style）：
  · 无外部资源：不 <link>/<script>/assets/favicon/图片；样式只在本文件 <style>，关键元素再写 inline；
  · 布局一律 table + inline style，禁用 flex/grid/SVG（环形图退化为分段条）；
  · 无交互：筛选/折叠一律不做，服务端渲染完成。
-->

<#-- 结果徽章底色：由展示标签反查（避免模板里再依赖 resultKey） -->
<#function badgeBg label>
  <#switch label>
    <#case "Passed"><#return "#16a34a">
    <#case "Failed"><#return "#dc2626">
    <#case "Error"><#return "#ea580c">
    <#case "Pending"><#return "#d97706">
    <#case "Skipped"><#return "#94a3b8">
    <#case "Ignored"><#return "#94a3b8">
    <#default><#return "#6b7a90">
  </#switch>
</#function>

<#-- 失败分类徽章底色：按展示名反查（ErrorClassifier 内置分类；配置自定义类型走 default） -->
<#function typeBg label>
  <#switch label>
    <#case "Timeout"><#return "#b45309">
    <#case "Navigation loss"><#return "#2563eb">
    <#case "Context closed"><#return "#7c3aed">
    <#case "Frame detached"><#return "#0891b2">
    <#case "Element not found"><#return "#db2777">
    <#case "Assertion failure"><#return "#b91c1c">
    <#case "Driver / infra"><#return "#64748b">
    <#default><#return "#6b7a90">
  </#switch>
</#function>

<#-- 卡片头：与 v2 的 .card h3（圆点 + 标题）一致 -->
<#macro secHead title>
<div style="padding:14px 18px;border-bottom:1px solid #e4e9f0;">
    <span style="display:inline-block;width:8px;height:8px;border-radius:50%;background:#3b82f6;margin-right:8px;vertical-align:middle;"></span>
    <span style="font-size:15px;font-weight:600;color:#1f2d3d;vertical-align:middle;">${title?html}</span>
</div>
</#macro>

<#-- KPI 卡：左侧色条 + 大数字（对齐 v2 的 .kpi）。
     w = 单元格宽度百分比（默认 20 = 5 卡；2026-10-22 新增 Features 卡后为 6 卡，传 16.66） -->
<#macro kpiCell label num pct color w="20">
<td width="${w}%" valign="top" style="padding:0 4px;">
    <table role="presentation" width="100%" cellpadding="0" cellspacing="0" border="0"
           style="background:#ffffff;border:1px solid #e4e9f0;border-radius:8px;">
        <tr>
            <td style="border-left:4px solid ${color};padding:12px 14px;">
                <div style="font-size:11px;color:#6b7a90;text-transform:uppercase;letter-spacing:.4px;">${label?html}</div>
                <div style="font-size:26px;font-weight:700;line-height:1.2;color:${color};margin-top:2px;">${num}</div>
                <div style="font-size:11px;color:#6b7a90;margin-top:2px;">${pct?html}</div>
            </td>
        </tr>
    </table>
</td>
</#macro>
<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8"/>
    <meta name="viewport" content="width=device-width, initial-scale=1"/>
    <title>${projectName?html}</title>
    <style>
        body { margin:0; padding:0; background:#f5f7fa; }
        body, td, th { font-family:-apple-system,"Segoe UI",Roboto,"Microsoft YaHei",Arial,sans-serif;
                       color:#1f2d3d; font-size:13px; line-height:1.55; }
        table { border-collapse:collapse; }
        .card { background:#ffffff; border:1px solid #e4e9f0; border-radius:10px; }
        .sec { padding:18px; }
        .th { background:#f8fafc; text-align:left; padding:9px 12px; font-size:11px; color:#64748b;
              text-transform:uppercase; letter-spacing:.4px; border-bottom:1px solid #e4e9f0; }
        .td { padding:10px 12px; border-bottom:1px solid #eef2f7; vertical-align:top; }
        .num { text-align:right; }
        .sub { color:#6b7a90; font-size:12px; }
        .name { font-weight:600; }
        .msg { font-family:Consolas,Menlo,monospace; font-size:11.5px; color:#b91c1c;
               word-break:break-all; margin-top:3px; }
        .foot { padding:16px 18px; font-size:11px; color:#94a3b8; text-align:center; }
        .env-sub { margin:2px 0 8px; font-size:11px; font-weight:700; letter-spacing:.06em;
                   text-transform:uppercase; color:#6b7a90; }
        .browser-legend { font-size:12px; color:#6b7a90; }
        .browser-legend b { color:#1f2d3d; }
    </style>
</head>
<body>
<table role="presentation" width="100%" cellpadding="0" cellspacing="0" border="0" bgcolor="#f5f7fa">
<tr>
<td align="center" style="padding:20px 8px;">

<#-- 品牌名与 "Test Report" 重复时不重复展示（旧版出现过 "Test Report — Test Report"） -->
<#assign brand = (projectName!"")?trim>
<#assign showBrand = brand?has_content && brand?lower_case != "test report">

<#-- 宽度 720 → 960：2026-10-22 用户反馈"整个页面打开太窄"，正文在宽屏浏览器/Outlook 预览里被压成窄条。
     960 是主流邮件客户端安全上限（Gmail 正文区 ~1024），仍留左右留白。 -->
<table role="presentation" width="960" cellpadding="0" cellspacing="0" border="0">

    <#-- 顶部横幅：品牌 + 运行概览 -->
    <tr>
        <td bgcolor="#1e3a8a" align="center" style="padding:26px 24px 18px;border-radius:10px 10px 0 0;">
            <#-- 报告名：显示配置的 project.report.name（未配置时 projectName 已在 Java 侧回退 "Test Report"）。
                 居中 + 放大；配置了名字就不再显示 "Test Report"（2026-10-22 用户反馈：
                 原名太小、没居中，且配置了 name 仍显示 Test Report）。 -->
            <div style="font-size:28px;font-weight:700;color:#ffffff;letter-spacing:.2px;">${projectName?html}</div>
            <div style="font-size:12px;color:#c7d2fe;margin-top:8px;">
                <#-- generatedAt 已自带 "UTC" 后缀（见 EmailReportRenderer.STAMP），此处不再重复拼接 -->
                ${count?c} scenario<#if count != 1>s</#if> &middot; ${featureCount?c} feature<#if featureCount != 1>s</#if> &middot; ${totalDurationText?html} &middot; ${generatedAt?html}
            </div>
        </td>
    </tr>
    <#-- 结果分段条紧贴横幅底部（替代 v2 的堆叠条 / 环形图） -->
    <tr>
        <td bgcolor="#1e3a8a" style="padding:0 24px 0;font-size:0;line-height:0;">
            <table role="presentation" width="100%" cellpadding="0" cellspacing="0" border="0" bgcolor="#1e3a8a">
                <tr>
                    <#if (passPct > 0)><td width="${passPct?c}%" bgcolor="#22c55e" style="height:5px;font-size:0;line-height:5px;">&nbsp;</td></#if>
                    <#if (failPct > 0)><td width="${failPct?c}%" bgcolor="#ef4444" style="height:5px;font-size:0;line-height:5px;">&nbsp;</td></#if>
                    <#if (errorPct > 0)><td width="${errorPct?c}%" bgcolor="#f97316" style="height:5px;font-size:0;line-height:5px;">&nbsp;</td></#if>
                    <#if (pendingPct > 0)><td width="${pendingPct?c}%" bgcolor="#f59e0b" style="height:5px;font-size:0;line-height:5px;">&nbsp;</td></#if>
                    <#if (skippedPct > 0)><td width="${skippedPct?c}%" bgcolor="#94a3b8" style="height:5px;font-size:0;line-height:5px;">&nbsp;</td></#if>
                    <#if count == 0><td bgcolor="#94a3b8" style="height:5px;font-size:0;line-height:5px;">&nbsp;</td></#if>
                </tr>
            </table>
        </td>
    </tr>
    <tr><td height="14" style="font-size:0;line-height:14px;">&nbsp;</td></tr>

    <#if !hasFailures>
    <tr>
        <td bgcolor="#f0fdf4" style="border:1px solid #bbf7d0;border-radius:8px;padding:14px 18px;color:#15803d;font-weight:600;">
            <span style="font-size:15px;margin-right:6px;color:#16a34a;">&#10004;</span>All scenarios passed — no failures in this run.
        </td>
    </tr>
    <tr><td height="14" style="font-size:0;line-height:14px;">&nbsp;</td></tr>
    </#if>

    <#-- KPI 行（6 卡，2026-10-22 用户要求"feature 的统计增加到顶部卡片"：新增 Features 卡；
         Functional Coverage 整节随之删除，feature 统计只在顶部展示一次） -->
    <tr>
        <td style="padding:0 0 14px;">
            <table role="presentation" width="100%" cellpadding="0" cellspacing="0" border="0">
                <tr>
                    <@kpiCell label="Features" num=featureCount?c pct="executed in this run" color="#3b82f6" w="16.66"/>
                    <@kpiCell label="Scenarios" num=count?c pct=("across " + featureCount?c + (featureCount == 1)?then(" feature", " features")) color="#3b82f6" w="16.66"/>
                    <@kpiCell label="Passed" num=passCount?c pct=passPct?c + "%" color="#16a34a" w="16.66"/>
                    <@kpiCell label="Failed" num=failCount?c pct=failPct?c + "%" color="#dc2626" w="16.66"/>
                    <@kpiCell label="Error" num=errorCount?c pct=errorPct?c + "%" color="#ea580c" w="16.66"/>
                    <@kpiCell label="Pending / Skipped" num=(pendingCount + skippedCount)?c pct=(pendingPct + skippedPct)?c + "%" color="#d97706" w="16.66"/>
                </tr>
            </table>
        </td>
    </tr>

    <#-- Summary -->
    <tr>
        <td class="card">
            <@secHead "Summary"/>
            <div class="sec">
                <table role="presentation" width="100%" cellpadding="0" cellspacing="0" border="0" bgcolor="#e4e9f0">
                    <tr>
                        <#if (passPct > 0)><td width="${passPct?c}%" bgcolor="#16a34a" style="height:14px;font-size:0;line-height:14px;">&nbsp;</td></#if>
                        <#if (failPct > 0)><td width="${failPct?c}%" bgcolor="#dc2626" style="height:14px;font-size:0;line-height:14px;">&nbsp;</td></#if>
                        <#if (errorPct > 0)><td width="${errorPct?c}%" bgcolor="#ea580c" style="height:14px;font-size:0;line-height:14px;">&nbsp;</td></#if>
                        <#if (pendingPct > 0)><td width="${pendingPct?c}%" bgcolor="#d97706" style="height:14px;font-size:0;line-height:14px;">&nbsp;</td></#if>
                        <#if (skippedPct > 0)><td width="${skippedPct?c}%" bgcolor="#94a3b8" style="height:14px;font-size:0;line-height:14px;">&nbsp;</td></#if>
                        <#if count == 0><td style="height:14px;font-size:0;line-height:14px;">&nbsp;</td></#if>
                    </tr>
                </table>

                <#-- 图例恢复单行 5 列：2026-10-22 曾把 Environment 加到右侧成两列，用户反馈
                     "summary 这块不是很好看"，故环境信息整块从邮件去除（站点报告仍保留）。 -->
                <table role="presentation" width="100%" cellpadding="0" cellspacing="0" border="0" style="margin-top:10px;line-height:1.4;">
                    <tr>
                        <td style="padding:0 4px 0 0;font-size:12px;color:#1f2d3d;"><span style="display:inline-block;width:8px;height:8px;border-radius:50%;background:#16a34a;margin-right:5px;"></span>Passed <b>${passCount}</b> <span style="color:#6b7a90;">${passPct?c}%</span></td>
                        <td style="padding:0 4px;font-size:12px;color:#1f2d3d;"><span style="display:inline-block;width:8px;height:8px;border-radius:50%;background:#dc2626;margin-right:5px;"></span>Failed <b>${failCount}</b> <span style="color:#6b7a90;">${failPct?c}%</span></td>
                        <td style="padding:0 4px;font-size:12px;color:#1f2d3d;"><span style="display:inline-block;width:8px;height:8px;border-radius:50%;background:#ea580c;margin-right:5px;"></span>Error <b>${errorCount}</b> <span style="color:#6b7a90;">${errorPct?c}%</span></td>
                        <td style="padding:0 4px;font-size:12px;color:#1f2d3d;"><span style="display:inline-block;width:8px;height:8px;border-radius:50%;background:#d97706;margin-right:5px;"></span>Pending <b>${pendingCount}</b> <span style="color:#6b7a90;">${pendingPct?c}%</span></td>
                        <td style="padding:0 0 0 4px;font-size:12px;color:#1f2d3d;"><span style="display:inline-block;width:8px;height:8px;border-radius:50%;background:#94a3b8;margin-right:5px;"></span>Skipped <b>${skippedCount}</b> <span style="color:#6b7a90;">${skippedPct?c}%</span></td>
                    </tr>
                </table>

                <#-- 运行时间（2026-10-22 用户要求"顶部除了 report name，显示 generate 的时间、
                     从什么时间开始测试、什么时间结尾"）：替代原来只有 "executed on <generatedAt>" 的单行。 -->
                <div class="sub" style="margin-top:12px;">
                    ${count?c} tests executed &middot; Generated at <b>${run.generatedAt?html}</b>
                    &middot; Started <b>${run.startedAt?html}</b> &middot; Ended <b>${run.endedAt?html}</b>
                </div>

                <#-- 耗时口径：5 格指标条（标签小字 + 数值加粗、格间细分隔线）。
                     2026-10-22 用户反馈"时间这块不好看"——原为一行深浅不一的粗体文本，
                     与上方图例、下方按钮都不成体系；改为卡内统一的分隔条样式，仍只占一行高度。 -->
                <table role="presentation" width="100%" cellpadding="0" cellspacing="0" border="0"
                       style="margin-top:12px;background:#f8fafc;border:1px solid #e8eef6;border-radius:8px;">
                    <tr>
                        <td width="20%" valign="top" style="padding:8px 12px;border-right:1px solid #e8eef6;">
                            <div style="font-size:11px;font-weight:700;color:#475569;text-transform:uppercase;letter-spacing:.5px;">Total</div>
                            <div style="font-size:15px;font-weight:700;color:#1f2d3d;">${timings.total?html}</div>
                        </td>
                        <td width="20%" valign="top" style="padding:8px 12px;border-right:1px solid #e8eef6;">
                            <div style="font-size:11px;font-weight:700;color:#475569;text-transform:uppercase;letter-spacing:.5px;">Clock</div>
                            <div style="font-size:15px;font-weight:700;color:#1f2d3d;">${timings.clock?html}</div>
                        </td>
                        <td width="20%" valign="top" style="padding:8px 12px;border-right:1px solid #e8eef6;">
                            <div style="font-size:11px;font-weight:700;color:#475569;text-transform:uppercase;letter-spacing:.5px;">Average</div>
                            <div style="font-size:15px;font-weight:700;color:#1f2d3d;">${timings.avg?html}</div>
                        </td>
                        <td width="20%" valign="top" style="padding:8px 12px;border-right:1px solid #e8eef6;">
                            <div style="font-size:11px;font-weight:700;color:#475569;text-transform:uppercase;letter-spacing:.5px;">Max</div>
                            <div style="font-size:15px;font-weight:700;color:#1f2d3d;">${timings.max?html}</div>
                        </td>
                        <td width="20%" valign="top" style="padding:8px 12px;">
                            <div style="font-size:11px;font-weight:700;color:#475569;text-transform:uppercase;letter-spacing:.5px;">Min</div>
                            <div style="font-size:15px;font-weight:700;color:#1f2d3d;">${timings.min?html}</div>
                        </td>
                    </tr>
                </table>
            </div>
        </td>
    </tr>
    <tr><td height="14" style="font-size:0;line-height:14px;">&nbsp;</td></tr>

    <#-- 查看 / 下载（table 布局，非 flex） -->
    <tr>
        <td style="padding:0 2px;">
            <table role="presentation" cellpadding="0" cellspacing="0" border="0"><tr>
                <td style="padding-right:10px;">
                    <a href="${links.fullReport}" target="_blank"
                       style="display:inline-block;background:#2563eb;color:#ffffff;font-weight:600;font-size:13px;padding:11px 22px;border-radius:999px;text-decoration:none;">View full report &rarr;</a>
                </td>
                <td>
                    <a href="${links.csv}" target="_blank"
                       style="display:inline-block;background:#ffffff;color:#334155;border:1px solid #cbd5e1;font-weight:600;font-size:13px;padding:10px 20px;border-radius:999px;text-decoration:none;">Download CSV</a>
                </td>
            </tr></table>
        </td>
    </tr>
    <tr><td height="14" style="font-size:0;line-height:14px;">&nbsp;</td></tr>

    <#-- Functional Coverage 整节删除（2026-10-22 用户决定）：feature 统计已进顶部 KPI 卡
         （Features 卡 + Scenarios 卡 "across N features"），再保留整表属重复信息。 -->

    <#-- Test Failure Overview（对齐 Serenity 2.0.84 home.ftl：左 Most Frequent Failures 次数 / 右 Most Unstable Features 百分比） -->
    <tr>
        <td class="card">
            <@secHead "Test Failure Overview"/>
            <div class="sec">
                <table role="presentation" width="100%" cellpadding="0" cellspacing="0" border="0"><tr>
                    <td width="50%" valign="top" style="padding-right:10px;">
                        <#-- 按「失败类型」聚合（report.error.types 可配置分类规则），不再是整段消息片段 -->
                        <div class="env-sub">Most Frequent Failure Types</div>
                        <#if frequentFailures?has_content>
                        <table role="presentation" width="100%" cellpadding="0" cellspacing="0" border="0">
                            <#list frequentFailures as f>
                            <tr>
                                <td class="td" style="border-bottom:1px solid #eef2f7;padding-left:0;">${f.name?html}</td>
                                <td class="td num" width="34" style="border-bottom:1px solid #eef2f7;padding-right:0;">
                                    <span style="display:inline-block;min-width:20px;padding:1px 7px;border-radius:999px;background:#fef2f2;color:#b91c1c;font-weight:700;font-size:11px;">${f.count}</span>
                                </td>
                            </tr>
                            </#list>
                        </table>
                        <#else><div class="sub">None.</div></#if>
                    </td>
                    <td width="50%" valign="top">
                        <div class="env-sub">Most Unstable Features</div>
                        <#if unstableFeatures?has_content>
                        <table role="presentation" width="100%" cellpadding="0" cellspacing="0" border="0">
                            <#list unstableFeatures as u>
                            <tr>
                                <td class="td" style="border-bottom:1px solid #eef2f7;padding-left:0;">${u.name?html}</td>
                                <td class="td num" width="44" style="border-bottom:1px solid #eef2f7;padding-right:0;">
                                    <span style="display:inline-block;min-width:20px;padding:1px 7px;border-radius:999px;background:#fef2f2;color:#b91c1c;font-weight:700;font-size:11px;">${u.failurePercentage}%</span>
                                </td>
                            </tr>
                            </#list>
                        </table>
                        <#else><div class="sub">None.</div></#if>
                    </td>
                </tr></table>
            </div>
        </td>
    </tr>
    <tr><td height="14" style="font-size:0;line-height:14px;">&nbsp;</td></tr>

    <#-- Full Failure List（按 feature 分组） -->
    <#if hasFailures>
    <tr>
        <td class="card">
            <@secHead "Full Failure List"/>
            <div class="sec">
                <table role="presentation" width="100%" cellpadding="0" cellspacing="0" border="0">
                    <tr><th class="th" width="46%" style="padding-left:0;">Requirement</th><th class="th" style="padding-right:0;">Failure</th></tr>
                    <#list failureGroups as g>
                    <tr>
                        <td colspan="2" style="padding:9px 0 7px;border-bottom:1px solid #e4e9f0;font-weight:700;font-size:12px;color:#334155;background:#f8fafc;">
                            &nbsp;${g.feature?html}
                        </td>
                    </tr>
                    <#list g.scenarios as s>
                    <tr>
                        <td class="td name" style="padding-left:0;">
                            <a href="${s.link}" style="color:#2563eb;text-decoration:none;">${s.name?html}</a>
                        </td>
                        <td class="td" style="padding-right:0;">
                            <span style="display:inline-block;padding:2px 9px;border-radius:999px;font-size:11px;font-weight:700;color:#ffffff;background:${badgeBg(s.resultLabel)};">${s.resultLabel?html}</span>
                            <#if s.failureTypeLabel?? && s.failureTypeLabel?trim?has_content>
                            <span style="display:inline-block;padding:2px 9px;border-radius:999px;font-size:11px;font-weight:600;color:#ffffff;background:${typeBg(s.failureTypeLabel)};margin-left:6px;">${s.failureTypeLabel?html}</span>
                            </#if>
                            <div class="msg">${s.message?html}</div>
                        </td>
                    </tr>
                    </#list>
                    </#list>
                </table>
            </div>
        </td>
    </tr>
    <tr><td height="14" style="font-size:0;line-height:14px;">&nbsp;</td></tr>
    </#if>

    <#-- Full Test Results（按 feature 分组） -->
    <tr>
        <td class="card">
            <@secHead "Full Test Results"/>
            <div class="sec">
                <table role="presentation" width="100%" cellpadding="0" cellspacing="0" border="0">
                    <tr>
                        <th class="th" width="52%" style="padding-left:0;">Requirement</th>
                        <th class="th" width="110">Result</th>
                        <th class="th num" width="86" style="padding-right:0;">This run</th>
                    </tr>
                    <#list groups as g>
                    <tr>
                        <td colspan="3" style="padding:9px 0 7px;border-bottom:1px solid #e4e9f0;font-weight:700;font-size:12px;color:#334155;background:#f8fafc;">
                            &nbsp;${g.feature?html}
                        </td>
                    </tr>
                    <#list g.scenarios as s>
                    <tr>
                        <td class="td" style="padding-left:0;">
                            <a href="${s.link}" style="color:#2563eb;text-decoration:none;">${s.name?html}</a>
                        </td>
                        <td class="td">
                            <span style="display:inline-block;padding:2px 9px;border-radius:999px;font-size:11px;font-weight:700;color:#ffffff;background:${badgeBg(s.resultLabel)};">${s.resultLabel?html}</span>
                        </td>
                        <td class="td num sub" style="padding-right:0;">${s.durationText?html}</td>
                    </tr>
                    </#list>
                    </#list>
                </table>
            </div>
        </td>
    </tr>
    <tr><td height="14" style="font-size:0;line-height:14px;">&nbsp;</td></tr>

    <#-- Environment 整节删除（2026-10-22 用户决定"email report 去除环境信息这一块"）：
         环境信息只保留在站点报告（index 的 Statistics 第四列）。 -->

    <#-- 页脚 -->
    <tr>
        <td class="card">
            <div class="foot">
                ${projectName?html} &middot; reportVersion ${reportVersion} &middot; duration ${totalDurationText?html}<br/>
                <#if ci?? && ci.present && ci.url??>
                <a href="${ci.url?html}" style="color:#2563eb;text-decoration:none;">View build in ${ci.ciName?html}<#if ci.job??> &middot; ${ci.job?html}<#if ci.number??> #${ci.number?html}</#if></#if></a><br/>
                </#if>
                Self-contained single file (email safe).
            </div>
        </td>
    </tr>

</table>

</td>
</tr>
</table>
</body>
</html>
