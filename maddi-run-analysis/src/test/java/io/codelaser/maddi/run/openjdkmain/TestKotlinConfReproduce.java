package io.codelaser.maddi.run.openjdkmain;

import io.codelaser.maddi.cst.impl.analysis.ModificationFacts;
import io.codelaser.maddi.cst.print.kotlin.KotlinPrintOptions;
import io.codelaser.maddi.modification.analyzer.nullability.Report;
import io.codelaser.maddi.run.j2k.JavaToKotlinRatchet;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.Objects;

/**
 * KotlinConf "numbers reproduce" (maddi-mod#50): the scoreboard's maddi translation, the {@code -both} output of
 * {@link TestJavaToKotlinAblation} (the pass's verdicts, {@code NullCheck.ASSERT}, the stored modification facts, the
 * world of the corpus's main and test types), once, into {@code build/j2k/<corpus>-reproduce}.
 * {@code scripts/kotlinconf/reproduce.sh} runs it twice and diffs. A measurement, never a ratchet: no {@code .ratchet}
 * file is read or written ({@link JavaToKotlinRatchet#measureOnly}). {@code ABLATION_CORPUS} names the corpus.
 */
@Tag("slow")
public class TestKotlinConfReproduce {

    @BeforeAll
    public static void beforeAll() {
        ((ch.qos.logback.classic.Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME))
                .setLevel(ch.qos.logback.classic.Level.INFO);
    }

    @Test
    public void test() throws Exception {
        String corpusName = Objects.requireNonNullElse(System.getenv("ABLATION_CORPUS"), "fernflower");
        JavaToKotlinRatchet.Corpus corpus = JavaToKotlinRatchet.parse(corpusName);
        Report report = CorpusNullability.report(corpus);
        KotlinPrintOptions both = new KotlinPrintOptions(report, KotlinPrintOptions.NullCheck.ASSERT,
                ModificationFacts.STORED);
        JavaToKotlinRatchet.measureOnly(corpusName + "-reproduce").run(corpus, both);
    }
}
