package com.hsbc.cmb.hk.dbb.automation.framework.web.config;

import com.hsbc.cmb.hk.dbb.automation.framework.common.config.FrameworkFlags;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/**
 * 2026-11-12 线上 {@code StackOverflowError} 的端到端回归。
 *
 * <p>失败链：{@code logback %msg → SanitizingMessageConverter → FrameworkFlags → ConfigReader
 * → FileConfigResolver.load() 打 INFO → 又回到 %msg}。当时 Cucumber Runner 在
 * 0.026s 内初始化即失败，一个 scenario 都没跑到。
 *
 * <p>这组用例同时锁定三层防御：{@code FrameworkFlags} 断边、快照先发布后打日志、
 * 加载期同线程重入返回空快照。
 */
public class FileConfigResolverRegressionTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    /**
     * 复刻线上触发条件：存在配置文件 ⇒ {@code loaded} 非空 ⇒ 加载完成会打 INFO。
     * 修复前该 INFO 在快照发布之前打印，同线程重入时 {@code flattened} 恒为 null。
     */
    @Test
    public void firstResolveWithLoadedSourcesNeverRecurses() throws Exception {
        File root = folder.newFolder("recursion-root");
        write(new File(root, "pw.properties"), "framework.test.recursion.guard=loaded\n");
        String previous = System.getProperty("user.dir");
        try {
            System.setProperty("user.dir", root.getAbsolutePath());

            assertEquals("首次加载必须完成并命中所求键",
                    "loaded", new FileConfigResolver().resolve("framework.test.recursion.guard"));
        } finally {
            restore("user.dir", previous);
        }
    }

    /**
     * 加载<b>过程中</b>打的日志（此处用 HOCON 语法错触发 WARN）同样不得递归。
     * 这条 WARN 发生在快照发布之前，专门覆盖 {@code FileConfigResolver.snapshot()} 的
     * 同线程重入保护（仅靠「先发布后打 INFO」是覆盖不到的）。
     */
    @Test
    public void warnDuringLoadDoesNotRecurse() throws Exception {
        File root = folder.newFolder("warn-during-load-root");
        write(new File(root, "pw.properties"), "framework.test.warn.guard=ok\n");
        write(new File(root, "pw.conf"), "unclosed = { \n");
        String previous = System.getProperty("user.dir");
        try {
            System.setProperty("user.dir", root.getAbsolutePath());

            assertEquals("解析失败的那一路被跳过，其余来源仍须生效",
                    "ok", new FileConfigResolver().resolve("framework.test.warn.guard"));
        } finally {
            restore("user.dir", previous);
        }
    }

    /**
     * 断边契约（根因）：文件层确实有该键，但 {@code FrameworkFlags} <b>不得</b>读到它。
     * 只要有人把 {@code FrameworkFlags.resolve} 改回 {@code ConfigReader.getProperty}，
     * 本用例立即转红——这正是递归重新接上的那一刻。
     */
    @Test
    public void frameworkFlagsNeverReadsFileConfigLayer() throws Exception {
        File root = folder.newFolder("flag-layer-root");
        write(new File(root, "pw.properties"), "framework.test.file.only=from-file\n");
        String previous = System.getProperty("user.dir");
        try {
            System.setProperty("user.dir", root.getAbsolutePath());

            assertEquals("前置条件：文件层确实包含该键",
                    "from-file", new FileConfigResolver().resolve("framework.test.file.only"));
            assertNull("FrameworkFlags 只认系统属性/环境变量，绝不经过文件配置层",
                    FrameworkFlags.resolve("framework.test.file.only", null));
        } finally {
            restore("user.dir", previous);
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
