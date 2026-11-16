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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import org.intellij.lang.annotations.Language;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.codelaser.maddi.cst.api.info.FieldInfo;
import io.codelaser.maddi.cst.api.info.Info;
import io.codelaser.maddi.cst.api.info.ParameterInfo;
import io.codelaser.maddi.cst.api.info.TypeInfo;
import io.codelaser.maddi.cst.impl.analysis.PropertyImpl;
import io.codelaser.maddi.cst.impl.analysis.ValueImpl;
import io.codelaser.maddi.modification.analyzer.CommonTest;
import io.codelaser.maddi.modification.analyzer.impl.IteratingAnalyzerImpl;

/**
 * The ownership round's feed (docs/roadmap/ownership.md, "Fed into the fixpoint"): a parameter every caller hands over
 * into an owned field is no dependence; its content still is.
 */
public class TestOwnershipFeed extends CommonTest {

    @Language("java")
    private static final String INPUT = """
            package a.b;
            import java.util.*;
            public class X {
                public static final class Counter {
                    private int n;
                    public void inc() { n++; }
                    public int get() { return n; }
                }
                // handed over, String elements: immutable
                public static final class Handed {
                    private final List<String> l;
                    public Handed(List<String> l) { this.l = l; }
                    public static Handed of(String s) { List<String> list = new ArrayList<>(); list.add(s); return new Handed(list); }
                    public String get(int i) { return l.get(i); }
                }
                // the caller keeps the list: not handed over
                public static final class Kept {
                    private final List<String> l;
                    public Kept(List<String> l) { this.l = l; }
                    public static Kept of(String s) {
                        List<String> list = new ArrayList<>();
                        Kept k = new Kept(list);
                        list.add(s);
                        return k;
                    }
                    public String get(int i) { return l.get(i); }
                }
                // handed over, but its mutable elements come from the caller: dependent through the content
                public static final class Counters {
                    private final List<Counter> l;
                    public Counters(List<Counter> l) { this.l = l; }
                    public static Counters of(Counter c) { List<Counter> list = new ArrayList<>(); list.add(c); return new Counters(list); }
                    public int total() { int t = 0; for (Counter c : l) t += c.get(); return t; }
                }
            }
            """;

    @DisplayName("closed world: a handed-over parameter is no dependence, its mutable content is")
    @Test
    public void test() throws IOException {
        Map<String, String> closed = run(true);
        assertEquals("@Immutable(hc=true) @Independent", closed.get("Handed"));
        assertEquals("@FinalFields @Dependent", closed.get("Kept"));
        assertEquals("owned", closed.get("Counters.l"));
        assertEquals(false, closed.get("Counters").contains("@Immutable"), closed.get("Counters"));
        assertEquals(false, closed.get("Counters").endsWith("@Independent"), closed.get("Counters"));
        // open world: the public constructors' callers are unknown, nothing is handed over
        Map<String, String> open = run(false);
        assertEquals("@FinalFields @Dependent", open.get("Handed"));
    }

    private Map<String, String> run(boolean closedWorld) throws IOException {
        AnalyzerBundle bundle = buildAnalyzerBundle();
        TypeInfo x = bundle.javaInspector().parse("a.b.X", INPUT);
        List<Info> analysisOrder = bundle.prepAnalyzer().doPrimaryType(x);
        new IteratingAnalyzerImpl(bundle.javaInspector(), new IteratingAnalyzerImpl.ConfigurationBuilder()
                .setMaxIterations(10).setModificationViaReachability(true).setOwnership(true)
                .setOwnershipClosedWorld(closedWorld).build())
                .analyze(analysisOrder);
        Map<String, String> actual = new TreeMap<>();
        x.recursiveSubTypeStream().forEach(t -> {
            actual.put(t.simpleName(), t.analysis().getOrNull(PropertyImpl.IMMUTABLE_TYPE, ValueImpl.ImmutableImpl.class)
                                       + " " + t.analysis().getOrNull(PropertyImpl.INDEPENDENT_TYPE,
                    ValueImpl.IndependentImpl.class));
            t.fields().forEach(fi -> actual.put(t.simpleName() + "." + fi.name(),
                    fi.analysis().getOrDefault(PropertyImpl.OWNED_FIELD, ValueImpl.BoolImpl.FALSE).isTrue()
                            ? "owned" : "-"));
        });
        actual.forEach((k, v) -> System.out.println("FEED " + (closedWorld ? "closed " : "open ") + k + " = " + v));
        return actual;
    }

    @DisplayName("the feed's delta: grown, unchanged, shrunk")
    @Test
    public void delta() throws IOException {
        TypeInfo x = javaInspector.parse("a.b.X", INPUT);
        TypeInfo handed = x.findSubType("Handed");
        ParameterInfo p = handed.findConstructor(1).parameters().getFirst();
        FieldInfo f = handed.getFieldByName("l", true);
        OwnershipFeed feed = new OwnershipFeed();
        Set<ParameterInfo> changed = new HashSet<>();
        assertEquals(OwnershipFeed.Delta.GROWN, feed.update(Map.of(p, f), changed));
        assertEquals(Set.of(p), changed);
        changed.clear();
        assertEquals(OwnershipFeed.Delta.UNCHANGED, feed.update(Map.of(p, f), changed));
        assertEquals(Set.of(), changed);
        assertEquals(OwnershipFeed.Delta.SHRUNK, feed.update(Map.of(), changed));
        assertEquals(Set.of(p), changed);
    }
}
