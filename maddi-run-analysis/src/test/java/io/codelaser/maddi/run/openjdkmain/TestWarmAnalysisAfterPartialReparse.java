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

package io.codelaser.maddi.run.openjdkmain;

import io.codelaser.maddi.callgraph.CallGraphContributions;
import io.codelaser.maddi.callgraph.ComputeAnalysisOrder;
import io.codelaser.maddi.callgraph.ComputeCallGraph;
import io.codelaser.maddi.cst.api.element.SourceSet;
import io.codelaser.maddi.cst.api.info.Info;
import io.codelaser.maddi.cst.api.info.MethodInfo;
import io.codelaser.maddi.cst.api.info.TypeInfo;
import io.codelaser.maddi.inspection.api.integration.JavaInspector;
import io.codelaser.maddi.inspection.api.parser.ParseResult;
import io.codelaser.maddi.inspection.api.resource.InputConfiguration;
import io.codelaser.maddi.inspection.openjdk.JavaInspectorImpl;
import io.codelaser.maddi.inspection.resource.InputConfigurationImpl;
import io.codelaser.maddi.inspection.resource.SourceSetImpl;
import io.codelaser.maddi.modification.analyzer.impl.IteratingAnalyzerImpl;
import io.codelaser.maddi.modification.prepwork.PrepAnalyzer;
import io.codelaser.maddi.modification.prepwork.io.AnalysisFingerprint;
import org.intellij.lang.annotations.Language;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Consumer;
import java.util.function.Predicate;

import static io.codelaser.maddi.inspection.api.integration.JavaInspector.InvalidationState.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The analysis on the objects a partial re-parse leaves, in one warm inspector: prep over the whole universe (the
 * carried types are skipped), then a seeded analyze whose seed is the re-parsed types' elements, stopping when the
 * worklist is dry. The kept types keep the values of the previous analysis unless the worklist pulls them in. The
 * result is compared with a cold parse, prep and analysis of the same files.
 * <p>
 * docs/design/incremental-and-overlay.md §7, the analysis half: the stale seed and the hierarchy-upward flow.
 */
public class TestWarmAnalysisAfterPartialReparse {

    @TempDir
    Path root;

    @Language("java")
    private static final String SHAPE = """
            package s;
            public interface Shape {
                double area();
            }
            """;

    @Language("java")
    private static final String CIRCLE = """
            package s;
            public class Circle implements Shape {
                private final double r;
                public Circle(double r) { this.r = r; }
                @Override public double area() { return 3.14 * r * r; }
            }
            """;

    // area() now modifies its object: Shape.area(), which Circle.area() implements, follows (the upward flow)
    @Language("java")
    private static final String CIRCLE_MODIFYING = """
            package s;
            public class Circle implements Shape {
                private double r;
                private int calls;
                public Circle(double r) { this.r = r; }
                @Override public double area() { calls++; return 3.14 * r * r; }
            }
            """;

    @Language("java")
    private static final String USER = """
            package s;
            public class User {
                public double twice(Shape shape) { return 2 * shape.area(); }
            }
            """;

    private static final Predicate<io.codelaser.maddi.cst.api.analysis.Property> OUTPUT =
            AnalysisFingerprint.ANALYZER_OUTPUT_ONLY;

    private record Warm(JavaInspector inspector, SourceSet set, ParseResult pr, Set<String> recomputed,
                        Map<String, String> before) {
    }

    /* cold analysis, Circle edited and re-parsed alone, prep, seeded analysis over Circle's elements */
    private Warm warmAfterCircleEdit() throws IOException {
        Path src = root.resolve("src");
        write(src, "s/Shape.java", SHAPE);
        write(src, "s/Circle.java", CIRCLE);
        write(src, "s/User.java", USER);
        SourceSet set = new SourceSetImpl.Builder().setName("main").setSourceDirectories(List.of(src))
                .setUri(src.toUri()).build();
        JavaInspector inspector = inspector(set);
        ParseResult pr = inspector.parse(Map.of(), JavaInspectorImpl.DETAILED_SOURCES).parseResult();
        CallGraphContributions contributions = new CallGraphContributions();
        Predicate<TypeInfo> noExternals = _ -> false;
        ComputeCallGraph ccg = prep(inspector, pr, contributions, noExternals);
        new IteratingAnalyzerImpl(inspector, configuration())
                .analyze(new ComputeAnalysisOrder().go(ccg.graph(), false), ccg.graph());
        assertSameAnalysis(dumpAll(coldAnalysis(set), inspector), dumpAll(pr, inspector), "sanity: cold equals cold");
        TypeInfo shape0 = pr.findType("s.Shape");
        Map<String, String> before = dumpAll(pr, inspector);

        write(src, "s/Circle.java", CIRCLE_MODIFYING);
        JavaInspector.ParseOptions options = new JavaInspector.ParseOptions.Builder()
                .setDetailedSources(true).setFailFast(true)
                .setInvalidated(ti -> ti.fullyQualifiedName().equals("s.Circle") ? INVALID : UNCHANGED)
                .build();
        pr = inspector.parse(Map.of(), options).parseResult();
        assertSame(shape0, pr.findType("s.Shape"), "Shape does not depend on Circle: kept");

        ccg = prep(inspector, pr, contributions, noExternals);
        List<Info> order = new ComputeAnalysisOrder().go(ccg.graph(), false);
        TypeInfo circle = pr.findType("s.Circle");
        Set<Info> seed = new HashSet<>();
        for (Info info : order) {
            TypeInfo type = info instanceof TypeInfo ti ? ti : info.typeInfo();
            if (type != null && type.primaryType() == circle) seed.add(info);
        }
        Set<String> recomputed = new java.util.TreeSet<>();
        Consumer<Info> clearBeforeRecompute = info -> {
            recomputed.add(info.fullyQualifiedName());
            io.codelaser.maddi.modification.prepwork.ClearDerived.element(info);
        };
        IteratingAnalyzerImpl analyzer = new IteratingAnalyzerImpl(inspector, configuration());
        analyzer.analyze(order, ccg.graph(), seed, clearBeforeRecompute);
        // the scope it reports, which its messages are about, is what it cleared and analysed
        assertEquals(recomputed, analyzer.lastScope().stream().map(Info::fullyQualifiedName)
                .collect(java.util.stream.Collectors.toCollection(java.util.TreeSet::new)));
        return new Warm(inspector, set, pr, recomputed, before);
    }

    @DisplayName("an implementation is re-parsed: the worklist pulls in the kept abstract method and its user")
    @Test
    public void testHierarchyUpward() throws IOException {
        Warm warm = warmAfterCircleEdit();
        assertTrue(warm.recomputed().containsAll(Set.of("s.Shape.area()", "s.User.twice(s.Shape)")),
                "the upward flow pulls in the kept Shape.area(), and from there its user: " + warm.recomputed());
        // prep's registry of implementations survives the clear-before-recompute, and names the live Circle.area()
        MethodInfo area = warm.pr().findType("s.Shape").findUniqueMethod("area", 0);
        var implementations = area.analysis().getOrNull(io.codelaser.maddi.cst.impl.analysis.PropertyImpl.IMPLEMENTATIONS,
                io.codelaser.maddi.cst.impl.analysis.ValueImpl.SetOfMethodInfoImpl.class);
        assertNotNull(implementations, "IMPLEMENTATIONS is prep's, the analyzer's clear must leave it");
        MethodInfo liveArea = warm.pr().findType("s.Circle").findUniqueMethod("area", 0);
        assertSame(liveArea, implementations.methodInfoSet().iterator().next());
    }

    /*
     Not yet: an incremental analysis stops when the worklist is dry. A cold one goes on: a verification pass, then
     cycle breaking (and MODREACH and the eventual contraction, both off in this configuration), and part of its result
     is settled there. Here the warm User.twice(Shape) ends with its parameter unmodified while the cold run, correctly,
     does not conclude that: shape.area() now modifies. The warm Shape and User also miss the cold immutableType.
     docs/design/incremental-and-overlay.md §3 item 3.
     */
    @DisplayName("an implementation becomes modifying: the warm analysis equals a cold one")
    @Test
    public void testWarmEqualsCold() throws IOException {
        Warm warm = warmAfterCircleEdit();
        assertSameAnalysis(dumpAll(coldAnalysis(warm.set()), warm.inspector()), dumpAll(warm.pr(), warm.inspector()),
                "warm as a cold analysis (recomputed: " + warm.recomputed() + "; before the edit: " + warm.before() + ")");
    }

    /* per type, the element lines that differ: "-" cold only, "+" warm only */
    private static void assertSameAnalysis(Map<String, String> cold, Map<String, String> warm, String what) {
        StringBuilder sb = new StringBuilder();
        for (String type : new java.util.TreeSet<>(cold.keySet())) {
            List<String> c = List.of(cold.get(type).split("\n"));
            List<String> w = List.of(String.valueOf(warm.get(type)).split("\n"));
            if (c.equals(w)) continue;
            sb.append("\n").append(type).append(':');
            c.stream().filter(l -> !w.contains(l)).forEach(l -> sb.append("\n  - ").append(l.strip()));
            w.stream().filter(l -> !c.contains(l)).forEach(l -> sb.append("\n  + ").append(l.strip()));
        }
        assertTrue(sb.isEmpty(), what + ":" + sb);
    }

    private static IteratingAnalyzerImpl.Configuration configuration() {
        return new IteratingAnalyzerImpl.ConfigurationBuilder().setMaxIterations(20)
                .setStopWhenCycleDetectedAndNoImprovements(true).build();
    }

    private static ComputeCallGraph prep(JavaInspector inspector, ParseResult pr, CallGraphContributions contributions,
                                         Predicate<TypeInfo> externals) {
        return new PrepAnalyzer(inspector.runtime(), new PrepAnalyzer.Options.Builder().setFaultTolerant(true).build())
                .doPrimaryTypesReturnComputeCallGraph(Set.copyOf(pr.primaryTypes()),
                        pr.sourceSetToModuleInfoMap().values(), externals, false, List.of(), null, contributions);
    }

    private static ParseResult coldAnalysis(SourceSet set) {
        JavaInspector cold = inspector(set);
        ParseResult pr = cold.parse(Map.of(), JavaInspectorImpl.DETAILED_SOURCES).parseResult();
        ComputeCallGraph ccg = prep(cold, pr, null, _ -> false);
        new IteratingAnalyzerImpl(cold, configuration())
                .analyze(new ComputeAnalysisOrder().go(ccg.graph(), false), ccg.graph());
        return pr;
    }

    /* every primary type's analyzer output, as the fingerprint dumps it, positions normalised */
    private static Map<String, String> dumpAll(ParseResult pr, JavaInspector inspector) {
        Map<String, String> map = new TreeMap<>();
        for (TypeInfo type : pr.primaryTypes()) {
            map.put(type.fullyQualifiedName(), AnalysisFingerprint.normalize(
                    AnalysisFingerprint.dump(inspector.runtime(), type, OUTPUT), AnalysisFingerprint.DEFAULT));
        }
        return map;
    }

    private static JavaInspector inspector(SourceSet set) {
        try {
            JavaInspector inspector = new JavaInspectorImpl(true, false);
            InputConfiguration inputConfiguration = new InputConfigurationImpl.Builder()
                    .addSourceSets(set)
                    .addClassPath(InputConfigurationImpl.DEFAULT_MODULES)
                    .build();
            inspector.initialize(inputConfiguration);
            return inspector;
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static void write(Path sourceRoot, String relative, String content) throws IOException {
        Path file = sourceRoot.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }
}
