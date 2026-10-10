(function(){ /* 【修复"打开新页面/整页导航后没有重新创建面板"】
         原来：__rolePanelEnabled==='0' 就无条件 return。'0' 是"用户关闭过面板"的【粘性墓碑】
         （close-panel-js.js 写入，并用 __rolePanelForce=false 显式清掉兜底），于是该 origin 上
         后续【每一个新文档】都在这里早退 ⇒ 下面的 __rolePanelForce=true 永不置位 ⇒
         panel-core-a.js 的 UI 门禁（'1' || __rolePanelForce）双假 ⇒ 面板永不重建（表现为
         "打开新页面，没有重新创建面板"，且此后任何导航都恢复不了）。
         现改为：墓碑不压过【进行中的拾取会话】—— __rolePickSessionOn==='1'（▶ 开始拾取期间由
         门控脚本/start 写入）即视为必须重建面板，同时把 '1' 写回 localStorage 修复该墓碑。
         浏览态（无会话）仍旧尊重墓碑，不会无故弹出面板。 */
      function __enableRolePanel(){
        try{localStorage.setItem('__rolePanelEnabled','1');}catch(e){}
        try{window.__rolePanelForce=true;}catch(e){}
        try{var n=localStorage.getItem('__rolePageName'); if(n) window.__rolePageName=n;}catch(e){}
      }
      var __panelFlag = null, __sessionOn = null, __winSession = false;
      try { __panelFlag = localStorage.getItem('__rolePanelEnabled'); } catch(e) {}
      try { __sessionOn = localStorage.getItem('__rolePickSessionOn'); } catch(e) {}
      try { __winSession = !!window.__rolePickSessionOn; } catch(e) {}
      // 无墓碑（新 origin / 首次打开）：直接启用，行为与先前一致。
      if (__panelFlag !== '0') { __enableRolePanel(); return; }
      // 有墓碑'0'但【此刻】已能确认拾取会话激活（localStorage 或 window 级开关）→ 强制重建，覆盖粘性墓碑。
      if (__sessionOn === '1' || __winSession) { __enableRolePanel(); return; }
      // 墓碑'0'且【此刻】会话信号尚不可见：不立即早退，等 DOM 就绪后再判定一次——
      // 门控脚本(gate-init.js)在文档起始稍后（本脚本之后）会把 window.__rolePickSessionOn 置位
      // （同源导航读得到 localStorage 会话标记即置位）。届时若会话确在拾取中则重建面板，否则尊重墓碑。
      // 对齐 page.pause：跨文档自愈由浏览器侧一次性监听完成，不再依赖 Java 轮询补灌（修复"打开新页面没有面板"）。
      function __rolePanelRetryOnReady(){
        try {
          var __win2 = false; try { __win2 = !!window.__rolePickSessionOn; } catch(e) {}
          var __ls2 = null; try { __ls2 = localStorage.getItem('__rolePickSessionOn'); } catch(e) {}
          if (__win2 || __ls2 === '1') {
            __enableRolePanel();
            try { if (typeof window.__roleEnsurePanel === 'function') window.__roleEnsurePanel(); } catch(e) {}
            try { if (window.__renderPicks) window.__renderPicks(); } catch(e) {}
          }
        } catch(e) {}
      }
      if (document.readyState === 'loading') { window.addEventListener('DOMContentLoaded', __rolePanelRetryOnReady, { once: true }); }
      else { __rolePanelRetryOnReady(); }
    })();(function(){ if (window.__mergeKey) return; window.__mergeKey = function(p){ try{   if (!p) return '';   if (p._sigKey) return p._sigKey;   if (typeof window.__sigKey === 'function') return window.__sigKey(p);   var pageKey = p._pageClass || '';   if (!pageKey) { try { pageKey = (location.origin||'') + (location.pathname||''); } catch(e){} }   return JSON.stringify([p._sig || '', pageKey]); }catch(e){ return ''; } }; })();