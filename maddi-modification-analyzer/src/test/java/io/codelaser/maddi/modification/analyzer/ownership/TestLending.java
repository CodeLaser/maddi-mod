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
 * Lending: a getter whose every caller only uses the object it returns leaves the field owned ({@code &self.f}).
 */
public class TestLending extends CommonTest {

    @Language("java")
    private static final String INPUT = """
            package a.b;
            import java.util.*;
            public class X {
                static class N { int v; void inc() { v++; } }
                static class Keeper {
                    private final List<Object> kept = new ArrayList<>();
                    void keep(Object o) { kept.add(o); }
                    int size() { return kept.size(); }
                }
                private final List<String> sized = new ArrayList<>();
                private final List<String> viaLocal = new ArrayList<>();
                private final List<String> iterated = new ArrayList<>();
                private final List<String> copied = new ArrayList<>();
                private final List<String> passedOn = new ArrayList<>();
                private final List<String> stored = new ArrayList<>();
                private final List<String> asElement = new ArrayList<>();
                private final List<String> publicly = new ArrayList<>();
                private final List<String> kept = new ArrayList<>();
                private final List<String> captured = new ArrayList<>();
                private final List<N> nodes = new ArrayList<>();
                private final List<N> keptNodes = new ArrayList<>();
                private List<String> other;
                private final List<List<String>> all = new ArrayList<>();
                private N first;

                List<String> getSized() { return sized; }
                int n() { return getSized().size(); }

                List<String> getViaLocal() { return viaLocal; }
                void put(String s) { List<String> x = getViaLocal(); x.add(s); }

                List<String> getIterated() { return iterated; }
                int count() { int c = 0; for (String s : getIterated()) c += s.length(); return c; }

                List<String> getCopied() { return copied; }
                void copy(List<String> out) { out.addAll(getCopied()); }

                List<String> getPassedOn() { return passedOn; }
                List<String> pass() { return getPassedOn(); }

                List<String> getStored() { return stored; }
                void store() { other = getStored(); }

                List<String> getAsElement() { return asElement; }
                void collect() { all.add(getAsElement()); }

                public List<String> getPublicly() { return publicly; }

                List<String> getKept() { return kept; }
                void give(Keeper k) { k.keep(getKept()); }

                List<String> getCaptured() { return captured; }
                Runnable later() { List<String> x = getCaptured(); return () -> x.clear(); }

                void grow() { nodes.add(new N()); keptNodes.add(new N()); }
                N node(int i) { return nodes.get(i); }
                void bump() { node(0).inc(); }
                N keptNode(int i) { return keptNodes.get(i); }
                void keepFirst() { first = keptNode(0); }

                int size() { return other.size() + all.size() + (first == null ? 0 : 1); }
            }
            """;

    private static final Map<String, String> OWNED = new TreeMap<>(Map.ofEntries(
            Map.entry("sized", "OWNED"),
            Map.entry("viaLocal", "OWNED"),
            Map.entry("iterated", "OWNED"),
            // addAll(Collection) keeps the elements, not the collection
            Map.entry("copied", "OWNED"),
            // returned on: no transitive lending yet
            Map.entry("passedOn", "NOT_OWNED"),
            Map.entry("stored", "NOT_OWNED"),
            // add(E) keeps its argument
            Map.entry("asElement", "NOT_OWNED"),
            Map.entry("publicly", "NOT_OWNED"),
            Map.entry("kept", "NOT_OWNED"),
            Map.entry("captured", "NOT_OWNED"),
            Map.entry("nodes", "OWNED"),
            Map.entry("keptNodes", "OWNED")));

    private static final Map<String, Boolean> ELEMENTS = new TreeMap<>(Map.of(
            // node(i) only lends: bump() calls inc() on it
            "nodes", true,
            // keptNode(i)'s element ends up in the field first
            "keptNodes", false));

    @DisplayName("lending: a getter whose callers only use its result leaves the field owned")
    @Test
    public void test() throws IOException {
        AnalyzerBundle bundle = buildAnalyzerBundle();
        TypeInfo x = bundle.javaInspector().parse("a.b.X", INPUT);
        List<Info> analysisOrder = bundle.prepAnalyzer().doPrimaryType(x);
        new IteratingAnalyzerImpl(bundle.javaInspector(), new IteratingAnalyzerImpl.ConfigurationBuilder()
                .setMaxIterations(10).setModificationViaReachability(true).setTrackObjectCreations(true).build())
                .analyze(analysisOrder);
        OwnershipPass.Report report = new OwnershipPass(false).go(analysisOrder);
        Map<String, String> owned = new TreeMap<>();
        report.owned().forEach((f, o) -> {
            if (!OWNED.containsKey(f.name()) || !"X".equals(f.owner().simpleName())) return;
            owned.put(f.name(), o.name());
            System.out.println("LENDING " + f.name() + " = " + o + "  " + report.notOwnedReason().getOrDefault(f, ""));
        });
        Map<String, Boolean> elements = new TreeMap<>();
        report.ownedElements().forEach((f, b) -> {
            if (!ELEMENTS.containsKey(f.name())) return;
            elements.put(f.name(), b);
            System.out.println("LENDING elements " + f.name() + " = " + b + "  "
                               + report.notOwnedElementsReason().getOrDefault(f, ""));
        });
        assertEquals(OWNED, owned);
        assertEquals(ELEMENTS, elements);
    }
}
