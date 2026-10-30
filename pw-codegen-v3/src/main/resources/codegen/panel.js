(function () {
    'use strict';

    // 命令桥：面板自己把命令推入 outbox，由 Java 端轮询 panel.evaluate 取走并分发。
    // 不再依赖 Java 的 exposeBinding（在 --app= 持久上下文窗口里注入不稳定，会导致「点 Start 毫无反应」）。
    window.__codegenOutbox = [];
    window.__codegenCmd = function () {
        window.__codegenOutbox.push(Array.prototype.slice.call(arguments));
    };

    var entries = {};            // pickId -> entry（不能用 sigKey：不同容器里同 role+name 的元素 sigKey 相同）

    // 三栏并排（Page / Step / Assert），每栏独立编辑器：窗口最大化时三栏同步变宽。
    var EDITED_TABS = ['page', 'step', 'assert'];
    var editors = {};            // tab -> monaco editor
    var editorFallbacks = {};    // tab -> <pre> 降级容器
    var monacoOk = false;

    var MONACO_CDN = 'https://cdn.jsdelivr.net/npm/monaco-editor@0.45.0/min/vs';

    function initMonaco() {
        if (typeof require === 'undefined' || !require.config) { fallback(); return; }
        require.config({ paths: { vs: MONACO_CDN } });
        require(['vs/editor/editor.main'], function () {
            try {
                EDITED_TABS.forEach(function (tab) {
                    editors[tab] = monaco.editor.create(document.getElementById('editor-' + tab), {
                        language: 'java', theme: 'vs-dark', readOnly: true, automaticLayout: true
                    });
                });
                monacoOk = true;
                EDITED_TABS.forEach(renderCodeArea);
            } catch (e) { fallback(); }
        }, function () { fallback(); });
    }

    /** 动态注入 Monaco loader：不阻塞 load，CDN 不可达时直接降级（公司网常见）。 */
    function bootMonaco() {
        var s = document.createElement('script');
        s.src = MONACO_CDN + '/loader.js';
        s.onload = initMonaco;
        s.onerror = fallback;
        document.head.appendChild(s);
    }

    /** Monaco 不可用（公司网常见）时的降级：三栏都退回只读 <pre>，功能不受影响。 */
    function fallback() {
        EDITED_TABS.forEach(function (tab) {
            var ed = document.getElementById('editor-' + tab);
            if (ed) ed.style.display = 'none';
            var pre = document.getElementById('editor-fallback-' + tab);
            if (pre) pre.style.display = 'block';
            editorFallbacks[tab] = pre;
        });
        EDITED_TABS.forEach(renderCodeArea);
    }

    // 每栏各自缓存一份代码：Java 会连续推送 page/step/assert 三份，各栏互不覆盖。
    // 多页面场景下 Page 栏按页面拆成若干块（blocks），由该栏的页内切换器（subswitcher）切换。
    var codeByTab = {};   // tab -> { blocks: [{label, code}], current: <int> }

    /** 渲染某一栏的编辑器内容 + 该栏的页内切换器（三栏同时可见、互不影响）。 */
    function renderCodeArea(tab) {
        var data = codeByTab[tab];
        var block = (data && data.blocks && data.blocks[data.current]) ? data.blocks[data.current] : null;
        var code = block ? (block.code || '') : '';
        if (monacoOk && editors[tab]) {
            editors[tab].setValue(code);
        } else {
            var pre = editorFallbacks[tab] || document.getElementById('editor-fallback-' + tab);
            if (pre) pre.textContent = code;
        }
        renderSubSwitcher(tab);
    }

    /**
     * 某栏的页内切换器：仅当该栏块数 > 1 时显示（单页场景隐藏，避免空切换器）。
     * 点击某块即把该栏编辑器切到该块；当前选中态高亮。
     */
    function renderSubSwitcher(tab) {
        var data = codeByTab[tab];
        var container = document.getElementById('subswitcher-' + tab);
        if (!container) return;
        while (container.firstChild) container.removeChild(container.firstChild);
        if (!data || !data.blocks || data.blocks.length <= 1) {
            container.style.display = 'none';
            return;
        }
        container.style.display = 'flex';
        data.blocks.forEach(function (b, idx) {
            var btn = document.createElement('button');
            btn.className = 'subtab' + (idx === data.current ? ' active' : '');
            btn.textContent = b.label || ('#' + (idx + 1));
            btn.title = b.label || '';
            btn.onclick = function () {
                data.current = idx;
                renderCodeArea(tab);
            };
            container.appendChild(btn);
        });
    }

    // ---- 代码区页签（Page / Step / Assert 同一时刻只显示一个） ----
    var activeTab = 'page';

    /** 切换代码区页签：更新按钮与面板显隐，并渲染目标页签内容。 */
    function activateTab(tab) {
        if (!tab) return;
        activeTab = tab;
        var tabs = document.querySelectorAll('.code-tab');
        for (var i = 0; i < tabs.length; i++) {
            if (tabs[i].dataset.tab === tab) tabs[i].classList.add('active');
            else tabs[i].classList.remove('active');
        }
        EDITED_TABS.forEach(function (t) {
            var pane = document.getElementById('pane-' + t);
            if (pane) pane.classList.toggle('active', t === tab);
        });
        renderCodeArea(tab);
        // Monaco 在 display:none 的容器里创建时尺寸为 0，切回可见后必须手动 layout 才能正确排版。
        if (monacoOk && editors[tab]) { try { editors[tab].layout(); } catch (e) { } }
    }

    function initTabs() {
        var tabs = document.querySelectorAll('.code-tab');
        Array.prototype.forEach.call(tabs, function (btn) {
            btn.onclick = function () { activateTab(btn.dataset.tab); };
        });
    }

    // ---- 状态语义 ----
    // Java 推送的状态分两类：
    // ① 生命周期状态（ready/inspecting/paused/stopped）：决定「页面能否操作」、Start 按钮文案、提示条；
    // ② 瞬时消息（starting / grouped N / start failed: ... / bridge 相关）：只更新徽标文字，
    //    不得改动生命周期状态——否则点一次「标记为一组」就会把 paused 冲成非 paused，
    //    连 Start 按钮文案都退回 Start，用户会以为拾取态丢了。
    var LIFECYCLE_STATES = { ready: true, inspecting: true, paused: true, stopped: true };

    // 状态原值 → 徽标文案。Java 侧可能推送未登记的状态/错误文本，此时原样显示。
    var STATUS_LABELS = {
        'ready': '就绪',
        'starting': '启动中',
        'inspecting': '拾取中',
        'paused': '已暂停',
        'stopped': '已停止',
        'waiting for bridge': '等待通道',
        'bridge not ready (queued)': '通道未就绪（已排队）'
    };

    /** 工具栏下方的一行说明：直接回答用户最关心的问题「现在页面能不能操作」。 */
    var STATE_HINTS = {
        'ready': '就绪：点「开始拾取」后，页面照常操作，点到的元素会自动加入 Elements 并录制动作。',
        'inspecting': '拾取中：页面可正常操作。每次点击/输入都会录制，且点到的元素自动加入 Elements。',
        'paused': '已暂停拾取：页面可正常操作，动作仍会录制；点「开始拾取」可继续收集元素。',
        'stopped': '已停止：本轮录制结束，可在右侧查看生成的代码。'
    };

    var lastStatus = '';       // 最近一次「生命周期状态」原值（用于判断是否进入新一轮拾取）
    var lastRawStatus = '';    // 最近一次任意状态的原始值（含瞬时消息，仅用于通道就绪后的复位判断）
    /** 是否正处于拾取（inspect）态：决定 Start 按钮是「开始拾取」还是「暂停拾取」。 */
    var inspectActive = false;

    function setBadge(text, cls) {
        var el = document.getElementById('status');
        if (!el) return;
        el.textContent = text;
        el.className = 'status' + (cls ? ' ' + cls : '');
    }

    /** 步骤列表标题旁的录制指示：拾取态与暂停态都在录制，区别只在于是否顺带收集元素。 */
    function renderStepsHint(s) {
        var el = document.getElementById('steps-hint');
        if (!el) return;
        if (s === 'paused') {
            el.textContent = '● 录制中（不拾取）';
            el.className = 'steps-hint recording';
            el.hidden = false;
        } else if (s === 'inspecting') {
            el.textContent = '● 拾取 + 录制中';
            el.className = 'steps-hint recording';
            el.hidden = false;
        } else {
            el.textContent = '';
            el.hidden = true;
        }
    }

    // ---- Java → 面板 回调 ----
    window.__setStatus = function (raw) {
        var s = String(raw == null ? '' : raw);
        lastRawStatus = s;
        var lifecycle = LIFECYCLE_STATES[s] === true;

        // 徽标：生命周期状态沿用语义配色，瞬时消息原样显示（失败类给红色）。
        var badgeCls = lifecycle ? s : (s.indexOf('fail') >= 0 ? 'error' : '');
        setBadge(STATUS_LABELS[s] || s, badgeCls);

        // 瞬时消息到此为止：不触碰状态机、按钮文案与提示条（理由见上方 LIFECYCLE_STATES 注释）。
        if (!lifecycle) return;

        var hint = document.getElementById('state-hint');
        if (hint) {
            var hintText = STATE_HINTS[s] || '';
            hint.textContent = hintText;
            hint.className = 'state-hint'
                + (s === 'inspecting' ? ' inspecting' : (s === 'paused' ? ' paused' : ''));
            hint.hidden = !hintText;
        }
        inspectActive = (s === 'inspecting');
        var btn = document.getElementById('btn-start');
        if (btn) btn.textContent = inspectActive ? '停止拾取' : '开始拾取';
        renderStepsHint(s);
        // 只在「进入新一轮拾取」时清空步骤：从 paused 恢复拾取属于同一轮，不能清掉已录动作。
        if (s === 'inspecting' && lastStatus !== 'paused') {
            var sl = document.getElementById('steps-list');
            if (sl) sl.innerHTML = '';
            updateStepsCount();
        }
        lastStatus = s;
    };

    window.__onPicked = function (e) {
        var id = String(e.pickId);
        // 修订可能先于行渲染到达（拾取线程推行、生成线程复核）：以已记录的修订版为准，
        // 否则面板会一直显示被复核推翻的 role 策略，与代码 Tab 里的产物不一致。
        var row = entries[id] || e;
        entries[id] = row;
        var tbody = document.querySelector('#elements-table tbody');
        var tr = document.createElement('tr');
        tr.dataset.pickId = id;
        var idx = tbody.children.length + 1;
        tr.innerHTML = '<td><input type="checkbox" class="pick-check" /></td>'
            + '<td></td><td></td><td></td><td></td><td></td>';
        tr.children[1].textContent = idx;
        tr.children[2].textContent = row.name || '(no name)';
        tr.children[3].textContent = row.role;
        tr.children[4].textContent = row.selector;
        tr.children[5].textContent = row.score;
        if (row.revisionReason) markRevised(tr, row.revisionReason);
        tr.onclick = function (ev) {
            // 勾选框自身不触发「高亮」，否则勾选操作会被当成选择该行
            if (ev && ev.target && ev.target.classList && ev.target.classList.contains('pick-check')) return;
            selectRow(tr, id);
        };
        tbody.appendChild(tr);
        updateElementsCount();
    };

    /** 刷新 Elements 标题右侧的条目计数。 */
    function updateElementsCount() {
        var el = document.getElementById('elements-count');
        if (!el) return;
        var tbody = document.querySelector('#elements-table tbody');
        var n = tbody ? tbody.children.length : 0;
        el.textContent = n + ' 项';
    }

    /** 就地修订的可见标记：琥珀色左边框 + Locator 列的"复核后"角标（悬停给出原因）。 */
    function markRevised(tr, reason) {
        tr.classList.add('revised');
        tr.title = 'role 经 Playwright 引擎复核未通过（' + reason + '），已就地回退为 CSS 定位';
    }

    /**
     * Java 回执：该条目已被修订（role 引擎复核不通过 ⇒ 策略回退 CSS）。
     *
     * 就地更新同一行（按 pickId 定位），绝不新增行：用户看到的是"这一条的选择器变了"，
     * 而不是"又多了一条同名元素"。行尚未渲染时只记 entries（`__onPicked` 渲染时取修订版）。
     */
    window.__onPickedRevised = function (e) {
        var id = String(e.pickId);
        entries[id] = e;
        var tr = document.querySelector('#elements-table tbody tr[data-pick-id="' + id + '"]');
        if (!tr) return;
        tr.children[2].textContent = e.name || '(no name)';
        tr.children[3].textContent = e.role;
        tr.children[4].textContent = e.selector;
        tr.children[5].textContent = e.score;
        markRevised(tr, e.revisionReason || '引擎复核不唯一');
    };

    /** Java 回执：这些行已被标记为「一组元素」→ 生成一个 PageElementList。 */
    window.__onGrouped = function (ids) {
        var set = {};
        for (var i = 0; i < ids.length; i++) set[String(ids[i])] = true;
        var rows = document.querySelectorAll('#elements-table tbody tr');
        for (var j = 0; j < rows.length; j++) {
            var tr = rows[j];
            if (!set[tr.dataset.pickId]) continue;
            tr.classList.add('grouped');
            var cb = tr.querySelector('.pick-check');
            if (cb) cb.checked = false;
            var roleCell = tr.children[3];
            if (roleCell && roleCell.textContent.indexOf('[组]') < 0) {
                roleCell.textContent = '[组] ' + roleCell.textContent;
            }
        }
    };

    window.__onAction = function (a) {
        var list = document.getElementById('steps-list');
        if (!list) return;
        var li = document.createElement('li');
        // 动作类型由 Java 决定：click / fill / pick（拾取）。不再把未知类型硬映射成 click，
        // 否则「拾取」会显示成「点击」，与用户实际做的操作不符。
        var type = a.type || 'click';
        li.className = 'step-' + type;
        var label = a.label || '(element)';
        var text = (a.seq != null ? a.seq + '. ' : '') + '[' + type + '] ' + label;
        if (a.value) text += ' = "' + String(a.value).replace(/"/g, '\\"') + '"';
        li.textContent = text;
        list.appendChild(li);
        updateStepsCount();
        // 步骤实时跟随：每次新增都把容器滚到底部，始终显示最新一条。
        var body = document.getElementById('steps-body');
        if (body) body.scrollTop = body.scrollHeight;
    };

    /** 刷新 Recorded Steps 计数（标题右侧「N 步 · 自动跟随」）。 */
    function updateStepsCount() {
        var el = document.getElementById('steps-count');
        if (!el) return;
        var list = document.getElementById('steps-list');
        var n = list ? list.children.length : 0;
        el.textContent = n + ' 步 · 自动跟随';
    }

    /** Java 推送某个 Tab 的产物：缓存起来，仅当它正是当前 Tab 时才立即渲染（不抢焦点）。 */
    window.__onCode = function (p) {
        // 多页面场景：直接收到按页拆好的 blocks；旧单页推送（仅 code 字段）退化为单块。
        var blocks;
        if (p.blocks && p.blocks.length) {
            blocks = p.blocks.map(function (b) { return { label: b.label || '', code: b.code || '' }; });
        } else {
            blocks = [{ label: '', code: p.code || '' }];
        }
        // 实时增量生成会反复推送：保留用户当前选中的页面（只要其索引仍有效），避免被冲回第一页。
        var prev = codeByTab[p.tab];
        var current = 0;
        if (prev && prev.blocks && prev.current != null && prev.current < blocks.length) {
            current = prev.current;
        }
        codeByTab[p.tab] = { blocks: blocks, current: current };
        renderCodeArea(p.tab);
    };

    // ---- 交互：点击列表项 → 通知 Java 高亮被测页元素（类 page.pause） ----
    function selectRow(tr, pickId) {
        var rows = document.querySelectorAll('#elements-table tbody tr');
        for (var i = 0; i < rows.length; i++) rows[i].classList.remove('selected');
        tr.classList.add('selected');
        try { window.__codegenCmd('highlight', pickId); } catch (e) { /* binding 未就绪时忽略 */ }
    }

    /** 收集勾选的行 id（≥2 才允许成组）。 */
    function checkedIds() {
        var ids = [];
        var rows = document.querySelectorAll('#elements-table tbody tr');
        for (var i = 0; i < rows.length; i++) {
            var cb = rows[i].querySelector('.pick-check');
            if (cb && cb.checked) ids.push(rows[i].dataset.pickId);
        }
        return ids;
    }

    // ---- 面板 → Java 命令 ----
    /**
     * 统一出口：桥未就绪时把原因显示到状态徽标（而不是抛 TypeError 静默无反应），
     * 便于「点了没反应」时一眼看出是通道问题还是业务问题。
     */
    function send() {
        var args = Array.prototype.slice.call(arguments);
        if (typeof window.__codegenCmd !== 'function') {
            // 桥未就绪（窗口已可见但脚本尚未跑到这里，或换页瞬间）：把命令排队，桥就绪后自动补发一次，
            // 避免用户这一下点击被直接丢弃而以为「没反应」。
            if (typeof window.__setStatus === 'function') window.__setStatus('bridge not ready (queued)');
            if (window.console && console.error) console.error('__codegenCmd not ready, command queued: ' + args[0]);
            var queued = args;
            var tries = 0;
            var q = setInterval(function () {
                if (typeof window.__codegenCmd === 'function') {
                    clearInterval(q);
                    window.__codegenCmd.apply(null, queued);
                } else if (++tries > 300) { // 60s 仍不行则放弃，避免无限轮询
                    clearInterval(q);
                }
            }, 200);
            return;
        }
        try {
            window.__codegenCmd.apply(null, args);
        } catch (e) {
            if (typeof window.__setStatus === 'function') window.__setStatus('command failed: ' + (e && e.message));
        }
    }

    // 拾取开关：页面交互始终可用（拾取不再拦截鼠标事件），按钮只控制「是否把点到的元素加入 Elements」。
    // 拾取态点它 = 停止拾取（动作仍录制）；非拾取态点它 = 开始拾取（每次开始 = 产物里的一步）。
    document.getElementById('btn-start').onclick = function () {
        send(inspectActive ? 'pause' : 'start');
    };
    document.getElementById('btn-stop').onclick = function () { send('stop'); };
    document.getElementById('btn-group').onclick = function () {
        var ids = checkedIds();
        if (ids.length < 2) { window.alert('请至少勾选 2 个元素，再「标记为一组」。'); return; }
        send.apply(null, ['group'].concat(ids));
    };

    /**
     * 桥（Java 侧 exposeBinding 注入的 __codegenCmd）可能比面板窗口晚就绪：
     * 窗口一出现就能点，若此刻桥未建立，点击会静默丢失。这里先显示「等待通道」，
     * 一旦可用即恢复 ready，避免"点了没反应"却看不出原因。
     */
    function watchBridge() {
        if (typeof window.__codegenCmd === 'function') return;
        if (typeof window.__setStatus === 'function') window.__setStatus('waiting for bridge');
        var ticks = 0;
        var timer = setInterval(function () {
            if (typeof window.__codegenCmd === 'function') {
                clearInterval(timer);
                // 只在仍停留在「等待」态时复位，避免覆盖 Java 侧已经推送的 inspecting/stopped 等状态。
                // 注意要比较原始状态值：徽标文字已本地化为中文，不能用 textContent 反查。
                if (lastRawStatus === 'waiting for bridge'
                        && typeof window.__setStatus === 'function') {
                    window.__setStatus('ready');
                }
            } else if (++ticks > 300) { // 60s 后放弃提示，避免无限轮询
                clearInterval(timer);
            }
        }, 200);
    }

    watchBridge();
    initTabs();
    bootMonaco();
})();
