(a) => { function __apply(){ try { window.__rolePickActive = true; if (window.__renderPicks) window.__renderPicks(); } catch(e){} }
  // 对齐 page.pause：页面未加载完成（document.readyState==='loading'）时不立即改 DOM，
  // 挂一次性 DOMContentLoaded 监听，待 DOM 就绪后再置激活态并渲染面板——避免对"还在加载/抖动"的页面做 evaluate
  // 触发 pickerEval 硬超时（日志里的 "pickerEval timed out"）与激活竞态；已就绪（interactive/complete）则立即执行，
  // 行为与先前一致。evaluate 因此立即返回（只注册监听），Java 侧 pickerEval 不再被加载中文档拖住。
  if (document.readyState === 'loading') { window.addEventListener('DOMContentLoaded', __apply, { once: true }); } else { __apply(); }
}
