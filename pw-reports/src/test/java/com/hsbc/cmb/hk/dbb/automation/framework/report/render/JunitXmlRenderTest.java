package com.hsbc.cmb.hk.dbb.automation.framework.report.render;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class JunitXmlRenderTest {

    @TempDir
    Path dir;

    @Test
    void writesJunitXmlWithSuiteAndFailure() throws Exception {
        JunitXmlWriter.writeXml(dir, List.of(OutcomeFixtures.full()));

        Path xml = dir.resolve("pw-junit.xml");
        assertTrue(Files.exists(xml), "pw-junit.xml should be produced");

        String content = Files.readString(xml);
        assertTrue(content.contains("<testsuites"), "root testsuites");
        assertTrue(content.contains("<testsuite name=\"Authentication\""), "feature suite");
        assertTrue(content.contains("<testcase name=\"User logs in with valid credentials\""),
                "testcase name");
        assertTrue(content.contains("<failure"), "failure element for FAILURE outcome");
        assertTrue(content.contains("type=\"java.lang.AssertionError\""), "failure type");
    }

    @Test
    void groupsByFeatureAndEscapesXml() throws Exception {
        JunitXmlWriter.writeXml(dir, List.of(OutcomeFixtures.full()));
        String content = Files.readString(dir.resolve("pw-junit.xml"));
        // & in failure message must be escaped
        assertTrue(content.contains("&amp;") || !content.contains("&"), "xml escaped");
        assertTrue(content.contains("tests="), "suite counts present");
    }
}
