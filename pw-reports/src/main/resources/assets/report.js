/* ============================================================
 * Serenity 增强报告模板 · 共享脚本 v4
 *
 * 【稳定性设计】
 *  所有交互均不依赖共享模块：文件选择用 <label for> 原生触发 + 内联 onchange，
 *  筛选/切换用内联 onchange，避免旧版脚本被缓存导致整页交互失效。
 *
 * 【业务语义】
 *  ① 自动化执行失败 → 人工上传截图复核 → 可标记「复核通过 / 仍失败」
 *  ② 手工测试用例（类型=手工）→ 上传截图 → 可标记「复核通过 / 仍失败」
 *
 * 【后端对接】
 *  截图上传：POST /api/reports/{scenarioId}/screenshots (multipart/form-data)
 *  人工结论：PATCH /api/reports/{scenarioId} { manualVerdict, caseType }
 *  Trace：reportOptions.traceViewerBaseUrl + testOutcome.traceZip
 * ============================================================ */
const ReportState = (function () {
  const KEY = 'serenity-template-state';
  let state;
  try { state = JSON.parse(localStorage.getItem(KEY) || '{}'); } catch (e) { state = {}; }
  state.scenarios = state.scenarios || {};   // { id: {manual, baseAuto, verdict, tester, env, note} }
  state.shots = state.shots || {};           // { scenarioId: [{id,name,size,type,verdict}] }
  state.stepShots = state.stepShots || {};   // { stepId: [...] }

  function save() { try { localStorage.setItem(KEY, JSON.stringify(state)); } catch (e) {} }
  function uid() { return 'f' + Date.now().toString(36) + Math.random().toString(36).slice(2, 7); }

  /* ---------- 结论 / 执行记录 ----------
     注：用例类型（自动化 / 手工）由测试代码或配置决定，报告只读展示，
     不提供切换接口。人工可编辑的只有复核结论与手工执行记录。 */
  function meta(id) { return state.scenarios[id] || {}; }
  function patch(id, obj) {
    const s = state.scenarios[id] || (state.scenarios[id] = {});
    Object.assign(s, obj); save();
  }
  function setVerdict(id, v) { patch(id, { verdict: v }); }

  /* ---------- 截图 ---------- */
  function add(bucket, ownerId, fileList) {
    const arr = state[bucket][ownerId] || (state[bucket][ownerId] = []);
    [].forEach.call(fileList || [], f => arr.push({
      id: uid(), name: f.name, size: f.size, type: f.type || 'file', verdict: 'todo'
    }));
    save(); return arr;
  }
  function list(bucket, id) { return state[bucket][id] || []; }
  function remove(bucket, id, fid) {
    state[bucket][id] = list(bucket, id).filter(x => x.id !== fid); save();
  }
  function setShotVerdict(bucket, id, fid, v) {
    const f = list(bucket, id).find(x => x.id === fid);
    if (f) { f.verdict = v; save(); }
  }
  function shotStat(bucket, id) {
    const a = list(bucket, id);
    return { total: a.length, pass: a.filter(x => x.verdict === 'pass').length,
             fail: a.filter(x => x.verdict === 'fail').length, todo: a.filter(x => x.verdict === 'todo').length };
  }

  const VERDICTS = { todo: '待确认', pass: '复核通过', fail: '仍失败' };
  function verdictSelect(bucket, ownerId, file, after) {
    const sel = document.createElement('select');
    sel.className = 'v-sel ' + file.verdict;
    Object.keys(VERDICTS).forEach(k => {
      const o = document.createElement('option');
      o.value = k; o.textContent = VERDICTS[k];
      if (file.verdict === k) o.selected = true;
      sel.appendChild(o);
    });
    sel.onchange = e => { setShotVerdict(bucket, ownerId, file.id, e.target.value); sel.className = 'v-sel ' + e.target.value; after && after(); };
    return sel;
  }

  function renderShots(container, bucket, ownerId, after) {
    if (!container) return;
    const shots = list(bucket, ownerId);
    container.innerHTML = '';
    if (!shots.length) return;
    const wrap = document.createElement('div'); wrap.className = 'shot-grid';
    shots.forEach(s => {
      const box = document.createElement('div');
      box.className = 'shot' + (s.type.indexOf('image') === 0 ? ' img' : '');
      const name = document.createElement('div');
      name.className = 'fname'; name.title = s.name; name.textContent = s.name;
      const meta2 = document.createElement('div'); meta2.className = 'smeta';
      const size = document.createElement('span'); size.textContent = fmtSize(s.size);
      const del = document.createElement('button');
      del.className = 'x'; del.title = '删除'; del.textContent = '✕';
      del.onclick = () => { remove(bucket, ownerId, s.id); renderShots(container, bucket, ownerId, after); after && after(); };
      meta2.append(size, del);
      box.append(name, meta2, verdictSelect(bucket, ownerId, s, after));
      wrap.appendChild(box);
    });
    container.appendChild(wrap);
  }

  function fmtSize(b) { return (b / 1024).toFixed(1) + ' KB'; }

  /* ---------- 文件选择（内联 onchange 调用，无 programmatic click） ---------- */
  function onFiles(input, bucket, ownerId, after) {
    if (input.files && input.files.length) add(bucket, ownerId, input.files);
    input.value = '';
    after && after();
  }
  /* ---------- 拖拽上传 ---------- */
  function bindDrop(zone, bucket, ownerIdGetter, after) {
    if (!zone) return;
    zone.ondragover = e => { e.preventDefault(); zone.classList.add('dragover'); };
    zone.ondragleave = () => zone.classList.remove('dragover');
    zone.ondrop = e => {
      e.preventDefault(); zone.classList.remove('dragover');
      if (e.dataTransfer.files.length) add(bucket, ownerIdGetter(), e.dataTransfer.files);
      after && after();
    };
  }

  /* ---------- SVG 环形图：圆角端点 + 间隙 + 阴影 ---------- */
  const C = 2 * Math.PI * 48;
  function donut(el, segs, centerMain, centerSub) {
    if (!el) return;
    const total = segs.reduce((a, s) => a + (s.value || 0), 0) || 1;
    let acc = 0, arcs = '';
    segs.filter(s => s.value > 0).forEach(s => {
      const len = (s.value / total) * C;
      const draw = Math.max(len - 3, 1);          // 3px 间隙
      arcs += '<circle cx="60" cy="60" r="48" fill="none" stroke="' + s.color + '" stroke-width="17" ' +
              'stroke-linecap="round" stroke-dasharray="' + draw.toFixed(2) + ' ' + (C - draw).toFixed(2) + '" ' +
              'stroke-dashoffset="' + (-acc).toFixed(2) + '" transform="rotate(-90 60 60)"></circle>';
      acc += len;
    });
    el.innerHTML = '<svg viewBox="0 0 120 120" class="donut-svg">' + arcs + '</svg>' +
      '<div class="donut-center"><b>' + centerMain + '</b>' +
      (centerSub ? '<span>' + centerSub + '</span>' : '') + '</div>';
  }

  return { state, save, meta, patch, setVerdict,
           add, list, remove, setShotVerdict, shotStat, verdictSelect, renderShots,
           onFiles, bindDrop, donut, fmtSize, VERDICTS };
})();

/* ---------- 全局色彩（环形图 / 图例共用） ---------- */
const RC = { pass:'#16a34a', failure:'#dc2626', error:'#ea580c', primary:'#3b82f6',
             manual:'#0d9488', pending:'#d97706', compromised:'#9333ea', skipped:'#94a3b8' };