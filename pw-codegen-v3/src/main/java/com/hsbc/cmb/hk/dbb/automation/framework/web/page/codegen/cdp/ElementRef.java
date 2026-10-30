package com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen.cdp;

import java.util.Map;

/**
 * CDP 元素引用：{@code backendNodeId} + 元素所属 {@code frameId} + frame 归属 + （可选）{@code objectId}
 * + （可选）frame 树快照。
 *
 * <p>带回 {@code frameId} 是<b>跨域 iframe 可运行</b>的前提：{@code DOM.describeNode} 的 {@code node.frameId}
 * 只对 frame owner 元素填充，普通元素要靠定位过程本身（逐 frame 求值 / {@code DOM.getNodeForLocation}）告知归属，
 * 再据此沿 {@code Page.getFrameTree} 的 {@code parentId} 链生成 {@code frame=SELa :: SELb :: <内层 css>}。
 *
 * <p>带回 {@link FrameScope} 的作用是<b>省掉一次多余的 {@code Page.getFrameTree}</b>，并让下游能区分
 * 「主 frame / 子 frame / 未知」：定位阶段本来就读过 frame 树，这里把结论直接带过来，抽取阶段不必重复读；
 * 子 frame 元素还可以据此跳过「页级 role 复核」（{@code page.getByRole} 只看主 frame，对子 frame 元素恒 0 命中）。
 *
 * <p>带回 {@code objectId} 的作用是<b>省掉一次 {@code DOM.resolveNode}</b>：按选择器定位时
 * {@code Runtime.evaluate} 已经把命中的元素做成了 {@code RemoteObject}（{@code objectId}），抽取阶段直接
 * {@code Runtime.callFunctionOn} 即可，无需再按 {@code backendNodeId} 反查一次对象。
 *
 * <p>带回 {@link Frames} 的作用是<b>避免抽取阶段为生成穿透路径二次读取同一棵 frame 树</b>：定位阶段解析
 * {@code Page.getFrameTree} 时顺带产出 parent 映射，随引用带给抽取阶段复用（仅子 frame 元素需要穿透路径）。
 *
 * <p>{@code objectId} 的<b>生命周期</b>只到所在执行上下文销毁为止（导航 / frame 重建即失效）。因此它被当作
 * <b>加速提示</b>而非事实：抽取阶段若发现该 {@code objectId} 不可用，会自动回退 {@code DOM.resolveNode}，
 * 语义与不传时完全一致（多一次往返，绝不失败）。{@code frames} 同理：抽取阶段若拿到也只当提示，为空时
 * 仍自行读一次 frame 树（行为与无快照完全一致）。
 *
 * @param backendNodeId 元素的 backendNodeId（坐标反查 / 兜底路径的唯一凭据）
 * @param frameId       元素所属 frame 的 id；主 frame 时即主 frame 的 id
 * @param scope         元素相对 frame 树的归属；未知（如仅经坐标反查）时为 {@link FrameScope#UNKNOWN}
 * @param objectId      定位阶段顺带得到的元素 {@code RemoteObject} id；{@code null} 表示不可用/未知
 * @param frames        frame 树快照（主 frame id + parent 映射）；{@code null} 表示未带，抽取阶段按需自读
 */
public record ElementRef(long backendNodeId, String frameId, FrameScope scope, String objectId, Frames frames) {

    /** 元素相对 frame 树的归属。 */
    public enum FrameScope {
        /** 位于主 frame（{@code page.getByRole}/{@code page.locator} 的作用域）。 */
        MAIN,
        /** 位于子 frame（含跨源 iframe）；页级定位器查不到它。 */
        SUB,
        /** 未知（未读取 frame 树，或读取失败）：按历史语义处理（视为主 frame）。 */
        UNKNOWN
    }

    /** 兼容构造：只有 backendNodeId + frameId（无 objectId / frame 树快照）。 */
    public ElementRef(long backendNodeId, String frameId) {
        this(backendNodeId, frameId, FrameScope.UNKNOWN, null, null);
    }

    /** 兼容构造：带 frame 归属但无 objectId / frame 树快照。 */
    public ElementRef(long backendNodeId, String frameId, FrameScope scope) {
        this(backendNodeId, frameId, scope, null, null);
    }

    /** 兼容构造：带 frame 归属与 objectId，但无 frame 树快照。 */
    public ElementRef(long backendNodeId, String frameId, FrameScope scope, String objectId) {
        this(backendNodeId, frameId, scope, objectId, null);
    }

    /** 是否位于子 frame（页级 Playwright 定位器不可见）。 */
    public boolean inSubFrame() {
        return scope == FrameScope.SUB;
    }

    /** 是否携带可直接复用的 {@code RemoteObject} id（省一次 {@code DOM.resolveNode}）。 */
    public boolean hasObjectId() {
        return objectId != null && !objectId.isEmpty();
    }

    /**
     * frame 树快照：主 frame id + 每个 frame 的父 frameId（来自一次 {@code Page.getFrameTree}）。
     *
     * <p>由定位阶段（{@link CdpFrameLocator}）解析 frame 树时顺带产出，随 {@link ElementRef} 带给抽取阶段，
     * 避免抽取阶段为生成「跨 frame 穿透路径」<b>二次</b>读取同一棵 frame 树（主 frame 元素本就不需要穿透路径，
     * 故仅子 frame 元素产出该快照才有意义，但定位阶段统一产出、零额外成本）。
     */
    public record Frames(String mainFrameId, Map<String, String> parentByFrame) {
    }
}
