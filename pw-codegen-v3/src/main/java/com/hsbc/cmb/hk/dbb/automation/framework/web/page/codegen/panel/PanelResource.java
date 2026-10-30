package com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen.panel;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * 解析面板资源（panel.html / panel.js / panel.css）的本地文件路径。
 *
 * <p>开发态（classes 在文件系统）直接用 {@code src/main/resources/codegen/panel.html}；打成 JAR 后导出到
 * 临时目录，并把同目录的 panel.js / panel.css 一并导出，使相对引用（{@code <script src="panel.js">}）仍可用。
 */
public final class PanelResource {

    private static final Logger log = LoggerFactory.getLogger(PanelResource.class);

    private PanelResource() {}

    public static Path resolvePanelHtml() {
        try {
            Path dev = Path.of("src/main/resources/codegen/panel.html");
            if (Files.exists(dev)) {
                log.info("[codegen][panel] using dev resource: {}", dev.toAbsolutePath());
                return dev.toAbsolutePath();
            }
            // 必须用「本次运行独占的临时目录」：旧实现把 panel.html 建在系统临时目录、panel.js/panel.css
            // 以「同名即跳过」方式复用到该共享目录，一旦本机曾运行过旧版本，panel.js 就永久停留在旧内容，
            // 新 jar 的 Java 侧与旧 JS 侧 API 不匹配 ⇒ 面板按钮点了没反应且无任何报错。
            Path dir = Files.createTempDirectory("codegen-panel-");
            Path html = dir.resolve("panel.html");
            export("panel.html", html);
            export("panel.js", dir.resolve("panel.js"));
            export("panel.css", dir.resolve("panel.css"));
            html.toFile().deleteOnExit();
            dir.resolve("panel.js").toFile().deleteOnExit();
            dir.resolve("panel.css").toFile().deleteOnExit();
            dir.toFile().deleteOnExit();
            log.info("[codegen][panel] exported panel resources to temp dir: {}", dir);
            return html;
        } catch (IOException e) {
            throw new RuntimeException("cannot resolve panel.html resource", e);
        }
    }

    private static void export(String name, Path target) throws IOException {
        try (InputStream in = PanelResource.class.getClassLoader().getResourceAsStream("codegen/" + name)) {
            if (in == null) throw new IOException("codegen/" + name + " not found in classpath");
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
