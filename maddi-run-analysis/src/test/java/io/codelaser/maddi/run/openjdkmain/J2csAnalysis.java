package io.codelaser.maddi.run.openjdkmain;

import io.codelaser.maddi.analysis.api.PrepOutcome;
import io.codelaser.maddi.analysis.api.PrepRequest;
import io.codelaser.maddi.callgraph.ComputeAnalysisOrder;
import io.codelaser.maddi.cst.api.element.Element;
import io.codelaser.maddi.cst.api.expression.Expression;
import io.codelaser.maddi.cst.api.info.FieldInfo;
import io.codelaser.maddi.cst.api.info.Info;
import io.codelaser.maddi.cst.api.info.MethodInfo;
import io.codelaser.maddi.cst.api.info.ParameterInfo;
import io.codelaser.maddi.cst.api.statement.Statement;
import io.codelaser.maddi.cst.api.type.NullableState;
import io.codelaser.maddi.cst.api.type.ParameterizedType;
import io.codelaser.maddi.cst.api.variable.LocalVariable;
import io.codelaser.maddi.cst.api.variable.Variable;
import io.codelaser.maddi.cst.print.csharp.CSharpFacts;
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
 * prep, call-graph order, MODREACH, nullability), then the NullabilityPass, whose verdicts it writes on the
 * declarations. The printer's facts read those ({@code CSharpFacts.STORED}), and the modification analysis's
 * {@code UNMODIFIED_PARAMETER}: unmodified collection parameters become read-only, nullable declarations {@code T?}.
 * <p>
 * The pass's policy is the Kotlin translation's, {@code KOTLIN}: C# with nullable reference types is a null-checked
 * target as Kotlin is. Every declaration that is not nullable is not null, a variable assigned a nullable call result
 * is nullable, and where Kotlin asserts with {@code !!} C# says {@code !}, which does not throw: the C# fails where
 * Java does. The facts say the analysis covered the program, so that the judge enables C#'s nullable reference types;
 * the pass's report adds what is not stored, the locals' verdicts and the use-site facts, for the printer's {@code !}.
 */
final class J2csAnalysis {

    private static final String JDK_HINTS =
            "../../maddi/maddi-aapi-archive/src/main/resources/io/codelaser/maddi/aapi/archive/analyzedPackageFiles/jdk";

    private J2csAnalysis() {
    }

    /** The stored verdicts, and the pass's report for the locals and the use-site facts. */
    private record Facts(NullabilityPass.Report report) implements CSharpFacts {
        @Override
        public boolean unmodified(ParameterInfo parameter) {
            return STORED.unmodified(parameter);
        }

        /** The pass's verdict, as the Kotlin ratchet reads it; what is stored for what the pass has none of. */
        @Override
        public NullableState nullable(FieldInfo field) {
            ParameterizedType verdict = report.verdicts().get(field);
            return verdict != null ? verdict.nullable() : STORED.nullable(field);
        }

        @Override
        public NullableState nullable(ParameterInfo parameter) {
            ParameterizedType verdict = report.verdicts().get(parameter);
            return verdict != null ? verdict.nullable() : STORED.nullable(parameter);
        }

        @Override
        public NullableState nullable(MethodInfo method) {
            ParameterizedType verdict = report.verdicts().get(method);
            return verdict != null ? verdict.nullable() : STORED.nullable(method);
        }

        /** As the Kotlin ratchet: a verdict that is no decision (a degraded method's local) counts as nullable. */
        @Override
        public NullableState nullable(MethodInfo method, Element declaration, LocalVariable variable) {
            ParameterizedType verdict = report.local(method, declaration, variable);
            if (verdict == null) return NullableState.UNSPECIFIED;
            return verdict.nullable() == NullableState.UNSPECIFIED && !verdict.isPrimitiveExcludingVoid()
                    ? NullableState.NULLABLE : verdict.nullable();
        }

        @Override
        public boolean assertedAtDeclaration(MethodInfo method, Element declaration, LocalVariable variable) {
            return report.assertedAtDeclaration(method, declaration, variable);
        }

        @Override
        public boolean nonNullAt(Statement statement, Variable variable) {
            return report.smartCasts().nonNullAt(statement, variable);
        }

        @Override
        public boolean nonNullAt(Expression expression, Variable variable) {
            return report.smartCasts().nonNullAt(expression, variable);
        }

        @Override
        public boolean callResultNonNull(Expression call) {
            return report.callResultNonNull(call);
        }

        @Override
        public boolean nullabilityAnalysed() {
            return true;
        }
    }

    /** Analyses the parsed corpus; the policy whose facts read what the analysis stored. */
    static CSharpProgram.Policy analyse(JavaToKotlinRatchet.Corpus corpus) throws java.io.IOException {
        return analyse(corpus, corpus.summary().parseResult().primaryTypes());
    }

    /**
     * Analyses {@code types} of the parsed corpus: a large reactor's translated source set (timefold's core/main),
     * not the 65 it parses.
     */
    static CSharpProgram.Policy analyse(JavaToKotlinRatchet.Corpus corpus,
                                        java.util.Collection<io.codelaser.maddi.cst.api.info.TypeInfo> types)
            throws java.io.IOException {
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
        NullabilityPass.Report report = new NullabilityPass(NullabilityPass.Policy.KOTLIN).go(order);
        return CSharpProgram.Policy.DEFAULT.withFacts(new Facts(report));
    }
}
