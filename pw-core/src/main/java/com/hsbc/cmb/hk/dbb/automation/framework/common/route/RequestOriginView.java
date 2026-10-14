package com.hsbc.cmb.hk.dbb.automation.framework.common.route;

/**
 * 请求归属三元组（只读视图）：谁（哪个 Page）在哪一版导航（哪个 Frame / 第几次主文档导航）发出。
 *
 * <p>本类型定义在 pw-core，与 {@link CapturedApiCallView} 同源 —— pw-web-ui 只依赖 pw-core，
 * 故跨模块载体不能出现 pw-route 的类型（模块方向：route → core，不可反向）。
 * pw-route 把自身的 {@code RequestOrigin} 映射成本类型。
 *
 * <p><b>为什么需要</b>：采集是 context 级的，popup 初始请求、更早创建的页、尚未绑定 handler
 * 的新页、以及 SPA 的 {@code pushState} 路由切换，在 context 级视图里全都无法区分。
 * 归属三元组让"这条记录属于哪个页面、哪一轮导航"成为可查询事实。
 *
 * <p><b>id 语义</b>：{@code ownerPageId} / {@code frameId} 是身份哈希派生的字符串，
 * <b>只在同一次 JVM 运行内稳定</b> —— 不可跨进程持久化、不可当业务主键使用；
 * 也不应长期持有裸 {@code Page}/{@code Frame} 对象（官方未承诺 Frame 跨 detach/attach 复用）。
 *
 * @param ownerPageId 所属 Page 的身份 id；不可归属时为 {@code null}
 * @param frameId     所属 Frame 的身份 id；不可归属时为 {@code null}
 * @param navSeq      所属 Page 的<b>主文档导航代次</b>（首次为 1）；不可归属时为 -1
 * @param degraded    归属读取是否降级（请求来自 Service Worker、Frame 未创建/已 detach 等）。
 *                    <b>降级只影响归属，不代表采集失败</b> —— 记录本身依然有效
 * @param redirectChainId 所属重定向链的 id（取自链首请求）；非重定向请求为 {@code null}
 * @param redirectHop    本请求在链上的跳序（链首为 0，每跳 +1）；非重定向为 0
 */
public record RequestOriginView(String ownerPageId, String frameId, long navSeq, boolean degraded,
                                String redirectChainId, int redirectHop) {

    /** 无归属信息（派发链证据等非采集路径）。 */
    public static final RequestOriginView UNKNOWN = new RequestOriginView(null, null, -1L, false, null, 0);

    /** 归属读取失败：请求来自 Service Worker / Frame 不可用等。 */
    public static final RequestOriginView DEGRADED = new RequestOriginView(null, null, -1L, true, null, 0);

    /** 是否可归属（有 owner 且未降级）。 */
    public boolean attributed() {
        return ownerPageId != null && !degraded;
    }

    /** 是否与指定 Page 同源（null 参数恒 false，便于在谓词里直接使用）。 */
    public boolean fromOwner(String pageId) {
        return pageId != null && pageId.equals(ownerPageId);
    }
}
