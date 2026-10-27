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
import io.codelaser.maddi.modification.analyzer.CheckpointWriter;
import io.codelaser.maddi.modification.analyzer.CommonTest;
import io.codelaser.maddi.modification.analyzer.IteratingAnalyzer;
import io.codelaser.maddi.modification.analyzer.impl.IteratingAnalyzerImpl;
import io.codelaser.maddi.modification.link.io.LinkCodec;
import io.codelaser.maddi.modification.prepwork.io.LoadAnalysisResults;
import io.codelaser.maddi.cst.api.info.Info;
import io.codelaser.maddi.cst.api.info.MethodInfo;
import io.codelaser.maddi.cst.api.info.TypeInfo;
import io.codelaser.maddi.cst.impl.analysis.PropertyImpl;
import org.intellij.lang.annotations.Language;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Task #34 (checkpoint/resume v1): the {@link CheckpointWriter} persists analyzed values at pass
 * boundaries; restore preloads them via {@link LoadAnalysisResults} and re-runs the analyzer, whose
 * verify-certify sweep is the soundness net. This pins the full round trip: analyze → checkpoint →
 * lose everything → restore → re-certify with identical verdicts.
 */
public class TestCheckpointResume extends CommonTest {

    @Language("java")
    private static final String INPUT = """
            package a.b;
            import java.util.ArrayList;
            import java.util.List;
            class X {
                private final List<String> list = new ArrayList<>();
                int size() { return list.size(); }
                void add(String s) { list.add(s); }
                static String twice(String s) { return s + s; }
            }
            """;

    @DisplayName("analyze -> checkpoint -> restore -> re-certify with identical verdicts")
    @Test
    public void test() throws IOException {
        TypeInfo X = javaInspector.parse("a.b.X", INPUT);
        List<Info> analysisOrder = prepWork(X);

        File dir = new File("build/json-checkpoint");
        LinkCodec linkCodec = new LinkCodec(javaInspector);
        CheckpointWriter writer = new CheckpointWriter(runtime, linkCodec::codec, dir);
        List<AnalysisValueFeed.Phase> phases = new ArrayList<>();
        AnalysisValueFeed feed = new AnalysisValueFeed() {
            @Override
            public void passCompleted(int iteration, boolean fullPass, java.util.Collection<Info> analyzed) {
                writer.passCompleted(iteration, fullPass, analyzed);
            }

            @Override
            public void phase(Phase phase, int iteration) {
                phases.add(phase);
                writer.phase(phase, iteration);
            }
        };
        IteratingAnalyzer iterating = new IteratingAnalyzerImpl(javaInspector,
                new IteratingAnalyzerImpl.ConfigurationBuilder().setMaxIterations(10).build());
        iterating.setValueFeed(feed);
        iterating.analyze(analysisOrder);

        assertTrue(phases.contains(AnalysisValueFeed.Phase.TERMINAL_CERTIFIED));
        assertTrue(writer.typesWritten() > 0, "the checkpoint must have written at least one type");
        assertTrue(new File(dir, "checkpoint-terminal.txt").exists(), "terminal marker must be written");

        Map<String, String> before = verdicts(X);
        assertFalse(before.isEmpty());

        // "crash": lose all in-memory analysis by re-parsing from scratch
        javaInspector.invalidateAllSources();
        TypeInfo X2 = javaInspector.parse("a.b.X", INPUT);
        List<Info> analysisOrder2 = prepWork(X2);

        // restore: preload the checkpointed values (present values win — prep recomputed some), then
        // re-run — the verify-certify sweep is the net
        int loaded = new LoadAnalysisResults(runtime, javaInspector.mainSources())
                .goDir(new LinkCodec(javaInspector).restoreCodec(), dir);
        assertTrue(loaded > 0, "restore must load the checkpointed files");

        IteratingAnalyzer iterating2 = new IteratingAnalyzerImpl(javaInspector,
                new IteratingAnalyzerImpl.ConfigurationBuilder().setMaxIterations(10).build());
        iterating2.analyze(analysisOrder2);

        Map<String, String> after = verdicts(X2);
        assertEquals(before, after, "restored + re-certified verdicts must be identical");
    }

    @DisplayName("wave-boundary delta writes during the first pass (checkpoint granularity gap)")
    @Test
    public void testWaveBoundary() throws IOException {
        TypeInfo X = javaInspector.parse("a.b.X", INPUT);
        List<Info> analysisOrder = prepWork(X);
        IteratingAnalyzer iterating = new IteratingAnalyzerImpl(javaInspector,
                new IteratingAnalyzerImpl.ConfigurationBuilder().setMaxIterations(10).build());
        iterating.analyze(analysisOrder);

        // throttled: within the interval, waves only accumulate — nothing on disk
        File throttledDir = new File("build/json-checkpoint-wave-throttled");
        deleteJsonFiles(throttledDir);
        LinkCodec linkCodec = new LinkCodec(javaInspector);
        CheckpointWriter throttled = new CheckpointWriter(runtime, linkCodec::codec, throttledDir, Long.MAX_VALUE);
        throttled.waveCompleted(1, 1, analysisOrder);
        assertEquals(0, throttled.typesWritten(), "within the interval, the wave must only accumulate");

        // interval 0: every wave flushes immediately
        File waveDir = new File("build/json-checkpoint-wave");
        deleteJsonFiles(waveDir);
        CheckpointWriter perWave = new CheckpointWriter(runtime, linkCodec::codec, waveDir, 0);
        perWave.waveCompleted(1, 1, analysisOrder);
        assertTrue(perWave.typesWritten() > 0, "interval 0 must flush the wave delta");
        File[] json = waveDir.listFiles((_, name) -> name.endsWith(".json"));
        assertNotNull(json);
        assertTrue(json.length > 0, "the wave delta must be on disk");
    }

    private static void deleteJsonFiles(File dir) {
        File[] files = dir.listFiles((_, name) -> name.endsWith(".json"));
        if (files != null) for (File f : files) assertTrue(f.delete());
    }

    private Map<String, String> verdicts(TypeInfo typeInfo) {
        Map<String, String> map = new LinkedHashMap<>();
        typeInfo.recursiveSubTypeStream().forEach(ti -> {
            var imm = ti.analysis().getOrNull(PropertyImpl.IMMUTABLE_TYPE,
                    io.codelaser.maddi.cst.api.analysis.Value.Immutable.class);
            map.put(ti.fullyQualifiedName(), "immutable=" + imm);
            ti.constructorAndMethodStream().forEach(mi -> map.put(mi.fullyQualifiedName(),
                    "nonModifying=" + nonModifying(mi)));
        });
        return map;
    }

    private String nonModifying(MethodInfo mi) {
        var v = mi.analysis().getOrNull(PropertyImpl.NON_MODIFYING_METHOD,
                io.codelaser.maddi.cst.impl.analysis.ValueImpl.BoolImpl.class);
        return v == null ? "null" : String.valueOf(v.isTrue());
    }
}
