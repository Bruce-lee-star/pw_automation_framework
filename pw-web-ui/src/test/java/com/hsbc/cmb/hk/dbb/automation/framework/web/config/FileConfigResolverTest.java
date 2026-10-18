package com.hsbc.cmb.hk.dbb.automation.framework.web.config;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;

import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * {@link FileConfigResolver} 单测：HOCON 扁平化（含点分叶子键）、标量渲染、缺配置优雅降级。
 *
 * <p>扁平化逻辑经 package-private {@link FileConfigResolver#flatten} 直接验证，不依赖 classpath 夹具。</p>
 */
public class FileConfigResolverTest {

    /** 复刻 serenity.conf 的结构（嵌套对象 + 点分叶子键 token.security.url），断言扁平化为 BDDUtils 使用的点分键。 */
    @Test
    public void flatten_nestedAndDottedLeafKeys_matchThucydidesShape() {
        String hocon = ""
                + "environment {\n"
                + "  O63_SIT1 = \"https://www.example.hsbc/portalserver/logon\"\n"
                + "}\n"
                + "userinfo_O63 {\n"
                + "  WP7UAT2_2 {\n"
                + "    profile = \"W1-HKHBAP001060698838PUSER1\"\n"
                + "    token.security.url = \"https://otp.example/getotp.htm?username=9900196198&key=abc\"\n"
                + "  }\n"
                + "}\n";

        Map<String, String> out = new LinkedHashMap<>();
        FileConfigResolver.flatten(ConfigFactory.parseString(hocon), "", out);

        assertEquals("https://www.example.hsbc/portalserver/logon", out.get("environment.O63_SIT1"));
        assertEquals("W1-HKHBAP001060698838PUSER1", out.get("userinfo_O63.WP7UAT2_2.profile"));
        // 点分叶子键必须保留为 ...token.security.url（BDDUtils 的查询键形状）
        assertEquals("https://otp.example/getotp.htm?username=9900196198&key=abc",
                out.get("userinfo_O63.WP7UAT2_2.token.security.url"));
    }

    /** 标量（布尔 / 数字）渲染为字符串值。 */
    @Test
    public void flatten_scalarTypes_renderAsString() {
        String hocon = "playwright { browser.headless = true, timeout = 30 }";
        Map<String, String> out = new LinkedHashMap<>();
        FileConfigResolver.flatten(ConfigFactory.parseString(hocon), "", out);

        assertEquals("true", out.get("playwright.browser.headless"));
        assertEquals("30", out.get("playwright.timeout"));
    }

    /** 空 config → 空 map（不抛异常）。 */
    @Test
    public void flatten_emptyConfig_yieldsEmptyMap() {
        Map<String, String> out = new LinkedHashMap<>();
        FileConfigResolver.flatten(ConfigFactory.empty(), "", out);
        assertTrue(out.isEmpty());
    }

    /**
     * 框架测试 classpath 无 serenity.conf/pw.conf：对绝对不存在的键返回 null 且不抛异常
     * （验证缺文件时的优雅降级；用不可能冲突的键，杜绝未来 classpath 变动导致脆弱）。
     */
    @Test
    public void resolve_absentKey_returnsNullWithoutThrowing() {
        FileConfigResolver resolver = new FileConfigResolver();
        assertNull(resolver.resolve("__pw_file_config_resolver__.absent.key"));
        assertNull(resolver.resolve(null));
    }
}
