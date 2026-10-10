            (function() {
              // 关键修复：无条件移除监听并置位（不再因 __rolePickActive 已为 false 而早退）。
              // 早退会在"Java 端 active[0] 与浏览器端 __rolePickActive 因竞态不一致"时，
              // 导致应停止的页面监听残留、状态错乱，进而出现"停止后再开始拾取不了"。
              // removeEventListener 对同一函数引用幂等，重复调用安全。
              document.removeEventListener('click', window.__rolePickClick, true);
              document.removeEventListener('mousemove', window.__rolePickMove, true);
              document.removeEventListener('keydown', window.__rolePickKey, true);
              document.removeEventListener('focusin', window.__rolePickFocus, true);
              document.removeEventListener('scroll', window.__rolePickScroll, true);
              window.__rolePickActive = false;
              // 复位面板切换控件的"乐观意图"位：避免上一轮残留的 __rolePickWanted 让下一轮按钮的 willStart
              // 计算误判、发出错误命令（关键修复"停止后再点开始却拾取不了 / 二轮停止不了"的边界）。
              try { window.__rolePickWanted = false; } catch (e) {}
              try { if (window.__roleRefreshToggle) window.__roleRefreshToggle(); } catch (e) {}
              // 手动模式收尾（▶开始→点元素→⏹停止）：所有拾取元素封装为【一个步骤】(由 Java 端 snapWithAutoStep 兜底)，
              // 此处只做面板状态收尾：清空当前选择集与序号计数器，并把「页面元素 List」每行序号前缀重置为 [-]。
              // 【每次「开始→停止」自动封存 1 个 step（跨轮累积、内容不跨轮）】
              // 把本轮 __currentStep 追加进 __steps 后再清空 __currentStep：每轮 = 一个独立 step，
              // 且 __steps 不再清空 ⇒ 跨轮累积为 step1/step2/…；step 内容只含本轮拾取（__currentStep 只装本轮）。
              // 旧实现每轮清空 __steps，靠 Java 端 snapWithAutoStep 把「全部 entries」兜底成 1 步 ——
              // 那正是"整个过程只有 1 个 step 且内容跨轮累积"的来源。
              try {
                var __cs = window.__currentStep;
                if (Array.isArray(__cs) && __cs.length) {
                  window.__steps = window.__steps || [];
                  var __pc = '';
                  try { __pc = window.__roleActivePageClass || (__cs[0] && (__cs[0]._pageClass || __cs[0].pageClass)) || ''; } catch (e2) {}
                  window.__steps.push({ pageClass: __pc, picks: __cs.slice() });
                }
              } catch (e) {}
              window.__currentStep = null;
              // 仅重置本轮动作序号计数器（下一轮 index 从 1 连续编号）；__steps 保留，不丢已封存步骤。
              try { window.__rolePickSeq = 0; } catch (e) {}
              // 【修复"新一轮首号不归 1"】停止收尾时一并清空"只增不回退"的最大动作号计数器，
              // 使下一轮 ▶ 拾取的 index 重新从 1 连续（与下方"下一轮 index 重新从 1 连续编号"语义一致）。
              try { window.__roleMaxNo = 0; } catch (e) {}
              // 停止收尾：清空本轮动作序号（令下一轮「开始拾取」的序号从 1 重新连续）。
              // 注：面板序号体系已整体移除，_seqStale / _manualPick 两个逐元素标志已无任何读取点，一并删除。
              try {
                var _ps = window.__rolePicks || [];
                for (var _pi = 0; _pi < _ps.length; _pi++) {
                  try { delete _ps[_pi]._pickNos; } catch (e) {}
                  try { _ps[_pi]._pickSeq = 0; } catch (e) {}
                }
                if (typeof window.__renderPicks === 'function') window.__renderPicks();
                if (typeof window.refreshSelInfo === 'function') window.refreshSelInfo();
              } catch (e) {}
              // 收起实时悬停高亮框
              try { var __hb = document.getElementById('__roleHoverBox'); if (__hb) __hb.style.display = 'none'; } catch (e) {}
              // 清除所有 frame（含主文档 + 各层 iframe）内元素的拾取描边（outline）与聚焦边框，
              // 避免"取消拾取 / 停止拾取后，iframe 内元素的蓝色/黄色边框未消失"的残留。
              try {
                (function __stopAll(win) {
                  if (!win || !win.document) return;
                  var d = win.document;
                  // 【关键修复"停止拾取后 iframe 内仍可点击拾取"】
                  // 原 stop 只移除当前 frame（顶层）的 document 监听，iframe 内自己的 document 监听未移除，
                  // 停止拾取后 iframe 内仍能点击拾取、边框也仍会高亮。这里在遍历每个 frame 时，
                  // 一并移除该 frame 的拾取监听，并把 __rolePickActive 置 false，彻底关闭所有 frame 的拾取态。
                  var rl = window.__rolePickClick, rm = window.__rolePickMove, rk = window.__rolePickKey,
                      rf = window.__rolePickFocus, rs = window.__rolePickScroll,
                      rp = window.__rolePickPointer, rpo = window.__rolePickOver;
                  d.removeEventListener('click', rl, true);
                  d.removeEventListener('mousemove', rm, true);
                  d.removeEventListener('keydown', rk, true);
                  d.removeEventListener('focusin', rf, true);
                  d.removeEventListener('scroll', rs, true);
                  if (rp) d.removeEventListener('pointerdown', rp, true);
                  if (rpo) d.removeEventListener('mouseover', rpo, true);
                  try { if (window.__rolePickLeave) d.removeEventListener('mouseleave', window.__rolePickLeave, false); } catch (_) {}
                  try { win.__rolePickActive = false; } catch (_) {}
                  var nodes = d.querySelectorAll('[_rolepick_outline]');
                  for (var i = 0; i < nodes.length; i++) {
                    try { nodes[i].style.outline = ''; nodes[i].style.outlineOffset = ''; nodes[i].removeAttribute('_rolepick_outline'); } catch (_) {}
                  }
                  // 兜底：清掉仍带显式拾取色描边的元素（部分老路径未打标记属性）
                  var all = d.querySelectorAll('*');
                  for (var j = 0; j < all.length; j++) {
                    var s = all[j].style;
                    if (s && (s.outline.indexOf('ffeb3b') >= 0 || s.outline.indexOf('29b6f6') >= 0 || s.outline.indexOf('ff9800') >= 0)) {
                      s.outline = ''; s.outlineOffset = '';
                    }
                  }
                  // 隐藏当前 frame 的实时悬停高亮框（青色 #29b6f6 边框）
                  try { var hb = d.getElementById('__roleHoverBox'); if (hb) hb.style.display = 'none'; } catch (_) {}
                  var fr = win.frames || [];
                  for (var k = 0; k < fr.length; k++) { try { __stopAll(fr[k]); } catch (_) {} }
                })(window);
              } catch (e) {}
            })();