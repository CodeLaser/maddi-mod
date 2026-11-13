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
import io.codelaser.maddi.cst.api.info.MethodInfo;
import io.codelaser.maddi.cst.api.info.TypeInfo;
import io.codelaser.maddi.cst.impl.analysis.PropertyImpl;
import io.codelaser.maddi.modification.analyzer.CommonTest;
import io.codelaser.maddi.modification.analyzer.IteratingAnalyzer;
import io.codelaser.maddi.modification.analyzer.impl.IteratingAnalyzerImpl;

/**
 * {@code FRESH_METHOD} (docs/roadmap/ownership.md stage 1): the result is an object created during the call, or
 * null, and held by nothing outside the call. Each case says why. Every part needs positive evidence, never the
 * absence of links: "created" is read per return path from the body, "held nowhere else" from the result's links.
 */
public class TestFreshMethod extends CommonTest {

    @Language("java")
    private static final String INPUT = """
            package a.b;
            import java.util.*;
            import java.util.function.Consumer;
            import io.codelaser.maddi.annotation.Fresh;
import java.util.function.Supplier;

            public class X {
                private final List<String> list = new ArrayList<>();
                private final List<List<String>> all = new ArrayList<>();
                private List<String> stored;
                private static final List<String> CONSTANT = new ArrayList<>();

                List<String> fresh() { return new ArrayList<>(); }
                Object[] array() { return new Object[3]; }
                Holder build() { return new Holder(list); }
                List<String> delegate() { return fresh(); }
                List<String> localModified() { List<String> r = new ArrayList<>(); r.add("a"); return r; }
                List<String> addedToLocal() { List<String> r = new ArrayList<>(); List<List<String>> l = new ArrayList<>(); l.add(r); return r; }
                Object nullOrNew(boolean b) { return b ? null : new Object(); }
                List<String> loop(List<String> in) { List<String> r = null; for (String s : in) { r = new ArrayList<>(); } return r; }
                List<String> recurse(int n) { return n == 0 ? new ArrayList<>() : recurse(n - 1); }
                List<String> fromHolder() { Holder h = new Holder(new ArrayList<>()); return h.held(); }

                List<String> storedToo() { List<String> r = new ArrayList<>(); stored = r; return r; }
                List<String> addedToParameter(List<List<String>> sink) { List<String> r = new ArrayList<>(); sink.add(r); return r; }
                List<String> addedToField() { List<String> r = new ArrayList<>(); all.add(r); return r; }
                List<String> paramOrNew(boolean b, List<String> l) { return b ? l : new ArrayList<>(); }
                List<String> getter() { return list; }
                List<String> constant() { return CONSTANT; }
                String literal() { return "abc"; }
                Supplier<List<String>> lambda() { return () -> new ArrayList<>(); }

                // no links at all: absence is not evidence (emptyList and List.of return singletons)
                List<String> emptyList() { return Collections.emptyList(); }
                List<String> listOf() { return List.of(); }
                List<String> copyOf(List<String> l) { return List.copyOf(l); }
                String str(int i) { return Integer.toString(i); }
                // library contracts (@Fresh in the JDK hints)
                String[] copied(String[] a) { return Arrays.copyOf(a, a.length); }
                Object[] toArray(List<String> l) { return l.toArray(); }
                char[] chars(String s) { return s.toCharArray(); }
                // one path is new, the other has no links at all: the marker of the first does not speak for the second
                List<String> mixed(boolean b) { return b ? new ArrayList<>() : Collections.emptyList(); }
                List<String> mixedReturns(boolean b) { if (b) return new ArrayList<>(); return List.of(); }
                List<String> mixedLocal(boolean b) { List<String> r = new ArrayList<>(); if (b) r = Collections.emptyList(); return r; }

                // dispatch
                List<String> viaBase(Base base) { return base.make(); }
                List<String> viaFinal(Final f) { return f.make(); }
                List<String> viaMaker(Maker m) { return m.make(); }
                List<String> viaShape(Shape s) { return s.make(); }
                List<String> registered(Registry reg) { List<String> r = new ArrayList<>(); reg.register(r); return r; }
                List<String> handedToBase(Acceptor a) { List<String> r = new ArrayList<>(); a.accept(r); return r; }
                // the object registers itself: no link says so, the summaries have no links for 'this'
                Leaky leaked() { Leaky k = new Leaky(); k.leak(); return k; }
                Listener listening(Bus bus) { return new Listener(bus); }
                Capturing capturing(Bus bus) { return new Capturing(bus); }
                List<String> viaFactory(Factory f) { return f.create(); }
                List<String> viaNotFresh(NotFresh n) { return n.create(); }
                List<String> consumed(Consumer<List<String>> c) { List<String> r = new ArrayList<>(); c.accept(r); return r; }

                static class Holder {
                    private final List<String> held;
                    Holder(List<String> held) { this.held = held; }
                    List<String> held() { return held; }
                }
                static class Base { List<String> make() { return new ArrayList<>(); } }
                static class Sub extends Base { private final List<String> mine = new ArrayList<>(); @Override List<String> make() { return mine; } }
                static final class Final { List<String> make() { return new ArrayList<>(); } }
                interface Maker { List<String> make(); }
                interface Shape { List<String> make(); }
                static class Circle implements Shape { public List<String> make() { return new ArrayList<>(); } }
                static class Square implements Shape { public List<String> make() { return new LinkedList<>(); } }
                interface Registry { void register(List<String> l); }
                static class Keeper implements Registry { private final List<List<String>> kept = new ArrayList<>(); public void register(List<String> l) { kept.add(l); } }
                static class Acceptor { void accept(List<String> l) { } }
                static class Collector implements Consumer<List<String>> { private final List<List<String>> got = new ArrayList<>(); public void accept(List<String> l) { got.add(l); } }
                static class Leaky { static final List<Leaky> ALL = new ArrayList<>(); void leak() { ALL.add(this); } }
                static class Bus { private final List<Object> listeners = new ArrayList<>(); void register(Object o) { listeners.add(o); } }
                static class Listener { Listener(Bus bus) { bus.register(this); } }
                static class Capturing { private int count; Capturing(Bus bus) { bus.register((Runnable) () -> count++); } }
                interface Factory { @Fresh List<String> create(); }
                static class Pool implements Factory { private final List<String> one = new ArrayList<>(); public List<String> create() { return one; } }
                interface NotFresh { @Fresh(absent = true) List<String> create(); }
                static class Maker2 implements NotFresh { public List<String> create() { return new ArrayList<>(); } }
                static class KeepingAcceptor extends Acceptor { private List<String> kept; @Override void accept(List<String> l) { kept = l; } }
            }
            """;

    private static final Map<String, Boolean> EXPECTED = new TreeMap<>(Map.ofEntries(
            Map.entry("X.fresh", true),
            Map.entry("X.array", true),
            // a new object that holds the receiver's list: the object itself is new
            Map.entry("X.build", true),
            Map.entry("X.delegate", true),
            Map.entry("X.localModified", true),
            // the local list holding it dies with the call
            Map.entry("X.addedToLocal", true),
            Map.entry("X.nullOrNew", true),
            Map.entry("X.loop", true),
            Map.entry("X.recurse", true),
            // the list is new and only a local holder holds it, but the path returns a getter's result: per-path
            // evidence cannot see through it (a loss, not an error)
            Map.entry("X.fromHolder", false),

            Map.entry("X.storedToo", false),
            Map.entry("X.addedToParameter", false),
            Map.entry("X.addedToField", false),
            Map.entry("X.paramOrNew", false),
            Map.entry("X.getter", false),
            Map.entry("X.constant", false),
            Map.entry("X.literal", false),
            Map.entry("X.lambda", false),

            Map.entry("X.emptyList", false),
            Map.entry("X.listOf", false),
            Map.entry("X.copyOf", false),
            Map.entry("X.str", false),
            Map.entry("X.copied", true),
            Map.entry("X.toArray", true),
            Map.entry("X.chars", true),
            Map.entry("X.mixed", false),
            Map.entry("X.mixedReturns", false),
            Map.entry("X.mixedLocal", false),

            // Base.make's body is fresh, but a call may dispatch to Sub.make, which returns a field
            Map.entry("X.viaBase", false),
            Map.entry("Base.make", false),
            Map.entry("Sub.make", false),
            Map.entry("X.viaFinal", true),
            Map.entry("Final.make", true),
            // no implementation in the sources
            Map.entry("Maker.make", false),
            Map.entry("X.viaMaker", false),
            // every implementation is fresh
            Map.entry("Shape.make", true),
            Map.entry("Circle.make", true),
            Map.entry("Square.make", true),
            // through the abstract method, whose implementations are all fresh
            Map.entry("X.viaShape", true),
            // an abstract method's summary relates the argument's contents to the receiver, not the argument itself
            // ('0:l.§m ≡ this.§m'): Keeper keeps the argument
            Map.entry("X.registered", false),
            // Acceptor.accept keeps nothing, KeepingAcceptor.accept does
            Map.entry("X.handedToBase", false),
            // a library interface with an implementation in the sources, which keeps the argument
            Map.entry("X.consumed", false),
            Map.entry("X.leaked", false),
            Map.entry("X.listening", false),
            Map.entry("X.capturing", false),
            // contracts decide a bodiless method, and are not second-guessed here (Pool breaks it: the guard's job)
            Map.entry("Factory.create", true),
            Map.entry("X.viaFactory", true),
            Map.entry("NotFresh.create", false),
            Map.entry("X.viaNotFresh", false)));

    @DisplayName("FRESH_METHOD: new or null, and held by nothing outside the call")
    @Test
    public void test() throws IOException {
        AnalyzerBundle bundle = buildAnalyzerBundle();
        TypeInfo x = bundle.javaInspector().parse("a.b.X", INPUT);
        List<Info> analysisOrder = bundle.prepAnalyzer().doPrimaryType(x);
        IteratingAnalyzer analyzer = new IteratingAnalyzerImpl(bundle.javaInspector(),
                new IteratingAnalyzerImpl.ConfigurationBuilder().setMaxIterations(10)
                        .setModificationViaReachability(true).setOwnership(true).build());
        analyzer.analyze(analysisOrder);
        OwnershipPass.Report report = new OwnershipPass().go(analysisOrder);
        Map<String, Boolean> actual = new TreeMap<>();
        x.recursiveSubTypeStream().flatMap(TypeInfo::methodStream).forEach(mi -> {
            String key = mi.typeInfo().simpleName() + "." + mi.name();
            if (EXPECTED.containsKey(key)) {
                actual.put(key, mi.analysis().getOrNull(PropertyImpl.FRESH_METHOD,
                        io.codelaser.maddi.cst.impl.analysis.ValueImpl.BoolImpl.class) instanceof
                        io.codelaser.maddi.cst.impl.analysis.ValueImpl.BoolImpl b && b.isTrue());
                System.out.println("FRESH " + key + " = " + actual.get(key) + "  "
                                   + report.notFreshReason().getOrDefault(mi, ""));
            }
        });
        assertEquals(EXPECTED, actual);
    }
}
