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

import io.codelaser.maddi.analysis.api.AnalysisValueFeed;
import io.codelaser.maddi.modification.analyzer.CommonTest;
import io.codelaser.maddi.modification.analyzer.IteratingAnalyzer;
import io.codelaser.maddi.modification.analyzer.impl.IteratingAnalyzerImpl;
import io.codelaser.maddi.cst.api.info.Info;
import org.intellij.lang.annotations.Language;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The streaming seam for the IDE daemon (see {@link AnalysisValueFeed}): pass boundaries deliver the
 * analyzed elements (all of them on the full first pass), the terminal phase certifies. The daemon-side
 * adapter lives on the ide branch; this pins the engine-side contract.
 */
public class TestAnalysisValueFeed extends CommonTest {

    @Language("java")
    private static final String INPUT = """
            package a.b;
            class X {
                private int i;
                int get() { return i; }
                void set(int i) { this.i = i; }
                static int twice(int j) { return j + j; }
            }
            """;

    record Pass(int iteration, boolean fullPass, int analyzed) {
    }

    @DisplayName("pass boundaries stream the analyzed elements; terminal phase certifies")
    @Test
    public void test() {
        var X = javaInspector.parse("a.b.X", INPUT);
        List<Info> analysisOrder = prepWork(X);

        List<Pass> passes = new ArrayList<>();
        List<AnalysisValueFeed.Phase> phases = new ArrayList<>();
        AnalysisValueFeed feed = new AnalysisValueFeed() {
            @Override
            public void passCompleted(int iteration, boolean fullPass, Collection<Info> analyzed) {
                passes.add(new Pass(iteration, fullPass, analyzed.size()));
            }

            @Override
            public void phase(Phase phase, int iteration) {
                phases.add(phase);
            }
        };

        // default maxIterations is 1 (single-pass testing config): allow the loop to actually certify
        IteratingAnalyzer iterating = new IteratingAnalyzerImpl(javaInspector,
                new IteratingAnalyzerImpl.ConfigurationBuilder().setMaxIterations(10).build());
        iterating.setValueFeed(feed);
        iterating.analyze(analysisOrder);

        assertFalse(passes.isEmpty());
        Pass first = passes.getFirst();
        assertEquals(1, first.iteration);
        assertTrue(first.fullPass, "the first pass analyzes everything");
        assertEquals(analysisOrder.size(), first.analyzed);
        // iteration numbers strictly increase
        for (int i = 1; i < passes.size(); i++) {
            assertEquals(passes.get(i - 1).iteration + 1, passes.get(i).iteration);
        }
        // exactly one terminal phase, and on this tiny input it must be a certified fixpoint
        List<AnalysisValueFeed.Phase> terminal = phases.stream().filter(p -> p.name().startsWith("TERMINAL")).toList();
        assertEquals(List.of(AnalysisValueFeed.Phase.TERMINAL_CERTIFIED), terminal);
        // the feed never fires after the terminal event
        assertEquals(phases.getLast().name().startsWith("TERMINAL"), true);
    }
}
