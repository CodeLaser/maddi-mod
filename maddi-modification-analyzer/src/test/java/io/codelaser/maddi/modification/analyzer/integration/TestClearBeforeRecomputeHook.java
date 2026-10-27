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

package io.codelaser.maddi.modification.analyzer.integration;

import io.codelaser.maddi.modification.analyzer.CommonTest;
import io.codelaser.maddi.modification.analyzer.IteratingAnalyzer;
import io.codelaser.maddi.modification.analyzer.impl.IteratingAnalyzerImpl;
import io.codelaser.maddi.callgraph.ComputeCallGraph;
import io.codelaser.maddi.cst.api.analysis.Property;
import io.codelaser.maddi.cst.api.info.Info;
import io.codelaser.maddi.cst.api.info.TypeInfo;
import io.codelaser.maddi.graph.G;
import org.intellij.lang.annotations.Language;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The clear-before-recompute hook of {@link IteratingAnalyzer#analyze(List, G, Set, Consumer)}: in incremental mode
 * the worklist pulls a carried type into the dirty frontier only when a dependency's summary changes, and the hook
 * fires once for each such element — before its first re-analysis — so the driver can clear its stale
 * cross-type-derived values (else the monotonic guard would reject a lowered value). The seed itself is fresh
 * (INVALID) source, never carried, so the hook never fires for it. See docs/design/analysis-rewiring.md.
 */
public class TestClearBeforeRecomputeHook extends CommonTest {

    @Language("java")
    private static final String INPUT = """
            package a.b;
            class X {
                private int i;
                int get() { return i; }
                void set(int j) { this.i = j; }
                int getTwice() { return get() + get(); }
            }
            """;

    private static final Predicate<Property> CROSS_TYPE_DERIVED =
            p -> p.analysisTier() == Property.AnalysisTier.CROSS_TYPE_DERIVED;

    private IteratingAnalyzer newAnalyzer() {
        return new IteratingAnalyzerImpl(javaInspector,
                new IteratingAnalyzerImpl.ConfigurationBuilder().setMaxIterations(10).build());
    }

    @DisplayName("the hook fires for propagated (carried) elements, once each, never for the seed")
    @Test
    public void testHookFiresForPropagatedNotSeed() {
        TypeInfo X = javaInspector.parse("a.b.X", INPUT);
        List<Info> analysisOrder = prepWork(X);
        G<Info> graph = new ComputeCallGraph(runtime, X).go().graph();
        newAnalyzer().analyze(analysisOrder, graph); // to fixpoint

        // simulate every element being carried-but-stale: drop the cross-type-derived tier everywhere, so re-analysis
        // genuinely recomputes and propagates (an intrinsic-only reload state). Seed a single element.
        analysisOrder.forEach(i -> i.analysis().removeIf(CROSS_TYPE_DERIVED));
        Info seed = analysisOrder.getFirst();

        List<Info> fired = new ArrayList<>();
        newAnalyzer().analyze(analysisOrder, graph, Set.of(seed), fired::add);

        assertFalse(fired.isEmpty(), "propagation past the seed occurred, so the hook fired");
        assertFalse(fired.contains(seed), "the seed is fresh, never carried: the hook must not fire for it");
        assertEquals(fired.size(), Set.copyOf(fired).size(), "the hook fires at most once per element");
        assertTrue(analysisOrder.containsAll(fired), "every fired element is an analysis-order element");
    }

    @DisplayName("nothing propagates from a converged seed: the hook stays silent")
    @Test
    public void testHookSilentWhenNothingPropagates() {
        TypeInfo X = javaInspector.parse("a.b.X", INPUT);
        List<Info> analysisOrder = prepWork(X);
        G<Info> graph = new ComputeCallGraph(runtime, X).go().graph();
        newAnalyzer().analyze(analysisOrder, graph); // to fixpoint; nothing is stale

        List<Info> fired = new ArrayList<>();
        newAnalyzer().analyze(analysisOrder, graph, Set.of(analysisOrder.getFirst()), fired::add);

        assertTrue(fired.isEmpty(), "a converged seed changes no summary, so nothing is pulled in and the hook is silent");
    }
}
