package io.codelaser.maddi.run.openjdkmain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.cli.ParseException;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.codelaser.maddi.util.corpus.Corpora;

/**
 * Jenkins core (enterprise/DI shape: mutable JavaBeans, Descriptor hierarchies, generated localizer
 * sources; 30k elements). Green since 2026-07-18 (certified fixpoint, ~12.5min at PARALLEL defaults).
 * Known residue: 10 test files dropped at scan (Messages-class resolution from the test source set).
 */
@Tag("slow")
public class TestJenkinsCore {

    @BeforeAll
    public static void beforeAll() {
        ((ch.qos.logback.classic.Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME)).setLevel(Level.INFO);
        ((ch.qos.logback.classic.Logger) LoggerFactory.getLogger("io.codelaser.maddi.shallow")).setLevel(Level.DEBUG);
        ((ch.qos.logback.classic.Logger) LoggerFactory
                .getLogger("io.codelaser.maddi.modification.link.impl.linkgraph.RedundantLinks")).setLevel(Level.ERROR);
    }

    private static final Path CONFIG = Corpora.oss("jenkins").config();

    @Test
    public void test() throws IOException, ParseException {
        // Ask the locator, do NOT test the file here: only the locator honours
        // -Dmaddi.corpus.required, which slowTest sets so that an absent corpus FAILS instead of
        // skipping. A hand-written assumeTrue reported the same green as a run that analysed the
        // whole corpus -- TestGuava skipped in 9 ms with no guava on disk and the build said
        // SUCCESSFUL (measured 2026-09-30).
        Corpora.oss("jenkins").requireConfig();
        ch.qos.logback.classic.Logger errorReport = (ch.qos.logback.classic.Logger) LoggerFactory
                .getLogger("io.codelaser.maddi.run.config.report.ErrorReport");
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        errorReport.addAppender(appender);
        int exitValue;
        try {
            exitValue = Main.execute(new String[]{
                    "--input-configuration=" + CONFIG
                    , "--analysis-steps=modification"
                    , "--preload-analysis-results-dirs=../../maddi/maddi-aapi-archive/src/main/resources/io/codelaser/maddi/aapi/archive/analyzedPackageFiles/jdk"
            });
        } finally {
            errorReport.detachAppender(appender);
        }
        List<String> errors = appender.list.stream()
                .filter(e -> e.getLevel() == Level.ERROR)
                .map(ILoggingEvent::getFormattedMessage)
                .filter(m -> m.matches("(?s)  \\[\\d+\\] .*"))
                .toList();
        if (exitValue == Main.EXIT_ANALYZER_ERROR && !errors.isEmpty()) {
            List<String> unexpected = errors.stream().filter(m -> !expectedViolation(m)).toList();
            assertTrue(unexpected.isEmpty(), () -> "analyzer errors beyond the accepted #125 contract violations: "
                                                   + unexpected);
        } else {
            assertEquals(Main.EXIT_OK, exitValue);
        }
    }

    /*
     CodeLaser/maddi-mod#125, option 3: our jenkins carries @NotModified(contract = true) on the parameters of
     ACL.hasPermission2(Authentication, Permission) and ACL.hasPermission(acegi Authentication, Permission). The guard
     reports each override that the analysis sees modifying such a parameter, as an error; these are accepted, every
     other analyzer error still fails the test.
     - ACL.$1.hasPermission2 (lambda2) and ACL.$2.hasPermission (lambda): both arguments go to an unknown
       BiFunction.apply, which counts them as modified -- the reason for the contract;
     - SidACL/SparseACL.hasPermission2, 'a': a.getAuthorities()/getPrincipal() on spring-security's Authentication,
       which has no annotated API, so the getters count as modifying.
     A subset is accepted: annotating spring-security's Authentication removes the last two.
     */
    private static final Set<String> EXPECTED_VIOLATIONS = Set.of(
            "a@hudson.security.ACL.$1.hasPermission2",
            "permission@hudson.security.ACL.$1.hasPermission2",
            "a@hudson.security.ACL.$2.hasPermission",
            "permission@hudson.security.ACL.$2.hasPermission",
            "a@hudson.security.SidACL.hasPermission2",
            "a@hudson.security.SparseACL.hasPermission2");

    private static final Pattern VIOLATION = Pattern.compile(
            "\\[contract-violation] parameter '(\\w+)' of ([\\w.$]+)\\(.*violating the @NotModified contract on parameter");

    private static boolean expectedViolation(String message) {
        Matcher m = VIOLATION.matcher(message);
        return m.find() && EXPECTED_VIOLATIONS.contains(m.group(1) + "@" + m.group(2));
    }
}
