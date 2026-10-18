package com.hsbc.cmb.hk.dbb.automation.framework.report.render;

import com.hsbc.cmb.hk.dbb.automation.framework.report.model.TestOutcome;
import com.hsbc.cmb.hk.dbb.automation.framework.report.model.TestResult;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * JUnit 兼容 XML——Serenity 对齐产物（CI / Surefire 聚合 / 第三方仪表盘消费）。
 *
 * <p>文件名 {@code serenity-junit.xml}；按 feature（UserStory）分组为多个 {@code <testsuite>}，
 * 每用例一个 {@code <testcase>}。结果映射：FAILURE→{@code <failure>}、ERROR→{@code <error>}、
 * SKIPPED/IGNORED/UNDEFINED→{@code <skipped>}、SUCCESS/PENDING→通过。用例级 stdout/stderr
 * 经 {@code <system-out>}/{@code <system-err>} 输出（含已捕获的控制台输出）。</p>
 */
public final class JunitXmlWriter {

    private JunitXmlWriter() {
    }

    /** 写 {@code <dir>/serenity-junit.xml}；返回目标路径。 */
    public static Path writeXml(Path dir, List<TestOutcome> outcomes) throws IOException {
        Files.createDirectories(dir);
        Path target = dir.resolve("serenity-junit.xml");

        Map<String, List<TestOutcome>> byFeature = new LinkedHashMap<>();
        for (TestOutcome o : outcomes) {
            String feature = o.getUserStory() == null ? "Unknown" : o.getUserStory().getStoryName();
            byFeature.computeIfAbsent(feature, k -> new ArrayList<>()).add(o);
        }

        int totalTests = 0;
        int totalFailures = 0;
        int totalErrors = 0;
        int totalSkipped = 0;
        double totalTime = 0.0;

        StringBuilder suites = new StringBuilder();
        for (Map.Entry<String, List<TestOutcome>> entry : byFeature.entrySet()) {
            String feature = entry.getKey();
            List<TestOutcome> list = entry.getValue();
            int tests = list.size();
            int failures = 0;
            int errors = 0;
            int skipped = 0;
            double time = 0.0;
            StringBuilder cases = new StringBuilder();
            for (TestOutcome o : list) {
                TestResult r = o.getResult();
                double caseTime = o.getDuration() / 1000.0;
                time += caseTime;
                cases.append("    <testcase name=\"").append(xml(o.getName()))
                        .append("\" classname=\"").append(xml(feature))
                        .append("\" time=\"").append(fmt(caseTime)).append("\">\n");
                if (r == TestResult.FAILURE) {
                    failures++;
                    cases.append("      <failure message=\"").append(xml(o.getTestFailureMessage()))
                            .append("\" type=\"").append(xml(o.getTestFailureClassname()))
                            .append("\"/>\n");
                } else if (r == TestResult.ERROR) {
                    errors++;
                    cases.append("      <error message=\"").append(xml(o.getTestFailureMessage()))
                            .append("\" type=\"").append(xml(o.getTestFailureClassname()))
                            .append("\"/>\n");
                } else if (r == TestResult.SKIPPED || r == TestResult.IGNORED || r == TestResult.UNDEFINED) {
                    skipped++;
                    cases.append("      <skipped/>\n");
                }
                if (o.getConsoleOutput() != null && !o.getConsoleOutput().isEmpty()) {
                    cases.append("      <system-out><![CDATA[")
                            .append(cdata(o.getConsoleOutput())).append("]]></system-out>\n");
                }
                if (o.getConsoleError() != null && !o.getConsoleError().isEmpty()) {
                    cases.append("      <system-err><![CDATA[")
                            .append(cdata(o.getConsoleError())).append("]]></system-err>\n");
                }
                cases.append("    </testcase>\n");
            }
            suites.append("  <testsuite name=\"").append(xml(feature))
                    .append("\" tests=\"").append(tests)
                    .append("\" failures=\"").append(failures)
                    .append("\" errors=\"").append(errors)
                    .append("\" skipped=\"").append(skipped)
                    .append("\" time=\"").append(fmt(time)).append("\">\n")
                    .append(cases)
                    .append("  </testsuite>\n");
            totalTests += tests;
            totalFailures += failures;
            totalErrors += errors;
            totalSkipped += skipped;
            totalTime += time;
        }

        StringBuilder root = new StringBuilder();
        root.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        root.append("<testsuites name=\"pw-reports\" tests=\"").append(totalTests)
                .append("\" failures=\"").append(totalFailures)
                .append("\" errors=\"").append(totalErrors)
                .append("\" skipped=\"").append(totalSkipped)
                .append("\" time=\"").append(fmt(totalTime)).append("\">\n")
                .append(suites)
                .append("</testsuites>\n");
        Files.writeString(target, root.toString(), StandardCharsets.UTF_8);
        return target;
    }

    private static String fmt(double v) {
        return String.format(Locale.ROOT, "%.3f", v);
    }

    private static String xml(String v) {
        if (v == null) {
            return "";
        }
        return v.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&apos;");
    }

    /** CDATA 段内不得出现 {@code ]]>}；拆段避免截断。 */
    private static String cdata(List<String> lines) {
        return String.join("\n", lines).replace("]]>", "]]]]><![CDATA[>");
    }
}
