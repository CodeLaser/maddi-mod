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
import io.codelaser.maddi.cst.impl.analysis.PropertyImpl;
import io.codelaser.maddi.cst.impl.analysis.ValueImpl;
import io.codelaser.maddi.modification.analyzer.CommonTest;
import io.codelaser.maddi.modification.analyzer.impl.IteratingAnalyzerImpl;

/**
 * {@code OWNED_FIELD} and {@code SHARED_IMMUTABLE_FIELD} (docs/roadmap/ownership.md stage 3): the field's object can
 * only be reached through its owner. Unlike a method summary, a field's own links record where its object goes; the
 * captures and callees they do not show are stage 2's rules.
 */
public class TestOwnedField extends CommonTest {

    @Language("java")
    private static final String INPUT = """
            package a.b;
            import java.util.*;
            import java.util.function.*;

            public class X {
                private static final List<Object> CACHE = new ArrayList<>();
                private final List<String> hidden = new ArrayList<>();
                private final List<String> exposed = new ArrayList<>();
                private final List<String> viewed = new ArrayList<>();
                private final List<String> fromParam;
                private final String name;
                private final List<String> toStatic = new ArrayList<>();
                private final List<String> toParam = new ArrayList<>();
                private final List<String> captured = new ArrayList<>();
                private final List<String> passedToCapturer = new ArrayList<>();
                private final List<String> passedToReader = new ArrayList<>();
                private final List<String> copiedOut = new ArrayList<>();
                private final List<String> readByLambda = new ArrayList<>();
                private final List<String> made = make();
                private final List<String> notMade = List.of();
                private List<String> lazyView;
                private List<String> lazy;
                private List<String> reassigned = new ArrayList<>();
                List<String> open = new ArrayList<>();
                private Supplier<Integer> task;

                X(List<String> p, String name) { this.fromParam = p; this.name = name; }
                static List<String> make() { return new ArrayList<>(); }
                int size() { return hidden.size() + fromParam.size() + made.size() + notMade.size(); }
                void add(String s) { hidden.add(s); }
                List<String> getExposed() { return exposed; }
                List<String> view() { return Collections.unmodifiableList(viewed); }
                void leakStatic() { CACHE.add(toStatic); }
                void leakParam(List<List<String>> sink) { sink.add(toParam); }
                Supplier<Integer> capture() { List<String> c = captured; return () -> c.size(); }
                void capturer(List<String> l) { task = () -> l.size(); }
                void give() { capturer(passedToCapturer); }
                int reader(List<String> l) { return l.size(); }
                int read() { return reader(passedToReader); }
                List<String> copy() { return new ArrayList<>(copiedOut); }
                IntSupplier counter() { return () -> readByLambda.size(); }
                List<String> lazyView() { if (lazyView == null) lazyView = new ArrayList<>(); return lazyView.subList(0, 0); }
                int lazySize() { if (lazy == null) lazy = new ArrayList<>(); return lazy.size(); }
                void reset() { reassigned = new ArrayList<>(); }
                String getName() { return name; }

                static class Node {
                    private final Node parent;
                    private final List<Node> children = new ArrayList<>();
                    Node(Node parent) { this.parent = parent; }
                    Node child() { Node n = new Node(this); children.add(n); return n; }
                    int count() { return children.size(); }
                }
            }
            """;

    private static final Map<String, String> EXPECTED = new TreeMap<>(Map.ofEntries(
            Map.entry("X.hidden", "OWNED"),
            Map.entry("X.passedToReader", "OWNED"),
            // a copy leaves, the field's object does not
            Map.entry("X.copiedOut", "OWNED"),
            // the lambda captures the owner, not the field's object
            Map.entry("X.readByLambda", "OWNED"),
            Map.entry("X.made", "OWNED"),
            Map.entry("X.lazy", "OWNED"),
            Map.entry("X.reassigned", "OWNED"),

            Map.entry("X.exposed", "NOT_OWNED"),
            // the view handed out holds the field's object: only the modification face shows it
            Map.entry("X.viewed", "NOT_OWNED"),
            Map.entry("X.lazyView", "NOT_OWNED"),
            Map.entry("X.fromParam", "NOT_OWNED"),
            // immutable, and also held by the caller
            Map.entry("X.name", "SHARED_IMMUTABLE"),
            Map.entry("X.toStatic", "NOT_OWNED"),
            Map.entry("X.toParam", "NOT_OWNED"),
            // no link shows these
            Map.entry("X.captured", "NOT_OWNED"),
            Map.entry("X.passedToCapturer", "NOT_OWNED"),
            // a library call without evidence (a shared empty list)
            Map.entry("X.notMade", "NOT_OWNED"),
            Map.entry("X.open", "NOT_OWNED"),
            Map.entry("X.task", "NOT_OWNED"),
            // a back-pointer: the parent is held by its children's parent field and by whoever holds it
            Map.entry("Node.parent", "NOT_OWNED"),
            // each child is also returned to the caller
            Map.entry("Node.children", "OWNED")));

    @DisplayName("OWNED_FIELD: only reachable through its owner")
    @Test
    public void test() throws IOException {
        AnalyzerBundle bundle = buildAnalyzerBundle();
        TypeInfo x = bundle.javaInspector().parse("a.b.X", INPUT);
        List<Info> analysisOrder = bundle.prepAnalyzer().doPrimaryType(x);
        new IteratingAnalyzerImpl(bundle.javaInspector(), new IteratingAnalyzerImpl.ConfigurationBuilder()
                .setMaxIterations(10).setModificationViaReachability(true).setOwnership(true).build())
                .analyze(analysisOrder);
        OwnershipPass.Report report = new OwnershipPass().go(analysisOrder);
        Map<String, String> actual = new TreeMap<>();
        x.recursiveSubTypeStream().flatMap(t -> t.fields().stream()).forEach(fi -> {
            String key = fi.owner().simpleName() + "." + fi.name();
            if (EXPECTED.containsKey(key)) {
                boolean owned = fi.analysis().getOrDefault(PropertyImpl.OWNED_FIELD, ValueImpl.BoolImpl.FALSE).isTrue();
                boolean shared = fi.analysis().getOrDefault(PropertyImpl.SHARED_IMMUTABLE_FIELD,
                        ValueImpl.BoolImpl.FALSE).isTrue();
                actual.put(key, owned ? "OWNED" : shared ? "SHARED_IMMUTABLE" : "NOT_OWNED");
                System.out.println("OWNED " + key + " = " + actual.get(key) + "  "
                                   + report.notOwnedReason().getOrDefault(fi, ""));
            }
        });
        assertEquals(EXPECTED, actual);
    }
}
