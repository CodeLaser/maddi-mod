package io.codelaser.maddi.run.openjdkmain;

import io.codelaser.maddi.cst.impl.analysis.ModificationFacts;
import io.codelaser.maddi.cst.impl.analysis.NullabilityFacts;
import io.codelaser.maddi.cst.print.kotlin.KotlinPrintOptions;
import io.codelaser.maddi.modification.analyzer.nullability.NullabilityPass;
import io.codelaser.maddi.run.j2k.JavaToKotlinRatchet;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.Objects;

/**
 * The ablation of the Java → Kotlin translation (KotlinConf 2027, maddi-mod#35): one corpus analysed once, as the
 * nullability ratchets analyse it ({@link CorpusNullability}), then printed four ways, each measured as the ratchets
 * measure (compilation, the corpus's own tests, the translated tests) into a directory of its own:
 * <ul>
 *   <li>{@code build/j2k/<corpus>-ablation-none}: neither analysis, the printer alone;</li>
 *   <li>{@code …-nullability}: the NullabilityPass's verdicts only, every collection mutable;</li>
 *   <li>{@code …-modification}: the modification analysis only (read-only collection types), every type as declared;</li>
 *   <li>{@code …-both}: both, as {@code TestJavaToKotlin*Nullability}.</li>
 * </ul>
 * A measurement, never a ratchet: no {@code .ratchet} file is read or written ({@link JavaToKotlinRatchet#measureOnly}).
 * {@code ABLATION_CORPUS} (passed on by a {@code --no-daemon} build) names the corpus, fernflower by default.
 */
@Tag("slow")
public class TestJavaToKotlinAblation {

    @BeforeAll
    public static void beforeAll() {
        ((ch.qos.logback.classic.Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME))
                .setLevel(ch.qos.logback.classic.Level.INFO);
    }

    @Test
    public void test() throws Exception {
        String corpusName = Objects.requireNonNullElse(System.getenv("ABLATION_CORPUS"), "fernflower");
        JavaToKotlinRatchet.Corpus corpus = JavaToKotlinRatchet.parse(corpusName);
        NullabilityPass.Report report = CorpusNullability.report(corpus);
        KotlinPrintOptions both = new KotlinPrintOptions(report, KotlinPrintOptions.NullCheck.ASSERT,
                ModificationFacts.STORED);
        String prefix = corpusName + "-ablation-";
        JavaToKotlinRatchet.measureOnly(prefix + "none").run(corpus,
                both.withVerdicts(NullabilityFacts.NONE).withModification(ModificationFacts.NONE));
        JavaToKotlinRatchet.measureOnly(prefix + "nullability").run(corpus,
                both.withModification(ModificationFacts.NONE));
        JavaToKotlinRatchet.measureOnly(prefix + "modification").run(corpus,
                both.withVerdicts(NullabilityFacts.NONE));
        JavaToKotlinRatchet.measureOnly(prefix + "both").run(corpus, both);
    }
}
