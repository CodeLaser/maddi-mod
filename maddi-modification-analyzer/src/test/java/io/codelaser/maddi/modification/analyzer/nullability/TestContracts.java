package io.codelaser.maddi.modification.analyzer.nullability;

import io.codelaser.maddi.cst.api.info.Info;
import io.codelaser.maddi.cst.api.type.NullableState;
import io.codelaser.maddi.cst.api.type.ParameterizedType;
import org.intellij.lang.annotations.Language;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link Contracts}: declared and inferred contracts, annotations, null-marked code, overrides and their clamps.
 */
public class TestContracts extends NullabilityFixtures {

    @Language("java")
    private static final String OVERRIDES = """
            package a.b;
            class O {
                interface I { String get(); void put(String s); }
                static class A implements I {
                    public String get() { return null; }
                    public void put(String s) { }
                }
                static class B implements I {
                    public String get() { return "b"; }
                    public void put(String s) { }
                }
                static void use(I i) { i.put(null); }
            }
            """;

    @DisplayName("overrides: a nullable implementation return makes the interface's nullable; one parameter per chain")
    @Test
    public void overrides() {
        Report report = run("a.b.O", OVERRIDES);
        System.out.println(explain(report));
        Map<String, String> byLabel = report.verdicts().entrySet().stream()
                .collect(Collectors.toMap(e -> e.getKey().fullyQualifiedName(), e -> k(e.getValue())));
        assertEquals("String?", byLabel.get("a.b.O.I.get()"));
        assertEquals("String?", byLabel.get("a.b.O.A.get()"));
        assertEquals("String", byLabel.get("a.b.O.B.get()"));
        assertEquals("String?", byLabel.get("a.b.O.I.put(String):0:s"));
        assertEquals("String?", byLabel.get("a.b.O.A.put(String):0:s"));
        assertEquals("String?", byLabel.get("a.b.O.B.put(String):0:s"));
    }

    @Language("java")
    private static final String GENERIC_OVERRIDES = """
            package a.b;
            class G {
                interface Fn<F, T> { T apply(F input); }
                static class ToString implements Fn<Object, String> {
                    public String apply(Object o) { return String.valueOf(o); }
                }
                static class Lookup implements Fn<String, String> {
                    public String apply(String key) { return null; }
                }
                static String go(Fn<String, String> f) { return f.apply(null); }
            }
            """;

    @DisplayName("a type-variable position: implementations are not joined upward through it")
    @Test
    public void genericOverrides() {
        Report report = run("a.b.G", GENERIC_OVERRIDES);
        System.out.println(explain(report));
        Map<String, String> byLabel = report.verdicts().entrySet().stream()
                .collect(Collectors.toMap(e -> e.getKey().fullyQualifiedName(), e -> k(e.getValue())));
        // a type-variable position null reaches is nullable ('@Nullable F', guava's own style); unreached it stays
        // parametric
        assertEquals("F?", byLabel.get("a.b.G.Fn.apply(Object):0:input"));
        assertEquals("T!", byLabel.get("a.b.G.Fn.apply(Object)"));
        // downward: the override chain does not carry null from the type variable F into a concrete 'String key';
        // the call 'f.apply(null)' on an Fn<String, String> does, by its receiver's type (typed dispatch). An
        // Object implementation stays reached through the chain.
        assertEquals("String?", byLabel.get("a.b.G.Lookup.apply(String):0:key"));
        assertEquals("Object?", byLabel.get("a.b.G.ToString.apply(Object):0:o"));
        // upward stops at the type variable: Lookup's null return does not make every implementation's nullable
        assertEquals("String?", byLabel.get("a.b.G.Lookup.apply(String)"));
        assertEquals("String", byLabel.get("a.b.G.ToString.apply(Object)"));
    }

    @Language("java")
    private static final String CONTRACTS = """
            package a.b;
            import io.codelaser.maddi.annotation.NotNull;
            import io.codelaser.maddi.annotation.Nullable;
            class C {
                private final String guarded;
                C(@NotNull String t) { this.guarded = t; }
                static C make() { return new C(null); }
                String echo(@Nullable String s) { return s; }
                @Nullable String maybe() { return "x"; }
                String viaMaybe() { return maybe(); }
                String getGuarded() { return guarded; }
            }
            """;

    @DisplayName("contracts (M2): a nullable annotation seeds, a non-null one stops null and keeps its verdict")
    @Test
    public void contracts() {
        Report report = run("a.b.C", CONTRACTS);
        System.out.println(explain(report));
        // make() passes null to the non-null constructor parameter: the caller's error, which does not travel on
        assertEquals("""
                <init>(0:t): String
                echo(): String?
                echo(0:s): String?
                getGuarded(): String
                guarded: String
                make(): C
                maybe(): String?
                viaMaybe(): String?""", verdicts(report));
    }

    @DisplayName("without contracts (the oracle's view): annotations are ignored, the inference alone decides")
    @Test
    public void withoutContracts() {
        Report report = run("a.b.C", CONTRACTS, Policy.NULL_MARKED.withoutContracts());
        Map<String, String> byLabel = report.verdicts().entrySet().stream()
                .collect(Collectors.toMap(e -> label(e.getKey()), e -> k(e.getValue())));
        assertEquals("String", byLabel.get("echo(0:s)"));
        assertEquals("String?", byLabel.get("<init>(0:t)"));
        assertEquals("String?", byLabel.get("guarded"));
        assertEquals("String", byLabel.get("maybe()"));
    }

    /*
     Kotlin: an override's parameter types equal the overridden member's, nullability included (guava, the printer
     session 2026-10-16: 63 "overrides nothing"). 'abstract B doForward(A a)' receives null from 'convert'; the
     override 'doForward(String s)' of 'Converter<String, String>' must take 'String?'. The diagnostics' policies keep
     the type-variable heuristic: a null through the generic method need not reach a concrete instantiation.
     */
    @DisplayName("Kotlin: an override's parameter is nullable where the overridden type-variable parameter is")
    @Test
    public void overrideOfTypeVariableParameter() {
        String src = """
                package a.b;
                class CV {
                    static abstract class Converter<A, B> {
                        abstract B doForward(A a);
                        B convert(A a, boolean b) { return doForward(b ? null : a); }
                    }
                    static final class Upper extends Converter<String, String> {
                        String doForward(String s) { return s == null ? "" : s.toUpperCase(); }
                    }
                    static final class Length extends Converter<String, Integer> {
                        Integer doForward(String s) { return 3; }
                    }
                    static String use(Upper u) { return u.convert("x", true); }
                }
                """;
        Map<String, String> kotlin = new java.util.TreeMap<>();
        run("a.b.CV", src, Policy.KOTLIN).verdicts()
                .forEach((info, pt) -> kotlin.put(info.fullyQualifiedName(), k(pt)));
        assertEquals("A?", kotlin.get("a.b.CV.Converter.doForward(Object):0:a"), kotlin.toString());
        assertEquals("String?", kotlin.get("a.b.CV.Upper.doForward(String):0:s"), kotlin.toString());
        assertEquals("String?", kotlin.get("a.b.CV.Length.doForward(String):0:s"), kotlin.toString());

        Map<String, String> flow = new java.util.TreeMap<>();
        run("a.b.CV2", src.replace("CV", "CV2"), Policy.NULL_MARKED_FLOW_ONLY).verdicts()
                .forEach((info, pt) -> flow.put(info.fullyQualifiedName(), k(pt)));
        assertEquals("String", flow.get("a.b.CV2.Length.doForward(String):0:s"), flow.toString());
    }

    /*
     Kotlin: an override's return may narrow the overridden member's, never widen it (guava: LocalCache.EntryIterator
     .next(): Entry? over HashIterator<T>.next(): T, itself over Iterator.next()). Over an analysed type-variable
     return the overridden member becomes nullable ('Holder<T>.get(): T?'); over a Kotlin collection member that
     returns its type variable non-null ('Iterator.next(): E'), the override and every analysed override below it
     are non-null, a null value asserted. Map.get, nullable in Kotlin too, is left alone.
     */
    @DisplayName("Kotlin: an override's return is no wider than the overridden member's")
    @Test
    public void overrideOfTypeVariableReturn() {
        Map<String, String> s = new java.util.TreeMap<>();
        run("a.b.OR", """
                package a.b;
                import java.util.AbstractMap;
                import java.util.Iterator;
                import java.util.Map;
                import java.util.Set;
                class OR {
                    static abstract class Base<T> implements Iterator<T> {
                        public abstract T next();
                        public boolean hasNext() { return true; }
                    }
                    static final class Entries extends Base<String> {
                        private String current;
                        public String next() { return current; }
                    }
                    static abstract class Holder<T> {
                        abstract T get();
                    }
                    static final class Empty extends Holder<String> {
                        String get() { return null; }
                    }
                    static final class Lookup extends AbstractMap<String, String> {
                        public Set<Map.Entry<String, String>> entrySet() { return Set.of(); }
                        public String get(Object key) { return null; }
                    }
                    static String use(Holder<String> h) { return h.get(); }
                }
                """, Policy.KOTLIN).verdicts()
                .forEach((info, pt) -> s.put(info.fullyQualifiedName(), k(pt)));
        assertEquals("T!", s.get("a.b.OR.Base.next()"), s.toString()); // unreached, its type variable's own
        assertEquals("String", s.get("a.b.OR.Entries.next()"), s.toString());
        assertEquals("T?", s.get("a.b.OR.Holder.get()"), s.toString());
        assertEquals("String?", s.get("a.b.OR.Empty.get()"), s.toString());
        assertEquals("String?", s.get("a.b.OR.Lookup.get(Object)"), s.toString());
    }

    /*
     Kotlin: type arguments are invariant, so an override's type-argument slots equal the overridden member's (guava:
     AbstractBiMap.KeySet.iterator(): MutableIterator<K?> over MutableIterator<K>; AbstractMapBasedMultimap.remove:
     MutableCollection<V?>? over MutableCollection<V>?). Between analysed members the slots are one. Over a library
     member they are not touched: 'Keys extends AbstractSet<String>' holding a null needs 'AbstractSet<String?>', a
     supertype's type argument, which no verdict carries.
     */
    @DisplayName("Kotlin: an override's type-argument slots equal the overridden member's")
    @Test
    public void overrideTypeArgumentSlots() {
        Map<String, String> s = new java.util.TreeMap<>();
        run("a.b.SL", """
                package a.b;
                import java.util.AbstractSet;
                import java.util.ArrayList;
                import java.util.Iterator;
                import java.util.List;
                class SL {
                    static abstract class Source<E> {
                        abstract List<E> items();
                    }
                    static final class Strings extends Source<String> {
                        List<String> items() {
                            List<String> list = new ArrayList<>();
                            list.add(null);
                            return list;
                        }
                    }
                    static final class Plain extends Source<String> {
                        List<String> items() { return new ArrayList<>(); }
                    }
                    static final class Keys extends AbstractSet<String> {
                        private final List<String> keys = new ArrayList<>();
                        void addNull() { keys.add(null); }
                        public Iterator<String> iterator() { return keys.iterator(); }
                        public int size() { return keys.size(); }
                    }
                }
                """, Policy.KOTLIN).verdicts()
                .forEach((info, pt) -> s.put(info.fullyQualifiedName(), k(pt)));
        // the overridden slot holds a type variable, which stays that variable (typeVariableSlot): Strings, which
        // holds a null, would need 'Source<String?>', a supertype's type argument; Plain no longer follows it
        assertEquals("List<E!>", s.get("a.b.SL.Source.items()"), s.toString());
        assertEquals("List<String?>", s.get("a.b.SL.Strings.items()"), s.toString());
        assertEquals("List<String>", s.get("a.b.SL.Plain.items()"), s.toString());
        assertEquals("Iterator<String?>", s.get("a.b.SL.Keys.iterator()"), s.toString());
    }

    /*
     Kotlin, a test's override of a main member (guava's tests, worktree-maddi-all 2026-10-16): its parameter takes
     main's states exactly, type arguments included. A main type variable's own
     state ('F', unreached) stands for the instantiation, non-null. Its return is not clamped: a test implementation's
     null widens main's (langchain4j's DocumentTransformerTest). The boundary itself needs a test source set, which
     this harness does not parse into: the guava J2K ratchet's test files check it.
     */
    @DisplayName("Kotlin: a test override's types clamped to main's")
    @Test
    public void testOverrideClamped() {
        run("a.b.CL", """
                package a.b;
                import java.util.Deque;
                import java.util.List;
                class CL<F> {
                    Deque<String> deque;
                    List<String> list;
                    F f;
                }
                """);
        ParameterizedType declared = parsed.getFieldByName("deque", true).type();
        ParameterizedType deque = declared.withParameters(
                List.of(declared.parameters().getFirst().withNullable(NullableState.NONNULL)));
        ParameterizedType list = parsed.getFieldByName("list", true).type();
        ParameterizedType f = parsed.getFieldByName("f", true).type();
        ParameterizedType nullableDeque = deque.withNullable(NullableState.NULLABLE);
        // a parameter: exactly main's
        assertEquals("Deque<String>", k(Contracts.clamped(nullableDeque,
                f.withNullable(NullableState.UNSPECIFIED))));
        assertEquals("Deque<String>?", k(Contracts.clamped(deque.withNullable(NullableState.NONNULL),
                f.withNullable(NullableState.NULLABLE))));
        ParameterizedType nullableElements = list.withNullable(NullableState.NONNULL)
                .withParameters(List.of(list.parameters().getFirst().withNullable(NullableState.NULLABLE)));
        ParameterizedType plainElements = list.withNullable(NullableState.NONNULL)
                .withParameters(List.of(list.parameters().getFirst().withNullable(NullableState.NONNULL)));
        assertEquals("List<String>", k(Contracts.clamped(nullableElements, plainElements)));
    }

    /*
     Kotlin: a type parameter with a bound other than Object ('C extends Comparable<C>', 'E extends Enum<E>') takes no
     nullable argument (guava, worktree-maddi-all 2026-10-16: 'Range<C?>', 'EnumSet<E?>', most of 129 "type argument
     is not within its bounds"). Its slots stay non-null; a null written into one is asserted. An unbounded one
     ('Holder<T>') takes the null.
     */
    @DisplayName("Kotlin: a bounded type parameter's slot is non-null")
    @Test
    public void boundedTypeParameterSlot() {
        Map<String, String> s = new java.util.TreeMap<>();
        run("a.b.BS", """
                package a.b;
                class BS {
                    static final class Box<C extends Comparable<C>> {
                        private final C c;
                        Box(C c) { this.c = c; }
                        C get() { return c; }
                    }
                    static final class Holder<T> {
                        private final T t;
                        Holder(T t) { this.t = t; }
                        T get() { return t; }
                    }
                    static Box<String> box() { return new Box<>(null); }
                    static Holder<String> holder() { return new Holder<>(null); }
                }
                """, Policy.KOTLIN).verdicts()
                .forEach((info, pt) -> s.put(info.fullyQualifiedName(), k(pt)));
        assertEquals("Box<String>", s.get("a.b.BS.box()"), s.toString());
        assertEquals("Holder<String?>", s.get("a.b.BS.holder()"), s.toString());
    }

    /*
     Kotlin: a type-argument slot whose argument is a type variable stays that variable ('Converter<A, B>', never
     'Converter<A?, B>'): the variable already ranges over nullable instantiations, and Kotlin's invariant generics
     make the two incompatible (guava, worktree-maddi-all 2026-10-16: Converter's composition and reverse paths, Maps,
     TreeRangeSet, LocalCache, Sets: about 419 argument and return mismatches). A null written into one is the
     printer's unchecked cast. A concrete argument still takes the null.
     */
    @DisplayName("Kotlin: a type-argument slot holding a type variable is not made nullable")
    @Test
    public void typeVariableSlot() {
        Map<String, String> s = new java.util.TreeMap<>();
        run("a.b.TV", """
                package a.b;
                class TV {
                    static final class Pair<A, B> {
                        private final A a;
                        private final B b;
                        Pair(A a, B b) { this.a = a; this.b = b; }
                    }
                    static <A> Pair<A, String> generic(String b) { return new Pair<>(null, b); }
                    static Pair<String, String> concrete(String b) { return new Pair<>(null, b); }
                }
                """, Policy.KOTLIN).verdicts()
                .forEach((info, pt) -> s.put(info.fullyQualifiedName(), k(pt)));
        assertEquals("Pair<A!, String>", s.get("a.b.TV.generic(String)"), s.toString());
        assertEquals("Pair<String?, String>", s.get("a.b.TV.concrete(String)"), s.toString());
    }

    @DisplayName("Kotlin: an explicitly @Nullable override of a bounded type-variable return widens the overridden")
    @Test
    public void explicitNullableBoundedOverride() {
        Report report = run("a.b.EB", """
                package a.b;
                import java.lang.annotation.ElementType;
                import java.lang.annotation.Target;
                class EB {
                    @Target(ElementType.TYPE_USE) @interface Nullable {}
                    interface Entry<E extends Entry<E>> { }
                    static final class Strong implements Entry<Strong> { }
                    interface Helper<E extends Entry<E>> { E copy(E entry); }
                    static final class StrongHelper implements Helper<Strong> {
                        @Override public @Nullable Strong copy(Strong entry) { return entry.hashCode() > 0 ? null : entry; }
                    }
                }
                """, Policy.KOTLIN);
        Map<String, String> s = new java.util.TreeMap<>();
        report.verdicts().forEach((info, pt) -> s.put(info.fullyQualifiedName(), k(pt)));
        assertEquals("Strong?", s.get("a.b.EB.StrongHelper.copy(a.b.EB.Strong)"), s.toString());
        assertEquals("E?", s.get("a.b.EB.Helper.copy(a.b.EB.Entry)"), s.toString());
    }

    @DisplayName("Kotlin: a type-use @Nullable in an array declaration is about the elements, not the array")
    @Test
    public void elementAnnotationOnArray() {
        Report report = run("a.b.EA", """
                package a.b;
                class EA {
                    @java.lang.annotation.Target(java.lang.annotation.ElementType.TYPE_USE) @interface Nullable {}
                    @Nullable Object[] entries = new Object[2];
                    @Nullable String[] names = {"a"};
                    @Nullable String[][] grid = {{"a"}};
                    String cell() { return java.util.Objects.requireNonNull(grid[0][0]); }
                    Object[] get() { return entries; }
                    static Object[] pass(@Nullable String[] p) { return p; }
                }
                """, Policy.KOTLIN);
        Map<String, String> s = new java.util.TreeMap<>();
        report.verdicts().forEach((info, pt) -> s.put(info.fullyQualifiedName(), k(pt)));
        String all = s + "\n" + explain(report);
        assertFalse(s.get("a.b.EA.entries").endsWith("]?"), all);
        assertTrue(s.get("a.b.EA.entries").contains("?["), all);
        // the elements by the declaration alone, not by a null written into them
        assertEquals("String?[]", s.get("a.b.EA.names"), all);
        // two dimensions: the rows stay non-null
        assertFalse(s.get("a.b.EA.grid").contains("[]?[]"), all);
        assertFalse(s.get("a.b.EA.cell()").endsWith("?"), all);
        assertFalse(s.get("a.b.EA.get()").endsWith("]?"), all);
        assertFalse(s.get("a.b.EA.pass(String[]):0:p").endsWith("]?"), all);
    }

    @DisplayName("Kotlin: a null-marked class's type parameter without a @Nullable bound is non-null, and so are its slots")
    @Test
    public void nullMarkedBounds() {
        Report report = run("a.b.NM", """
                package a.b;
                import java.util.HashMap;
                import java.util.Map;
                class NM {
                    @java.lang.annotation.Target(java.lang.annotation.ElementType.TYPE_USE) @interface Nullable {}
                    @interface NullMarked {}
                    @NullMarked static class Box<K, V> {
                        final Map<K, V> m = new HashMap<>();
                        void put(K k, V v) { m.put(k, v); }
                    }
                    @NullMarked static class Lenient<T extends @Nullable Object> { }
                    static class Sub<E> extends Box<E, String> { }
                    static class Free<F> { }
                    static void use(Box<String, String> b) { b.put("a", null); }
                }
                """, Policy.KOTLIN);
        Map<String, io.codelaser.maddi.cst.api.info.TypeParameter> tps = new java.util.TreeMap<>();
        parsed.subTypes().forEach(st -> st.typeParameters().forEach(tp -> tps.put(st.simpleName() + "." + tp.simpleName(), tp)));
        assertTrue(report.nonNullTypeParameter(tps.get("Box.K")), tps.toString());
        assertTrue(report.nonNullTypeParameter(tps.get("Box.V")));
        assertFalse(report.nonNullTypeParameter(tps.get("Lenient.T")));
        assertTrue(report.nonNullTypeParameter(tps.get("Sub.E")), "carried over through the supertype");
        assertFalse(report.nonNullTypeParameter(tps.get("Free.F")));
        Map<String, String> s = new java.util.TreeMap<>();
        report.verdicts().forEach((info, pt) -> s.put(info.fullyQualifiedName(), k(pt)));
        assertFalse(s.get("a.b.NM.use(a.b.NM.Box):0:b").contains("?"), s + "\n" + explain(report));
    }

    @DisplayName("Kotlin: a type variable used in a null-marked position is non-null too, or the position is left unbounded")
    @Test
    public void nullMarkedBoundsCarried() {
        Report report = run("a.b.NC", """
                package a.b;
                class NC {
                    @java.lang.annotation.Target(java.lang.annotation.ElementType.TYPE_USE) @interface Nullable {}
                    @interface NullMarked {}
                    @NullMarked static class Response<T> {
                        final T content;
                        Response(T content) { this.content = content; }
                        static <U> Response<U> from(U content) { return new Response<>(content); }
                    }
                    interface Handler<X> { void onComplete(Response<X> response); }
                    @NullMarked static class Pair<A> { }
                    interface Lenient<Y extends @Nullable Object> { Pair<Y> get(); }
                    @NullMarked static class Single<S> { }
                    interface Explicit<W> { Single<@Nullable W> get(); }
                    @java.lang.annotation.Target(java.lang.annotation.ElementType.TYPE_USE) @interface NonNull {}
                    interface Fluent<Z extends @Nullable Object> { Response<@NonNull Z> toResponse(); }
                    static class Maker<M> {
                        Object make(M m) { return new Response<M>(m); }
                    }
                    static <V> Object wild(java.util.List<? extends Response<? extends V>> list) { return list; }
                    @NullMarked static class Token<Q> { }
                    abstract static class Holder<B extends @Nullable Object>
                            implements java.util.function.Supplier<Token<? extends @NonNull B>> { }
                    static <E> java.util.List<E> create(java.util.List<? extends java.util.List<? extends E>> lists) {
                        Response<E> r = null;
                        return null;
                    }
                    static <P> Object product(java.util.List<? extends java.util.List<? extends P>> lists) {
                        return create(lists);
                    }
                    static <T> Object explicitList(java.util.List<T> values) { return new Response<T>(values.get(0)); }
                    static <V2> Object explicitVar(V2 first) {
                        return explicitList(java.util.Collections.singletonList(first));
                    }
                    static <K3> java.util.function.Function<java.util.List<K3>, Object> wrapper() {
                        return Wrap::new;
                    }
                    @NullMarked static class Wrap<W> {
                        Wrap(java.util.List<W> list) { }
                    }
                }
                """, Policy.KOTLIN);
        Map<String, io.codelaser.maddi.cst.api.info.TypeParameter> tps = new java.util.TreeMap<>();
        parsed.subTypes().forEach(st -> st.typeParameters().forEach(tp -> tps.put(st.simpleName() + "." + tp.simpleName(), tp)));
        java.util.stream.Stream.concat(java.util.stream.Stream.of(parsed), parsed.subTypes().stream())
                .forEach(st -> st.methods().forEach(m -> m.typeParameters()
                        .forEach(tp -> tps.put(m.name() + "." + tp.simpleName(), tp))));
        assertTrue(report.nonNullTypeParameter(tps.get("Response.T")), tps.toString());
        assertTrue(report.nonNullTypeParameter(tps.get("Handler.X")), "an interface's type variable in the position");
        assertTrue(report.nonNullTypeParameter(tps.get("from.U")), "a method's type variable in the position");
        assertTrue(report.nonNullTypeParameter(tps.get("Pair.A")), "a @Nullable-bounded variable is not a conflict");
        assertFalse(report.nonNullTypeParameter(tps.get("Lenient.Y")));
        assertFalse(report.nonNullTypeParameter(tps.get("Single.S")), "a @Nullable use");
        assertFalse(report.nonNullTypeParameter(tps.get("Fluent.Z")), "'@NonNull Z' is Kotlin's 'Z & Any'");
        assertTrue(report.nonNullTypeParameter(tps.get("Maker.M")), "a constructor call in a body uses the position");
        assertTrue(report.nonNullTypeParameter(tps.get("wild.V")), "a nested wildcard's bound");
        assertFalse(report.nonNullTypeParameter(tps.get("Token.Q")), "'? extends @NonNull B' in a supertype");
        assertTrue(report.nonNullTypeParameter(tps.get("product.P")), "what the call to create instantiates E with");
        assertTrue(report.nonNullTypeParameter(tps.get("explicitVar.V2")), "through the argument's inferred type");
        assertTrue(report.nonNullTypeParameter(tps.get("wrapper.K3")), "a constructor reference");
    }

    @DisplayName("Kotlin: a slot whose values reach a null-marked slot is non-null too, ImmutableMultiset.SerializedForm")
    @Test
    public void tiedToNullMarkedSlot() {
        Report report = run("a.b.NT", """
                package a.b;
                import java.util.ArrayList;
                import java.util.List;
                class NT {
                    @interface NullMarked {}
                    @NullMarked static class Bag<G> {
                        static <H> Bag<H> copyOf(Iterable<? extends H> items) { return new Bag<>(); }
                    }
                    static Object restore(Object[] elements) {
                        List<Object> list = new ArrayList<>();
                        for (int i = 0; i < elements.length; i++) list.add(elements[i]);
                        return Bag.copyOf(list);
                    }
                    static Object other(Object[] elements) {
                        List<Object> free = new ArrayList<>();
                        for (int i = 0; i < elements.length; i++) free.add(elements[i]);
                        return free;
                    }
                    static Object call() { Object[] holes = new Object[3]; restore(holes); return other(holes); }
                    static Object keys(Iterable<?> keys) {
                        List<Object> fromWildcard = new ArrayList<>();
                        for (Object k : keys) fromWildcard.add(k);
                        List<Object> copied = copy(keys);
                        copied.add(null);
                        return Bag.copyOf(copied);
                    }
                    static List<Object> copy(Iterable<?> it) { return new ArrayList<>(); }
                }
                """, Policy.KOTLIN);
        Map<String, String> l = new java.util.TreeMap<>();
        report.locals().forEach((local, pt) -> l.put(local.name(), k(pt)));
        assertTrue(l.get("free").contains("?"), l + "\n" + explain(report));
        assertFalse(l.get("list").contains("?"), l + "\n" + explain(report));
        assertTrue(l.get("copied").contains("?"), "made from a '?' argument: " + l + "\n" + explain(report));
    }

    @DisplayName("Kotlin: non-null bounds reach a member-less subinterface and an override family's method type parameter, timefold")
    @Test
    public void nonNullBoundsFamilies() {
        Report report = run("a.b.TF", """
                package a.b;
                import java.util.Comparator;
                class TF {
                    @interface NullMarked {}
                    @NullMarked interface ComparatorFactory<S, T> {
                        Comparator<T> createComparator(S solution);
                    }
                    interface NullComparatorFactory<S, T> extends ComparatorFactory<S, T> {
                    }
                    @NullMarked interface Stream2<A> {
                    }
                    interface ConstraintFactory {
                        <A> Stream2<A> forEach(Class<A> sourceClass);
                    }
                    @NullMarked interface Move<M> {
                    }
                    abstract static class SwapIterator<Solution_, Move_ extends Move<Solution_>, Sub_> {
                        abstract Move_ next(Sub_ sub);
                    }
                    static class Swap implements Move<String> {
                    }
                    static class SwapImpl extends SwapIterator<String, Swap, Integer> {
                        @Override
                        Swap next(Integer sub) {
                            return new Swap();
                        }
                    }
                    static class Impl<S2, T2> implements NullComparatorFactory<S2, T2> {
                        @Override
                        public Comparator<T2> createComparator(S2 solution) {
                            return null;
                        }
                    }
                    interface UniStream<A> {
                        <B> UniStream<A> ifExists(Class<B> otherClass);
                    }
                    static class InnerStream<A> implements UniStream<A> {
                        ConstraintFactory factory;
                        @Override
                        public <B> UniStream<A> ifExists(Class<B> otherClass) {
                            factory.forEach(otherClass);
                            return this;
                        }
                    }
                    static class OtherStream<A> implements UniStream<A> {
                        @Override
                        public <B> UniStream<A> ifExists(Class<B> otherClass) {
                            return this;
                        }
                    }
                }
                """, Policy.KOTLIN);
        java.util.Set<String> nonNull = new java.util.TreeSet<>();
        for (Info info : report.verdicts().keySet()) {
            if (info instanceof io.codelaser.maddi.cst.api.info.MethodInfo mi) {
                for (var tp : mi.typeParameters()) {
                    if (report.nonNullTypeParameter(tp)) nonNull.add(mi.fullyQualifiedName() + " " + tp.simpleName());
                }
                for (var tp : mi.typeInfo().typeParameters()) {
                    if (report.nonNullTypeParameter(tp)) nonNull.add(mi.typeInfo().fullyQualifiedName() + " " + tp.simpleName());
                }
                if (mi.typeInfo().parentClass() != null && mi.typeInfo().parentClass().typeInfo() != null) {
                    for (var tp : mi.typeInfo().parentClass().typeInfo().typeParameters()) {
                        if (report.nonNullTypeParameter(tp)) {
                            nonNull.add(mi.typeInfo().parentClass().typeInfo().fullyQualifiedName() + " " + tp.simpleName());
                        }
                    }
                }
                for (var st : mi.typeInfo().interfacesImplemented()) {
                    if (st.typeInfo() == null) continue;
                    for (var tp : st.typeInfo().typeParameters()) {
                        if (report.nonNullTypeParameter(tp)) nonNull.add(st.typeInfo().fullyQualifiedName() + " " + tp.simpleName());
                    }
                }
            }
        }
        String all = String.join("\n", nonNull);
        assertTrue(all.contains("a.b.TF.SwapIterator Solution_"), all);
        assertTrue(all.contains("a.b.TF.NullComparatorFactory S"), all);
        assertTrue(all.contains("a.b.TF.NullComparatorFactory T"), all);
        assertTrue(all.contains("a.b.TF.UniStream.ifExists(Class) B"), all);
        assertTrue(all.contains("a.b.TF.OtherStream.ifExists(Class) B"), all);
        assertTrue(all.contains("a.b.TF.InnerStream.ifExists(Class) B"), all);
    }

    @DisplayName("Kotlin: an override of a @NullMarked library method's non-null return stays non-null, jenkins AuthenticationProcessingFilter2")
    @Test
    public void nullMarkedLibraryReturnOverride() {
        Report report = run("a.b.NR", """
                package a.b;
                import org.junit.jupiter.api.DisplayNameGenerator;
                class NR extends DisplayNameGenerator.Standard {
                    @Override
                    public String generateDisplayNameForClass(Class<?> testClass) {
                        if (testClass.isInterface()) return null;
                        return super.generateDisplayNameForClass(testClass);
                    }
                }
                """, Policy.KOTLIN);
        Map<String, String> s = new java.util.TreeMap<>();
        report.verdicts().forEach((info, pt) -> s.put(info.fullyQualifiedName(), k(pt)));
        assertEquals("String", s.get("a.b.NR.generateDisplayNameForClass(Class)"), s + "\n" + explain(report));
    }

    @DisplayName("Kotlin: a @ParametricNullness type-variable parameter is a plain V, guava's TrustedListenableFutureTask.create")
    @Test
    public void parametricParameter() {
        Report report = run("a.b.PP", """
                package a.b;
                import java.util.concurrent.Callable;
                import java.util.concurrent.Executors;
                class PP {
                    @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.CLASS) @interface ParametricNullness {}
                    static final class Task<V> {
                        final Callable<V> callable;
                        Task(Callable<V> callable) { this.callable = callable; }
                        static <V> Task<V> create(Runnable runnable, @ParametricNullness V result) {
                            return new Task<V>(Executors.callable(runnable, result));
                        }
                    }
                    static Task<Void> submit(Runnable r) { return Task.create(r, null); }
                }
                """, Policy.KOTLIN);
        Map<String, String> s = new java.util.TreeMap<>();
        report.verdicts().forEach((info, pt) -> s.put(info.fullyQualifiedName(), k(pt)));
        String all = s + "\n" + explain(report);
        assertFalse(s.get("a.b.PP.Task.create(Runnable,Object):1:result").endsWith("?"), all);
    }

    @DisplayName("Kotlin: a lazily cached view whose factory family returns another map's view is non-null, guava's createKeySet")
    @Test
    public void lazyCacheCycle() {
        Report report = run("a.b.LC", """
                package a.b;
                import java.util.HashSet;
                import java.util.Set;
                abstract class LC {
                    @java.lang.annotation.Target(java.lang.annotation.ElementType.TYPE_USE) @interface Nullable {}
                    private transient @Nullable Set<String> keySet;
                    Set<String> keySet() {
                        Set<String> result = keySet;
                        return (result == null) ? keySet = createKeySet() : result;
                    }
                    abstract Set<String> createKeySet();
                    static final class Plain extends LC {
                        Set<String> createKeySet() { return new HashSet<>(); }
                    }
                    static final class View extends LC {
                        final LC from;
                        View(LC from) { this.from = from; }
                        Set<String> createKeySet() { return from.keySet(); }
                    }
                    static final class Nulls extends LC {
                        private transient @Nullable Set<String> cached;
                        Set<String> cached() {
                            Set<String> result = cached;
                            return (result == null) ? cached = make() : result;
                        }
                        @Nullable Set<String> make() { return null; }
                        Set<String> createKeySet() { return new HashSet<>(); }
                    }
                }
                """, Policy.KOTLIN);
        Map<String, String> s = new java.util.TreeMap<>();
        report.verdicts().forEach((info, pt) -> s.put(info.fullyQualifiedName(), k(pt)));
        String all = s + "\n" + explain(report);
        assertFalse(s.get("a.b.LC.keySet()").endsWith("?"), all);
        assertFalse(s.get("a.b.LC.createKeySet()").endsWith("?"), all);
        assertFalse(s.get("a.b.LC.View.createKeySet()").endsWith("?"), all);
        // a real null still wins
        assertTrue(s.get("a.b.LC.Nulls.cached()").endsWith("?"), all);
    }

    @DisplayName("Kotlin: an annotated nullable lazy-init field returned through a cast is non-null, guava's NaturalOrdering")
    @Test
    public void lazyInitCastReturn() {
        Report report = run("a.b.NO", """
                package a.b;
                import java.util.Comparator;
                abstract class NO {
                    @java.lang.annotation.Target(java.lang.annotation.ElementType.TYPE_USE) @interface Nullable {}
                    static abstract class Ord<T extends @Nullable Object> implements Comparator<T> {
                        <S extends T> Ord<S> reverse() { return new Rev<S>(this); }
                        <S extends T> Ord<@Nullable S> nullsFirst() { return new NullsFirst<S>(this); }
                    }
                    static final class Rev<T extends @Nullable Object> extends Ord<T> {
                        final Ord<? super T> forward;
                        Rev(Ord<? super T> forward) { this.forward = forward; }
                        public int compare(T a, T b) { return forward.compare(b, a); }
                    }
                    static final class NullsFirst<T> extends Ord<@Nullable T> {
                        final Ord<? super T> ordering;
                        NullsFirst(Ord<? super T> ordering) { this.ordering = ordering; }
                        public int compare(@Nullable T a, @Nullable T b) { return a == null ? -1 : b == null ? 1 : ordering.compare(a, b); }
                        @Override <S extends @Nullable T> Ord<S> reverse() { return ordering.<T>reverse().<S>nullsFirst(); }
                    }
                    static final class Natural extends Ord<Comparable<?>> {
                        private transient @Nullable Ord<@Nullable Comparable<?>> nullsFirst;
                        @SuppressWarnings("unchecked")
                        public int compare(Comparable<?> left, Comparable<?> right) { return ((Comparable<Object>) left).compareTo(right); }
                        @Override
                        @SuppressWarnings("unchecked")
                        <S extends Comparable<?>> Ord<@Nullable S> nullsFirst() {
                            Ord<@Nullable Comparable<?>> result = nullsFirst;
                            if (result == null) {
                                result = nullsFirst = super.nullsFirst();
                            }
                            return (Ord<@Nullable S>) result;
                        }
                    }
                }
                """, Policy.KOTLIN);
        Map<String, String> s = new java.util.TreeMap<>();
        report.verdicts().forEach((info, pt) -> s.put(info.fullyQualifiedName(), k(pt)));
        String all = s + "\n" + explain(report);
        assertFalse(s.get("a.b.NO.Natural.nullsFirst()").endsWith("?"), all);
        assertFalse(s.get("a.b.NO.Ord.nullsFirst()").endsWith("?"), all);
        assertFalse(s.get("a.b.NO.Ord.reverse()").endsWith("?"), all);
        assertFalse(s.get("a.b.NO.NullsFirst.reverse()").endsWith("?"), all);
    }

    @DisplayName("Kotlin: an explicitly @Nullable type-variable return over a JDK default stays nullable")
    @Test
    public void nullableJdkDefaultOverride() {
        Report report = run("a.b.JD", """
                package a.b;
                import java.lang.annotation.ElementType;
                import java.lang.annotation.Target;
                import java.util.AbstractMap;
                import java.util.Map;
                import java.util.Set;
                import java.util.concurrent.ConcurrentMap;
                abstract class JD {
                    @Target(ElementType.TYPE_USE) @interface Nullable {}
                    abstract static class Fwd<K, V> implements ConcurrentMap<K, V> {
                        abstract ConcurrentMap<K, V> delegate();
                        @Override public @Nullable V replace(K key, V value) { return delegate().replace(key, value); }
                    }
                    abstract static class View<K, V> extends AbstractMap<K, V> {
                        @Override public @Nullable V get(@Nullable Object key) { return getOrDefault(key, null); }
                    }
                }
                """, Policy.KOTLIN);
        Map<String, String> s = new java.util.TreeMap<>();
        report.verdicts().forEach((info, pt) -> s.put(info.fullyQualifiedName(), k(pt)));
        assertEquals("V?", s.get("a.b.JD.Fwd.replace(Object,Object)"), s.toString());
        assertEquals("V?", s.get("a.b.JD.View.get(Object)"), s.toString());
    }

    @DisplayName("Kotlin: an override of a type-variable parameter keeps the overridden state")
    @Test
    public void typeVariableParameterOverride() {
        Report report = run("a.b.TO", """
                package a.b;
                import java.lang.annotation.ElementType;
                import java.lang.annotation.Target;
                import java.util.function.BiPredicate;
                class TO {
                    @Target(ElementType.TYPE_USE) @interface Nullable {}
                    interface Function<F, T> extends java.util.function.Function<F, T> {
                        T apply(F input);
                    }
                    static final class Cut implements Comparable<Cut> {
                        @Override public int compareTo(Cut that) { return that == null ? 1 : 0; }
                    }
                    abstract static class Eq<T> implements BiPredicate<@Nullable T, @Nullable T> {
                        @Override public boolean test(@Nullable T t, @Nullable T u) { return t == u; }
                    }
                    static String call(Function<String, String> f, Cut c) {
                        c.compareTo(null);
                        return f.apply(null);
                    }
                }
                """, Policy.KOTLIN);
        Map<String, String> s = new java.util.TreeMap<>();
        report.verdicts().forEach((info, pt) -> s.put(info.fullyQualifiedName(), k(pt)));
        assertEquals("F!", s.get("a.b.TO.Function.apply(Object):0:input"), s.toString());
        assertEquals("Cut", s.get("a.b.TO.Cut.compareTo(a.b.TO.Cut):0:that"), s.toString());
        assertEquals("T?", s.get("a.b.TO.Eq.test(Object,Object):0:t"), s.toString());
        assertEquals("Function<String?, String>", s.get("a.b.TO.call(a.b.TO.Function,a.b.TO.Cut):0:f"),
                "the null belongs to the instantiation");
    }

    @DisplayName("Kotlin: a @ParametricNullness return's own null stays with nullable instantiations")
    @Test
    public void parametricNullnessReturn() {
        Report report = run("a.b.PN", """
                package a.b;
                import java.lang.annotation.Retention;
                import java.lang.annotation.RetentionPolicy;
                import java.util.HashMap;
                import java.util.Map;
                class PN {
                    @Retention(RetentionPolicy.CLASS) @interface ParametricNullness {}
                    interface Supplier<T> { @ParametricNullness T get(); }
                    static class Memo<T> implements Supplier<T> {
                        T value;
                        @Override @ParametricNullness public T get() { return value; }
                    }
                    static class Table<R, C, V> {
                        final Map<R, Map<C, V>> backingMap;
                        final Supplier<? extends Map<C, V>> factory;
                        Table(Map<R, Map<C, V>> backingMap, Supplier<? extends Map<C, V>> factory) {
                            this.backingMap = backingMap;
                            this.factory = factory;
                        }
                        Map<C, V> getOrCreate(R rowKey) {
                            Map<C, V> map = backingMap.get(rowKey);
                            if (map == null) {
                                map = factory.get();
                                backingMap.put(rowKey, map);
                            }
                            return map;
                        }
                        Map<C, V> create() { return factory.get(); }
                    }
                }
                """, Policy.KOTLIN);
        Map<String, String> s = new java.util.TreeMap<>();
        report.verdicts().forEach((info, pt) -> s.put(info.fullyQualifiedName(), k(pt)));
        assertEquals("Map<C!, V!>", s.get("a.b.PN.Table.create()"), s + "\n" + explain(report));
        assertEquals("T?", s.get("a.b.PN.Supplier.get()"), "the declaration keeps it; the printer prints T");
    }

    @DisplayName("Kotlin: an implementation for a @Nullable type argument keeps its null from the overridden return")
    @Test
    public void nullableInstantiationReturn() {
        Report report = run("a.b.NI", """
                package a.b;
                import java.lang.annotation.ElementType;
                import java.lang.annotation.Retention;
                import java.lang.annotation.RetentionPolicy;
                import java.lang.annotation.Target;
                class NI {
                    @Retention(RetentionPolicy.CLASS) @interface ParametricNullness {}
                    @Target(ElementType.TYPE_USE) @interface Nullable {}
                    interface Cell<V> { @ParametricNullness V getValue(); }
                    abstract static class AbstractCell<V> implements Cell<V> { }
                    static final class Immutable<V> extends AbstractCell<V> {
                        private final V value;
                        Immutable(V value) { this.value = value; }
                        @Override public V getValue() { return value; }
                    }
                    static final class Table<V> {
                        private final @Nullable Object[] values = new Object[4];
                        @SuppressWarnings("unchecked")
                        @Nullable V at(int i) { return (V) values[i]; }
                        Cell<@Nullable V> cell(int i) {
                            return new AbstractCell<@Nullable V>() {
                                @Override public @Nullable V getValue() { return at(i); }
                            };
                        }
                    }
                    interface Plain<V> { V get(); }
                    static final class Absent<V> implements Plain<V> {
                        @Override public V get() { return null; }
                    }
                    static <V> V valueOf(Cell<V> c) { return c.getValue(); }
                    // guava's ArrayTable.ArrayMap and its Row over ArrayMap<C, @Nullable V>
                    abstract static class ArrayMap<K, V> {
                        @ParametricNullness abstract V getValue(int index);
                        V get(int index) { return getValue(index); }
                    }
                    static final class Grid<V> {
                        private final @Nullable Object[] cells = new Object[4];
                        @SuppressWarnings("unchecked")
                        @Nullable V at(int i) { return (V) cells[i]; }
                        final class Row extends ArrayMap<String, @Nullable V> {
                            @Override @Nullable V getValue(int index) { return at(index); }
                        }
                    }
                }
                """, Policy.KOTLIN);
        Map<String, String> s = new java.util.TreeMap<>();
        report.verdicts().forEach((info, pt) -> s.put(info.fullyQualifiedName(), k(pt)));
        assertEquals("V!", s.get("a.b.NI.Cell.getValue()"), s + "\n" + explain(report));
        assertEquals("V?", s.get("a.b.NI.Table.$0.getValue()"), s.toString());
        assertEquals("V?", s.get("a.b.NI.Plain.get()"), "no nullable type argument: the null reaches the declaration");
        assertEquals("V!", s.get("a.b.NI.ArrayMap.getValue(int)"), s.toString());
        assertEquals("V?", s.get("a.b.NI.Grid.Row.getValue(int)"), "the override keeps its own null: " + s);
    }

    @DisplayName("Kotlin: a lazily initialised field's cached value is non-null at the return")
    @Test
    public void lazyInitReturn() {
        Report report = run("a.b.LZ", """
                package a.b;
                import java.util.Collections;
                import java.util.HashMap;
                import java.util.Map;
                class LZ {
                    final Map<String, String> source = new HashMap<>();
                    transient Map<String, String> map;
                    transient Map<String, String> other;
                    Map<String, String> chained() {
                        Map<String, String> result = map;
                        if (result == null) {
                            result = map = Collections.unmodifiableMap(source);
                        }
                        return result;
                    }
                    Map<String, String> separate() {
                        Map<String, String> result = other;
                        if (result == null) {
                            result = Collections.unmodifiableMap(source);
                            other = result;
                        }
                        return result;
                    }
                }
                """, Policy.KOTLIN);
        Map<String, String> s = new java.util.TreeMap<>();
        report.verdicts().forEach((info, pt) -> s.put(info.fullyQualifiedName(), k(pt)));
        assertEquals("Map<String, String>", s.get("a.b.LZ.chained()"), s + "\n" + explain(report));
        assertEquals("Map<String, String>", s.get("a.b.LZ.separate()"), s + "\n" + explain(report));
    }

    @DisplayName("library content nullable one level down (JSpecify's nested positions): overrides and callbacks")
    @Test
    public void libraryNestedNullability() {
        String source = """
                package a.b;
                import java.lang.reflect.InvocationHandler;
                import java.lang.reflect.Method;
                import java.util.AbstractCollection;
                import java.util.Iterator;
                import java.util.List;
                import java.util.Map;
                class LN {
                    static class Bag extends AbstractCollection<String> {
                        private final List<String> items;
                        Bag(List<String> items) { this.items = items; }
                        @Override public Iterator<String> iterator() { return items.iterator(); }
                        @Override public int size() { return items.size(); }
                        @Override public Object[] toArray() { return items.toArray(); }
                    }
                    static class Handler implements InvocationHandler {
                        @Override
                        public Object invoke(Object proxy, Method method, Object[] args) {
                            return args.length;
                        }
                    }
                    static void count(Map<String, Integer> counts, String word) {
                        counts.compute(word, (key, old) -> old + 1);
                    }
                    static void touch(Map<String, Integer> counts, String word) {
                        counts.computeIfPresent(word, (key, old) -> old + 1);
                    }
                }
                """;
        Report kotlin = run("a.b.LN", source, Policy.KOTLIN);
        System.out.println(explain(kotlin));
        Map<String, String> k = kotlin.verdicts().entrySet().stream()
                .collect(Collectors.toMap(e -> e.getKey().fullyQualifiedName(), e -> k(e.getValue())));
        // Collection.toArray(): '@Nullable Object[]' in JSpecify, the elements; the array itself is not null
        assertEquals("Object?[]", k.get("a.b.LN.Bag.toArray()"));
        // InvocationHandler.invoke: 'args' is null for a method without parameters, and its elements may be
        assertEquals("Object?[]?", k.get("a.b.LN.Handler.invoke(Object,java.lang.reflect.Method,Object[]):2:args"));
        // Map.compute calls the remapping function with null for an absent key ('? super @Nullable V');
        // computeIfPresent never does ('? super @NonNull V')
        // the lambdas in source order: $0 in count, $1 in touch
        assertEquals("Integer?", k.get("a.b.LN.$0.apply(String,Integer):1:old"));
        assertEquals("Integer", k.get("a.b.LN.$1.apply(String,Integer):1:old"));
    }

    @DisplayName("a parameter of a library override is nullable for Kotlin unless the library declares it non-null")
    @Test
    public void libraryOverrideParameter() {
        String source = """
                package a.b;
                import java.io.IOException;
                import java.nio.file.*;
                import java.nio.file.attribute.BasicFileAttributes;
                class LO {
                    static void deleteRecursively(Path dir) throws IOException {
                        Files.walkFileTree(dir, new SimpleFileVisitor<>() {
                            @Override
                            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                                Files.delete(file);
                                return FileVisitResult.CONTINUE;
                            }
                            @Override
                            public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                                Files.delete(dir);
                                return FileVisitResult.CONTINUE;
                            }
                        });
                    }
                    static class Handler implements Thread.UncaughtExceptionHandler {
                        @Override
                        public void uncaughtException(Thread t, Throwable e) { }
                    }
                    static class Completion implements java.nio.channels.CompletionHandler<Integer, Void> {
                        @Override
                        public void completed(Integer result, Void attachment) { }
                        @Override
                        public void failed(Throwable exc, Void attachment) { }
                    }
                }
                """;
        Report kotlin = run("a.b.LO", source, Policy.KOTLIN);
        System.out.println(explain(kotlin));
        Map<String, String> k = kotlin.verdicts().entrySet().stream()
                .collect(Collectors.toMap(e -> e.getKey().fullyQualifiedName(), e -> k(e.getValue())));
        // the JDK hint: exc is null when the directory's iteration completed without an error
        assertEquals("IOException?", k.get("a.b.LO.$0.postVisitDirectory(java.nio.file.Path,java.io.IOException):1:exc"),
                "fernflower DecompilerTestFixture.deleteRecursively");
        // T is Path here: a type-variable parameter follows the instantiation
        assertEquals("Path", k.get("a.b.LO.$0.visitFile(java.nio.file.Path,java.nio.file.attribute.BasicFileAttributes):0:file"));
        // hinted non-null: the JDK passes the file's attributes
        assertEquals("BasicFileAttributes", k.get("a.b.LO.$0.visitFile(java.nio.file.Path,java.nio.file.attribute.BasicFileAttributes):1:attrs"));
        // hinted non-null since the JDK hints took JSpecify's nullability (2026-10-13): the JVM passes the throwable
        assertEquals("Throwable", k.get("a.b.LO.Handler.uncaughtException(Thread,Throwable):1:e"));
        // no hint (java.nio.channels has none): the library calls it back with what it likes
        assertEquals("Throwable?", k.get("a.b.LO.Completion.failed(Throwable,Void):0:exc"));

        Report java = run("a.b.LJ", source.replace("class LO", "class LJ"),
                Policy.NULL_MARKED);
        Map<String, String> j = java.verdicts().entrySet().stream()
                .collect(Collectors.toMap(e -> e.getKey().fullyQualifiedName(), e -> k(e.getValue())));
        assertEquals("IOException?", j.get("a.b.LJ.$0.postVisitDirectory(java.nio.file.Path,java.io.IOException):1:exc"));
        assertEquals("Throwable", j.get("a.b.LJ.Handler.uncaughtException(Thread,Throwable):1:e"));
    }

    @DisplayName("a parameter of an override of a @NullMarked library's method is non-null for Kotlin")
    @Test
    public void nullMarkedLibraryOverride() {
        // JUnit 6 marks its packages (package-info.class): Kotlin reads publishEntry(Map) as non-null, and an
        // override with a nullable parameter overrides nothing (fernflower's JavaCompilerArgumentsProvider)
        Report report = run("a.b.NM", """
                package a.b;
                import java.util.Map;
                import org.junit.jupiter.api.TestReporter;
                class NM {
                    static class Reporter implements TestReporter {
                        @Override
                        public void publishEntry(Map<String, String> map) { }
                    }
                }
                """, Policy.KOTLIN);
        System.out.println(explain(report));
        Map<String, String> byName = report.verdicts().entrySet().stream()
                .collect(Collectors.toMap(e -> e.getKey().fullyQualifiedName(), e -> k(e.getValue())));
        assertEquals("Map<String, String>", byName.get("a.b.NM.Reporter.publishEntry(java.util.Map):0:map"));
    }

    // langchain4j OutputGuardrailExecutor.execute overrides GuardrailExecutor's 'R execute(P)', R bounded by
    // GuardrailResult<R>: Kotlin refuses a nullable override there, so the return is non-null and the null asserted
    @DisplayName("Kotlin: an override of a bounded type variable's return stays non-null")
    @Test
    public void boundedTypeVariableOverrideReturn() {
        Report report = run("a.b.BO", """
                package a.b;
                class BO {
                    interface Result<R extends Result<R>> { boolean ok(); }
                    static final class Res implements Result<Res> { public boolean ok() { return true; } }
                    interface Executor<R extends Result<R>> { R execute(int attempts); }
                    abstract static class Base<R extends Result<R>> implements Executor<R> { }
                    static class Exec extends Base<Res> {
                        @Override
                        public Res execute(int attempts) {
                            Res result = null;
                            int attempt = 0;
                            while (attempt < attempts) {
                                result = new Res();
                                if (result.ok()) return result;
                                attempt++;
                            }
                            return result;
                        }
                    }
                    static class Plain {
                        Res find(int attempts) { return attempts > 0 ? new Res() : null; }
                    }
                }
                """, Policy.KOTLIN);
        Map<String, String> s = new java.util.TreeMap<>();
        report.verdicts().forEach((info, pt) -> s.put(info.fullyQualifiedName(), k(pt)));
        assertEquals("Res", s.get("a.b.BO.Exec.execute(int)"), s.toString());
        assertEquals("Res?", s.get("a.b.BO.Plain.find(int)"), s.toString());
    }

    // timefold DefaultExhaustiveSearchPhaseFactory (CodeLaser/maddi-mod#27 a): 'List.of(find())' throws on a null,
    // so the list holds none; an ArrayList given the same value does
    @DisplayName("an immutable collection factory stores no null element")
    @Test
    public void immutableFactoryRejectsNull() {
        Report report = run("a.b.IF", """
                package a.b;
                import java.util.ArrayList;
                import java.util.List;
                class IF {
                    static String find(int i) { return i > 0 ? "x" : null; }
                    static List<String> one(int i) { return List.of(find(i)); }
                    static List<String> two(int i) {
                        List<String> list = new ArrayList<>();
                        list.add(find(i));
                        return list;
                    }
                }
                """, Policy.KOTLIN);
        Map<String, String> s = new java.util.TreeMap<>();
        report.verdicts().forEach((info, pt) -> s.put(info.fullyQualifiedName(), k(pt)));
        assertEquals("String?", s.get("a.b.IF.find(int)"), s.toString());
        assertEquals("List<String>", s.get("a.b.IF.one(int)"), s.toString());
        assertEquals("List<String?>", s.get("a.b.IF.two(int)"), s.toString());
    }
}
