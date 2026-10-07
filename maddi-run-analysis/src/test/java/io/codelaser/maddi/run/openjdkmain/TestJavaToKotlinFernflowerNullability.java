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
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

/**
 * ⛔ THE RATCHET FOR JAVA → KOTLIN TRANSLATION WITH NULLABILITY: maddi's TestJavaToKotlinFernflower (the printer
 * alone), plus the verdicts of {@link NullabilityPass} for the {@code ?} on fields, parameters, returns and local
 * variables. The modification analysis runs as {@link TestNullabilityOracleGuava#inference} runs it (JDK hints,
 * prep, call-graph order, MODREACH, nullability), then the pass under {@code NULL_MARKED}: Kotlin source cannot
 * write a platform type, so "not nullable" must mean non-null.
 * <p>
 * The pass is flow-insensitive ({@code if (v == null) return; use(v)} still sees a nullable {@code v}): the
 * printer's {@code !!} at such uses is expected, until the per-expression decisions of the design's M4. Same
 * measurement and output as the base test ({@code build/j2k/fernflower-nullability/report.txt}), its own ratchet.
 */
@Tag("slow")
public class TestJavaToKotlinFernflowerNullability {

    private static final String JDK_HINTS =
            "../../maddi/maddi-aapi-archive/src/main/resources/io/codelaser/maddi/aapi/archive/analyzedPackageFiles/jdk";

    /* INFO, as TestNullabilityOracleGuava: at DEBUG the analyzer's log fills the test report. */
    @BeforeAll
    public static void beforeAll() {
        ((ch.qos.logback.classic.Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME))
                .setLevel(ch.qos.logback.classic.Level.INFO);
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

        /** Kotlin's smart casts only: useSites() also counts requireNonNull and contracts, which Kotlin does not. */
        @Override
        public boolean nonNullAt(io.codelaser.maddi.cst.api.statement.Statement statement,
                                 io.codelaser.maddi.cst.api.variable.Variable variable) {
            return report.smartCasts().nonNullAt(statement, variable);
        }
    }

    @Test
    public void test() throws Exception {
        JavaToKotlinRatchet.Corpus corpus = JavaToKotlinRatchet.parse("fernflower");
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
        NullabilityPass.Report report = new NullabilityPass(NullabilityPass.Policy.NULL_MARKED).go(order);

        new JavaToKotlinRatchet("fernflower-nullability", Path.of("src/test/resources/j2k/fernflower-nullability.ratchet"))
                .run(corpus, new KotlinPrintOptions(new Verdicts(report), KotlinPrintOptions.NullCheck.ASSERT));
    }
}
