package com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen;

import com.microsoft.playwright.Page;
import org.junit.Ignore;
import org.junit.Test;

/**
 * <b>编译期</b>锁定对外 API 形态（本方法 {@code @Ignore} 不执行，执行会真的启动浏览器）。
 *
 * <p>目的：一旦有人把签名改回 {@code String[]}、或引入会导致 varargs 歧义的重载、
 * 或重新引入 {@code pageClassName}/{@code packageName} 参数，这里的调用会编译失败，第一时间暴露破坏。
 */
public class CodegenLauncherApiShapeTest {

    @Ignore("仅编译期校验 API 形态，运行会启动浏览器")
    @Test
    public void publicApiCompilesAsDocumented() {
        Page page = null;

        // 1) 只传 page ⇒ 无 NLS ⇒ name 定位，类名与包名由框架默认（URL 派生类名 + DEFAULT_PACKAGE）
        CodegenLauncher.inspect(page);

        // 2) page + 单个 NLS（不 new String[]）
        CodegenLauncher.inspect(page, "nls/login.nls.json");

        // 3) page + 多个 NLS（可变参数）
        CodegenLauncher.inspect(page, "nls/login.nls.json", "nls/common.nls.json");
    }
}
