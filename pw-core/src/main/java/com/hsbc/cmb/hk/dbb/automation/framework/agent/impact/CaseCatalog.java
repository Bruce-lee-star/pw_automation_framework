package com.hsbc.cmb.hk.dbb.automation.framework.agent.impact;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 存量用例目录：从 {@code *.feature} 解析出 {@link FeatureCase} 清单（变更影响筛选的输入侧）。
 *
 * <p><b>解析口径（刻意保守）</b>：
 * <ul>
 *   <li>只认 {@code Feature:} / {@code Scenario:} / {@code Scenario Outline:} / 步骤关键字行；</li>
 *   <li>{@code Background:} 下的步骤<b>不计入</b>任何场景（登录前置几乎每个场景都有，
 *       计入会让「几乎全部用例命中」而失去筛选意义）；</li>
 *   <li>{@code Examples:} 的表格行、注释行、空行一律忽略；</li>
 *   <li>标签按行累积：feature 级标签对所有场景生效，场景前的 {@code @tag} 只对其后第一个场景生效。</li>
 * </ul>
 * 解析只读文本、不执行任何 Glue，因此可以在离线/无浏览器环境跑。</p>
 */
public final class CaseCatalog {

    private static final String TAG = "@";
    private static final String[] STEP_KEYWORDS = {"Given ", "When ", "Then ", "And ", "But ", "* "};

    private CaseCatalog() {
    }

    /** 递归扫描目录下所有 {@code *.feature}（目录不存在时返回空列表，不抛异常）。 */
    public static List<FeatureCase> scan(Path featuresDir) throws IOException {
        List<FeatureCase> cases = new ArrayList<>();
        if (featuresDir == null || !Files.isDirectory(featuresDir)) {
            return cases;
        }
        List<Path> paths;
        try (var walk = Files.walk(featuresDir)) {
            paths = walk.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".feature"))
                    .sorted()
                    .collect(Collectors.toList());
        }
        for (Path path : paths) {
            String rel = featuresDir.relativize(path).toString().replace('\\', '/');
            cases.addAll(scanContent(Files.readString(path, StandardCharsets.UTF_8), rel));
        }
        cases.sort(Comparator.comparing(FeatureCase::featureFile).thenComparingInt(FeatureCase::line));
        return cases;
    }

    /** 解析单段 feature 文本（供单测与文件扫描复用）。 */
    public static List<FeatureCase> scanContent(String content, String sourceName) {
        List<FeatureCase> cases = new ArrayList<>();
        if (content == null || content.isBlank()) {
            return cases;
        }
        String featureName = "";
        List<String> featureTags = new ArrayList<>();
        List<String> pendingTags = new ArrayList<>();
        boolean inBackground = false;

        String currentScenario = null;
        List<String> currentTags = new ArrayList<>();
        List<String> currentSteps = new ArrayList<>();
        int currentLine = 0;

        String[] lines = content.split("\r?\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String raw = lines[i];
            String trimmed = raw.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            if (trimmed.startsWith(TAG)) {
                for (String tag : trimmed.split("\\s+")) {
                    if (tag.startsWith(TAG)) {
                        pendingTags.add(tag);
                    }
                }
                continue;
            }
            if (trimmed.startsWith("Feature:")) {
                featureName = afterColon(trimmed);
                featureTags.addAll(pendingTags);
                pendingTags.clear();
                continue;
            }
            if (trimmed.startsWith("Background:")) {
                inBackground = true;
                continue;
            }
            if (trimmed.startsWith("Scenario Outline:") || trimmed.startsWith("Scenario:")) {
                if (currentScenario != null) {
                    cases.add(new FeatureCase(sourceName, featureName, currentScenario,
                            currentTags, currentSteps, currentLine));
                }
                currentScenario = afterColon(trimmed);
                currentLine = i + 1;
                currentTags = new ArrayList<>(featureTags);
                currentTags.addAll(pendingTags);
                pendingTags.clear();
                currentSteps = new ArrayList<>();
                inBackground = false;
                continue;
            }
            if (trimmed.startsWith("Examples:")) {
                continue;
            }
            if (currentScenario != null && !inBackground) {
                String step = stepText(trimmed);
                if (step != null) {
                    currentSteps.add(step);
                }
            }
        }
        if (currentScenario != null) {
            cases.add(new FeatureCase(sourceName, featureName, currentScenario,
                    currentTags, currentSteps, currentLine));
        }
        return cases;
    }

    private static String afterColon(String line) {
        int idx = line.indexOf(':');
        return idx >= 0 ? line.substring(idx + 1).trim() : line.trim();
    }

    private static String stepText(String line) {
        for (String keyword : STEP_KEYWORDS) {
            if (line.startsWith(keyword)) {
                return line.substring(keyword.length()).trim();
            }
        }
        return null;
    }
}
