package com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen.cdp;

import com.google.gson.JsonObject;

import java.util.Map;

/**
 * CDP 命令发送通道（包级私有 seam）。
 *
 * <p>生产实现即 {@link CodegenCdpSession#send(String, Map)}；抽这一层是为了让「元素事实抽取 / frame 归属探测」
 * 的算法能在无浏览器、无真实 CDP 会话的情况下单测（{@link CodegenCdpSession} 是 final 且构造即建会话）。
 *
 * <p><b>约定</b>：返回值是 JSON-RPC 的 {@code result} <b>本体</b>（Playwright 的 {@code CDPSession.send}
 * 已剥掉外层）。失败返回 {@code null}。
 */
@FunctionalInterface
interface CdpTransport {
    JsonObject send(String method, Map<String, Object> params);
}
