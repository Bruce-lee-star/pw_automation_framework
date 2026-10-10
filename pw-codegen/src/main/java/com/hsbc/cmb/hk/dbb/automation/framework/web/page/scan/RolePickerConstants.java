package com.hsbc.cmb.hk.dbb.automation.framework.web.page.scan;

/**
 * 扫描包（T5-1）统一常量归集。
 *
 * <p>把原本散落在各引擎/控制器里的 Java 侧魔法常量集中到此处，按职责分区，消除重复字面量、
 * 降低「Java 与面板 JS 契约键不一致」的风险：
 * <ul>
 *   <li>{@code CMD_*}   —— 面板 JS → Java 引擎命令名（{@code RolePickerCommandEngine#runPickerCommand} 的 cmd 取值）；</li>
 *   <li>{@code MODE_*}  —— 拾取模式枚举 {@link com.hsbc.cmb.hk.dbb.automation.framework.web.page.scan.model.PickMode} 对应的浏览器侧字符串
 *                          （等价于 {@code mode.name().toLowerCase()}，集中后改动一目了然）；</li>
 *   <li>{@code STATE_*} —— Java ↔ 面板 JS 通信的实参键，与对应脚本里的 {@code a.xxx} 字段严格对应；</li>
 *   <li>{@code TIMEOUT_*} —— 超时/去抖阈值（毫秒）；</li>
 *   <li>{@code CAP_*}   —— 容量上限。</li>
 * </ul>
 *
 * <p><b>行为保持零回归：</b>所有取值均与既有生产字节码逐字符等价，仅做「常量引用」替换，
 * 不改动任何运行时行为；契约键（STATE_/CMD_/MODE_）的值必须与面板 JS 侧保持一致。
 */
final class RolePickerConstants {

    private RolePickerConstants() {}

    // ───────────────────────── CMD_* : 面板 → 引擎命令名 ─────────────────────────
    static final String CMD_START = "start";
    static final String CMD_PACKAGE = "package";
    static final String CMD_REFRESH_CODE = "refreshCode";
    static final String CMD_STOP = "stop";
    static final String CMD_ABORT = "abort";
    static final String CMD_DONE = "done";
    /**
     * 面板「列表」开关的 JSON 对象命令 type（经 {@code __rolePickerCmd} 投递的对象命令，
     * 形如 {@code {type:'markList', list:<bool>, pick:{...}}}）。对象命令在 runPickerCommand 顶部拦截。
     */
    static final String CMD_TYPE_MARK_LIST = "markList";

    // ───────────────────────── MODE_* : PickMode → 浏览器侧字符串 ─────────────────────────
    //  取值必须与面板 JS 的比对字面量严格一致（同一 Java↔JS 契约）。
    //  当前契约面只有两个值：panel-core-a.js / picker-core-b2.js 全程比对 'idle' / 'manual'。
    //  历史（F-06）：原 scan_page / scan_region（下划线）与 JS 的 scanPage / scanRegion（驼峰）错位，
    //  导致窗口模式判定恒不成立 —— 该缺陷已随「扫描整页 / 区域扫描」两类命令整体移除而消失
    //  （见 RolePickerCommandEngine 中「已移除「扫描整页」与「区域扫描」」的说明），
    //  故此处不再涉及 scanPage / scanRegion。
    //  守卫方式（2026-10-10 核实）：
    //    ① JS 侧语法 + 运行期组合体：pw-core 的 validate 阶段
    //       （exec-maven-plugin → tools/validate_picker_js.js，含就地组合校验）；
    //    ② Java↔JS 字符串一致性：本包 RolePickerContractTest 逐键比对 JS 资源。
    //  注：历史上被引用的 RolePickerModeContractTest 并不存在（A-03 幻影守卫），已不再引用。
    static final String MODE_IDLE = "idle";
    static final String MODE_MANUAL = "manual";

    // ───────────────────────── STATE_* : Java↔JS 通信实参键（对应脚本 a.xxx） ─────────────────────────
    static final String STATE_KEY_MODE = "mode";
    static final String STATE_KEY_PAGE_NAME = "pageName";
    static final String STATE_KEY_CODE = "code";
    static final String STATE_KEY_MSG = "msg";
    static final String STATE_KEY_FILES = "files";
    static final String STATE_KEY_NLS = "nls";
    static final String STATE_KEY_AUTO_STEP_COUNT = "n";
    static final String STATE_KEY_STATE_JSON = "stateJson";

    // ───────────────────────── STRATEGY_* : 拾取策略名（Java↔JS 契约键） ─────────────────────────
    // 与面板 JS / 拾取数据 schema 中 strategy 字段取值严格对应；改值时须同步 JS 侧。
    static final String STRATEGY_TEXT = "text";
    static final String STRATEGY_ALT_TEXT = "altText";
    static final String STRATEGY_TITLE = "title";
    static final String STRATEGY_PLACEHOLDER = "placeholder";
    static final String STRATEGY_LABEL = "label";
    static final String STRATEGY_TEST_ID = "testid";
    static final String STRATEGY_ID = "id";
    static final String STRATEGY_CSS = "css";
    static final String STRATEGY_I18N = "i18n";

    // ───────────────────────── TIMEOUT_* : 超时/去抖（毫秒） ─────────────────────────
    /** 同一 page 跨域重注入去抖窗口：窗口内只真正重注入一次，避免重复渲染所有元素。 */
    static final long TIMEOUT_FORCE_START_DEBOUNCE_MS = 2000L;
    /** NLS 反向映射缓存 TTL 默认 5 分钟（可由系统属性 {@code rolePicker.nlsCacheTtlMs} 覆盖）。 */
    static final long TIMEOUT_NLS_CACHE_TTL_MS = 5L * 60 * 1000;
    /** NLS 缓存 TTL 系统属性名。 */
    static final String NLS_CACHE_TTL_PROPERTY = "rolePicker.nlsCacheTtlMs";
    /**
     * 面板会话最长存活时间（毫秒）—— 超过即自动结束会话并释放面板（N-19，doc 21）。
     *
     * <p><b>要防的缺陷</b>：面板主循环是 {@code while (true)}，且它跑在<b>调用者线程</b>上（本模块不创建
     * 任何线程）。其退出条件只有「根页关闭 / 无存活页 / 线程中断」三类 —— 若这些事件因异常丢失
     * （{@code onClose} 未回退、{@code rootClosed} 未置位、浏览器侧回调被吞），循环会以
     * {@code poll(1000ms)} <b>无限阻塞调用线程</b>：表现为"面板点了没反应、用例永不结束"，
     * 且没有任何超时兜底。</p>
     *
     * <p>默认 30 分钟：远长于任何正常拾取会话（正常为分钟级），又能在事件丢失时兜底结束。
     * 可由系统属性 {@code rolePicker.panelSessionMaxMs} 覆盖；<b>非正值不视为"无上限"</b>，
     * 而是回落默认值并告警（与 N-16 的页面超时纪律一致：{@code 0} 表示无限等待属隐患，
     * 需要更长会话请显式给正数，例如 240 分钟）。</p>
     */
    static final long TIMEOUT_PANEL_SESSION_MAX_MS = 30L * 60 * 1000;

    /** 面板会话最长存活时间的系统属性名（覆盖 {@link #TIMEOUT_PANEL_SESSION_MAX_MS}）。 */
    static final String PANEL_SESSION_MAX_PROPERTY = "rolePicker.panelSessionMaxMs";

    // ───────────────────────── CAP_* : 容量上限 ─────────────────────────
    /**  URL→类名映射上限。原实现无上限，每派生一个新 URL 的类名就登记一条、只增不减。 */
    static final int CAP_GLOBAL_URL_TO_CLASS_MAX = 1024;
}
