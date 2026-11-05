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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.codelaser.maddi.callgraph.ComputeAnalysisOrder;
import io.codelaser.maddi.callgraph.ComputeCallGraph;
import io.codelaser.maddi.callgraph.PrimaryTypeUseGraph;
import io.codelaser.maddi.cst.api.element.FingerPrint;
import io.codelaser.maddi.cst.api.element.SourceSet;
import io.codelaser.maddi.cst.api.info.Info;
import io.codelaser.maddi.cst.api.info.TypeInfo;
import io.codelaser.maddi.graph.G;
import io.codelaser.maddi.graph.V;
import io.codelaser.maddi.inspection.api.integration.JavaInspector;
import io.codelaser.maddi.inspection.api.parser.ParseResult;
import io.codelaser.maddi.inspection.api.resource.InputConfiguration;
import io.codelaser.maddi.inspection.openjdk.JavaInspectorImpl;
import io.codelaser.maddi.inspection.resource.InputConfigurationImpl;
import io.codelaser.maddi.inspection.resource.SourceSetImpl;
import io.codelaser.maddi.modification.analyzer.impl.IteratingAnalyzerImpl;
import io.codelaser.maddi.modification.prepwork.PrepAnalyzer;
import io.codelaser.maddi.modification.prepwork.callgraph.EarlyCutoffWorklist;
import io.codelaser.maddi.modification.prepwork.io.AnalysisFingerprint;

/**
 * Prototype of the early-cutoff <em>compare half</em> (docs/design/analysis-rewiring.md). We analyse a tiny project, snapshot the
 * per-type analysisFingerprint, edit a source file, analyse again, and diff the snapshots. The diff is exactly the
 * set of types whose analyzer output actually moved — the blast radius a real incremental run would have to
 * recompute; everything else is what the cutoff would spare.
 * <p>
 * This demonstrates the <em>decision</em> (which types changed), not yet the saving: both runs are full, clean
 * analyses on a fresh inspector. Wiring the decision into the reload flow so unchanged types are not re-analysed at
 * all is the next step, and it needs the carry so a spared type returns with its analysis rather than empty.
 * <p>
 * Lives in {@code run-openjdk} because that is the only module with the analyzer, the openjdk front-end, and the
 * fingerprint on one path ({@code run-rewire} cannot see {@code modification.analyzer}).
 */
public class TestAnalysisEarlyCutoffPrototype {

    // Data is edited from unmodifiable to mutable. User, Holder and Reader all use it structurally; only the first two
    // keep a Data in a field, so only their output can follow Data's.
    private static final String DATA_V1 = """
            package x;
            public class Data {
                private final int v;
                public Data(int v) { this.v = v; }
                public int v() { return v; }
            }
            """;
    private static final String DATA_MUTABLE = """
            package x;
            public class Data {
                private int v;
                public Data(int v) { this.v = v; }
                public int v() { return v; }
                public void set(int v) { this.v = v; }
            }
            """;
    // User keeps a Data in a field and hands it out. The field's independence follows Data's modifiability: with the
    // cycle-breaking sweep (CBSWEEP, maddi-mod#82) it is INDEPENDENT while Data is unmodifiable and not once Data has a
    // setter, so User's output moves with Data's. (Without the sweep the field stayed undecided either way, which is
    // why this test once expected User to be spared.)
    private static final String USER = """
            package x;
            public class User {
                private final Data data;
                public User(Data d) { this.data = d; }
                public Data get() { return data; }
            }
            """;
    // Holder exposes its Data field publicly: its field's independence follows Data's in the same way as User's.
    private static final String HOLDER = """
            package x;
            public class Holder {
                public final Data data;
                public Holder(Data d) { this.data = d; }
            }
            """;

    // Reader only reads a Data argument's value: no field, nothing handed out, and v() is non-modifying whether Data
    // is mutable or not. Its verdicts cannot depend on Data's mutability: the firewall must SPARE it.
    private static final String READER = """
            package x;
            public class Reader {
                public static int twice(Data d) { return 2 * d.v(); }
            }
            """;

    @TempDir
    Path sourceDir;

    private InputConfiguration inputConfiguration() {
        SourceSet sourceSet = new SourceSetImpl.Builder()
                .setName("main")
                .setSourceDirectories(List.of(sourceDir))
                .setUri(sourceDir.toUri())
                .build();
        return new InputConfigurationImpl.Builder()
                .addSourceSets(sourceSet)
                .addClassPath(InputConfigurationImpl.DEFAULT_MODULES)
                .build();
    }

    /** Prep + modification analysis of a parse result on the given inspector; returns the call graph. */
    private ComputeCallGraph prepAndAnalyze(JavaInspector javaInspector, ParseResult pr) {
        PrepAnalyzer prep = new PrepAnalyzer(javaInspector.runtime(),
                new PrepAnalyzer.Options.Builder().setFaultTolerant(true).build());
        ComputeCallGraph ccg = prep.doPrimaryTypesReturnComputeCallGraph(Set.copyOf(pr.primaryTypes()),
                pr.sourceSetToModuleInfoMap().values(), t -> false, false);
        List<Info> order = new ComputeAnalysisOrder().go(ccg.graph(), false);
        new IteratingAnalyzerImpl(javaInspector,
                new IteratingAnalyzerImpl.ConfigurationBuilder().setMaxIterations(20)
                        .setStopWhenCycleDetectedAndNoImprovements(true).build())
                .analyze(order, ccg.graph());
        return ccg;
    }

    private TreeMap<String, FingerPrint> fingerprintAll(JavaInspector javaInspector, ParseResult pr) {
        TreeMap<String, FingerPrint> snapshot = new TreeMap<>();
        for (TypeInfo pt : pr.primaryTypes()) {
            snapshot.put(pt.fullyQualifiedName(), AnalysisFingerprint.of(javaInspector.runtime(), pt));
        }
        return snapshot;
    }

    /** A full clean analysis on a fresh inspector; returns fqn -> analysisFingerprint for every primary type. */
    private TreeMap<String, FingerPrint> analyzeAndFingerprint() throws IOException {
        JavaInspector javaInspector = new JavaInspectorImpl(true, false);
        javaInspector.initialize(inputConfiguration());
        ParseResult pr = javaInspector.parse(Map.of(), JavaInspectorImpl.DETAILED_SOURCES).parseResult();
        prepAndAnalyze(javaInspector, pr);
        return fingerprintAll(javaInspector, pr);
    }

    /** The compare-half decision: which types' analyzer output changed between two runs. */
    private static Set<String> changedTypes(TreeMap<String, FingerPrint> before, TreeMap<String, FingerPrint> after) {
        Set<String> changed = new TreeSet<>();
        for (String fqn : before.keySet()) {
            if (!before.get(fqn).equals(after.get(fqn))) changed.add(fqn);
        }
        return changed;
    }

    private void write(String data) throws IOException {
        Path pkg = Files.createDirectories(sourceDir.resolve("x"));
        Files.writeString(pkg.resolve("Data.java"), data);
        Files.writeString(pkg.resolve("User.java"), USER);
        Files.writeString(pkg.resolve("Holder.java"), HOLDER);
        Files.writeString(pkg.resolve("Reader.java"), READER);
    }

    @DisplayName("a comment-only edit changes no analyzer output: the whole run would be cut off")
    @Test
    public void testCommentEditIsFullyCutOff() throws IOException {
        write(DATA_V1);
        TreeMap<String, FingerPrint> before = analyzeAndFingerprint();
        assertEquals(Set.of("x.Data", "x.User", "x.Holder", "x.Reader"), before.keySet());

        // a comment edit that shifts every line, exactly like RunRewireTests' edit
        write("// a leading comment\n\n" + DATA_V1);
        TreeMap<String, FingerPrint> after = analyzeAndFingerprint();

        Set<String> changed = changedTypes(before, after);
        assertTrue(changed.isEmpty(),
                "a comment edit changes no verdict; the cutoff would spare every type, but these moved: " + changed);
    }

    @DisplayName("a semantic edit (Data becomes mutable) moves Data, and spares Reader, whose output cannot follow it")
    @Test
    public void testSemanticEditBlastRadius() throws IOException {
        write(DATA_V1);
        TreeMap<String, FingerPrint> before = analyzeAndFingerprint();

        write(DATA_MUTABLE);
        TreeMap<String, FingerPrint> after = analyzeAndFingerprint();

        Set<String> changed = changedTypes(before, after);
        System.out.println("=== compare-half blast radius (types whose analyzer output moved) === " + changed);
        assertTrue(changed.contains("x.Data"), "the edited type's own verdicts must move");
        // The firewall: Reader structurally uses Data, yet its *output* does not move, so the cutoff spares it. This is
        // the whole point of an output fingerprint over structural reachability: 'reaches Data' would recompute it.
        // User and Holder are not pinned either way: their field independence follows Data's when the analyzer
        // decides it (see USER), so whether they move depends on the engine, not on the cutoff.
        assertFalse(changed.contains("x.Reader"), "Reader's output is unchanged, so the cutoff must spare it: " + changed);
    }

    @DisplayName("through the REAL reload path (reloadSources + Invalidated + reparse), a comment edit still cuts off")
    @Test
    public void testReloadPathCommentEditCutsOff() throws IOException {
        write(DATA_V1);
        JavaInspector javaInspector = new JavaInspectorImpl(true, false);
        javaInspector.initialize(inputConfiguration());
        ParseResult pr0 = javaInspector.parse(Map.of(), JavaInspectorImpl.DETAILED_SOURCES).parseResult();
        ComputeCallGraph ccg0 = prepAndAnalyze(javaInspector, pr0);
        TreeMap<String, FingerPrint> before = fingerprintAll(javaInspector, pr0);

        // edit Data with a leading comment, then drive the real incremental reload path
        Files.writeString(sourceDir.resolve("x/Data.java"), "// a leading comment\n\n" + DATA_V1);
        JavaInspector.ReloadResult rr = javaInspector.reloadSources(inputConfiguration(), Map.of());
        Set<TypeInfo> changed = rr.sourceHasChanged();
        assertFalse(changed.isEmpty(), "the edited Data must be reported as source-changed");

        Set<TypeInfo> dependents = new PrimaryTypeUseGraph(ccg0.graph()).dependentsOf(changed);
        JavaInspector.Invalidated invalidated = ti ->
                changed.contains(ti) ? INVALID : dependents.contains(ti) ? REWIRE : UNCHANGED;
        JavaInspector.ParseOptions parseOptions = new JavaInspector.ParseOptions.Builder()
                .setDetailedSources(true).setFailFast(true).setInvalidated(invalidated).build();
        ParseResult pr1 = javaInspector.parse(parseOptions).parseResult();

        prepAndAnalyze(javaInspector, pr1);
        TreeMap<String, FingerPrint> after = fingerprintAll(javaInspector, pr1);

        Set<String> diff = changedTypes(before, after);
        System.out.println("=== reload-path blast radius (comment edit) === " + diff);
        assertTrue(diff.isEmpty(),
                "a comment edit through the real reload path changes no analyzer output; moved: " + diff);
    }

    @DisplayName("the WORKLIST on real fingerprints: a comment edit recomputes only the seed, sparing its dependents")
    @Test
    public void testWorklistCommentEditSparesDependents() throws IOException {
        write(DATA_V1);
        JavaInspector javaInspector = new JavaInspectorImpl(true, false);
        javaInspector.initialize(inputConfiguration());
        ParseResult pr0 = javaInspector.parse(Map.of(), JavaInspectorImpl.DETAILED_SOURCES).parseResult();
        ComputeCallGraph ccg0 = prepAndAnalyze(javaInspector, pr0);
        TreeMap<String, FingerPrint> before = fingerprintAll(javaInspector, pr0);

        Files.writeString(sourceDir.resolve("x/Data.java"), "// a leading comment\n\n" + DATA_V1);
        JavaInspector.ReloadResult rr = javaInspector.reloadSources(inputConfiguration(), Map.of());
        Set<TypeInfo> changed = rr.sourceHasChanged();
        Set<TypeInfo> dependents = new PrimaryTypeUseGraph(ccg0.graph()).dependentsOf(changed);
        JavaInspector.Invalidated invalidated = ti ->
                changed.contains(ti) ? INVALID : dependents.contains(ti) ? REWIRE : UNCHANGED;
        ParseResult pr1 = javaInspector.parse(new JavaInspector.ParseOptions.Builder()
                .setDetailedSources(true).setFailFast(true).setInvalidated(invalidated).build()).parseResult();
        prepAndAnalyze(javaInspector, pr1);
        TreeMap<String, FingerPrint> after = fingerprintAll(javaInspector, pr1);

        // one-hop dependents from the run-0 use graph (edge t -> Y means Y uses t), keyed by fqn
        G<TypeInfo> useGraph = new PrimaryTypeUseGraph(ccg0.graph()).graph();
        Map<String, TypeInfo> byFqn = new HashMap<>();
        pr0.primaryTypes().forEach(t -> byFqn.put(t.fullyQualifiedName(), t));
        Function<String, Set<String>> immediateDependents = fqn -> {
            TypeInfo t = byFqn.get(fqn);
            V<TypeInfo> v = t == null ? null : useGraph.vertex(t);
            if (v == null) return Set.of();
            return useGraph.edges(v).keySet().stream().map(V::t)
                    .map(TypeInfo::fullyQualifiedName).collect(Collectors.toSet());
        };
        Set<String> seed = changed.stream().map(TypeInfo::fullyQualifiedName)
                .collect(Collectors.toSet());

        // recompute = look up the post-edit fingerprint (a stand-in for actually re-analysing t; the real per-type
        // recompute against carried dependencies is the documented production-integration gap, docs/design/analysis-rewiring.md)
        EarlyCutoffWorklist.Result<String> result = EarlyCutoffWorklist.run(
                seed, immediateDependents, before::get, after::get);
        System.out.println("=== worklist recomputed (comment edit) === " + result.recomputed());
        assertEquals(Set.of("x.Data"), result.recomputed(),
                "the seed's analysis output is unchanged, so the worklist cuts off: User and Holder are spared");
    }
}
