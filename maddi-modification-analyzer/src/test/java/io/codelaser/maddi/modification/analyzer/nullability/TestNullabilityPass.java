package io.codelaser.maddi.modification.analyzer.nullability;

import io.codelaser.maddi.cst.api.info.FieldInfo;
import io.codelaser.maddi.cst.api.info.Info;
import io.codelaser.maddi.cst.api.info.MethodInfo;
import io.codelaser.maddi.cst.api.info.ParameterInfo;
import io.codelaser.maddi.cst.api.info.TypeInfo;
import io.codelaser.maddi.cst.api.type.ParameterizedType;
import io.codelaser.maddi.modification.analyzer.CommonTest;
import io.codelaser.maddi.modification.analyzer.impl.IteratingAnalyzerImpl;
import io.codelaser.maddi.modification.analyzer.impl.ModAnalyzerForTesting;
import io.codelaser.maddi.modification.analyzer.impl.SingleIterationAnalyzerImpl;
import org.intellij.lang.annotations.Language;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The declaration pass (docs/design/nullability.md §4.2 M3, T3): one verdict per declaration, in Kotlin's
 * notation ({@code String?} nullable, {@code String} non-null, {@code String!} unspecified), under the
 * {@code @NullMarked} policy (no null reaches it: non-null).
 */
public class TestNullabilityPass extends CommonTest {

    private TypeInfo parsed;

    private List<Info> analysisOrder;

    private NullabilityPass.Report run(String fqn, String src) {
        return run(fqn, src, NullabilityPass.Policy.NULL_MARKED);
    }

    private NullabilityPass.Report run(String fqn, String src, NullabilityPass.Policy policy) {
        TypeInfo t = javaInspector.parse(fqn, src);
        parsed = t;
        List<Info> ao = prepWork(t);
        ModAnalyzerForTesting analyzer = new SingleIterationAnalyzerImpl(javaInspector,
                new IteratingAnalyzerImpl.ConfigurationBuilder().setNullability(true).build());
        analyzer.go(ao, 3);
        analysisOrder = ao;
        return new NullabilityPass(policy).go(ao);
    }

    static String k(ParameterizedType pt) {
        if (pt.arrays() > 0) {
            String own = switch (pt.nullable()) {
                case NULLABLE -> "?";
                case NONNULL -> "";
                case UNSPECIFIED -> "!";
            };
            return k(pt.componentType()) + "[]" + own;
        }
        String base = pt.typeParameter() != null ? pt.typeParameter().simpleName() : pt.typeInfo().simpleName();
        String args = pt.parameters().isEmpty() ? ""
                : pt.parameters().stream().map(TestNullabilityPass::k).collect(Collectors.joining(", ", "<", ">"));
        String suffix = switch (pt.nullable()) {
            case NULLABLE -> "?";
            case NONNULL -> "";
            case UNSPECIFIED -> "!";
        };
        return base + args + suffix;
    }

    // every verdict of the type, sorted: 'f: String?', 'm(0:s): String?' for a parameter, 'm(): String' for a return
    private static String verdicts(NullabilityPass.Report report) {
        return report.verdicts().entrySet().stream()
                .map(e -> label(e.getKey()) + ": " + k(e.getValue()))
                .sorted().collect(Collectors.joining("\n"));
    }

    private static String label(Info info) {
        return switch (info) {
            case FieldInfo fi -> fi.name();
            case ParameterInfo pi -> pi.methodInfo().name() + "(" + pi.index() + ":" + pi.name() + ")";
            case MethodInfo mi -> mi.name() + "()";
            default -> info.toString();
        };
    }

    private static String explain(NullabilityPass.Report report) {
        return report.verdicts().keySet().stream()
                .filter(i -> report.cause().containsKey(i) || report.seedOrigin().containsKey(i))
                .map(report::explain).sorted().collect(Collectors.joining("\n"));
    }

    @Language("java")
    private static final String RETURNS = """
            package a.b;
            class R {
                String direct() { return null; }
                String viaCall() { return direct(); }
                String ternary(boolean b, String s) { return b ? s : null; }
                String literal() { return "x"; }
                String identity(String s) { return s; }
                int primitive() { return 3; }
                <T> T generic(T t) { return t; }
            }
            """;

    @DisplayName("returns: a null literal, through a call, through a ternary; non-null literals and primitives")
    @Test
    public void returns() {
        NullabilityPass.Report report = run("a.b.R", RETURNS);
        System.out.println(explain(report));
        assertEquals("""
                direct(): String?
                generic(): T!
                generic(0:t): T!
                identity(): String
                identity(0:s): String
                literal(): String
                primitive(): int
                ternary(): String?
                ternary(0:b): boolean
                ternary(1:s): String
                viaCall(): String?""", verdicts(report));
    }

    @Language("java")
    private static final String FIELDS = """
            package a.b;
            class F {
                private final String fin;
                private String assigned;
                private String lazy;
                private String cleared = "x";
                private String initNull = null;
                private String fromParam;
                F(String fin, String assigned) { this.fin = fin; this.assigned = assigned; }
                String getLazy() { if (lazy == null) lazy = "y"; return lazy; }
                void clear() { cleared = null; }
                void setFromParam(String s) { this.fromParam = s; }
                String getFromParam() { return fromParam; }
            }
            """;

    @DisplayName("fields: final, assigned in every constructor, Java's default value, assigned null, initialized null")
    @Test
    public void fields() {
        NullabilityPass.Report report = run("a.b.F", FIELDS);
        System.out.println(explain(report));
        assertEquals("""
                <init>(0:fin): String
                <init>(1:assigned): String
                assigned: String
                cleared: String?
                fin: String
                fromParam: String?
                getFromParam(): String?
                getLazy(): String
                initNull: String?
                lazy: String?
                setFromParam(0:s): String""", verdicts(report));
    }

    @Language("java")
    private static final String PARAMETERS = """
            package a.b;
            class P {
                private String f;
                void take(String s) { this.f = s; }
                void passNull() { take(null); }
                String keep(String k) { return k; }
                void passLocal(boolean b) { String x = b ? "a" : null; keep(x); }
                void never(String n) { System.out.println(n); }
                void callNever() { never("z"); }
                String tolerant(String t) { if (t == null) return ""; return t; }
            }
            """;

    @DisplayName("parameters: a null argument, a possibly-null local as argument, never null; the field it lands in")
    @Test
    public void parameters() {
        NullabilityPass.Report report = run("a.b.P", PARAMETERS);
        System.out.println(explain(report));
        assertEquals("""
                f: String?
                keep(): String?
                keep(0:k): String?
                never(0:n): String
                passLocal(0:b): boolean
                take(0:s): String?
                tolerant(): String
                tolerant(0:t): String?""", verdicts(report));
        // tolerant(): after 'if (t == null) return ""', t is known non-null where it is returned (M4)
    }

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
        NullabilityPass.Report report = run("a.b.O", OVERRIDES);
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
    private static final String LIBRARY = """
            package a.b;
            import java.util.*;
            class L {
                private final Map<String, String> map = new HashMap<>();
                private final NavigableMap<String, String> nav = new TreeMap<>();
                String lookup(String k) { return map.get(k); }
                String viaLocal(String k) { String v = map.get(k); return v; }
                Map.Entry<String, String> first() { return nav.firstEntry(); }
                int size() { return map.size(); }
                @Override public boolean equals(Object other) { return other == this; }
                @Override public int hashCode() { return 1; }
            }
            """;

    @DisplayName("JDK null contracts: Map.get and NavigableMap.firstEntry may return null; equals accepts null")
    @Test
    public void library() {
        NullabilityPass.Report report = run("a.b.L", LIBRARY);
        System.out.println(explain(report));
        assertEquals("""
                equals(): boolean
                equals(0:other): Object?
                first(): Entry<String, String>?
                hashCode(): int
                lookup(): String?
                lookup(0:k): String
                map: Map<String, String>
                nav: NavigableMap<String, String>
                size(): int
                viaLocal(): String?
                viaLocal(0:k): String""", verdicts(report));
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
        NullabilityPass.Report report = run("a.b.G", GENERIC_OVERRIDES);
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
    private static final String LOCALS = """
            package a.b;
            import java.util.List;
            class V {
                String reassigned(boolean b) { String x = null; if (b) x = "a"; return "z"; }
                void sibling(boolean b) {
                    if (b) { String s = null; System.out.println(s); }
                    else { String s = "t"; System.out.println(s); }
                }
                void loops(List<String> list) {
                    for (String e : list) { System.out.println(e); }
                    for (String f = null; f != null; ) { }
                }
                void caught() {
                    try { System.out.println("x"); } catch (RuntimeException ex) { System.out.println(ex); }
                }
                String flows() { String y = null; String w = y; return w; }
            }
            """;

    // every local verdict, sorted: 'method.name: Type', a counter per method and name so sibling locals both show
    private static String locals(NullabilityPass.Report report) {
        Map<String, Integer> seen = new java.util.HashMap<>();
        return report.locals().entrySet().stream()
                .map(e -> {
                    String key = e.getKey().methodInfo().name() + "." + e.getKey().name();
                    int n = seen.merge(key, 1, Integer::sum);
                    return key + (n > 1 ? "#" + n : "") + ": " + k(e.getValue());
                })
                .sorted().collect(Collectors.joining("\n"));
    }

    @DisplayName("locals: keyed by declaration, so same-named locals of sibling blocks have their own verdict")
    @Test
    public void locals() {
        NullabilityPass.Report report = run("a.b.V", LOCALS);
        System.out.println(explain(report));
        assertEquals("""
                caught.ex: RuntimeException
                flows.w: String?
                flows.y: String?
                loops.e: String
                loops.f: String?
                reassigned.x: String?
                sibling.s#2: String
                sibling.s: String?""", locals(report));
        assertEquals("String?", k(report.verdicts().entrySet().stream()
                .filter(e -> e.getKey() instanceof MethodInfo mi && "flows".equals(mi.name()))
                .findFirst().orElseThrow().getValue()));
        // the lookup the printer uses: by declaring element
        MethodInfo sibling = parsed.findUniqueMethod("sibling", 1);
        io.codelaser.maddi.cst.api.statement.Statement ifElse = sibling.methodBody().statements().getFirst();
        var first = (io.codelaser.maddi.cst.api.statement.LocalVariableCreation) ifElse.block().statements().getFirst();
        var second = (io.codelaser.maddi.cst.api.statement.LocalVariableCreation) ifElse.otherBlocksStream()
                .findFirst().orElseThrow().statements().getFirst();
        assertEquals("String?", k(report.local(sibling, first, first.localVariable())));
        assertEquals("String", k(report.local(sibling, second, second.localVariable())));
    }

    @Language("java")
    private static final String NESTED_LOCALS = """
            package a.b;
            import java.util.ArrayList;
            import java.util.LinkedList;
            import java.util.List;
            class N {
                static class Node { List<Node> succ = new ArrayList<>(); Node next; }
                void flatten(Node root) {
                    class StackEntry {
                        final Node node;
                        StackEntry(Node node) { this.node = node; }
                    }
                    LinkedList<StackEntry> stack = new LinkedList<>();
                    stack.add(new StackEntry(root));
                    mainloop:
                    while (!stack.isEmpty()) {
                        StackEntry statEntry = stack.removeFirst();
                        if (statEntry == null) continue mainloop;
                        Node source = statEntry.node;
                        if (source != null) {
                            for (int i = 0; i < source.succ.size(); i++) {
                                Node shortEntry = null;
                                Node longEntry = null;
                                if (i > 2) { shortEntry = source.succ.get(i); longEntry = source; }
                                while (true) {
                                    StackEntry entry = null;
                                    if (shortEntry != null) entry = new StackEntry(shortEntry);
                                    if (entry == null || longEntry == null) break;
                                    stack.add(entry);
                                }
                            }
                        }
                    }
                }
            }
            """;

    @DisplayName("locals initialized null, nested in loops after a local class declaration (fernflower)")
    @Test
    public void nestedLocals() {
        NullabilityPass.Report report = run("a.b.N", NESTED_LOCALS);
        String all = locals(report);
        System.out.println(all);
        org.junit.jupiter.api.Assertions.assertTrue(all.contains("flatten.shortEntry: Node?"), all);
        org.junit.jupiter.api.Assertions.assertTrue(all.contains("flatten.longEntry: Node?"), all);
        org.junit.jupiter.api.Assertions.assertTrue(all.contains("flatten.entry: StackEntry?"), all);
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
        NullabilityPass.Report report = run("a.b.C", CONTRACTS);
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
        NullabilityPass.Report report = run("a.b.C", CONTRACTS, NullabilityPass.Policy.NULL_MARKED.withoutContracts());
        Map<String, String> byLabel = report.verdicts().entrySet().stream()
                .collect(Collectors.toMap(e -> label(e.getKey()), e -> k(e.getValue())));
        assertEquals("String", byLabel.get("echo(0:s)"));
        assertEquals("String?", byLabel.get("<init>(0:t)"));
        assertEquals("String?", byLabel.get("guarded"));
        assertEquals("String", byLabel.get("maybe()"));
    }

    @Language("java")
    private static final String DEGRADED = """
            package a.b;
            class D {
                String compute() { return "c"; }
                void big(boolean b) {
                    String known = compute();
                    String none = null;
                    if (b) none = known;
                    System.out.println(none);
                }
            }
            """;

    @DisplayName("a degraded method (no links): a local assigned null is still seen, the others are unspecified")
    @Test
    public void degradedLocals() {
        TypeInfo t = javaInspector.parse("a.b.D", DEGRADED);
        List<Info> ao = prepWork(t);
        new SingleIterationAnalyzerImpl(javaInspector,
                new IteratingAnalyzerImpl.ConfigurationBuilder().setNullability(true).build()).go(ao, 3);
        MethodInfo big = t.findUniqueMethod("big", 1);
        // what LinkComputerImpl writes when it gives up on a method; its variable data then carries no links
        big.analysis().setAllowControlledOverwrite(io.codelaser.maddi.cst.impl.analysis.PropertyImpl.DEGRADED_ANALYSIS_METHOD,
                io.codelaser.maddi.cst.impl.analysis.ValueImpl.BoolImpl.TRUE);
        NullabilityPass.Report report = new NullabilityPass(NullabilityPass.Policy.NULL_MARKED).go(ao);
        String all = locals(report);
        org.junit.jupiter.api.Assertions.assertTrue(all.contains("big.none: String?"), all);
        org.junit.jupiter.api.Assertions.assertTrue(all.contains("big.known: String!"), all);
    }

    @Language("java")
    private static final String USE_SITES = """
            package a.b;
            import java.util.HashMap;
            import java.util.Map;
            class U {
                private final Map<String, String> map = new HashMap<>();
                private String cache;
                U() { cache = "c"; }
                String lookup(String k) {
                    String v = map.get(k);
                    if (v == null) return "default";
                    cache = v;
                    return v;
                }
                void passOn(String k) {
                    String v = map.get(k);
                    if (v != null) {
                        take(v);
                    }
                }
                void take(String t) { System.out.println(t); }
                int deref(String s) {
                    String w = map.get(s);
                    int n = w.length();
                    keep(w);
                    return n;
                }
                void keep(String x) { System.out.println(x); }
                void requireIt(String p) {
                    if (p == null) throw new IllegalArgumentException();
                    System.out.println(p);
                }
                String unguarded(String k) { String u = map.get(k); return u; }
                private String lazy;
                String getLazy() { String result = lazy; return result == null ? lazy = "z" : result; }
            }
            """;

    @DisplayName("use sites (M4): a null checked away, dereferenced away, or thrown on does not travel")
    @Test
    public void useSites() {
        NullabilityPass.Report report = run("a.b.U", USE_SITES);
        System.out.println(explain(report));
        Map<String, String> byLabel = report.verdicts().entrySet().stream()
                .collect(Collectors.toMap(e -> label(e.getKey()), e -> k(e.getValue())));
        assertEquals("String", byLabel.get("cache"), "assigned after 'if (v == null) return'");
        assertEquals("String", byLabel.get("lookup()"), "returned after the guard");
        assertEquals("String", byLabel.get("take(0:t)"), "passed inside 'if (v != null)'");
        assertEquals("String", byLabel.get("keep(0:x)"), "passed after 'w.length()'");
        // a null test is an expectation of null, even when its branch throws (guava's checking methods)
        assertEquals("String?", byLabel.get("requireIt(0:p)"));
        assertEquals("String?", byLabel.get("unguarded()"), "no check: Map.get's null travels");
        assertEquals("String?", byLabel.get("lazy"), "Java's default value");
        assertEquals("String", byLabel.get("getLazy()"), "the lazy getter: null only in the branch that assigns");
        String locals = locals(report);
        org.junit.jupiter.api.Assertions.assertTrue(locals.contains("lookup.v: String?"), locals);
    }

    @Language("java")
    private static final String SMART_CASTS = """
            package a.b;
            import java.util.Objects;
            class K {
                int checked(String s) { if (s == null) return 0; return s.length(); }
                int required(String r) { Objects.requireNonNull(r); return r.length(); }
            }
            """;

    @DisplayName("smart casts: only what Kotlin derives too; requireNonNull is Java's, not Kotlin's")
    @Test
    public void smartCasts() {
        NullabilityPass.Report report = run("a.b.K", SMART_CASTS);
        io.codelaser.maddi.cst.api.info.MethodInfo checked = parsed.findUniqueMethod("checked", 1);
        io.codelaser.maddi.cst.api.statement.Statement ret = checked.methodBody().statements().getLast();
        assertEquals(true, report.useSites().nonNullAt(ret, checked.parameters().getFirst()));
        assertEquals(true, report.smartCasts().nonNullAt(ret, checked.parameters().getFirst()));
        io.codelaser.maddi.cst.api.info.MethodInfo required = parsed.findUniqueMethod("required", 1);
        io.codelaser.maddi.cst.api.statement.Statement ret2 = required.methodBody().statements().getLast();
        ParameterInfo r = required.parameters().getFirst();
        // the JDK hint: requireNonNull's parameter demands non-null, so Java knows; Kotlin does not smart-cast
        assertEquals(true, report.useSites().nonNullAt(ret2, r));
        assertEquals(false, report.smartCasts().nonNullAt(ret2, r));
    }

    @Language("java")
    private static final String IN_STATEMENT = """
            package a.b;
            import java.util.HashMap;
            import java.util.Map;
            class S {
                private final Map<String, String> map = new HashMap<>();
                private String lazy;
                private final String fin;
                S(String fin) { this.fin = fin; }
                boolean both(String k) { String v = map.get(k); return v != null && accept(v); }
                boolean accept(String a) { return a.isEmpty(); }
                int either(String k) { String w = map.get(k); return w == null ? 0 : measure(w); }
                int measure(String m) { return m.length(); }
                String getLazy() {
                    if (lazy == null) {
                        lazy = "computed";
                    }
                    return lazy;
                }
                String afterCall() {
                    if (lazy == null) return "none";
                    accept("x");
                    return lazy;
                }
                private String cached;
                String create() { return "fresh"; }
                String cached() { String result = cached; return result == null ? cached = create() : result; }
            }
            """;

    @DisplayName("inside a statement (&&, ?:) and fields of this (forgotten at a call)")
    @Test
    public void inStatementAndFields() {
        NullabilityPass.Report report = run("a.b.S", IN_STATEMENT);
        System.out.println(explain(report));
        Map<String, String> byLabel = report.verdicts().entrySet().stream()
                .collect(Collectors.toMap(e -> label(e.getKey()), e -> k(e.getValue())));
        assertEquals("String", byLabel.get("accept(0:a)"), "'v != null && accept(v)'");
        assertEquals("String", byLabel.get("measure(0:m)"), "'w == null ? 0 : measure(w)'");
        assertEquals("String?", byLabel.get("lazy"));
        assertEquals("String", byLabel.get("getLazy()"), "the field is non-null after 'if (lazy == null) lazy = ...'");
        assertEquals("String?", byLabel.get("afterCall()"), "a call in between may have reset the field");
        // round 2: create() was found non-null in round 1, so 'cached = create()' assigns a non-null value
        assertEquals("String", byLabel.get("cached()"), "the lazy getter with a factory method");
        assertEquals("String?", byLabel.get("cached"));
        // the printer's view: in 'v != null && accept(v)', the call has v; Kotlin smart-casts it too
        MethodInfo both = parsed.findUniqueMethod("both", 1);
        io.codelaser.maddi.cst.api.statement.Statement ret = both.methodBody().statements().getLast();
        java.util.List<io.codelaser.maddi.cst.api.expression.MethodCall> calls = new java.util.ArrayList<>();
        ret.expression().visit(e -> {
            if (e instanceof io.codelaser.maddi.cst.api.expression.MethodCall mc
                && "accept".equals(mc.methodInfo().name())) calls.add(mc);
            return true;
        });
        io.codelaser.maddi.cst.api.expression.MethodCall accept = calls.getFirst();
        io.codelaser.maddi.cst.api.variable.Variable v = ((io.codelaser.maddi.cst.api.statement.LocalVariableCreation)
                both.methodBody().statements().getFirst()).localVariable();
        assertEquals(true, report.smartCasts().nonNullAt(accept, v));
    }

    @Language("java")
    private static final String WORLD = """
            package a.b;
            import java.util.Comparator;
            public class W {
                private String stored = "";
                public void store(String s) { this.stored = s; }
                public int length(String s) { return s.length(); }
                public String echo(String s) { return s; }
                public String checked(String s) {
                    if (s == null) throw new IllegalArgumentException();
                    return s;
                }
                public String early(String s, boolean b) {
                    if (b) return "x";
                    return s.trim();
                }
                int internal(String s) { return 1; }
                private String helper(String s) { return s; }
                public String use() { return helper("y") + internal("z") + stored; }
                static final class Hidden implements Comparator<String> {
                    public int compare(String a, String b) { return 0; }
                    public void m(String s) { }
                }
            }
            """;

    @DisplayName("world: closed decides from the analysed calls; open makes outside-callable parameters unspecified")
    @Test
    public void world() {
        String closed = verdicts(run("a.b.W", WORLD));
        assertEquals("""
                checked(): String
                checked(0:s): String?
                compare(): int
                compare(0:a): String
                compare(1:b): String
                early(): String
                early(0:s): String
                early(1:b): boolean
                echo(): String
                echo(0:s): String
                helper(): String
                helper(0:s): String
                internal(): int
                internal(0:s): String
                length(): int
                length(0:s): String
                m(0:s): String
                store(0:s): String
                stored: String
                use(): String""", closed);
        // a public parameter no analysed call passes null to: unspecified, and so is what it flows into; the
        // package-private and private methods, and the public method of a package-private type, stay closed-world; an
        // override of a library method is called by the library
        assertEquals("""
                checked(): String
                checked(0:s): String?
                compare(): int
                compare(0:a): String!
                compare(1:b): String!
                early(): String
                early(0:s): String!
                early(1:b): boolean
                echo(): String!
                echo(0:s): String!
                helper(): String
                helper(0:s): String
                internal(): int
                internal(0:s): String
                length(): int
                length(0:s): String!
                m(0:s): String
                store(0:s): String!
                stored: String!
                use(): String""", verdicts(new NullabilityPass(
                NullabilityPass.Policy.NULL_MARKED.withWorld(NullabilityPass.World.OPEN_VISIBILITY)).go(analysisOrder)));
        // with preconditions: dereferenced on every normal exit is non-null; 'early' returns before the dereference
        assertEquals("""
                checked(): String
                checked(0:s): String?
                compare(): int
                compare(0:a): String!
                compare(1:b): String!
                early(): String
                early(0:s): String!
                early(1:b): boolean
                echo(): String!
                echo(0:s): String!
                helper(): String
                helper(0:s): String
                internal(): int
                internal(0:s): String
                length(): int
                length(0:s): String
                m(0:s): String
                store(0:s): String!
                stored: String!
                use(): String""", verdicts(new NullabilityPass(
                NullabilityPass.Policy.NULL_MARKED.withWorld(NullabilityPass.World.OPEN)).go(analysisOrder)));
    }

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
        NullabilityPass.Report report = run("a.b.A", ARRAYS);
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
        NullabilityPass.Report report = run("a.b.G", GENERICS);
        System.out.println(explain(report));
        // 'intBox' is an over-approximation: a null in Box.t (from 'boxNull') reaches the slot of every Box
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
                get(): T?
                intBox(): Box<Integer?>
                loop(): String?
                names: List<String?>
                opt(): Optional<String>
                opt(0:s): String
                putNull(0:k): String
                set(0:t): T?
                t: T?
                unbox(): String?
                unbox(0:b): Box<String?>""", verdicts(report));
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
                void a(String k) { fromMap.add(map.get(k)); }
                void b(String k) { String v = map.get(k); viaLocal.add(v); }
                void c() { literal.add(null); }
                void d(String k) { slots[0] = map.get(k); }
            }
            """;

    @DisplayName("Policy.assertContentWrites: a library-nullable value written into content is asserted, not spread")
    @Test
    public void assertContentWrites() {
        NullabilityPass.Report java = run("a.b.W", CONTENT_WRITES);
        assertEquals("""
                a(0:k): String
                b(0:k): String
                d(0:k): String
                fromMap: List<String?>
                literal: List<String?>
                map: Map<String, String>
                slots: String?[]
                viaLocal: List<String?>""", verdicts(java));
        // Kotlin: 'fromMap.add(map.get(k)!!)', 'viaLocal.add(v!!)', 'slots[0] = map.get(k)!!'; the literal null stays
        NullabilityPass.Report kotlin = new NullabilityPass(NullabilityPass.Policy.KOTLIN).go(analysisOrder);
        assertEquals("""
                a(0:k): String
                b(0:k): String
                d(0:k): String
                fromMap: List<String>
                literal: List<String?>
                map: Map<String, String>
                slots: String[]
                viaLocal: List<String>""", verdicts(kotlin));
    }
}
