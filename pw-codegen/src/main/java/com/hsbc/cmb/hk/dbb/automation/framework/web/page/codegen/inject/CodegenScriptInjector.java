package com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen.inject;

import com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen.quiet.CodegenQuiet;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Frame;
import com.microsoft.playwright.Page;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * 把 {@code codegen-inject.js}（role 算法 + recorder）经 {@code addInitScript} 注入被测页。
 *
 * <p>addInitScript 在文档创建早期运行、且随导航自动重挂，故每次页面切换/刷新都不需要重新注入。
 * 脚本内定义 {@code window.__CODEGEN__}，供 CDP 抽取层调用；recorder 监听真实操作并经
 * {@code window.__codegenEvent} 桥回 Java。
 */
public final class CodegenScriptInjector {

    private static final Logger log = LoggerFactory.getLogger(CodegenScriptInjector.class);

    private CodegenScriptInjector() {}

    /** 读取 classpath 上的 codegen-inject.js 内容。 */
    private static String readScript() {
        try (InputStream in = CodegenScriptInjector.class.getClassLoader()
                .getResourceAsStream("codegen/codegen-inject.js")) {
            if (in == null) {
                log.warn("[codegen][inject] codegen-inject.js not found on classpath");
                return "";
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            CodegenQuiet.ignore("CodegenScriptInjector#readScript", e);
            return "";
        }
    }

    /**
     * 注入 codegen-inject.js 到指定页面（<b>当前文档立即生效</b>，后续导航继续生效）。
     *
     * <p>{@code addInitScript} 只对<b>之后加载</b>的文档运行，对"已经打开的页面"不生效——
     * 登录页早已加载完毕，只注册 addInitScript 会导致 {@code window.__CODEGEN__} 根本不存在
     * （拾取靠 CDP 自包含函数仍可用，但录制器永远收不到事件，表现为 Recorded Steps 恒空）。
     * 故额外 {@code evaluate} 一次让当前文档立即生效；脚本首行有 {@code if (window.__CODEGEN__) return;}
     * 幂等保护，重复求值安全。
     */
    public static void inject(Page page) {
        String js = readScript();
        if (js.isEmpty()) return;
        try {
            page.addInitScript(js);
            page.evaluate(js);
            // 已存在的子 frame（含 iframe 承载的登录表单）在本次注入之前就加载完了，addInitScript 覆盖不到，
            // 必须逐个求值；否则 iframe 内的点击/输入没有任何监听器 → 拾取与录制双双静默失效。
            int frames = 0;
            for (Frame f : page.frames()) {
                if (f == page.mainFrame()) continue;
                try {
                    f.evaluate(js);
                    frames++;
                } catch (Exception e) {
                    log.debug("[codegen][inject] sub frame inject skipped ({}): {}", f.url(), e.getMessage());
                }
            }
            log.info("[codegen][inject] codegen-inject.js injected into inspected page "
                    + "(current document + {} sub frame(s) + future navigations)", frames);
            // 注入自检：evaluate 成功并不代表脚本真的跑起来了（例如脚本在更早的文档里已设幂等标记）。
            // 这两项缺失会让拾取/录制全链路静默失效，故在启动阶段就明确报出来。
            Object recorder = page.evaluate("() => !!(window.__CODEGEN__ && window.__CODEGEN__.recorder)");
            Object bridge = page.evaluate("() => typeof window.__codegenEvent");
            if (!Boolean.TRUE.equals(recorder) || !"function".equals(bridge)) {
                log.warn("[codegen][inject] self-check failed: recorderPresent={}, bridgeType={} "
                        + "— 拾取与录制将不会有任何事件", recorder, bridge);
            }
        } catch (Exception e) {
            CodegenQuiet.ignore("CodegenScriptInjector#inject", e);
        }
    }

    /**
     * 注入到整个浏览器上下文（<b>覆盖后续新建的弹窗 / 新标签页</b>）：addInitScript 在上下文级注册后，
     * 任意新建页（{@code context.onPage}）都会自动运行该脚本，无需逐页重注。
     */
    public static void inject(BrowserContext context) {
        String js = readScript();
        if (js.isEmpty()) return;
        try {
            context.addInitScript(js);
            log.info("[codegen][inject] codegen-inject.js injected into browser context (covers popups/new tabs)");
        } catch (Exception e) {
            CodegenQuiet.ignore("CodegenScriptInjector#injectContext", e);
        }
    }
}
