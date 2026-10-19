package io.codelaser.maddi.run.openjdkmain;

import io.codelaser.maddi.run.j2k.JavaToKotlinRatchet;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * JAVA → KOTLIN TRANSLATION WITH NULLABILITY, a trial corpus: jenkins core printed as Kotlin with the verdicts of the
 * NullabilityPass (see {@link CorpusNullability}), measured by maddi's JavaToKotlinRatchet, and not ratcheted.
 * <p>
 * Why jenkins: long-lived Java without JSpecify annotations, written before generics settled: raw types, anonymous and
 * inner classes, {@code synchronized}, reflection. One main source set, {@code Jenkins core/main} (its generated
 * sources, antlr4 and localizer, included); the cli module is a library jar.
 */
@Tag("slow")
public class TestJavaToKotlinJenkinsNullability {

    /* INFO, as TestNullabilityOracleGuava: at DEBUG the analyzer's log fills the test report. */
    @BeforeAll
    public static void beforeAll() {
        ((ch.qos.logback.classic.Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME))
                .setLevel(ch.qos.logback.classic.Level.INFO);
    }

    @Test
    public void test() throws Exception {
        JavaToKotlinRatchet.Corpus corpus = JavaToKotlinRatchet.parse("jenkins", "Jenkins core/main", null, false);
        JavaToKotlinRatchet.measureOnly("jenkins-nullability").run(corpus, CorpusNullability.kotlinOptions(corpus));
    }
}
