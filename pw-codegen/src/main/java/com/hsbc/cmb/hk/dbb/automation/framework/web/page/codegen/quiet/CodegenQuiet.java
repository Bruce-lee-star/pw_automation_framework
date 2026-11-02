package com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen.quiet;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * V3 best-effort 通信/UI 链路的"静默忽略"工具：这些异常是面板/UI 抖动导致的非致命失败，
 * 记 debug 即可，绝不能上抛拖垮拾取主循环。
 */
public final class CodegenQuiet {

    private CodegenQuiet() {}

    public static void ignore(String where, Throwable t) {
        Logger log = LoggerFactory.getLogger(CodegenQuiet.class);
        log.debug("[codegen][quiet] {} ignored: {}", where, t.getMessage());
    }
}
