'use strict';
// 轻量 DOM mock + 从真实源码抽取 __raiseToInteractive 的回归单测。
// 目的：锁定"上溯到根、命中可交互祖先、无祖先则保持原节点"的语义，
//       尤其验证"深层嵌套（>旧 5 层上限）仍能上溯到 button/link"，对齐 page.pause Inspector。
// 运行：node pw-core/src/test/resources/scan/picker-raise.test.js
const fs = require('fs');
const path = require('path');
const vm = require('vm');

// ---- 1) 从真实源码抽取 __raiseToInteractive（保证测的是生产代码，无实现漂移） ----
const SRC = path.resolve(__dirname, '../../../main/resources/scan/js/picker-core-b1.js');
const code = fs.readFileSync(SRC, 'utf8');
const fnText = extractFunction(code, '__raiseToInteractive');
if (!fnText) { console.error('FAIL: 未从源码抽取到 __raiseToInteractive'); process.exit(1); }

// ---- 2) 忠实于源码语义的 getRole / resolveLabel / INTERACTIVE_ROLES ----
const INTERACTIVE_ROLES = { button:1, link:1, checkbox:1, radio:1, tab:1,
  menuitem:1, menuitemcheckbox:1, menuitemradio:1, option:1, 'switch':1,
  textbox:1, combobox:1, listbox:1, searchbox:1, slider:1, spinbutton:1, treeitem:1 };

function getRole(node) {
  if (!node) return '';
  var r = node.getAttribute ? node.getAttribute('role') : (node._role || null);
  if (r) return r;
  var tag = (node.tagName || '').toLowerCase();
  if (tag === 'a') return 'link';
  if (tag === 'button') return 'button';
  if (tag === 'input') {
    var t = (node.type || '').toLowerCase();
    if (t === 'checkbox') return 'checkbox';
    if (t === 'radio') return 'radio';
    if (t === 'search') return 'searchbox';
    return 'textbox';
  }
  if (tag === 'select') return 'combobox';
  if (tag === 'textarea') return 'textbox';
  return '';
}

function resolveLabel(node) {
  if (!node) return node;
  if ((node.tagName || '').toLowerCase() === 'label') {
    if (node.control) return node.control;
    if (node.htmlFor && node._owner) {
      var c = node._owner.getElementById(node.htmlFor);
      if (c) return c;
    }
  }
  return node;
}

function makeEl(opts) {
  opts = opts || {};
  return {
    tagName: (opts.tag || 'div').toUpperCase(),
    type: opts.type || '',
    parentElement: opts.parent || null,
    _role: opts.role || null,
    control: opts.control || null,
    htmlFor: opts.htmlFor || null,
    _owner: opts.owner || { getElementById: function(){ return null; } },
    getAttribute: function(n){ if (n === 'role') return this._role || null; if (n === 'type') return this.type || null; return null; }
  };
}

// ---- 3) 在隔离上下文执行抽取出的函数（仅依赖上面的 getRole/resolveLabel/INTERACTIVE_ROLES） ----
const sandbox = { getRole, resolveLabel, INTERACTIVE_ROLES, console, globalThis: {} };
vm.createContext(sandbox);
vm.runInContext(fnText + '\nglobalThis.__raiseToInteractive = __raiseToInteractive;', sandbox);
const raise = sandbox.globalThis.__raiseToInteractive;

// ---- 4) 断言 ----
let pass = 0, fail = 0;
function assert(name, cond) {
  if (cond) { pass++; console.log('  PASS ' + name); }
  else { fail++; console.log('  FAIL ' + name); }
}

// 场景1：span 直接包在 button 内（深度1）→ button
(function () {
  var btn = makeEl({ tag: 'button' });
  var span = makeEl({ tag: 'span', parent: btn });
  assert('浅层 span→button', raise(span) === btn);
})();

// 场景2：span 在第 8 层嵌套后才是 button（原 5 层上限会失败）→ button  ★核心回归
(function () {
  var btn = makeEl({ tag: 'button' });
  var cur = btn;
  for (var i = 0; i < 8; i++) { var d = makeEl({ tag: 'div', parent: cur }); cur = d; }
  var span = makeEl({ tag: 'span', parent: cur });
  assert('深层(8层) span→button（突破旧 5 层上限）', raise(span) === btn);
})();

// 场景3：纯结构节点、无任何可交互祖先 → 返回 null（调用方保持原节点，不偏离 Inspector）
(function () {
  var root = makeEl({ tag: 'div' });
  var cur = root;
  for (var i = 0; i < 10; i++) { var d = makeEl({ tag: 'div', parent: cur }); cur = d; }
  var leaf = makeEl({ tag: 'span', parent: cur });
  assert('无交互祖先→null', raise(leaf) === null);
})();

// 场景4：直接点击 button → button
(function () {
  var btn = makeEl({ tag: 'button' });
  assert('直接 button→button', raise(btn) === btn);
})();

// 场景5：label 包 checkbox → resolveLabel 指向 checkbox（可交互）
(function () {
  var cb = makeEl({ tag: 'input', type: 'checkbox' });
  var label = makeEl({ tag: 'label', control: cb });
  assert('label→checkbox', raise(label) === cb);
})();

// 场景6：点击 role=link 容器内的文本 span → 该容器
(function () {
  var link = makeEl({ tag: 'div', role: 'link' });
  var span = makeEl({ tag: 'span', parent: link });
  assert('span→role=link 容器', raise(span) === link);
})();

console.log('\n结果: ' + pass + ' passed, ' + fail + ' failed');
process.exit(fail ? 1 : 0);

// 简单花括号匹配抽取函数文本（被测函数体不含字符串字面量中的花括号，安全）
function extractFunction(src, name) {
  var idx = src.indexOf('function ' + name + '(');
  if (idx < 0) return null;
  var i = src.indexOf('{', idx);
  if (i < 0) return null;
  var depth = 0, j = i;
  for (; j < src.length; j++) {
    var ch = src[j];
    if (ch === '{') depth++;
    else if (ch === '}') { depth--; if (depth === 0) { j++; break; } }
  }
  return src.slice(idx, j);
}
