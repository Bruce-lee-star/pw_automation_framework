package com.hsbc.cmb.hk.dbb.automation.framework.route.dispatch;

import com.microsoft.playwright.APIRequestContext;
import com.microsoft.playwright.options.RequestOptions;
import org.junit.Assume;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

import java.lang.reflect.Field;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * MOCK intercept 取真实响应（{@link RouteAction#fetch}）的 <b>TLS 契约</b>（2026-10-21）。
 *
 * <p><b>为什么必须有这条契约</b>：取真实响应由<b>驱动侧</b>的 {@code APIRequestContext.fetch} 发起，
 * 其 TLS 校验与浏览器渲染进程不一致（自签 / 缺中间证书时浏览器补链放行、驱动直接失败）。
 * 失败即上抛 → fail-open → 因尚未 {@code markIssued()} 而 {@code fallback} 放行 →
 * <b>mock 静默失效</b>，表象却是"规则没匹配"。故 {@code RequestOptions} 必须<b>显式携带</b>
 * {@code ignoreHTTPSErrors}（未携带＝驱动按严格校验，回归即静默降级）。</p>
 *
 * <p>本测试只读「有效开关 → RequestOptions」这一条链路，不依赖开关被解析成哪个值
 * （开关在类初始化时定格，同 JVM 内可能被其它用例影响），故断言"两边一致"而非写死 true。</p>
 */
public class RouteActionFetchTlsTest {

    @Test
    public void fetchCarriesIgnoreHttpsErrorsFlagIntoRequestOptions() throws Exception {
        APIRequestContext context = mock(APIRequestContext.class);
        RouteAction.RequestSnapshot snapshot =
                new RouteAction.RequestSnapshot("https://sit.example.com/profile/list", "POST",
                        Map.of("content-type", "application/json"), "{\"k\":\"v\"}");

        RouteAction.fetch(context, snapshot);

        ArgumentCaptor<RequestOptions> captor = ArgumentCaptor.forClass(RequestOptions.class);
        verify(context).fetch(eq("https://sit.example.com/profile/list"), captor.capture());

        Boolean actual = ignoreHttpsErrors(captor.getValue());
        assertNotNull("RequestOptions 必须显式携带 ignoreHTTPSErrors（不携带＝驱动严格校验→mock 静默失效）",
                actual);
        assertTrue("RequestOptions 的证书策略必须与 RouteAction 的有效开关一致",
                effectiveFlag() == actual.booleanValue());
    }

    @Test
    public void defaultIsPermissive_whenFlagNotOverridden() throws Exception {
        //  仅在"本次运行没有显式覆盖该开关"时才断言默认值——显式 -D 是合法用法，不该让测试红。
        Assume.assumeTrue(System.getProperty("route.fetch.ignoreHttpsErrors") == null
                && System.getenv("ROUTE_FETCH_IGNORE_HTTPS_ERRORS") == null);
        //  默认开启是刻意的取舍（SIT 自签/内网代理证书），见 RouteAction#FETCH_IGNORE_HTTPS。
        assertTrue("默认应忽略证书校验（-Droute.fetch.ignoreHttpsErrors=false 可关闭）",
                effectiveFlag());
    }

    /** 读取本次运行下 {@code RouteAction} 真正生效的开关值（反射私有常量，避免为测试放宽可见性）。 */
    private static boolean effectiveFlag() throws Exception {
        Field f = RouteAction.class.getDeclaredField("FETCH_IGNORE_HTTPS");
        f.setAccessible(true);
        return f.getBoolean(null);
    }

    /** 读取 {@code RequestOptionsImpl} 的 {@code ignoreHTTPSErrors}（该字段无公开 getter）。 */
    private static Boolean ignoreHttpsErrors(RequestOptions options) throws Exception {
        Field f = options.getClass().getDeclaredField("ignoreHTTPSErrors");
        f.setAccessible(true);
        return (Boolean) f.get(options);
    }
}
