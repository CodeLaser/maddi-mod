package io.codelaser.maddi.run.openjdkmain;

import io.codelaser.maddi.run.j2k.JavaToKotlinRatchet;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;

/**
 * ⛔ THE RATCHET FOR JAVA → KOTLIN TRANSLATION WITH NULLABILITY, a second corpus: langchain4j-core printed as Kotlin
 * with the verdicts of the NullabilityPass (see {@link J2kNullability}), measured by maddi's JavaToKotlinRatchet: the
 * type checker, the class files, langchain4j-core's own tests, and those tests translated.
 * <p>
 * Why langchain4j-core: an API of builders and value objects, whose collections are mostly read and rarely changed.
 * Where the translation chooses Kotlin's {@code List} or {@code MutableList} (today: read-only only for unmodified
 * parameters of closed methods), the type errors here are the modification analysis's work list.
 */
@Tag("slow")
public class TestJavaToKotlinLangchain4jNullability {

    /* INFO, as TestNullabilityOracleGuava: at DEBUG the analyzer's log fills the test report. */
    @BeforeAll
    public static void beforeAll() {
        ((ch.qos.logback.classic.Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME))
                .setLevel(ch.qos.logback.classic.Level.INFO);
    }

    @Test
    public void test() throws Exception {
        JavaToKotlinRatchet.Corpus corpus = JavaToKotlinRatchet.parse("langchain4j");
        new JavaToKotlinRatchet("langchain4j-nullability", Path.of("src/test/resources/j2k/langchain4j-nullability.ratchet"))
                .run(corpus, J2kNullability.options(corpus));
    }
}
