package io.codelaser.maddi.run.openjdkmain;

import io.codelaser.maddi.run.j2k.JavaToKotlinRatchet;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * JAVA → KOTLIN TRANSLATION WITH NULLABILITY, a trial corpus: timefold-solver's core printed as Kotlin with the verdicts
 * of the NullabilityPass (see {@link CorpusNullability}), measured by maddi's JavaToKotlinRatchet, and not ratcheted.
 * <p>
 * Why timefold-solver: modern Java (release 21), unlike jenkins and guava: records, sealed types, {@code var},
 * generic-heavy APIs (constraint streams, score types) and lambdas everywhere. One main source set, {@code core/main};
 * its tests are {@code core/test-classes}, JUnit 5.
 */
@Tag("slow")
public class TestJavaToKotlinTimefoldNullability {

    /* INFO, as TestNullabilityOracleGuava: at DEBUG the analyzer's log fills the test report. */
    @BeforeAll
    public static void beforeAll() {
        ((ch.qos.logback.classic.Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME))
                .setLevel(ch.qos.logback.classic.Level.INFO);
    }

    @Test
    public void test() throws Exception {
        JavaToKotlinRatchet.Corpus corpus = JavaToKotlinRatchet.parse("timefold-solver", "core/main",
                "core/test-classes", false);
        JavaToKotlinRatchet.measureOnly("timefold-nullability").run(corpus, CorpusNullability.kotlinOptions(corpus));
    }
}
