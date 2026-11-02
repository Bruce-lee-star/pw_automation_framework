package com.hsbc.cmb.hk.dbb.automation.framework.report.render;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 静态资产拷贝——自有 CSS（{@code /assets/report.css}，不来自任何 serenity JAR）。
 * 幂等：目标已存在且大小一致则跳过；失败只记日志。
 */
public final class StaticAssets {

    private static final Logger log = LoggerFactory.getLogger(StaticAssets.class);

    /**
     * 随报告发布的静态资产。
     *
     * <p>{@code report-v2.css} / {@code report.js} 是新一代报告（对齐 docs/template-design）；
     * 旧 {@code report.css} 仍被尚未迁移的模板引用，待全部迁移完成后再统一收名（届时删除旧文件）。
     */
    private static final String[] ASSETS = {
            "assets/report.css", "assets/report-v2.css", "assets/report.js", "assets/favicon.svg"};

    private StaticAssets() {
    }

    public static void copyAssets(Path dir) {
        for (String asset : ASSETS) {
            try (InputStream in = StaticAssets.class.getResourceAsStream("/" + asset)) {
                if (in == null) {
                    log.warn("asset {} not found on classpath, skipped", asset);
                    continue;
                }
                Path target = dir.resolve(asset);
                if (Files.exists(target) && Files.size(target) == in.available()) {
                    continue; // 幂等：同内容不重写
                }
                Files.createDirectories(target.getParent());
                Files.copy(in, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                log.warn("asset copy failed for {}: {}", asset, e.toString());
            }
        }
    }
}
