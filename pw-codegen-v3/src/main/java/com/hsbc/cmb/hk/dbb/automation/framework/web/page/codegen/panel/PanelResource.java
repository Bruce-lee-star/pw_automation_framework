package com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen.panel;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * 解析面板资源（panel.html / panel.js / panel.css）的本地文件路径。
 *
 * <p>开发态（classes 在文件系统）直接用 {@code src/main/resources/codegen/panel.html}；打成 JAR 后导出到
 * 临时目录，并把同目录的 panel.js / panel.css 一并导出，使相对引用（{@code <script src="panel.js">}）仍可用。
 *
 * <p><b>B 轨道 2.6（固定导出路径）</b>：导出目录从「每次随机 {@code createTempDirectory}」改为
 * 「{@code %TEMP%\codegen-panel-<hash>}」—— hash 取三份资源内容的 SHA-256。同一 JVM（同一份 jar）内
 * 内容不变 ⇒ 目录恒定，而面板常驻进程的 HTTP server 在 launch 时捕获一次 serveDir 即跨会话稳定，
 * 消除旧实现「第 2 会话 404 / NLS 陈旧」（serveDir 每会话漂移，见方案文档 P0-3）；jar 内容一旦变化
 * （新版本部署 ⇒ 新 hash）目录自然换新，不会与旧进程的资源互相污染。
 */
public final class PanelResource {

    private static final Logger log = LoggerFactory.getLogger(PanelResource.class);

    private static final String[] RESOURCE_NAMES = {"panel.html", "panel.js", "panel.css"};

    private PanelResource() {}

    public static Path resolvePanelHtml() {
        try {
            Path dev = Path.of("src/main/resources/codegen/panel.html");
            if (Files.exists(dev)) {
                log.info("[codegen][panel] using dev resource: {}", dev.toAbsolutePath());
                return dev.toAbsolutePath();
            }
            Path dir = panelExportDir();
            Files.createDirectories(dir);
            Path html = dir.resolve("panel.html");
            for (String name : RESOURCE_NAMES) {
                export(name, dir.resolve(name));
            }
            for (String name : RESOURCE_NAMES) {
                dir.resolve(name).toFile().deleteOnExit();
            }
            log.info("[codegen][panel] exported panel resources to fixed dir: {}", dir);
            return html;
        } catch (IOException e) {
            throw new RuntimeException("cannot resolve panel.html resource", e);
        }
    }

    /**
     * 固定导出目录：{@code %TEMP%\codegen-panel-<hash>}。
     *
     * <p>内容 hash 保证：同一内容 ⇒ 同一目录（常驻 serveDir 稳定）；内容变化 ⇒ 目录自动换新。
     */
    static Path panelExportDir() {
        return Path.of(System.getProperty("java.io.tmpdir"), "codegen-panel-" + contentHash()).toAbsolutePath();
    }

    /** 三份资源内容的 SHA-256 前 16 个 hex 字符（目录名不可见字符排除；碰撞面 2^-64，可忽略）。 */
    private static String contentHash() {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            for (String name : RESOURCE_NAMES) {
                try (InputStream in = open(name)) {
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) != -1) {
                        md.update(buf, 0, n);
                    }
                }
            }
            byte[] digest = md.digest();
            StringBuilder hex = new StringBuilder(16);
            for (int i = 0; i < 8; i++) {
                hex.append(String.format("%02x", digest[i]));
            }
            return hex.toString();
        } catch (IOException | NoSuchAlgorithmException e) {
            throw new RuntimeException("cannot hash panel resources", e);
        }
    }

    private static InputStream open(String name) throws IOException {
        InputStream in = PanelResource.class.getClassLoader().getResourceAsStream("codegen/" + name);
        if (in == null) {
            throw new IOException("codegen/" + name + " not found in classpath");
        }
        return in;
    }

    private static void export(String name, Path target) throws IOException {
        try (InputStream in = open(name)) {
            // REPLACE_EXISTING：固定目录可能残留旧内容，必须覆盖为当前 jar 内容。
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
