package io.codelaser.maddi.run.openjdkmain;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import io.codelaser.maddi.run.j2k.JavaToKotlinRatchet;

/**
 * ⛔ THE RATCHET FOR JAVA → KOTLIN TRANSLATION WITH NULLABILITY, a third corpus: guava printed as Kotlin with the
 * verdicts of the NullabilityPass (see {@link CorpusNullability}), measured by maddi's JavaToKotlinRatchet.
 * <p>
 * Why guava: it implements Java's collections itself (Maps, Multimaps, the immutable collections, LocalCache), which
 * Kotlin maps onto its own collection types, with their own member shapes; and its generics are wildcard-heavy, with
 * {@code @ParametricNullness} type arguments. The other two corpora compile; this one is the printer's work list.
 * Heavy: run it with {@code lab run --mb 22g} and {@code TESTXMX=16G}.
 */
@Tag("slow")
public class TestJavaToKotlinGuavaNullability {

    /* INFO, as TestNullabilityOracleGuava: at DEBUG the analyzer's log fills the test report. */
    @BeforeAll
    public static void beforeAll() {
        ((Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME))
                .setLevel(Level.INFO);
    }

    /*
     ⛔ #158, FOR 0.9.7 ONLY: compilingFiles is not ratcheted but held to a floor. Thread timing in the parallel passes
     after the first decides one nullability verdict on a group of Class parameters (Primitives.wrap, ClassToInstanceMap.
     getInstance, ...), and with it whether MutableClassToInstanceMap.kt compiles: 610 or 609 at the same commits. The
     ratchet fails on progress as on regress, so no recorded value passes every run. 608 still fails here. The ratchet
     file keeps 610. REMOVE THIS FLOOR WITH THE #158 FIX: drop the reportedOnly set and COMPILING_FLOOR.
     */
    private static final String COMPILING = "compilingFiles";
    private static final long COMPILING_FLOOR = 609;

    @Test
    public void test() throws Exception {
        JavaToKotlinRatchet.Corpus corpus = JavaToKotlinRatchet.parse("guava");
        new JavaToKotlinRatchet("guava-nullability", Path.of("src/test/resources/j2k/guava-nullability.ratchet"),
                Set.of(COMPILING))
                .run(corpus, CorpusNullability.kotlinOptions(corpus));

        // run() writes the report before it ratchets, and the output directory is fresh for each run
        Path report = Path.of("build/j2k/guava-nullability/report.txt");
        long compiling = Files.readAllLines(report).stream()
                .map(String::trim)
                .filter(l -> l.startsWith(COMPILING + " "))
                .map(l -> Long.parseLong(l.split("\\s+")[1]))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no " + COMPILING + " in " + report.toAbsolutePath()));
        if (compiling < COMPILING_FLOOR) {
            throw new AssertionError("THE TRANSLATION GOT WORSE on guava-nullability: " + COMPILING + " " + compiling
                                     + " < " + COMPILING_FLOOR + " (the #158 floor; the ratchet file records 610). "
                                     + "Read " + report + ".");
        }
    }
}
