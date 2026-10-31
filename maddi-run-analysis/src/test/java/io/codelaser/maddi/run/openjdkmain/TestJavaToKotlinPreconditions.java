package io.codelaser.maddi.run.openjdkmain;

import io.codelaser.maddi.cst.print.kotlin.KotlinPrintOptions;
import io.codelaser.maddi.modification.analyzer.nullability.Policy;
import io.codelaser.maddi.modification.analyzer.nullability.Report;
import io.codelaser.maddi.run.j2k.JavaToKotlinRatchet;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Objects;

/**
 * The Kotlin side of CodeLaser/maddi#125, a measurement for Bart's decision: one corpus analysed once, as the
 * nullability ratchets analyse it ({@link CorpusNullability}), its pass run under {@code KOTLIN} and under
 * {@code KOTLIN.withPreconditionsNonNull()}, each printed as {@code TestJavaToKotlin*Nullability} prints it and
 * measured into {@code build/j2k/<corpus>-preconditions-off} and {@code …-on}. With the policy on, a parameter its
 * method needs non-null is non-null, and a caller that may pass null asserts the argument: the NullPointerException
 * moves from the method's body to the call ({@code PRECONDITION_NON_NULL} in {@code messages.txt}).
 * A measurement, never a ratchet ({@link JavaToKotlinRatchet#measureOnly}). {@code PRECONDITIONS_CORPUS} (passed on
 * by a {@code --no-daemon} build) names the corpus, fernflower by default.
 */
@Tag("slow")
public class TestJavaToKotlinPreconditions {

    @BeforeAll
    public static void beforeAll() {
        ((ch.qos.logback.classic.Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME))
                .setLevel(ch.qos.logback.classic.Level.INFO);
    }

    @Test
    public void test() throws Exception {
        String corpusName = Objects.requireNonNullElse(System.getenv("PRECONDITIONS_CORPUS"), "fernflower");
        JavaToKotlinRatchet.Corpus corpus = JavaToKotlinRatchet.parse(corpusName);
        List<Report> reports = CorpusNullability.reports(corpus,
                corpus.summary().parseResult().primaryTypes(),
                List.of(Policy.KOTLIN, Policy.KOTLIN.withPreconditionsNonNull()));
        System.out.println("preconditions " + reports.get(1).preconditions().size() + ", declared non-null where null"
                           + " reached " + reports.get(1).preconditionsNonNull().size() + ", null arguments "
                           + reports.get(1).nullArguments().size());
        JavaToKotlinRatchet.measureOnly(corpusName + "-preconditions-off").run(corpus,
                new KotlinPrintOptions(reports.get(0), KotlinPrintOptions.NullCheck.ASSERT));
        JavaToKotlinRatchet.measureOnly(corpusName + "-preconditions-on").run(corpus,
                new KotlinPrintOptions(reports.get(1), KotlinPrintOptions.NullCheck.ASSERT));
    }
}
