package io.codelaser.maddi.run.openjdkmain;

import io.codelaser.maddi.run.j2cs.JavaToCSharpRatchet;
import io.codelaser.maddi.run.j2k.JavaToKotlinRatchet;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;

/**
 * langchain4j-core printed as C# with the analysis's facts ({@link J2csAnalysis}), a second corpus beside
 * {@link TestJavaToCSharpFernflowerFacts}: an API of builders, value objects, Optionals and streams. maddi's
 * {@code TestJavaToCSharpLangchain4j} measures the same without an analysis; this one has its own ratchet,
 * {@code src/test/resources/j2cs/langchain4j-facts.ratchet}, and report, {@code build/j2cs/langchain4j-facts/report.txt}.
 */
@Tag("slow")
public class TestJavaToCSharpLangchain4jFacts {

    /* INFO, as TestJavaToKotlinLangchain4jNullability: at DEBUG the analyzer's log fills the test report. */
    @BeforeAll
    public static void beforeAll() {
        ((ch.qos.logback.classic.Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME))
                .setLevel(ch.qos.logback.classic.Level.INFO);
    }

    @Test
    public void test() throws Exception {
        JavaToKotlinRatchet.Corpus corpus = JavaToKotlinRatchet.parse("langchain4j");
        new JavaToCSharpRatchet("langchain4j-facts", Path.of("src/test/resources/j2cs/langchain4j-facts.ratchet"),
                J2csAnalysis.analyse(corpus)).run(corpus);
    }
}
