package com.hsbc.cmb.hk.dbb.automation.framework.route.probe;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import org.junit.Ignore;
import org.junit.Test;

/**
 * 探针：Playwright chromium 对靶场 manual-302 fetch 的语义（区分框架介入与否）。
 *
 * <p>cascade 的 btn-302（fetch manual 302）UI 实证长期失败（#302-out='0 location=null'）。
 * 需证明这是否与 route 拦截有关：NO-ROUTE / ROUTE-RESUME / ROUTE-FALLBACK 三态对比。
 * 只打印，不断言。
 *
 * <p><b>环境依赖（不进全量单测，定向 {@code -Dtest=Manual302ProbeTest} 手动跑）</b>：
 * 需要靶场 8899（login 流程）+ 独立 Python 302 服务器 8900；两者缺失时本探针必挂
 * （2026-10-10 全量单测实证：8900 未启动 → navigate 超时 Playwright Error）。
 * 处置对齐 {@code ContextCloseProbeTest}：@Ignore + 定向运行。
 */
@Ignore("环境依赖探针（靶场 8899 + Python 302 服务器 8900），定向 -Dtest=Manual302ProbeTest 运行")
public class Manual302ProbeTest {

    @Test
    public void probe() {
        try (Playwright pw = Playwright.create()) {
            Browser browser = pw.chromium().launch(new BrowserType.LaunchOptions().setHeadless(false));
            try {
                probeCase(browser, "NO-ROUTE", null);
                probeCase(browser, "ROUTE-RESUME", "resume");
                probeCase(browser, "ROUTE-FALLBACK", "fallback");
                probePython302(browser);
            } finally {
                browser.close();
            }
        }
    }

    /** Python 标准 302 服务器（同源 8900）作为 Chromium fetch(manual) 的语义对照。 */
    private void probePython302(Browser browser) {
        BrowserContext c = browser.newContext();
        try {
            Page p = c.newPage();
            p.navigate("http://127.0.0.1:8900/home", new Page.NavigateOptions().setTimeout(20000));
            p.click("#b");
            p.waitForTimeout(1000);
            System.out.println("PROBE[PY-302] = '" + p.textContent("#o") + "'");
        } finally {
            c.close();
        }
    }

    private void probeCase(Browser browser, String label, String mode) {
        BrowserContext c = browser.newContext();
        try {
            Page p = c.newPage();
            if ("resume".equals(mode)) {
                p.route("**/api/j_spring*", r -> r.resume());
            } else if ("fallback".equals(mode)) {
                p.route("**/api/j_spring*", r -> r.fallback());
            }
            System.out.println("PROBE[" + label + "] step1 navigate login");
            p.navigate("http://127.0.0.1:8899/login", new Page.NavigateOptions().setTimeout(20000));
            System.out.println("PROBE[" + label + "] step2 fill+click");
            p.fill("#username", "WP7UAT2_2");
            p.click("#login-btn");
            System.out.println("PROBE[" + label + "] step3 wait home url");
            p.waitForURL("**/home", new Page.WaitForURLOptions().setTimeout(20000));
            System.out.println("PROBE[" + label + "] step4 click btn-302");
            p.click("[id=\"btn-302\"]");
            p.waitForTimeout(1200);
            System.out.println("PROBE[" + label + "] #302-out = '" + p.textContent("[id=\"302-out\"]") + "'");
        } finally {
            c.close();
        }
    }
}
