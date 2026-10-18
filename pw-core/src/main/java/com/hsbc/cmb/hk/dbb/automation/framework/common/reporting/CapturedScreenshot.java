package com.hsbc.cmb.hk.dbb.automation.framework.common.reporting;

import java.io.File;
import java.util.Objects;

/**
 * 原生截图持有者（替代 serenity 的 {@code ScreenshotAndHtmlSource}）。
 *
 * <p>只承载截图文件与展示名，不耦合任何报告引擎；实际挂载到报告由
 * {@link ResultReporter#attachScreenshot} 的实现方（如 serenity 接线层）完成。
 * 定义于 {@code framework.common} 以便 web / reports 各模块共享引用。
 */
public final class CapturedScreenshot {

    private final File pngFile;
    private final String name;

    public CapturedScreenshot(File pngFile, String name) {
        this.pngFile = Objects.requireNonNull(pngFile, "pngFile");
        this.name = name;
    }

    public File pngFile() {
        return pngFile;
    }

    public String name() {
        return name;
    }
}
