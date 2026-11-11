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

package io.codelaser.maddi.modification.analyzer.modification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.intellij.lang.annotations.Language;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.codelaser.maddi.cst.api.info.Info;
import io.codelaser.maddi.cst.api.info.MethodInfo;
import io.codelaser.maddi.cst.api.info.TypeInfo;
import io.codelaser.maddi.cst.impl.analysis.PropertyImpl;
import io.codelaser.maddi.cst.impl.analysis.ValueImpl;
import io.codelaser.maddi.modification.analyzer.CommonTest;
import io.codelaser.maddi.modification.analyzer.impl.GuardAnalyzerImpl;
import io.codelaser.maddi.modification.analyzer.impl.IteratingAnalyzerImpl;

/**
 * The OPTIMISTIC shapes of the Eclipse Collections engine work list (maddi-aapi-archive
 * .../libs/eclipsecollections/ENGINE-WORKLIST.md, O1-O4): each is a method that modifies its receiver and is
 * computed {@code @NotModified}, reduced from the EC source it was found in. Each test asserts the verdict a CLIENT
 * sees, under the production configuration (modification via reachability on: every fixture that leaves it off,
 * which is the default, never saw O1).
 * <p>
 * O1 was ShadowModificationPass's receiver-projection cache, keyed by expression only while a lambda body is walked
 * twice (as its own method and inside its enclosing method): the enclosing method lost its edge and the MODREACH
 * cutover wrote {@code list.forEach(x -> this.items.add(x))} non-modifying.
 */
public class TestOptimisticModificationShapes extends CommonTest {

    private void analyze(TypeInfo typeInfo) {
        List<Info> ao = prepWork(typeInfo);
        new IteratingAnalyzerImpl(javaInspector, new IteratingAnalyzerImpl.ConfigurationBuilder()
                .setMaxIterations(10)
                .setModificationViaReachability(true)
                .build()).analyze(ao);
    }

    private void analyze(TypeInfo typeInfo, boolean modReach) {
        List<Info> ao = prepWork(typeInfo);
        new IteratingAnalyzerImpl(javaInspector, new IteratingAnalyzerImpl.ConfigurationBuilder()
                .setMaxIterations(10)
                .setModificationViaReachability(modReach)
                .build()).analyze(ao);
    }

    private static MethodInfo method(TypeInfo typeInfo, String name) {
        return typeInfo.methods().stream().filter(m -> m.name().equals(name)).findFirst().orElseThrow();
    }

    /*
     O2. EC's MutableByteList.sortThis(ByteComparator) is a default method that only throws; ByteArrayList overrides
     it and sorts. A call through the interface reaches the override, so the interface method, and every caller
     through it, modifies.
     */
    @Language("java")
    private static final String O2 = """
            package a.b;
            class O2 {
                interface Sortable {
                    default void sortThis() { throw new UnsupportedOperationException("not supported"); }
                }
                static class Counter implements Sortable {
                    private int sorts;
                    @Override public void sortThis() { sorts++; }
                    int sorts() { return sorts; }
                }
                static void sortIt(Sortable s) { s.sortThis(); }
            }
            """;

    @DisplayName("O2: a throwing default method overridden by a modifying implementation modifies")
    @Test
    public void o2() {
        TypeInfo X = javaInspector.parse("a.b.O2", O2);
        analyze(X);
        MethodInfo sortIt = method(X, "sortIt");
        assertTrue(!sortIt.parameters().getFirst().isUnmodified(),
                "the argument of sortIt(s) reaches Counter.sortThis(), which modifies it");
    }

    /*
     O2, option D: a default method with a REAL, non-modifying body overridden by a modifying method keeps its own
     verdict (no dispatch union, by design); the guard reports it instead.
     */
    @Language("java")
    private static final String O2_D = """
            package a.b;
            class O2d {
                interface Closeable2 {
                    default void close() { }
                }
                static class Counter implements Closeable2 {
                    private int closes;
                    @Override public void close() { closes++; }
                }
                static void closeIt(Closeable2 c) { c.close(); }
            }
            """;

    @DisplayName("O2, option 2 (DISPATCHUNION): an overridden concrete method takes the union with its overrides")
    @Test
    public void o2dispatchUnion() {
        boolean before = io.codelaser.maddi.modification.prepwork.Util.DISPATCH_UNION;
        io.codelaser.maddi.modification.prepwork.Util.DISPATCH_UNION = true;
        try {
            TypeInfo X = javaInspector.parse("a.b.O2d", O2_D);
            List<Info> ao = prepWork(X);
            var iterating = new IteratingAnalyzerImpl(javaInspector, new IteratingAnalyzerImpl.ConfigurationBuilder()
                    .setMaxIterations(10)
                    .setModificationViaReachability(true)
                    .build());
            iterating.analyze(ao);
            MethodInfo close = method(X.findSubType("Closeable2"), "close");
            assertFalse(close.isNonModifying(), "a call through the default may reach Counter.close, which modifies");
            MethodInfo closeIt = method(X, "closeIt");
            assertFalse(closeIt.parameters().getFirst().isUnmodified(), "closeIt(c) modifies c through close()");
            assertTrue(iterating.messages().stream().noneMatch(m -> m.message().contains("O2d.Counter.close")
                            && GuardAnalyzerImpl.OVERRIDE_WEAKENS_COMPUTED.equals(m.category())),
                    "nothing weakens the union");
        } finally {
            io.codelaser.maddi.modification.prepwork.Util.DISPATCH_UNION = before;
        }
    }

    private static final String O2_CYCLE = """
            package a.b;
            public class O2c {
                public static class VT {
                    private final int dim;
                    private final java.util.List<VT> arguments = new java.util.ArrayList<>();
                    public VT(int dim) { this.dim = dim; }
                    public int dim() { return dim; }
                    public java.util.List<VT> arguments() { return arguments; }
                    public VT resize(int d) { VT t = new VT(d); t.arguments.addAll(arguments); return t; }
                }
                public static class Ex {
                    public void infer(VT upper) { }
                }
                public static class Assign extends Ex {
                    private Ex left;
                    @Override public void infer(VT upper) { left.infer(upper); }
                }
                public static class Func extends Ex {
                    private boolean cast;
                    private final java.util.List<Ex> operands = new java.util.ArrayList<>();
                    @Override public void infer(VT upper) {
                        cast = true;
                        operands.get(0).infer(upper);
                        if (upper != null && upper.dim() > 0) { upper = upper.resize(0); }
                    }
                }
                public static class Invocation extends Ex {
                    private Ex instance;
                    private final java.util.List<VT> genericArgs = new java.util.ArrayList<>();
                    @Override public void infer(VT upper) {
                        genericArgs.clear();
                        if (instance != null) { instance.infer(upper); }
                    }
                }
                public static class New extends Ex {
                    private VT inferred;
                    @Override public void infer(VT upper) { inferred = gather(upper); }
                    private static VT gather(VT upper) {
                        return upper == null || upper.arguments().isEmpty() ? null : upper.arguments().get(0);
                    }
                }
                public static void run(Ex ex, VT t) { ex.infer(t); }
            }
            """;

    @DisplayName("O2, option 2 (DISPATCHUNION): a parameter passed round the dispatch cycle stays unmodified")
    @Test
    public void o2dispatchUnionCycle() {
        boolean before = io.codelaser.maddi.modification.prepwork.Util.DISPATCH_UNION;
        io.codelaser.maddi.modification.prepwork.Util.DISPATCH_UNION = true;
        try {
            TypeInfo X = javaInspector.parse("a.b.O2c", O2_CYCLE);
            List<Info> ao = prepWork(X);
            var iterating = new IteratingAnalyzerImpl(javaInspector, new IteratingAnalyzerImpl.ConfigurationBuilder()
                    .setMaxIterations(10)
                    .setModificationViaReachability(true)
                    .build());
            iterating.analyze(ao);
            MethodInfo exInfer = method(X.findSubType("Ex"), "infer");
            MethodInfo assignInfer = method(X.findSubType("Assign"), "infer");
            MethodInfo funcInfer = method(X.findSubType("Func"), "infer");
            assertFalse(exInfer.isNonModifying(), "a call through Ex.infer may reach Func.infer, which modifies");
            assertTrue(funcInfer.parameters().getFirst().isUnmodified(), "Func.infer only reads upper");
            assertTrue(assignInfer.parameters().getFirst().isUnmodified(), "Assign.infer only passes upper on");
            assertTrue(exInfer.parameters().getFirst().isUnmodified(), "no override modifies upper");
        } finally {
            io.codelaser.maddi.modification.prepwork.Util.DISPATCH_UNION = before;
        }
    }

    @DisplayName("O2-D: a real default body keeps its verdict; the weakening override is reported")
    @Test
    public void o2d() {
        TypeInfo X = javaInspector.parse("a.b.O2d", O2_D);
        List<Info> ao = prepWork(X);
        var iterating = new IteratingAnalyzerImpl(javaInspector, new IteratingAnalyzerImpl.ConfigurationBuilder()
                .setMaxIterations(10)
                .setModificationViaReachability(true)
                .build());
        iterating.analyze(ao);
        MethodInfo close = method(X.findSubType("Closeable2"), "close");
        assertTrue(close.isNonModifying(), "the default's own body decides its verdict");
        assertTrue(iterating.messages().stream().anyMatch(m -> m.message().contains("O2d.Counter.close")
                        && GuardAnalyzerImpl.OVERRIDE_WEAKENS_COMPUTED
                        .equals(m.category())),
                "the override that modifies is reported: " + iterating.messages());
    }

    /*
     O1. EC's <P>ObjectHashMap.putAll: map.forEachKeyValue((k, v) -> Outer.this.put(k, v)).
     */
    @Language("java")
    private static final String O1 = """
            package a.b;
            import java.util.ArrayList;
            import java.util.List;
            import java.util.function.Consumer;
            class O1 {
                interface Source { void each(Consumer<String> consumer); }
                private final List<String> items = new ArrayList<>();
                void add(String s) { items.add(s); }
                void addAll(Source source) { source.each(s -> O1.this.add(s)); }
                int size() { return items.size(); }
            }
            """;

    @DisplayName("O1: a lambda calling a modifying method on the captured outer instance modifies the receiver")
    @Test
    public void o1() {
        TypeInfo X = javaInspector.parse("a.b.O1", O1);
        analyze(X);
        assertTrue(method(X, "add").isModifying(), "add modifies items");
        assertTrue(method(X, "addAll").isModifying(), "addAll adds through the lambda");
    }

    /*
     O1, with an implementation of Source that calls its consumer (EC's forEachKeyValue has many).
     */
    @Language("java")
    private static final String O1_IMPL = """
            package a.b;
            import java.util.ArrayList;
            import java.util.List;
            import java.util.function.Consumer;
            class O1b {
                interface Source { void each(Consumer<String> consumer); }
                static class ListSource implements Source {
                    private final List<String> list = new ArrayList<>();
                    @Override public void each(Consumer<String> consumer) { for (String s : list) consumer.accept(s); }
                }
                private final List<String> items = new ArrayList<>();
                void add(String s) { items.add(s); }
                void addAll(Source source) { source.each(s -> O1b.this.add(s)); }
            }
            """;

    @DisplayName("O1b: as O1, with an implementation of Source that calls the consumer")
    @Test
    public void o1b() {
        TypeInfo X = javaInspector.parse("a.b.O1b", O1_IMPL);
        analyze(X);
        assertTrue(method(X, "addAll").isModifying(), "addAll adds through the lambda");
    }

    /*
     O1, through the JDK: List.forEach, whose behaviour comes from the JDK analysis hints.
     */
    @Language("java")
    private static final String O1_JDK = """
            package a.b;
            import java.util.ArrayList;
            import java.util.List;
            class O1c {
                private final List<String> items = new ArrayList<>();
                void add(String s) { items.add(s); }
                void addAll(List<String> source) { source.forEach(s -> O1c.this.add(s)); }
                void addAllRef(List<String> source) { source.forEach(this::add); }
                void addAllThis(List<String> source) { source.forEach(s -> this.add(s)); }
                void addAllField(List<String> source) { source.forEach(s -> items.add(s)); }
            }
            """;

    @DisplayName("O1c: as O1, through java.util.List.forEach")
    @Test
    public void o1c() {
        TypeInfo X = javaInspector.parse("a.b.O1c", O1_JDK);
        analyze(X);
        String verdicts = Stream.of("add", "addAll", "addAllRef", "addAllThis", "addAllField")
                .map(m -> m + "=" + method(X, m).isModifying()).collect(Collectors.joining(" "));
        System.out.println("### O1c " + verdicts);
        assertTrue(method(X, "addAllField").isModifying(), "lambda modifying a field: " + verdicts);
        assertTrue(method(X, "addAllThis").isModifying(), "lambda calling this.add: " + verdicts);
        assertTrue(method(X, "addAllRef").isModifying(), "this::add: " + verdicts);
        assertTrue(method(X, "addAll").isModifying(), "lambda calling O1c.this.add (EC's shape): " + verdicts);
    }

    /*
     O3 (GREEN: does not reproduce the EC finding in this reduction -- EC's remove binds to its own interfaces).
     EC's AbstractUnifiedSet.removeAllIterable: for (Object each : iterable) changed |= this.remove(each); in an
     abstract class that inherits remove from java.util.Set.
     */
    @Language("java")
    private static final String O3 = """
            package a.b;
            import java.util.AbstractSet;
            abstract class O3<T> extends AbstractSet<T> {
                public boolean removeAllIterable(Iterable<?> iterable) {
                    boolean changed = false;
                    for (Object each : iterable) { changed |= this.remove(each); }
                    return changed;
                }
            }
            """;

    @DisplayName("O3: this.remove(x) in a for-each over an argument modifies the receiver")
    @Test
    public void o3() {
        TypeInfo X = javaInspector.parse("a.b.O3", O3);
        analyze(X);
        assertTrue(method(X, "removeAllIterable").isModifying(), "removeAllIterable calls this.remove");
    }

    /*
     O4. EC's MutableBooleanCollection.removeIf: a default method removing through an iterator obtained from this.
     Two causes. java.lang.Iterable's hint was @ImmutableContainer (no §m, so the iterator's except="remove" link
     it.§m ☷ this.§m was never made): back to @Container. With the link made, the fixpoint read @Modified and the
     MODREACH cutover overwrote it: ShadowModificationPass now follows a ☷ link whose pass set names the called
     method. EC's own shape -- AbstractMutableCollection.remove through EC's
     abstract iterator() -- is a different mechanism (the implementation union of iterator()'s independence).
     */
    @Language("java")
    private static final String O4 = """
            package a.b;
            import java.util.Iterator;
            import java.util.function.Predicate;
            interface O4<T> extends Iterable<T> {
                default boolean removeIf2(Predicate<? super T> predicate) {
                    boolean changed = false;
                    Iterator<T> iterator = this.iterator();
                    while (iterator.hasNext()) {
                        if (predicate.test(iterator.next())) { iterator.remove(); changed = true; }
                    }
                    return changed;
                }
            }
            """;

    @DisplayName("O4: removing through an iterator obtained from this modifies the receiver")
    @Test
    public void o4() {
        TypeInfo X = javaInspector.parse("a.b.O4", O4);
        analyze(X);
        assertTrue(method(X, "removeIf2").isModifying(), "iterator.remove() modifies the collection it came from");
    }

    /*
     O4, EC's own shape: Mutable<P>Collection.removeIf, a default method removing through the collection's OWN abstract
     iterator method (no hint states its except="remove"). The implementation returns an inner-class iterator whose
     remove() modifies the outer instance and whose next() only moves its own cursor. Four engine steps make it work:
     'new Iter()' links 'oc.§m ☷{remove} this.§m' (ExpressionVisitor), the implementation is computed
     @Independent(except = remove) and the abstract union keeps it, the abstract method's shallow links carry the ☷,
     and statement-level links are no longer frozen at the method's first link computation (VariableInfoImpl).
     sum() iterates with next() only and must stay non-modifying: a plain ≡ would have charged next() to the
     collection.
     */
    @Language("java")
    private static final String O4_EC = """
            package a.b;
            import java.util.function.IntPredicate;
            class O4ec {
                interface IntIter { boolean hasNext(); int next(); void remove(); }
                interface IntColl {
                    boolean add(int x);
                    IntIter intIterator();
                    default boolean removeIf(IntPredicate predicate) {
                        boolean changed = false;
                        IntIter iterator = this.intIterator();
                        while (iterator.hasNext()) {
                            if (predicate.test(iterator.next())) { iterator.remove(); changed = true; }
                        }
                        return changed;
                    }
                    default int sum() {
                        int s = 0;
                        IntIter iterator = this.intIterator();
                        while (iterator.hasNext()) { s += iterator.next(); }
                        return s;
                    }
                }
                static class IntArrayColl implements IntColl {
                    private int[] items = new int[10];
                    private int size;
                    void removeAt(int index) {
                        System.arraycopy(items, index + 1, items, index, size - index - 1);
                        size--;
                    }
                    @Override public boolean add(int x) { items[size++] = x; return true; }
                    @Override public IntIter intIterator() { return new Iter(); }
                    class Iter implements IntIter {
                        private int cursor;
                        @Override public boolean hasNext() { return cursor < size; }
                        @Override public int next() { return items[cursor++]; }
                        @Override public void remove() { REMOVE }
                    }
                }
            }
            """;

    private TypeInfo parseO4ec(String name, String removeBody, boolean modReach) {
        TypeInfo X = javaInspector.parse("a.b." + name, O4_EC.replace("O4ec", name).replace("REMOVE", removeBody));
        analyze(X, modReach);
        return X;
    }

    @DisplayName("O4-EC: removing through the collection's own abstract iterator method modifies the receiver")
    @Test
    public void o4ec() {
        for (boolean modReach : new boolean[]{false, true}) {
            TypeInfo X = parseO4ec(modReach ? "O4ecR" : "O4ec", "IntArrayColl.this.removeAt(--cursor);", modReach);
            TypeInfo coll = X.findSubType("IntColl");
            String where = "modReach=" + modReach;
            assertTrue(method(coll, "removeIf").isModifying(), "removeIf, " + where);
            assertTrue(method(coll, "sum").isNonModifying(), "sum only calls next(), " + where);
            assertEquals("@Independent(except={\"remove\"})", method(coll, "intIterator").analysis()
                    .getOrNull(PropertyImpl.INDEPENDENT_METHOD,
                            ValueImpl.IndependentImpl.class).toString(), where);
        }
    }

    @DisplayName("O4-EC veto: an iterator whose remove() does not reach the collection leaves removeIf non-modifying")
    @Test
    public void o4ecVeto() {
        for (boolean modReach : new boolean[]{false, true}) {
            TypeInfo X = parseO4ec(modReach ? "O4ecVR" : "O4ecV", "throw new UnsupportedOperationException();",
                    modReach);
            TypeInfo coll = X.findSubType("IntColl");
            assertTrue(method(coll, "removeIf").isNonModifying(), "removeIf, modReach=" + modReach);
        }
    }

    /*
     CodeLaser/maddi-mod#42, guava's ForwardingTable: a forwarding method calls an ANALYZED interface method on the
     result of its accessor, 'delegate().clear()'. The accessor's summary carries the receiver on its modification
     face, 'delegate.§m ≡ this.§m'; the R2 rule (#24, only the whole result for an analyzed outer callee) dropped that
     link, so the MODREACH cutover wrote clear() non-modifying. With a library interface (ForwardingCollection's
     Collection.clear) R2 does not apply and the same link was followed.
     */
    @Language("java")
    private static final String FORWARDING = """
            package a.b;
            import java.util.ArrayList;
            import java.util.List;
            class FW {
                interface Tbl { void clear(); void put(String s); int size(); }
                static class Std implements Tbl {
                    private final List<String> list = new ArrayList<>();
                    public void clear() { list.clear(); }
                    public void put(String s) { list.add(s); }
                    public int size() { return list.size(); }
                }
                static abstract class FwdTbl implements Tbl {
                    protected abstract Tbl delegate();
                    public void clear() { delegate().clear(); }
                    public void put(String s) { delegate().put(s); }
                    public int size() { return delegate().size(); }
                }
                static class Wrapping extends FwdTbl {
                    private final Tbl d;
                    Wrapping(Tbl d) { this.d = d; }
                    @Override protected Tbl delegate() { return d; }
                }
            }
            """;

    @DisplayName("#42: modifying an analyzed interface through an accessor's result modifies the receiver")
    @Test
    public void forwardingThroughAnalyzedInterface() {
        TypeInfo X = javaInspector.parse("a.b.FW", FORWARDING);
        analyze(X);
        TypeInfo fwd = X.findSubType("FwdTbl");
        assertTrue(method(fwd, "delegate").isNonModifying(),
                "the accessor itself does not modify");
        assertTrue(!method(fwd, "clear").isNonModifying(), "delegate().clear() modifies this, through delegate.§m");
        assertTrue(!method(fwd, "put").isNonModifying(), "delegate().put(s) modifies this");
        assertTrue(method(fwd, "size").isNonModifying(), "delegate().size() does not");
    }
}
