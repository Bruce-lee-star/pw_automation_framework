(a) => { function __apply(){ try {
  function __dec(s){ var b=s.replace(/-/g, '+').replace(/_/g, '/'); return decodeURIComponent(escape(atob(b))); }
  var steps = JSON.parse(__dec(a[0]));
  var nls = JSON.parse(__dec(a[1]) || '{}');
  var files = JSON.parse(__dec(a[2]) || '[]');
  // nls 反向表：供弹窗页点击时反查 key（i18n 元素的定位器反向映射），与原 applyPickState 口径一致。
  window.__nlsFiles = files;
  window.__nlsReverse = (nls && nls.exact) ? nls.exact : ((nls && nls.templates) ? {} : (nls || {}));
  window.__nlsTemplates = (nls && nls.templates) ? nls.templates : [];
  // 步骤清单：本地非空优先合并——本页已有步骤（进行中当轮）则保留，只在全新文档（本地为空）时采用 Java 权威封存态，
  // 从而把会话内容带到新页展示，且绝不覆盖本页已拾步骤（修复"回灌把本页 step 清掉 / 第二个新页面没有第二个页面的步骤"）。
  var __localSteps = window.__steps || [];
  window.__steps = (__localSteps.length ? __localSteps : (steps instanceof Array ? steps : []));
  if (window.__renderSteps) window.__renderSteps();
} catch(e){} }
  if (document.readyState === 'loading') { window.addEventListener('DOMContentLoaded', __apply, { once: true }); } else { __apply(); }
}
