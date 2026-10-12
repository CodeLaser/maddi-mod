package io.codelaser.maddi.run.openjdkmain;

import io.codelaser.maddi.run.j2cs.JavaToCSharpRatchet;
import io.codelaser.maddi.run.j2k.JavaToKotlinRatchet;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;

/**
 * timefold-solver's core printed as C# with the analysis's facts ({@link CorpusNullability}), the third corpus beside
 * {@link TestJavaToCSharpFernflowerFacts} and {@link TestJavaToCSharpLangchain4jFacts}: a large constraint solver,
 * generics-heavy, with streams and functional interfaces throughout. The reactor parses whole (65 source sets); the
 * analysis and the translation take core/main, the module everything else depends on. Its own ratchet,
 * {@code src/test/resources/j2cs/timefold-facts.ratchet}, and report, {@code build/j2cs/timefold-facts/report.txt}.
 */
@Tag("slow")
// Only READS the shared timefold checkout; as TestTimefoldSolver, kept apart from the tests that rewrite it.
@Tag("corpus-timefold-solver")
public class TestJavaToCSharpTimefoldFacts {

    /* INFO, as TestTimefoldSolver: at DEBUG the analyzer's log fills the test report. */
    @BeforeAll
    public static void beforeAll() {
        ((ch.qos.logback.classic.Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME))
                .setLevel(ch.qos.logback.classic.Level.INFO);
        // corpus-scale noise, as TestTimefoldSolver
        ((ch.qos.logback.classic.Logger) LoggerFactory
                .getLogger("io.codelaser.maddi.modification.link.impl.linkgraph.RedundantLinks"))
                .setLevel(ch.qos.logback.classic.Level.ERROR);
    }

    @Test
    public void test() throws Exception {
        JavaToKotlinRatchet.Corpus corpus = JavaToKotlinRatchet.parse("timefold-solver");
        new JavaToCSharpRatchet("timefold-facts", Path.of("src/test/resources/j2cs/timefold-facts.ratchet"),
                CorpusNullability.csharpPolicy(corpus, corpus.types())).run(corpus);
    }
}
