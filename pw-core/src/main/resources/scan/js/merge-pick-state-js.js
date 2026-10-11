/*
 * 【只合并、不动激活态】的拾取态回灌（供"文档起始自举"使用）。
 *
 * 与 apply-pick-state-js.js 的区别（关键）：
 *   · 不置 window.__rolePickActive = false —— 自举发生在文档起始，拾取库随后才注入/激活，
 *     若在此处把 active 清掉，就会出现"新页面点不动 / 要再点一次开始"；
 *   · 不写 nls 反向表、不置 __applyingState —— 那些是"整份状态搬运"的语义，自举不需要。
 *
 * 合并口径（与 apply-pick-state-js.js 完全一致，避免两处行为分叉）：
 *   · __rolePicks   —— 按 mergeKey 取并集（本地在前：本页已拾的对象优先保留；Java 权威序号随后由
 *                      syncPanelToBrowser 的"保留更长"回灌补齐）；
 *   · __steps       —— 本页封存的 step 原样保留（不搬别页的 step，避免同一步骤在多页重复计数）；
 *   · __currentStep —— 本轮只认本页拾取的（不搬别页本轮，避免别页元素混入本页 step）；
 *   · __rolePickSeq —— 按并集后的最大序号垫高，保证本页新拾取的编号不与已拾元素冲突。
 */
(a) => {
  try {
    var s = JSON.parse(a.stateJson || '{}');
    function __mk(p) {
      try { return (typeof window.__mergeKey === 'function') ? window.__mergeKey(p) : (p && (p._sigKey || p._sig)); }
      catch (e) { return null; }
    }
    function __mergeByKey(base, add) {
      var out = [], seen = {}, i, k;
      for (i = 0; i < (base || []).length; i++) { k = __mk(base[i]) || ('?b' + i); if (!seen[k]) { seen[k] = 1; out.push(base[i]); } }
      for (i = 0; i < (add || []).length; i++) { k = __mk(add[i]) || ('?a' + i); if (!seen[k]) { seen[k] = 1; out.push(add[i]); } }
      return out;
    }
    // 【归属必须明确】只合并【带 _pageClass】的条目：面板行的归属判据是
    //   p._pageClass || window.__rolePageName
    // 缺 _pageClass 的条目会被算进"当前文档的页名"下 —— 现场：LogonPage 上拾取的 i18n 元素
    // 在自举合并进新文档后，被显示到了新页的 Tab 里（"还没拾取呢，怎么有元素"）。
    var __incoming = (s.picks || []).filter(function (p) { return !!p && !!p._pageClass; });
    window.__rolePicks = __mergeByKey(window.__rolePicks || [], __incoming);
    window.__steps = window.__steps || [];
    window.__currentStep = window.__currentStep || [];
    (function () {
      var mx = 0;
      (window.__rolePicks || []).forEach(function (p) {
        (p && Array.isArray(p._pickNos) ? p._pickNos : []).forEach(function (n) { if (n > mx) mx = n; });
      });
      if (mx > (window.__rolePickSeq || 0)) window.__rolePickSeq = mx;
    })();
    try { if (window.__renderPicks) window.__renderPicks(); } catch (e) {}
  } catch (e) { /* 合并失败无害：导航事件与主循环回灌仍会兜底 */ }
}
