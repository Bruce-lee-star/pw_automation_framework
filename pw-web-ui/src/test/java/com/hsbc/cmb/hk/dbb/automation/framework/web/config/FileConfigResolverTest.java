package com.hsbc.cmb.hk.dbb.automation.framework.web.config;

import com.typesafe.config.ConfigFactory;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * {@link FileConfigResolver} 单测：HOCON 扁平化、classpath / 工程根目录 / 显式路径的来源合并、
 * 缺配置优雅降级。
 *
 * <p>扁平化经 package-private {@link FileConfigResolver#flatten} 直接验证；
 * 磁盘来源经 package-private {@link FileConfigResolver#load} 注入临时目录，
 * 端到端用例则走真实的 {@link FileConfigResolver#resolve} + 系统属性路径。</p>
 */
public class FileConfigResolverTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    /** 复刻 pw.conf 的结构（嵌套对象 + 点分叶子键 token.security.url），断言扁平化为 BDDUtils 使用的点分键。 */
    @Test
    public void flatten_nestedAndDottedLeafKeys_matchDottedShape() {
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
     * 框架测试 classpath 无 pw.conf / pw.properties：对绝对不存在的键返回 null 且不抛异常
     * （验证缺文件时的优雅降级；用不可能冲突的键，杜绝未来 classpath 变动导致脆弱）。
     */
    @Test
    public void resolve_absentKey_returnsNullWithoutThrowing() {
        FileConfigResolver resolver = new FileConfigResolver();
        assertNull(resolver.resolve("__pw_file_config_resolver__.absent.key"));
        assertNull(resolver.resolve(null));
    }

    // ------------------------------------------------------------------ 磁盘来源

    /**
     * 用户诉求回归：工程根目录（user.dir，与 pom.xml 同级）下的 pw.properties 必须被加载。
     * 走真实 resolve → snapshot → 来源解析链路，不调用包级私有加载入口。
     */
    @Test
    public void projectRootPropertiesAreLoadedViaResolve() throws Exception {
        File root = folder.newFolder("project-root");
        write(new File(root, "pw.properties"),
                "framework.test.root.key=from-project-root\n");

        String previous = System.getProperty("user.dir");
        try {
            System.setProperty("user.dir", root.getAbsolutePath());
            assertEquals("工程根目录的 pw.properties 必须生效",
                    "from-project-root",
                    new FileConfigResolver().resolve("framework.test.root.key"));
        } finally {
            restore("user.dir", previous);
        }
    }

    /** 同一目录内 pw.conf（HOCON）覆盖 pw.properties，与既有 classpath 语义一致。 */
    @Test
    public void confOverridesPropertiesWithinSameDirectory() throws Exception {
        File root = folder.newFolder("same-dir");
        write(new File(root, "pw.properties"), "framework.test.key=from-properties\n");
        write(new File(root, "pw.conf"), "framework.test.key = \"from-conf\"\n");

        Map<String, String> loaded = FileConfigResolver.load(
                List.of(root.toPath()), List.of());

        assertEquals("from-conf", loaded.get("framework.test.key"));
    }

    /** 层级优先级：classpath < 工程根目录 < 显式目录；低层独有键仍保留。 */
    @Test
    public void laterDirectoryOverridesEarlierAndKeepsOtherKeys() throws Exception {
        File projectRoot = folder.newFolder("root-tier");
        File overrideDir = folder.newFolder("override-tier");
        write(new File(projectRoot, "pw.properties"),
                "framework.test.shared=root\nframework.test.only.root=kept\n");
        write(new File(overrideDir, "pw.properties"), "framework.test.shared=override\n");

        Map<String, String> loaded = FileConfigResolver.load(
                List.of(projectRoot.toPath(), overrideDir.toPath()), List.of());

        assertEquals("override", loaded.get("framework.test.shared"));
        assertEquals("低层独有键不得被覆盖掉的层清空", "kept", loaded.get("framework.test.only.root"));
    }

    /** 显式单文件优先级最高，且 HOCON 嵌套对象在磁盘来源同样递归扁平化。 */
    @Test
    public void explicitFileWinsAndHoconIsFlattened() throws Exception {
        File root = folder.newFolder("with-explicit-file");
        write(new File(root, "pw.properties"), "framework.test.key=from-directory\n");
        File explicit = folder.newFile("override.conf");
        write(explicit, "framework { test { key = \"from-explicit\" } }\n");
        File explicitProps = new File(root, "extra.properties");
        write(explicitProps, "framework.test.extra=extra-value\n");

        Map<String, String> loaded = FileConfigResolver.load(
                List.of(root.toPath()), List.of(explicit.toPath(), explicitProps.toPath()));

        assertEquals("from-explicit", loaded.get("framework.test.key"));
        assertEquals("extra-value", loaded.get("framework.test.extra"));
    }

    /** 目录 / 文件缺失时优雅降级，不抛异常、不污染其它来源。 */
    @Test
    public void missingExternalSourcesAreSkippedGracefully() throws Exception {
        File empty = folder.newFolder("empty-root");
        Path absent = new File(empty, "no-such-dir").toPath();

        Map<String, String> loaded = FileConfigResolver.load(
                List.of(empty.toPath(), absent), List.of(absent.resolve("missing.conf")));

        assertNotNull(loaded);
        assertNull(loaded.get("framework.test.absent.key"));
    }

    /** -Dpw.config.dir 显式目录端到端生效（CI 把配置放到工程外路径的场景）。 */
    @Test
    public void explicitDirectorySystemPropertyIsHonoured() throws Exception {
        File external = folder.newFolder("external-config");
        write(new File(external, "pw.properties"), "framework.test.external.key=external-value\n");

        String previous = System.getProperty(FileConfigResolver.CONFIG_DIR_PROPERTY);
        try {
            System.setProperty(FileConfigResolver.CONFIG_DIR_PROPERTY, external.getAbsolutePath());
            assertEquals("external-value",
                    new FileConfigResolver().resolve("framework.test.external.key"));
        } finally {
            restore(FileConfigResolver.CONFIG_DIR_PROPERTY, previous);
        }
    }

    /** 显式位置指向不存在的目录时只告警，不得打断配置层。 */
    @Test
    public void missingExplicitDirectoryDoesNotBreakResolve() {
        String previous = System.getProperty(FileConfigResolver.CONFIG_DIR_PROPERTY);
        try {
            System.setProperty(FileConfigResolver.CONFIG_DIR_PROPERTY,
                    new File(folder.getRoot(), "definitely-absent").getAbsolutePath());
            assertNull(new FileConfigResolver().resolve("framework.test.absent.key"));
        } finally {
            restore(FileConfigResolver.CONFIG_DIR_PROPERTY, previous);
        }
    }

    @Test
    public void projectRootIsAbsoluteAndNormalized() {
        Path root = FileConfigResolver.projectRoot();
        // user.dir 在某些嵌入容器里可能缺省；存在时必须是可用的绝对路径
        if (root != null) {
            assertTrue(root.isAbsolute());
            assertEquals(root.normalize(), root);
        }
    }

    private static void write(File file, String content) throws Exception {
        Files.write(file.toPath(), content.getBytes(StandardCharsets.UTF_8));
    }

    private static void restore(String key, String previous) {
        if (previous == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, previous);
        }
    }
}
