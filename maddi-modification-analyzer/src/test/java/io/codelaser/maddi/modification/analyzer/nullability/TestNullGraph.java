package io.codelaser.maddi.modification.analyzer.nullability;

import io.codelaser.maddi.cst.api.info.MethodInfo;
import io.codelaser.maddi.cst.api.type.ParameterizedType;
import org.intellij.lang.annotations.Language;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link NullGraph}: the slots of arrays, type arguments and content, how flows tie them, and the closure over them.
 */
public class TestNullGraph extends NullabilityFixtures {

    @Language("java")
    private static final String ARRAYS = """
            package a.b;
            class A {
                private String[] filled = {"a"};
                private String[] holes = new String[3];
                private static final int[][][] table = {{null, {1}}, null, {{2}}};
                void setNull(String[] a, int i) { a[i] = null; }
                void callSetNull() { String[] b = {"x"}; setNull(b, 0); }
                String get(String[] a) { return a[0]; } // nothing passes it an array holding null
                String fromHoles() { return holes[0]; }
                String fromFilled() { return filled[0]; }
                String[] fresh(int n) { return new String[n]; }
                String[] literal() { return new String[]{"a", null}; }
                void varargs(String... xs) { }
                void callVarargs() { varargs("a", null); }
                void plainVarargs(String... ys) { }
                void callPlain() { plainVarargs("a", "b"); }
                String loop(String[] zs) { for (String z : zs) { return z; } return ""; }
                void callLoop() { loop(new String[2]); }
                int[] primitive() { return new int[3]; }
            }
            """;

    @DisplayName("arrays: an element slot per array, tied along every flow of the array; new T[n] holds nulls")
    @Test
    public void arrays() {
        Report report = run("a.b.A", ARRAYS);
        System.out.println(explain(report));
        assertEquals("""
                filled: String[]
                fresh(): String?[]
                fresh(0:n): int
                fromFilled(): String
                fromHoles(): String?
                get(): String
                get(0:a): String[]
                holes: String?[]
                literal(): String?[]
                loop(): String?
                loop(0:zs): String?[]
                plainVarargs(0:ys): String[]
                primitive(): int[]
                setNull(0:a): String?[]
                setNull(1:i): int
                table: int[]?[]?[]
                varargs(0:xs): String?[]""", verdicts(report));
    }

    @Language("java")
    private static final String SUB_ARRAYS = """
            package a.b;
            class F {
                static class Set2 { }
                private int size;
                private final Set2[][] elements = new Set2[3][];
                F() { for (int i = 0; i < 3; i++) elements[i] = new Set2[0]; }
                void ensure(int index, int n) { Set2[] grown = new Set2[n]; elements[index] = grown; }
                void put(int index, int key, Set2 value) { elements[index][key] = value; }
                void removeAllFields() {
                    Set2[] arr = elements[2];
                    for (int i = arr.length - 1; i >= 0; i--) {
                        Set2 val = arr[i];
                        if (val != null) { arr[i] = null; size--; }
                    }
                }
                Set2 first(int index) {
                    Set2[] arr = elements[index];
                    Set2 one = arr[0];
                    return one;
                }
            }
            """;

    @DisplayName("an element of a field's sub-array, read through a local copy of the sub-array: nullable when the "
                 + "method nulls it, or an array allocated elsewhere has holes (fernflower SFormsFastMapDirect, 23f8bf65)")
    @Test
    public void subArrayElements() {
        Report report = run("a.b.F", SUB_ARRAYS);
        System.out.println(explain(report));
        String locals = locals(report);
        System.out.println(locals);
        assertTrue(locals.contains("removeAllFields.val: Set2?"), locals);
        assertTrue(locals.contains("first.one: Set2?"), locals);
    }

    @Language("java")
    private static final String GENERICS = """
            package a.b;
            import java.util.*;
            class G {
                static class Box<T> {
                    private T t;
                    Box(T t) { this.t = t; }
                    void set(T t) { this.t = t; }
                    T get() { return t; }
                }
                private final List<String> names = new ArrayList<>();
                private final List<String> clean = new ArrayList<>();
                private final Map<String, Integer> counts = new HashMap<>();
                void addNull(List<String> list) { list.add(null); }
                void useAddNull() { addNull(names); }
                void addClean(String s) { clean.add(s); }
                String first() { return names.get(0); }
                String firstClean() { return clean.get(0); }
                void putNull(String k) { counts.put(k, null); }
                String loop() { for (String s : names) { return s; } return ""; }
                void boxNull(Box<String> b) { b.set(null); }
                void useBox() { boxNull(new Box<>("x")); }
                String unbox(Box<String> b) { return b.get(); }
                Box<Integer> intBox() { return new Box<>(1); }
                List<String> copy() { List<String> out = new ArrayList<>(names); return out; }
                List<String> copyClean() { return new ArrayList<>(clean); }
                Optional<String> opt(String s) { return Optional.ofNullable(s); }
                <X> List<X> generic(List<X> in) { return in; }
            }
            """;

    @DisplayName("type arguments: a slot per type argument, from content links, holder fields, and invariance")
    @Test
    public void typeArguments() {
        Report report = run("a.b.G", GENERICS);
        System.out.println(explain(report));
        // 'boxNull' writes its null into its own receiver's slot, not into 'Box.set(T)': 'intBox' and 'unbox' stay
        // clean (before 2026-10-07 the null reached Box.t and from there the slot of every Box)
        assertEquals("""
                <init>(0:t): T!
                addClean(0:s): String
                addNull(0:list): List<String?>
                boxNull(0:b): Box<String?>
                clean: List<String>
                copy(): List<String?>
                copyClean(): List<String>
                counts: Map<String, Integer?>
                first(): String?
                firstClean(): String
                generic(): List<X!>
                generic(0:in): List<X!>
                get(): T!
                intBox(): Box<Integer>
                loop(): String?
                names: List<String?>
                opt(): Optional<String>
                opt(0:s): String
                putNull(0:k): String
                set(0:t): T!
                t: T!
                unbox(): String
                unbox(0:b): Box<String>""", verdicts(report));
    }

    @Language("java")
    private static final String CONTENT_WRITES = """
            package a.b;
            import java.util.*;
            class W {
                private final Map<String, String> map = new HashMap<>();
                private final List<String> fromMap = new ArrayList<>();
                private final List<String> viaLocal = new ArrayList<>();
                private final List<String> literal = new ArrayList<>();
                private final String[] slots = {"a"};
                private final List<String> lateList = new ArrayList<>();
                private String late;
                void a(String k) { fromMap.add(map.get(k)); }
                void e() { lateList.add(late); }
                private final Map<Integer, String> byLength = new HashMap<>();
                void f(String maybe) { byLength.put(maybe.length(), maybe); }
                void g() { f(null); }
                void b(String k) { String v = map.get(k); viaLocal.add(v); }
                void c() { literal.add(null); }
                void d(String k) { slots[0] = map.get(k); }
            }
            """;

    @DisplayName("Policy.assertContentWrites: a library-nullable value written into content is asserted, not spread")
    @Test
    public void assertContentWrites() {
        Report java = run("a.b.W", CONTENT_WRITES);
        assertEquals("""
                a(0:k): String
                b(0:k): String
                byLength: Map<Integer, String>
                d(0:k): String
                f(0:maybe): String?
                fromMap: List<String?>
                late: String?
                lateList: List<String?>
                literal: List<String?>
                map: Map<String, String>
                slots: String?[]
                viaLocal: List<String?>""", verdicts(java));
        // Kotlin: 'fromMap.add(map.get(k)!!)', 'viaLocal.add(v!!)', 'slots[0] = map.get(k)!!', 'lateList.add(late!!)'
        // (a field nullable only by its default value); the literal null stays. 'byLength.put(maybe.length(), maybe)':
        // the put only happens with a non-null 'maybe', dereferenced by the first argument
        Report kotlin = new NullabilityPass(Policy.KOTLIN).go(analysisOrder);
        assertEquals("""
                a(0:k): String
                b(0:k): String
                byLength: Map<Integer, String>
                d(0:k): String
                f(0:maybe): String?
                fromMap: List<String>
                late: String?
                lateList: List<String>
                literal: List<String?>
                map: Map<String, String>
                slots: String[]
                viaLocal: List<String>""", verdicts(kotlin));
    }

    @Language("java")
    private static final String COPIES = """
            package a.b;
            import java.util.*;
            class C {
                private final List<String> holes = new ArrayList<>();
                private final List<String> all = new ArrayList<>();
                private final Map<String, String> source = new HashMap<>();
                private final Map<String, String> target = new HashMap<>();
                void fill() { holes.add(null); source.put("k", null); }
                void copy() { all.addAll(holes); target.putAll(source); }
                List<String> fresh() { return new ArrayList<>(holes); }
                List<String> getHoles() { return holes; }
                void viaCall(List<String> sink) { sink.add("x"); }
                void pass() { viaCall(getHoles()); }
                String find(int i) { return i > 0 ? "x" : null; }
                String head;
                void loop() { String r; if ((r = find(1)) != null) head = r; }
                private final Map<String, Map<String, List<String>>> nested = new HashMap<>();
                void nestedNull(String a, String b) {
                    List<String> mask = nested.get(a).get(b);
                    mask.set(0, null);
                }
            }
            """;

    @DisplayName("content copies (addAll, putAll, copy constructors) and call results tie the slots")
    @Test
    public void copies() {
        Report report = run("a.b.C", COPIES, Policy.KOTLIN);
        System.out.println(explain(report));
        // 'head' by its default value (it is assigned only where 'r' is checked non-null); the local 'r' holds find's
        // nullable result (Policy.callResults), also assigned inside the condition
        assertEquals("""
                all: List<String?>
                find(): String?
                find(0:i): int
                fresh(): List<String?>
                getHoles(): List<String?>
                head: String?
                holes: List<String?>
                nested: Map<String, Map<String, List<String?>>>
                nestedNull(0:a): String
                nestedNull(1:b): String
                source: Map<String, String?>
                target: Map<String, String?>
                viaCall(0:sink): List<String?>""", verdicts(report));
    }

    @DisplayName("an analysed generic class: a null written through a receiver goes to its slot, not to 'T?'")
    @Test
    public void classTypeVariables() {
        Report report = run("a.b.Q", """
                package a.b;
                import java.util.*;
                class Q {
                    static class Stack<T> {
                        private final List<T> items = new ArrayList<>();
                        void push(T item) { items.add(item); }
                        T peek() { return items.isEmpty() ? null : items.get(items.size() - 1); }
                        void pushTwice(T item) { push(item); push(item); }
                    }
                    static class Holder<T> {
                        T value;
                        void set(T t) { value = t; }
                    }
                    private final Stack<String> names = new Stack<>();
                    private final Holder<String> self = new Holder<>();
                    void fill() { names.push(null); names.push("x"); }
                    void inner(Holder<String> h) { h.set("a"); }
                }
                """);
        System.out.println(explain(report));
        Map<String, String> byName = report.verdicts().entrySet().stream()
                .collect(Collectors.toMap(e -> e.getKey().fullyQualifiedName(), e -> k(e.getValue())));
        assertEquals("T!", byName.get("a.b.Q.Stack.push(Object):0:item"), "the null is the receiver's");
        assertEquals("Stack<String?>", byName.get("a.b.Q.names"));
        assertEquals("T?", byName.get("a.b.Q.Stack.peek()"), "a null of the class's own");
    }

    @DisplayName("super(...) and this(...) arguments carry no links: their slots are still tied to the parameter's")
    @Test
    public void explicitConstructorInvocation() {
        Report report = run("a.b.M", """
                package a.b;
                import java.util.*;
                class M {
                    static class Constant {
                        Object value;
                        Constant(Object v) { value = v; }
                        String getString() { return (String) value; }
                    }
                    static class Member {
                        protected Map<String, Integer> attributes;
                        Member(Map<String, Integer> attributes) { this.attributes = attributes; }
                    }
                    static class Field extends Member {
                        Field(Map<String, Integer> attributes) { super(attributes); }
                        static Field create(Constant c) {
                            Map<String, Integer> attributes = read(c);
                            return new Field(attributes);
                        }
                    }
                    static Map<String, Integer> read(Constant c) {
                        Map<String, Integer> attributes = new HashMap<>();
                        String name = c.getString();
                        attributes.put(name, 1);
                        return attributes;
                    }
                    static Map<String, Integer> nulls() {
                        Map<String, Integer> m = new HashMap<>();
                        m.put(null, 1);
                        return m;
                    }
                    static Field viaNulls() { return new Field(nulls()); }
                }
                """, Policy.KOTLIN);
        System.out.println(explain(report));
        Map<String, String> byName = report.verdicts().entrySet().stream()
                .collect(Collectors.toMap(e -> e.getKey().fullyQualifiedName(), e -> k(e.getValue())));
        assertEquals("Map<String?, Integer>", byName.get("a.b.M.Field.<init>(java.util.Map):0:attributes"));
        // invariance: what reaches Field's map reaches Member's, and every map passed to Field (read's too)
        assertEquals("Map<String?, Integer>", byName.get("a.b.M.Member.<init>(java.util.Map):0:attributes"));
        assertEquals("Map<String?, Integer>", byName.get("a.b.M.Member.attributes"));
        assertEquals("String", byName.get("a.b.M.Constant.getString()"));
    }

    @DisplayName("an array created and filled by the next loop has no null elements")
    @Test
    public void filledArrays() {
        Report report = run("a.b.A", """
                package a.b;
                class A {
                    static String[] filled(String[] in) {
                        String[] out = new String[in.length];
                        for (int i = 0; i < in.length; i++) { out[i] = in[i].trim(); }
                        return out;
                    }
                    static String[] byLength(int n) {
                        String[] out = new String[n];
                        for (int i = 0; i < out.length; i++) out[i] = "x" + i;
                        return out;
                    }
                    static String[] half(int n) {
                        String[] out = new String[n];
                        for (int i = 0; i < n; i += 2) { out[i] = "x"; }
                        return out;
                    }
                    static String[] conditional(int n) {
                        String[] out = new String[n];
                        for (int i = 0; i < n; i++) { if (i > 1) out[i] = "x"; }
                        return out;
                    }
                    static String[] early(int n) {
                        String[] out = new String[n];
                        for (int i = 0; i < n; i++) { if (i > 3) break; out[i] = "x"; }
                        return out;
                    }
                    static String[] later(int n) {
                        String[] out = new String[n];
                        System.out.println(n);
                        for (int i = 0; i < n; i++) { out[i] = "x"; }
                        return out;
                    }
                    static String[] assigned(int n) {
                        String[] out;
                        out = new String[n];
                        for (int i = 0; i < n; i++) { out[i] = "x"; }
                        return out;
                    }
                    static String[] counted(java.util.Collection<String> in) {
                        int n = in.size();
                        String[] out = new String[n];
                        if (n == 0) return out;
                        int k = 0;
                        for (String s : in) { String t = s.trim(); out[k++] = t; }
                        return out;
                    }
                    static String[] countedConditional(java.util.Collection<String> in) {
                        String[] out = new String[in.size()];
                        int k = 0;
                        for (String s : in) { if (s.isEmpty()) out[k++] = s; }
                        return out;
                    }
                    static String[] countedOther(java.util.Collection<String> in, java.util.List<String> other) {
                        String[] out = new String[in.size()];
                        int k = 0;
                        for (String s : other) { out[k++] = s; }
                        return out;
                    }
                    static String[] countedRead(java.util.Collection<String> in) {
                        String[] out = new String[in.size()];
                        System.out.println(out[0]);
                        int k = 0;
                        for (String s : in) { out[k++] = s; }
                        return out;
                    }
                    static class Engine {
                        private final String[] workers;
                        private final String[] idle;
                        Engine(int size) {
                            this.workers = new String[size];
                            for (int mod = 0; mod < size; ++mod) { workers[mod] = "w" + mod; }
                            idle = new String[size];
                            for (int i = 0; i < size; i++) { if (i > 0) idle[i] = "x"; }
                        }
                        String worker(int i) { return workers[i]; }
                        String idle(int i) { return idle[i]; }
                    }
                }
                """);
        System.out.println(explain(report));
        Map<String, String> byName = report.verdicts().entrySet().stream()
                .collect(Collectors.toMap(e -> e.getKey().fullyQualifiedName(), e -> k(e.getValue())));
        assertEquals("String[]", byName.get("a.b.A.filled(String[])"));
        assertEquals("String[]", byName.get("a.b.A.byLength(int)"));
        assertEquals("String?[]", byName.get("a.b.A.half(int)"), "every other element");
        assertEquals("String?[]", byName.get("a.b.A.conditional(int)"), "not a statement of the body");
        assertEquals("String?[]", byName.get("a.b.A.early(int)"), "a break");
        assertEquals("String?[]", byName.get("a.b.A.later(int)"), "not the next statement");
        // guava RegularImmutableMultiset.create: a for-each over the collection whose size made the array
        assertEquals("String[]", byName.get("a.b.A.counted(java.util.Collection)"), "one element per element");
        assertEquals("String?[]", byName.get("a.b.A.countedConditional(java.util.Collection)"), "not a statement of the body");
        assertEquals("String?[]", byName.get("a.b.A.countedOther(java.util.Collection,java.util.List)"), "another collection");
        assertEquals("String?[]", byName.get("a.b.A.countedRead(java.util.Collection)"), "read before the loop");
        // CodeLaser/maddi-mod#22 gap 4: the creation is an assignment, to a local declared before or to a field in the constructor
        assertEquals("String[]", byName.get("a.b.A.assigned(int)"), "assigned after its declaration");
        assertEquals("String[]", byName.get("a.b.A.Engine.workers"), "a field the constructor's loop fills");
        assertEquals("String", byName.get("a.b.A.Engine.worker(int)"));
        assertEquals("String?[]", byName.get("a.b.A.Engine.idle"), "not a statement of the body");
    }

    @DisplayName("a reassigned parameter: the parameter is the caller's value, what the body assigns is a shadow (issue #22 gap 5)")
    @Test
    public void reassignedParameter() {
        Report report = run("a.b.R", """
                package a.b;
                class R {
                    static String decode(String s) { return s.isEmpty() ? null : s; }
                    String incoming(String value) { value = decode(value.trim()); return value; }
                    String loop(String p) { while (p.length() > 3) { p = decode(p); } return p; }
                    String constant(String q) { q = "x"; return q; }
                    String conditional(String r) { if (r.isEmpty()) r = decode(r); return r; }
                    int length(String t) { int n = t.length(); t = decode(t); return n; }
                }
                """);
        System.out.println(explain(report));
        Map<String, String> byName = report.verdicts().entrySet().stream()
                .collect(Collectors.toMap(e -> e.getKey().fullyQualifiedName(), e -> k(e.getValue())));
        // the parameters: no caller passes null, and the body's assignments do not count
        for (String m : new String[]{"incoming(String):0:value", "loop(String):0:p", "constant(String):0:q",
                "conditional(String):0:r", "length(String):0:t"}) {
            assertEquals("String", byName.get("a.b.R." + m), m);
        }
        // the returns read the shadow, which holds the caller's value and decode's null
        assertEquals("String?", byName.get("a.b.R.incoming(String)"));
        // the loop's condition dereferences p on every way out: 'return p' returns no null
        assertEquals("String", byName.get("a.b.R.loop(String)"));
        assertEquals("String", byName.get("a.b.R.constant(String)"));
        assertEquals("String?", byName.get("a.b.R.conditional(String)"));
        Map<String, String> shadows = new java.util.TreeMap<>();
        for (MethodInfo mi : report.verdicts().keySet().stream().filter(i -> i instanceof MethodInfo)
                .map(i -> (MethodInfo) i).toList()) {
            for (io.codelaser.maddi.cst.api.info.ParameterInfo pi : mi.parameters()) {
                ParameterizedType shadow = report.reassigned(pi);
                if (shadow != null) shadows.put(mi.name(), k(shadow));
            }
        }
        assertEquals("{conditional=String?, constant=String, incoming=String?, length=String?, loop=String?}",
                shadows.toString());
    }

    // CodeLaser/maddi-mod#49: diagnostics splits results by what reaches them, a written null or a contract only
    @DisplayName("Report.reachedByWrittenNull: the strict closure, under a policy that does not assert content writes")
    @Test
    public void reachedByWrittenNull() {
        Report report = run("a.b.WR", """
                package a.b;
                import java.util.Map;
                class WR {
                    static String written() { return null; }
                    static String byContract(Map<String, String> m, String k) { return m.get(k); }
                    static String both(Map<String, String> m, String k, boolean b) { return b ? null : m.get(k); }
                    static String through(Map<String, String> m, String k) { return byContract(m, k); }
                    static String none() { return "x"; }
                }
                """, Policy.NULL_MARKED);
        Map<String, String> s = new java.util.TreeMap<>();
        for (MethodInfo mi : parsed.methods()) {
            String reached = k(report.verdicts().get(mi)).endsWith("?") ? "reached" : "-";
            s.put(mi.name(), reached + (report.reachedByWrittenNull(mi) ? "/written" : ""));
        }
        assertEquals("{both=reached/written, byContract=reached, none=-, through=-, written=reached/written}",
                s.toString());
    }

    /*
     Kotlin: guava's slot hubs (worktree-maddi-all 2026-10-17, ~150 mismatches: Multiset.entrySet():
     MutableSet<Entry<E>?>?, Table.rowMap(), Multimap.asMap() type arguments). JoinerTest's map with null values
     reached Joiner.MapJoiner.join(Map<?, ?>)'s slots, flowed back from that unbound wildcard into another caller's
     argument, 'ImmutableMap.of()', and through '(ImmutableMap<K, V>) RegularImmutableMap.EMPTY' into every
     collection sharing EMPTY. A '?' slot only receives, as '? extends' does; an unchecked conversion from Object
     type arguments starts a new instantiation, not one slot.
     */
    @DisplayName("Kotlin: a read-only array parameter's element nulls do not flow back into the caller's array")
    @Test
    public void readOnlyArrayParameter() {
        Report report = run("a.b.RA", """
                package a.b;
                class RA {
                    static String join(Object[] parts) {
                        StringBuilder sb = new StringBuilder();
                        for (Object p : parts) sb.append(p);
                        return sb.toString();
                    }
                    static String nulls() { return join(new Object[]{"a", null}); }
                    static String bounds(String[] upperBounds) { return join(upperBounds); }
                    static void fill(Object[] out) { out[0] = null; }
                    static String[] written() { String[] w = new String[]{"x"}; fill(w); return w; }
                    static Object[] keep(Object[] held) { join(held); return held; }
                    static Object[] kept() { return keep(new Object[]{null}); }
                    static Object[] passKept(String[] names) { return keep(names); }
                    static String joinIterable(Iterable<?> parts) { return parts.toString(); }
                    @java.lang.annotation.Target(java.lang.annotation.ElementType.TYPE_USE) @interface Nullable {}
                    static String joinAnnotated(@Nullable Object[] parts) { return java.util.Arrays.toString(parts); }
                    static String annotatedBounds(String[] bounds) { return joinAnnotated(bounds); }
                    static String joinList(Object[] parts) {
                        java.util.List<?> partsList = java.util.Arrays.<Object>asList(parts);
                        return joinIterable(partsList);
                    }
                    static String listed(String[] upper) { return joinList(upper); }
                    static Runnable capture(Object[] hashers) { return () -> join(hashers); }
                    static Runnable captured(String[] fns) { return capture(fns); }
                    static Runnable nullHashers() { return capture(new Object[]{null}); }
                }
                """, Policy.KOTLIN);
        Map<String, String> s = new java.util.TreeMap<>();
        report.verdicts().forEach((info, pt) -> s.put(info.fullyQualifiedName(), k(pt)));
        assertEquals("Object?[]", s.get("a.b.RA.join(Object[]):0:parts"), s.toString());
        assertEquals("String[]", s.get("a.b.RA.bounds(String[]):0:upperBounds"), s.toString());
        assertEquals("String[]", s.get("a.b.RA.listed(String[]):0:upper"), "Arrays.asList reads: " + s);
        assertEquals("String[]", s.get("a.b.RA.annotatedBounds(String[]):0:bounds"), "Joiner's shape: " + s);
        // a String[] into an Object[] is cast by the printer (covariantArrayArgument) whatever the elements' states:
        // the parameter's nulls (nullHashers, kept) are never in the caller's array (narrowedArray, 2026-10-27)
        assertEquals("String[]", s.get("a.b.RA.captured(String[]):0:fns"), "captured, cast: " + s);
        assertEquals("String?[]", s.get("a.b.RA.written()"), "written through: still both ways " + s);
        // still tied: kept()'s '{null}' reaches held by the links both ways, which writtenThrough counts as a write
        assertEquals("String?[]", s.get("a.b.RA.passKept(String[]):0:names"), "returned: " + s);
    }

    @DisplayName("Kotlin: a varargs parameter takes a copy: its elements do not flow back into the caller's array")
    @Test
    public void varargsCopy() {
        Report report = run("a.b.VC", """
                package a.b;
                class VC {
                    @java.lang.annotation.Target(java.lang.annotation.ElementType.TYPE_USE) @interface Nullable {}
                    static Object construct(@Nullable Object... elements) { return elements.length; }
                    static Object copyOf(Object[] elements) { return construct(elements.clone()); }
                    static Object bounds(String[] bounds) { return copyOf(bounds); }
                    static Object make(String... arguments) { return arguments.length; }
                    static Object defensive() {
                        String[] typesIn = {"a", "b"};
                        Object p = make(typesIn);
                        typesIn[0] = null;
                        return p;
                    }
                    static Object caller(String[] upper) { return make(upper); }
                    static Object inner(String... whitelist) { return whitelist.length; }
                    static Object outer(String... whitelist) { return inner(whitelist); }
                    static Object nullOne() { return outer("a", null); }
                    abstract static class Builder { abstract Builder add(String... elements); }
                    static final class ListBuilder extends Builder {
                        @Override Builder add(String... elements) { return this; }
                    }
                    static Object useBuilder(Builder b) { return b.add("x", null); }
                }
                """, Policy.KOTLIN);
        Map<String, String> s = new java.util.TreeMap<>();
        report.verdicts().forEach((info, pt) -> s.put(info.fullyQualifiedName(), k(pt)));
        assertEquals("String[]", s.get("a.b.VC.bounds(String[]):0:bounds"), s + "\n" + explain(report));
        assertEquals("String[]", s.get("a.b.VC.caller(String[]):0:upper"), s.toString());
        // forward, the caller's elements are the varargs': fernflower's 'isVarReferenced(obj, *whitelist)'
        assertEquals("String?[]", s.get("a.b.VC.inner(String[]):0:whitelist"), s.toString());
        // an override family's varargs stay one state
        assertEquals(s.get("a.b.VC.Builder.add(String[]):0:elements"),
                s.get("a.b.VC.ListBuilder.add(String[]):0:elements"), s.toString());
        assertEquals("String?[]", s.get("a.b.VC.ListBuilder.add(String[]):0:elements"), s.toString());
    }

    @DisplayName("Kotlin: guava's Joiner/WildcardCapturer array shape")
    @Test
    public void joinerShape() {
        Report report = run("a.b.JS", """
                package a.b;
                import java.lang.annotation.ElementType;
                import java.lang.annotation.Target;
                import java.util.Arrays;
                import java.util.Iterator;
                import java.util.List;
                class JS {
                    @Target(ElementType.TYPE_USE) @interface Nullable {}
                    static class Joiner {
                        static Joiner on(char c) { return new Joiner(); }
                        String join(Iterable<? extends @Nullable Object> parts) { return join(parts.iterator()); }
                        String join(Iterator<? extends @Nullable Object> parts) { return parts.toString(); }
                        final String join(@Nullable Object[] parts) {
                            List<?> partsList = Arrays.<@Nullable Object>asList(parts);
                            return join(partsList);
                        }
                        Joiner useForNull(String s) { return new Joiner() { @Override String join(Iterator<? extends @Nullable Object> p) { return s; } }; }
                    }
                    static class Capturer {
                        Object capture(String[] upperBounds) {
                            String name = "capture#" + Joiner.on('&').join(upperBounds);
                            return make(name, upperBounds);
                        }
                        static Object make(String name, String[] bounds) { return name + bounds.length; }
                    }
                    static Capturer sub() { return new Capturer() { @Override Object capture(String[] upperBounds) { return upperBounds; } }; }
                    static String callerBuilt(Object x, Object y) { return Joiner.on(',').join(new Object[]{x, y}); }
                }
                """, Policy.KOTLIN);
        Map<String, String> s = new java.util.TreeMap<>();
        report.verdicts().forEach((info, pt) -> s.put(info.fullyQualifiedName(), k(pt)));
        assertEquals("String[]", s.get("a.b.JS.Capturer.capture(String[]):0:upperBounds"), s + "\n" + explain(report));
    }

    @DisplayName("Kotlin: an argument to a method's own type variable instantiates it")
    @Test
    public void methodTypeVariableArgument() {
        Report report = run("a.b.MT", """
                package a.b;
                class MT {
                    interface Entry<E> { E getElement(); }
                    static final class ImmutableEntry<E> implements Entry<E> {
                        private final E element;
                        ImmutableEntry(E element) { this.element = element; }
                        public E getElement() { return element; }
                    }
                    static <E> Entry<E> immutableEntry(E e) { return new ImmutableEntry<>(e); }
                    static <T> T id(T t) { return t; }
                    static Entry<String> nullKey() { return immutableEntry(null); }
                    static String nothing() { return id(null); }
                    static <T> T ensureNotNull(T object, String name) {
                        if (object == null) throw new IllegalArgumentException(name);
                        return object;
                    }
                    static <T> T getOrDefault(T value, T defaultValue) { return value != null ? value : defaultValue; }
                    static String checked(String x) { return ensureNotNull(x, "x"); }
                    static String unchecked() { return checked(null); }
                    static String withDefault() { return getOrDefault(null, "d"); }
                }
                """, Policy.KOTLIN);
        Map<String, String> s = new java.util.TreeMap<>();
        report.verdicts().forEach((info, pt) -> s.put(info.fullyQualifiedName(), k(pt)));
        assertEquals("E!", s.get("a.b.MT.Entry.getElement()"), s + "\n" + explain(report));
        assertEquals("E!", s.get("a.b.MT.ImmutableEntry.element"), s.toString());
        assertEquals("String?", s.get("a.b.MT.nothing()"), "the call's own result still carries it: " + s);
        assertEquals("Entry<String?>", s.get("a.b.MT.nullKey()"), "Kotlin infers Entry<String?>: " + s);
        // a parameter the method checks is declared T?: the null goes there, the result keeps T
        assertEquals("T?", s.get("a.b.MT.ensureNotNull(Object,String):0:object"), s.toString());
        assertEquals("String", s.get("a.b.MT.checked(String)"), s.toString());
        assertEquals("T?", s.get("a.b.MT.getOrDefault(Object,Object):0:value"), s.toString());
        assertEquals("String", s.get("a.b.MT.withDefault()"), s.toString());
    }

    @DisplayName("Kotlin: a generic method's T[] result has the elements of the T[] it is given, guava's ObjectArrays.concat")
    @Test
    public void arrayResultElements() {
        Report report = run("a.b.GC", """
                package a.b;
                import java.util.Arrays;
                class GC {
                    @java.lang.annotation.Target(java.lang.annotation.ElementType.TYPE_USE) @interface Nullable {}
                    static <T extends @Nullable Object> T[] newArray(T[] reference, int length) {
                        T[] empty = reference.length == 0 ? reference : Arrays.copyOf(reference, 0);
                        return Arrays.copyOf(empty, length);
                    }
                    static <T extends @Nullable Object> T[] concat(T element, T[] array) {
                        T[] result = newArray(array, array.length + 1);
                        result[0] = element;
                        System.arraycopy(array, 0, result, 1, array.length);
                        return result;
                    }
                    static String[] use() { return concat("a", new String[]{"b", null}); }
                    static final class Set {
                        private static final Object[] EMPTY_ARRAY = new Object[0];
                        static final Set EMPTY = new Set(EMPTY_ARRAY, EMPTY_ARRAY);
                        final Object[] elements;
                        final @Nullable Object[] table;
                        Set(Object[] elements, @Nullable Object[] table) { this.elements = elements; this.table = table; }
                        static Set of(Object a) { Object[] t = new Object[2]; t[1] = a; return new Set(new Object[]{a}, t); }
                    }
                }
                """, Policy.KOTLIN);
        Map<String, String> s = new java.util.TreeMap<>();
        report.verdicts().forEach((info, pt) -> s.put(info.fullyQualifiedName(), k(pt)));
        String all = s + "\n" + explain(report);
        // the result's elements are the argument array's, as Kotlin infers T from it
        assertEquals("T?[]", s.get("a.b.GC.concat(Object,Object[]):1:array"), all);
        assertEquals("T?[]", s.get("a.b.GC.concat(Object,Object[])"), all);
    }

    @DisplayName("Kotlin: a list made from an array with null elements has nullable elements, guava's partitionImpl")
    @Test
    public void partitionList() {
        Report report = run("a.b.PT", """
                package a.b;
                import java.util.Arrays;
                import java.util.Collections;
                import java.util.Iterator;
                import java.util.List;
                class PT {
                    @java.lang.annotation.Target(java.lang.annotation.ElementType.TYPE_USE) @interface Nullable {}
                    static <T extends @Nullable Object> List<@Nullable T> next(Iterator<T> iterator, int size) {
                        @SuppressWarnings("unchecked")
                        @Nullable T[] array = (@Nullable T[]) new Object[size];
                        int count = 0;
                        for (; count < size && iterator.hasNext(); count++) {
                            array[count] = iterator.next();
                        }
                        for (int i = count; i < size; i++) {
                            array[i] = null;
                        }
                        List<@Nullable T> list = Collections.unmodifiableList(Arrays.asList(array));
                        return list;
                    }
                    static <E> List<E> copyOf(E[] elements) { return Collections.unmodifiableList(Arrays.asList(elements)); }
                    static List<String> nulls() { return copyOf(new String[]{"a", null}); }
                    static final class Holder {
                        final List<String> modes;
                        Holder(String... modes) { this.modes = copyOf(modes); }
                    }
                }
                """, Policy.KOTLIN);
        Map<String, String> s = new java.util.TreeMap<>();
        report.verdicts().forEach((info, pt) -> s.put(info.fullyQualifiedName(), k(pt)));
        Map<String, String> l = new java.util.TreeMap<>();
        report.locals().forEach((local, pt) -> l.put(local.name(), k(pt)));
        assertEquals("List<T?>", l.get("list"), l + "\n" + explain(report));
        // a parameter's array: the callers' nulls belong to their instantiations
        assertEquals("List<E!>", s.get("a.b.PT.copyOf(Object[])"), s + "\n" + explain(report));
        // nor through an analysed generic method, whose elements parameter holds every caller's array
        assertEquals("List<String>", s.get("a.b.PT.Holder.modes"), s + "\n" + explain(report));
    }

    @DisplayName("Kotlin: a double-brace map's puts are the new map's values, nacos RequestMeta.extractAppLabels")
    @Test
    public void doubleBraceMap() {
        Report report = run("a.b.DB", """
                package a.b;
                import java.util.HashMap;
                import java.util.Map;
                class DB {
                    private Map<String, String> labels = new HashMap<>();
                    private String clientVersion;
                    private Map<String, String> appLabels = new HashMap<>();
                    void setClientVersion(String v) { clientVersion = v; }
                    void extract() {
                        HashMap<String, String> map = new HashMap<String, String>(8) {
                            {
                                put("app", labels.get("app"));
                                put("version", clientVersion);
                            }
                        };
                        this.appLabels = map;
                    }
                    Map<String, String> getAppLabels() { return appLabels; }
                    static void use(DB db) { db.setClientVersion(null); }
                }
                """, Policy.KOTLIN);
        Map<String, String> s = new java.util.TreeMap<>();
        report.verdicts().forEach((info, pt) -> s.put(info.fullyQualifiedName(), k(pt)));
        Map<String, String> l = new java.util.TreeMap<>();
        report.locals().forEach((local, pt) -> l.put(local.name(), k(pt)));
        assertTrue(l.get("map").endsWith("String?>"), l + "\n" + s + "\n" + explain(report));
        assertTrue(s.get("a.b.DB.appLabels").endsWith("String?>"), s + "\n" + explain(report));
    }

    @DisplayName("Kotlin: a field replaced by a parameter shares its nested slots, nacos NamingMetadataManager:207")
    @Test
    public void fieldReplacedByParameter() {
        Report report = run("a.b.FR", """
                package a.b;
                import java.util.concurrent.ConcurrentHashMap;
                import java.util.concurrent.ConcurrentMap;
                class FR {
                    private ConcurrentMap<String, ConcurrentMap<String, Object>> map = new ConcurrentHashMap<>();
                    void update(String service, String key, Object value) {
                        map.computeIfAbsent(service, k -> new ConcurrentHashMap<>()).put(key, value);
                    }
                    void load(ConcurrentMap<String, ConcurrentMap<String, Object>> snapshot) {
                        ConcurrentMap<String, ConcurrentMap<String, Object>> old = map;
                        map = snapshot;
                        old.clear();
                    }
                    static void use(FR fr) { fr.update("s", null, "v"); }
                    static void loader(FR fr) { fr.load(new ConcurrentHashMap<>()); }
                }
                """, Policy.KOTLIN);
        Map<String, String> s = new java.util.TreeMap<>();
        report.verdicts().forEach((info, pt) -> s.put(info.fullyQualifiedName(), k(pt)));
        String field = s.get("a.b.FR.map");
        String parameter = s.get("a.b.FR.load(java.util.concurrent.ConcurrentMap):0:snapshot");
        assertEquals(field, parameter, s + "\n" + explain(report));
    }

    @DisplayName("Kotlin: a map's views share its slots, nacos ServiceManager.getAllNamespaces, Header.iterator")
    @Test
    public void mapViews() {
        Report report = run("a.b.MV", """
                package a.b;
                import java.util.Iterator;
                import java.util.Map;
                import java.util.Set;
                import java.util.TreeMap;
                import java.util.concurrent.ConcurrentHashMap;
                class MV {
                    private final ConcurrentHashMap<String, Object> byNamespace = new ConcurrentHashMap<>();
                    private final Map<String, String> header = new TreeMap<>();
                    void add(String namespace, String key, String value) {
                        byNamespace.put(namespace, value);
                        header.put(key, value);
                    }
                    Set<String> namespaces() { return byNamespace.keySet(); }
                    Iterator<Map.Entry<String, String>> iterator() { return header.entrySet().iterator(); }
                    static void use(MV mv) { mv.add(null, null, "v"); }
                }
                """, Policy.KOTLIN);
        Map<String, String> s = new java.util.TreeMap<>();
        report.verdicts().forEach((info, pt) -> s.put(info.fullyQualifiedName(), k(pt)));
        assertEquals("Set<String?>", s.get("a.b.MV.namespaces()"), s + "\n" + explain(report));
        assertEquals("Iterator<Entry<String?, String>>", s.get("a.b.MV.iterator()"), s + "\n" + explain(report));
    }

    @DisplayName("Kotlin: a field initialized with another field or a method's array takes its element nullability, jenkins CrontabLexer, DomainValidator")
    @Test
    public void fieldInitializerContent() {
        Report report = run("a.b.FC", """
                package a.b;
                class FC {
                    static final String[] EMPTY = new String[0];
                    private static final String[] NAMES = makeNames();
                    private String[] plus = EMPTY;
                    static String[] makeNames() {
                        return new String[]{null, "'-'", "'+'"};
                    }
                    static String[] intern(String[] input) {
                        if (input.length == 0) return EMPTY;
                        input[0] = input[0].isEmpty() ? null : input[0];
                        return input;
                    }
                    int count() {
                        return NAMES.length + plus.length;
                    }
                }
                """, Policy.KOTLIN);
        Map<String, String> s = new java.util.TreeMap<>();
        report.verdicts().forEach((info, pt) -> s.put(info.fullyQualifiedName(), k(pt)));
        assertEquals("String?[]", s.get("a.b.FC.NAMES"), s + "\n" + explain(report));
        assertEquals("String?[]", s.get("a.b.FC.plus"), s + "\n" + explain(report));
    }

    @DisplayName("Kotlin: a null into a non-modifying abstract consumer on a Funnel<?> stays with the receiver, guava FunnelsTest")
    @Test
    public void nullIntoWildcardConsumer() {
        Report report = run("a.b.WC", """
                package a.b;
                class WC {
                    interface Funnel<T> {
                        void funnel(T from, StringBuilder into);
                    }
                    static final class LongFunnel implements Funnel<Long> {
                        @Override
                        public void funnel(Long value, StringBuilder into) {
                            into.append(value.longValue());
                        }
                    }
                    static void assertNullsThrow(Funnel<?> funnel) {
                        try {
                            funnel.funnel(null, new StringBuilder());
                        } catch (NullPointerException ok) {
                        }
                    }
                    static void use() {
                        assertNullsThrow(new LongFunnel());
                    }
                }
                """, Policy.KOTLIN);
        Map<String, String> s = new java.util.TreeMap<>();
        report.verdicts().forEach((info, pt) -> s.put(info.fullyQualifiedName(), k(pt)));
        assertEquals("Long", s.get("a.b.WC.LongFunnel.funnel(Long,StringBuilder):0:value"), s + "\n" + explain(report));
    }

    @DisplayName("Kotlin: a null into a non-modifying abstract consumer on an Ordering<@Nullable Object> belongs to the receiver, guava OrderingTest")
    @Test
    public void nullIntoAbstractConsumer() {
        Report report = run("a.b.AC", """
                package a.b;
                import io.codelaser.maddi.annotation.NotModified;
                import java.util.Map;
                class AC {
                    static abstract class Ordering<T> {
                        @NotModified
                        public abstract int compare(T left, T right);
                    }
                    static final class Incomparable extends RuntimeException {
                        final Object value;
                        Incomparable(Object value) {
                            super("cannot compare " + value);
                            this.value = value;
                        }
                    }
                    static final class Explicit<T> extends Ordering<T> {
                        private final Map<T, Integer> ranks;
                        Explicit(Map<T, Integer> ranks) {
                            this.ranks = ranks;
                        }
                        @Override
                        public int compare(T left, T right) {
                            return rank(left) - rank(right);
                        }
                        private int rank(T value) {
                            Integer rank = ranks.get(value);
                            if (rank == null) {
                                throw new Incomparable(value);
                            }
                            return rank;
                        }
                    }
                    static int allEqual(Ordering<Object> comparator) {
                        return comparator.compare(null, null);
                    }
                    static int use(Map<String, Integer> ranks) {
                        return new Explicit<>(ranks).compare("a", "b");
                    }
                }
                """, Policy.KOTLIN);
        Map<String, String> s = new java.util.TreeMap<>();
        report.verdicts().forEach((info, pt) -> s.put(info.fullyQualifiedName(), k(pt)));
        String context = s + "\n" + explain(report);
        // the null is the receiver's: an Ordering<Object?>
        assertEquals("Ordering<Object?>", s.get("a.b.AC.allEqual(a.b.AC.Ordering):0:comparator"), context);
        // not every override's, whose parameter Kotlin types as the declaration's T anyway: before, the null flowed on
        // from Explicit's clamped 'compare(left: T)' into 'rank(value: T?)' and 'Incomparable(value: Any?)'
        assertEquals("T!", s.get("a.b.AC.Explicit.compare(Object,Object):0:left"), context);
        assertEquals("T!", s.get("a.b.AC.Explicit.rank(Object):0:value"), context);
        assertEquals("Object", s.get("a.b.AC.Incomparable.<init>(Object):0:value"), context);
    }

    @DisplayName("Kotlin: a library view passed to an analysed method shares the receiver's slots, jenkins CLIAction")
    @Test
    public void viewArgumentToMethod() {
        Report report = run("a.b.VA", """
                package a.b;
                import java.util.ArrayList;
                import java.util.List;
                class VA {
                    private final List<String> args = new ArrayList<>();
                    void onArg(String text) {
                        args.add(text);
                    }
                    int run() {
                        return main(args.subList(1, args.size()));
                    }
                    int main(List<String> args) {
                        return args.size();
                    }
                    static void use(VA va) {
                        va.onArg(null);
                    }
                }
                """, Policy.KOTLIN);
        Map<String, String> s = new java.util.TreeMap<>();
        report.verdicts().forEach((info, pt) -> s.put(info.fullyQualifiedName(), k(pt)));
        assertEquals("List<String?>", s.get("a.b.VA.args"), s + "\n" + explain(report));
        assertEquals("List<String?>", s.get("a.b.VA.main(java.util.List):0:args"), s + "\n" + explain(report));
    }

    @DisplayName("Kotlin: a generic method's result slot that is not a type variable flows into the target, jenkins User.getProperties")
    @Test
    public void genericResultSlot() {
        Report report = run("a.b.GR", """
                package a.b;
                import java.util.ArrayList;
                import java.util.LinkedHashMap;
                import java.util.List;
                import java.util.Map;
                class GR {
                    static class Desc<T> {
                    }
                    interface Describable<T extends Describable<T>> {
                        Desc<T> getDescriptor();
                    }
                    static class Prop implements Describable<Prop> {
                        @Override
                        public Desc<Prop> getDescriptor() {
                            return null;
                        }
                    }
                    static <T extends Describable<T>> Map<Desc<T>, T> toMap(Iterable<T> describables) {
                        Map<Desc<T>, T> m = new LinkedHashMap<>();
                        for (T d : describables) {
                            Desc<T> descriptor = d.getDescriptor();
                            m.put(descriptor, d);
                        }
                        return m;
                    }
                    List<Prop> properties = new ArrayList<>();
                    Map<Desc<Prop>, Prop> getProperties() {
                        return toMap(properties);
                    }
                }
                """, Policy.KOTLIN);
        Map<String, String> s = new java.util.TreeMap<>();
        report.verdicts().forEach((info, pt) -> s.put(info.fullyQualifiedName(), k(pt)));
        assertEquals("Map<Desc<Prop>?, Prop>", s.get("a.b.GR.getProperties()"), s + "\n" + explain(report));
    }

    @DisplayName("Kotlin: a private class writing null into its generic superclass's slots has a nullable supertype argument, jenkins OwnerContext")
    @Test
    public void supertypeSlots() {
        Report report = run("a.b.SS", """
                package a.b;
                import java.util.HashMap;
                import java.util.LinkedList;
                import java.util.Map;
                class SS {
                    private static class OwnerContext extends LinkedList<String> {
                        void startVisiting(String owner) {
                            if (owner != null) {
                                System.out.println(owner);
                            }
                            addFirst(owner);
                        }
                        static void use(OwnerContext c) {
                            c.startVisiting(null);
                        }
                    }
                    abstract static class Base<T> {
                        final Map<String, T> data = new HashMap<>();
                        Map<String, T> getData() {
                            return data;
                        }
                        abstract T get(String key);
                    }
                    static class Space {
                    }
                    private static class Monitor extends Base<Space> {
                        @Override
                        Space get(String key) {
                            return key.isEmpty() ? null : new Space();
                        }
                        Map<String, Space> monitor() {
                            Map<String, Space> data = getData();
                            for (Map.Entry<String, Space> e : data.entrySet()) {
                                Space d = e.getValue();
                                if (d == null) {
                                    e.setValue(d = get(e.getKey()));
                                }
                            }
                            return data;
                        }
                    }
                    // not private: its superclass is its type elsewhere too
                    static class Shared extends LinkedList<String> {
                        void add(String s, int n) {
                            addFirst(s);
                        }
                        static void use(Shared s) {
                            s.add(null, 1);
                        }
                    }
                    private static class Plain extends LinkedList<String> {
                        void add2(String s) {
                            addFirst(s);
                        }
                    }
                }
                """, Policy.KOTLIN);
        Map<String, String> p = new java.util.TreeMap<>();
        report.parentClasses().forEach((type, pt) -> p.put(type.fullyQualifiedName(), k(pt)));
        assertEquals("LinkedList<String?>", p.get("a.b.SS.OwnerContext"), p + "\n" + explain(report));
        Map<String, String> l = new java.util.TreeMap<>();
        report.locals().forEach((local, pt) -> l.put(local.name(), k(pt)));
        // a write through a view loop's variable stays there (NOVIEWLOOPWRITES): the map and the superclass keep Space
        assertFalse(p.containsKey("a.b.SS.Monitor"), p + "\n" + l + "\n" + explain(report));
        assertEquals("Entry<String, Space?>", l.get("e"), l + "\n" + explain(report));
        assertEquals("Map<String, Space>", l.get("data"), l + "\n" + explain(report));
        assertFalse(p.containsKey("a.b.SS.Plain"), p + "\n" + explain(report));
        assertFalse(p.containsKey("a.b.SS.Shared"), p + "\n" + explain(report));
    }

    @DisplayName("Kotlin: no null through a primitive return; a '?' argument's generic result is nullable, guava's Functions/AbstractFuture")
    @Test
    public void primitiveCutAndStarResult() {
        Report report = run("a.b.PC", """
                package a.b;
                import java.util.concurrent.Future;
                class PC {
                    interface Predicate<T> { boolean apply(T t); }
                    interface Function<F, T> { T apply(F f); }
                    static final class Mock implements Predicate<String> {
                        java.util.Map<String, Boolean> expected = new java.util.HashMap<>();
                        public boolean apply(String s) { return expected.get(s); }
                    }
                    static final class PredicateFunction<T> implements Function<T, Boolean> {
                        final Predicate<T> predicate;
                        PredicateFunction(Predicate<T> predicate) { this.predicate = predicate; }
                        public Boolean apply(T t) { return predicate.apply(t); }
                    }
                    static <V> V get(Future<V> future) throws Exception { return future.get(); }
                    static Object value(Future<?> future) throws Exception {
                        Object v = get(future);
                        return v == null ? "NULL" : v;
                    }
                }
                """, Policy.KOTLIN);
        Map<String, String> s = new java.util.TreeMap<>();
        report.verdicts().forEach((info, pt) -> s.put(info.fullyQualifiedName(), k(pt)));
        String all = s + "\n" + explain(report);
        assertFalse(s.get("a.b.PC.PredicateFunction.apply(Object)").endsWith("?"), all);
        Map<String, String> l = new java.util.TreeMap<>();
        report.locals().forEach((local, pt) -> l.put(local.name(), k(pt)));
        assertTrue(l.get("v").endsWith("?"), l + "\n" + all);
    }

    @DisplayName("Kotlin: a null into a parameter declared nullable leaves the receiver's type argument, guava's Converter")
    @Test
    public void nullableParameterReceiverSlot() {
        Report report = run("a.b.CV", """
                package a.b;
                class CV {
                    @java.lang.annotation.Target(java.lang.annotation.ElementType.TYPE_USE) @interface Nullable {}
                    abstract static class Converter<A, B> {
                        abstract B doForward(A a);
                        final @Nullable B convert(@Nullable A a) { return a == null ? null : doForward(a); }
                    }
                    static final class IntConverter extends Converter<String, Integer> {
                        static final Converter<String, Integer> INSTANCE = new IntConverter();
                        Integer doForward(String s) { return Integer.valueOf(s); }
                    }
                    static Converter<String, Integer> stringConverter() { return IntConverter.INSTANCE; }
                    static Integer nulls() { return stringConverter().convert(null); }
                    interface Predicate<T> { boolean apply(T input); }
                    abstract static class Equivalence<T> {
                        abstract boolean doEquivalent(T a, T b);
                        final Predicate<@Nullable T> equivalentTo(@Nullable T target) { return input -> target == input; }
                    }
                    static final class Equals extends Equivalence<Object> {
                        static final Equals INSTANCE = new Equals();
                        boolean doEquivalent(Object a, Object b) { return a.equals(b); }
                    }
                    static Equivalence<Object> equals() { return Equals.INSTANCE; }
                    static boolean test() {
                        Predicate<@Nullable Object> equalTo1 = equals().equivalentTo("1");
                        return equalTo1.apply(null);
                    }
                }
                """, Policy.KOTLIN);
        Map<String, String> s = new java.util.TreeMap<>();
        report.verdicts().forEach((info, pt) -> s.put(info.fullyQualifiedName(), k(pt)));
        String all = s + "\n" + explain(report);
        assertFalse(s.get("a.b.CV.IntConverter.INSTANCE").contains("String?"), all);
        assertFalse(s.get("a.b.CV.stringConverter()").contains("String?"), all);
        // a result position declared nullable ties nothing back into the receiver
        assertFalse(s.get("a.b.CV.equals()").contains("Object?"), all);
    }

    @DisplayName("Kotlin: a caller's null into an abstract type-variable parameter is its receiver's instantiation's")
    @Test
    public void consumerReceiverSlot() {
        Report report = run("a.b.CS", """
                package a.b;
                class CS {
                    interface Predicate<T> { boolean apply(T input); }
                    static final class Edges implements Predicate<String> {
                        String last = "";
                        public boolean apply(String edge) { last = edge; return edge.isEmpty(); }
                    }
                    static boolean test(Predicate<? super Integer> p) { return p.apply(null); }
                }
                """, Policy.KOTLIN);
        Map<String, String> s = new java.util.TreeMap<>();
        report.verdicts().forEach((info, pt) -> s.put(info.fullyQualifiedName(), k(pt)));
        assertEquals("String", s.get("a.b.CS.Edges.apply(String):0:edge"), s.toString());
        assertEquals("String", s.get("a.b.CS.Edges.last"), s.toString());
    }

    @DisplayName("Kotlin: an Object-typed singleton's slots hold nothing")
    @Test
    public void erasedSingletonSlots() {
        Report report = run("a.b.ES", """
                package a.b;
                import java.util.HashMap;
                import java.util.Map;
                class ES {
                    static final Map<Object, Object> EMPTY = new HashMap<>();
                    static Map<Object, Object> nulls() {
                        Map<Object, Object> m = EMPTY;
                        m.put(null, "x");
                        return m;
                    }
                    static Map<Object, Object> other() {
                        Map<Object, Object> o = EMPTY;
                        return o;
                    }
                }
                """, Policy.KOTLIN);
        Map<String, String> s = new java.util.TreeMap<>();
        report.verdicts().forEach((info, pt) -> s.put(info.fullyQualifiedName(), k(pt)));
        assertEquals("Map<Object, Object>", s.get("a.b.ES.EMPTY"), s.toString());
        assertEquals("Map<Object, Object>", s.get("a.b.ES.other()"), s + "\n" + locals(report));
    }

    @DisplayName("Kotlin: a call on this ties its result's slots to the target, the instantiation being the caller's")
    @Test
    public void sameInstantiationSlots() {
        Report report = run("a.b.SI", """
                package a.b;
                import java.util.concurrent.atomic.AtomicReferenceArray;
                class SI<K, V> {
                    static class Entry<K, V> { }
                    AtomicReferenceArray<Entry<K, V>> table;
                    AtomicReferenceArray<Entry<K, V>> newEntryArray(int size) { return new AtomicReferenceArray<>(size); }
                    void init(int n) {
                        AtomicReferenceArray<Entry<K, V>> newTable = newEntryArray(n);
                        newTable.set(0, null);
                        table = newTable;
                    }
                }
                """, Policy.KOTLIN);
        Map<String, String> s = new java.util.TreeMap<>();
        report.verdicts().forEach((info, pt) -> s.put(info.fullyQualifiedName(), k(pt)));
        String field = s.get("a.b.SI.table");
        assertTrue(field != null && field.contains("?>"), s.toString());
        assertEquals("AtomicReferenceArray<Entry<K!, V!>?>?", field, s.toString());
        assertEquals("AtomicReferenceArray<Entry<K!, V!>?>", s.get("a.b.SI.newEntryArray(int)"), s.toString());
    }

    @DisplayName("Kotlin: a callee writing null into a wider array parameter reaches the caller's narrower array")
    @Test
    public void narrowedArrayWrittenThrough() {
        Report report = run("a.b.NW", """
                package a.b;
                class NW {
                    static void clear(Object[] a) { a[0] = null; }
                    static void read(Object[] a) { System.out.println(a.length); }
                    static String[] cleared() {
                        String[] s = new String[] {"x"};
                        clear(s);
                        return s;
                    }
                    static String[] kept() {
                        String[] s = new String[] {"x"};
                        read(s);
                        return s;
                    }
                }
                """, Policy.KOTLIN);
        Map<String, String> s = new java.util.TreeMap<>();
        report.verdicts().forEach((info, pt) -> s.put(info.fullyQualifiedName(), k(pt)));
        assertEquals("String?[]", s.get("a.b.NW.cleared()"), s + "\n" + explain(report));
        assertEquals("String[]", s.get("a.b.NW.kept()"), s.toString());
    }

    @DisplayName("Kotlin: guava's RegularImmutableMultiset.create fills its entry array with a counted for-each")
    @Test
    public void countedFillGuava() {
        Report report = run("a.b.RM", """
                package a.b;
                import java.lang.annotation.ElementType;
                import java.lang.annotation.Target;
                import java.util.Collection;
                class RM<E> {
                    @Target(ElementType.TYPE_USE) @interface Nullable {}
                    interface Entry<E> { E getElement(); int getCount(); }
                    static class ImmutableEntry<E> implements Entry<E> {
                        private final E element;
                        private final int count;
                        ImmutableEntry(E element, int count) { this.element = element; this.count = count; }
                        @Override public E getElement() { return element; }
                        @Override public int getCount() { return count; }
                        @Nullable ImmutableEntry<E> nextInBucket() { return null; }
                    }
                    static final class NonTerminalEntry<E> extends ImmutableEntry<E> {
                        private final ImmutableEntry<E> next;
                        NonTerminalEntry(E element, int count, ImmutableEntry<E> next) { super(element, count); this.next = next; }
                        @Override ImmutableEntry<E> nextInBucket() { return next; }
                    }
                    private static final ImmutableEntry<?>[] EMPTY_ARRAY = new ImmutableEntry<?>[0];
                    private final transient ImmutableEntry<E>[] entries;
                    private final transient @Nullable ImmutableEntry<?>[] hashTable;
                    private RM(ImmutableEntry<E>[] entries, @Nullable ImmutableEntry<?>[] hashTable) {
                        this.entries = entries;
                        this.hashTable = hashTable;
                    }
                    static <E> RM<E> create(Collection<? extends Entry<? extends E>> entries) {
                        int distinct = entries.size();
                        @SuppressWarnings({"unchecked", "rawtypes"})
                        ImmutableEntry<E>[] entryArray = new ImmutableEntry[distinct];
                        if (distinct == 0) {
                            return new RM<>(entryArray, EMPTY_ARRAY);
                        }
                        int tableSize = distinct * 2;
                        int mask = tableSize - 1;
                        @SuppressWarnings({"unchecked", "rawtypes"})
                        @Nullable ImmutableEntry<E>[] hashTable = new @Nullable ImmutableEntry[tableSize];
                        int index = 0;
                        int hashCode = 0;
                        long size = 0;
                        for (Entry<? extends E> entryWithWildcard : entries) {
                            @SuppressWarnings("unchecked")
                            Entry<E> entry = (Entry<E>) entryWithWildcard;
                            E element = entry.getElement();
                            int count = entry.getCount();
                            int hash = element.hashCode();
                            int bucket = hash & mask;
                            ImmutableEntry<E> bucketHead = hashTable[bucket];
                            ImmutableEntry<E> newEntry;
                            if (bucketHead == null) {
                                boolean canReuseEntry = entry instanceof ImmutableEntry && !(entry instanceof NonTerminalEntry);
                                newEntry = canReuseEntry ? (ImmutableEntry<E>) entry : new ImmutableEntry<E>(element, count);
                            } else {
                                newEntry = new NonTerminalEntry<>(element, count, bucketHead);
                            }
                            hashCode += hash ^ count;
                            entryArray[index++] = newEntry;
                            hashTable[bucket] = newEntry;
                            size += count;
                        }
                        return flooded(hashTable) ? asList(entryArray) : new RM<E>(entryArray, hashTable);
                    }
                    static boolean flooded(@Nullable ImmutableEntry<?>[] hashTable) { return hashTable.length > 9; }
                    // guava's ImmutableList.asImmutableList(@Nullable Object[]), stored by asImmutableList(Object[], int)
                    static <E> RM<E> asList(@Nullable Object[] elements) { return asList(elements, elements.length); }
                    static <E> RM<E> asList(@Nullable Object[] elements, int length) {
                        Held h = new Held(elements);
                        return null;
                    }
                    static final class Held {
                        final @Nullable Object[] array;
                        Held(@Nullable Object[] array) { this.array = array; }
                    }
                    // guava's ImmutableList.Builder: a second caller of asList(@Nullable Object[], int), with an array
                    // of its own that it writes into; that write is not one into create's entryArray (#74)
                    static final class Builder<E> {
                        @Nullable Object[] contents = new @Nullable Object[4];
                        int size;
                        void add(E element) {
                            if (size == contents.length) contents = java.util.Arrays.copyOf(contents, size * 2);
                            contents[size++] = element;
                        }
                        void reset() { contents[0] = null; size = 0; }
                        private void add(@Nullable Object[] elements, int n) {
                            System.arraycopy(elements, 0, contents, size, n);
                            size += n;
                        }
                        Builder<E> combine(Builder<E> builder) { add(builder.contents, builder.size); return this; }
                        RM<E> build() { return asList(contents, size); }
                    }
                    Entry<E> getEntry(int index) { return entries[index]; }
                }
                """, Policy.KOTLIN);
        Map<String, String> s = new java.util.TreeMap<>();
        report.verdicts().forEach((info, pt) -> s.put(info.fullyQualifiedName(), k(pt)));
        String why = s + "\n" + explain(report);
        assertEquals("ImmutableEntry<E!>[]", s.get("a.b.RM.entries"), why);
        assertEquals("Entry<E!>", s.get("a.b.RM.getEntry(int)"), why);
    }

    /*
     CodeLaser/maddi-mod#74: guava's 'asImmutableList(@Nullable Object[], int)' stores its argument in a
     RegularImmutableList. The links lift that field onto ImmutableList ('IList.array' here), one field for the
     content of every list. 'of(e1, e2)' filling its own array before it becomes a list is a write booked on that
     field, not one into the array 'create' passes in.
     */
    @DisplayName("Kotlin: a write booked on a field lifted onto a supertype is no write into another array passed in")
    @Test
    public void narrowedArrayThroughLiftedField() {
        Report report = run("a.b.GL", """
                package a.b;
                import java.lang.annotation.ElementType;
                import java.lang.annotation.Target;
                class GL {
                    @Target(ElementType.TYPE_USE) @interface Nullable {}
                    abstract static class IList<E> {
                        abstract E get(int i);
                        static <E> IList<E> of(E e1, E e2) {
                            Object[] array = new Object[2];
                            array[0] = e1;
                            array[1] = e2;
                            return asList(array, 2);
                        }
                        static <E> IList<E> asList(@Nullable Object[] elements, int length) {
                            Object[] kept = length < elements.length ? java.util.Arrays.copyOf(elements, length) : elements;
                            return new RList<>(kept);
                        }
                    }
                    static final class RList<E> extends IList<E> {
                        final Object[] array;
                        RList(Object[] array) { this.array = array; }
                        @SuppressWarnings("unchecked") @Override E get(int i) { return (E) array[i]; }
                    }
                    static final class Entry { }
                    private final Entry[] entries;
                    private GL(Entry[] entries) { this.entries = entries; }
                    static GL create(int n, Entry e) {
                        Entry[] entryArray = new Entry[n];
                        for (int i = 0; i < n; i++) entryArray[i] = e;
                        IList<Entry> asList = IList.asList(entryArray, n);
                        return new GL(entryArray);
                    }
                    Entry entry(int i) { return entries[i]; }
                }
                """, Policy.KOTLIN);
        Map<String, String> s = new java.util.TreeMap<>();
        report.verdicts().forEach((info, pt) -> s.put(info.fullyQualifiedName(), k(pt)));
        String why = s + "\n" + explain(report);
        assertEquals("Entry[]", s.get("a.b.GL.entries"), why);
        assertEquals("Entry", s.get("a.b.GL.entry(int)"), why);
    }

    @DisplayName("Kotlin: what is read from a '?' argument is Any?")
    @Test
    public void starSlotRead() {
        Report report = run("a.b.SR", """
                package a.b;
                import java.util.ArrayList;
                import java.util.List;
                class SR {
                    String getIfPresent(Object key) { return key.toString(); }
                    void all(Iterable<?> keys) {
                        for (Object key : keys) getIfPresent(key);
                    }
                    void some(Iterable<? extends Object> keys) {
                        for (Object key : keys) getIfPresent(key);
                    }
                    void use() {
                        List<String> names = new ArrayList<>();
                        names.add("x");
                        all(names);
                    }
                }
                """, Policy.KOTLIN);
        Map<String, String> s = new java.util.TreeMap<>();
        report.verdicts().forEach((info, pt) -> s.put(info.fullyQualifiedName(), k(pt)));
        assertEquals("Object?", s.get("a.b.SR.getIfPresent(Object):0:key"), s.toString());
        assertTrue(locals(report).contains("use.names: List<String>"), "nothing flows back to the caller: " + locals(report));
    }

    @DisplayName("Kotlin: no slot flows back out of an unbound wildcard, nor through an unchecked cast of an Object slot")
    @Test
    public void wildcardAndUncheckedSlots() {
        Report report = run("a.b.WS", """
                package a.b;
                import java.util.HashMap;
                import java.util.Map;
                class WS {
                    static final Map<Object, Object> EMPTY = new HashMap<>();
                    @SuppressWarnings("unchecked")
                    static <K, V> Map<K, V> empty() { return (Map<K, V>) EMPTY; }
                    static int join(Map<?, ?> map) { return map.size(); }
                    static int nulls() {
                        Map<String, String> withNull = new HashMap<>();
                        withNull.put("a", null);
                        return join(withNull);
                    }
                    static int plain() {
                        Map<String, String> plain = new HashMap<>();
                        plain.put("a", "b");
                        return join(plain) + join(empty());
                    }
                    static Map<String, String> names() { return empty(); }
                    static void poison() {
                        Map<String, String> m = empty();
                        m.put("x", null);
                    }
                }
                """, Policy.KOTLIN);
        Map<String, String> s = new java.util.TreeMap<>();
        report.verdicts().forEach((info, pt) -> s.put(info.fullyQualifiedName(), k(pt)));
        String l = locals(report);
        assertTrue(l.contains("nulls.withNull: Map<String, String?>"), l);
        assertTrue(l.contains("plain.plain: Map<String, String>"), "nothing flows back out of join's Map<?, ?>: " + l);
        assertEquals("Map<String, String>", s.get("a.b.WS.names()"), "poison's null stays in its own map: " + s);
    }

    @DisplayName("two alternatives of one argument are not each other's value")
    @Test
    public void alternativesInAnArgument() {
        Report report = run("a.b.T", """
                package a.b;
                class T {
                    static class Struct { final String qualifiedName; Struct(String q) { qualifiedName = q; } }
                    static class Node {
                        final Struct struct;
                        String simpleName;
                        boolean root;
                        Node(Struct struct) {
                            this.struct = struct;
                            simpleName = struct.qualifiedName.substring(struct.qualifiedName.lastIndexOf('/') + 1);
                        }
                    }
                    static void use(String s) { }
                    static void add(Node node) {
                        if (node.simpleName != null) use(node.root ? node.struct.qualifiedName : node.simpleName);
                    }
                    static String name(Node node) { return node.struct.qualifiedName; }
                }
                """);
        System.out.println(explain(report));
        Map<String, String> byName = report.verdicts().entrySet().stream()
                .collect(Collectors.toMap(e -> e.getKey().fullyQualifiedName(), e -> k(e.getValue())));
        assertEquals("String?", byName.get("a.b.T.Node.simpleName"));
        assertEquals("String", byName.get("a.b.T.Struct.qualifiedName"), "fernflower ClassesProcessor:508");
        assertEquals("String", byName.get("a.b.T.name(a.b.T.Node)"));
    }

    @DisplayName("a null written through a getter's result goes to the receiver's slot, as through a variable")
    @Test
    public void slotThroughGetter() {
        Report report = run("a.b.SG", """
                package a.b;
                import java.util.*;
                class SG {
                    static class Keyed<E, K> extends ArrayList<E> {
                        private final Map<K, Integer> map = new HashMap<>();
                        void addWithKey(E element, K key) {
                            map.put(key, size());
                            super.add(element);
                        }
                    }
                    static class Wrapper {
                        private final Keyed<String, String> inits = new Keyed<>();
                        Keyed<String, String> getInits() { return inits; }
                    }
                    static void extract(Wrapper wrapper, String key) {
                        String value = null;
                        if (key.length() > 2) value = key;
                        wrapper.getInits().addWithKey(value, key);
                    }
                }
                """);
        System.out.println(explain(report));
        Map<String, String> byName = report.verdicts().entrySet().stream()
                .collect(Collectors.toMap(e -> e.getKey().fullyQualifiedName(), e -> k(e.getValue())));
        assertEquals("E!", byName.get("a.b.SG.Keyed.addWithKey(Object,Object):0:element"),
                "fernflower VBStyleCollection.addWithKey via InitializerProcessor");
        assertEquals("Keyed<String?, String>", byName.get("a.b.SG.Wrapper.inits"));
        assertEquals("Keyed<String?, String>", byName.get("a.b.SG.Wrapper.getInits()"));
    }

    // fernflower's FastFixedSetFactory<E> / FastFixedSet<E> as DomHelper.calcPostDominators uses them
    @Language("java")
    private static final String INSTANTIATION = """
            package a.b;
            import java.util.*;
            class CI {
                static class Node { Node next = null; }
                static class Factory<E> {
                    private final List<E> keys;
                    Factory(Collection<E> set) { keys = new ArrayList<>(set); }
                    FSet<E> spawn() { return new FSet<>(this); }
                    List<E> all() { List<E> l = new ArrayList<>(keys); l.add(null); return l; }
                }
                static class FSet<E> {
                    final Factory<E> factory;
                    final Set<E> elements = new HashSet<>();
                    FSet(Factory<E> factory) { this.factory = factory; }
                    void add(E e) { elements.add(e); }
                    void union(FSet<E> other) { elements.addAll(other.elements); }
                    FSet<E> copy() { FSet<E> c = new FSet<>(factory); c.elements.addAll(elements); return c; }
                }
                static List<Node> succ(Node n) { List<Node> l = new ArrayList<>(); l.add(n.next); return l; }
                static void dom(List<Node> nodes) {
                    Factory<Node> factory = new Factory<>(nodes);
                    FSet<Node> flags = factory.spawn();
                    FSet<Node> init = factory.spawn();
                    for (Node n : nodes) { for (Node s : succ(n)) flags.add(s); }
                    FSet<Node> tmp = init.copy();
                    tmp.union(flags);
                    List<Node> withHole = factory.all();
                }
                static void ints() {
                    List<Integer> ones = new ArrayList<>();
                    ones.add(1);
                    Factory<Integer> f = new Factory<>(ones);
                    FSet<Integer> s = f.spawn();
                    s.add(2);
                }
            }
            """;

    @DisplayName("a class type variable inside a generic type is instantiated by the receiver's slot, not tied to it")
    @Test
    public void classTypeVariableInstantiation() {
        Report report = run("a.b.CI", INSTANTIATION, Policy.KOTLIN);
        System.out.println(explain(report));
        Map<String, String> byName = report.verdicts().entrySet().stream()
                .collect(Collectors.toMap(e -> e.getKey().fullyQualifiedName(), e -> k(e.getValue())));
        String locals = locals(report);
        // the class stays generic in E: no 'E?' from one instantiation's null (fernflower FastFixedSetFactory.kt:19/33)
        assertEquals("FSet<E!>", byName.get("a.b.CI.Factory.spawn()"));
        assertEquals("Collection<E!>", byName.get("a.b.CI.Factory.<init>(java.util.Collection):0:set"));
        assertEquals("FSet<E!>", byName.get("a.b.CI.FSet.union(a.b.CI.FSet):0:other"));
        assertEquals("FSet<E!>", byName.get("a.b.CI.FSet.copy()"));
        // the class's own null into its E slot is the printer's unchecked cast: a slot holding a type variable stays
        // that variable in Kotlin (typeVariableSlot; it was 'List<E?>' before guava's invariance mismatches,
        // 2026-10-16)
        assertEquals("List<E!>", byName.get("a.b.CI.Factory.all()"));
        assertTrue(locals.contains("dom.withHole: List<Node?>"), locals); // its factory is a Factory<Node?>
        // the null added to 'flags' belongs to the factory's instantiation, and so to every set it spawns (Kotlin)
        assertTrue(locals.contains("dom.flags: FSet<Node?>"), locals);
        assertTrue(locals.contains("dom.factory: Factory<Node?>"), locals);
        assertTrue(locals.contains("dom.init: FSet<Node?>"), locals);
        assertTrue(locals.contains("dom.tmp: FSet<Node?>"), locals);
        assertEquals("List<Node?>", byName.get("a.b.CI.dom(java.util.List):0:nodes"), "new Factory<>(nodes) is invariant");
        // another instantiation is untouched
        assertTrue(locals.contains("ints.f: Factory<Integer>"), locals);
        assertTrue(locals.contains("ints.s: FSet<Integer>"), locals);

        // Java annotations: the argument flows into the receiver; a result is tied to its receiver by the links only
        Report java = run("a.b.CJ", INSTANTIATION.replace("class CI", "class CJ"),
                Policy.NULL_MARKED);
        Map<String, String> javaByName = java.verdicts().entrySet().stream()
                .collect(Collectors.toMap(e -> e.getKey().fullyQualifiedName(), e -> k(e.getValue())));
        String javaLocals = locals(java);
        assertEquals("FSet<E!>", javaByName.get("a.b.CJ.Factory.spawn()"));
        assertTrue(javaLocals.contains("dom.flags: FSet<Node?>"), javaLocals);
        assertTrue(javaLocals.contains("dom.tmp: FSet<Node?>"), javaLocals);
        assertTrue(javaLocals.contains("dom.factory: Factory<Node>"), javaLocals);
        // (init: the links make tmp = init.copy() share content, so init follows tmp; not asserted)
        // (withHole: the class's own null reaches it through the links where they exist; the explicit edge is Kotlin's)
        assertEquals("List<Node>", javaByName.get("a.b.CJ.dom(java.util.List):0:nodes"));
    }

    private static Map<String, String> localsByName(Report report) {
        return report.locals().entrySet().stream()
                .collect(Collectors.toMap(e -> e.getKey().methodInfo().name() + "." + e.getKey().name(),
                        e -> k(e.getValue()), (a, b) -> a + "|" + b));
    }

    /*
     CodeLaser/maddi-mod#22 gap 2, nacos MapperContext: a Map<String, Object> written and read through accessors with
     constant keys. One value slot made every key's value nullable when one key's was (a null 'dataId' made 'ids'
     nullable). A key's values are now a node of their own; the field's slot still holds all of them.
     */
    @DisplayName("keyed maps: a constant key's values are its own, through accessors or directly (gap 2)")
    @Test
    public void keyedMaps() {
        Report report = run("a.b.KM", """
                package a.b;
                import java.util.*;
                class KM {
                    static final String A = "a";
                    static final String B = "b";
                    static final String B2 = "b";
                    static class Ctx {
                        private final Map<String, Object> where;
                        private final Map<String, String> direct = new HashMap<>();
                        Ctx() { this.where = new HashMap<>(); }
                        Object get(String key) { return where.get(key); }
                        void put(String key, Object value) { where.put(key, value); }
                        void fill(String x) { direct.put(A, x); direct.put("c", "c"); }
                        String c() { String c = direct.get("c"); return c; }
                        String a() { String a = direct.get(A); return a; }
                        @Override public String toString() { return "Ctx{" + where + "}"; }
                    }
                    static Ctx build(String a, List<Long> ids) { Ctx c = new Ctx(); c.put(A, a); c.put(B, ids); c.fill(a); return c; }
                    static Ctx caller() { return build(null, new ArrayList<>()); }
                    static int useB(Ctx c) { List<Long> ids = (List<Long>) c.get(B); return ids.size(); }
                    static int useB2(Ctx c) { List<Long> ids2 = (List<Long>) c.get(B2); return ids2.size(); }
                    static int useA(Ctx c) { String a = (String) c.get(A); return a.length(); }
                    static Object any(Ctx c, String k) { Object any = c.get(k); return any; }
                }
                """);
        Map<String, String> locals = localsByName(report);
        assertEquals("List<Long>", locals.get("useB.ids"), "only B's values");
        assertEquals("List<Long>", locals.get("useB2.ids2"), "B2 is the same key as B");
        assertEquals("String?", locals.get("useA.a"), "a null is put under A");
        assertEquals("Object?", locals.get("any.any"), "a key not known reads every key");
        // directly on the field: Map.get's own contract (the key may be absent) still holds, but no other key's null
        String chains = report.locals().keySet().stream().filter(l -> report.cause().containsKey(l)
                                                                     || report.seedOrigin().containsKey(l))
                .map(report::explain).sorted().collect(Collectors.joining("\n"));
        assertTrue(chains.contains("c in a.b.KM.Ctx.c() <- assigned java.util.Map.get(Object)"), chains);
        Map<String, String> byName = byName(report);
        // the declarations keep every value
        assertEquals("Map<String, Object?>", byName.get("a.b.KM.Ctx.where"));
        assertEquals("Object?", byName.get("a.b.KM.Ctx.put(String,Object):1:value"));
        assertEquals("Map<String, String?>", byName.get("a.b.KM.Ctx.direct"));

        // a write under a key not known reaches every key; a map that escapes keeps one slot
        Report unknown = run("a.b.KU", """
                package a.b;
                import java.util.*;
                class KU {
                    static final String A = "a";
                    static final String B = "b";
                    static class Ctx {
                        private final Map<String, Object> where = new HashMap<>();
                        Object get(String key) { return where.get(key); }
                        void put(String key, Object value) { where.put(key, value); }
                    }
                    static class Escaping {
                        private final Map<String, Object> where = new HashMap<>();
                        Object get(String key) { return where.get(key); }
                        void put(String key, Object value) { where.put(key, value); }
                        Map<String, Object> raw() { return where; }
                    }
                    static void fill(Ctx c, String k, Escaping e) { c.put(k, null); c.put(B, "b"); e.put(A, null); e.put(B, "b"); }
                    static int useB(Ctx c) { String b = (String) c.get(B); return b.length(); }
                    static int escapedB(Escaping e) { String eb = (String) e.get(B); return eb.length(); }
                }
                """);
        Map<String, String> u = localsByName(unknown);
        assertEquals("String?", u.get("useB.b"), "the null put under k may be B's");
        assertEquals("String?", u.get("escapedB.eb"), "raw() lets anyone put under B");
    }

    // langchain4j EmbeddingStoreRequestContext.Add: 'Add(store, attributes, embedding)' calls 'this(..., null)' into
    // its 'Embedded embedded' field. That null is the class's own: Kotlin's 'val embedded: Embedded?', not an
    // Add<X?> for every instance (which spread to ListeningEmbeddingStore's 'delegate' and clashed with 'this').
    // A null written outside the class into the same kind of field is still the instance's.
    @DisplayName("a holder field's own null makes the field nullable, an outside null the instance's slot")
    @Test
    public void holderFieldOwnNull() {
        Report report = run("a.b.HF", """
                package a.b;
                class HF {
                    static class Store<E> { }
                    static class Ctx<E> {
                        final Store<E> store;
                        final E embedded;
                        Ctx(Store<E> store, E embedded) { this.store = store; this.embedded = embedded; }
                        Ctx(Store<E> store) { this(store, null); }
                        E embedded() { return embedded; }
                    }
                    static class Box<T> {
                        final T t;
                        Box(T t) { this.t = t; }
                    }
                    static <X> Ctx<X> make(Store<X> s) { return new Ctx<>(s); }
                    static Box<String> box() { return new Box<>(null); }
                }
                """, Policy.KOTLIN);
        Map<String, String> s = new java.util.TreeMap<>();
        report.verdicts().forEach((info, pt) -> s.put(label(info), k(pt)));
        assertEquals("E?", s.get("embedded"), s.toString());
        // X!: no claim about X, which is the point -- not X?
        assertEquals("Ctx<X!>", s.get("make()"), "the class's own null stays in the class: " + s);
        assertEquals("Box<String?>", s.get("box()"), "a null from outside is the instance's: " + s);
    }

    // langchain4j EmbeddingModelRequestContext, DefaultContent: 'this.items = copy(ensure(builder.items, "items"))'.
    // Kotlin infers copy's and ensure's T from the argument, so the field's type is the builder's, element included
    @DisplayName("Kotlin: a generic method handing its argument's type on ties the type arguments")
    @Test
    public void passedThroughGenericMethod() {
        Report report = run("a.b.PT", """
                package a.b;
                import java.util.ArrayList;
                import java.util.Collections;
                import java.util.List;
                class PT {
                    private final List<String> items;
                    private final List<String> names;
                    PT(Builder builder) {
                        this.items = copy(ensure(builder.items, "items"));
                        this.names = copy(builder.names);
                    }
                    static <T> T ensure(T t, String name) {
                        if (t == null) throw new IllegalArgumentException(name);
                        return t;
                    }
                    static <T> List<T> copy(List<? extends T> list) {
                        return list == null ? List.of() : Collections.unmodifiableList(list);
                    }
                    List<String> items() { return items; }
                    List<String> names() { return names; }
                    static class Builder {
                        List<String> items;
                        List<String> names;
                        Builder items(List<String> items) { this.items = items; return this; }
                        Builder names(List<String> names) { this.names = names; return this; }
                        PT build() { return new PT(this); }
                    }
                    static PT make() {
                        List<String> list = new ArrayList<>();
                        list.add(null);
                        return new Builder().items(list).names(List.of("a")).build();
                    }
                }
                """, Policy.KOTLIN);
        Map<String, String> s = new java.util.TreeMap<>();
        report.verdicts().forEach((info, pt) -> s.put(info.fullyQualifiedName(), k(pt)));
        assertEquals("List<String?>?", s.get("a.b.PT.Builder.items"), s.toString());
        assertEquals("List<String?>", s.get("a.b.PT.items"), s.toString());
        assertEquals("List<String>", s.get("a.b.PT.names"), s.toString());
    }

    // langchain4j DefaultStructuredPromptFactory: 'return OBJECT_MAPPER.readValue(json, MAP_TYPE)' with a
    // 'TypeReference<Map<String, Object>> MAP_TYPE'. Kotlin infers readValue's T from the TypeReference, so the field's
    // type argument is the result's type: a nullable map value in the result is one in the field (maddi-mod#38)
    @Test
    public void typeArgumentArgumentGivesTheResultsType() {
        Report report = run("a.b.TR", """
                package a.b;
                import java.util.Map;
                class TR {
                    static abstract class TypeRef<T> { }
                    interface Mapper { <T> T read(String json, TypeRef<T> type); }
                    private static final TypeRef<Map<String, Object>> MAP_TYPE = new TypeRef<>() { };
                    static Map<String, Object> extract(Mapper mapper, String json) {
                        return mapper.read(json, MAP_TYPE);
                    }
                    static String fill(Mapper mapper) {
                        Map<String, Object> variables = extract(mapper, "{}");
                        variables.put("absent", null);
                        return variables.toString();
                    }
                }
                """, Policy.KOTLIN);
        Map<String, String> s = new java.util.TreeMap<>();
        report.verdicts().forEach((info, pt) -> s.put(info.fullyQualifiedName(), k(pt)));
        assertEquals("Map<String, Object?>", s.get("a.b.TR.extract(a.b.TR.Mapper,String)"), s.toString());
        assertEquals("TypeRef<Map<String, Object?>>", s.get("a.b.TR.MAP_TYPE"), s.toString());
    }

    // timefold SolverMetricUtil.extractScore (CodeLaser/maddi-mod#27 1): the loop fills every index or returns, so the
    // array that leaves the loop has no null element; returning the array itself from the loop does not count
    @DisplayName("an array filled by a loop that returns early: no null element")
    @Test
    public void arrayFillWithEarlyReturn() {
        Report report = run("a.b.EX", """
                package a.b;
                import java.util.function.Function;
                class EX {
                    interface Def { Object from(Number[] levels); }
                    static class D implements Def {
                        public Object from(Number[] levels) { return levels[0].intValue(); }
                    }
                    static class P implements Def {
                        public Object from(Number[] partial) { return partial.length; }
                    }
                    static Object extract(String[] labels, Function<String, Number> f, Def def) {
                        var levels = new Number[labels.length];
                        for (var i = 0; i < labels.length; i++) {
                            var n = f.apply(labels[i]);
                            if (n == null) return null;
                            levels[i] = n;
                        }
                        return def.from(levels);
                    }
                    static Number[] handOut(String[] labels, Function<String, Number> f, Def def) {
                        var partial = new Number[labels.length];
                        for (var i = 0; i < labels.length; i++) {
                            var n = f.apply(labels[i]);
                            if (n == null) return partial;
                            partial[i] = n;
                        }
                        return partial;
                    }
                }
                """);
        Map<String, String> s = new java.util.TreeMap<>();
        report.verdicts().forEach((info, pt) -> s.put(info.fullyQualifiedName(), k(pt)));
        assertEquals("Number[]", s.get("a.b.EX.D.from(Number[]):0:levels"), s.toString());
        assertEquals("Number?[]", s.get("a.b.EX.handOut(String[],java.util.function.Function,a.b.EX.Def)"),
                s.toString());
    }

    @DisplayName("direct links: Optional.ofNullable(x).orElseGet(s) is non-null whatever x is (langchain4j StreamingToSynchronousChatExecutor)")
    @Test
    public void orElseGetIsDirect() {
        // the closure had tied the result to x through the Optional's hidden content ('orElseGet ← this.§$' after
        // 'ofNullable.§$ ← 0:t'); the pass reads the statements' own links only (Link.derived), and no statement
        // assigns the result from x
        Report report = run("a.b.OG", """
                package a.b;
                import java.util.Optional;
                import java.util.concurrent.atomic.AtomicReference;
                class OG {
                    private final AtomicReference<String> response = new AtomicReference<>();
                    private final String successfulResult;
                    OG(String s) { this.successfulResult = s; }
                    void onComplete(String r) { response.set(r); }
                    String getResponse() { return response.get(); }
                    String execute() { return Optional.ofNullable(getResponse()).orElseGet(() -> "x"); }
                    String result() { return Optional.ofNullable(successfulResult).orElseGet(() -> "y"); }
                    static void test() { new OG(null).onComplete(null); }
                }
                """);
        System.out.println(explain(report));
        Map<String, String> byName = report.verdicts().entrySet().stream()
                .collect(Collectors.toMap(e -> e.getKey().fullyQualifiedName(), e -> k(e.getValue())));
        assertEquals("String?", byName.get("a.b.OG.getResponse()"));
        assertEquals("String", byName.get("a.b.OG.execute()"));
        assertEquals("String?", byName.get("a.b.OG.successfulResult"));
        assertEquals("String", byName.get("a.b.OG.result()"));
    }

    @DisplayName("direct links: the timefold shapes whose verdict moved (createMergedResult, getReachableValues, cloneCollection)")
    @Test
    public void directLinksTimefoldShapes() {
        Report report = run("a.b.RG", """
                package a.b;
                import java.util.*;
                class RG {
                    String pick(boolean b) { return b ? "p" : null; }
                    String reachable(boolean b, boolean c) {
                        var v = pick(b);
                        if (v != null) return v;
                        if (c) { var s = "s"; return s; }
                        throw new IllegalStateException();
                    }
                    String manager(boolean b) { return reachable(b, true); }
                    String reachable2(boolean b, boolean c) { var v = pick(b); if (v != null) return v; var s = other(c); return s; }
                    String other(boolean c) { if (c) return "s"; throw new IllegalStateException(); }
                    static RG singleton(List<String> list) {
                        RG n = null;
                        for (String s : list) { if (n == null) { n = new RG(); } }
                        return n;
                    }
                    static RG merged(List<String> list) { RG m = singleton(list); use(m, list); return m; }
                    static void use(RG m, List<String> l) { l.add(String.valueOf(m)); }
                    <E> Collection<E> cloneAll(Collection<E> in) {
                        var out = new ArrayList<E>();
                        for (var e : in) { var c = cloneIf(e); out.add(c); }
                        return out;
                    }
                    <E> E cloneIf(E e) { if (e instanceof String) return null; return e; }
                }
                """, Policy.NULL_MARKED_FLOW_ONLY.withoutContracts().withWorld(World.CLOSED));
        System.out.println(explain(report));
        Map<String, String> byName = report.verdicts().entrySet().stream()
                .collect(Collectors.toMap(e -> e.getKey().fullyQualifiedName(), e -> k(e.getValue())));
        assertEquals("String?", byName.get("a.b.RG.pick(boolean)"));
        assertEquals("String", byName.get("a.b.RG.reachable(boolean,boolean)"), "the null is checked away");
        assertEquals("String", byName.get("a.b.RG.manager(boolean)"), "ValueRangeManager.getReachableValues");
        assertEquals("String", byName.get("a.b.RG.reachable2(boolean,boolean)"), "a later return repeats the guarded marker");
        assertEquals("RG?", byName.get("a.b.RG.singleton(java.util.List)"), "an empty list");
        assertEquals("RG?", byName.get("a.b.RG.merged(java.util.List)"), "createMergedResult really returns it (use does not dereference it)");
        assertEquals("Collection<E?>", byName.get("a.b.RG.cloneAll(java.util.Collection)"), "cloneIf's null is added");
    }

    @DisplayName("direct links: the guava shapes whose verdict moved (HashBiMap.hashTableVToK, RegularImmutableMap.table, removeExactly)")
    @Test
    public void directLinksGuavaShapes() {
        Report report = run("a.b.HB", """
                package a.b;
                import java.util.Arrays;
                import java.util.concurrent.ConcurrentHashMap;
                class HB {
                    static final class Node { Node nextInVToKBucket; Node(Node n) { nextInVToKBucket = n; } }
                    private Node[] hashTableVToK;
                    HB(int size) { this.hashTableVToK = createTable(size); }
                    private Node[] createTable(int length) { return new Node[length]; }
                    void delete(Node node, int valueBucket) { hashTableVToK[valueBucket] = node.nextInVToKBucket; }
                    void insert(Node node, int valueBucket) {
                        node.nextInVToKBucket = hashTableVToK[valueBucket];
                        hashTableVToK[valueBucket] = node;
                    }
                    void clear() { Arrays.fill(hashTableVToK, null); }
                    static final class Entry<K, V> { final K k; Entry(K k) { this.k = k; } }
                    static final class RIM<K, V> { final Entry<K, V>[] table; RIM(Entry<K, V>[] table) { this.table = table; } }
                    static <K, V> Entry<K, V>[] createEntryArray(int size) { return (Entry<K, V>[]) new Entry<?, ?>[size]; }
                    static <K, V> RIM<K, V> fromEntries(K[] keys) {
                        Entry<K, V>[] table = createEntryArray(keys.length * 2);
                        for (int i = 0; i < keys.length; i++) {
                            Entry<K, V> head = table[i];
                            table[i] = new Entry<>(keys[i]);
                        }
                        return new RIM<>(table);
                    }
                    private final ConcurrentHashMap<Object, Integer> countMap = new ConcurrentHashMap<>();
                    boolean removeExactly(Object element, int occurrences) {
                        if (occurrences == 0) return true;
                        Integer c = countMap.get(element);
                        if (c == null) return false;
                        countMap.remove(element, c);
                        return true;
                    }
                    boolean test() { return removeExactly("a", 0) && removeExactly("c", 1); }
                }
                """);
        System.out.println(explain(report));
        Map<String, String> byName = report.verdicts().entrySet().stream()
                .collect(Collectors.toMap(e -> e.getKey().fullyQualifiedName(), e -> k(e.getValue())));
        assertEquals("Node?[]", byName.get("a.b.HB.hashTableVToK"), "created with null elements, and node.nextInVToKBucket");
        assertEquals("Entry<K!, V!>?[]", byName.get("a.b.HB.RIM.table"), "created with null elements by a generic factory");
        assertEquals("Object", byName.get("a.b.HB.removeExactly(Object,int):0:element"));
    }
}
