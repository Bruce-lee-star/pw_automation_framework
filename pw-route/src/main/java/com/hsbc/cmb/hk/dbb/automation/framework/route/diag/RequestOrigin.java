package com.hsbc.cmb.hk.dbb.automation.framework.route.diag;

/**
 * 一次请求的<b>归属三元组</b>：谁（哪个 Page）在哪一版导航（哪个 Frame / 第几次主文档导航）发出。
 *
 * <p><b>为什么需要</b>：本框架的采集是 context 级的 —— popup 初始请求、更早创建的页、
 * 尚未绑定 handler 的新页、以及 SPA 的 {@code pushState} 路由切换，在 context 级视图里
 * 全部无法区分。连续导航时，上一轮请求的迟到响应会被新一轮等待命中（"过期命中"），
 * 而单靠采集序号只能解决"先后"，不能解决"属于谁"。
 *
 * <p><b>为什么只存 id 而不存 {@code Page}/{@code Frame} 对象</b>：官方未承诺 Frame 对象跨
 * detach/attach 复用，iframe 导航还会重建 frame 树；长期持有裸 {@code Frame} 会让先前记录的
 * id 在严格相等匹配下"消失"（且其持有会延长 Playwright 对象生命周期）。故只保留
 * <b>身份哈希派生</b>的字符串 id，且同一 JVM 会话内保持稳定。
 *
 * @param ownerPageId 所属 Page 的身份 id；不可归属时为 {@code null}
 * @param frameId     所属 Frame 的身份 id；不可归属时为 {@code null}
 * @param navSeq      所属 Page 的<b>主文档导航代次</b>（首次为 1）；不可归属时为 -1。
 *                    同一 Page 的主文档导航会使该值前进，从而使"旧代次的迟到响应"可被识别与排除
 * @param degraded    归属读取是否降级（如请求来自 Service Worker、主文档先于 Frame 创建、
 *                    Frame 已 detach 等）。<b>降级只影响归属，绝不丢观测</b> —— 记录照样入采集库
 */
public record RequestOrigin(String ownerPageId, String frameId, long navSeq, boolean degraded) {

    /** 无归属信息（派发链证据等非采集路径）。 */
    public static final RequestOrigin UNKNOWN = new RequestOrigin(null, null, -1L, false);

    /** 归属读取失败：请求来自 Service Worker / Frame 不可用等，须原子标记以免误判。 */
    public static final RequestOrigin DEGRADED = new RequestOrigin(null, null, -1L, true);

    /** 是否可用（可归属且未降级）。 */
    public boolean attributed() {
        return ownerPageId != null && !degraded;
    }
}
