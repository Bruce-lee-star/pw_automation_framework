package com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen.locator.candidate;

/**
 * 候选库并发冲突（区别于 I/O 错误）。
 *
 * <p>单独成类型是为了让调用方能区分"该重试"与"该报错"：冲突说明有人正在改同一份台账，
 * 重试通常就能成功；I/O 错误重试只会重复失败。</p>
 */
final class LibraryConflictException extends RuntimeException {

    LibraryConflictException(String message) {
        super(message);
    }
}
