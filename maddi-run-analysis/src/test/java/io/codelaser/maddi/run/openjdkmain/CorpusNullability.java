package io.codelaser.maddi.run.openjdkmain;

import io.codelaser.maddi.analysis.api.PrepOutcome;
import io.codelaser.maddi.analysis.api.PrepRequest;
import io.codelaser.maddi.callgraph.ComputeAnalysisOrder;
import io.codelaser.maddi.cst.api.info.Info;
import io.codelaser.maddi.cst.api.info.ParameterInfo;
import io.codelaser.maddi.cst.api.info.TypeInfo;
import io.codelaser.maddi.cst.impl.analysis.NullabilityFacts;
import io.codelaser.maddi.cst.print.csharp.CSharpFacts;
import io.codelaser.maddi.cst.print.csharp.CSharpProgram;
import io.codelaser.maddi.cst.print.kotlin.KotlinPrintOptions;
import io.codelaser.maddi.inspection.api.integration.JavaInspector;
import io.codelaser.maddi.modification.analyzer.IteratingAnalyzer;
import io.codelaser.maddi.modification.analyzer.impl.IteratingAnalyzerImpl;
import io.codelaser.maddi.modification.analyzer.nullability.NullabilityPass;
import io.codelaser.maddi.run.analysis.AnalysisEngineImpl;
import io.codelaser.maddi.run.j2k.JavaToKotlinRatchet;

import java.io.IOException;
import java.util.Collection;
import java.util.List;
import java.util.Set;

/**
 * The translation ratchets with nullability: a corpus analysed by the modification analysis with its NullabilityPass,
 * and the pass's report as the printers ask for it ({@link NullabilityFacts}). Shared by the Kotlin and the C#
 * ratchets of fernflower, langchain4j and timefold.
 * <p>
 * The pass's policy is the Kotlin translation's, {@code KOTLIN}, for C# as well: C# with nullable reference types is
 * a null-checked target as Kotlin is. Every declaration that is not nullable is not null, a variable assigned a
 * nullable call result is nullable, and where Kotlin asserts with {@code !!} C# says {@code !}, which does not throw:
 * the C# fails where Java does. For C# with {@code withPreconditionsNonNull()} (CodeLaser/maddi#125): a parameter its
 * method needs non-null is non-nullable, and a caller that may pass null says {@code !}; nothing changes at run time.
 * Not for Kotlin, where the NullPointerException would move from the method's body to the call (not approved).
 */
final class CorpusNullability {

    private static final String JDK_HINTS =
            "../../maddi/maddi-aapi-archive/src/main/resources/io/codelaser/maddi/aapi/archive/analyzedPackageFiles/jdk";

    private CorpusNullability() {
    }

    /** Analyses the parsed corpus, main and test sources. */
    static NullabilityPass.Report report(JavaToKotlinRatchet.Corpus corpus) throws IOException {
        return report(corpus, corpus.summary().parseResult().primaryTypes(), NullabilityPass.Policy.KOTLIN);
    }

    /**
     * Analyses {@code types} of the parsed corpus: JDK hints, prep, call-graph order, the iterating analyzer with
     * MODREACH and nullability, then the pass. A large reactor's translated source set (timefold's core/main) rather
     * than everything it parses.
     */
    static NullabilityPass.Report report(JavaToKotlinRatchet.Corpus corpus, Collection<TypeInfo> types,
                                         NullabilityPass.Policy policy) throws IOException {
        return reports(corpus, types, List.of(policy)).getFirst();
    }

    /** One analysis, one pass per policy: a measurement that compares policies. */
    static List<NullabilityPass.Report> reports(JavaToKotlinRatchet.Corpus corpus, Collection<TypeInfo> types,
                                                List<NullabilityPass.Policy> policies) throws IOException {
        JavaInspector javaInspector = corpus.javaInspector();
        AnalysisEngineImpl engine = new AnalysisEngineImpl();
        engine.resultsLoader(javaInspector.runtime(), javaInspector.mainSources()).load(List.of(JDK_HINTS));
        var parseResult = corpus.summary().parseResult();
        PrepOutcome prep = engine.prep(new PrepRequest(javaInspector.runtime(), Set.copyOf(types),
                parseResult.sourceSetToModuleInfoMap().values(), _ -> false, true, true));
        List<Info> order = new ComputeAnalysisOrder().go(prep.callGraph().graph(), true);
        IteratingAnalyzer analyzer = new IteratingAnalyzerImpl(javaInspector,
                new IteratingAnalyzerImpl.ConfigurationBuilder()
                        .setMaxIterations(30)
                        .setStopWhenCycleDetectedAndNoImprovements(true)
                        .setFaultTolerant(true)
                        .setModificationViaReachability(true)
                        .setNullability(true)
                        .build());
        analyzer.analyze(order, prep.callGraph().graph());
        return policies.stream().map(policy -> new NullabilityPass(policy).go(order)).toList();
    }

    /** The Kotlin printer with the pass's report: its verdicts, and {@code !!} where Kotlin needs a non-null value. */
    static KotlinPrintOptions kotlinOptions(JavaToKotlinRatchet.Corpus corpus) throws IOException {
        return kotlinOptions(corpus, NullabilityPass.Policy.KOTLIN);
    }

    /** As {@link #kotlinOptions(JavaToKotlinRatchet.Corpus)}, under another policy: a measurement. */
    static KotlinPrintOptions kotlinOptions(JavaToKotlinRatchet.Corpus corpus, NullabilityPass.Policy policy)
            throws IOException {
        return new KotlinPrintOptions(report(corpus, corpus.summary().parseResult().primaryTypes(), policy),
                KotlinPrintOptions.NullCheck.ASSERT);
    }

    static CSharpProgram.Policy csharpPolicy(JavaToKotlinRatchet.Corpus corpus) throws IOException {
        return csharpPolicy(corpus, corpus.summary().parseResult().primaryTypes());
    }

    /**
     * The C# printer with the pass's report, and what the analyzer stored for the declarations the pass has no
     * verdict of (a library's, from the hints); the modification analysis's {@code UNMODIFIED_PARAMETER} makes
     * unmodified collection parameters read-only. The report says the analysis covered the program, so that the
     * judge enables C#'s nullable reference types.
     */
    static CSharpProgram.Policy csharpPolicy(JavaToKotlinRatchet.Corpus corpus, Collection<TypeInfo> types)
            throws IOException {
        NullabilityFacts facts = report(corpus, types, NullabilityPass.Policy.KOTLIN.withPreconditionsNonNull())
                .orElse(NullabilityFacts.STORED);
        return CSharpProgram.Policy.DEFAULT.withFacts(CSharpFacts.of(facts, ParameterInfo::isUnmodified));
    }
}
