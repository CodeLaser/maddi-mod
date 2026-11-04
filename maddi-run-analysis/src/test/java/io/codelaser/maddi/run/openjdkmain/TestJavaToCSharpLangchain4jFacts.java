package io.codelaser.maddi.run.openjdkmain;

import java.nio.file.Path;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import io.codelaser.maddi.run.j2cs.JavaToCSharpRatchet;
import io.codelaser.maddi.run.j2k.JavaToKotlinRatchet;

/**
 * langchain4j-core printed as C# with the analysis's facts ({@link CorpusNullability}), a second corpus beside
 * {@link TestJavaToCSharpFernflowerFacts}: an API of builders, value objects, Optionals and streams. maddi's
 * {@code TestJavaToCSharpLangchain4j} measures the same without an analysis; this one has its own ratchet,
 * {@code src/test/resources/j2cs/langchain4j-facts.ratchet}, and report, {@code build/j2cs/langchain4j-facts/report.txt}.
 */
@Tag("slow")
public class TestJavaToCSharpLangchain4jFacts {

    /* INFO, as TestJavaToKotlinLangchain4jNullability: at DEBUG the analyzer's log fills the test report. */
    @BeforeAll
    public static void beforeAll() {
        ((Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME))
                .setLevel(Level.INFO);
    }

    @Test
    public void test() throws Exception {
        JavaToKotlinRatchet.Corpus corpus = JavaToKotlinRatchet.parse("langchain4j");
        new JavaToCSharpRatchet("langchain4j-facts", Path.of("src/test/resources/j2cs/langchain4j-facts.ratchet"),
                CorpusNullability.csharpPolicy(corpus)).run(corpus);
    }
}
