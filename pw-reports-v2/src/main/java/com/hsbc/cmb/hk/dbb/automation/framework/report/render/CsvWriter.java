package com.hsbc.cmb.hk.dbb.automation.framework.report.render;

import com.hsbc.cmb.hk.dbb.automation.framework.report.model.TestOutcome;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * 扁平 CSV 汇总——Serenity 对齐产物（报告可经表格工具 / CI 二次消费）。
 *
 * <p>文件名 {@code serenity-summary.csv}；一行一个用例，列：outcomeId / feature / scenario /
 * result / durationMs / startTime / endTime / tags / failureClass / failureMessage。
 * 字段按 RFC 4180 转义（含逗号 / 引号 / 换行时加引号并翻倍内部引号）。</p>
 */
public final class CsvWriter {

    private static final String[] HEADER = {
            "outcomeId", "feature", "scenario", "result", "durationMs",
            "startTime", "endTime", "tags", "failureClass", "failureMessage"
    };

    private CsvWriter() {
    }

    /** 写 {@code <dir>/serenity-summary.csv}；返回目标路径。 */
    public static Path writeCsv(Path dir, List<TestOutcome> outcomes) throws IOException {
        Files.createDirectories(dir);
        Path target = dir.resolve("serenity-summary.csv");
        StringBuilder sb = new StringBuilder();
        sb.append(String.join(",", HEADER)).append('\n');
        for (TestOutcome o : outcomes) {
            String feature = o.getUserStory() == null ? "" : o.getUserStory().getStoryName();
            String result = o.getResult() == null ? "UNKNOWN" : o.getResult().name();
            String tags = o.getTags() == null ? "" : String.join(";", o.getTags());
            sb.append(csvField(o.getOutcomeId())).append(',')
                    .append(csvField(feature)).append(',')
                    .append(csvField(o.getName())).append(',')
                    .append(csvField(result)).append(',')
                    .append(Long.toString(o.getDuration())).append(',')
                    .append(csvField(o.getStartTime())).append(',')
                    .append(csvField(o.getEndTime())).append(',')
                    .append(csvField(tags)).append(',')
                    .append(csvField(o.getTestFailureClassname())).append(',')
                    .append(csvField(o.getTestFailureMessage())).append('\n');
        }
        Files.writeString(target, sb.toString(), StandardCharsets.UTF_8);
        return target;
    }

    private static String csvField(String v) {
        if (v == null) {
            return "";
        }
        boolean needQuote = v.indexOf(',') >= 0 || v.indexOf('"') >= 0
                || v.indexOf('\n') >= 0 || v.indexOf('\r') >= 0;
        if (!needQuote) {
            return v;
        }
        return "\"" + v.replace("\"", "\"\"") + "\"";
    }
}
