// 把页面类/步骤代码与断言类代码在同一次 page.evaluate 内同步写入各自 Tab。
// 关键修复（SPA 实时生成不对称）：原先 fillCode 分两次独立 evaluate（先 FILL_CODE_JS 写步骤、再
// FILL_ASSERT_JS 写断言）。SPA / 后台 iframe 持续繁忙的页面，主线程事件循环被占用，两次 evaluate 之间
// 存在 hang 窗口——第一次（步骤）抢在 hang 前成功、第二次（断言）撞上软超时（2000ms）被丢弃，
// 表现为「步骤实时生成、断言不生成」。合并为单次 evaluate 后两步渲染同步执行、无中间窗口，
// 步骤与断言原子更新（要么一起成功、要么一起失败），消除不对称。
(a) => {
  window.__fillCodeTabs({ pageByPage: a.pageByPage, stepByPage: a.stepByPage, msg: a.msg });
  window.__fillAssertTabs({ assertByPage: a.assertByPage, msg: a.msg });
}
