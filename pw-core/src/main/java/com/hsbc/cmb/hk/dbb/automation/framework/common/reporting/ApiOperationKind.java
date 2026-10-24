package com.hsbc.cmb.hk.dbb.automation.framework.common.reporting;

/**
 * {@code apiOperations} 行的<b>来源约定</b>（行首前缀）——报告页据此把同一字段分拣到不同面板。
 *
 * <p>背景（2026-10-20 用户反馈）：{@code ReportingBridge.recordApiOperation(String)} 同时被两类
 * 调用方使用，但报告语义完全不同：
 * <ul>
 *   <li>{@link #REST} —— 业务经 <b>RestAssured</b> 主动发出的 API 请求
 *       （{@code AbstractRestJob} 上报）→ 报告页 “API Request”；</li>
 *   <li>{@link #CAPTURED} —— Playwright <b>route 抓包</b>得到的页面真实调用
 *       （monitor / mock / delay / capture，{@code NetworkEvidenceBridge} 上报）
 *       → 报告页 “Route Captured”。</li>
 * </ul>
 *
 * <p>为什么不加新字段 / 新桥方法：{@code apiOperations} 是 schema additive-only 的既有字段，
 * 增加来源枚举要改 JSON schema 与所有读写方；而行首前缀由<b>上报方自己</b>拼写、渲染方只做
 * 前缀判断，两侧共享本类常量即不会漂移。历史行（Serenity 时代的
 * {@code POST /api/x -> 401}，无前缀）一律归 {@link #REST} 口径，保持旧报告可用。
 */
public final class ApiOperationKind {

    /** RestAssured 主动请求（报告页 “API Request”）。 */
    public static final String REST = "REST";

    /** 页面 route 抓包得到的真实调用（报告页 “Route Captured”）。 */
    public static final String CAPTURED = "NET";

    private ApiOperationKind() {
    }
}
