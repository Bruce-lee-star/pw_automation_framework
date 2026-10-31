// 【ESC 热键停止拾取 —— 唯一对 CDP inspect 浮层免疫的控制通道】
//
// 背景（经 CDP 探针实测确证，勿凭直觉改动）：
//   在 Overlay.setInspectMode(searchForNode) 生效期间，被测页 frame 树内的【指针事件一律不可达】：
//     · 主文档的 mousemove 收不到（计数 0）；
//     · 主文档的 click 被浮层吞掉，元素自身的 onclick 不触发；
//     · 子 iframe 也不免疫——父 frame 的 inspect 会穿透进去，连 iframe 内部的按钮都能被抓到
//       （inspectNodeRequested 上报的是那个 button 本身）。
//   因此"悬浮按钮 / iframe 控件 / hover 自动暂停 inspect"这几类方案在机制上均不成立。
//
//   唯独【键盘事件】不受 inspect 浮层拦截（探针实测 keydown 正常抵达文档）。
//   故 ESC 是本拾取器唯一确定可达、零结构风险的"停止"控制点，等同于点面板的「⏹ 停止拾取」。
//
// 实现要点：
//   1) 幂等安装：多次 evaluate 也只会挂一个监听（__roleStopHotkey 守卫），避免重复发 stop。
//   2) 仅在拾取进行中生效：以 window.__rolePickActive 为闸（由 set-pick-active-and-render-js 置 true、
//      停止时置 false），拾取结束/未开始时按 ESC 不会被误当成停止命令。
//   3) 命令投递复用面板同一套通道：优先 __rolePickerCmd 绑定，未就绪则回退 __panelCmds 队列，保证不丢。
(function () {
  try {
    if (window.__roleStopHotkey) return;
    window.__roleStopHotkey = function (e) {
      try {
        if (e.keyCode !== 27 && e.key !== 'Escape' && e.key !== 'Esc') return;
        if (!window.__rolePickActive) return;   // 未在拾取中：放行，不干扰页面自身快捷键
        if (typeof window.__rolePickerCmd === 'function') {
          window.__rolePickerCmd('stop');
        } else {
          window.__panelCmds = window.__panelCmds || [];
          window.__panelCmds.push('stop');
        }
        e.preventDefault();
        e.stopPropagation();
      } catch (err) {}
    };
    // 捕获阶段安装：先于被测页自身处理器拿到按键，避免被页面 stopPropagation 吞掉。
    document.addEventListener('keydown', window.__roleStopHotkey, true);
  } catch (e) {}
}());
