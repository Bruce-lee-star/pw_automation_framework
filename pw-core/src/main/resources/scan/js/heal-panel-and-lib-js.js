(function(){try{
  var hasPanel = !!document.getElementById('__rolePanel');
  var hasLib = (typeof window.__recordPick === 'function');
  if (!hasLib) return 'none';          /* 库也没了：Java 侧负责重挂 START_SCRIPT + 重建面板 */
  if (!hasPanel) {
    /* 库在、面板 DOM 丢失：走门面幂等重建（含重渲染），不在此处重复实现构建逻辑 */
    if (typeof window.__roleSpaHeal === 'function') { window.__roleSpaHeal(); }
    else if (typeof window.__roleEnsurePanel === 'function') { window.__roleEnsurePanel(); }
    else return 'none';
    return 'panel';
  }
  return '';
}catch(e){ return 'err'; }})()
