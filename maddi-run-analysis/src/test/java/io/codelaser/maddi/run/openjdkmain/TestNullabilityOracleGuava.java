package io.codelaser.maddi.run.openjdkmain;

import io.codelaser.maddi.cst.api.expression.AnnotationExpression;
import io.codelaser.maddi.analysis.api.PrepOutcome;
import io.codelaser.maddi.analysis.api.PrepRequest;
import io.codelaser.maddi.callgraph.ComputeAnalysisOrder;
import io.codelaser.maddi.cst.api.info.FieldInfo;
import io.codelaser.maddi.cst.api.info.Info;
import io.codelaser.maddi.cst.api.info.MethodInfo;
import io.codelaser.maddi.cst.api.info.ParameterInfo;
import io.codelaser.maddi.cst.api.info.TypeInfo;
import io.codelaser.maddi.cst.api.type.NullableState;
import io.codelaser.maddi.cst.api.type.ParameterizedType;
import io.codelaser.maddi.cst.impl.type.DeclaredNullability;
import io.codelaser.maddi.inspection.api.integration.JavaInspector;
import io.codelaser.maddi.inspection.api.parser.Summary;
import io.codelaser.maddi.inspection.openjdk.JavaInspectorImpl;
import io.codelaser.maddi.inspection.resource.InputConfigurationImpl;
import io.codelaser.maddi.modification.analyzer.IteratingAnalyzer;
import io.codelaser.maddi.modification.analyzer.impl.IteratingAnalyzerImpl;
import io.codelaser.maddi.modification.analyzer.nullability.NullabilityComparison;
import io.codelaser.maddi.modification.analyzer.nullability.NullabilityPass;
import io.codelaser.maddi.run.analysis.AnalysisEngineImpl;
import io.codelaser.maddi.modification.analyzer.nullability.NullabilityComparison.Kind;
import io.codelaser.maddi.modification.analyzer.nullability.NullabilityComparison.Outcome;
import io.codelaser.maddi.run.config.util.JsonStreaming;
import io.codelaser.maddi.util.corpus.Corpora;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The nullability oracle (maddi-mod {@code docs/design/nullability.md} §6, T6), first stage: guava's own JSpecify
 * annotations under its {@code @NullMarked} packages are the reference, read by {@link DeclaredNullability}; a
 * verdict is scored against them position by position by {@link NullabilityComparison}.
 * <p>
 * No inference exists yet. This stage pins the reference census and the two trivial baselines, so the inference
 * has numbers to beat: "everything nullable" can never be unsafe and is all noise; "everything non-null" can never
 * be noise and is all unsafe. The unsafe count of an inferred verdict must stay near the first baseline's zero,
 * its noise well below the first baseline's.
 */
@Tag("slow")
public class TestNullabilityOracleGuava {
    private static final Logger LOGGER = LoggerFactory.getLogger(TestNullabilityOracleGuava.class);

    private static final Path CONFIG = Corpora.oss("guava").config();

    /*
     ⛔ INFO, set here: without it the analyzer logs at DEBUG into the test report, and one inference run wrote a
     27.7 GB XML (2026-10-06). TestGuava sets the same.
     */
    @BeforeAll
    public static void beforeAll() {
        ((ch.qos.logback.classic.Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME))
                .setLevel(ch.qos.logback.classic.Level.INFO);
    }

    private record Parsed(JavaInspector javaInspector, Summary summary, List<TypeInfo> types) {
    }

    private static List<TypeInfo> parseGuava() throws IOException {
        return parse().types();
    }

    private static Parsed parse() throws IOException {
        Corpora.oss("guava").requireConfig();
        InputConfigurationImpl inputConfiguration = JsonStreaming.objectMapper()
                .readValue(CONFIG.toFile(), InputConfigurationImpl.class);
        JavaInspector javaInspector = new JavaInspectorImpl(true, false);
        javaInspector.setJdkInternals(true); // guava-tests imports sun.security.jca (see TestGuava)
        javaInspector.initialize(inputConfiguration);
        javaInspector.preload("java.base::java.util");
        Summary summary = javaInspector.parse(new JavaInspector.ParseOptions.Builder()
                .setDetailedSources(true).setFailFast(false).setParallel(true).setIgnoreModule(true).build());
        assertFalse(summary.haveErrors(), "guava must parse cleanly");
        return new Parsed(javaInspector, summary, List.copyOf(summary.parseResult().primaryTypes()));
    }

    /** Every source declaration with a reference type, through {@code sink(kind, declared type)}. */
    interface Sink {
        void accept(Kind kind, Info info, ParameterizedType declared);
    }

    static void declarations(List<TypeInfo> primaryTypes, DeclaredNullability dn, Sink sink) {
        primaryTypes.stream()
                .filter(t -> !t.typeNature().isPackageInfo())
                .flatMap(TypeInfo::recursiveSubTypeStream)
                .forEach(t -> {
                    for (FieldInfo f : t.fields()) {
                        if (!f.isSynthetic()) sink.accept(Kind.FIELD, f, dn.field(f));
                    }
                    t.constructorAndMethodStream().filter(m -> !m.isSynthetic()).forEach(m -> {
                        for (ParameterInfo p : m.parameters()) sink.accept(Kind.PARAMETER, p, dn.parameter(p));
                        ParameterizedType rt = dn.returnType(m);
                        if (rt != null) sink.accept(Kind.RETURN, m, rt);
                    });
                });
    }

    static DeclaredNullability declaredNullability(List<TypeInfo> primaryTypes) {
        Map<String, List<AnnotationExpression>> byPackage = primaryTypes.stream()
                .filter(t -> t.typeNature().isPackageInfo())
                .collect(Collectors.toMap(TypeInfo::packageName, TypeInfo::annotations, (a, b) -> a));
        return new DeclaredNullability(p -> byPackage.getOrDefault(p, List.of()));
    }

    // the same type, every non-primitive position set to 'state'
    static ParameterizedType everywhere(ParameterizedType pt, NullableState state) {
        ParameterizedType withArguments = pt.parameters().isEmpty() ? pt
                : pt.withParameters(pt.parameters().stream().map(p -> everywhere(p, state)).toList());
        boolean primitive = pt.isPrimitiveExcludingVoid() && pt.arrays() == 0;
        return withArguments.withNullable(primitive ? NullableState.NONNULL : state);
    }

    private static NullabilityComparison score(List<TypeInfo> types, DeclaredNullability dn,
                                               UnaryOperator<ParameterizedType> verdict) {
        NullabilityComparison comparison = new NullabilityComparison();
        declarations(types, dn, (kind, _, declared) -> comparison.add(kind, declared, verdict.apply(declared)));
        return comparison;
    }

    @Test
    public void baselines() throws IOException {
        List<TypeInfo> types = parseGuava();
        DeclaredNullability dn = declaredNullability(types);

        long marked = types.stream().filter(t -> t.typeNature().isPackageInfo())
                .filter(t -> t.annotations().stream().anyMatch(a -> "NullMarked".equals(a.typeInfo().simpleName())))
                .count();
        LOGGER.info("guava: {} primary types, {} @NullMarked packages", types.size(), marked);

        NullabilityComparison allNullable = score(types, dn, pt -> everywhere(pt, NullableState.NULLABLE));
        NullabilityComparison allNonNull = score(types, dn, pt -> everywhere(pt, NullableState.NONNULL));
        LOGGER.info("BASELINE all-nullable\n{}", allNullable.report());
        LOGGER.info("BASELINE all-non-null\n{}", allNonNull.report());

        // vacuity guards: the reference must actually say something, in both directions
        int scored = allNullable.count(Outcome.AGREE) + allNullable.count(Outcome.NOISE);
        assertTrue(marked >= 10, "expected guava's @NullMarked packages, found " + marked);
        assertTrue(scored >= 10_000, "expected a large scored reference, got " + scored);
        assertTrue(allNullable.count(Outcome.AGREE) >= 1_000, "expected many declared-nullable positions");

        // the baselines' defining properties
        assertEquals(0, allNullable.count(Outcome.UNSAFE));
        assertEquals(0, allNonNull.count(Outcome.NOISE));
        assertEquals(allNullable.count(Outcome.AGREE), allNonNull.count(Outcome.UNSAFE));
        assertEquals(allNullable.count(Outcome.NOISE), allNonNull.count(Outcome.AGREE));
    }

    private static final String JDK_HINTS =
            "../../maddi/maddi-aapi-archive/src/main/resources/io/codelaser/maddi/aapi/archive/analyzedPackageFiles/jdk";

    /**
     * The inference (M3) against the reference: the modification analysis runs as the CLI runs it (JDK hints, prep,
     * call-graph order, MODREACH), plus {@code nullability}, then {@link NullabilityPass} under the
     * {@code @NullMarked} policy. Logged, not yet ratcheted: this is the first measurement.
     */
    @Test
    public void inference() throws IOException {
        Parsed parsed = parse();
        JavaInspector javaInspector = parsed.javaInspector();
        AnalysisEngineImpl engine = new AnalysisEngineImpl();
        engine.resultsLoader(javaInspector.runtime(), javaInspector.mainSources()).load(List.of(JDK_HINTS));
        var parseResult = parsed.summary().parseResult();
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

        List<TypeInfo> types = parsed.types();
        DeclaredNullability dn = declaredNullability(types);
        NullabilityComparison flowOnly = measure("NULL_MARKED_FLOW_ONLY", types, dn,
                new NullabilityPass(NullabilityPass.Policy.NULL_MARKED_FLOW_ONLY.withoutContracts()).go(order));
        NullabilityComparison comparison = measure("NULL_MARKED", types, dn,
                new NullabilityPass(NullabilityPass.Policy.NULL_MARKED.withoutContracts()).go(order));
        assertTrue(flowOnly.count(Outcome.AGREE) >= 10_000, "the inference must cover the reference");
        assertTrue(comparison.count(Outcome.UNSAFE) <= flowOnly.count(Outcome.UNSAFE),
                "null tests only add nullable seeds, so they cannot add unsafe verdicts");
    }

    private static NullabilityComparison measure(String name, List<TypeInfo> types, DeclaredNullability dn,
                                                 NullabilityPass.Report report) {
        NullabilityComparison comparison = new NullabilityComparison();
        int[] missing = new int[1];
        List<String> disagreements = new java.util.ArrayList<>();
        declarations(types, dn, (kind, info, declared) -> {
            ParameterizedType verdict = report.verdicts().get(info);
            if (verdict == null) {
                missing[0]++;
                return;
            }
            int unsafeBefore = comparison.count(Outcome.UNSAFE);
            int noiseBefore = comparison.count(Outcome.NOISE);
            comparison.add(kind, declared, verdict);
            boolean unsafe = comparison.count(Outcome.UNSAFE) > unsafeBefore;
            if (unsafe || comparison.count(Outcome.NOISE) > noiseBefore) {
                String shape = declared.arrays() > 0 ? (info instanceof ParameterInfo pi && pi.isVarArgs()
                        ? "varargs" : "array") : "plain";
                disagreements.add((unsafe ? "UNSAFE" : "NOISE") + "\t" + kind + "\t" + shape + "\t"
                                  + info.fullyQualifiedName() + "\t" + report.explain(info));
            }
        });
        LOGGER.info("INFERENCE ({} policy), {} declarations without a verdict\n{}", name, missing[0],
                comparison.report());
        // every disagreement with its cause chain, for classification: kind, shape, element, chain
        java.nio.file.Path out = java.nio.file.Path.of("build", "nullability-oracle-guava-" + name + ".tsv");
        try {
            java.nio.file.Files.createDirectories(out.getParent());
            java.nio.file.Files.write(out, disagreements.stream().sorted().toList());
            LOGGER.info("INFERENCE ({} policy): {} disagreements written to {}", name, disagreements.size(),
                    out.toAbsolutePath());
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
        return comparison;
    }
}
