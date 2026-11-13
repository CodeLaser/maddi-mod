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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;

import org.intellij.lang.annotations.Language;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.codelaser.maddi.cst.api.info.Info;
import io.codelaser.maddi.cst.api.info.TypeInfo;
import io.codelaser.maddi.modification.analyzer.CommonTest;
import io.codelaser.maddi.modification.analyzer.impl.IteratingAnalyzerImpl;
import io.codelaser.maddi.modification.link.vf.CanonicalVirtuals;

/**
 * An analysed model whose owner drops it is released: no static of the analysis keeps it reachable. Two did
 * (2026-11-13): {@link CanonicalVirtuals} held its keys strongly, and a key's owner type reached, through its
 * analysis, the very fields the entry's weak value referred to, so no entry was ever cleared (the refactor server
 * kept a dropped model of 8.6 GB alive through it); and {@code TolerantWrite}'s changed targets of the last pass stayed
 * set after the analysis, until the next one.
 */
public class TestAnalysedModelRelease extends CommonTest {

    @Language("java")
    private static final String INPUT = """
            package a.b;
            import java.util.ArrayList;
            import java.util.HashMap;
            import java.util.List;
            import java.util.Map;
            class X<T> {
                final List<T> list = new ArrayList<>();
                final Map<String, T> map = new HashMap<>();
                void add(T t) { list.add(t); }
                T get(String k) { return map.get(k); }
                static class Y extends X<String> {
                    void addAll(Y other) { other.list.addAll(list); }
                    String first(Y other) { return other.list.get(0); }
                    void put(Y other, String s) { other.map.put(s, s); }
                }
                static <K, V> Map<K, V> copy(Map<K, V> m) { return new HashMap<>(m); }
            }
            """;

    @DisplayName("an analysed model that is dropped is released, and the virtual-field cache holds nothing of it")
    @Test
    public void test() throws InterruptedException {
        List<Object> entries = new ArrayList<>();
        List<WeakReference<TypeInfo>> model = analyse(entries);

        // drop every holder of the model this test has
        javaInspector = null;
        runtime = null;
        prepAnalyzer = null;
        analyzer = null;

        for (int i = 0; i < 50 && model.stream().anyMatch(w -> w.get() != null); i++) {
            List<byte[]> pressure = new ArrayList<>();
            for (int j = 0; j < 32; j++) pressure.add(new byte[1 << 20]);
            pressure.clear();
            System.gc();
        }
        for (WeakReference<TypeInfo> w : model) {
            assertNull(w.get(), "a type of the dropped model is still reachable");
        }
        // this model's entries only: the cache is static, and the other tests of this JVM fill and empty it meanwhile
        // (gate run 43407323930 compared the global size). A cleared reference reaches the queue a little after the GC.
        int left = CanonicalVirtuals.stillHeld(entries);
        for (int i = 0; i < 100 && left > 0; i++) {
            Thread.sleep(20);
            left = CanonicalVirtuals.stillHeld(entries);
        }
        assertEquals(0, left, "entries left of the dropped model, of " + entries.size());
    }

    // in a method of its own, so that no local of the test keeps the model
    private List<WeakReference<TypeInfo>> analyse(List<Object> entries) {
        TypeInfo X = javaInspector.parse("a.b.X", INPUT);
        List<Info> analysisOrder = prepWork(X);
        new IteratingAnalyzerImpl(javaInspector, new IteratingAnalyzerImpl.ConfigurationBuilder()
                .setMaxIterations(20).build()).analyze(analysisOrder);
        TypeInfo Y = X.findSubType("Y");
        // not vacuous: the analysis left the model's own types in the cache
        assertTrue(CanonicalVirtuals.holdsAny(X, Y), "the analysis left nothing of the model in the cache");
        entries.addAll(CanonicalVirtuals.entriesHolding(X, Y));
        return List.of(new WeakReference<>(X), new WeakReference<>(Y));
    }
}
