package io.codelaser.maddi.run.openjdkmain;

import io.codelaser.maddi.run.j2k.JavaToKotlinRatchet;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;

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
        ((ch.qos.logback.classic.Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME))
                .setLevel(ch.qos.logback.classic.Level.INFO);
    }

    @Test
    public void test() throws Exception {
        JavaToKotlinRatchet.Corpus corpus = JavaToKotlinRatchet.parse("guava");
        new JavaToKotlinRatchet("guava-nullability", Path.of("src/test/resources/j2k/guava-nullability.ratchet"))
                .run(corpus, CorpusNullability.kotlinOptions(corpus));
    }
}
