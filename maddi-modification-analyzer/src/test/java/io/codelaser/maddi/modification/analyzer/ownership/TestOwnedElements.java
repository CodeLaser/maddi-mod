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

package io.codelaser.maddi.modification.analyzer.ownership;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.intellij.lang.annotations.Language;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.codelaser.maddi.cst.api.info.Info;
import io.codelaser.maddi.cst.api.info.TypeInfo;
import io.codelaser.maddi.modification.analyzer.CommonTest;
import io.codelaser.maddi.modification.analyzer.impl.IteratingAnalyzerImpl;

/**
 * {@code OWNED_ELEMENTS_FIELD}: the elements of an owned collection are reachable only through it.
 */
public class TestOwnedElements extends CommonTest {

    @Language("java")
    private static final String INPUT = """
            package a.b;
            import java.util.*;
            public class X {
                static class N { int v; void inc() { v++; } }
                static class Keeper {
                    private final List<N> kept = new ArrayList<>();
                    void keep(N n) { kept.add(n); }
                    int size() { return kept.size(); }
                }
                static class H {
                    private final List<N> fresh = new ArrayList<>();
                    private final List<N> handed = new ArrayList<>();
                    private final List<N> fromPublic = new ArrayList<>();
                    private final List<N> gotten = new ArrayList<>();
                    private final List<N> iterated = new ArrayList<>();
                    private final Map<String, N> map = new HashMap<>();
                    private final Map<String, N> mapOk = new HashMap<>();
                    private final List<N> copied;
                    private final List<N> streamed = new ArrayList<>();
                    private final List<N> removed = new ArrayList<>();
                    private final List<String> strings = new ArrayList<>();
                    private final List<N> exposed = new ArrayList<>();
                    private final List<N> lambda = new ArrayList<>();
                    private final List<N> compared = new ArrayList<>();
                    private final List<N> passed = new ArrayList<>();
                    private final List<N> aliased = new ArrayList<>();
                    private N first;
                    H(List<N> src) { copied = new ArrayList<>(src); }
                    void addFresh() { fresh.add(new N()); }
                    void touch() { for (N n : fresh) n.inc(); }
                    private void addHanded(N n) { handed.add(n); }
                    void more() { addHanded(new N()); }
                    public void addPublic(N n) { fromPublic.add(n); }
                    N get(int i) { return gotten.get(i); }
                    void each(List<N> sink) { for (N n : iterated) sink.add(n); }
                    void put(String k) { map.put(k, new N()); }
                    N look(String k) { return map.get(k); }
                    void putOk(String k) { mapOk.put(k, new N()); }
                    void bump(String k) { N n = mapOk.get(k); if (n != null) n.inc(); }
                    int count() { return (int) streamed.stream().filter(n -> n.v > 0).count(); }
                    N take() { return removed.remove(0); }
                    void addString(String s) { strings.add(s); }
                    List<N> getExposed() { return exposed; }
                    void incAll() { lambda.forEach(N::inc); }
                    void addCompared() { compared.add(new N()); }
                    void addPassed() { passed.add(new N()); }
                    void give(Keeper k) { for (N n : passed) k.keep(n); }
                    void addAliased() { aliased.add(new N()); }
                    void alias() { first = aliased.get(0); }
                    boolean has(N x) { for (N n : compared) if (n == x) return true; return false; }
                    int size() { return fresh.size() + handed.size() + copied.size() + streamed.size() + removed.size()
                                        + lambda.size() + compared.size() + strings.size() + passed.size()
                                        + aliased.size() + (first == null ? 0 : 1); }
                }
            }
            """;

    private static final Map<String, Boolean> EXPECTED = new TreeMap<>(Map.ofEntries(
            Map.entry("fresh", true),
            // every caller of the private addHanded hands a new N over
            Map.entry("handed", true),
            Map.entry("fromPublic", false),
            Map.entry("gotten", false),
            Map.entry("iterated", false),
            Map.entry("map", false),
            // a value read, its null checked, modified: it stays in the map
            Map.entry("mapOk", true),
            Map.entry("copied", false),
            Map.entry("streamed", false),
            Map.entry("removed", false),
            Map.entry("exposed", false),
            Map.entry("lambda", false),
            // an element compared with ==
            Map.entry("compared", true),
            // handed to a method that keeps it
            Map.entry("passed", false),
            // also held by another field
            Map.entry("aliased", false)));

    @DisplayName("OWNED_ELEMENTS_FIELD: an owned collection's elements, reachable only through it")
    @Test
    public void test() throws IOException {
        AnalyzerBundle bundle = buildAnalyzerBundle();
        TypeInfo x = bundle.javaInspector().parse("a.b.X", INPUT);
        List<Info> analysisOrder = bundle.prepAnalyzer().doPrimaryType(x);
        new IteratingAnalyzerImpl(bundle.javaInspector(), new IteratingAnalyzerImpl.ConfigurationBuilder()
                .setMaxIterations(10).setModificationViaReachability(true).setTrackObjectCreations(true).build())
                .analyze(analysisOrder);
        OwnershipPass.Report report = new OwnershipPass(false).go(analysisOrder);
        Map<String, Boolean> actual = new TreeMap<>();
        report.ownedElements().forEach((f, b) -> {
            if (!"H".equals(f.owner().simpleName())) return;
            actual.put(f.name(), b);
            System.out.println("ELEMENTS " + f.name() + " = " + b + "  "
                               + report.notOwnedElementsReason().getOrDefault(f, ""));
        });
        assertEquals(EXPECTED, actual);
    }
}
