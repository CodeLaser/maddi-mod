package io.codelaser.maddi.run.openjdkmain;

import io.codelaser.maddi.cst.api.expression.AnnotationExpression;
import io.codelaser.maddi.cst.api.info.FieldInfo;
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
import io.codelaser.maddi.modification.analyzer.nullability.NullabilityComparison;
import io.codelaser.maddi.modification.analyzer.nullability.NullabilityComparison.Kind;
import io.codelaser.maddi.modification.analyzer.nullability.NullabilityComparison.Outcome;
import io.codelaser.maddi.run.config.util.JsonStreaming;
import io.codelaser.maddi.util.corpus.Corpora;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
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

    private static List<TypeInfo> parseGuava() throws IOException {
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
        return List.copyOf(summary.parseResult().primaryTypes());
    }

    /** Every source declaration with a reference type, through {@code sink(kind, declared type)}. */
    static void declarations(List<TypeInfo> primaryTypes, DeclaredNullability dn,
                             BiConsumer<Kind, ParameterizedType> sink) {
        primaryTypes.stream()
                .filter(t -> !t.typeNature().isPackageInfo())
                .flatMap(TypeInfo::recursiveSubTypeStream)
                .forEach(t -> {
                    for (FieldInfo f : t.fields()) {
                        if (!f.isSynthetic()) sink.accept(Kind.FIELD, dn.field(f));
                    }
                    t.constructorAndMethodStream().filter(m -> !m.isSynthetic()).forEach(m -> {
                        for (ParameterInfo p : m.parameters()) sink.accept(Kind.PARAMETER, dn.parameter(p));
                        ParameterizedType rt = dn.returnType(m);
                        if (rt != null) sink.accept(Kind.RETURN, rt);
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
        declarations(types, dn, (kind, declared) -> comparison.add(kind, declared, verdict.apply(declared)));
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
}
