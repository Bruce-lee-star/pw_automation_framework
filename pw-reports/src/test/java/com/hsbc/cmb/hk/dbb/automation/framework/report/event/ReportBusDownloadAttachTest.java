package com.hsbc.cmb.hk.dbb.automation.framework.report.event;

import com.hsbc.cmb.hk.dbb.automation.framework.report.config.PwReportsConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 下载文件挂载契约（{@link ReportBus#attachDownloadableFile}）：
 * 文件被复制进 {@code <outputDir>/<outcomeId>/<file>}，并<b>结构化登记</b>为
 * {@code TestOutcome.downloads}（供 per-case 报告「Downloads」Tab 罗列/链接）。
 */
class ReportBusDownloadAttachTest {

    @Test
    @DisplayName("attachDownloadableFile：归档到 per-case 目录并登记 DownloadRecord（additive-only）")
    void archivesFileAndRegistersDownloadRecord() throws Exception {
        Path dir = Files.createTempDirectory("pw-dl-attach");
        ReportBus bus = new ReportBus(PwReportsConfig.builder().enabled(true).outputDir(dir).build());
        StepHandle h = bus.startCase("c1", "feature", "scenario", List.of("t"), "feature.feature");

        Path src = Files.createTempDirectory("pw-dl-src").resolve("report.csv");
        Files.write(src, "a,b\n1,2\n".getBytes());

        bus.attachDownloadableFile(h, src);

        //  ① 文件已归档到 per-case 目录（与报告页同目录，Tab 内以 fileName 作相对链接）
        Path archived = dir.resolve("c1").resolve("report.csv");
        assertThat(Files.exists(archived)).isTrue();
        assertThat(new String(Files.readAllBytes(archived))).isEqualTo("a,b\n1,2\n");

        //  ② 结构化登记：TestOutcome.downloads 含一条记录（title/fileName/relativePath/timestamp）
        var downloads = bus.outcomeSnapshots().get(0).getDownloads();
        assertThat(downloads).hasSize(1);
        assertThat(downloads.get(0).fileName()).isEqualTo("report.csv");
        assertThat(downloads.get(0).relativePath()).isEqualTo("c1/report.csv");
        assertThat(downloads.get(0).title()).contains("report.csv");
        assertThat(downloads.get(0).timestamp()).isNotBlank();
    }
}
