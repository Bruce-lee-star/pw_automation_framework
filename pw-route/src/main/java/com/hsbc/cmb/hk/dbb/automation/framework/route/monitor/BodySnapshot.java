package com.hsbc.cmb.hk.dbb.automation.framework.route.monitor;

import java.util.List;

/**
 * body 快照（{@code headers()} + {@code body()} 的即时副本）——framework-internal，包级私有。
 *
 * <p>{@code headers()} 是客户端本地快照、{@code body()} 是协议调用——两者都在拿到句柄的同一时刻
 * 取走，之后断言只依赖本对象，<b>不再触碰驱动句柄</b>。这正是"立即读、自己存"的落点：
 * 由 {@code MonitorSink} 的"到达即读"提前生成并存入 {@link CapturedExchange}，
 * body 断言消费本快照而非活句柄，从而消除"持有句柄→延迟读取"的驱逐窗口。</p>
 *
 * @param bytes              响应体字节（快照失败 / 跳过时为 null）
 * @param headerNames        响应 header <b>名</b>清单（小写去重；只暴露名字不暴露值，防泄露）
 * @param contentType        content-type 原值（可能为 null）
 * @param inconclusiveReason 非 null 表示快照失败（句柄不可用），原因文本
 * @param preview            响应体前缀（已按字符截断；供证据展示）
 * @param bodySkipped        是否<b>刻意跳过</b>读体（流式端点：读体会永久阻塞观测线程）
 */
record BodySnapshot(byte[] bytes, List<String> headerNames, String contentType,
                    String inconclusiveReason, String preview, boolean bodySkipped) {
}
