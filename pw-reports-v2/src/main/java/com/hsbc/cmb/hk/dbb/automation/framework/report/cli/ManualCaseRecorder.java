package com.hsbc.cmb.hk.dbb.automation.framework.report.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hsbc.cmb.hk.dbb.automation.framework.report.model.TestOutcome;
import com.hsbc.cmb.hk.dbb.automation.framework.report.model.TestResult;
import com.hsbc.cmb.hk.dbb.automation.framework.report.model.TestStep;
import com.hsbc.cmb.hk.dbb.automation.framework.report.model.UserStory;
import com.hsbc.cmb.hk.dbb.automation.framework.report.cucumber.OutcomeIdFactory;
import com.hsbc.cmb.hk.dbb.automation.framework.report.render.AggregateBuilder;
import com.hsbc.cmb.hk.dbb.automation.framework.report.render.CsvWriter;
import com.hsbc.cmb.hk.dbb.automation.framework.report.render.HtmlRenderer;
import com.hsbc.cmb.hk.dbb.automation.framework.report.render.JsonWriter;
import com.hsbc.cmb.hk.dbb.automation.framework.report.render.JunitXmlWriter;
import com.hsbc.cmb.hk.dbb.automation.framework.report.render.StaticAssets;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 手动 case 录入 CLI——两种模式：
 *
 * <p><b>模式 A：新建手动 case</b>
 * <pre>
 * java -cp pw-reports-v2.jar ...ManualCaseRecorder \
 *   --dir target/site/serenity --feature "Login" --scenario "手动验证" \
 *   --result PASS --screenshot 1.png --note "人工验证"
 * </pre>
 *
 * <p><b>模式 B：给已有 case（通常是失败的自动 case）补传截图</b>
 * <pre>
 * ...ManualCaseRecorder --dir target/site/serenity \
 *   --append login-scenario-123 --screenshot 1.png --note "人工复现确认"
 * </pre>
 *
 * <p>截图统一时间戳命名 <code>yyyyMMdd-HHmmss-NN.png</code>。
 */
public final class ManualCaseRecorder {

    public static void main(String[] args) throws Exception {
        String dir = null, feature = null, scenario = null, result = null, note = null;
        String appendId = null;
        String applyPatch = null;
        List<String> screenshots = new ArrayList<>();

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--dir" -> dir = args[++i];
                case "--feature" -> feature = args[++i];
                case "--scenario" -> scenario = args[++i];
                case "--result" -> result = args[++i];
                case "--note" -> note = args[++i];
                case "--append" -> appendId = args[++i];
                case "--apply-patch" -> applyPatch = args[++i];
                case "--screenshot" -> screenshots.add(args[++i]);
                default -> {
                    System.err.println("unknown arg: " + args[i]);
                    System.exit(2);
                }
            }
        }
        if (dir == null) {
            System.err.println("missing --dir");
            System.exit(2);
        }
        Path outputDir = Paths.get(dir);
        Files.createDirectories(outputDir);

        ObjectMapper mapper = JsonWriter.mapper();
        String ts = ZonedDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));

        TestOutcome outcome;
        if (applyPatch != null) {
            // 模式 C：消费浏览器下载的 patch.json
            var patch = mapper.readValue(Paths.get(applyPatch).toFile(), java.util.Map.class);
            String pid = (String) patch.get("outcomeId");
            String pnote = (String) patch.getOrDefault("note", "");
            Path existingJson = outputDir.resolve(pid).resolve(pid + ".json");
            if (!Files.exists(existingJson)) {
                System.err.println("no such outcome: " + pid);
                System.exit(1);
            }
            outcome = mapper.readValue(existingJson.toFile(), TestOutcome.class);
            outcome.getEvidence().add("[manual patch] " + (pnote.isEmpty() ? "browser patch applied" : pnote));
            Path shotDir = outputDir.resolve(pid).resolve("screenshots");
            Files.createDirectories(shotDir);
            var patchShots = (java.util.List<?>) patch.get("screenshots");
            int idx = 1;
            for (Object o : patchShots) {
                var shot = (java.util.Map<?, ?>) o;
                String name = ts + "-" + String.format(Locale.ROOT, "%02d", idx++) + ".png";
                byte[] bytes = java.util.Base64.getDecoder().decode((String) shot.get("dataBase64"));
                Files.write(shotDir.resolve(name), bytes);
                TestStep s = new TestStep();
                s.setStepId("patch-" + ts + "-" + idx);
                s.setDescription("浏览器补图: " + shot.get("filename"));
                s.setResult(outcome.getResult() != null ? outcome.getResult() : TestResult.SUCCESS);
                s.setDuration(0);
                s.setScreenshot(name);
                outcome.getTestSteps().add(s);
            }
        } else if (appendId != null) {
            // 模式 B：追加到已有 JSON
            Path existingJson = outputDir.resolve(appendId).resolve(appendId + ".json");
            if (!Files.exists(existingJson)) {
                System.err.println("no such outcome: " + appendId);
                System.exit(1);
            }
            outcome = mapper.readValue(existingJson.toFile(), TestOutcome.class);
            outcome.getEvidence().add("[manual supplement] " +
                    (note != null ? note : "screenshots attached at " + ts));
        } else {
            // 模式 A：新建
            if (feature == null || scenario == null || result == null) {
                System.err.println("missing: --feature --scenario --result PASS|FAIL (or use --append <outcomeId>)");
                System.exit(2);
            }
            String outcomeId = OutcomeIdFactory.timestampedId(feature + ":" + scenario);
            TestResult tr = "PASS".equalsIgnoreCase(result) ? TestResult.SUCCESS : TestResult.FAILURE;
            ZonedDateTime now = ZonedDateTime.now();
            outcome = new TestOutcome();
            outcome.setOutcomeId(outcomeId);
            outcome.setName(feature + " - " + scenario);
            outcome.setUserStory(new UserStory(feature, feature, null));
            outcome.setResult(tr);
            outcome.setStartTime(now.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
            outcome.setEndTime(now.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
            outcome.setDuration(0);
            outcome.setManual(true);
            outcome.setManualNote(note);
            outcome.setTestSteps(new ArrayList<>());
        }

        // 拷截图 + 追加为步骤
        Path shotDir = outputDir.resolve(outcome.getOutcomeId()).resolve("screenshots");
        Files.createDirectories(shotDir);
        int idx = 1;
        for (String shotPath : screenshots) {
            Path src = Paths.get(shotPath);
            String name = ts + "-" + String.format(Locale.ROOT, "%02d", idx++) + ".png";
            Files.copy(src, shotDir.resolve(name), StandardCopyOption.REPLACE_EXISTING);
            TestStep s = new TestStep();
            s.setStepId("manual-shot-" + ts + "-" + idx);
            s.setDescription("手动补图: " + src.getFileName());
            s.setResult(outcome.getResult() != null ? outcome.getResult() : TestResult.SUCCESS);
            s.setDuration(0);
            s.setScreenshot(name);
            outcome.getTestSteps().add(s);
        }

        // 写 JSON
        JsonWriter.writeOutcome(outputDir, outcome);

        // 重扫全部 JSON，重渲染 index + per-case HTML。per-case JSON 现已位于
        // <outputDir>/<outcomeId>/<outcomeId>.json（2026-10-21 迁移），故递归遍历。
        List<TestOutcome> all = new ArrayList<>();
        try (var paths = Files.walk(outputDir)) {
            paths.filter(p -> {
                        String name = p.getFileName().toString();
                        return name.endsWith(".json") && !name.startsWith("serenity-summary")
                                && Files.isRegularFile(p);
                    })
                    .forEach(p -> {
                        try {
                            all.add(mapper.readValue(p.toFile(), TestOutcome.class));
                        } catch (Exception ignored) {
                        }
                    });
        }
        for (TestOutcome existing : all) {
            try {
                HtmlRenderer.renderOutcome(outputDir, existing);
            } catch (Exception ignored) {
            }
        }
        JsonWriter.writeSummary(outputDir, all);
        AggregateBuilder.writeIndex(outputDir, all);
        CsvWriter.writeCsv(outputDir, all);
        JunitXmlWriter.writeXml(outputDir, all);
        StaticAssets.copyAssets(outputDir);

        System.out.println("OK: " + outcome.getOutcomeId() + " (" + screenshots.size() + " screenshots)");
    }

    private ManualCaseRecorder() {
    }
}
