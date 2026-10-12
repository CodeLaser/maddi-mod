package io.codelaser.maddi.run.openjdkmain;

import io.codelaser.maddi.run.j2cs.JavaToCSharpRatchet;
import io.codelaser.maddi.run.j2k.JavaToKotlinRatchet;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;

/**
 * Fernflower printed as C# with the analysis's facts ({@link CorpusNullability}): read-only collection parameters and
 * nullable types. maddi's {@code TestJavaToCSharpFernflower} measures the same without an analysis; this one has its
 * own ratchet, {@code src/test/resources/j2cs/fernflower-facts.ratchet}, and report,
 * {@code build/j2cs/fernflower-facts/report.txt}.
 */
@Tag("slow")
public class TestJavaToCSharpFernflowerFacts {

    /* INFO, as TestJavaToKotlinFernflowerNullability: at DEBUG the analyzer's log fills the test report. */
    @BeforeAll
    public static void beforeAll() {
        ((ch.qos.logback.classic.Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME))
                .setLevel(ch.qos.logback.classic.Level.INFO);
    }

    @Test
    public void test() throws Exception {
        JavaToKotlinRatchet.Corpus corpus = JavaToKotlinRatchet.parse("fernflower");
        new JavaToCSharpRatchet("fernflower-facts", Path.of("src/test/resources/j2cs/fernflower-facts.ratchet"),
                CorpusNullability.csharpPolicy(corpus)).run(corpus);
    }
}
