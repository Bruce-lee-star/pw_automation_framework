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
<#import "macros.ftl" as m/>
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
<#-- 单行渲染（步骤级分组面板与底部 Console log 共用）：
     ① 行首缩进 → 多值参数续行；② "Key: value" → 键值两列（值为 <none> 整行丢弃）；
     ③ 其余 → 原始等宽行。判据收紧到"首字母开头 + 冒号后必须有空白"，避免把 https:// 与
        时间戳 12:34:56 误判成键值。这样底部 Console log 虽是"整个运行日志"的单块，
        RestAssured 请求/响应转储仍是键值排版，不会糊成一片。 -->
<#macro consoleLine line>
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
</#macro>

<#macro consolePanel groups open=false>
  <#list groups as g>
    <details class="con-group con-${g.tone}"<#if open> open</#if>>
      <summary>${g.label?html}<span class="con-count">${g.lines?size}</span></summary>
      <ul class="con-lines">
        <#list g.lines as line><@consoleLine line/></#list>
      </ul>
    </details>
  </#list>
</#macro>

<#-- 状态徽标配色：2xx/3xx 绿、4xx/5xx 与 FAILED 红、未知中性 -->
<#function statusClass s>
  <#if s == "FAILED"><#return "bad"></#if>
  <#if s?starts_with("2") || s?starts_with("3")><#return "ok"></#if>
  <#if s?starts_with("4") || s?starts_with("5")><#return "bad"></#if>
  <#return "na">
</#function>
<#-- 单个 API 调用卡片（数据为 Java 侧解析好的 ApiCallBlock：method/url/status/elapsed/rows）。
     渲染成结构化卡片而不是一整段 pre——方法要有自己的徽标、URL/状态/耗时单独一栏、
     Request/Response 分区明确（2026-10-20 用户反馈"没有区分 request method、
     request headers 等信息"）。解析逻辑在 Java（ApiCallBlock），模板只取值展示。 -->
<#macro apiCard call seq=0>
  <div class="api-card">
    <div class="api-card-head">
      <#-- 序号：Route Captured 一次抓多条，带编号才能一眼区分（2026-10-22 用户要求"明显区分"） -->
      <#if seq gt 0><span class="api-seq">${seq}</span></#if>
      <#-- 能力标签：MONITOR / MOCK / DELAY / MODIFY / CAPTURED（API Request 无此值，隐藏） -->
      <#if call.kind?has_content><span class="api-kind">${call.kind?html}</span></#if>
      <#if call.method?has_content>
        <span class="api-method api-m-${call.method?lower_case}">${call.method?html}</span>
      </#if>
      <span class="api-url">${call.url?html}</span>
      <#if call.status?has_content>
        <span class="api-status api-s-${statusClass(call.status)}">${call.status?html}</span>
      </#if>
      <#if call.elapsed?has_content>
        <span class="api-ms">${call.elapsed?html}</span>
      </#if>
    </div>
    <#list call.rows as row>
      <#-- 值为 JSON（对象/数组）时单独用 <pre class="api-json"> 展示：
           保留 2 空格缩进与换行、独立滚动，避免被键值两列的窄列挤压成"一坨文本"
           （2026-10-21 用户反馈：request/response body 要按 JSON 格式展示）。 -->
      <div class="api-row<#if row.json??> is-json</#if>">
        <span class="api-label">${row.label?html}<#if row.json??><span class="api-tag">JSON</span></#if></span>
        <#if row.json??>
          <pre class="api-json">${row.value?html}</pre>
        <#else>
          <span class="api-value">${row.value?html}</span>
        </#if>
      </div>
    </#list>
  </div>
</#macro>
<#macro stepTree steps>
    <#list steps as step>
        <li class="st-${sclass(step.result)}">
            <span class="node"></span>
            <#-- 步骤行 = 可折叠明细 + 截图，两者是<b>同级兄弟</b>（2026-10-22 用户要求：
                 "截图和步骤应该是同级关系，不在 step 下面"）。截图放在 <details> <b>之外</b>，
                 折叠/展开步骤都不影响截图可见；flex 让明细占满剩余宽度、截图定宽靠右。 -->
            <div class="step-line">
            <#-- 步骤可折叠：折叠时连同 Route Register / Route Captured / API Request
                 一起隐藏（用户要求"步骤折叠不显示这些，展开再显示"）。
                 summary 即原来的 step-row（时间线节点与标题行）。 -->
            <#-- 默认折叠（2026-10-21 用户要求）：步骤行本身即摘要，明细按需展开，
                 避免一进页面就被多个 Route/API 面板铺满。
                 例外：失败步骤默认展开——失败原因应随步骤内联可见，而不是只看到一条红色摘要、
                 要点开才知道为什么失败（2026-10-22 用户反馈"失败信息独立显示"）。 -->
            <details class="step-details"<#if sclass(step.result) == "FAILURE"> open</#if>>
                <summary class="step-row">
                    <span class="step-desc">${step.description?html}</span>
                    <#-- 时长以<b>秒</b>结算（保留 2 位小数，去掉无意义的 .00）：
                         毫秒在步骤行上噪音大、且不易读（2026-10-22 用户要求）。 -->
                    <span class="step-time"><#if step.duration??>${((step.duration!0) / 1000)?string["0.##"]}s</#if></span>
                </summary>
                <div class="step-body">
            <#if step.dataTable?has_content>
                <table class="tbl step-table">
                    <#list step.dataTable as row>
                        <tr><#list row as cell><td>${cell?html}</td></#list></tr>
                    </#list>
                </table>
            </#if>
            <#if step.docString??><pre class="att-text step-docstring">${step.docString?html}</pre></#if>
            <#-- 步骤内 API 信息区（顺序：Route Register → API Request → Route Captured → 失败信息）。
                 2026-10-20 用户反馈：API 请求信息要在 error 上方——先看清"发了什么、抓到什么"，
                 再看失败原因，排查顺序才顺。 -->
            <#-- Route Register（可伸缩）：注册发生在哪个步骤就附在哪个步骤下（用例级仅作无步骤上下文时的兜底）。
                 注册日志是原文行，保留 con-lines + consoleLine 排版；Route Captured 已升级为
                 与 API Request 同构的卡片（2026-10-22 用户要求两者显示一致）。 -->
            <#if step.routeRegistrations?has_content>
                <#-- 默认折叠（2026-10-20 用户要求）：步骤树保持紧凑，需要时再展开 -->
                <details class="con-group con-register step-route">
                    <summary>Route Register<span class="con-count">${step.routeRegistrations?size}</span></summary>
                    <ul class="con-lines"><#list step.routeRegistrations as line><@consoleLine line/></#list></ul>
                </details>
            </#if>
            <#-- API Request（可伸缩）：本步骤内业务经 RestAssured 主动发出的 API 请求。
                 每条为多行结构化块（请求行 + 请求头/体 + 响应状态/头/体预览），
                 故用 <pre class="api-block"> 保留换行——单行摘要看不到请求/响应明细
                 （2026-10-20 用户反馈"展开没有具体的 api 请求响应信息"）。
                 与 Route Captured 的分界：前者是"我们请求别人"，后者是"页面自己发出的调用被我们抓到"。 -->
            <#if step.stepId?? && restApi?? && restApi[step.stepId]??>
                <details class="con-group con-net step-api">
                    <summary>API Request<span class="con-count">${restApi[step.stepId]?size}</span></summary>
                    <#list restApi[step.stepId] as block><@apiCard block/></#list>
                </details>
            </#if>
            <#-- Route Captured（可伸缩）：本步骤内 route 抓包得到的<b>页面真实调用</b>
                 （monitor / mock / delay / capture），归位到发生它的步骤。
                 注册日志见上方 Route Register（不重复）；普通 stdout/stderr 汇入页面底部 Console log。 -->
            <#if step.stepId?? && capturedApi?? && capturedApi[step.stepId]??>
                <#-- 与 Route Register 用<b>不同色调</b>（青绿 vs 紫罗兰）：同一行摘要里一眼区分
                     "我们注册了什么" 与 "页面实际被抓到什么"（2026-10-21 用户反馈两者观感一致）。 -->
                <details class="con-group con-captured step-captured">
                    <summary>Route Captured<span class="con-count">${capturedApi[step.stepId]?size}</span></summary>
                    <#-- 与 API Request 同一套卡片（方法 + URL + 状态 + Request/Response 分区），
                         每条之间有明显间隔与序号，不再挤在一段等宽文本里（2026-10-22 用户反馈）。 -->
                    <div class="con-cards">
                        <#list capturedApi[step.stepId] as block><@apiCard call=block seq=(block_index + 1)/></#list>
                    </div>
                </details>
            </#if>
            <#if step.exception??>
                <#-- 失败信息只挂在失败步骤下（不再额外渲染页面级错误卡，避免重复）。
                     2026-10-20 重设计：异常类型做成徽标、消息用正文排版（不再整段红字等宽），
                     堆栈收进与 Console 面板同风格的折叠区，长 URL 自动折行。 -->
                <div class="failure-panel">
                    <div class="failure-head">
                        <span class="failure-icon" aria-hidden="true">✕</span>
                        <div class="failure-body">
                            <span class="failure-type">${(step.exception.errorType!"Error")?html}</span>
                            <#-- 失败消息/堆栈同样要脱敏：业务异常消息常内联完整 URL（含 key/token），
                                 不过 ScrubGate 就等于把敏感值写进报告（2026-10-20 用户反馈）。
                                 消息按 " | " 拆成多行（框架元素异常的三段式：原因 / page+title+obstruction / cause），
                                 整段糊一行既看不出重点也没法扫读（2026-10-22 用户反馈"失败展示不友好"）。 -->
                            <div class="failure-msg"><#list failureLines(step.exception.message!"") as seg>
                              <div class="failure-seg<#if seg?starts_with("cause=")> is-cause</#if><#if seg?starts_with("page=")> is-page</#if>">${seg?html}</div>
                            </#list></div>
                        </div>
                    </div>
                    <#-- 堆栈结构化展示（2026-10-23 优化）：原先把整段堆栈塞进一个 <pre>，
                         几十行等宽红字糊成一片，看不出"我们自己的代码在哪一行炸的"。
                         改为逐行渲染并分类：异常头 / Caused by 根因加重，应用帧（com.hsbc…）加重，
                         三方库帧（playwright/java/junit…）弱化，行尾 "… N more" 折为灰斜体；
                         summary 显示行数（有 classname 时一并显示），并提供 Copy 按钮贴工单。 -->
                    <#if outcome.testFailureSummary??>
                        <#assign stackText = scrub(outcome.testFailureSummary)?replace("\r", "")>
                        <#assign stackLines = stackText?split("\n")>
                        <details class="failure-stack">
                            <summary>
                                <span class="stack-title">Stack trace</span>
                                <#if outcome.testFailureClassname??>
                                    <span class="stack-class">${outcome.testFailureClassname?html}</span>
                                </#if>
                                <span class="stack-count">${stackLines?size} lines</span>
                                <button type="button" class="stack-copy" title="Copy stack trace">Copy</button>
                            </summary>
                            <div class="stack-body">
                                <#list stackLines as raw>
                                    <#assign t = raw?trim>
                                    <#assign cls = "stack-line">
                                    <#if t?starts_with("Caused by:")>
                                        <#assign cls = cls + " is-cause">
                                    <#elseif t?starts_with("Suppressed:")>
                                        <#assign cls = cls + " is-suppressed">
                                    <#elseif t?starts_with("at ")>
                                        <#if t?contains("com.hsbc.cmb.hk.dbb.automation")>
                                            <#assign cls = cls + " is-app">
                                        <#else>
                                            <#assign cls = cls + " is-lib">
                                        </#if>
                                    <#elseif t?starts_with("...")>
                                        <#assign cls = cls + " is-more">
                                    <#elseif t?has_content>
                                        <#assign cls = cls + " is-ex">
                                    <#else>
                                        <#assign cls = cls + " is-empty">
                                    </#if>
                                    <div class="${cls}">${raw?html}</div>
                                </#list>
                            </div>
                        </details>
                    </#if>
                </div>
            </#if>
            <#-- Evidence 区已移除：抓取到的 API 改由结构化 apiOperations 逐条展示（见上方 API Request）。 -->
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
            <#if step.children?has_content>
                <ul class="steps"><@stepTree step.children/></ul>
            </#if>
                </div>
            </details>
            <#if step.screenshot??>
                <#-- 截图与步骤明细同级：定宽靠右。
                     点击放大到全屏、再点一下恢复（2026-10-22 用户要求"点击放大，再点击恢复原来的"）；
                     不再跳转底部 Screenshots 标签——那里现在同样支持点击放大，语义重复。 -->
                <div class="shot">
                    <img class="shot-zoom" src="screenshots/${step.screenshot}"
                         alt="Step screenshot" title="Click to enlarge · click again to restore"/>
                </div>
            </#if>
            </div>
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
    <#-- 页面位于 per-case 子目录（<runDir>/<outcomeId>/），assets 在报告根：本地需回退一级；
         托管（baseHref 存在）时 <base> 已把相对链接锚定到报告根，保持原样即可。 -->
    <#if baseHref?? && baseHref?has_content>
    <link rel="icon" type="image/svg+xml" href="assets/favicon.svg"/>
    <link rel="stylesheet" href="assets/report-v2.css"/>
    <#else>
    <link rel="icon" type="image/svg+xml" href="../assets/favicon.svg"/>
    <link rel="stylesheet" href="../assets/report-v2.css"/>
    </#if>
    <style>
        /* 新设计 CSS 未覆盖的局部样式（证据 / 附件 / 截图 / 未定义结果） */
        .evidence { margin: 6px 0; padding-left: 20px; font-family: ui-monospace, SFMono-Regular, Consolas, monospace; font-size: 12px; }
        .attachment { margin: 6px 0; }
        .attachment > summary { cursor: pointer; color: var(--text-2); font-size: 12px; }
        .att-text, .att-html { background: #f8fafc; border: 1px solid var(--border); border-radius: 6px; padding: 8px; font-size: 12px; overflow-x: auto; }
        /* 截图与步骤明细同级（flex 兄弟）：明细占满剩余宽度、截图定宽，折叠步骤时截图仍可见 */
        .step-line { display:flex; align-items:flex-start; gap:14px; }
        .step-line > .step-details { flex:1 1 auto; min-width:0; }
        /* margin-left:auto —— 截图整体靠右（2026-10-22 用户要求"图片向右移动一点"）；
           去掉 overflow:hidden —— 原 hover 放大被它裁成局部，用户看不到整页（2026-10-22 反馈） */
        .shot { flex:0 0 300px; width:300px; margin:0 0 0 auto; position:relative; }
        .shot img {
            display:block; width:100%; max-width:300px; border:1px solid var(--border); border-radius:6px;
            cursor: zoom-in;
        }
        .shot a { display:block; }
        /* 失败步骤与成功步骤统一布局（2026-10-22 反馈"失败的和成功的没有对齐"）：
           此前失败步骤用 flex-wrap 把截图换行到明细下方并放大到 560px，于是同一「Execution steps」
           列表里出现「成功步骤截图在右上、失败步骤截图掉到下方」的错位。
           现在统一为「明细占满左侧 + 截图固定右列、顶部对齐」：成功/失败截图同列、同顶线、同尺寸，
           失败面板仍完整占据左侧可读宽度（.failure-body min-width:0 + .failure-msg overflow-wrap:anywhere
           已在 assets/report-v2.css 保证长 URL / 堆栈自动折行，不会横向溢出）。 */
        /* 点击放大（2026-10-22 用户要求"点击放大，再点击恢复原来的"）：
           点截图 → 铺满视口的完整预览；再点一下（或按 Esc）→ 恢复原状。
           指针事件必须可用（原 hover 方案是 pointer-events:none，点了没反应）。 */
        #shot-hover { position:fixed; inset:0; z-index:999; display:none; align-items:center; justify-content:center;
                      background:rgba(15,23,42,.82); pointer-events:auto; cursor:zoom-out; }
        #shot-hover.on { display:flex; }
        #shot-hover img { max-width:92vw; max-height:90vh; object-fit:contain; background:#fff;
                          border-radius:8px; box-shadow:0 20px 60px rgba(0,0,0,.5); cursor:zoom-out; }
        /* 放大态提示：告诉用户"再点一下就回去" */
        #shot-hover .zoom-tip { position:absolute; top:18px; left:50%; transform:translateX(-50%);
                                background:rgba(15,23,42,.78); color:#fff; font-size:12px;
                                padding:4px 12px; border-radius:999px; pointer-events:none; }
        /* 底部 Screenshots 画廊：大图 + 一行序号按钮（2026-10-22 用户要求去掉下方缩略图小图条，
           序号（纯数字）直接显示在图片底部，点图片下方的序号按钮或大图左右箭头切换对应步骤截图）。
           大图可点击放大（cursor:zoom-in + JS 切到 #shot-hover 全屏预览）。 */
        .shot-gallery { padding: 14px; }
        /* 2026-10-23 用户反馈「图片两边黑边占据太宽」：舞台原先是整行宽的深色块（block），
           而截多为竖长的整页图，被 max-height 限高后左右只剩大片深色空档，图片被挤在中间显得很小。
           改为「舞台包住图片」：width:fit-content + margin:0 auto，深色底只比图片多 10px padding，
           左右切图箭头也随之贴到图片两侧（不再飘在远端两颗孤立按钮）。
           2026-10-23 二次反馈「期望 height 自适应」：Playwright 整页截图是长图，故移除 max-height
           硬限高，改为 width 限 100% + height:auto——图片按原始比例自适应高度（长图就长，短图就短），
           舞台随之贴高，看长图时页面自然纵向滚动，序号按钮行落在该截图之后。 */
        .shot-viewer { position:relative; width:fit-content; max-width:100%; margin:0 auto; text-align:center;
                       background:#0f172a; border-radius:8px; padding:10px; }
        .shot-viewer img { display:block; width:auto; height:auto; max-width:100%; border-radius:4px; cursor:zoom-in; }
        /* 大图底部的当前步骤序号（与序号按钮同一套视觉，字号放大） */
        .shot-num-big { position:absolute; left:50%; bottom:16px; transform:translateX(-50%);
                        background:rgba(15,23,42,.78); color:#fff; font-size:15px; font-weight:600;
                        padding:2px 10px; border-radius:6px; pointer-events:none; }
        /* 序号按钮行：点哪个序号显示哪个步骤的截图（不再放缩略图） */
        .shot-picker { display:flex; flex-wrap:wrap; justify-content:center; gap:8px; margin-top:12px; }
        .shot-pick { min-width:34px; padding:4px 6px; border:1px solid #cbd5e1; border-radius:6px;
                     background:#fff; cursor:pointer; line-height:1; }
        .shot-pick:hover { border-color:#2563eb; background:#eff6ff; }
        .shot-pick.is-active { border-color:#2563eb; background:#2563eb; }
        .shot-pick.is-active .shot-pick-num { color:#fff; }
        .shot-pick-num { font-size:14px; color:#334155; font-weight:600; }
        /* 序号按钮的键盘焦点必须可见（2026-10-22 用户特别提醒"注意序号的 focus"）：
           鼠标点击不显示描边，Tab 聚焦时给 2px 高对比描边 + 外扩，避免被 is-active 底色吞掉。 */
        .shot-pick:focus-visible { outline:2px solid #60a5fa; outline-offset:2px; }
        /* 大图左右切图按钮：叠在图片两侧、垂直居中；首/末张 disabled 降透明度 */
        .shot-nav { position:absolute; top:50%; transform:translateY(-50%); z-index:2;
                    width:38px; height:38px; padding:0; display:flex; align-items:center; justify-content:center;
                    border-radius:999px; border:1px solid rgba(255,255,255,.45);
                    background:rgba(15,23,42,.55); color:#fff; cursor:pointer;
                    transition:background .12s,border-color .12s,opacity .12s; }
        .shot-nav:hover { background:rgba(37,99,235,.9); border-color:rgba(96,165,250,.9); }
        .shot-nav:focus-visible { outline:2px solid #60a5fa; outline-offset:2px; }
        .shot-nav[disabled] { opacity:.3; cursor:default; }
        .shot-nav[disabled]:hover { background:rgba(15,23,42,.55); border-color:rgba(255,255,255,.45); }
        .shot-prev { left:12px; }
        .shot-next { right:12px; }
        @media (max-width:1020px) {
            .step-line { flex-direction:column; }
            .shot { flex:0 0 auto; width:100%; }
            .shot img { max-width:100%; }
        }
        .step-actions { margin-top: 4px; }
        .b-UNDEFINED { background: var(--skipped); }
        .step-table { margin: 8px 0 2px; font-size: 12px; }
        .step-table td { padding: 4px 10px; border-bottom: 1px solid var(--border); }
        .step-table tr:first-child td { font-weight: 600; background: #f8fafc; }
        .step-docstring { margin: 8px 0 2px; white-space: pre-wrap; }
        /* 失败用例操作入口（上传补图 / 标记通过） */
        .case-actions .action-row { display: flex; flex-wrap: wrap; gap: 8px; margin-top: 4px; }
        .btn-action { padding: 8px 14px; border: 1px solid var(--border); border-radius: 6px; background: #fff; cursor: pointer; font-size: 13px; transition: background .15s ease; }
        .btn-action:hover { background: #f1f5f9; }
        .btn-ok { color: #047857; border-color: #a7f3d0; }
        .btn-ok:hover { background: #ecfdf5; }
    </style>
</head>
<body>

<#-- 顶部栏（2026-10-22 用户反馈重设计）：
     ① 报告名居中显示，不再拼 feature 描述（feature 名过长会把标题挤压得很难看）；
     ② 返回链接固定文案 "← back to feature"，只回到当前 scenario 所属 feature（不再拼接 feature 名）；
     ③ 顶部整体绿色（绿色=PASS 的语义直觉）。 -->
<div class="topbar">
  <div class="topbar-side">
    <#if featureLink??>
      <a href="../${featureLink}">← back to feature</a>
    <#else>
      <a href="../index.html">← back to overview</a>
    </#if>
  </div>
  <div class="topbar-title">${projectName?html}</div>
  <#-- 2026-10-22 用户要求"三个 report 都要"：顶栏除报告名外显示生成/开始/结束时间。
       per-case 的 startedAt / endedAt 即本用例自身的执行窗口。 -->
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

  <div class="card">
    <div class="crumbs"><a href="../index.html">Overview</a><#if featureLink??><span class="sep">›</span><a href="../${featureLink}">${featureName?html}</a></#if><span class="sep">›</span><b>${outcome.name?html}</b></div>
    <div class="case-head">
      <#-- result 可能尚未落定（已有失败原因但结果为 null）：此时按“有失败原因即失败”推导，
           避免详情页显示 Undefined 而总览页显示 Failed 的自相矛盾。 -->
      <#assign caseKey = (outcome.result??)?then(rkey(outcome.result), (outcome.testFailureCause??)?then("FAILURE", "UNDEFINED"))>
      <span class="badge b-${caseKey}" id="case-result-badge" style="padding:4px 14px;font-size:14px">${keyLabel(caseKey)}</span>
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

  <#-- 2026-10-22 用户反馈「Execution steps 整个区域最右边没和上下两部分对齐」：
       原先失败用例会把 Case actions 放进 340px 右栏，导致 case-main 比上方标题卡、
       下方底部 Tab 卡窄一截，右边缘不齐。改为<b>始终单栏</b>：Case actions 以全宽卡片并入主列，
       于是标题卡 / Case actions / 执行步骤 / 底部 Tab 卡左右边界严格同宽。
       （Playwright Trace / Records 已在页面底部 Tab 区，不再需要右栏承载。） -->
  <#assign hasSidePanel = (caseKey == "FAILURE" || caseKey == "ERROR" || (outcome.overridden!false))>
  <div class="case-layout is-single">

    <#-- 主列：失败用例操作入口 + 执行步骤（Route Register / API Request 已按发生步骤归位） + 用例级证据/附件/示例 -->
    <div class="case-main">

      <#-- 失败用例操作入口：上传补图 + 标记通过（仅失败用例；通过用例不需要）。
           2026-10-20：文案统一为 Scenario（Cucumber 里一条用例就是一个 scenario，避免 case/scenario 混用）。
           2026-10-22：由右侧栏移入主列——右栏会让执行步骤卡片变窄、右边缘与上下卡片不齐。 -->
      <#if hasSidePanel>
      <div class="card case-actions" id="case-actions">
        <h3><span class="icon"></span>Case actions</h3>
        <div class="action-row">
          <button type="button" id="btn-upload" class="btn-action">⬆ Upload screenshot</button>
          <#if !(outcome.overridden!false)>
          <button type="button" id="btn-marksuccess" class="btn-action btn-ok">✓ Make Scenario success</button>
          <#else>
          <button type="button" id="btn-editnote" class="btn-action btn-ok">✎ Edit mark note</button>
          <button type="button" id="btn-revert" class="btn-action">↩ Revert to original</button>
          </#if>
        </div>
        <#if (outcome.overridden!false) && outcome.overrideNote??>
        <div class="sub" style="margin-top:6px">Mark note: ${outcome.overrideNote?html}</div>
        </#if>
        <input type="file" id="shot-input" accept="image/png,image/jpeg" multiple style="display:none"/>
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

      <#-- 用例级兜底：调用发生在步骤上下文之外（如 @Before 钩子）才落到这里；
           步骤内的一律归位到对应步骤（见步骤树）。同样按来源拆 API Request / Route Captured。 -->
      <#if caseRestApi?has_content>
      <div class="card">
        <h3><span class="icon"></span>API Request (${caseRestApi?size})</h3>
        <#list caseRestApi as block><@apiCard block/></#list>
      </div>
      </#if>

      <#if caseCapturedApi?has_content>
      <div class="card con-captured">
        <h3><span class="icon"></span>Route Captured (${caseCapturedApi?size})</h3>
        <div class="con-cards">
          <#list caseCapturedApi as block><@apiCard call=block seq=(block_index + 1)/></#list>
        </div>
      </div>
      </#if>

      <#if outcome.routeRegistrations?has_content>
      <div class="card con-register">
        <h3><span class="icon"></span>Route Register (${outcome.routeRegistrations?size})</h3>
        <ul class="con-lines" style="border-top:0;background:transparent;padding:4px 0 0">
          <#list outcome.routeRegistrations as line><@consoleLine line/></#list>
        </ul>
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
              <td><a href="${ex.outcomeId}/${ex.outcomeId}.html">${ex.name?html}<#if ex.exampleValues?has_content> — ${(ex.exampleValues?join(", "))?html}</#if></a></td>
            </tr>
          </#list>
          </tbody>
        </table>
      </div>
      </#if>

    </div>
  </div>

  <#-- 底部 Tab 区（与页面主体同级、平铺在三张卡位置）：
        ① Console log      —— 普通 stdout/stderr（步骤内 + 步骤外全量），按类别分组；
        ② Playwright trace —— 提示下载 + 一键打开官方 Trace Viewer（由用户选择播放方式）；
        ③ Playwright records —— 场景录屏播放（框架开启 recordVideo 时才有内容）。
       route/net 已在各自步骤以「Route Captured / API Request」展示，Console log 只留 out/err。 -->
  <#assign hasConsole = consoleGroups?has_content || (outcome.browserConsole?has_content)!false || (outcome.browserErrors?has_content)!false>
  <#assign hasTrace = outcome.tracePath??>
  <#assign hasRecords = (outcome.videoPath??)!false>
  <#assign hasScreenshots = (screenshots?has_content)!false>
  <#assign hasDownloads = (outcome.downloads?has_content)!false>
  <#if hasConsole || hasTrace || hasRecords || hasScreenshots || hasDownloads>
  <div class="card bottom-tabs">
    <div class="tab-bar" role="tablist">
      <button type="button" class="tab-btn is-active" role="tab" data-tab="tab-console"
              aria-selected="true">Console log</button>
      <#if hasScreenshots>
      <button type="button" class="tab-btn" role="tab" data-tab="tab-screenshots"
              aria-selected="false">Screenshots (${screenshots?size})</button>
      </#if>
      <#if hasDownloads>
      <button type="button" class="tab-btn" role="tab" data-tab="tab-downloads"
              aria-selected="false">Downloads (${outcome.downloads?size})</button>
      </#if>
      <button type="button" class="tab-btn" role="tab" data-tab="tab-trace"
              aria-selected="false">Playwright trace</button>
      <button type="button" class="tab-btn" role="tab" data-tab="tab-records"
              aria-selected="false">Playwright records</button>
    </div>

    <#-- ① Console log -->
    <div class="tab-pane is-active" id="tab-console" role="tabpanel">
      <#if hasConsole>
        <#if consoleGroups?has_content><@consolePanel consoleGroups/></#if>
        <#if (outcome.browserErrors?has_content)!false>
          <div class="sub" style="margin-top:10px">Page errors</div>
          <ul class="log-lines"><#list outcome.browserErrors as line><li class="con-raw">${line?html}</li></#list></ul>
        </#if>
        <#if (outcome.browserConsole?has_content)!false>
          <div class="sub" style="margin-top:10px">Browser console</div>
          <ul class="log-lines"><#list outcome.browserConsole as line><li class="con-raw">${line?html}</li></#list></ul>
        </#if>
      <#else>
        <p class="tab-empty">No console output was captured for this scenario.</p>
      </#if>
    </div>

    <#-- ①-B Screenshots：该用例全部截图画廊，按步骤序号切换查看任意一张 -->
    <div class="tab-pane" id="tab-screenshots" role="tabpanel">
      <#if hasScreenshots>
        <div class="shot-gallery">
          <#-- 2026-10-22 用户反馈：① 不要下方「缩略图小图条」；② 序号 ①②③ 直接显示在图片底部；
               ③ 图片下方放一行序号按钮，点哪个序号就显示哪个步骤的截图。 -->
          <div class="shot-viewer">
            <#assign firstShot = screenshots?first>
            <img id="shot-big" src="screenshots/${firstShot.file?html}" alt="screenshot"/>
            <#-- 向前 / 向后切图（2026-10-22 用户要求）：叠在大图左右两侧，点一下切上一张/下一张；
                 首/末张时由 JS 置 disabled（避免"看起来能点但没反应"）。 -->
            <button type="button" class="shot-nav shot-prev" id="shot-prev"
                    aria-label="Previous screenshot" title="Previous screenshot">
              <svg viewBox="0 0 24 24" width="22" height="22" aria-hidden="true" fill="none"
                   stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round">
                <polyline points="15 5 8 12 15 19"/>
              </svg>
            </button>
            <button type="button" class="shot-nav shot-next" id="shot-next"
                    aria-label="Next screenshot" title="Next screenshot">
              <svg viewBox="0 0 24 24" width="22" height="22" aria-hidden="true" fill="none"
                   stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round">
                <polyline points="9 5 16 12 9 19"/>
              </svg>
            </button>
            <span class="shot-num shot-num-big" id="shot-big-num">${firstShot.badge}</span>
          </div>
          <div class="shot-picker">
            <#list screenshots as shot>
              <button type="button" class="shot-pick<#if shot?is_first> is-active</#if>"
                      data-file="${shot.file?html}" data-index="${shot.index}" data-badge="${shot.badge}"
                      title="Step ${shot.index}<#if shot.description??> — ${shot.description?html}</#if>">
                <span class="shot-pick-num">${shot.badge}</span>
              </button>
            </#list>
          </div>
        </div>
      <#else>
        <p class="tab-empty">No screenshots were captured for this scenario.</p>
      </#if>
    </div>

    <#-- ①-C Downloads：本用例下载并挂载到报告的文件（框架开启 download.attachToReport 时才有内容）。
         文件已随报告归档在 <outcomeId>/ 下，与页面同目录，故直接用 fileName 作相对链接。 -->
    <div class="tab-pane" id="tab-downloads" role="tabpanel">
      <#if hasDownloads>
        <table class="download-list">
          <thead><tr><th>File</th><th>Archived at</th><th>Attached at</th></tr></thead>
          <tbody>
            <#list outcome.downloads as dl>
              <tr>
                <td><a href="${dl.fileName?html}" download="${dl.fileName?html}">${dl.fileName?html}</a></td>
                <td><code>${(dl.relativePath?html)!''}</code></td>
                <td>${(dl.timestamp?html)!''}</td>
              </tr>
            </#list>
          </tbody>
        </table>
      <#else>
        <p class="tab-empty">
          No files were downloaded in this scenario.<br>
          To attach downloads to the report, set <code>playwright.browser.download.attachToReport.enabled=true</code>
          and read them via <code>waitForDownloadSaved</code> (or call <code>DownloadInfo.attachToReport()</code>).
        </p>
      </#if>
    </div>

    <#-- ② Playwright trace：下载 or 打开官方 Viewer，由用户选择播放方式 -->
    <div class="tab-pane" id="tab-trace" role="tabpanel">
      <#if hasTrace>
        <#-- 下载地址仍需二态：托管（baseHref 存在）时相对链接已锚定到报告根；
             本地预览时页面在 per-case 子目录，需回退一级。播放按钮则<b>只打开 Viewer 首页</b>
             （2026-10-22 用户要求）：官方 Viewer 的 ?trace= 远程拉取依赖服务端 CORS，
             file:// / IDEA 预览（63342）下必然被浏览器拦截，拼了参数反而点开是一片错误——
             不如直接给首页，让用户把下载好的 trace.zip 拖进去，路径短且不会失败。 -->
        <#if baseHref?? && baseHref?has_content>
          <#-- 托管：baseUrl + tracePath 作为真实工件 URL。此处【不能】整体 ?url 编码，
               否则 / 会被编码成 %2F，而 Jenkins(Winstone/Jetty) 默认不会把 %2F 解码回 /
               做路径路由，会把它当作单个文件名 → CI 下载 trace 时 404。
               路径分隔符 / 必须原样保留；若段内含特殊字符再由调用方保证合法。 -->
          <#assign traceUrl = baseHref + outcome.tracePath>
        <#else>
          <#-- 页面已迁到 per-case 子目录，trace.zip 仍位于 runDir 下，需回退一级 -->
          <#assign traceUrl = "../" + outcome.tracePath>
        </#if>
        <#-- Tips 移到路径上方（2026-10-22 用户要求）：先告诉用户"怎么播"，再给路径与操作。 -->
        <div class="trace-hint">
          Tip: download the <code>trace.zip</code> below, then open
          <a href="${traceViewerBaseUrl!'https://trace.playwright.dev'}" target="_blank" rel="noopener">trace.playwright.dev</a>
          and drag the file into the viewer to replay.
        </div>
        <div class="trace-path-row">
          <code class="trace-path">${outcome.tracePath?html}</code>
          <div class="trace-btns">
            <a class="btn-trace" href="${traceViewerBaseUrl!'https://trace.playwright.dev'}" target="_blank" rel="noopener">Play in Trace Viewer</a>
            <a class="btn-trace ghost" href="${traceUrl?html}" download="${outcome.tracePath?keep_after_last("/")}">Download trace.zip</a>
          </div>
        </div>
        <dl class="trace-meta">
          <#-- 英文文案（与按钮 / 提示 / 标签页一致，2026-10-22 用户反馈该区中英混排"显示乱了"）；
               毫秒数用 ?c 关闭 FreeMarker 默认的千分位分组（3510 而不是 3,510）。 -->
          <#if outcome.traceStartTime?? && outcome.traceEndTime??>
            <dt>Recorded window</dt><dd>${outcome.traceStartTime?html} → ${outcome.traceEndTime?html}</dd>
          </#if>
          <#if outcome.traceDurationMs gt 0>
            <dt>Duration</dt><dd>${outcome.traceDurationMs?c} ms</dd>
          </#if>
        </dl>
      <#else>
        <p class="tab-empty">
          No trace was recorded for this scenario (recording disabled, or the trace file failed to write).<br>
          To enable it, set <code>playwright.context.trace.enabled=true</code> (on by default in the framework) and re-run the scenario.
          If it is already enabled but still missing, check <code>playwright.context.close.trace.timeout.seconds</code>
          (default 15s) — a too-small write timeout discards the trace file.
        </p>
      </#if>
    </div>

    <#-- ③ Playwright records：场景录屏 -->
    <div class="tab-pane" id="tab-records" role="tabpanel">
      <#if hasRecords>
        <#-- 与 trace 同源：托管（baseHref 存在）时相对链接已锚定到报告根，直接用 videoPath；
             本地预览时页面在 per-case 子目录，需回退一级。 -->
        <#if baseHref?? && baseHref?has_content>
          <#assign videoSrc = outcome.videoPath>
        <#else>
          <#assign videoSrc = "../" + outcome.videoPath>
        </#if>
        <video class="record-video" controls preload="metadata" src="${videoSrc?html}">
          Your browser cannot play this video — please <a href="${videoSrc?html}" download>download the recording</a>.
        </video>
      <#else>
        <p class="tab-empty">No video was recorded. To enable it, set <code>playwright.context.recordVideo.enabled=true</code>.</p>
      </#if>
    </div>
  </div>
  </#if>

  <footer>Scenario page · reportVersion ${outcome.reportVersion} · outcomeId ${outcome.outcomeId}</footer>
</div>

<#-- 悬浮预览层：hover 步骤截图时显示完整页面（pointer-events:none，不拦截点击/滚动） -->
<div id="shot-hover" aria-hidden="true" role="button" tabindex="-1" title="Click to restore">
  <span class="zoom-tip">Click anywhere or press Esc to restore</span>
  <img alt=""/>
</div>

<script>
(function(){
  var OUTCOME_ID = "${outcome.outcomeId}";
  var OUTCOME_OVERRIDE_NOTE = <#if (outcome.overridden!false) && outcome.overrideNote??>"${outcome.overrideNote?js_string}"<#else>null</#if>;
  var VERDICT_KEY = 'pw-manual-scenario-success';

  /* 底部 Tab 切换（Console log / Playwright trace / Playwright records）：
     三段内容同级平铺，一次只看一段，避免长日志把 trace/records 挤出首屏。 */
  var tabButtons = document.querySelectorAll('.bottom-tabs .tab-btn');
  Array.prototype.forEach.call(tabButtons, function(btn) {
    btn.addEventListener('click', function() {
      var bar = btn.parentNode;
      Array.prototype.forEach.call(bar.querySelectorAll('.tab-btn'), function(b) {
        b.classList.remove('is-active');
        b.setAttribute('aria-selected', 'false');
      });
      btn.classList.add('is-active');
      btn.setAttribute('aria-selected', 'true');
      var card = bar.parentNode;
      Array.prototype.forEach.call(card.querySelectorAll('.tab-pane'), function(p) {
        p.classList.remove('is-active');
      });
      var pane = card.querySelector('#' + btn.getAttribute('data-tab'));
      if (pane) { pane.classList.add('is-active'); }
    });
  });

  /* ---------- Playwright trace 播放 ----------
     播放按钮已改为「只打开 Viewer 首页」的普通链接（2026-10-22 用户要求）：
     官方 Viewer 的 ?trace= 远程拉取依赖服务端 CORS，而 file:// / IDEA 预览（63342）必然被浏览器拦截，
     拼参数只会点开一片错误。现在点击即开 https://trace.playwright.dev/，
     用户把下载好的 trace.zip 拖进去即可——路径短、不会失败，也不再需要这段 JS 预探测。 */

  /* ---------- 人工结论（仅本浏览器） ----------
     报告是静态产物：只有 ReportServer 托管时 /api/mark-success 才存在；IDE 预览（如 63342）、
     file:// 或 CI 制品目录下没有任何后端，POST 会拿到 404 HTML 页。
     以前这里直接 r.json() → "Unexpected token '<' is not valid JSON"（用户已报此错）。
     现在统一走 postJson（校验 ok + content-type），失败即降级：标记只在当前浏览器生效并明确提示，
     上传则自动改为下载 patch 文件（可由 ManualCaseRecorder --apply-patch 回灌），不再报语法错。 */
  function readVerdicts() {
    try { return JSON.parse(localStorage.getItem(VERDICT_KEY) || '{}'); } catch (e) { return {}; }
  }
  function saveVerdict(id) {
    var map = readVerdicts();
    map[id] = 1;
    try { localStorage.setItem(VERDICT_KEY, JSON.stringify(map)); } catch (e) {}
  }
  function applyVerdicts() {
    if (!readVerdicts()[OUTCOME_ID]) return;
    var badge = document.getElementById('case-result-badge');
    if (badge) {
      badge.className = 'badge b-PASS';
      badge.textContent = 'Passed';
      badge.style.padding = '4px 14px';
      badge.style.fontSize = '14px';
    }
    var actions = document.getElementById('case-actions');
    if (actions) actions.style.display = 'none';
  }

  function postJson(url, body) {
    return fetch(url, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'Accept': 'application/json' },
      body: JSON.stringify(body)
    }).then(function(r){
      var contentType = (r.headers && r.headers.get('content-type')) || '';
      if (!r.ok || contentType.indexOf('json') < 0) {
        throw new Error('report backend not available');
      }
      return r.json();
    });
  }

  function downloadPatch(payload, intro) {
    var blob = new Blob([payload], {type: 'application/json'});
    var a = document.createElement('a');
    a.href = URL.createObjectURL(blob);
    a.download = OUTCOME_ID + '.patch.json';
    a.click();
    alert(intro + '\nPatch file: ' + a.download
        + '\nApply locally with: ManualCaseRecorder --apply-patch ' + a.download);
  }

  applyVerdicts();

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
        postJson('api/patch', JSON.parse(payload))
          .then(function(d) {
            if (d && d.ok) {
              alert('Uploaded ' + results.length + ' screenshot(s), reloading...');
              setTimeout(function(){ location.reload(); }, 800);
            } else {
              downloadPatch(payload, 'Upload rejected by the report server (static preview).');
            }
          })
          .catch(function() {
            downloadPatch(payload, 'Report server not available (static preview).');
          });
      } else {
        downloadPatch(payload, 'Patch downloaded (no report server).');
      }
    });
  };

  //  失败用例 / 已标记用例操作按钮：上传补图 + 标记通过（可改备注）+ 撤销标记
  var upBtn = document.getElementById('btn-upload');
  var input = document.getElementById('shot-input');
  if (upBtn && input) {
    upBtn.addEventListener('click', function() { input.click(); });
    input.addEventListener('change', function() {
      if (input.files && input.files.length) { window.__doPatch(input.files); input.value = ''; }
    });
  }
  function markSuccessWithNote(existingNote) {
    var note = window.prompt('Mark note (optional):', existingNote || '');
    if (note === null) { return; }
    var applied = function() {
      saveVerdict(OUTCOME_ID);
      applyVerdicts();
      alert('Scenario marked as success (this browser only).');
    };
    if (location.protocol === 'http:' || location.protocol === 'https:') {
      postJson('api/mark-success', { outcomeId: OUTCOME_ID, note: note })
        .then(function(d) { if (d && d.ok) { setTimeout(function() { location.reload(); }, 400); } else { applied(); } })
        .catch(function() { applied(); });
    } else {
      applied();
    }
  }
  var msBtn = document.getElementById('btn-marksuccess');
  if (msBtn) { msBtn.addEventListener('click', function() { markSuccessWithNote(''); }); }
  var editBtn = document.getElementById('btn-editnote');
  if (editBtn) {
    editBtn.addEventListener('click', function() { markSuccessWithNote(OUTCOME_OVERRIDE_NOTE || ''); });
  }
  var revertBtn = document.getElementById('btn-revert');
  if (revertBtn) {
    revertBtn.addEventListener('click', function() {
      if (!confirm('Revert this scenario to its original (failed) result?')) { return; }
      if (location.protocol === 'http:' || location.protocol === 'https:') {
        postJson('api/mark-revert', { outcomeId: OUTCOME_ID })
          .then(function(d) {
            if (d && d.ok) { setTimeout(function() { location.reload(); }, 400); }
            else { alert('Revert failed (this browser only).'); }
          })
          .catch(function() { alert('Revert requires the report server (not available in static preview).'); });
      } else {
        alert('Revert requires the report server (not available in static preview).');
      }
    });
  }

  /* ---------- 底部 Screenshots 画廊 ----------
     切换方式：点序号按钮 / 点大图左右箭头 / 键盘 ← →（仅 Screenshots 标签可见时生效）。
     2026-10-22 用户要求：加向前向后图标可切图；序号按钮保持明确的 active 与 focus 状态。
     以「数组下标」而非 data-index 判定 active——序号与截图顺序强绑定，避免属性缺失时高亮错位。 */
  var shotBig = document.getElementById('shot-big');
  var shotBigNum = document.getElementById('shot-big-num');
  var shotPicks = [].slice.call(document.querySelectorAll('.shot-pick'));
  var shotPrev = document.getElementById('shot-prev');
  var shotNext = document.getElementById('shot-next');
  var shotPane = document.getElementById('tab-screenshots');
  var shotAt = 0;
  function renderShot() {
    if (!shotPicks.length) { return; }
    shotAt = Math.max(0, Math.min(shotAt, shotPicks.length - 1));
    var cur = shotPicks[shotAt];
    if (shotBig) { shotBig.src = 'screenshots/' + cur.getAttribute('data-file'); }
    if (shotBigNum) { shotBigNum.textContent = cur.getAttribute('data-badge'); }
    shotPicks.forEach(function(t, i) {
      var on = i === shotAt;
      t.classList.toggle('is-active', on);
      t.setAttribute('aria-pressed', on ? 'true' : 'false');
    });
    if (shotPrev) { shotPrev.disabled = shotAt === 0; }
    if (shotNext) { shotNext.disabled = shotAt === shotPicks.length - 1; }
  }
  function stepShot(delta) {
    var next = shotAt + delta;
    if (next < 0 || next >= shotPicks.length) { return; }
    shotAt = next;
    renderShot();
  }
  shotPicks.forEach(function(t, i) {
    t.addEventListener('click', function() { shotAt = i; renderShot(); });
  });
  if (shotPrev) { shotPrev.addEventListener('click', function() { stepShot(-1); }); }
  if (shotNext) { shotNext.addEventListener('click', function() { stepShot(1); }); }
  renderShot();

  /* ---------- 截图点击放大 / 再点恢复 ----------
     点任意截图（底部画廊大图 / 步骤树内联小图）→ 铺满视口显示完整页面；
     再点一下预览层（或按 Esc）→ 恢复原状。
     2026-10-22 用户要求"点击放大，再点击恢复原来的"；原 hover 方案 pointer-events:none，
     鼠标划过就弹全屏、且无法主动关闭，观感上是"乱跳"。 */
  var shotHover = document.getElementById('shot-hover');
  var shotHoverImg = shotHover ? shotHover.querySelector('img') : null;
  function openZoom(src) {
    if (!shotHover || !shotHoverImg || !src) { return; }
    shotHoverImg.src = src;
    shotHover.classList.add('on');
    shotHover.setAttribute('aria-hidden', 'false');
  }
  function closeZoom() {
    if (!shotHover) { return; }
    shotHover.classList.remove('on');
    shotHover.setAttribute('aria-hidden', 'true');
  }
  Array.prototype.forEach.call(document.querySelectorAll('.shot-zoom, #shot-big'), function(img) {
    img.addEventListener('click', function() { openZoom(img.getAttribute('src')); });
  });
  if (shotHover) {
    shotHover.addEventListener('click', closeZoom);
  }
  document.addEventListener('keydown', function(e) {
    if (e.key === 'Escape' || e.keyCode === 27) { closeZoom(); return; }
    //  键盘 ← → 切图：仅在 Screenshots 标签可见、且未处于放大预览时生效。
    if (shotPane && shotPane.classList.contains('is-active') && shotPicks.length > 1
        && !(shotHover && shotHover.classList.contains('on'))) {
      if (e.key === 'ArrowLeft') { e.preventDefault(); stepShot(-1); }
      if (e.key === 'ArrowRight') { e.preventDefault(); stepShot(1); }
    }
  });

  /* 堆栈 Copy：按钮在 <summary> 内，需 preventDefault 阻止折叠切换；
     复制该面板内全部 .stack-line 的文本（保持行序与原缩进）。 */
  Array.prototype.forEach.call(document.querySelectorAll('.stack-copy'), function(btn) {
    btn.addEventListener('click', function(ev) {
      ev.preventDefault();
      ev.stopPropagation();
      var panel = btn.closest('.failure-stack');
      var lines = panel ? panel.querySelectorAll('.stack-line') : [];
      var text = Array.prototype.map.call(lines, function(l) { return l.textContent; }).join('\n');
      var flash = function() {
        var old = btn.textContent;
        btn.textContent = 'Copied';
        btn.disabled = true;
        setTimeout(function() { btn.textContent = old; btn.disabled = false; }, 1200);
      };
      if (navigator.clipboard && navigator.clipboard.writeText) {
        navigator.clipboard.writeText(text).then(flash, flash);
      } else {
        var ta = document.createElement('textarea');
        ta.value = text;
        document.body.appendChild(ta);
        ta.select();
        try { document.execCommand('copy'); } catch (e) {}
        document.body.removeChild(ta);
        flash();
      }
    });
  });
})();
</script>
</body>
</html>
