package io.codelaser.maddi.run.openjdkmain;

import io.codelaser.maddi.run.j2k.JavaToKotlinRatchet;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * JAVA → KOTLIN TRANSLATION WITH NULLABILITY, a trial corpus: one module of nacos printed as Kotlin with the verdicts
 * of the NullabilityPass (see {@link CorpusNullability}), measured by maddi's JavaToKotlinRatchet, and not ratcheted.
 * <p>
 * Why nacos: Spring-style service code without JSpecify annotations, where guava is annotated, null-marked and
 * generics-heavy; it shows whether the translation generalizes. A multi-module corpus of 35 main source sets and no
 * test source sets: {@code J2K_NACOS_MAIN} names the one translated ({@code api/main} by default, the client API of
 * builders and value objects); the others are parsed, and their class directories are on kotlinc's class path.
 */
@Tag("slow")
public class TestJavaToKotlinNacosNullability {

    /* INFO, as TestNullabilityOracleGuava: at DEBUG the analyzer's log fills the test report. */
    @BeforeAll
    public static void beforeAll() {
        ((ch.qos.logback.classic.Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME))
                .setLevel(ch.qos.logback.classic.Level.INFO);
    }

    @Test
    public void test() throws Exception {
        String main = System.getenv("J2K_NACOS_MAIN");
        if (main == null || main.isBlank()) main = "api/main";
        JavaToKotlinRatchet.Corpus corpus = JavaToKotlinRatchet.parse("nacos", main, null, false, true);
        JavaToKotlinRatchet.measureOnly("nacos-nullability").run(corpus, CorpusNullability.kotlinOptions(corpus));
    }
}
