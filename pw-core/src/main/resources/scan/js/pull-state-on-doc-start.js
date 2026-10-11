/*
 * 【文档起始自举】随 context 级 addInitScript 注入：顶层文档在文档起始主动向 Java 要一次权威拾取态
 * （window.__rolePickPullState 由 exposeBinding 暴露），由 Java 合并回灌。
 *
 * 为何必须自举：回灌原先挂在 onFrameNavigated 之后，该事件在"重定向链 + 广告/跟踪资源密集"的页面上
 * 实测可迟到 10~20s。文档自己开口要，就不依赖任何导航事件 —— 跨源新页同样适用（binding 是 context 级）。
 *
 * 【为什么按 URL 幂等、而不是"每文档只拉一次"】
 *   现场：新页出现 40ms 就自举成功（日志 [picker][bootstrap] 16 pick(s)），但页面上仍是空的，
 *   十几秒后才由兜底链慢慢补回 —— 因为该页是重定向链 / SPA pushState：第一次拉取落在中间文档（或中间 URL），
 *   其后文档被替换或 URL 变化，成果随之丢失；而 init 脚本对"同文档内的 URL 变化"不会再跑一次。
 *   故此处改为：以【上次拉取时的 URL】为幂等键，URL 变化即再拉一次（合并幂等、不会重复）。
 *
 * 约束：
 *   ① 仅顶层文档（iframe 数量多且从不承载面板，避免每个广告 iframe 都来拉）；
 *   ② 纯 JS 侧比对 location.href，不发起任何 Playwright 往返（零成本）；
 *   ③ 绑定可能尚未就绪（绑定注册晚于首个文档起始）⇒ 失败后以 200ms 间隔重试至多 15 次（3s）；
 *   ④ URL 跟随仅做有限次（500ms × 20 = 10s）后自动停止，避免长生命周期页面上的无谓定时器；
 *   ⑤ 只做"合并"，绝不置 __rolePickActive=false（见 merge-pick-state-js.js），不会打断拾取。
 */
(function () {
  try {
    if (window.top !== window.self) return;   // ① 仅顶层

    function __url() {
      try { return location.href || ''; } catch (e) { return ''; }
    }
    function __pullOnce() {
      try {
        if (typeof window.__rolePullPickState === 'function') {
          window.__rolePullPickState();
          window.__rolePulledStateUrl = __url();   // 记录"这次是对哪个 URL 拉的"
          return true;
        }
      } catch (e) { /* 绑定调用失败：走重试 */ }
      return false;
    }

    // 首次：立即拉；绑定未就绪则短间隔重试（③）
    if (!__pullOnce()) {
      var __n = 0;
      var __t = setInterval(function () {
        __n++;
        if (__pullOnce() || __n > 15) clearInterval(__t);
      }, 200);
    }

    // URL 跟随（④）：pushState / 重定向后同文档内的 URL 变化 ⇒ 再拉一次
    var __c = 0;
    var __u = setInterval(function () {
      __c++;
      try {
        var cur = __url();
        if (cur && cur !== window.__rolePulledStateUrl) __pullOnce();
      } catch (e) { /* 忽略：下一拍再试 */ }
      if (__c > 20) clearInterval(__u);
    }, 500);
  } catch (e) { /* 自举失败无害：既有导航事件 / 主循环回灌仍会兜底 */ }
})();
