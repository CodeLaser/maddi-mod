package io.codelaser.maddi.run.openjdkmain;

import java.util.Objects;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import io.codelaser.maddi.cst.impl.analysis.ModificationFacts;
import io.codelaser.maddi.cst.print.kotlin.KotlinPrintOptions;
import io.codelaser.maddi.modification.analyzer.nullability.Report;
import io.codelaser.maddi.run.j2k.JavaToKotlinRatchet;

/**
 * KotlinConf, maddi's declarations with another translator's bodies: the options of {@link TestKotlinConfReproduce}
 * with {@code Bodies.STUB}, into {@code build/j2k/<corpus>-stubs}. Every method body is {@code TODO("maddi:<id>")}, and
 * {@code src/maddi-bodies.json} holds what an LLM needs to write it (the Java lines, the declaration, the printer's own
 * body, the declarations of the translated methods it calls). The stubbed files compile as they are, constructors and
 * init blocks being printed; the corpus's tests then fail on the TODOs. A measurement, never a ratchet.
 * {@code ABLATION_CORPUS} names the corpus.
 */
@Tag("slow")
public class TestKotlinConfStubs {

    @BeforeAll
    public static void beforeAll() {
        ((Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME))
                .setLevel(Level.INFO);
    }

    @Test
    public void test() throws Exception {
        String corpusName = Objects.requireNonNullElse(System.getenv("ABLATION_CORPUS"), "fernflower");
        JavaToKotlinRatchet.Corpus corpus = JavaToKotlinRatchet.parse(corpusName);
        Report report = CorpusNullability.report(corpus);
        KotlinPrintOptions both = new KotlinPrintOptions(report, KotlinPrintOptions.NullCheck.ASSERT,
                ModificationFacts.STORED).withBodies(KotlinPrintOptions.Bodies.STUB);
        JavaToKotlinRatchet.measureOnly(corpusName + "-stubs").run(corpus, both);
    }
}
