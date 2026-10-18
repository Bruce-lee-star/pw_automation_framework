package com.hsbc.cmb.hk.dbb.automation.framework.report.render;

import com.hsbc.cmb.hk.dbb.automation.framework.report.model.TestOutcome;
import com.hsbc.cmb.hk.dbb.automation.framework.report.model.TestResult;
import com.hsbc.cmb.hk.dbb.automation.framework.report.model.UserStory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CsvRenderTest {

    @TempDir
    Path dir;

    @Test
    void writesHeaderAndOneRowPerOutcome() throws Exception {
        CsvWriter.writeCsv(dir, List.of(OutcomeFixtures.full()));

        Path csv = dir.resolve("serenity-summary.csv");
        assertTrue(Files.exists(csv), "serenity-summary.csv should be produced");

        List<String> lines = Files.readAllLines(csv);
        assertEquals("outcomeId,feature,scenario,result,durationMs,startTime,endTime,tags,"
                + "failureClass,failureMessage", lines.get(0));
        assertEquals(2, lines.size(), "header + 1 outcome row");

        String row = lines.get(1);
        assertTrue(row.contains("User logs in with valid credentials"), "scenario name");
        assertTrue(row.contains("Authentication"), "feature");
        assertTrue(row.contains(",FAILURE,"), "result column");
        assertTrue(row.contains("java.lang.AssertionError"), "failure class");
        assertTrue(row.contains("smoke;login"), "tags joined by ;");
    }

    @Test
    void quotesFieldsContainingComma() throws Exception {
        TestOutcome o = new TestOutcome();
        o.setOutcomeId("o1");
        o.setName("open, then close");
        o.setUserStory(new UserStory("F", "F", "f.feature"));
        o.setResult(TestResult.SUCCESS);
        o.setDuration(10);
        o.setTags(List.of("a", "b"));
        CsvWriter.writeCsv(dir, List.of(o));

        String row = Files.readAllLines(dir.resolve("serenity-summary.csv")).get(1);
        // name contains a comma → must be wrapped in quotes
        assertTrue(row.contains("\"open, then close\""), "comma field quoted");
        assertTrue(row.contains("a;b"), "tags joined by ;");
    }
}
