/*
 * 【文档起始自举】随 context 级 addInitScript 注入：每个新文档（顶层）在文档起始阶段主动向 Java 要一次
 * 权威拾取态（window.__rolePullPickState，经 exposeBinding 暴露），由 Java 合并回灌。
 *
 * 为何必须自举：回灌原先挂在 onFrameNavigated 之后，而该事件在"重定向链 + 重营销页（广告/跟踪资源）"
 * 上实测可迟到 10~20s（现场：新页面出现 12s 后才 applyPickState，用户看到"已拾元素迟迟不出现"）。
 * 文档自己开口要，就不依赖任何导航事件 —— 新页面【出生即带齐已拾元素】。
 *
 * 约束：
 *   ① 仅顶层文档自举（iframe 数量多且从不承载面板，避免每个 ad iframe 都来拉一次）；
 *   ② 每文档只拉一次（__rolePulledState 幂等）；
 *   ③ 绑定可能尚未就绪（绑定注册晚于首个文档起始），故失败后以 200ms 间隔重试至多 15 次（3s）；
 *   ④ 只做"合并"，绝不置 __rolePickActive=false（见 merge-pick-state-js.js），不会打断拾取。
 */
(function () {
  try {
    if (window.top !== window.self) return;          // ① 仅顶层
    if (window.__rolePulledState) return;            // ② 幂等
    window.__rolePulledState = 1;
    function __pullOnce() {
      try {
        if (typeof window.__rolePullPickState === 'function') {
          window.__rolePullPickState();
          return true;
        }
      } catch (e) { /* 绑定调用失败：走重试 */ }
      return false;
    }
    if (!__pullOnce()) {                             // ③ 绑定未就绪 → 短间隔重试
      var __n = 0;
      var __t = setInterval(function () {
        __n++;
        if (__pullOnce() || __n > 15) clearInterval(__t);
      }, 200);
    }
  } catch (e) { /* 自举失败无害：既有导航事件 / 主循环回灌仍会兜底 */ }
})();
