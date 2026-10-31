package com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen.panel;

import com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen.codegen.MultiPageCodegen;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen.model.CodegenEntry;
import com.microsoft.playwright.Page;

import java.util.List;
import java.util.Map;

/**
 * 面板会话句柄（B 轨道 2.1：{@code CodegenPanel} 拆两层后的"会话侧"）。
 *
 * <p>一个常驻 {@link CodegenPanelManager.PanelProcess}（进程 + driver + 窗口）可以顺序服务多个会话：
 * 每次 {@link CodegenPanelManager#open(Path, java.util.function.Consumer, String)} 返回一个新的
 * {@code CodegenPanelHandle}，承载本次会话的 {@code onCommand} 回调与全部推送方法；会话结束
 * （面板「结束会话」按钮 / 窗口被 OS 关闭 / {@link #close()}）时调用方执行
 * {@link CodegenPanelManager#release(CodegenPanelHandle)} 解绑 —— <b>只解绑会话，不关浏览器进程</b>，
 * 进程生命周期归 Manager（watchdog idle 回收 / {@code ShutdownCoordinator}）。
 *
 * <p>推送方法签名与既有 {@code CodegenPanel} 完全一致（原样迁移），因此调用方（{@code CodegenLauncher}
 * 的拾取回调 / 实时生成 / 分组修订）无需感知进程常驻化。
 */
public final class CodegenPanelHandle {

    private final CodegenPanelManager owner;
    private final Page panelPage;
    private volatile Runnable closeCallback;
    /** 会话是否已释放（release/窗口关闭后置 true；推送跳过，防异步线程在会话结束后再 evaluate）。 */
    private volatile boolean released;

    CodegenPanelHandle(CodegenPanelManager owner, Page panelPage) {
        this.owner = owner;
        this.panelPage = panelPage;
    }

    /**
     * 推送一条拾取结果到面板元素列表。
     *
     * @param pickId 本次拾取的唯一 id（同一元素在不同容器可能与既有条目 sigKey 相同，
     *               必须靠它区分列表行，见 {@code CodegenLauncher.Pick}）
     */
    public void onPicked(long pickId, CodegenEntry entry) {
        Map<String, Object> payload = entry.toMap();
        payload.put("pickId", pickId);
        owner.pushSafely(this, "entry => window.__onPicked(entry)", payload);
    }

    /**
     * 推送「该条目已被修订」（ASYNC role 复核失败 ⇒ 策略回退 CSS）：<b>就地更新</b>既有行，不新增行。
     *
     * @param pickId 目标行 id（{@link #onPicked} 时分配的同一个 id）
     * @param entry  修订后的条目（{@code sigKey} 不变，仅 strategy/selector 变化）
     */
    public void onPickedRevised(long pickId, CodegenEntry entry) {
        onPickedRevised(pickId, entry, null);
    }

    /**
     * 同上，并带上<b>修订原因</b>（如 {@code hits=2, expected exactly 1 hit}）。
     *
     * @param reason 简短原因；{@code null}/空表示不展示
     */
    public void onPickedRevised(long pickId, CodegenEntry entry, String reason) {
        Map<String, Object> payload = entry.toMap();
        payload.put("pickId", pickId);
        if (reason != null && !reason.isEmpty()) {
            payload.put("revisionReason", reason);
        }
        owner.pushSafely(this, "entry => window.__onPickedRevised(entry)", payload);
    }

    /** 通知面板：这些行已被标记为「一组元素」（将生成一个 {@code PageElementList}）。 */
    public void onGrouped(List<Long> pickIds) {
        owner.pushSafely(this, "ids => window.__onGrouped(ids)", pickIds);
    }

    /** 推送一条录制动作到面板步骤区。 */
    public void onAction(Map<String, Object> action) {
        owner.pushSafely(this, "action => window.__onAction(action)", action);
    }

    /** 推送生成的代码到对应 Tab（tab ∈ {page,step,assert}）。 */
    public void onCode(String tab, String code) {
        owner.pushSafely(this, "(p) => window.__onCode(p)", Map.of("tab", tab, "code", code));
    }

    /**
     * 推送按页拆分的代码块到对应 Tab（tab ∈ {page,step,assert}），供面板在 Tab 内做「页内分页面切换器」。
     */
    public void onCodeBlocks(String tab, List<MultiPageCodegen.CodeBlock> blocks) {
        java.util.List<Map<String, Object>> list = new java.util.ArrayList<>();
        if (blocks != null) {
            for (MultiPageCodegen.CodeBlock b : blocks) {
                Map<String, Object> m = new java.util.LinkedHashMap<>();
                m.put("label", b.label);
                m.put("code", b.code);
                list.add(m);
            }
        }
        owner.pushSafely(this, "(p) => window.__onCode(p)", Map.of("tab", tab, "blocks", list));
    }

    /** 推送状态文案到面板状态条（与旧 {@code CodegenPanel#setStatus} 语义一致）。 */
    public void setStatus(String status) {
        owner.pushSafely(this, "s => window.__setStatus(s)", status);
    }

    /**
     * 面板窗口被用户关闭（OS 标题栏 ×）时回调。
     *
     * <p>B 轨道下关窗意味着<b>面板进程被终止</b>（常驻失效），是异常结束路径；会话仍会释放闩锁让主线程
     * 返回。回调注册在进程级（launch 时一次性），此处仅登记本会话的回调，由 Manager 在窗口关闭时派发，
     * 避免多会话在同一常驻页上累积 {@code page.onClose} 监听器。
     */
    public void onClose(Runnable callback) {
        this.closeCallback = callback;
    }

    Runnable closeCallback() {
        return closeCallback;
    }

    /** 标记本会话已释放（由 Manager 在 release / 窗口关闭时调用；幂等）。 */
    void markReleased() {
        this.released = true;
    }

    /** 会话是否已释放：释放后推送静默跳过（异步生成线程晚到的推送不再 evaluate 已结束的会话）。 */
    boolean isReleased() {
        return released;
    }

    /**
     * 关闭本会话（B 轨道语义：<b>仅解绑</b>）。
     *
     * <p>与旧 {@code CodegenPanel#close()}（关 ctx + driver + server + scheduler 四段清理）不同：进程清理
     * 已归 {@link CodegenPanelManager#shutdownProcess()}（仅 idle watchdog / {@code ShutdownCoordinator}
     * 调用）；这里只释放本会话的句柄与命令回调，浏览器进程常驻待复用。幂等。
     */
    public void close() {
        owner.release(this);
    }

    /** 获取承载页（供 Manager 内部推送 / 进程活性检测；不对外暴露进程其它资源）。 */
    Page page() {
        return panelPage;
    }

    /**
     * 面板页是否已关闭（用户点标题栏 × / 窗口被 OS 关闭即 true）。
     *
     * <p>读取失败（上下文已销毁）也视为「已关闭」，让兜底看门狗尽快收尾，避免误判为仍存活而继续空等。
     */
    public boolean isClosed() {
        try {
            return panelPage.isClosed();
        } catch (Exception e) {
            return true;
        }
    }
}
