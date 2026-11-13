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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

import org.intellij.lang.annotations.Language;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.codelaser.maddi.cst.api.info.Info;
import io.codelaser.maddi.cst.api.info.MethodInfo;
import io.codelaser.maddi.cst.api.info.TypeInfo;
import io.codelaser.maddi.modification.analyzer.CommonTest;
import io.codelaser.maddi.modification.analyzer.impl.IteratingAnalyzerImpl;

/**
 * {@code @Finalizer} computed and checked (docs/roadmap/ownership.md stage 6): transfer of the receiver.
 */
public class TestFinalizer extends CommonTest {

    @Language("java")
    private static final String INPUT = """
            package a.b;
            import java.util.*;
            import java.util.stream.*;
            import io.codelaser.maddi.annotation.rare.Finalizer;

            public class X {
                // a fluent chain on a new builder, its list moved out
                static class B1 {
                    private final List<String> list = new ArrayList<>();
                    B1 add(String s) { list.add(s); return this; }
                    List<String> build() { return list; }
                    static List<String> make() { return new B1().add("a").add("b").build(); }
                }
                // a local, used before as the receiver of add
                static class B2 {
                    private final List<String> list = new ArrayList<>();
                    B2 add(String s) { list.add(s); return this; }
                    List<String> build() { return list; }
                    static List<String> make() { B2 b = new B2(); b.add("x"); return b.build(); }
                }
                // used after
                static class B3 {
                    private final List<String> list = new ArrayList<>();
                    B3 add(String s) { list.add(s); return this; }
                    List<String> build() { return list; }
                    static List<String> make() { B3 b = new B3(); List<String> l = b.build(); b.add("y"); return l; }
                }
                // called on a parameter
                static class B4 {
                    private final List<String> list = new ArrayList<>();
                    List<String> build() { return list; }
                    static List<String> make(B4 b) { return b.build(); }
                    static List<String> other() { return new B4().build(); }
                }
                // called on temporaries only, but moves nothing out: no need, no verdict
                static class C {
                    private final List<String> list = new ArrayList<>();
                    int size() { return list.size(); }
                    static int n() { return new C().size(); }
                }
                // the list handed over to the product
                static class T5 {
                    private final List<String> l;
                    private T5(List<String> l) { this.l = l; }
                    int size() { return l.size(); }
                }
                static class B5 {
                    private final List<String> list = new ArrayList<>();
                    B5 add(String s) { list.add(s); return this; }
                    T5 build() { return new T5(list); }
                    static T5 make() { return new B5().add("a").build(); }
                }
                // public: callers outside the sources
                public static class B6 {
                    private final List<String> list = new ArrayList<>();
                    public B6 add(String s) { list.add(s); return this; }
                    public List<String> build() { return list; }
                    static List<String> make() { return new B6().add("a").build(); }
                }
                // the constructor registers the object: it was shared before the call
                static class B7 {
                    static final List<Object> REG = new ArrayList<>();
                    private final List<String> list = new ArrayList<>();
                    B7() { REG.add(this); }
                    List<String> build() { return list; }
                    static List<String> make() { return new B7().build(); }
                }

                // ---- contracts checked
                static class D {
                    private final List<String> list = new ArrayList<>();
                    @Finalizer List<String> close() { return list; }
                    void use() { list.add("x"); }
                    static void bad() { D d = new D(); d.close(); d.use(); }
                    static List<String> good() { D d = new D(); d.use(); return d.close(); }
                }
                static long reuse(List<String> list) {
                    Stream<String> s = list.stream();
                    s.map(String::trim);
                    return s.count();
                }
                static long rebind(List<String> list) {
                    Stream<String> s = list.stream();
                    s = s.filter(x -> !x.isEmpty());
                    return s.count();
                }
                static long chain(List<String> list) {
                    return list.stream().map(String::trim).filter(x -> !x.isEmpty()).count();
                }
                public static long param(Stream<String> s) { return s.count(); }
                private static long byValue(Stream<String> s) { return s.count(); }
                static long callsByValue(List<String> l) { return byValue(l.stream()); }
            }
            """;

    private static final Map<String, Boolean> FINALIZER_OPEN = new TreeMap<>(Map.of(
            "B1.build", true,
            "B2.build", true,
            "B3.build", false,
            "B4.build", false,
            "B5.build", true,
            "B6.build", false,
            "B7.build", false));

    private static final Map<String, String> OWNED_OPEN = new TreeMap<>(Map.of(
            "B1.list", "OWNED",
            "B2.list", "OWNED",
            "B3.list", "NOT_OWNED",
            "B4.list", "NOT_OWNED",
            "B5.list", "OWNED",
            "T5.l", "OWNED",
            "B6.list", "NOT_OWNED",
            "B7.list", "NOT_OWNED"));

    // caller, finalizer
    private static final TreeSet<String> VIOLATIONS = new TreeSet<>(List.of(
            "D.bad close",
            "X.param count",
            "X.reuse map"));

    @DisplayName("@Finalizer computed: need (an owned field moved out) and permission (every receiver handed over)")
    @Test
    public void test() throws IOException {
        AnalyzerBundle bundle = buildAnalyzerBundle();
        TypeInfo x = bundle.javaInspector().parse("a.b.X", INPUT);
        List<Info> analysisOrder = bundle.prepAnalyzer().doPrimaryType(x);
        new IteratingAnalyzerImpl(bundle.javaInspector(), new IteratingAnalyzerImpl.ConfigurationBuilder()
                .setMaxIterations(10).setModificationViaReachability(true).setTrackObjectCreations(true).build())
                .analyze(analysisOrder);
        // the pass is run here, not by the analyzer: a written FINALIZER_METHOD reads as a contract afterwards

        OwnershipPass.Report closed = new OwnershipPass(true).go(analysisOrder);
        OwnershipPass.Report open = new OwnershipPass(false).go(analysisOrder);
        assertEquals(FINALIZER_OPEN, finalizers(open, "open"));
        assertEquals(OWNED_OPEN, owned(open));
        assertEquals(VIOLATIONS, violations(open));
        assertTrue(open.finalizerViolations().stream().allMatch(m -> Finalizers.CATEGORY.equals(m.category())));

        Map<String, Boolean> finalizerClosed = new TreeMap<>(FINALIZER_OPEN);
        finalizerClosed.put("B6.build", true);
        assertEquals(finalizerClosed, finalizers(closed, "closed"));
        assertEquals("OWNED", owned(closed).get("B6.list"));
        OwnershipPass.write(open);
        assertTrue(method(x, "B1", "build").isFinalizer());
    }

    private static MethodInfo method(TypeInfo x, String type, String name) {
        return x.findSubType(type).findUniqueMethod(name, 0);
    }

    private static Map<String, Boolean> finalizers(OwnershipPass.Report report, String world) {
        Map<String, Boolean> map = new TreeMap<>();
        report.finalizer().forEach((m, b) -> {
            String key = m.typeInfo().simpleName() + "." + m.name();
            map.put(key, b);
            System.out.println("FINALIZER " + world + " " + key + " = " + b + "  "
                               + report.notFinalizerReason().getOrDefault(m, ""));
        });
        return map;
    }

    private static Map<String, String> owned(OwnershipPass.Report report) {
        Map<String, String> map = new TreeMap<>();
        report.owned().forEach((f, o) -> {
            String key = f.owner().simpleName() + "." + f.name();
            if (OWNED_OPEN.containsKey(key)) {
                map.put(key, o.name());
                System.out.println("OWNED " + key + " = " + o + "  " + report.notOwnedReason().getOrDefault(f, ""));
            }
        });
        return map;
    }

    private static TreeSet<String> violations(OwnershipPass.Report report) {
        TreeSet<String> set = new TreeSet<>();
        for (String[] row : report.finalizerViolationRows()) {
            System.out.println("VIOLATION " + String.join(" | ", row));
            String caller = row[0].replaceAll("\\(.*", "");
            String[] c = caller.split("\\.");
            String callee = row[1].replaceAll("\\(.*", "");
            set.add(c[c.length - 2] + "." + c[c.length - 1] + " " + callee.substring(callee.lastIndexOf('.') + 1));
        }
        return set;
    }
}
