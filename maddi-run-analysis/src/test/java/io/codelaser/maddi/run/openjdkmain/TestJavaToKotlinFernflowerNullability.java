package io.codelaser.maddi.run.openjdkmain;

import io.codelaser.maddi.run.j2k.JavaToKotlinRatchet;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;

/**
 * ⛔ THE RATCHET FOR JAVA → KOTLIN TRANSLATION WITH NULLABILITY: maddi's TestJavaToKotlinFernflower (the printer
 * alone), plus the verdicts of {@link NullabilityPass} for the {@code ?} on fields, parameters, returns and local
 * variables. The modification analysis runs as {@link TestNullabilityOracleGuava#inference} runs it (JDK hints,
 * prep, call-graph order, MODREACH, nullability), then the pass under {@code NULL_MARKED}: Kotlin source cannot
 * write a platform type, so "not nullable" must mean non-null.
 * <p>
 * The pass is flow-insensitive ({@code if (v == null) return; use(v)} still sees a nullable {@code v}): the
 * printer's {@code !!} at such uses is expected, until the per-expression decisions of the design's M4. Same
 * measurement and output as the base test ({@code build/j2k/fernflower-nullability/report.txt}), its own ratchet.
 */
@Tag("slow")
public class TestJavaToKotlinFernflowerNullability {

    /* INFO, as TestNullabilityOracleGuava: at DEBUG the analyzer's log fills the test report. */
    @BeforeAll
    public static void beforeAll() {
        ((ch.qos.logback.classic.Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME))
                .setLevel(ch.qos.logback.classic.Level.INFO);
    }

    @Test
    public void test() throws Exception {
        JavaToKotlinRatchet.Corpus corpus = JavaToKotlinRatchet.parse("fernflower");
        new JavaToKotlinRatchet("fernflower-nullability", Path.of("src/test/resources/j2k/fernflower-nullability.ratchet"))
                .run(corpus, J2kNullability.options(corpus));
    }
}
