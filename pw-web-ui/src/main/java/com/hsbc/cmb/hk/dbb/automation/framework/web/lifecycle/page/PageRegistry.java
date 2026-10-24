package com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.page;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.page.PageRegistryImpl;

import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;

/**
 * Page 注册契约（WEB-P1-6 Phase 1 接口提取）。
 *
 * <p>默认实现 {@link PageRegistryImpl}（WEB-P1-1 拆分成果的逻辑承载体，已实例化为单例）。
 * 该接口把 Page 的「获取 / 创建 / 关闭 / 设置」职责收敛为可替换契约，
 * 供 {@code PlaywrightRuntime} 实例门面与测试替身经此接口替换实现。</p>
 *
 * @see PageRegistryImpl
 */
public interface PageRegistry {

    Page getPage();

    Page createPage(BrowserContext context);

    void closePage();

    void setPage(Page page);

    /**
     * 本线程当前 Page（<b>线程级</b>记录，独立于用例级的 {@code PAGE_KEY}）。
     *
     * <p>用途：① 收尾（框架 {@code testFinished}，用例级键已被 Cucumber {@code @After} 清空）时
     * 仍能可靠关闭本线程 Page；② 用例级键暂缺（@After 之后 / 异步回调路径）时的回退复用依据。
     *
     * @return 本线程当前 Page；无则 {@code null}
     */
    Page currentPageForThread();

    /**
     * 本线程当前 Page 的「变更世代」信号（P2-18 热路径优化）。
     *
     * <p>仅当本线程当前页发生过切换 / 重建（{@code setPage}/{@code closePage}/建页）时自增。
     * 供 {@code PageContextState.ensurePageValid} 在 page 已有效时据此跳过昂贵的 {@code getPage()} 全链路比对。</p>
     *
     * @return 当前 epoch 值（线程级，只读）
     */
    long currentPageEpoch();
}