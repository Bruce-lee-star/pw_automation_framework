package com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen.locator.candidate;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Locale;
import java.util.Set;

import static org.junit.Assert.assertFalse;

/**
 * P4 third cut: R1-R9 layered tests plus ArchUnit structural guards.
 * ArchUnit only checks structural constraints; it does not replace behavioral tests.
 *
 * Three guards:
 *  1) R4 isolation + R6 AI has no execution right: the governance closure must not depend on
 *     the runtime browser (pw-web-ui / Playwright) or the LLM analysis layer (agent.analysis /
 *     agent.llm). Evidence collection uses dependency inversion via the EvidenceProbe interface.
 *  2) R7 data minimization: within the candidate package, every class except the explicit IO
 *     boundary classes (and their nested classes) must not depend on file IO or locks
 *     (java.io / java.nio.file / java.nio.channels). Pure governance logic stays in-memory.
 *  3) Single approval entrypoint / no bypass: CandidateLibrary must not expose public methods that
 *     would set APPROVED while bypassing ApprovalPolicy (force* / bypass* / setState / direct*).
 */
public class ArchitectureTest {

    private static final String PKG =
            "com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen.locator.candidate";

    private static final JavaClasses CLASSES = new ClassFileImporter()
            .withImportOption(new ImportOption.DoNotIncludeTests())
            .importPackages(PKG);

    private static final DescribedPredicate<? super JavaClass> FORBIDDEN_RUNTIME_LLM =
            JavaClass.Predicates.resideInAnyPackage(
                    "..web.ui..", "..playwright..", "..agent.analysis..", "..agent.llm..");

    private static final DescribedPredicate<? super JavaClass> FORBIDDEN_IO =
            JavaClass.Predicates.resideInAnyPackage(
                    "java.io..", "java.nio.file..", "java.nio.channels..");

    /**
     * Explicit IO boundary classes: the only places in the candidate closure allowed to touch
     * file IO / locks. Nested classes (e.g. CandidateCli$Options) are covered via the "$" prefix.
     */
    private static final Set<String> IO_BOUNDARY_FQNS = Set.of(
            PKG + ".CandidateCli",
            PKG + ".CandidateLibrary",
            PKG + ".LibraryLock",
            PKG + ".CandidateEvidenceJson",
            PKG + ".CandidateIntake",
            PKG + ".CandidateAuditReport");

    private static final DescribedPredicate<JavaClass> NOT_IO_BOUNDARY =
            new DescribedPredicate<JavaClass>(
                    "not an explicit IO boundary class (incl. its nested classes)") {
                @Override
                public boolean test(JavaClass clazz) {
                    String name = clazz.getName();
                    for (String base : IO_BOUNDARY_FQNS) {
                        if (name.equals(base) || name.startsWith(base + "$")) {
                            return false;
                        }
                    }
                    return true;
                }
            };

    @Test
    public void governanceClosureIsolatedFromRuntimeBrowserAndLlm() {
        com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses()
                .should().dependOnClassesThat(FORBIDDEN_RUNTIME_LLM)
                .check(CLASSES);
    }

    @Test
    public void governanceLogicIsPureInMemory() {
        com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses()
                .that(NOT_IO_BOUNDARY)
                .should().dependOnClassesThat(FORBIDDEN_IO)
                .check(CLASSES);
    }

    @Test
    public void approvalEntrypointHasNoBypass() {
        for (Method m : CandidateLibrary.class.getDeclaredMethods()) {
            if (!Modifier.isPublic(m.getModifiers())) {
                continue;
            }
            String name = m.getName().toLowerCase(Locale.ROOT);
            assertFalse("CandidateLibrary must not expose a bypass entrypoint: " + m.getName(),
                    name.contains("force") || name.contains("bypass")
                            || name.equals("setstate") || name.contains("direct"));
        }
    }
}
