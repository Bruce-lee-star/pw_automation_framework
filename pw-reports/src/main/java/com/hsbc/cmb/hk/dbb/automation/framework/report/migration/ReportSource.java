package com.hsbc.cmb.hk.dbb.automation.framework.report.migration;

import java.nio.file.Path;
import java.util.List;

/**
 * 报告源抽象——把某个报告目录归一化成 {@link CanonicalOutcome} 列表。
 * pw-reports 与 Serenity 各有一实现；门禁只比对归一化结果。
 */
public interface ReportSource {

    /** 源标识（用于日志/报告）。 */
    String sourceName();

    /** 加载目录下所有用例的归一化视图（缺目录/缺文件 → 空列表，不抛）。 */
    List<CanonicalOutcome> load(Path dir);
}
