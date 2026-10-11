package io.codelaser.maddi.run.openjdkmain;

import io.codelaser.maddi.analysis.api.PrepOutcome;
import io.codelaser.maddi.analysis.api.PrepRequest;
import io.codelaser.maddi.callgraph.ComputeAnalysisOrder;
import io.codelaser.maddi.cst.api.info.Info;
import io.codelaser.maddi.cst.print.csharp.CSharpProgram;
import io.codelaser.maddi.inspection.api.integration.JavaInspector;
import io.codelaser.maddi.modification.analyzer.IteratingAnalyzer;
import io.codelaser.maddi.modification.analyzer.impl.IteratingAnalyzerImpl;
import io.codelaser.maddi.modification.analyzer.nullability.NullabilityPass;
import io.codelaser.maddi.run.analysis.AnalysisEngineImpl;
import io.codelaser.maddi.run.j2k.JavaToKotlinRatchet;

import java.util.List;
import java.util.Set;

/**
 * The C# ratchets with the analysis's facts: the corpus analysed as {@link J2kNullability} analyses it (JDK hints,
 * prep, call-graph order, MODREACH, nullability), then the NullabilityPass under {@code NULL_MARKED}, whose verdicts
 * it writes on the declarations. The printer's default facts ({@code CSharpFacts.STORED}) read those, and the
 * modification analysis's {@code UNMODIFIED_PARAMETER}: unmodified collection parameters become read-only, nullable
 * declarations {@code T?}.
 */
final class J2csAnalysis {

    private static final String JDK_HINTS =
            "../../maddi/maddi-aapi-archive/src/main/resources/io/codelaser/maddi/aapi/archive/analyzedPackageFiles/jdk";

    private J2csAnalysis() {
    }

    /** Analyses the parsed corpus; the policy whose facts read what the analysis stored. */
    static CSharpProgram.Policy analyse(JavaToKotlinRatchet.Corpus corpus) throws java.io.IOException {
        JavaInspector javaInspector = corpus.javaInspector();
        AnalysisEngineImpl engine = new AnalysisEngineImpl();
        engine.resultsLoader(javaInspector.runtime(), javaInspector.mainSources()).load(List.of(JDK_HINTS));
        var parseResult = corpus.summary().parseResult();
        PrepOutcome prep = engine.prep(new PrepRequest(javaInspector.runtime(), Set.copyOf(parseResult.primaryTypes()),
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
        new NullabilityPass(NullabilityPass.Policy.NULL_MARKED).go(order);
        return CSharpProgram.Policy.DEFAULT;
    }
}
