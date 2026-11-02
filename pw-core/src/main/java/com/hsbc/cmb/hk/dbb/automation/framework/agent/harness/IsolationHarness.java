package com.hsbc.cmb.hk.dbb.automation.framework.agent.harness;

import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.BrowserCommandChannel;

/**
 * 隔离靶场 SPI（设计 §3.1.2.1 / R4 / R8）。
 *
 * <p>在隔离环境提供 BrowserCommandChannel 实例；真正实现由 pw-web-ui 基于 RouteTestBed
 * （LEAK-REPORT / 收敛）提供，确保分析期 / 受控验证与回归环境逻辑分离。
 *
 * <p>pw-core 仅定义契约，不依赖浏览器实现；调用方负责在 finally 中 release。</p>
 */
public interface IsolationHarness {

    /** 在隔离靶场内获得一个命令通道，指向 targetUrl。 */
    BrowserCommandChannel acquire(String targetUrl);

    /** 释放通道（关闭 context / 回收资源）。 */
    void release(BrowserCommandChannel channel);
}
