(a) => { function __apply(){ try { window.__rolePickActive = true; try { localStorage.setItem('__rolePickSessionOn','1'); } catch(e){} try { window.__rolePickSessionOn = true; } catch(e){} /* 【修复"到新页面面板是停止拾取状态"】跨源新页 localStorage 被 origin 隔离、读不到会话开关，panel-core-a.js 重建面板时 __roleMode 落回 'idle' ⇒ 按钮显示"▶ 开始拾取"（表现为拾取已停）。此处随"激活保活"一并把会话开关与模式置为 manual，并刷新按钮态，使新页"打开即显示 ⏹ 停止拾取"、与真实激活态一致。 */ try { window.__roleMode = 'manual'; window.__rolePickWanted = null; } catch(e){} try { if (window.__roleRefreshToggle) window.__roleRefreshToggle(); } catch(e){} if (window.__renderPicks) window.__renderPicks(); } catch(e){} }
  // 对齐 page.pause：页面未加载完成（document.readyState==='loading'）时不立即改 DOM，
  // 挂一次性 DOMContentLoaded 监听，待 DOM 就绪后再置激活态并渲染面板——避免对"还在加载/抖动"的页面做 evaluate
  // 触发 pickerEval 硬超时（日志里的 "pickerEval timed out"）与激活竞态；已就绪（interactive/complete）则立即执行，
  // 行为与先前一致。evaluate 因此立即返回（只注册监听），Java 侧 pickerEval 不再被加载中文档拖住。
  if (document.readyState === 'loading') { window.addEventListener('DOMContentLoaded', __apply, { once: true }); } else { __apply(); }
}
