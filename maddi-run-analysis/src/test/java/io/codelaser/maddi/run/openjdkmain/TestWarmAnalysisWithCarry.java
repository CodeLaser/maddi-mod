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

import static io.codelaser.maddi.inspection.api.integration.JavaInspector.InvalidationState.*;
import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Predicate;

import org.intellij.lang.annotations.Language;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.codelaser.maddi.callgraph.CallGraphContributions;
import io.codelaser.maddi.callgraph.ComputeAnalysisOrder;
import io.codelaser.maddi.callgraph.ComputeCallGraph;
import io.codelaser.maddi.callgraph.PrimaryTypeUseGraph;
import io.codelaser.maddi.callgraph.ReparseWithDependents;
import io.codelaser.maddi.cst.api.analysis.Property;
import io.codelaser.maddi.cst.api.element.SourceSet;
import io.codelaser.maddi.cst.api.info.Info;
import io.codelaser.maddi.cst.api.info.InfoMapView;
import io.codelaser.maddi.cst.api.info.TypeInfo;
import io.codelaser.maddi.inspection.api.integration.JavaInspector;
import io.codelaser.maddi.inspection.api.parser.ParseResult;
import io.codelaser.maddi.inspection.api.resource.InputConfiguration;
import io.codelaser.maddi.inspection.openjdk.JavaInspectorImpl;
import io.codelaser.maddi.inspection.resource.InputConfigurationImpl;
import io.codelaser.maddi.inspection.resource.SourceSetImpl;
import io.codelaser.maddi.modification.analyzer.impl.ElementSummary;
import io.codelaser.maddi.modification.analyzer.impl.IteratingAnalyzerImpl;
import io.codelaser.maddi.modification.prepwork.CarryDerived;
import io.codelaser.maddi.modification.prepwork.ClearDerived;
import io.codelaser.maddi.modification.prepwork.PrepAnalyzer;
import io.codelaser.maddi.modification.prepwork.io.AnalysisFingerprint;

/**
 * A seeded analysis whose seed is the re-parsed type alone: the {@code REWIRE} cone's analyzer output is carried onto
 * the rewired copies ({@link CarryDerived}) and the worklist wakes a cone element only when an input it read changed.
 * {@code Holder} uses {@code Circle} through a field, a method and a lambda, so an edit of {@code Circle} rewires it.
 * The early cutoff ({@link IteratingAnalyzerImpl#setPriorSummaries}) compares the re-analysed {@code Circle} with its
 * previous generation: unchanged, the carried output stands; changed, the run falls back to the cold analysis.
 * docs/design/incremental-and-overlay.md §3 item 3.
 */
public class TestWarmAnalysisWithCarry {

    @TempDir
    Path root;

    @Language("java")
    private static final String CIRCLE = """
            package s;
            public class Circle {
                private final double r;
                public Circle(double r) { this.r = r; }
                public double area() { return 3.14 * r * r; }
            }
            """;

    // area() now modifies its object
    @Language("java")
    private static final String CIRCLE_MODIFYING = """
            package s;
            public class Circle {
                private final double r;
                private int calls;
                public Circle(double r) { this.r = r; }
                public double area() { calls++; return 3.14 * r * r; }
            }
            """;

    @Language("java")
    private static final String HOLDER = """
            package s;
            import java.util.ArrayList;
            import java.util.List;
            public class Holder {
                private final List<Circle> circles = new ArrayList<>();
                public void add(Circle c) { circles.add(c); }
                public double total() { return circles.stream().mapToDouble(c -> c.area()).sum(); }
                public List<Circle> get() { return circles; }
            }
            """;

    private static final Predicate<Property> OUTPUT =
            AnalysisFingerprint.ANALYZER_OUTPUT_ONLY;

    private record Warm(JavaInspector inspector, SourceSet set, ParseResult pr, Set<String> recomputed,
                        boolean fellBack) {
    }

    /* cold analysis; Circle edited, re-parsed, Holder rewired; prep; Holder's output carried; seed: Circle */
    private Warm warmAfterCircleEdit(String newCircle) throws IOException {
        Path src = root.resolve("src");
        write(src, "s/Circle.java", CIRCLE);
        write(src, "s/Holder.java", HOLDER);
        SourceSet set = new SourceSetImpl.Builder().setName("main").setSourceDirectories(List.of(src))
                .setUri(src.toUri()).build();
        JavaInspector inspector = inspector(set);
        ParseResult pr0 = inspector.parse(Map.of(), JavaInspectorImpl.DETAILED_SOURCES).parseResult();
        CallGraphContributions contributions = new CallGraphContributions();
        Predicate<TypeInfo> noExternals = _ -> false;
        ComputeCallGraph ccg = prep(inspector, pr0, contributions, noExternals);
        new IteratingAnalyzerImpl(inspector, configuration())
                .analyze(new ComputeAnalysisOrder().go(ccg.graph(), false), ccg.graph());
        TypeInfo circle0 = pr0.findType("s.Circle");
        TypeInfo holder0 = pr0.findType("s.Holder");
        assertEquals(Set.of(holder0), new PrimaryTypeUseGraph(ccg.graph()).dependentsOf(Set.of(circle0)));

        write(src, "s/Circle.java", newCircle);
        JavaInspector.ParseOptions options = new JavaInspector.ParseOptions.Builder()
                .setDetailedSources(true).setFailFast(true)
                .setInvalidated(ti -> ti == circle0 ? INVALID : ti == holder0 ? REWIRE : UNCHANGED)
                .build();
        ParseResult pr = inspector.parse(Map.of(), options).parseResult();
        assertNotSame(holder0, pr.findType("s.Holder"), "Holder was rewired");

        ccg = prep(inspector, pr, contributions, noExternals);
        InfoMapView view = inspector.lastRewireInfoMap();
        CarryDerived.primaryType(holder0, view);
        List<Info> order = new ComputeAnalysisOrder().go(ccg.graph(), false);
        TypeInfo circle = pr.findType("s.Circle");
        Set<Info> seed = new HashSet<>();
        for (Info info : order) {
            TypeInfo type = info instanceof TypeInfo ti ? ti : info.typeInfo();
            if (type != null && type.primaryType() == circle) seed.add(info);
        }
        Set<String> recomputed = new TreeSet<>();
        IteratingAnalyzerImpl analyzer = new IteratingAnalyzerImpl(inspector, configuration());
        // the early cutoff: Circle's previous generation, whose output the carried Holder was computed against
        analyzer.setPriorSummaries(ElementSummary.ofTypes(Set.of(circle0)));
        analyzer.analyze(order, ccg.graph(), seed, info -> {
            recomputed.add(info.fullyQualifiedName());
            ClearDerived.element(info);
        });
        return new Warm(inspector, set, pr, recomputed, analyzer.cutoffFellBack());
    }

    @DisplayName("a comment in Circle: Holder's carried output stands, nothing of it is analysed again")
    @Test
    public void testCommentSparesTheCone() throws IOException {
        Warm warm = warmAfterCircleEdit("// a comment\n" + CIRCLE);
        assertFalse(warm.fellBack());
        assertTrue(warm.recomputed().stream().noneMatch(s -> s.startsWith("s.Holder")),
                "the carried Holder is spared: " + warm.recomputed());
        assertSameAnalysis(dumpAll(coldAnalysis(warm.set()), warm.inspector()), dumpAll(warm.pr(), warm.inspector()),
                "warm with the carry as a cold analysis (recomputed: " + warm.recomputed() + ")");
    }

    @DisplayName("Circle.area() becomes modifying: Holder's carried output no longer holds, the cold analysis runs")
    @Test
    public void testChangeFallsBackToCold() throws IOException {
        Warm warm = warmAfterCircleEdit(CIRCLE_MODIFYING);
        assertTrue(warm.fellBack(), "Circle.area() moved, and Holder.total() reads it");
        assertSameAnalysis(dumpAll(coldAnalysis(warm.set()), warm.inspector()), dumpAll(warm.pr(), warm.inspector()),
                "warm with the carry as a cold analysis (recomputed: " + warm.recomputed() + ")");
    }

    // ---- an edit of Circle's declarations: Holder's names resolve differently, so it is parsed again, not rewired

    @Language("java")
    private static final String SCALED = """
            package s;
            public class Circle {
                private double r;
                public Circle(double r) { this.r = r; }
                public double scale(Object o) { return r; }
            }
            """;

    @Language("java")
    private static final String SCALING_HOLDER = """
            package s;
            public class Holder {
                private final Circle circle = new Circle(1);
                public double area() { return circle.scale("x"); }
                public String text() { return circle.toString(); }
            }
            """;

    /*
     cold analysis; Circle edited; ReparseWithDependents: Holder is rewired, or parsed again when Circle's declarations
     moved. Rewired: Holder's output is carried, the seed is Circle, with the early cutoff. Parsed again: Holder is a
     new type without values, so both are seeded, and nothing is carried.
     */
    private Warm warmAfterEdit(String circleSource, String newCircle, String holderSource, boolean expectReparsed)
            throws IOException {
        Path src = root.resolve("src");
        write(src, "s/Circle.java", circleSource);
        write(src, "s/Holder.java", holderSource);
        SourceSet set = new SourceSetImpl.Builder().setName("main").setSourceDirectories(List.of(src))
                .setUri(src.toUri()).build();
        JavaInspector inspector = inspector(set);
        ParseResult pr0 = inspector.parse(Map.of(), JavaInspectorImpl.DETAILED_SOURCES).parseResult();
        CallGraphContributions contributions = new CallGraphContributions();
        Predicate<TypeInfo> noExternals = _ -> false;
        ComputeCallGraph ccg = prep(inspector, pr0, contributions, noExternals);
        new IteratingAnalyzerImpl(inspector, configuration())
                .analyze(new ComputeAnalysisOrder().go(ccg.graph(), false), ccg.graph());
        TypeInfo circle0 = pr0.findType("s.Circle");
        TypeInfo holder0 = pr0.findType("s.Holder");

        write(src, "s/Circle.java", newCircle);
        ReparseWithDependents.Outcome reparse = ReparseWithDependents.go(inspector, Set.of(circle0), Set.of(holder0),
                invalidated -> new JavaInspector.ParseOptions.Builder().setDetailedSources(true).setFailFast(true)
                        .setInvalidated(invalidated).build());
        assertEquals(expectReparsed, reparse.dependentsReparsed());
        ParseResult pr = reparse.parseResult();

        ccg = prep(inspector, pr, contributions, noExternals);
        Set<String> seedTypes = new HashSet<>(Set.of("s.Circle"));
        IteratingAnalyzerImpl analyzer = new IteratingAnalyzerImpl(inspector, configuration());
        if (reparse.dependentsReparsed()) {
            seedTypes.add("s.Holder");
        } else {
            CarryDerived.primaryType(holder0, inspector.lastRewireInfoMap());
            analyzer.setPriorSummaries(ElementSummary.ofTypes(Set.of(circle0)));
        }
        List<Info> order = new ComputeAnalysisOrder().go(ccg.graph(), false);
        Set<Info> seed = new HashSet<>();
        for (Info info : order) {
            TypeInfo type = info instanceof TypeInfo ti ? ti : info.typeInfo();
            if (type != null && seedTypes.contains(type.primaryType().fullyQualifiedName())) seed.add(info);
        }
        Set<String> recomputed = new TreeSet<>();
        analyzer.analyze(order, ccg.graph(), seed, info -> {
            recomputed.add(info.fullyQualifiedName());
            ClearDerived.element(info);
        });
        return new Warm(inspector, set, pr, recomputed, analyzer.cutoffFellBack());
    }

    private void assertAsCold(Warm warm) {
        assertSameAnalysis(dumpAll(coldAnalysis(warm.set()), warm.inspector()), dumpAll(warm.pr(), warm.inspector()),
                "warm as a cold analysis (recomputed: " + warm.recomputed() + ")");
    }

    @DisplayName("an overload scale(String) that modifies: Holder.area() calls it now, and is no longer non-modifying")
    @Test
    public void testOverloadAdded() throws IOException {
        Warm warm = warmAfterEdit(SCALED, SCALED.replace("public double scale(Object o) { return r; }",
                "public double scale(Object o) { return r; }\n    public double scale(String s) { r++; return r; }"),
                SCALING_HOLDER, true);
        assertTrue(warm.recomputed().contains("s.Holder.area()"), "Holder was parsed again: " + warm.recomputed());
        assertAsCold(warm);
    }

    @DisplayName("toString() overridden, modifying: Holder.text() calls Circle's now")
    @Test
    public void testInheritedMethodOverridden() throws IOException {
        Warm warm = warmAfterEdit(SCALED, SCALED.replace("public double scale(Object o) { return r; }",
                "public double scale(Object o) { return r; }\n"
                + "    @Override public String toString() { r++; return \"c\"; }"), SCALING_HOLDER, true);
        assertAsCold(warm);
    }

    @DisplayName("a body edit through the same path: Holder is rewired and its output carried")
    @Test
    public void testBodyEditThroughReparseWithDependents() throws IOException {
        Warm warm = warmAfterEdit(SCALED, "// a comment\n" + SCALED, SCALING_HOLDER, false);
        assertFalse(warm.fellBack());
        assertTrue(warm.recomputed().stream().noneMatch(s -> s.startsWith("s.Holder")), "" + warm.recomputed());
        assertAsCold(warm);
    }

    // ---- real edits of a body, where the early cutoff decides: whatever it decides, the result is a cold one

    @Language("java")
    private static final String POKING = """
            package s;
            public class Circle {
                private final double r;
                public Circle(double r) { this.r = r; }
                public double area() { return 3.14 * r * r; }
                public void poke(Holder h) { POKE }
            }
            """;

    @DisplayName("Circle.poke now also assigns a field of Holder, which it modified already")
    @Test
    public void testForeignFieldAssigned() throws IOException {
        String holder = """
                package s;
                public class Holder {
                    int count;
                    int other;
                    private final Circle circle = new Circle(1);
                    public int count() { return count; }
                    public double area() { return circle.area(); }
                }
                """;
        assertAsCold(warmAfterEdit(POKING.replace("POKE", "h.other = 2;"),
                POKING.replace("POKE", "h.other = 2; h.count = 3;"), holder, false));
    }

    @DisplayName("Circle.poke now also modifies a second field of Holder")
    @Test
    public void testForeignFieldModified() throws IOException {
        String holder = """
                package s;
                import java.util.ArrayList;
                import java.util.List;
                public class Holder {
                    final List<String> items = new ArrayList<>();
                    final List<String> names = new ArrayList<>();
                    private final Circle circle = new Circle(1);
                    public int size() { return names.size(); }
                    public double area() { return circle.area(); }
                }
                """;
        assertAsCold(warmAfterEdit(POKING.replace("POKE", "h.items.add(\"a\");"),
                POKING.replace("POKE", "h.items.add(\"a\"); h.names.add(\"b\");"), holder, false));
    }

    @Language("java")
    private static final String PLAIN = """
            package s;
            public class Circle {
                private double r;
                public Circle(double r) { this.r = r; }
                public double area() { return r; }
            }
            """;

    @Language("java")
    private static final String PLAIN_HOLDER = """
            package s;
            public class Holder {
                private final Circle circle = new Circle(1);
                public String text() { return "" + circle.area(); }
                public Circle get() { return circle; }
            }
            """;

    @DisplayName("Circle.area() stops modifying: the cutoff sees it move, the cold analysis runs")
    @Test
    public void testMethodStopsModifying() throws IOException {
        Warm warm = warmAfterEdit(PLAIN.replace("return r;", "r++; return r;"), PLAIN, PLAIN_HOLDER, false);
        assertTrue(warm.fellBack());
        assertAsCold(warm);
    }

    @DisplayName("another computation of the same value in Circle.area()")
    @Test
    public void testBodyRewritten() throws IOException {
        assertAsCold(warmAfterEdit(PLAIN, PLAIN.replace("return r;", "double q = r * 2; return q / 2;"),
                PLAIN_HOLDER, false));
    }

    @DisplayName("a setter nobody calls makes Circle mutable: a declaration edit, Holder is parsed again")
    @Test
    public void testTypeBecomesMutable() throws IOException {
        assertAsCold(warmAfterEdit(PLAIN.replace("private double r;", "private final double r;"),
                PLAIN.replace("public double area() { return r; }",
                        "public double area() { return r; }\n    public void setR(double d) { r = d; }"),
                PLAIN_HOLDER, true));
    }

    /* per type, the element lines that differ: "-" cold only, "+" warm only */
    private static void assertSameAnalysis(Map<String, String> cold, Map<String, String> warm, String what) {
        StringBuilder sb = new StringBuilder();
        for (String type : new TreeSet<>(cold.keySet())) {
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
