package com.hsbc.cmb.hk.dbb.automation.framework.web.agent;

/**
 * 靶场目标主机被沙箱护栏拒绝时抛出（R4/R8 运行期落点，fail-closed）。
 *
 * <p>受控重放/验证只能指向 file://（本地 fixture）、loopback 或
 * {@code pw.agent.testbed.allowedHosts} 显式列出的主机；其余网络主机一律拒绝，
 * 确保隔离靶场不会被误指向生产环境。</p>
 */
public class TestBedTargetRejectedException extends RuntimeException {

    public TestBedTargetRejectedException(String host, String allowedHostsKey) {
        super("RouteTestBed 拒绝非靶场目标主机 ["
                + (host == null ? "<null>" : host)
                + "]：受控重放/验证仅允许 file://、loopback 或配置键 ["
                + allowedHostsKey
                + "] 显式列出的主机（fail-closed）。");
    }
}
