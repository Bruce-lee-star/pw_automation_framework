(a) => { try { window.__applyingState = true; } catch(e){} try { localStorage.setItem('__rolePanelEnabled','1'); } catch(e){} window.__nlsFiles = a.nlsFiles; var __o = JSON.parse(a.nlsReverseJson || '{}'); window.__nlsReverse = (__o && __o.exact) ? __o.exact : (__o && __o.templates ? {} : (__o || {})); window.__nlsTemplates = (__o && __o.templates) ? __o.templates : []; var s = JSON.parse(a.stateJson);
  /* ============================================================================
   * 【修复"回灌把本页已拾元素的勾选/本页 step 清掉"】
   *
   * 载荷 s 来自 readPickStateJson(打开器页) —— 即【打开器页的旧快照】，它与"接收文档此刻的真实状态"
   * 并不一致（URL change / 弹窗跟随时尤其明显：载荷常常只有一个别的页面的当轮元素，甚至为空）。
   * 旧实现整体覆盖三个数组，于是：
   *   ① window.__currentStep（面板勾选态【唯一】依据，见 panel-core-b.js 的 selSet）被载荷替换成
   *      1 个甚至 0 个 → 本页刚拾的元素全部变回未勾选
   *      ⇒ 现场表现「已拾取的元素，回灌回来，checkbox 没被选中」；
   *   ② window.__currentStep / window.__steps 被清 → 停止时该页封存不出任何 step
   *      ⇒ 现场表现「第二个新页面没有第二个页面的步骤」。
   *
   * 现改为【合并、不覆盖】：
   *   · __rolePicks —— 按 mergeKey 取并集（本地在前，冲突时保留本地对象；Java 权威序号随后由
   *     syncPanelToBrowser 的"保留更长"回灌补齐），保证载荷永远不会把本页已拾元素从列表里挤掉；
   *   · __currentStep / __steps —— 【本地非空优先】：本页已有拾取记录就原样保留（勾选与 step 都还在），
   *     只有本页确实为空（新文档首次回灌）才采用载荷，从而仍能把会话内容带到新页展示。
   * ============================================================================ */
  (function(){
    function __mk(p){ try { return (typeof window.__mergeKey === 'function') ? window.__mergeKey(p) : (p && (p._sigKey || p._sig)); } catch(e){ return null; } }
    function __mergeByKey(base, add){
      var out = [], seen = {}, i, k;
      for (i = 0; i < (base || []).length; i++) { k = __mk(base[i]) || ('?b' + i); if (!seen[k]) { seen[k] = 1; out.push(base[i]); } }
      for (i = 0; i < (add || []).length; i++) { k = __mk(add[i]) || ('?a' + i); if (!seen[k]) { seen[k] = 1; out.push(add[i]); } }
      return out;
    }
    var __localPicks = window.__rolePicks || [], __localSteps = window.__steps || [], __localCur = window.__currentStep || [];
    window.__rolePicks = __mergeByKey(__localPicks, s.picks || []);
    window.__steps = (__localSteps.length ? __localSteps : (s.steps || []));
    window.__currentStep = (__localCur.length ? __localCur : (s.currentStep || []));
  })(); window.__rolePickSigs = s.sigs || {}; (function(){ var mx=0; (window.__rolePicks||[]).forEach(function(p){   (p&&Array.isArray(p._pickNos)?p._pickNos:[]).forEach(function(n){ if(n>mx)mx=n; }); });   window.__rolePickSeq = mx; })(); window.__rolePickActive = false; try { if (window.__renderPicks) window.__renderPicks(); } catch(e){} try { setTimeout(function(){ window.__applyingState = false; }, 0); } catch(e){ window.__applyingState = false; }}