package com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.bootstrap;

import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.PlaywrightManager;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.context.CustomOptionsManager;
import com.microsoft.playwright.Browser;
import org.junit.After;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * storageState 冷启动加载契约（基于 Playwright 1.62 官方 API 事实）。
 *
 * <p><b>API 事实</b>：{@code Browser.NewContextOptions} 同时提供两个原生重载——
 * {@code setStorageState(String)}（直接收 JSON 文本）与 {@code setStorageStatePath(Path)}（文件路径），
 * 两者均合法、<b>无需临时文件</b>。「直接传 JSON 字符串会抛 expected object, got string」只发生在
 * <b>已启动的 live {@code BrowserContext.setStorageState}</b>（该重载仅接受 Path）——那是另一回事。</p>
 *
 * <p><b>本测试覆盖的 newContext 路径语义（文件优先、内存兜底）</b>：
 * <ul>
 *   <li>仅内存 JSON：{@code options.storageState} 携带原 JSON，{@code storageStatePath} 为 null（不落临时文件）；</li>
 *   <li>仅文件路径：{@code options.storageStatePath} 原样生效（不重复落盘），{@code storageState} 为 null；</li>
 *   <li>路径优先：两者同设时，文件路径胜出，内存 JSON 被忽略（避免陈旧内存快照覆盖权威落盘凭证）。</li>
 * </ul>
 */
public class PlaywrightContextManagerStorageStateTest {

    private static final String SAMPLE_JSON = "{\"cookies\":[{\"name\":\"a\",\"value\":\"b\"}],\"origins\":[]}";

    @After
    public void cleanup() {
        // 逐用例清理 per-thread 自定义选项，避免残留污染同 JVM 的其它用例
        CustomOptionsManager.removeAllThreadLocals();
    }

    @Test
    public void inMemoryStorageStateIsAppliedAsRawString() {
        PlaywrightManager.customOptions().setStorageStateWithoutRebuild(SAMPLE_JSON);

        Browser.NewContextOptions options = new Browser.NewContextOptions();
        PlaywrightContextManager.configureCustomContextOptions(options);

        assertEquals("内存 JSON 应直接交给 NewContextOptions.setStorageState（原生支持，无需临时文件）",
                SAMPLE_JSON, options.storageState);
        assertNull("内存来源不得误用路径入口", options.storageStatePath);
    }

    @Test
    public void existingStorageStatePathIsAppliedAsIs() throws Exception {
        Path file = Files.createTempFile("pw-storage-test-", ".json");
        Files.write(file, SAMPLE_JSON.getBytes(StandardCharsets.UTF_8));

        try {
            PlaywrightManager.customOptions().setStorageStatePathWithoutRebuild(file);

            Browser.NewContextOptions options = new Browser.NewContextOptions();
            PlaywrightContextManager.configureCustomContextOptions(options);

            assertNull("字符串入口不得被使用", options.storageState);
            assertEquals("路径来源应原样生效（不重复落盘）", file, options.storageStatePath);
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    public void missingStorageStatePathIsIgnored() {
        Path missing = java.nio.file.Paths.get("target", "definitely-missing-storageState.json");
        PlaywrightManager.customOptions().setStorageStatePathWithoutRebuild(missing);

        Browser.NewContextOptions options = new Browser.NewContextOptions();
        PlaywrightContextManager.configureCustomContextOptions(options);

        assertNull("不存在的路径不得下发（否则驱动读文件报错）", options.storageStatePath);
        assertNull(options.storageState);
    }

    @Test
    public void filePathTakesPrecedenceOverInMemoryJson() throws Exception {
        Path file = Files.createTempFile("pw-storage-precedence-", ".json");
        Files.write(file, SAMPLE_JSON.getBytes(StandardCharsets.UTF_8));

        try {
            // 同设内存 JSON 与文件路径：文件路径应优先（权威落盘凭证覆盖陈旧内存快照）
            PlaywrightManager.customOptions().setStorageStateWithoutRebuild("{\"cookies\":[],\"origins\":[]}");
            PlaywrightManager.customOptions().setStorageStatePathWithoutRebuild(file);

            Browser.NewContextOptions options = new Browser.NewContextOptions();
            PlaywrightContextManager.configureCustomContextOptions(options);

            assertEquals("文件路径应优先", file, options.storageStatePath);
            assertNull("文件路径优先时内存 JSON 不得下发", options.storageState);
        } finally {
            Files.deleteIfExists(file);
        }
    }
}
