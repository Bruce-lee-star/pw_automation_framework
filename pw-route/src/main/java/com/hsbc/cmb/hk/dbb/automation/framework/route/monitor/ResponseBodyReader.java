package com.hsbc.cmb.hk.dbb.automation.framework.route.monitor;

import com.microsoft.playwright.Response;

/**
 * 响应体读取器（framework-internal，包级私有）——把"读 body + 失败分类"收敛一处。
 *
 * <p>跨浏览器事实（见《Java-Playwright-1.62 网络API分层指南》§3.1）：官方对三浏览器一致的契约是
 * 「<b>必须在导航前读 body</b>」——页面关闭后 {@code body()} 三浏览器俱败（{@code raceWithPageClosure}），
 * Chromium 的重取兜底<b>仅限 GET + 静态安全类型</b>（font/image/script/stylesheet/document），
 * <b>JSON/XHR/POST 不在列</b>。⇒ 对本框架的 JSON API 采集而言，body 一旦读失败就<b>无法跨浏览器挽回</b>，
 * 故本读取器<b>只读一次、绝不做失败后重试</b>：读失败即交回根因，由调用方 fail-open
 * （body=null + 分类计数）。真正的 mitigation 是"立即读、自己存"——在业务线程 main 尽快读、
 * 压缩"响应到达→读取"窗口，而非失败后重试。</p>
 *
 * <p><b>调用线程约定</b>：业务线程（main）——{@code body()} 是协议调用，须与 UI/断言串行、无并发泵 Connection。</p>
 */
final class ResponseBodyReader {

    /**
     * 读取结果：{@link #bytes} = {@code body()} 原始返回（未抛，可能为 null/空数组）；
     * {@link #failure} = 抛错时的根因（未抛为 null）。
     */
    static final class Result {
        final byte[] bytes;
        final Throwable failure;

        private Result(byte[] bytes, Throwable failure) {
            this.bytes = bytes;
            this.failure = failure;
        }

        /** 是否抛错（读失败）；未抛而返回 null/空不算失败。 */
        boolean failed() {
            return failure != null;
        }
    }

    /**
     * 读响应体一次。仅当 {@code body()} 抛错时判失败（{@link Result#failed()} 为 true）；
     * 未抛而返回 null/空数组<b>不当失败</b>——保持"只有抛错才 fail-open"的口径。
     *
     * @param response 已到达的真实响应（业务线程持有）；调用方须已排除流式与 3xx
     */
    Result read(Response response) {
        try {
            return new Result(response.body(), null);
        } catch (Throwable t) {
            return new Result(null, t);
        }
    }
}
