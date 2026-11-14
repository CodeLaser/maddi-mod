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
 * {@code BORROWED_PARAMETER} (docs/roadmap/ownership.md stage 2): nothing that outlives the call holds the argument's
 * object afterwards. Keys are {@code Type.method:parameter}. Several shapes keep the argument where no link shows:
 * a capture, a callee that captures, an implementation behind an interface, a receiver that leaks {@code this}.
 */
public class TestBorrowedParameter extends CommonTest {

    @Language("java")
    private static final String INPUT = """
            package a.b;
            import java.util.*;
            import java.util.function.*;
            import io.codelaser.maddi.annotation.Borrowed;

            public class X {
                private final List<List<String>> all = new ArrayList<>();
                private final List<String> list = new ArrayList<>();
                private List<String> stored;
                private String name;
                private Supplier<Integer> task;
                private static final List<Object> CACHE = new ArrayList<>();

                int read(List<String> l) { return l.size(); }
                void modify(List<String> l) { l.add("x"); }
                void addAllToField(List<String> l) { list.addAll(l); }
                String first(List<String> l) { return l.get(0); }
                int len(String s) { return s.length(); }
                int temporary(List<String> l) { return new Holder(l).size(); }
                void passOnReading(List<String> l) { read(l); }

                void store(List<String> l) { stored = l; }
                void addToField(List<String> l) { all.add(l); }
                List<String> same(List<String> l) { return l; }
                // an immutable object kept is kept: here ownership differs from independence
                void storeString(String s) { name = s; }
                void toStatic(Object o) { CACHE.add(o); }
                void putInto(Map<String, List<String>> m, List<String> l) { m.put("k", l); }
                Holder wrapped(List<String> l) { return new Holder(l); }
                void passOnKeeping(List<String> l) { store(l); }
                void aliasStore(List<String> l) { List<String> x = l; stored = x; }

                // captures: no link shows them
                Supplier<Integer> capture(List<String> l) { return () -> l.size(); }
                void aliasCapture(List<String> l) { List<String> x = l; task = () -> x.size(); }
                Supplier<Integer> methodRef(List<String> l) { return l::size; }
                Object anonymous(List<String> l) { return new Object() { public String toString() { return l.toString(); } }; }
                void capturer(List<String> l) { task = () -> l.size(); }
                void passToCapturer(List<String> l) { capturer(l); }

                // dispatch, and 'this'
                void viaIface(Registry r, List<String> l) { r.register(l); }
                int viaReader(Reader r, List<String> l) { return r.count(l); }
                void viaAcceptor(Acceptor a, List<String> l) { a.accept(l); }
                void consume(Consumer<List<String>> c, List<String> l) { c.accept(l); }
                void receiverLeaks(Leaky k) { k.leak(); }
                void viaSink(Sink s, List<String> l) { s.put(l); }

                // found by the run-time check on gson (rust part 2e)
                Object anonSuper(List<String> l) { return new Base(l) { }; }
                void checked(List<String> l) { stored = Objects.requireNonNull(l); }
                void conditional(List<String> l) { stored = l == null ? new ArrayList<>() : l; }
                private final Node root = new Node();
                Node find() { return root; }
                void intoNode(List<String> l) { Node n = find(); n.value = l; }
                int localNew(List<String> l) { Node n = new Node(); n.value = l; return n.value.size(); }
                Supplier<Holder> holderCaptured(List<String> l) { Holder h = new Holder(l); return () -> h; }
                // a view of the parameter returned keeps it (rv.§m ≡ 0:m.§m, the hints' dependentReturnValue)
                Set<String> view(Map<String, Boolean> m) { return Collections.newSetFromMap(m); }
                List<String> sync(List<String> l) { return Collections.synchronizedList(l); }
                int viewDiscarded(List<String> l) { return Collections.synchronizedList(l).size(); }
                static class Node { List<String> value; }
                static class TreeMap2<K, V> {
                    static final class N<K, V> { final K key; V value; N<K, V> left; N(K key) { this.key = key; } }
                    private N<K, V> root;
                    N<K, V> find(K key, boolean create) {
                        N<K, V> n = root;
                        while (n != null) { if (n.key.equals(key)) return n; n = n.left; }
                        if (!create) return null;
                        N<K, V> created = new N<>(key);
                        created.left = root;
                        root = created;
                        return created;
                    }
                    public V put(K key, V value) {
                        N<K, V> created = find(key, true);
                        V result = created.value;
                        created.value = value;
                        return result;
                    }
                }
                static class Json {
                    static final Json NULL = new Json();
                    private final TreeMap2<String, Json> members = new TreeMap2<>();
                    public void add(String property, Json value) { members.put(property, value == null ? NULL : value); }
                }
                interface Strategy { String name(String s); }
                static class Builder {
                    private Strategy strategy;
                    public Builder set(Strategy s) { this.strategy = Objects.requireNonNull(s); return this; }
                }
                static class Pair {
                    private final List<String> a;
                    private final List<String> b;
                    Pair(List<String> a, List<String> b, boolean x) { this.a = a; this.b = b; }
                    Pair(List<String> b) { this(new ArrayList<>(), b, true); }
                }
                static class Base { protected final List<String> base; Base(List<String> base) { this.base = base; } }
                static class Sub extends Base { Sub(List<String> s) { super(s); } }
                static class Holder { private final List<String> held; Holder(List<String> held) { this.held = held; } int size() { return held.size(); } }
                interface Registry { void register(List<String> l); }
                static class Keeper implements Registry { private final List<List<String>> kept = new ArrayList<>(); public void register(List<String> l) { kept.add(l); } }
                interface Reader { int count(List<String> l); }
                static class CountReader implements Reader { public int count(List<String> l) { return l.size(); } }
                static class Acceptor { void accept(List<String> l) { } }
                static class KeepingAcceptor extends Acceptor { private List<String> kept; @Override void accept(List<String> l) { kept = l; } }
                static class Collector implements Consumer<List<String>> { private final List<List<String>> got = new ArrayList<>(); public void accept(List<String> l) { got.add(l); } }
                static class Leaky { static final List<Leaky> ALL = new ArrayList<>(); void leak() { ALL.add(this); } }
                interface Sink { void put(@Borrowed List<String> l); }
                static class KeepingSink implements Sink { private List<String> kept; public void put(List<String> l) { kept = l; } }
            }
            """;

    private static final Map<String, Boolean> EXPECTED = new TreeMap<>(Map.ofEntries(
            Map.entry("X.read:l", true),
            Map.entry("X.modify:l", true),
            // the elements are kept, the list is not
            Map.entry("X.addAllToField:l", true),
            Map.entry("X.first:l", true),
            Map.entry("X.len:s", true),
            // a temporary holder dies with the call
            Map.entry("X.temporary:l", true),
            Map.entry("X.passOnReading:l", true),

            Map.entry("X.store:l", false),
            Map.entry("X.addToField:l", false),
            Map.entry("X.same:l", false),
            Map.entry("X.storeString:s", false),
            Map.entry("X.toStatic:o", false),
            Map.entry("X.putInto:m", true),
            Map.entry("X.putInto:l", false),
            Map.entry("X.wrapped:l", false),
            Map.entry("Holder.<init>:held", false),
            Map.entry("X.passOnKeeping:l", false),
            Map.entry("X.aliasStore:l", false),

            Map.entry("X.capture:l", false),
            Map.entry("X.aliasCapture:l", false),
            Map.entry("X.methodRef:l", false),
            Map.entry("X.anonymous:l", false),
            Map.entry("X.capturer:l", false),
            // the callee's capture is in no summary
            Map.entry("X.passToCapturer:l", false),

            // Keeper keeps it; the abstract method's summary only relates the contents
            Map.entry("Registry.register:l", false),
            Map.entry("Keeper.register:l", false),
            Map.entry("X.viaIface:r", true),
            Map.entry("X.viaIface:l", false),
            Map.entry("Reader.count:l", true),
            Map.entry("CountReader.count:l", true),
            Map.entry("X.viaReader:l", true),
            // Acceptor.accept borrows, its override does not
            Map.entry("Acceptor.accept:l", false),
            Map.entry("KeepingAcceptor.accept:l", false),
            Map.entry("X.viaAcceptor:l", false),
            // a library interface implemented in the sources
            Map.entry("X.consume:c", true),
            Map.entry("X.consume:l", false),
            Map.entry("X.receiverLeaks:k", false),
            // a contract decides a bodiless method, and is not second-guessed (KeepingSink breaks it: the guard's job)
            Map.entry("Sink.put:l", true),
            Map.entry("X.viaSink:l", true),
            // this(..) and super(..): the callee's links are not the caller's
            Map.entry("Pair.<init>:b", false),
            Map.entry("Sub.<init>:s", false),
            // an anonymous class's super(..) arguments
            Map.entry("X.anonSuper:l", false),
            // stored through an identity method, a conditional, into a field of an object found elsewhere
            Map.entry("X.checked:l", false),
            Map.entry("X.conditional:l", false),
            Map.entry("X.intoNode:l", false),
            // into a new object that dies with the call
            Map.entry("X.localNew:l", true),
            // a local holding an object that keeps it, captured
            Map.entry("X.holderCaptured:l", false),
            Map.entry("TreeMap2.put:value", false),
            Map.entry("X.view:m", false),
            Map.entry("X.sync:l", false),
            Map.entry("X.viewDiscarded:l", true),
            Map.entry("Json.add:value", false),
            Map.entry("Builder.set:s", false)));

    @DisplayName("BORROWED_PARAMETER: nothing that outlives the call holds the argument")
    @Test
    public void test() throws IOException {
        AnalyzerBundle bundle = buildAnalyzerBundle();
        TypeInfo x = bundle.javaInspector().parse("a.b.X", INPUT);
        List<Info> analysisOrder = bundle.prepAnalyzer().doPrimaryType(x);
        new IteratingAnalyzerImpl(bundle.javaInspector(), new IteratingAnalyzerImpl.ConfigurationBuilder()
                .setMaxIterations(10).setModificationViaReachability(true).setOwnership(true).build())
                .analyze(analysisOrder);
        OwnershipPass.Report report = new OwnershipPass().go(analysisOrder);
        Map<String, Boolean> actual = new TreeMap<>();
        x.recursiveSubTypeStream().flatMap(TypeInfo::constructorAndMethodStream).flatMap(mi -> mi.parameters().stream())
                .forEach(pi -> {
                    String key = pi.methodInfo().typeInfo().simpleName() + "." + pi.methodInfo().name() + ":" + pi.name();
                    if (EXPECTED.containsKey(key)) {
                        actual.put(key, pi.analysis().getOrNull(PropertyImpl.BORROWED_PARAMETER,
                                ValueImpl.BoolImpl.class) instanceof ValueImpl.BoolImpl b && b.isTrue());
                        System.out.println("BORROWED " + key + " = " + actual.get(key) + "  "
                                           + report.notBorrowedReason().getOrDefault(pi, ""));
                    }
                });
        assertEquals(EXPECTED, actual);
    }
}
