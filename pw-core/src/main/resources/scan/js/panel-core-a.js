    (function() {
      // 仅当 openPanel 运行期间（由 localStorage 开关标记）才注入面板；
      // 这样刷新/导航后 addInitScript 会自动重建面板，而正常访问不受影响。
      // 注意：外层必须用 (function(){...})() 而非 (() => {...})() —— 后者以 "(() =>" 开头，
      // 会被 Playwright 的 evaluate/addInitScript 误判成"箭头函数"并错误解析尾部的 ")();"，
      // 抛出 SyntaxError: Invalid or unexpected token。
      // 门禁：正常访问不打面板。openPanel/followPage 会显式置位 __rolePanelEnabled='1'；
      // 跨源新页面 localStorage 可能为空或不可写，故同时允许 window.__rolePanelForce 兜底
      // （由 followPage 注入），确保新页面无论如何都能重建面板。
      // 【关键修复"嵌套 iframe 元素序号 - / 不进 step"】
      // 门禁原来会让 iframe（frameOne）的 message 监听一并早退：frameTwo（grandchild）的 postMessage
      // 先发到 frameOne，但 frameOne 的 message 监听没注册 → 不向上转发到顶层 → 顶层 __currentStep
      // 无 frameTwo 元素 → 面板序号 '-'、停止时不入选 step（Java 内存态却因绑定桥直接回传而有值，
      // 表现为"能回传但不在面板/step"）。而 frameOne 元素能进 step，是因 frameOne→top 是直达、
      // 由顶层监听接收，不依赖 frameOne 自身的转发。
      // 修复：把"拾取聚合/转发的 message 监听"从面板门禁中解耦——无论是否渲染面板，iframe 都应
      // 无条件注册该监听，确保嵌套 iframe 的 pick 能逐层转发到顶层进入 __currentStep。面板 UI 门禁
      // 保持不变（见下方 __rolePanelUI 分支）。
      var __rolePanelUI = false;
      try { __rolePanelUI = (localStorage.getItem('__rolePanelEnabled') === '1' || !!window.__rolePanelForce); } catch (e) { __rolePanelUI = false; }
      // 顶层面板接收来自 iframe（含跨源）的拾取：iframe 内点击 push 进的是 iframe 自己的 window.__rolePicks，
      // 主框架可见面板读不到 → 表现为"Java 内存态增长、面板空白"。iframe 经 postMessage 把 pick 发给父窗口，
      // 此处接收并聚合进主框架 window.__rolePicks + 渲染。纯前端同步，无需 Java 主循环轮询。
      window.addEventListener('message', function(ev) {
        try {
          var d = ev.data;
          if (!d || d.__rolePickMsg !== true || !d.pick) return;
          try {
            var __dbg = (d.pick && (d.pick.role || d.pick._sig || d.pick.name)) || '';
            var __dbgKey = d.pick && d.pick._sigKey;
            if (typeof console !== 'undefined' && console.log)
              console.log('[rolePick][msg-in] selfTop=' + (window.self === window.top)
                + ' srcTop=' + (ev.source && ev.source === window.self)
                + ' role=' + __dbg + ' sigKey=' + (__dbgKey || '') + ' pickActive=' + (window.__rolePickActive)
                + ' href=' + (location && location.href));
          } catch (_dbgErr) {}
          // 安全加固（本地开发工具场景）：排除顶层自身的"自环"消息（理论上不会发生），
          // 其余带 __rolePickMsg 标记的 pick 一律接纳——包括 srcdoc/跨源子 frame。
          // 注：srcdoc iframe 在某些情况下 ev.source 为 null/非标准 Window，若强校验 ev.source.postMessage
          // 反而会误杀合法 iframe 拾取（实测 postMessage 已发出但被守卫丢弃）。消息真伪以 __rolePickMsg 标记为准。
          if (window.self === window.top && ev.source === window.self) {
            return;   // 顶层自身发给自己：拒绝（避免自环）
          }
          // 向上中继：非顶层 frame 收到子 frame 的 pick 后，继续向自己的父窗口转发，
          // 直到顶层（顶层才真正聚合进主框架 window.__rolePicks）。这样即使跨多级嵌套 iframe /
          // Playwright 下 window.top 直达投递异常的场景，pick 也能逐层可靠上送。
          if (window.self !== window.top) {
            try { window.parent.postMessage(d, '*'); } catch (e) {}
          }
          window.__rolePicks = window.__rolePicks || [];
          window.__rolePickSigs = window.__rolePickSigs || {};
          var p = d.pick;
          var key = p._sigKey || p._sig;
          if (key && window.__rolePickSigs[key]) return;
          if (key) window.__rolePickSigs[key] = true;
          window.__rolePicks.push(p);
          // 关键修复：iframe（含嵌套 frame）内的拾取元素经 postMessage 上送顶层后，必须回传 Java 权威内存态
          // （javaPickBySig），否则仅停留在浏览器侧 window.__rolePicks——顶层面板虽能渲染，但 stop 时按 Java
          // 内存态生成代码会漏掉这些 iframe 元素（表现为"页面元素清单不含 frame 内元素"）。
          // 与 __recordPick 内回传保持同一套（_sigKey 去重键 + __roleOnPick 回传 + console 兜底），且幂等。
          if (window.self === window.top) {
            try {
              if (typeof window.__sigKey === 'function') p._sigKey = window.__sigKey(p);
              if (typeof window.__roleOnPick === 'function') window.__roleOnPick(JSON.stringify(p));
            } catch (e) {}
            try { console.log('__roleOnPick::' + JSON.stringify(p)); } catch (_) {}
          }
          // 与主框架实时点选一致：拾取激活态下，iframe 内的点选也自动入选当前 step（选择集），
          // 用户无需再到面板重复勾选。
          // 只聚合进 __rolePicks 供面板展示，绝不入选 __currentStep（否则 checkbox 等被默认勾选）。
          // 【关键修复"用户点击回灌候选不进 step"】与主框架 __recordPick 的 2169 行一致：
          // 只要不在当前 __currentStep 中就入选 step（用户主动点击 = 明确选择）；已入选的重复点击除外。
          if (window.__rolePickActive && window.__currentStep) {
            var __pkey = (typeof window.__sigKey === 'function') ? window.__sigKey(p) : (p._sigKey || p._sig || '');
            var __pinStep = false;
            if (__pkey) {
              for (var __qi = 0; __qi < window.__currentStep.length; __qi++) {
                try {
                  var __q = window.__currentStep[__qi];
                  if (__q && (__q._sigKey === __pkey
                      || (__q._sig && __pkey.indexOf(__q._sig) !== -1)
                      || (typeof window.__sigKey === 'function' && window.__sigKey(__q) === __pkey))) {
                    __pinStep = true; break;
                  }
                } catch (e) {}
              }
            }
            if (!__pinStep) window.__currentStep.push(p);
          }

          if (window.__renderPicks) window.__renderPicks();
        } catch (e) {}
      });
      // 面板 UI 仅顶层文档渲染：iframe / 嵌套 frame 内不应再构建面板（避免面板被嵌入子 frame）。
      // 非顶层 frame 到此为止——上面注册的 message 监听器已能把子 frame 的 pick 逐层上送顶层聚合，
      // 但不再调用 build() 建面板，保证整页只有一个可见面板（顶层）。
      if (window.self !== window.top) return;
      // 顶层但面板门禁未开（正常访问，localStorage 开关未置位）→ 仅注册 message 监听用于嵌套 iframe
      // 拾取聚合/转发，不构建面板 UI。
      if (!__rolePanelUI) return;
      function build() {
      var old = document.getElementById('__rolePanel');
      if (old) old.remove();
      // 重建面板时清理上一次遗留的定时器，避免叠加
      if (window.__roleToggleTimer) { clearInterval(window.__roleToggleTimer); window.__roleToggleTimer = null; }
      window.__panelCmds = window.__panelCmds || [];
      window.__pickDone = false;
      // 面板打开默认进入 IDLE（待命）态：显示"▶ 开始拾取"，页面点击不拾取任何元素；
      // Java 通过 setPickMode 在 start 时显式切到手动拾取模式（权威驱动）。
      // 修复：整文档导航/iframe 重建面板后，若本会话仍处于拾取中（sessionOn）
      // 则保持 'manual'，避免按钮掉回"▶ 开始拾取"而底层 active 仍为 true 的错位
      // （用户的停止拾取按钮在 url change 后不应变回开始拾取）。
      var __sessOn = window.__rolePickSessionOn;
      if (!__sessOn) { try { __sessOn = localStorage.getItem('__rolePickSessionOn') === '1'; } catch (e) {} }
      window.__roleMode = (__sessOn) ? 'manual' : 'idle';

      function pushCmd(c) {
        // 优先用 exposeFunction 暴露的 Java 回调（事件驱动，无需 Java 轮询）；
        // 万一绑定尚未就绪则回退到 __panelCmds 队列，保证命令不丢。
        if (typeof window.__rolePickerCmd === 'function') {
          try { window.__rolePickerCmd(c); return; } catch (e) {}
        }
        window.__panelCmds = window.__panelCmds || [];
        window.__panelCmds.push(c);
      }

      // 内联 SVG 图标（24x24，currentColor 继承按钮文字色）
      function svg(inner) {
        return '<svg width="18" height="18" viewBox="0 0 24 24" fill="currentColor" xmlns="http://www.w3.org/2000/svg">' + inner + '</svg>';
      }
      var ICON = {
        start: svg('<path d="M8 5v14l11-7z"/>'),                                                // ▶ 开始
        stop:  svg('<path d="M6 6h12v12H6z"/>'),                                               // ⏹ 停止
        copy:  svg('<path d="M16 1H4a2 2 0 0 0-2 2v12h2V3h12V1zm3 4H8a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h11a2 2 0 0 0 2-2V7a2 2 0 0 0-2-2z"/>'), // 📋 复制
        abort: svg('<path d="M13 3h-2v10h2V3zm4.83 2.17-1.42 1.42A7 7 0 1 1 7.58 6.59L6.17 5.17a9 9 0 1 0 11.66 0z"/>'), // ⏻ 终止
        close: svg('<path d="M18.3 5.7 12 12l6.3 6.3-1.4 1.4L10.6 13.4 4.3 19.7 2.9 18.3 9.2 12 2.9 5.7 4.3 4.3l6.3 6.3 6.3-6.3z"/>'), // ✕ 关闭
        };
      // 统一的图标按钮：圆形/圆角方块、hover 提亮、带 title 作为无障碍提示
      function mkIconBtn(svgHtml, bg, title, onClick) {
        var b = document.createElement('button');
        b.type = 'button';
        b.title = title;
        b.innerHTML = svgHtml;
        b.style.cssText = 'width:34px;height:34px;padding:0;border:0;border-radius:8px;cursor:pointer;' +
          'display:inline-flex;align-items:center;justify-content:center;color:#fff;background:' + bg + ';' +
          'transition:filter .15s;';
        b.onmouseenter = function(){ b.style.filter = 'brightness(1.12)'; };
        b.onmouseleave = function(){ b.style.filter = 'none'; };
        b.onclick = onClick;
        return b;
      }

              // docked 模式：把页面内容挤到左侧，给面板留出 420px 右侧空间（像 DevTools，不盖内容）。
              document.documentElement.style.overflowX = 'hidden';
              if (document.body) document.body.style.marginRight = '420px';

              var panel = document.createElement('div');
              panel.id = '__rolePanel';
              panel.style.cssText = 'position:fixed;top:0;right:0;width:420px;height:100vh;' +
                'display:flex;flex-direction:column;pointer-events:auto;background:#1e1e1e;color:#e0e0e0;' +
                'border-left:1px solid #000;box-shadow:-8px 0 24px rgba(0,0,0,.35);' +
                'font:13px/1.5 Consolas,Monaco,monospace;overflow:hidden;z-index:2147483647;';

              // 左侧可拖拽改变宽度的握把（col-resize）：拖动时同步改面板宽度与页面右侧预留空间
              // 注意：grip 必须放在 panel 创建之后，否则 panel 此时为 undefined，调用 panel.appendChild 会崩溃。
              var grip = document.createElement('div');
              grip.title = '拖动调整面板宽度';
              grip.style.cssText = 'position:absolute;left:0;top:0;width:6px;height:100%;cursor:col-resize;'
                + 'background:rgba(255,255,255,.06);z-index:5;';
              grip.onmousedown = function(ev) {
                ev.preventDefault();
                var startX = ev.clientX, startW = panel.offsetWidth;
                function move(e) {
                  var w = startW + (startX - e.clientX);
                  if (w < 280) w = 280; if (w > 900) w = 900;
                  panel.style.width = w + 'px';
                  if (document.body) document.body.style.marginRight = w + 'px';
                }
                function up() { document.removeEventListener('mousemove', move); document.removeEventListener('mouseup', up); }
                document.addEventListener('mousemove', move);
                document.addEventListener('mouseup', up);
              };
              panel.appendChild(grip);

              var header = document.createElement('div');
              header.style.cssText = 'padding:10px 12px;background:#1e88e5;color:#fff;font-weight:bold;' +
                'display:flex;align-items:center;gap:10px;cursor:move;';
              var title = document.createElement('span');
              title.style.cssText = 'flex:1;min-width:0;white-space:nowrap;overflow:hidden;text-overflow:ellipsis;';
              var nf = window.__nlsFiles || [];
              var pn = window.__rolePageName || '';
              title.textContent = 'RoleElement 拾取器'
                + (pn ? '  › ' + pn : '')
                + (nf.length ? '  (files=' + (nf.length === 1 ? nf[0] : nf[0] + ' (+' + (nf.length - 1) + ')') + ')' : '');
              var status = document.createElement('span');
              status.id = '__roleStatus';
              // 【修复状态文字颜色"深绿看不清"】
              // 面板 header 是蓝色（#1e88e5），title 用 color:#fff 显式声明以保对比度；状态条原本只设
              // opacity 与布局依赖继承，偶发受宿主页面/字体/颜色继承影响呈现深绿，与蓝色背景几乎不可读。
              // 修复：显式 color:#fff（白色）确保任何继承/上下文下都清晰显示在蓝色 header 上。
              status.style.cssText = 'font-weight:normal;font-size:12px;color:#fff;opacity:.95;' +
                'white-space:nowrap;overflow:hidden;text-overflow:ellipsis;max-width:190px;flex-shrink:0;';
              status.textContent = '就绪：点 ▶ 开始拾取并在页面点击元素';
              // 关闭面板：标题栏右侧 X 图标（对齐 page.pause() 的 inspector 关闭按钮）
              var closeBtn = mkIconBtn(ICON.close, 'transparent', '关闭面板', function() {
                if (window.__roleToggleTimer) { clearInterval(window.__roleToggleTimer); window.__roleToggleTimer = null; }
                // 关闭面板时还原页面布局（撤销 docked 给 body/html 预留的右侧空间）
                try { document.body.style.marginRight = ''; document.documentElement.style.overflowX = ''; } catch (e) {}
                pushCmd('done');
              });
              closeBtn.style.background = 'transparent';
              closeBtn.onmouseenter = function(){ closeBtn.style.background = 'rgba(255,255,255,.2)'; closeBtn.style.filter = 'none'; };
              closeBtn.onmouseleave = function(){ closeBtn.style.background = 'transparent'; closeBtn.style.filter = 'none'; };
              closeBtn.onmousedown = function(e){ e.stopPropagation(); };
              header.appendChild(title);
              header.appendChild(status);
              header.appendChild(closeBtn);

              var toolbar = document.createElement('div');
              toolbar.style.cssText = 'padding:10px 14px;background:#252526;display:flex;gap:8px;align-items:center;';
              // 开始/停止合并为同一个切换控件：空闲显示 ▶ 开始，拾取中显示 ⏹ 停止
              var toggleBtn = mkIconBtn(ICON.start, '#43a047', '开始拾取', function togglePick() {
                // 乐观即时反馈：点击后立刻让切换控件呈现"目标态"，无需等待 Java 往返 + START_SCRIPT 执行，
                // 消除"点了开始却要等往返才变成停止"的迟滞感（refreshToggle 会读取 __rolePickWanted）。
                // 关键修复：用"当前显示态"（真实激活态 ∪ 乐观意图）翻转来决定命令，而非直接读可能滞后的
                // window.__rolePickActive。否则快速连点 / Java 往返延迟期间 window.__rolePickActive 未及时更新，
                // 两次点击会读到相同旧值、发出相同命令（都 start 或都 stop）而非翻转，
                // 表现即"停止按钮有时点不动、停止后再点开始却拾取不了"。
                var willStart = !(window.__rolePickActive || window.__rolePickWanted);
                window.__rolePickWanted = willStart;
                // 同步更新 __roleMode（按钮态的唯一权威依据）。
                window.__roleMode = willStart ? 'manual' : 'idle';
                if (willStart) {
                  // 新一轮「开始拾取」：清空旧元素的动作序号并复位计数器，使本轮编号从 1 重新连续
                  //（本次新拾取或重拾的元素会在 recordPick 中重新铸号）。
                  try { window.__rolePickSeq = 0; } catch (e) {}
                  // 【修复"新一轮首号不归 1"】__roleMaxNo 是 recordPick 内"只增不回退"的最大动作号计数器，
                  // 若不在新一轮开始处归零，下一轮首元素会续接上一轮末号（与"从 1 重新连续"语义矛盾）。
                  // 仅用户手动开启新一轮（willStart）时清零；跨域导航的 Java 注入 start 不走此分支，保留续接。
                  try { window.__roleMaxNo = 0; } catch (e) {}
                  try {
                    var _ps = window.__rolePicks || [];
                    for (var _pi = 0; _pi < _ps.length; _pi++) {
                      try { delete _ps[_pi]._pickNos; } catch (e) {}
                      try { _ps[_pi]._pickSeq = 0; } catch (e) {}
                    }
                    if (typeof window.__renderPicks === 'function') window.__renderPicks();
                  } catch (e) {}
                }
                pushCmd(willStart ? 'start' : 'stop');
              });
              // ---- 顶部 Tab：页面元素 / 页面类 / 步骤代码 ----
              var tabBar = document.createElement('div');
              tabBar.style.cssText = 'display:flex;gap:0;background:#252526;border-bottom:1px solid #1b1b1b;';
              function mkTab(label, id, active) {
                var t = document.createElement('button');
                t.type = 'button';
                t.textContent = label;
                // 【优化当前 Tab 聚焦背景颜色】
                // 旧实现 active 背景 #1e1e1e（深灰）与非 active #2d2d2d（稍浅灰）差异极小，3 个 tab 谁聚焦难以一眼分辨。
                // 优化：聚焦 Tab 用蓝色（与 header #1e88e5 同色系 #1565c0）背景 + 白色加粗文字 + 3px 亮蓝下边框，
                // 与非聚焦（#2d2d2d 灰底 + 普通字重）形成强烈对比，聚焦一目了然。
                t.style.cssText = 'flex:1;padding:8px 0;border:0;cursor:pointer;font:13px/1.4 sans-serif;font-weight:' + (active ? 'bold' : 'normal') + ';' +
                  'color:#fff;background:' + (active ? '#1565c0' : '#2d2d2d') + ';' +
                  'border-bottom:' + (active ? '3px solid #42a5f5' : '3px solid transparent') + ';';
                t.onclick = function() { window.__roleActiveTab = id; showTab(); };
                return t;
              }
              var tabPage = mkTab('页面元素', 'page', true);
              var tabClass = mkTab('页面类', 'class', false);
              var tabStep = mkTab('步骤代码', 'step', false);
              // 【断言 Tab】与「步骤代码」同源同序生成的独立 *Assertions 类（只对勾选元素）。
              // 单独成 Tab 的原因：断言与操作生命周期不同（操作随流程改、断言是契约），分开展示才好 review。
              var tabAssert = mkTab('断言', 'assert', false);
              tabBar.appendChild(tabPage);
              tabBar.appendChild(tabClass);
              tabBar.appendChild(tabStep);
              tabBar.appendChild(tabAssert);
              window.__roleActiveTab = window.__roleActiveTab || 'page';

              // 焦点感知复制：优先按当前真实 DOM 焦点（activeElement）判断用户"聚焦在哪一块"，
              // 再复制对应区块内容；若焦点不在任何代码区，则回退到当前激活的 Tab（点击切换的 Tab）。
              function __activeScope() {
                // 返回当前焦点所在区块：'class' / 'step' / 'assert' / 'page' / null（都不在，回退 tab）
                var a = document.activeElement;
                if (!a || !a.id) return null;
                if (a.id.indexOf('__roleCodeArea__') === 0) return 'class';
                if (a.id.indexOf('__roleCodeArea2__') === 0) return 'step';
                // 【修复"Copy 没有覆盖断言"】「断言」Tab 的 textarea id 为 __roleAssertArea__<pageClass>。
                // 不认它时焦点判定返回 null ⇒ 回退到激活 Tab='assert' ⇒ 掉进下方 else 分支（页面元素清单），
                // 表现为"在断言 Tab 点复制，复制出来的是元素清单，不是断言代码"。
                if (a.id.indexOf('__roleAssertArea__') === 0) return 'assert';
                // 焦点落在某 Tab 内容容器或其子节点（textarea 之外）时，按容器归属判定
                if (classContent && classContent.contains(a)) return 'class';
                if (stepContent && stepContent.contains(a)) return 'step';
                if (pageContent && pageContent.contains(a)) return 'page';
                var assertAreas = document.getElementById('__roleAssertAreas');
                if (assertAreas && assertAreas.contains(a)) return 'assert';
                return null;
              }
              var copyBtn = mkIconBtn(ICON.copy, '#2e7d32', '复制代码（焦点在哪块就复制哪块）', function() {
                var ta = null, code = '';
                try {
                  // 1) 焦点优先：焦点落在某块代码区/容器，跟随焦点；否则回退到当前激活 Tab
                  var scope = __activeScope() || window.__roleActiveTab;
                  if (scope === 'class') {
                    var k = window.__roleClassSubTabBar_active;
                    ta = k ? document.getElementById('__roleCodeArea__' + k) : null;
                    if (!ta) ta = document.querySelector('#__roleClassAreas textarea');
                    code = ta ? ta.value : '';
                  } else if (scope === 'step') {
                    var k2 = window.__roleStepSubTabBar_active;
                    ta = k2 ? document.getElementById('__roleCodeArea2__' + k2) : null;
                    if (!ta) ta = document.querySelector('#__roleStepAreas textarea');
                    code = ta ? ta.value : '';
                  } else if (scope === 'assert') {
                    // 「断言」Tab：复制当前子 Tab（页面类过滤）下的断言类源码（与页面类/步骤代码同一口径）。
                    // 断言子 Tab 的 textarea id 前缀为 __roleAssertArea（见 panel-core-b.js#__fillAssertTabs）。
                    var k3 = window.__roleAssertSubTabBar_active;
                    ta = k3 ? document.getElementById('__roleAssertArea__' + k3) : null;
                    if (!ta) ta = document.querySelector('#__roleAssertAreas textarea');
                    code = ta ? ta.value : '';
                  } else {
                    // "页面元素"Tab：复制当前子 Tab（页面类过滤）下的元素清单文本，
                    // 每行与列表展示完全一致（[全局拾取顺序号] strategy/role/name/id/css/index/标记），
                    // 含全局递增的拾取序号 _pickNos（如 [1,4,7]），便于外部粘贴核对步骤顺序。
                    var act = window.__roleActivePageClass;
                    var lines = [];
                    (window.__rolePicks || []).forEach(function(p) {
                      if (!p) return;
                      var pc = p._pageClass || (window.__rolePageName || '未知页');
                      if (pc !== act) return;
                      // 全局拾取顺序号（与面板列表前缀一致）：优先 _pickNos 数组，其次单值 _pickSeq，再兜底全局位次。
                      var _seqNo;
                      if (Array.isArray(p._pickNos) && p._pickNos.length) {
                        _seqNo = p._pickNos.join(',');
                      } else if (typeof p._pickSeq === 'number' && p._pickSeq > 0) {
                        _seqNo = p._pickSeq;
                      } else {
                        var _all = window.__rolePicks || [];
                        for (var _ai = 0; _ai < _all.length; _ai++) { if (_all[_ai] === p) { _seqNo = (_ai + 1); break; } }
                        if (_seqNo === undefined) _seqNo = '-';
                      }
                      var s = (p.strategy || 'role');
                      if (p.role) s += ' role=' + p.role;
                      if (p.name) s += ' name="' + p.name + '"';
                      // 【按需求移除面板上的 key= 展示】与 panel-core-b.js 行渲染同口径：只展示 role/name/id/css。
                      // pick.key/resolvedKey 仍保留在状态与回传里，生成侧据"是否传 NLS"决定定位方式。
                      if (p.id) s += ' id=' + p.id;
                      if (p.css) s += ' css=' + p.css;
                      // 下标优先用回传的 p.index；回传链路常丢 index（RoleEntry.index 为 -1，Java 端刻意不从签名回填，
                      // 以免大量 ...#0 签名改变产物形态），此时从 p._sigKey 末尾的 #N 还原（与 list 判定 / step 生成同源）。
                      // 这样即便 index 回传丢失，面板仍稳定展示"列表元素的局部序号"，与后续步骤 .get(N) 对齐。
                      var _ix = p.index;
                      if (_ix == null || _ix < 0) {
                        _ix = -1;
                        var _sk = p._sigKey;
                        if (_sk) {
                          var _t = String(_sk).trim();
                          if (_t.charAt(0) === '[') { try { var _a = JSON.parse(_t); if (_a && _a.length) _t = String(_a[0]); } catch (e) {} }
                          var _h = _t.lastIndexOf('#');
                          if (_h > 0 && _h < _t.length - 1) { var _n = parseInt(_t.substring(_h + 1), 10); if (!isNaN(_n)) _ix = _n; }
                        }
                      }
                      if (_ix >= 0) s += ' #' + _ix;
                      if (p.popup) s += ' [popup]';
                      if (p.download) s += ' [download]';
                      if (p.hover) s += ' [hover]';
                      if (p.dblClick) s += ' [dbl]';
                      if (p.clickCount && p.clickCount > 1) s += ' [点' + p.clickCount + '次]';
                      lines.push(pc + ' | [' + _seqNo + '] ' + s);
                    });
                    code = lines.join('\\n');
                    if (!code) { status.textContent = '当前无可复制的页面元素'; return; }
                  }
                } catch (e) {}
                function ok() {
                  status.textContent = '已复制 ✔';
                  status.style.color = '#00e676';
                  status.style.fontWeight = 'bold';
                  status.style.fontSize = '14px';
                  status.style.background = 'rgba(0,230,118,.18)';
                  status.style.borderRadius = '3px';
                  status.style.padding = '1px 6px';
                  status.style.textShadow = '0 0 6px rgba(0,230,118,.6)';
                  copyBtn.title = '已复制';
                }
                try {
                  if (navigator.clipboard && navigator.clipboard.writeText) {
                    navigator.clipboard.writeText(code).then(ok, function() { fb(); });
                  } else { fb(); }
                } catch (e) { fb(); }
                function fb() {
                  // 兜底：有文本框则选中复制；"页面元素"Tab 无文本框时用临时 textarea 承载再 execCommand。
                  if (ta) { ta.focus(); ta.select(); try { document.execCommand('copy'); ok(); }
                  catch (e2) { status.textContent = '复制失败，请手动复制'; } return; }
                  try {
                    var tmp = document.createElement('textarea');
                    tmp.value = code;
                    tmp.style.cssText = 'position:fixed;left:-9999px;top:0;';
                    document.body.appendChild(tmp);
                    tmp.focus(); tmp.select();
                    document.execCommand('copy');
                    document.body.removeChild(tmp);
                    ok();
                  } catch (e3) { status.textContent = '复制失败，请手动复制'; }
                }
              });
              var abortBtn = mkIconBtn(ICON.abort, '#e53935', '终止运行', function() { pushCmd('abort'); });
              toolbar.appendChild(toggleBtn);
              toolbar.appendChild(copyBtn);
              toolbar.appendChild(abortBtn);

              // 根据 window.__roleMode（Java 权威驱动）实时同步按钮态。
              // 模式语义：idle（待命，仅开始可用）/ manual（手动拾取，开始=停止）。
              // 仅在状态变化时重写 DOM，避免定时器无谓重绘（企业级：减少无变化重排）。
              var __lastMode = null;
              function refreshToggle() {
                // 【按钮态自愈：新页面开箱即显示"⏹ 停止拾取"】__roleMode 在 build() 里只推导一次，而新文档
                // （尤其跨源新页）上"面板构建"常早于 head 脚本置位会话开关 ⇒ 模式停在 'idle'、按钮显示
                // "▶ 开始拾取"，与"其实正处于拾取会话"的真实态不符（用户被迫手点一次 ▶ 才能拾取）。
                // 这里每轮（80ms）都按会话真相兜底升级一次：判定与拾取库的 __rolePickEnsureManual 同源，
                // 含"用户刚点过停止则不得回弹"的保护；停止时 stop-* 脚本会清掉会话开关，故不会把已停止误升。
                try {
                  if ((window.__roleMode || 'idle') === 'idle'
                      && typeof window.__rolePickEnsureManual === 'function') {
                    window.__rolePickEnsureManual();
                  }
                } catch (e) {}
                var mode = window.__roleMode || 'idle';
                var modeChanged = (mode !== __lastMode);
                __lastMode = mode;
                // 当前聚焦页面（用户正在点击/停留的那个浏览器页）实时反映：手动切换标签页/弹窗/跳转后，
                // window.__rolePageName 会随之变化，此处每轮刷新标题，让用户始终看清"此刻拾取归属哪一页"。
                var focusPage = (typeof window.__rolePageName === 'string' && window.__rolePageName) ? window.__rolePageName : '';
                if (typeof title !== 'undefined' && title) {
                  var nf = window.__nlsFiles || [];
                  var newTitle = 'RoleElement 拾取器'
                    + (focusPage ? '  › ' + focusPage : '')
                    + (nf.length ? '  (files=' + (nf.length === 1 ? nf[0] : nf[0] + ' (+' + (nf.length - 1) + ')') + ')' : '');
                  if (title.textContent !== newTitle) title.textContent = newTitle;
                }
                // 已生成 step 数：自动生成（拾取中实时）与手动封装二者只会其一有值
                var autoN = (typeof window.__roleAutoStepCount === 'number') ? window.__roleAutoStepCount : 0;
                var pkgN = (window.__steps && window.__steps.length) ? window.__steps.length : 0;
                var stepTotal = Math.max(autoN, pkgN);
                var stepSuffix = stepTotal > 0 ? (' · 已生成 ' + stepTotal + ' 个步骤（页面类按所属 URL 自动派生，自动处理 iframe/弹窗/新页面）') : '';
                // 仅模式变化时重绘按钮态（避免无谓重排）
                if (modeChanged) {
                  if (mode === 'manual') {
                    toggleBtn.innerHTML = ICON.stop; toggleBtn.title = '停止拾取';
                    toggleBtn.style.background = '#fb8c00'; toggleBtn.disabled = false;
                    toggleBtn.style.opacity = '1'; toggleBtn.style.pointerEvents = 'auto'; toggleBtn.style.cursor = 'pointer';
                  } else { // idle
                    toggleBtn.innerHTML = ICON.start; toggleBtn.title = '开始拾取';
                    toggleBtn.style.background = '#43a047'; toggleBtn.disabled = false;
                    toggleBtn.style.opacity = '1'; toggleBtn.style.pointerEvents = 'auto'; toggleBtn.style.cursor = 'pointer';
                  }
                  window.__rolePickWanted = null;
                }
                // 每次都刷新状态栏（实时提示 step 语义与计数，不依赖模式切换）
                var hint;
                if (mode === 'manual') {
                  hint = '手动拾取中：点哪个拾哪个；停止时整段拾取作为一个步骤（自动包含 iframe/弹窗/新页面边界）' + stepSuffix;
                } else {
                  hint = '就绪：▶ 开始拾取 = 手动模式（开始到停止为一个步骤）' + stepSuffix;
                }
                if (status.textContent !== hint) status.textContent = hint;
                // 步骤 Tab 标题固定为「步骤代码」，不再追加实时计数 (N)
                // （产品要求：Tab 名不带 number；step 数量信息仍保留在状态栏文案里，不丢可见性）。
                if (tabStep) tabStep.textContent = '步骤代码';
                // 【修复"全选计数为0/7"】每次 refreshToggle 都刷新"已选"计数，
                // 确保有序号元素（_pickNos.length>0）被正确计入已选计数，无需等待 checkbox 点击。
                try { if (typeof window.refreshSelInfo === 'function') window.refreshSelInfo(); } catch (e) {}
              }
              window.__roleRefreshToggle = refreshToggle;   // 暴露给拾取库作用域（如回灌完成 / 模式切换时刷新按钮态）
              refreshToggle();
              // 状态同步定时器：每 80ms 收敛到真实态，降低"开始"迟滞感。
              window.__roleToggleTimer = setInterval(refreshToggle, 80);

              // 页面类 / 步骤代码文本框按 pageClass 分栏动态创建（见 __fillCodeTabs / __renderCodeTabs），
              // 每个 pageClass 一个子 Tab 对应一个可复制的代码文本框，对齐"页面元素"Tab 的子 Tab 布局。

              // "页面元素" Tab 内容：页面类子 Tab 栏 + 候选项清单（带勾选框）+ 封装为步骤 按钮。
              var pageContent = document.createElement('div');
              pageContent.style.cssText = 'flex:1;display:flex;flex-direction:column;min-height:0;overflow:hidden;';
              var subTabBar = document.createElement('div');
              subTabBar.id = '__roleSubTabBar';
              subTabBar.style.cssText = 'display:flex;gap:0;overflow-x:auto;background:#1b1b1b;border-bottom:1px solid #111;flex:0 0 auto;';
              var listEl = document.createElement('div');
              listEl.id = '__rolePickList';
              listEl.style.cssText = 'flex:1;overflow:auto;padding:6px 8px;background:#161616;color:#bdbdbd;' +
                'font:12px/1.5 Consolas,Monaco,monospace;min-height:0;';
              // 注：「封装为步骤」按钮已整体移除（step 的唯一边界是「开始→停止」，由 Java 侧
              // RoleElementStepGenerator / snapWithAutoStep 生成），故这里不再创建任何 pkgBtn 占位元素。
              pageContent.appendChild(subTabBar);
              pageContent.appendChild(listEl);