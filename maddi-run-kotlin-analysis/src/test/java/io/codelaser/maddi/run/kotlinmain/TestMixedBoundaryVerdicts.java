/*
 * maddi: a modification analyzer for duplication detection and immutability.
 * Copyright 2020-2026, CodeLaser BV, https://github.com/CodeLaser/maddi
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.codelaser.maddi.run.kotlinmain;

import io.codelaser.maddi.cst.api.analysis.Value;
import io.codelaser.maddi.cst.api.info.Info;
import io.codelaser.maddi.cst.api.info.MethodInfo;
import io.codelaser.maddi.cst.api.info.ParameterInfo;
import io.codelaser.maddi.cst.api.info.TypeInfo;
import io.codelaser.maddi.cst.api.runtime.Runtime;
import io.codelaser.maddi.cst.impl.analysis.PropertyImpl;
import io.codelaser.maddi.cst.impl.analysis.ValueImpl;
import io.codelaser.maddi.cst.api.element.SourceSet;
import io.codelaser.maddi.graph.G;
import io.codelaser.maddi.inspection.api.resource.InputConfiguration;
import io.codelaser.maddi.inspection.mixed.MixedProjectInspector;
import io.codelaser.maddi.inspection.resource.InputConfigurationImpl;
import io.codelaser.maddi.inspection.resource.SourceSetImpl;
import io.codelaser.maddi.kotlin.api.PlaceholderCensus;
import io.codelaser.maddi.modification.analyzer.impl.IteratingAnalyzerImpl;
import io.codelaser.maddi.modification.prepwork.PrepAnalyzer;
import io.codelaser.maddi.callgraph.ComputeAnalysisOrder;
import io.codelaser.maddi.modification.prepwork.io.LoadAnalysisResults;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ⭐ <b>Does a verdict cross the language boundary?</b> {@code TestLoweredShapesVsJava} answers it in the
 * Kotlin → Java direction (Kotlin code calling a Java helper). This is the other one, and it is the
 * direction the downstream planners actually hit: <b>Java calling Kotlin</b>.
 *
 * <p>The parse of that direction is well covered ({@code TestMixedHardening}: void/Unit, companions, file
 * facades, extensions, varargs, generics). A parse is not a verdict. This test is owned HERE rather than in
 * a downstream repository because the boundary it pins belongs to maddi: the mixed inspector generates the
 * Java stubs javac resolves Kotlin through, and a stub that loses a method body's effects would still parse.
 *
 * <p>⛔ The two assertions are a matched pair on purpose. "Java sees the Kotlin modification" alone would
 * also pass if the analysis simply gave up and called every parameter modified, which is why the read-only
 * method is asserted in the same run — and why {@code KBox}'s own verdicts are asserted first, so a failure
 * says WHICH side broke.
 */
public class TestMixedBoundaryVerdicts {
    private static final Logger LOGGER = LoggerFactory.getLogger(TestMixedBoundaryVerdicts.class);

    private static final String KOTLIN = """
            package a
            // ⚠ deliberately JDK-free: this source set has no Java dependency (the dependency runs the other
            // way), so an `ArrayList` here is an unresolved call and the census guard below correctly refuses
            // the run. A field of a primitive type is a modification the analysis can see without any library.
            class KBox {
                private var count: Int = 0
                fun add(n: Int) { count += n }
                fun size(): Int = count
            }
            """;

    private static final String JAVA = """
            package b;
            import a.KBox;
            public class JUser {
                public void viaKotlin(KBox k, int n) { k.add(n); }
                public int readOnly(KBox k) { return k.size(); }
            }
            """;

    @Test
    public void aModificationMadeInKotlinIsVisibleToJavaThatCallsIt(@TempDir Path tmp) throws Exception {
        Path kDir = tmp.resolve("src/main/kotlin");
        Path jDir = tmp.resolve("src/main/java");
        Files.createDirectories(kDir.resolve("a"));
        Files.createDirectories(jDir.resolve("b"));
        Files.writeString(kDir.resolve("a/KBox.kt"), KOTLIN);
        Files.writeString(jDir.resolve("b/JUser.java"), JAVA);

        // ⚠ the dependency runs the other way round from TestLoweredShapesVsJava: there Kotlin uses a Java
        // helper, here Java uses a Kotlin type, so it is the JAVA set that depends on the Kotlin one.
        SourceSet kotlinSet = new SourceSetImpl.Builder().setName("kotlin/main")
                .setSourceDirectories(List.of(kDir)).setUri(kDir.toUri()).build();
        SourceSet javaSet = new SourceSetImpl.Builder().setName("java/main")
                .setSourceDirectories(List.of(jDir)).setUri(jDir.toUri())
                .setDependencies(List.of(kotlinSet)).build();
        InputConfiguration config = new InputConfigurationImpl.Builder()
                .addSourceSets(kotlinSet).addSourceSets(javaSet).build();

        MixedProjectInspector.Result parsed = new MixedProjectInspector().parse(config);
        Runtime runtime = parsed.getRuntime();
        Set<TypeInfo> primaryTypes = Stream.concat(parsed.getKotlinTypes().stream(), parsed.getJavaTypes().stream())
                .map(TypeInfo::primaryType).collect(Collectors.toUnmodifiableSet());
        PlaceholderCensus census = PlaceholderCensus.of(parsed.getKotlinTypes());
        assertEquals(0, census.getTotal(), "unread Kotlin would make this vacuous: " + census.getByKind());

        new LoadAnalysisResults(runtime, kotlinSet).go(LoadAnalysisResults.ANALYZED_RESULTS);
        PrepAnalyzer prepAnalyzer = new PrepAnalyzer(runtime,
                new PrepAnalyzer.Options.Builder().setFaultTolerant(true).build());
        G<Info> callGraph = prepAnalyzer.doPrimaryTypesReturnGraph(primaryTypes);
        assertEquals(List.of(), prepAnalyzer.exceptions().stream().map(String::valueOf).toList(),
                "prep must isolate nothing: an isolated element is an unanswered question, not an answer");
        List<Info> order = new ComputeAnalysisOrder().go(callGraph);
        new IteratingAnalyzerImpl(parsed.getJavaInspector(), new IteratingAnalyzerImpl.ConfigurationBuilder()
                .setMaxIterations(30).setStopWhenCycleDetectedAndNoImprovements(true).setFaultTolerant(true)
                .build()).analyze(order, callGraph);

        TypeInfo kBox = type(primaryTypes, "a.KBox");
        TypeInfo jUser = type(primaryTypes, "b.JUser");

        // 1. the Kotlin side on its own, so a failure below names the side that broke
        assertFalse(nonModifying(method(kBox, "add")), "KBox.add mutates its own list; the Kotlin analysis must see it");
        assertTrue(nonModifying(method(kBox, "size")), "KBox.size only reads");

        // 2. ⭐ the crossing
        boolean modifiedAcross = !unmodified(method(jUser, "viaKotlin").parameters().getFirst());
        boolean readOnlyAcross = unmodified(method(jUser, "readOnly").parameters().getFirst());
        LOGGER.info("across the boundary: viaKotlin modifies its KBox = {}, readOnly leaves it alone = {}",
                modifiedAcross, readOnlyAcross);
        assertTrue(modifiedAcross, "Java calling a modifying Kotlin method must see the parameter modified");
        assertTrue(readOnlyAcross, "...and a Java caller of a read-only Kotlin method must NOT: "
                                   + "without this, 'everything is modified' would pass the assertion above");
    }

    private static boolean nonModifying(MethodInfo method) {
        return method.analysis().getOrDefault(PropertyImpl.NON_MODIFYING_METHOD, ValueImpl.BoolImpl.FALSE).isTrue();
    }

    private static boolean unmodified(ParameterInfo parameter) {
        Value.Bool v = parameter.analysis()
                .getOrDefault(PropertyImpl.UNMODIFIED_PARAMETER, ValueImpl.BoolImpl.FALSE);
        return v.isTrue();
    }

    private static TypeInfo type(Set<TypeInfo> types, String fqn) {
        return types.stream().filter(t -> fqn.equals(t.fullyQualifiedName())).findFirst()
                .orElseThrow(() -> new AssertionError("no type " + fqn + " in "
                        + types.stream().map(TypeInfo::fullyQualifiedName).sorted().toList()));
    }

    private static MethodInfo method(TypeInfo type, String name) {
        return type.methodStream().filter(m -> name.equals(m.name())).findFirst()
                .orElseThrow(() -> new AssertionError("no method " + name + " on " + type));
    }
}
