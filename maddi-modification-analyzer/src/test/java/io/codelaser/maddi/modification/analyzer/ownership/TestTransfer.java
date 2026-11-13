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

import io.codelaser.maddi.cst.api.info.FieldInfo;
import io.codelaser.maddi.cst.api.info.Info;
import io.codelaser.maddi.cst.api.info.TypeInfo;
import io.codelaser.maddi.modification.analyzer.CommonTest;
import io.codelaser.maddi.modification.analyzer.impl.IteratingAnalyzerImpl;

/**
 * Transfer (docs/roadmap/ownership.md stage 5): a field assigned from a parameter is owned when every caller hands
 * its argument over (creates it for the call and does not use it afterwards) and the callee keeps it only there.
 */
public class TestTransfer extends CommonTest {

    @Language("java")
    private static final String INPUT = """
            package a.b;
            import java.util.*;
            import java.util.function.*;
            import io.codelaser.maddi.annotation.rare.Finalizer;

            public class X {
                static class Plain {
                    private final List<String> l;
                    private Plain(List<String> l) { this.l = l; }
                    static Plain make() { return new Plain(new ArrayList<>()); }
                    int size() { return l.size(); }
                }
                public static class Pub {
                    private final List<String> l;
                    public Pub(List<String> l) { this.l = l; }
                    static Pub make() { return new Pub(new ArrayList<>()); }
                    int size() { return l.size(); }
                }
                static class After {
                    private final List<String> l;
                    private After(List<String> l) { this.l = l; }
                    static After make() { List<String> l = new ArrayList<>(); After a = new After(l); l.add("x"); return a; }
                    int size() { return l.size(); }
                }
                static class Before {
                    private final List<String> l;
                    private Before(List<String> l) { this.l = l; }
                    static Before make() { List<String> l = new ArrayList<>(); l.add("x"); return new Before(l); }
                    int size() { return l.size(); }
                }
                static class FromField {
                    private final List<String> l;
                    private FromField(List<String> l) { this.l = l; }
                    int size() { return l.size(); }
                }
                static class Maker {
                    private final List<String> src = new ArrayList<>();
                    FromField make() { return new FromField(src); }
                }
                static class Loop {
                    private final List<String> l;
                    private Loop(List<String> l) { this.l = l; }
                    static List<Loop> make() {
                        List<Loop> out = new ArrayList<>();
                        List<String> l = new ArrayList<>();
                        for (int i = 0; i < 3; i++) out.add(new Loop(l));
                        return out;
                    }
                    int size() { return l.size(); }
                }
                static class Delegate {
                    private final List<String> l;
                    private Delegate(List<String> l, int n) { this.l = l; }
                    private Delegate(List<String> l) { this(l, 0); }
                    static Delegate make() { return new Delegate(new ArrayList<>()); }
                    int size() { return l.size(); }
                }
                static class Ref {
                    private final List<String> l;
                    private Ref(List<String> l) { this.l = l; }
                    static Ref make() { return new Ref(new ArrayList<>()); }
                    static Function<List<String>, Ref> factory() { return Ref::new; }
                    int size() { return l.size(); }
                }
                static class Checked {
                    private final List<String> l;
                    private Checked(List<String> l) { this.l = Objects.requireNonNull(l); }
                    static Checked make() { return new Checked(new ArrayList<>()); }
                    int size() { return l.size(); }
                }
                static class Twice {
                    private final List<String> a;
                    private final List<String> b;
                    private Twice(List<String> l) { this.a = l; this.b = l; }
                    static Twice make() { return new Twice(new ArrayList<>()); }
                    int size() { return a.size() + b.size(); }
                }
                static class Str {
                    private final String s;
                    private Str(String s) { this.s = s; }
                    static Str make() { return new Str("x"); }
                    int length() { return s.length(); }
                }
                static class Lam {
                    private final List<String> l;
                    private final Runnable r;
                    private Lam(List<String> l) { this.l = l; this.r = () -> l.clear(); }
                    static Lam make() { return new Lam(new ArrayList<>()); }
                    int size() { return l.size(); }
                }
                static class View {
                    private final List<String> l;
                    private View(List<String> l) { this.l = l; }
                    static List<String> make() {
                        List<String> l = new ArrayList<>();
                        List<String> v = l.subList(0, 0);
                        View w = new View(l);
                        return v;
                    }
                    int size() { return l.size(); }
                }
                static class Sized {
                    private final List<String> l;
                    private Sized(List<String> l) { this.l = l; }
                    static Sized make() {
                        List<String> l = new ArrayList<>();
                        if (l.isEmpty()) l.add("x");
                        String first = l.get(0);
                        return new Sized(l);
                    }
                    int size() { return l.size(); }
                }
                static class T1 {
                    private final List<String> l;
                    private T1(List<String> l) { this.l = l; }
                    int size() { return l.size(); }
                }
                static class B1 {
                    private final List<String> list = new ArrayList<>();
                    B1 add(String s) { list.add(s); return this; }
                    @Finalizer T1 build() { return new T1(list); }
                }
                static class T2 {
                    private final List<String> l;
                    private T2(List<String> l) { this.l = l; }
                    int size() { return l.size(); }
                }
                static class B2 {
                    private final List<String> list = new ArrayList<>();
                    B2 add(String s) { list.add(s); return this; }
                    T2 build() { return new T2(list); }
                }
                static class T3 {
                    private final List<String> l;
                    private T3(List<String> l) { this.l = l; }
                    int size() { return l.size(); }
                }
                static class B3 {
                    private final List<String> list = new ArrayList<>();
                    B3 add(String s) { list.add(s); return this; }
                    @Finalizer T3 build() { T3 t = new T3(list); list.clear(); return t; }
                }
                static class T4 {
                    private final List<String> l;
                    private T4(List<String> l) { this.l = l; }
                    int size() { return l.size(); }
                }
                static class B4 {
                    private final List<String> list = new ArrayList<>();
                    B4 add(String s) { list.add(s); return this; }
                    List<String> peek() { return list; }
                    @Finalizer T4 build() { return new T4(list); }
                }
                static class D {
                    private final List<String> list = new ArrayList<>();
                    void add(String s) { list.add(s); }
                    @Finalizer List<String> data() { return list; }
                }
                static class Setter {
                    private List<String> l;
                    private void set(List<String> l) { this.l = l; }
                    void reset() { set(new ArrayList<>()); }
                    int size() { return l == null ? 0 : l.size(); }
                }
            }
            """;

    private static final Map<String, String> EXPECTED_OPEN = new TreeMap<>(Map.ofEntries(
            Map.entry("Plain.l", "OWNED"),
            // public: callers outside the sources may pass anything
            Map.entry("Pub.l", "NOT_OWNED"),
            Map.entry("After.l", "NOT_OWNED"),
            // before the call, l is only the receiver of a call that keeps nothing of it
            Map.entry("Before.l", "OWNED"),
            Map.entry("FromField.l", "NOT_OWNED"),
            // one list for every Loop
            Map.entry("Loop.l", "NOT_OWNED"),
            Map.entry("Delegate.l", "OWNED"),
            // Ref::new can be called with anything
            Map.entry("Ref.l", "NOT_OWNED"),
            Map.entry("Checked.l", "OWNED"),
            Map.entry("Twice.a", "NOT_OWNED"),
            Map.entry("Twice.b", "NOT_OWNED"),
            // a literal is not created for the call
            Map.entry("Str.s", "SHARED_IMMUTABLE"),
            // the lambda keeps the list too
            Map.entry("Lam.l", "NOT_OWNED"),
            Map.entry("Setter.l", "OWNED"),
            // the view v still reaches the list
            Map.entry("View.l", "NOT_OWNED"),
            // isEmpty's result is a boolean, add's is discarded, get's an element
            Map.entry("Sized.l", "OWNED"),
            // a finalizer moves the builder's list into the product
            Map.entry("T1.l", "OWNED"),
            Map.entry("B1.list", "OWNED"),
            // without @Finalizer the builder may go on using its list
            Map.entry("T2.l", "NOT_OWNED"),
            Map.entry("B2.list", "NOT_OWNED"),
            // the finalizer itself uses the list after handing it over
            Map.entry("T3.l", "NOT_OWNED"),
            Map.entry("B3.list", "NOT_OWNED"),
            // peek hands the list out: not owned, so not moved
            Map.entry("T4.l", "NOT_OWNED"),
            Map.entry("B4.list", "NOT_OWNED"),
            // fn into_data(self) -> Vec
            Map.entry("D.list", "OWNED")));

    private static final Map<String, String> EXPECTED_CLOSED = new TreeMap<>(EXPECTED_OPEN);

    static {
        EXPECTED_CLOSED.put("Pub.l", "OWNED");
    }

    @DisplayName("transfer: a parameter every caller hands over arrives fresh")
    @Test
    public void test() throws IOException {
        AnalyzerBundle bundle = buildAnalyzerBundle();
        TypeInfo x = bundle.javaInspector().parse("a.b.X", INPUT);
        List<Info> analysisOrder = bundle.prepAnalyzer().doPrimaryType(x);
        new IteratingAnalyzerImpl(bundle.javaInspector(), new IteratingAnalyzerImpl.ConfigurationBuilder()
                .setMaxIterations(10).setModificationViaReachability(true).setOwnership(true).build())
                .analyze(analysisOrder);
        assertEquals(EXPECTED_OPEN, verdicts(x, new OwnershipPass(false).go(analysisOrder), "open"));
        assertEquals(EXPECTED_CLOSED, verdicts(x, new OwnershipPass(true).go(analysisOrder), "closed"));
    }

    private static Map<String, String> verdicts(TypeInfo x, OwnershipPass.Report report, String world) {
        Map<String, String> actual = new TreeMap<>();
        for (Map.Entry<FieldInfo, OwnershipPass.Owned> e : report.owned().entrySet()) {
            FieldInfo fi = e.getKey();
            String key = fi.owner().simpleName() + "." + fi.name();
            if (!EXPECTED_OPEN.containsKey(key)) continue;
            actual.put(key, e.getValue().name());
            System.out.println("TRANSFER " + world + " " + key + " = " + e.getValue() + "  "
                               + report.notOwnedReason().getOrDefault(fi, ""));
        }
        return actual;
    }
}
