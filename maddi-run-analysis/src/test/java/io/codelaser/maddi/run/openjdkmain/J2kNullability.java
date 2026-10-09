package io.codelaser.maddi.run.openjdkmain;

import io.codelaser.maddi.analysis.api.PrepOutcome;
import io.codelaser.maddi.analysis.api.PrepRequest;
import io.codelaser.maddi.callgraph.ComputeAnalysisOrder;
import io.codelaser.maddi.cst.api.element.Element;
import io.codelaser.maddi.cst.api.info.FieldInfo;
import io.codelaser.maddi.cst.api.info.Info;
import io.codelaser.maddi.cst.api.info.MethodInfo;
import io.codelaser.maddi.cst.api.info.ParameterInfo;
import io.codelaser.maddi.cst.api.type.ParameterizedType;
import io.codelaser.maddi.cst.api.variable.LocalVariable;
import io.codelaser.maddi.cst.print.kotlin.KotlinPrintOptions;
import io.codelaser.maddi.cst.print.kotlin.NullabilityVerdicts;
import io.codelaser.maddi.inspection.api.integration.JavaInspector;
import io.codelaser.maddi.modification.analyzer.IteratingAnalyzer;
import io.codelaser.maddi.modification.analyzer.impl.IteratingAnalyzerImpl;
import io.codelaser.maddi.modification.analyzer.nullability.NullabilityPass;
import io.codelaser.maddi.run.analysis.AnalysisEngineImpl;
import io.codelaser.maddi.run.j2k.JavaToKotlinRatchet;

import java.util.List;
import java.util.Set;

/**
 * The J2K ratchets with nullability: a corpus analysed by the modification analysis with its NullabilityPass (policy
 * KOTLIN), and the pass's verdicts as the Kotlin printer asks for them. Shared by fernflower's and langchain4j's.
 */
final class J2kNullability {

    private static final String JDK_HINTS =
            "../../maddi/maddi-aapi-archive/src/main/resources/io/codelaser/maddi/aapi/archive/analyzedPackageFiles/jdk";

    private J2kNullability() {
    }

    /** The pass's report as the printer asks for it. */
    private record Verdicts(NullabilityPass.Report report) implements NullabilityVerdicts {
        @Override
        public ParameterizedType field(FieldInfo fieldInfo) {
            return report.verdicts().get(fieldInfo);
        }

        @Override
        public ParameterizedType parameter(ParameterInfo parameterInfo) {
            return report.verdicts().get(parameterInfo);
        }

        @Override
        public ParameterizedType returnType(MethodInfo methodInfo) {
            return report.verdicts().get(methodInfo);
        }

        @Override
        public ParameterizedType local(MethodInfo method, Element declaration, LocalVariable variable) {
            return report.local(method, declaration, variable);
        }

        // NullabilityVerdicts.assertedAtDeclaration (maddi 9942ae3d0); no @Override until maddi has it
        public boolean assertedAtDeclaration(MethodInfo method, Element declaration, LocalVariable variable) {
            return report.assertedAtDeclaration(method, declaration, variable);
        }

        // NullabilityVerdicts.unobservedBeforeDereference (maddi, the printer's next commit); no @Override until then
        public boolean unobservedBeforeDereference(MethodInfo method, Element declaration, LocalVariable variable) {
            return report.unobservedBeforeDereference(method, declaration, variable);
        }

        /** Kotlin's smart casts only: useSites() also counts requireNonNull and contracts, which Kotlin does not. */
        @Override
        public boolean nonNullAt(io.codelaser.maddi.cst.api.statement.Statement statement,
                                 io.codelaser.maddi.cst.api.variable.Variable variable) {
            return report.smartCasts().nonNullAt(statement, variable);
        }

        /** The same at a call: also what the enclosing condition establishes, and final fields of this. */
        @Override
        public boolean nonNullAt(io.codelaser.maddi.cst.api.expression.Expression expression,
                                 io.codelaser.maddi.cst.api.variable.Variable variable) {
            return report.smartCasts().nonNullAt(expression, variable);
        }
    }

    /** Analyses the parsed corpus, main and test sources, and prints with the pass's verdicts. */
    static KotlinPrintOptions options(JavaToKotlinRatchet.Corpus corpus) throws java.io.IOException {
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
        NullabilityPass.Report report = new NullabilityPass(NullabilityPass.Policy.KOTLIN).go(order);

        return new KotlinPrintOptions(new Verdicts(report), KotlinPrintOptions.NullCheck.ASSERT);
    }
}
