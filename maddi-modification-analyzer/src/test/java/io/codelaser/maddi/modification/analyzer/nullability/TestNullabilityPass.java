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

import io.codelaser.maddi.cst.api.statement.IfElseStatement;
import io.codelaser.maddi.cst.api.statement.Statement;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
        NullabilityPass.Report java = run("a.b.W", CONTENT_WRITES);
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
        NullabilityPass.Report kotlin = new NullabilityPass(NullabilityPass.Policy.KOTLIN).go(analysisOrder);
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
        NullabilityPass.Report report = run("a.b.C", COPIES, NullabilityPass.Policy.KOTLIN);
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

    @DisplayName("use sites: a loop condition sees what the loop assigns; an old-style case does not see the one before")
    @Test
    public void loopsAndCases() {
        NullabilityPass.Report report = run("a.b.N", """
                package a.b;
                class N {
                    N parent;
                    String name = "";
                    static String top(N n) {
                        n.name.length();
                        while (n.parent != null) { n = n.parent; }
                        return n.name;
                    }
                    static int cases(int k, String s) {
                        switch (k) {
                            case 0: s.length(); break;
                            case 2: return s.length();
                        }
                        return 0;
                    }
                    static int arms(int k, String s) {
                        return switch (k) { case 0 -> s.length(); case 1 -> s.hashCode(); default -> 0; };
                    }
                }
                """);
        TypeInfo n = parsed;
        MethodInfo top = n.findUniqueMethod("top", 1);
        ParameterInfo np = top.parameters().getFirst();
        io.codelaser.maddi.cst.api.statement.Statement loop = top.methodBody().statements().get(1);
        assertEquals(false, report.useSites().nonNullAt(loop, np), "the loop assigns n: not known at its condition");
        MethodInfo cases = n.findUniqueMethod("cases", 2);
        ParameterInfo s = cases.parameters().get(1);
        io.codelaser.maddi.cst.api.statement.Statement sw = cases.methodBody().statements().getFirst();
        io.codelaser.maddi.cst.api.statement.Statement returnInCase2 = sw.subBlockStream().findFirst().orElseThrow()
                .statements().get(2);
        assertEquals(false, report.useSites().nonNullAt(returnInCase2, s), "case 2 is not reached through case 0");
        MethodInfo arms = n.findUniqueMethod("arms", 2);
        ParameterInfo armsS = arms.parameters().get(1);
        List<io.codelaser.maddi.cst.api.expression.MethodCall> calls = new java.util.ArrayList<>();
        arms.methodBody().statements().getFirst().expression().visit(e -> {
            if (e instanceof io.codelaser.maddi.cst.api.expression.MethodCall mc) calls.add(mc);
            return true;
        });
        assertEquals("hashCode", calls.get(1).methodInfo().name());
        assertEquals(false, report.smartCasts().nonNullAt(calls.get(1), armsS), "an arm does not see the one before");
    }

    @DisplayName("lambdas: a lambda's parameter takes the nullability of the functional method's parameter")
    @Test
    public void lambdas() {
        NullabilityPass.Report report = run("a.b.F", """
                package a.b;
                import java.util.*;
                class F {
                    interface I { int p(String s); }
                    static void each(I i) { i.p(null); }
                    static void use() {
                        each(s -> s.length());
                        each(new I() { public int p(String t) { return t.length(); } });
                    }
                }
                """);
        System.out.println(explain(report));
        Map<String, String> byName = report.verdicts().entrySet().stream()
                .collect(Collectors.toMap(e -> e.getKey().fullyQualifiedName(), e -> k(e.getValue())));
        assertEquals("String?", byName.get("a.b.F.I.p(String):0:s"));
        assertEquals("String?", byName.get("a.b.F.$0.p(String):0:s"), "the lambda's parameter");
        assertEquals("String?", byName.get("a.b.F.$1.p(String):0:t"), "the anonymous class's parameter");
    }

    @DisplayName("guards: a null-checked conditional with a call branch; a local passed to a constructor once assigned")
    @Test
    public void guards() {
        NullabilityPass.Report report = run("a.b.G", """
                package a.b;
                import java.util.*;
                class G {
                    static class Holder {
                        private final List<String> items;
                        Holder(List<String> items) { this.items = items == null ? Collections.emptyList() : items; }
                    }
                    static class Box {
                        private final Holder holder;
                        Box(Holder holder) { this.holder = holder; }
                    }
                    static Holder make(boolean b) {
                        if (b) return new Holder(null);
                        return new Holder(new ArrayList<>());
                    }
                    static Box box(boolean b) {
                        Holder h = null;
                        Box box = null;
                        if (b) {
                            h = make(b);
                            box = new Box(h);
                        }
                        return box;
                    }
                }
                """);
        System.out.println(explain(report));
        Map<String, String> byName = report.verdicts().entrySet().stream()
                .collect(Collectors.toMap(e -> e.getKey().fullyQualifiedName(), e -> k(e.getValue())));
        assertEquals("List<String>", byName.get("a.b.G.Holder.items"));
        assertEquals("Holder", byName.get("a.b.G.Box.holder"));
    }

    @DisplayName("a null returned through a call: the chain names the callee, not a null written in the caller")
    @Test
    public void returnedNull() {
        NullabilityPass.Report report = run("a.b.R", """
                package a.b;
                class R {
                    String name = "n";
                    String find(String key) { if (key.isEmpty()) return null; return key + name; }
                    String relay(String key) { return find(key); }
                    String relay2(String key) { String r = relay(key); return r; }
                    void take(String s) { }
                    void pass(String key) { take(find(key)); }
                }
                """);
        System.out.println(explain(report));
        TypeInfo r = parsed;
        MethodInfo find = r.findUniqueMethod("find", 1);
        MethodInfo relay = r.findUniqueMethod("relay", 1);
        MethodInfo relay2 = r.findUniqueMethod("relay2", 1);
        ParameterInfo s = r.findUniqueMethod("take", 1).parameters().getFirst();
        assertEquals("return a.b.R.find(String) <- null in a.b.R.find(String)", report.explain(find));
        assertEquals("return a.b.R.relay(String) <- return a.b.R.find(String) <- null in a.b.R.find(String)",
                report.explain(relay));
        assertTrue(report.explain(relay2).contains("null in a.b.R.find(String)"), report.explain(relay2));
        assertTrue(report.explain(s).contains("null in a.b.R.find(String)"), report.explain(s));
    }

    @DisplayName("library factories: an argument for the method's type variable is a value of the result's slot")
    @Test
    public void factories() {
        NullabilityPass.Report report = run("a.b.Y", """
                package a.b;
                import java.util.*;
                class Y {
                    private static final Map<Integer, Integer[]> TABLE = Map.of(1, new Integer[]{2, null}, 3, new Integer[]{4});
                    private static final List<String> NAMES = Arrays.asList("a", null);
                    private static final List<String> CLEAN = List.of("a", "b");
                    static List<String> wrap(String s) { String t = s.isEmpty() ? null : s; return Arrays.asList(t, "x"); }
                }
                """);
        System.out.println(explain(report));
        Map<String, String> byName = report.verdicts().entrySet().stream()
                .collect(Collectors.toMap(e -> e.getKey().fullyQualifiedName(), e -> k(e.getValue())));
        assertEquals("Map<Integer, Integer?[]>", byName.get("a.b.Y.TABLE"));
        assertEquals("List<String?>", byName.get("a.b.Y.NAMES"));
        assertEquals("List<String>", byName.get("a.b.Y.CLEAN"));
        assertEquals("List<String?>", byName.get("a.b.Y.wrap(String)"));
    }

    @DisplayName("an analysed generic class: a null written through a receiver goes to its slot, not to 'T?'")
    @Test
    public void classTypeVariables() {
        NullabilityPass.Report report = run("a.b.Q", """
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
        NullabilityPass.Report report = run("a.b.M", """
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
                """, NullabilityPass.Policy.KOTLIN);
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
        NullabilityPass.Report report = run("a.b.A", """
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
        // CodeLaser/maddi-mod#22 gap 4: the creation is an assignment, to a local declared before or to a field in the constructor
        assertEquals("String[]", byName.get("a.b.A.assigned(int)"), "assigned after its declaration");
        assertEquals("String[]", byName.get("a.b.A.Engine.workers"), "a field the constructor's loop fills");
        assertEquals("String", byName.get("a.b.A.Engine.worker(int)"));
        assertEquals("String?[]", byName.get("a.b.A.Engine.idle"), "not a statement of the body");
    }

    @DisplayName("a reassigned parameter: the parameter is the caller's value, what the body assigns is a shadow (issue #22 gap 5)")
    @Test
    public void reassignedParameter() {
        NullabilityPass.Report report = run("a.b.R", """
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
        assertEquals("String?", byName.get("a.b.R.loop(String)"));
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

    @DisplayName("a map lookup whose key is known present is not the absent key's null (issue #22 gap 1)")
    @Test
    public void keyPresence() {
        NullabilityPass.Report report = run("a.b.K", """
                package a.b;
                import java.util.*;
                class K {
                    String guarded(Map<String, String> m, String k) { if (m.containsKey(k)) { return m.get(k); } return ""; }
                    String early(Map<String, String> m, String k) { if (!m.containsKey(k)) return ""; return m.get(k); }
                    String unguarded(Map<String, String> m, String k) { return m.get(k); }
                    String afterPut(Map<String, String> m, String k) { m.put(k, "v"); return m.get(k); }
                    String afterPutNull(Map<String, String> m, String k) { m.put(k, null); return m.get(k); }
                    String loop(Map<String, String> m) { for (String k : m.keySet()) { return m.get(k); } return ""; }
                    String cleared(Map<String, String> m, String k) { if (m.containsKey(k)) { m.clear(); return m.get(k); } return ""; }
                    String otherCall(Map<String, String> m, String k, List<String> l) { if (m.containsKey(k)) { l.add(k); return m.get(k); } return ""; }
                    String reassigned(Map<String, String> m, String k) { if (m.containsKey(k)) { k = k + "x"; return m.get(k); } return ""; }
                    String passed(Map<String, String> m, String k) { if (m.containsKey(k)) { use(m); return m.get(k); } return ""; }
                    static void use(Map<String, String> m) { m.clear(); }
                    String removed(Map<String, String> m, String k) { if (m.containsKey(k)) { return m.remove(k); } return ""; }
                    int length(Map<String, String> m, String k) { if (m.containsKey(k)) { return m.get(k).length(); } return 0; }
                }
                """);
        System.out.println(explain(report));
        Map<String, String> byName = report.verdicts().entrySet().stream()
                .collect(Collectors.toMap(e -> e.getKey().fullyQualifiedName(), e -> k(e.getValue())));
        assertEquals("String", byName.get("a.b.K.guarded(java.util.Map,String)"));
        assertEquals("String", byName.get("a.b.K.early(java.util.Map,String)"));
        assertEquals("String?", byName.get("a.b.K.unguarded(java.util.Map,String)"));
        assertEquals("String", byName.get("a.b.K.afterPut(java.util.Map,String)"));
        assertEquals("String?", byName.get("a.b.K.afterPutNull(java.util.Map,String)"), "the value put is null");
        assertEquals("String", byName.get("a.b.K.loop(java.util.Map)"));
        assertEquals("String?", byName.get("a.b.K.cleared(java.util.Map,String)"), "a call on the map");
        assertEquals("String", byName.get("a.b.K.otherCall(java.util.Map,String,java.util.List)"));
        assertEquals("String?", byName.get("a.b.K.reassigned(java.util.Map,String)"), "the key is assigned");
        assertEquals("String?", byName.get("a.b.K.passed(java.util.Map,String)"), "the map is handed to a call");
        assertEquals("String", byName.get("a.b.K.removed(java.util.Map,String)"));
    }

    @DisplayName("a null returned by a method that may return its argument does not flow back into the argument")
    @Test
    public void identityReturn() {
        NullabilityPass.Report report = run("a.b.I", """
                package a.b;
                import java.util.*;
                class I {
                    private final Map<String, String> map = new HashMap<>();
                    private String check(String name) {
                        if (map.containsKey(name)) return name;
                        String noDot = name.replace('.', '_');
                        if (!name.equals(noDot) && map.containsKey(noDot)) return noDot;
                        return null;
                    }
                    String get(String key) {
                        String checked = check(key);
                        if (checked == null) {
                            final String upper = key.toUpperCase();
                            if (!upper.equals(key)) {
                                checked = check(upper);
                            }
                        }
                        if (checked == null) return null;
                        return map.get(checked);
                    }
                }
                """);
        System.out.println(explain(report));
        Map<String, String> byName = report.verdicts().entrySet().stream()
                .collect(Collectors.toMap(e -> e.getKey().fullyQualifiedName(), e -> k(e.getValue())));
        assertEquals("String?", byName.get("a.b.I.check(String)"));
        assertEquals("String", byName.get("a.b.I.get(String):0:key"), "the return's null is not the argument's");
        assertEquals("String", byName.get("a.b.I.check(String):0:name"));
    }

    @DisplayName("null-check predicates: an analysed one inferred, as a condition; not for Kotlin's smart casts")
    @Test
    public void predicates() {
        NullabilityPass.Report report = run("a.b.P", """
                package a.b;
                import java.util.*;
                class P {
                    static boolean isBlank(CharSequence cs) {
                        int len;
                        if (cs == null || (len = cs.length()) == 0) return true;
                        for (int i = 0; i < len; i++) { if (!Character.isWhitespace(cs.charAt(i))) return false; }
                        return true;
                    }
                    static boolean isNotBlank(String s) { return !isBlank(s); }
                    static boolean isEmpty(String s) { return s == null || s.length() == 0; }
                    static boolean hasText(String s) { return s != null && !s.isEmpty(); }
                    static String trimmed(String in) {
                        if (isNotBlank(in)) return in.trim();
                        return "";
                    }
                    static String other(String in) {
                        return isEmpty(in) ? "" : in.trim();
                    }
                    static int viaObjects(String in) {
                        if (Objects.isNull(in)) return 0;
                        return in.length();
                    }
                }
                """);
        TypeInfo p = parsed;
        MethodInfo trimmed = p.findUniqueMethod("trimmed", 1);
        ParameterInfo in = trimmed.parameters().getFirst();
        IfElseStatement ifElse = (IfElseStatement) trimmed.methodBody().statements().getFirst();
        Statement then = ifElse.block().statements().getFirst();
        assertEquals(true, report.useSites().nonNullAt(then, in), "isNotBlank is false for null");
        assertEquals(false, report.smartCasts().nonNullAt(then, in), "Kotlin does not see it");
        MethodInfo viaObjects = p.findUniqueMethod("viaObjects", 1);
        Statement last = viaObjects.methodBody().statements().getLast();
        assertEquals(true, report.useSites().nonNullAt(last, viaObjects.parameters().getFirst()),
                "Objects.isNull, from the table");
        MethodInfo other = p.findUniqueMethod("other", 1);
        List<io.codelaser.maddi.cst.api.expression.MethodCall> calls = new java.util.ArrayList<>();
        other.methodBody().statements().getFirst().expression().visit(e -> {
            if (e instanceof io.codelaser.maddi.cst.api.expression.MethodCall mc) calls.add(mc);
            return true;
        });
        io.codelaser.maddi.cst.api.expression.MethodCall trim = calls.stream()
                .filter(c -> "trim".equals(c.methodInfo().name())).findFirst().orElseThrow();
        assertEquals(true, report.useSites().nonNullAt(trim, other.parameters().getFirst()), "isEmpty is true for null");
    }

    @DisplayName("Kotlin: an analysed generic method's own null (E?) reaches the variable its result is assigned to")
    @Test
    public void classTypeVariableResults() {
        NullabilityPass.Report report = run("a.b.V", """
                package a.b;
                import java.util.*;
                class V {
                    static class VB<E, K> extends ArrayList<E> {
                        private final Map<K, Integer> map = new HashMap<>();
                        void addWithKey(E element, K key) { map.put(key, size()); super.add(element); }
                        E getWithKey(K key) {
                            Integer index = map.get(key);
                            if (index == null) return null;
                            return super.get(index);
                        }
                    }
                    static class Block { int id; }
                    private final VB<Block, Integer> blocks = new VB<>();
                    private final VB<Block, Integer> holes = new VB<>();
                    private final Map<String, Object> attributes = new HashMap<>();
                    void fill(Block b) { blocks.addWithKey(b, b.id); holes.add(null); }
                    Block first() { Block b = holes.get(0); return b; }
                    @SuppressWarnings("unchecked")
                    <T> T attribute(String name, Class<T> type) { return (T) attributes.get(name); }
                    String name() { String n = attribute("name", String.class); return n; }
                    <X> X id(X x) { return x; }
                    String same(String s) { String t = id(s); return t; }
                    List<Block> range(int from, int to) {
                        List<Block> out = new ArrayList<>();
                        for (int j = from; j < to; j++) {
                            Block block = blocks.getWithKey(j);
                            out.add(block);
                        }
                        return out;
                    }
                }
                """, NullabilityPass.Policy.KOTLIN);
        System.out.println(explain(report));
        Map<String, String> byName = report.verdicts().entrySet().stream()
                .collect(Collectors.toMap(e -> e.getKey().fullyQualifiedName(), e -> k(e.getValue())));
        assertEquals("E?", byName.get("a.b.V.VB.getWithKey(Object)"));
        // 'block = blocks.getWithKey(j)' is nullable, and so is what it is added to (fernflower ControlFlowGraph:351)
        assertEquals("List<Block?>", byName.get("a.b.V.range(int,int)"));
        assertEquals("VB<Block, Integer>", byName.get("a.b.V.blocks"), "the slot itself holds no null");
        // a list method on a two-argument subclass: ArrayList's E is the receiver's argument 0
        assertEquals("VB<Block?, Integer>", byName.get("a.b.V.holes"));
        assertEquals("Block?", byName.get("a.b.V.first()"));
        // a method type variable's own null (a map lookup), but not one a parameter of that type carries
        assertEquals("String?", byName.get("a.b.V.name()"));
        assertEquals("String", byName.get("a.b.V.same(String)"));
    }

    @DisplayName("two alternatives of one argument are not each other's value")
    @Test
    public void alternativesInAnArgument() {
        NullabilityPass.Report report = run("a.b.T", """
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

    @DisplayName("a java.util lookup's Object argument is not known non-null after the call, for Kotlin")
    @Test
    public void lookupArguments() {
        NullabilityPass.Report report = run("a.b.L2", """
                package a.b;
                import java.util.*;
                class L2 {
                    static class Block { List<Block> preds = new ArrayList<>(); }
                    static int count(Set<Block> blocks, List<Block> lst) {
                        int n = 0;
                        for (int i = 0; i < lst.size(); i++) {
                            Block child = lst.get(i);
                            if (!blocks.contains(child)) {
                                n += child.preds.size();
                            }
                        }
                        return n;
                    }
                }
                """, NullabilityPass.Policy.KOTLIN);
        MethodInfo count = parsed.findUniqueMethod("count", 2);
        List<io.codelaser.maddi.cst.api.expression.VariableExpression> derefs = new java.util.ArrayList<>();
        List<Statement> inner = new java.util.ArrayList<>();
        count.methodBody().visit(e -> {
            if (e instanceof IfElseStatement ifElse) inner.add(ifElse.block().statements().getFirst());
            return true;
        });
        Statement use = inner.getFirst();
        io.codelaser.maddi.cst.api.variable.Variable child = report.smartCasts().before(use).stream()
                .filter(v -> v.simpleName().equals("child")).findFirst().orElse(null);
        System.out.println("SMART " + report.smartCasts().before(use) + " JAVA " + report.useSites().before(use));
        assertEquals(null, child, "contains(Object) accepts null: Kotlin knows nothing about child");
    }

    @DisplayName("a try-with-resources resource is a declaration: its initializer's null reaches what it is passed to")
    @Test
    public void tryResources() {
        List<NullabilityPass.Policy> policies = List.of(NullabilityPass.Policy.NULL_MARKED, NullabilityPass.Policy.KOTLIN);
        for (int p = 0; p < policies.size(); p++) {
            NullabilityPass.Policy policy = policies.get(p);
            String name = "W" + p; // one parse per type and test
            NullabilityPass.Report report = run("a.b." + name, """
                    package a.b;
                    import java.io.*;
                    import java.util.*;
                    class %s {
                        private final Map<String, byte[]> links = new HashMap<>();
                        InputStream open(String name) {
                            byte[] data = links.get(name);
                            return data == null ? null : new ByteArrayInputStream(data);
                        }
                        static int create(InputStream in) throws IOException { return in.read(); }
                        int reload(String name) throws IOException {
                            try (InputStream in = open(name)) {
                                return create(in);
                            }
                        }
                    }
                    """.formatted(name), policy);
            Map<String, String> byName = report.verdicts().entrySet().stream()
                    .collect(Collectors.toMap(e -> e.getKey().fullyQualifiedName(), e -> k(e.getValue())));
            assertEquals("InputStream?", byName.get("a.b." + name + ".open(String)"), policy.toString());
            assertEquals("InputStream?", byName.get("a.b." + name + ".create(java.io.InputStream):0:in"),
                    "fernflower ContextUnit.reload, " + policy);
        }
    }

    @DisplayName("a null-checked array element (constant index) guards the argument it is passed as")
    @Test
    public void checkedElements() {
        NullabilityPass.Report report = run("a.b.E2", """
                package a.b;
                import java.util.*;
                class E2 {
                    static class Op { final List<String> operands = new ArrayList<>(); Op(String o) { operands.add(o); } }
                    static Object[] find(boolean b) { Object[] r = new Object[2]; if (b) r[0] = "x"; return r; }
                    static Op guarded(boolean b) {
                        Object[] res = find(b);
                        if (res[0] != null) return new Op((String) res[0]);
                        return null;
                    }
                    static Op afterCall(boolean b) {
                        Object[] res = find(b);
                        if (res[0] != null) { find(!b); return new Op2((String) res[0]).op; }
                        return null;
                    }
                    static class Op2 { final Op op; Op2(String s) { op = new Op("y"); take(s); } }
                    static void take(String s) { }
                }
                """, NullabilityPass.Policy.KOTLIN);
        System.out.println(explain(report));
        Map<String, String> byName = report.verdicts().entrySet().stream()
                .collect(Collectors.toMap(e -> e.getKey().fullyQualifiedName(), e -> k(e.getValue())));
        assertEquals("String", byName.get("a.b.E2.Op.<init>(String):0:o"), "fernflower AssertProcessor:186");
        assertEquals("List<String>", byName.get("a.b.E2.Op.operands"));
        assertEquals("String?", byName.get("a.b.E2.Op2.<init>(String):0:s"), "a call in between may write the array");
    }

    @DisplayName("statements in a switch expression's block arms are statements: their seeds, links and fills")
    @Test
    public void switchExpressionArms() {
        NullabilityPass.Report report = run("a.b.SA", """
                package a.b;
                class SA {
                    static String[] arm(int k, int n) {
                        return switch (k) {
                            case 0 -> {
                                String[] out = new String[n];
                                for (int i = 0; i < n; i++) out[i] = "x";
                                yield out;
                            }
                            default -> new String[0];
                        };
                    }
                    static void hole(int k) {
                        String r = switch (k) {
                            case 0 -> {
                                String s = null;
                                yield s;
                            }
                            default -> "x";
                        };
                        take(r);
                    }
                    static void take(String s) { }
                }
                """);
        System.out.println(explain(report));
        Map<String, String> byName = report.verdicts().entrySet().stream()
                .collect(Collectors.toMap(e -> e.getKey().fullyQualifiedName(), e -> k(e.getValue())));
        assertEquals("String[]", byName.get("a.b.SA.arm(int,int)"), "fernflower StructTypeAnnotationAttribute:52");
        assertEquals("String?", byName.get("a.b.SA.take(String):0:s"), "a null in a block arm");

        NullabilityPass.Report report2 = run("a.b.SB", """
                package a.b;
                class SB {
                    interface Info {
                        class Local implements Info {
                            private final Offsets[] table;
                            public Local(Offsets[] table) { this.table = table; }
                            public Offsets[] getTable() { return table; }
                            public static class Offsets {
                                private final int at;
                                public Offsets(int at) { this.at = at; }
                            }
                        }
                        class Empty implements Info { }
                    }
                    static Info parse(int k, int n) {
                        Info info = switch (k) {
                            case 0 -> new Info.Empty();
                            case 1 -> {
                                Info.Local.Offsets[] offsets = new Info.Local.Offsets[n];
                                for (int i = 0; i < n; i++) {
                                    offsets[i] = new Info.Local.Offsets(i);
                                }
                                yield new Info.Local(offsets);
                            }
                            default -> throw new RuntimeException("k " + k);
                        };
                        return info;
                    }
                }
                """);
        System.out.println(explain(report2));
        Map<String, String> byName2 = report2.verdicts().entrySet().stream()
                .collect(Collectors.toMap(e -> e.getKey().fullyQualifiedName(), e -> k(e.getValue())));
        assertEquals("Offsets[]", byName2.get("a.b.SB.Info.Local.table"), "fernflower TargetInfo.LocalvarTarget.table");
        // locals of a block arm have their own verdict: the printer declared 'offsets' as Array<Offsets?> without one
        assertEquals("""
                parse.i: int
                parse.info: Info
                parse.offsets: Offsets[]""", locals(report2));
        assertEquals("""
                arm.i: int
                arm.out: String[]
                hole.r: String?
                hole.s: String?""", locals(report));
    }

    @DisplayName("a library call's nullable result as an alternative of the value: a switch arm, a conditional branch")
    @Test
    public void callResultAlternatives() {
        NullabilityPass.Report report = run("a.b.SC", """
                package a.b;
                import java.util.Map;
                class SC {
                    private static final Map<String, Integer> TYPES = Map.of("a", 1);
                    static void rule(int k, String s) {
                        Object value;
                        value = switch (k) {
                            case 0 -> TYPES.get(s);
                            default -> s;
                        };
                        take(value);
                    }
                    static void branch(boolean b, String s) {
                        Object value = b ? TYPES.get(s) : s;
                        put(value);
                    }
                    static Object returned(int k, String s) {
                        return switch (k) {
                            case 0 -> s;
                            default -> {
                                String t = s.trim();
                                yield TYPES.get(t);
                            }
                        };
                    }
                    static void take(Object o) { }
                    static void put(Object o) { }
                }
                """);
        System.out.println(explain(report));
        Map<String, String> byName = report.verdicts().entrySet().stream()
                .collect(Collectors.toMap(e -> e.getKey().fullyQualifiedName(), e -> k(e.getValue())));
        assertEquals("Object?", byName.get("a.b.SC.take(Object):0:o"), "fernflower MatchEngine:124");
        assertEquals("Object?", byName.get("a.b.SC.put(Object):0:o"));
        assertEquals("Object?", byName.get("a.b.SC.returned(int,String)"));
        assertEquals("""
                branch.value: Object?
                returned.t: String
                rule.value: Object?""", locals(report));
    }

    @DisplayName("a null written through a getter's result goes to the receiver's slot, as through a variable")
    @Test
    public void slotThroughGetter() {
        NullabilityPass.Report report = run("a.b.SG", """
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

    @Language("java")
    private static final String ASSERTED = """
            package a.b;
            import java.util.*;
            class AD {
                static class Block {
                    void touch() { }
                }
                private final Map<Integer, Block> blocks = new HashMap<>();
                Block getWithKey(int j) { return blocks.containsKey(j) ? blocks.get(j) : null; }
                void edges(int from, int to, List<Block> range) {
                    for (int j = from; j < to; j++) {
                        Block block = getWithKey(j);
                        range.add(block);
                        block.touch();
                    }
                }
                void guarded(int j, List<Block> out) {
                    Block block = getWithKey(j);
                    out.add(block);
                    if (j > 0) block.touch();
                }
                void leaves(int j, List<Block> out2) {
                    Block block = getWithKey(j);
                    out2.add(block);
                    if (j < 0) return;
                    block.touch();
                }
                void reassigned(int j, List<Block> out3) {
                    Block block = getWithKey(j);
                    out3.add(block);
                    block.touch();
                    block = getWithKey(j + 1);
                }
                void nonNull(List<Block> out4) {
                    Block block = new Block();
                    out4.add(block);
                    block.touch();
                }
            }
            """;

    @DisplayName("Kotlin: a local its block dereferences unconditionally is asserted at its declaration")
    @Test
    public void assertedAtDeclaration() {
        NullabilityPass.Report report = run("a.b.AD", ASSERTED, NullabilityPass.Policy.KOTLIN);
        System.out.println(explain(report));
        Map<String, String> byName = report.verdicts().entrySet().stream()
                .collect(Collectors.toMap(e -> e.getKey().fullyQualifiedName(), e -> k(e.getValue())));
        assertEquals("List<Block>", byName.get("a.b.AD.edges(int,int,java.util.List):2:range"),
                "fernflower ControlFlowGraph.setExceptionEdges");
        assertEquals("List<Block?>", byName.get("a.b.AD.guarded(int,java.util.List):1:out"), "a conditional dereference");
        assertEquals("List<Block?>", byName.get("a.b.AD.leaves(int,java.util.List):1:out2"), "a return before it");
        assertEquals("List<Block?>", byName.get("a.b.AD.reassigned(int,java.util.List):1:out3"), "assigned again");
        assertEquals("""
                edges.block: Block
                edges.j: int
                guarded.block: Block?
                leaves.block: Block?
                nonNull.block: Block
                reassigned.block: Block?""", locals(report));
        // the printer's lookup, by declaring element
        List<String> asserted = new java.util.ArrayList<>();
        for (MethodInfo mi : parsed.methods()) {
            mi.methodBody().visit(e -> {
                if (e instanceof io.codelaser.maddi.cst.api.statement.LocalVariableCreation lvc) {
                    lvc.localVariableStream().filter(lv -> report.assertedAtDeclaration(mi, lvc, lv))
                            .forEach(lv -> asserted.add(mi.name() + "." + lv.simpleName()));
                }
                return true;
            });
        }
        assertEquals("[edges.block]", asserted.toString(), "not where nothing null arrives (nonNull)");

        NullabilityPass.Report java = run("a.b.AD2", ASSERTED.replace("class AD ", "class AD2 "));
        Map<String, String> javaByName = java.verdicts().entrySet().stream()
                .collect(Collectors.toMap(e -> e.getKey().fullyQualifiedName(), e -> k(e.getValue())));
        assertEquals("List<Block?>", javaByName.get("a.b.AD2.edges(int,int,java.util.List):2:range"),
                "Java annotations say what can happen");
    }

    @Language("java")
    private static final String LOOKUP = """
            package a.b;
            import java.util.*;
            class LK {
                static class Keyed<E> {
                    private final List<E> items = new ArrayList<>();
                    private final List<Integer> keys = new ArrayList<>();
                    E getWithKey(int key) {
                        int index = keys.indexOf(key);
                        if (index < 0) return null;
                        return items.get(index);
                    }
                    void addWithKey(E e, int key) { items.add(e); keys.add(key); }
                    E get(int i) { return items.get(i); }
                    int size() { return items.size(); }
                }
                static class Stmt {
                    int id;
                    Stmt first;
                    final Keyed<Stmt> stats = new Keyed<>();
                    Stmt() { }
                    Stmt(Stmt head) {
                        first = head;
                        stats.addWithKey(head, head.id);
                    }
                    Stmt(Stmt head, int kind) {
                        first = head;
                        stats.addWithKey(first, first.id);
                    }
                    void initSimpleCopy() {
                        if (stats.size() > 0) first = stats.get(0);
                    }
                    void replaceStatement(Stmt oldstat, Stmt newstat) {
                        stats.addWithKey(newstat, newstat.id);
                        if (first == oldstat) first = newstat;
                    }
                }
                static Stmt general(Stmt root, int headId) {
                    Stmt head = root.stats.getWithKey(headId);
                    return new Stmt(head);
                }
                static Stmt root(Stmt root, int headId) {
                    return new Stmt(root.stats.getWithKey(headId), 1);
                }
            }
            """;

    @DisplayName("'stats.addWithKey(first, first.id)': a later argument dereferences a field of this before the write")
    @Test
    public void dereferencedFieldOfThis() {
        NullabilityPass.Report report = run("a.b.LK", LOOKUP, NullabilityPass.Policy.KOTLIN);
        System.out.println(explain(report));
        Map<String, String> byName = report.verdicts().entrySet().stream()
                .collect(Collectors.toMap(e -> e.getKey().fullyQualifiedName(), e -> k(e.getValue())));
        assertEquals("E?", byName.get("a.b.LK.Keyed.getWithKey(int)"));
        assertEquals("Stmt?", byName.get("a.b.LK.Stmt.first"), "the value itself is still nullable");
        assertEquals("Keyed<Stmt>", byName.get("a.b.LK.Stmt.stats"), "fernflower RootStatement(head, dummyExit)");

        NullabilityPass.Report java = run("a.b.LK2", LOOKUP.replace("class LK ", "class LK2 "));
        Map<String, String> javaByName = java.verdicts().entrySet().stream()
                .collect(Collectors.toMap(e -> e.getKey().fullyQualifiedName(), e -> k(e.getValue())));
        assertEquals("E?", javaByName.get("a.b.LK2.Keyed.getWithKey(int)"));
    }

    @DisplayName("Kotlin: an asserted local is unobserved before its dereference only with the links' proof")
    @Test
    public void unobservedBeforeDereference() {
        NullabilityPass.Report report = run("a.b.UO", """
                package a.b;
                import java.util.*;
                class UO {
                    static class Block {
                        int id;
                        Block next;
                        void touch() { }
                    }
                    static String key(Map<Integer, Block> instrBlocks, int a, int b) {
                        Block from = instrBlocks.get(a);
                        Block to = instrBlocks.get(b);
                        return from.id + ":" + to.id;
                    }
                    static Block find(int j) { return j < 0 ? null : new Block(); }
                    static void range(int n, List<Block> protectedRange) {
                        for (int j = 0; j < n; j++) {
                            Block block = find(j);
                            protectedRange.add(block);
                            block.touch();
                        }
                    }
                    static void alias(Block holder, int j) {
                        Block block = holder.next;
                        System.out.println(holder.next);
                        block.touch();
                    }
                    static void next(int j) {
                        Block block = find(j);
                        block.touch();
                    }
                }
                """, NullabilityPass.Policy.KOTLIN);
        System.out.println(explain(report));
        List<String> asserted = new java.util.ArrayList<>();
        List<String> unobserved = new java.util.ArrayList<>();
        for (MethodInfo mi : parsed.methods()) {
            mi.methodBody().visit(e -> {
                if (e instanceof io.codelaser.maddi.cst.api.statement.LocalVariableCreation lvc) {
                    lvc.localVariableStream().forEach(lv -> {
                        if (report.assertedAtDeclaration(mi, lvc, lv)) asserted.add(mi.name() + "." + lv.simpleName());
                        if (report.unobservedBeforeDereference(mi, lvc, lv)) {
                            unobserved.add(mi.name() + "." + lv.simpleName());
                        }
                    });
                }
                return true;
            });
        }
        java.util.Collections.sort(asserted);
        java.util.Collections.sort(unobserved);
        assertEquals("[alias.block, key.from, key.to, next.block, range.block]", asserted.toString());
        // key.from: fernflower ControlFlowGraph:337, 'to = instrBlocks.get(...)' reads the container, not 'from';
        // range.block: ControlFlowGraph:351, 'protectedRange.add(block)' refers to it;
        // alias.block: 'holder.next' is the same value
        assertEquals("[key.from, key.to, next.block]", unobserved.toString());
    }

    @Language("java")
    private static final String RUNTIME_NULLS = """
            package a.b;
            import java.util.*;
            class RN {
                static class St { int id; }
                private final Map<Integer, List<String[]>> ranges = new HashMap<>();
                void saveEdge(int id, boolean monitor, boolean cont) {
                    ranges.computeIfAbsent(id, k -> new ArrayList<>())
                            .add(new String[]{"x", monitor ? "1" : null, cont ? "1" : null});
                }
                static boolean choice(St head, List<? super St> lst, int n) {
                    St post = null;
                    if (n > 2) post = new St();
                    lst.add(head);
                    lst.remove(post);
                    lst.add(0, post);
                    return true;
                }
                static void use(St head) {
                    List<St> lst = new ArrayList<>();
                    choice(head, lst, 3);
                }
                static int next(St sample, List<St> successors, boolean last) {
                    Map<String, St[]> mapNext = new HashMap<>();
                    for (St successor : successors) {
                        mapNext.put(sample.id + "#" + successor.id, new St[]{sample, successor, last ? successor : null});
                    }
                    return unique(new HashSet<>(mapNext.values()));
                }
                static int unique(Set<St[]> setNext) {
                    int n = 0;
                    for (St[] arr : setNext) if (arr[2] != null) n++;
                    return n;
                }
            }
            """;

    @DisplayName("nulls Kotlin must carry: a null alternative in an array initializer; a null added to a '? super' list")
    @Test
    public void runtimeNulls() {
        NullabilityPass.Report report = run("a.b.RN", RUNTIME_NULLS, NullabilityPass.Policy.KOTLIN);
        System.out.println(explain(report));
        Map<String, String> byName = report.verdicts().entrySet().stream()
                .collect(Collectors.toMap(e -> e.getKey().fullyQualifiedName(), e -> k(e.getValue())));
        assertEquals("Map<Integer, List<String?[]>>", byName.get("a.b.RN.ranges"),
                "fernflower FlattenStatementsHelper.saveEdge");
        assertEquals("List<St?>", byName.get("a.b.RN.choice(a.b.RN.St,java.util.List,int):1:lst"),
                "fernflower DecHelper.isChoiceStatement");
        assertTrue(locals(report).contains("next.mapNext: Map<String, St?[]>"),
                "fernflower FinallyProcessor.compareSubGraphsEx: " + locals(report));
        assertEquals("Set<St?[]>", byName.get("a.b.RN.unique(java.util.Set):0:setNext"),
                "fernflower FinallyProcessor.getUniqueNext(graph, new HashSet<>(mapNext.values()))");
    }

    @DisplayName("JDK hint: JarFile.getManifest() returns null for a jar without a manifest")
    @Test
    public void jarManifest() {
        NullabilityPass.Report report = run("a.b.JM", """
                package a.b;
                import java.io.IOException;
                import java.util.jar.JarFile;
                import java.util.jar.Manifest;
                class JM {
                    static class Unit {
                        private Manifest manifest;
                        void setManifest(Manifest manifest) { this.manifest = manifest; }
                    }
                    static void addSpace(JarFile archive, Unit unit) throws IOException {
                        unit.setManifest(archive.getManifest());
                    }
                }
                """, NullabilityPass.Policy.KOTLIN);
        System.out.println(explain(report));
        Map<String, String> byName = report.verdicts().entrySet().stream()
                .collect(Collectors.toMap(e -> e.getKey().fullyQualifiedName(), e -> k(e.getValue())));
        assertEquals("Manifest?", byName.get("a.b.JM.Unit.setManifest(java.util.jar.Manifest):0:manifest"),
                "fernflower StructContext.addSpace -> ContextUnit.setManifest");
        assertEquals("Manifest?", byName.get("a.b.JM.Unit.manifest"));
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
        NullabilityPass.Report report = run("a.b.CI", INSTANTIATION, NullabilityPass.Policy.KOTLIN);
        System.out.println(explain(report));
        Map<String, String> byName = report.verdicts().entrySet().stream()
                .collect(Collectors.toMap(e -> e.getKey().fullyQualifiedName(), e -> k(e.getValue())));
        String locals = locals(report);
        // the class stays generic in E: no 'E?' from one instantiation's null (fernflower FastFixedSetFactory.kt:19/33)
        assertEquals("FSet<E!>", byName.get("a.b.CI.Factory.spawn()"));
        assertEquals("Collection<E!>", byName.get("a.b.CI.Factory.<init>(java.util.Collection):0:set"));
        assertEquals("FSet<E!>", byName.get("a.b.CI.FSet.union(a.b.CI.FSet):0:other"));
        assertEquals("FSet<E!>", byName.get("a.b.CI.FSet.copy()"));
        // the class's own null does reach every instantiation
        assertEquals("List<E?>", byName.get("a.b.CI.Factory.all()"));
        assertTrue(locals.contains("dom.withHole: List<Node?>"), locals);
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
        NullabilityPass.Report java = run("a.b.CJ", INSTANTIATION.replace("class CI", "class CJ"),
                NullabilityPass.Policy.NULL_MARKED);
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

    // fernflower's SwitchPatternHelper: case values through two stream pipelines and two records
    @Language("java")
    private static final String STREAMS = """
            package a.b;
            import java.util.*;
            import java.util.stream.*;
            class SP {
                static class Exprent { }
                record Case(List<Exprent> exprents) { }
                record Pair(Exprent exprent, int edge) { }
                private final List<List<Exprent>> caseValues = new ArrayList<>();
                SP() { List<Exprent> vals = new ArrayList<>(); vals.add(null); caseValues.add(vals); }
                List<List<Exprent>> getCaseValues() { return caseValues; }
                void resort(List<Case> cases) {
                    getCaseValues().clear();
                    getCaseValues().addAll(cases.stream().map(t -> t.exprents()).toList());
                }
                List<Case> build() {
                    List<List<Exprent>> sorted = new ArrayList<>();
                    for (List<Exprent> cv : caseValues) {
                        List<Pair> pairs = IntStream.range(0, cv.size()).mapToObj(i -> new Pair(cv.get(i), i)).toList();
                        sorted.add(pairs.stream().map(p -> p.exprent()).collect(Collectors.toList()));
                    }
                    return IntStream.range(0, sorted.size()).mapToObj(i -> new Case(sorted.get(i))).collect(Collectors.toList());
                }
                static List<Exprent> firsts(List<Case> cases) {
                    return cases.stream().filter(c -> !c.exprents().isEmpty()).map(c -> c.exprents().get(0)).toList();
                }
                static Set<Case> same(List<Case> cases) { return cases.stream().map(c -> c).collect(Collectors.toSet()); }
                static Map<Integer, Exprent> byEdge(List<Pair> pairs) {
                    return pairs.stream().collect(Collectors.toMap(p -> p.edge(), p -> p.exprent()));
                }
            }
            """;

    @DisplayName("stream pipelines: map, filter, toList, collect(toList/toSet/toMap) carry the elements' nodes (Kotlin)")
    @Test
    public void streamPipelines() {
        NullabilityPass.Report report = run("a.b.SP", STREAMS, NullabilityPass.Policy.KOTLIN);
        System.out.println(explain(report));
        Map<String, String> byName = report.verdicts().entrySet().stream()
                .collect(Collectors.toMap(e -> e.getKey().fullyQualifiedName(), e -> k(e.getValue())));
        String locals = locals(report);
        assertEquals("List<List<Exprent?>>", byName.get("a.b.SP.caseValues"));
        assertEquals("Exprent?", byName.get("a.b.SP.Pair.exprent"), "new Pair(cv.get(i), i) inside mapToObj");
        assertTrue(locals.contains("build.sorted: List<List<Exprent?>>"), locals);
        assertEquals("List<Exprent?>", byName.get("a.b.SP.Case.exprents"), "new Case(sorted.get(i)) and resort's addAll");
        // the synthesised accessors follow their components (they have no variable data of their own)
        assertEquals("List<Exprent?>", byName.get("a.b.SP.Case.exprents()"));
        assertEquals("Exprent?", byName.get("a.b.SP.Pair.exprent()"));
        assertEquals("List<Exprent?>", byName.get("a.b.SP.firsts(java.util.List)"));
        assertEquals("Set<Case>", byName.get("a.b.SP.same(java.util.List)"));
        assertEquals("Map<Integer, Exprent?>", byName.get("a.b.SP.byEdge(java.util.List)"));
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
                }
                """;
        NullabilityPass.Report kotlin = run("a.b.LO", source, NullabilityPass.Policy.KOTLIN);
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
        // no hint: the JVM calls it back with what it likes
        assertEquals("Throwable?", k.get("a.b.LO.Handler.uncaughtException(Thread,Throwable):1:e"));

        NullabilityPass.Report java = run("a.b.LJ", source.replace("class LO", "class LJ"),
                NullabilityPass.Policy.NULL_MARKED);
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
        NullabilityPass.Report report = run("a.b.NM", """
                package a.b;
                import java.util.Map;
                import org.junit.jupiter.api.TestReporter;
                class NM {
                    static class Reporter implements TestReporter {
                        @Override
                        public void publishEntry(Map<String, String> map) { }
                    }
                }
                """, NullabilityPass.Policy.KOTLIN);
        System.out.println(explain(report));
        Map<String, String> byName = report.verdicts().entrySet().stream()
                .collect(Collectors.toMap(e -> e.getKey().fullyQualifiedName(), e -> k(e.getValue())));
        assertEquals("Map<String, String>", byName.get("a.b.NM.Reporter.publishEntry(java.util.Map):0:map"));
    }

    // langchain4j's JsonSchemaElementJsonUtils.fromMap. Here the argument's links carry the flow; on the corpus they
    // are empty (no links for an immutable String), and callResultArgument supplies the edge. This pins the flow, not
    // the empty-links case, which needs the corpus (TestFernflowerNullabilityCauses, NULLABILITY_CORPUS=langchain4j).
    @DisplayName("a nullable helper result handed to a builder setter, as langchain4j's JsonSchemaElementJsonUtils.fromMap")
    @Test
    public void builderSetterFromHelper() {
        NullabilityPass.Report report = run("a.b.BS", """
                package a.b;
                import java.util.Map;
                class BS {
                    static class S {
                        private final String description;
                        S(Builder b) { this.description = b.description; }
                        static Builder builder() { return new Builder(); }
                        static class Builder {
                            private String description;
                            Builder description(String description) { this.description = description; return this; }
                            S build() { return new S(this); }
                        }
                    }
                    static class E {
                        private final String description;
                        E(Builder b) { this.description = b.description; }
                        static Builder builder() { return new Builder(); }
                        static class Builder {
                            private String description;
                            Builder description(String description) { this.description = description; return this; }
                            E build() { return new E(this); }
                        }
                    }
                    private static String optionalString(Map<String, Object> map, String field) {
                        Object value = map.get(field);
                        if (value == null) return null;
                        if (!(value instanceof String)) {
                            throw new IllegalArgumentException(field + " must be a string, but was: " + className(value));
                        }
                        return (String) value;
                    }
                    private static String className(Object obj) { return obj == null ? "null" : obj.getClass().getSimpleName(); }
                    static <T> T ensureNotNull(T object, String name) {
                        if (object == null) throw new IllegalArgumentException(name);
                        return object;
                    }
                    private static boolean allStrings(java.util.List<?> list) {
                        return list.stream().allMatch(String.class::isInstance);
                    }
                    static Object fromMap(Map<String, Object> map, String type) {
                        ensureNotNull(map, "map");
                        if (map.containsKey("enum")) {
                            Object enumObj = map.get("enum");
                            if (!(enumObj instanceof java.util.List<?> enumList)) {
                                throw new IllegalArgumentException("enum must be a list, but was: " + className(enumObj));
                            }
                            Object enumTypeObj = map.get("type");
                            if (!allStrings(enumList) || (enumTypeObj != null && !"string".equals(enumTypeObj))) {
                                return null;
                            }
                            return E.builder()
                                    .description(optionalString(map, "description"))
                                    .build();
                        }
                        return switch (type) {
                            case "string" -> map.isEmpty() ? S.builder().description(optionalString(map, "description")).build() : null;
                            default -> null;
                        };
                    }
                }
                """, NullabilityPass.Policy.KOTLIN);
        System.out.println(explain(report));
        Map<String, String> byName = report.verdicts().entrySet().stream()
                .filter(e -> e.getKey().fullyQualifiedName().contains("Builder.description(String)"))
                .collect(Collectors.toMap(e -> e.getKey().fullyQualifiedName(), e -> k(e.getValue())));
        assertEquals("String?", byName.get("a.b.BS.E.Builder.description(String):0:description"));
        assertEquals("String?", byName.get("a.b.BS.S.Builder.description(String):0:description"));
    }

    // the verdict of every declaration, by fully qualified name
    private static Map<String, String> byName(NullabilityPass.Report report) {
        return report.verdicts().entrySet().stream()
                .collect(Collectors.toMap(e -> e.getKey().fullyQualifiedName(), e -> k(e.getValue())));
    }

    private static Map<String, String> localsByName(NullabilityPass.Report report) {
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
        NullabilityPass.Report report = run("a.b.KM", """
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
        NullabilityPass.Report unknown = run("a.b.KU", """
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

    /*
     CodeLaser/maddi-mod#22 gap 3, nacos ClientOperationEvent: 'super(clientId, null)' in one subclass made the event's
     service nullable, and with it every use, also where the event is known to be another subclass. A local read
     from 'x.getF()' is non-null where 'x instanceof T' holds and no class an instance of T can be leaves null in F.
     */
    @DisplayName("narrowed reads: a getter's value on an instance of a subclass that never stores null (gap 3)")
    @Test
    public void narrowedReads() {
        NullabilityPass.Report report = run("a.b.EV", """
                package a.b;
                import java.util.*;
                class EV {
                    static class Service { void touch() { } }
                    static class Op {
                        private final String clientId;
                        final Service service;
                        Op(String clientId, Service service) { this.clientId = clientId; this.service = service; }
                        Service getService() { return service; }
                        String getClientId() { return clientId; }
                    }
                    static class Register extends Op { Register(Service s, String id) { super(id, s); } }
                    static class Fuzzy extends Op { Fuzzy(String id) { super(id, null); } }
                    static class Changed { private final Service service; Changed(Service service) { this.service = service; service.touch(); } }
                    static List<Object> published = new ArrayList<>();
                    static void publish(Op other) {
                        Op[] all = { new Register(new Service(), "x"), new Fuzzy("y"), new Op("z", new Service()) };
                        for (Op op : all) { after(op); early(op); pattern(op); inside(op); broad(op); field(op); moved(op, other); }
                    }
                    static void after(Op event) {
                        Service service = event.getService();
                        if (event instanceof Register) {
                            add(service);
                        }
                    }
                    static void early(Op event) {
                        Service service = event.getService();
                        if (!(event instanceof Register)) return;
                        addEarly(service);
                    }
                    static void pattern(Op event) {
                        Service service = event.getService();
                        if (event instanceof Register r && r.getClientId() != null) addPattern(service);
                    }
                    static void inside(Op event) {
                        if (event instanceof Register) { Service s = event.getService(); addInside(s); }
                    }
                    static void broad(Op event) {
                        Service service = event.getService();
                        if (event instanceof Op) addBroad(service);
                    }
                    static void field(Op event) {
                        Service service = event.service;
                        if (event instanceof Register) addField(service);
                    }
                    static void moved(Op event, Op other) {
                        Service service = event.getService();
                        event = other;
                        if (event instanceof Register) addMoved(service);
                    }
                    static void add(Service service) { published.add(new Changed(service)); }
                    static void addEarly(Service service) { published.add(service); }
                    static void addPattern(Service service) { published.add(service); }
                    static void addInside(Service service) { published.add(service); }
                    static void addBroad(Service service) { published.add(service); }
                    static void addField(Service service) { published.add(service); }
                    static void addMoved(Service service) { published.add(service); }
                }
                """);
        System.out.println(explain(report));
        Map<String, String> byName = byName(report);
        String p = "(a.b.EV.Service):0:service";
        assertEquals("Service", byName.get("a.b.EV.add" + p), "after the read, in the branch");
        assertEquals("Service", byName.get("a.b.EV.Changed.service"));
        assertEquals("Service", byName.get("a.b.EV.addEarly" + p), "after 'if (!(x instanceof T)) return'");
        assertEquals("Service", byName.get("a.b.EV.addPattern" + p));
        assertEquals("Service", byName.get("a.b.EV.addInside" + p), "the read inside the branch");
        assertEquals("Service", byName.get("a.b.EV.addField" + p), "a field read");
        assertEquals("Service?", byName.get("a.b.EV.addBroad" + p), "a Fuzzy is an Op");
        assertEquals("Service?", byName.get("a.b.EV.addMoved" + p), "the event tested is not the one read");
        // the declarations keep the null of the Fuzzy events
        assertEquals("Service?", byName.get("a.b.EV.Op.service"));
        assertEquals("Service?", byName.get("a.b.EV.Op.getService()"));

        // a subclass of the narrowed type that stores null, an overriding getter, a non-final field: no narrowing
        NullabilityPass.Report sub = run("a.b.EW", """
                package a.b;
                import java.util.*;
                class EW {
                    static class Service { }
                    static class Op {
                        private final Service service;
                        private Service mutable;
                        Op(Service service) { this.service = service; this.mutable = service; }
                        Service getService() { return service; }
                        Service getMutable() { return mutable; }
                        void setMutable(Service s) { this.mutable = s; }
                    }
                    static class Register extends Op { Register(Service s) { super(s); } }
                    static class Late extends Register { Late() { super(null); } }
                    static class Other extends Op { Other() { super(new Service()); } }
                    static class Weird extends Other { Weird() { setMutable(null); } @Override Service getService() { return null; } }
                    static List<Object> published = new ArrayList<>();
                    static void publish() { Op[] all = { new Register(new Service()), new Late(), new Other(), new Weird() }; for (Op op : all) handle(op); }
                    static void handle(Op event) {
                        Service service = event.getService();
                        Service mutable = event.getMutable();
                        if (event instanceof Register) addRegister(service);
                        if (event instanceof Other) { addOther(service); addMutable(mutable); }
                    }
                    static void addRegister(Service service) { published.add(service); }
                    static void addOther(Service service) { published.add(service); }
                    static void addMutable(Service service) { published.add(service); }
                }
                """);
        Map<String, String> s = byName(sub);
        assertEquals("Service?", s.get("a.b.EW.addRegister(a.b.EW.Service):0:service"), "a Late is a Register");
        assertEquals("Service?", s.get("a.b.EW.addOther(a.b.EW.Service):0:service"), "Weird overrides the getter");
        assertEquals("Service?", s.get("a.b.EW.addMutable(a.b.EW.Service):0:service"), "not a final field");
    }

    // langchain4j ValidationUtils: 'if (i == null || i < 0) throw illegalArgument("...%s", name, i)' hands the
    // vararg a value that is null on that branch. 'i' is nullable only through the comparison (indirect evidence),
    // which under assertContentWrites does not reach a slot; but a vararg's elements are not stored content: the
    // call builds the array, and asserting at the call ('i!!') would throw where Java formats "null"
    @DisplayName("a value null on the throwing branch passed as a vararg element")
    @Test
    public void varargElementAfterNullTest() {
        NullabilityPass.Report report = run("a.b.VA", """
                package a.b;
                class VA {
                    static IllegalArgumentException illegalArgument(String format, Object... args) {
                        return new IllegalArgumentException(String.format(format, args));
                    }
                    static RuntimeException runtime(String format, Object... args) {
                        return new RuntimeException(String.format(format, args));
                    }
                    static int ensureNotNegative(Integer i, String name) {
                        if (i == null || i < 0) throw illegalArgument("%s must not be negative, but is: %s", name, i);
                        return i;
                    }
                    static Object plain(Integer j) { return runtime("%s", j); }
                    static void caller() { plain(null); }
                }
                """, NullabilityPass.Policy.KOTLIN);
        Map<String, String> s = new java.util.TreeMap<>();
        report.verdicts().forEach((info, pt) -> s.put(label(info), k(pt)));
        assertEquals("Integer?", s.get("ensureNotNegative(0:i)"), s.toString());
        assertEquals("Object?[]", s.get("illegalArgument(1:args)"), s.toString());
        assertEquals("Object?[]", s.get("runtime(1:args)"), "a null argument, as before: " + s);
    }

    // langchain4j EmbeddingStoreRequestContext.Add: 'Add(store, attributes, embedding)' calls 'this(..., null)' into
    // its 'Embedded embedded' field. That null is the class's own: Kotlin's 'val embedded: Embedded?', not an
    // Add<X?> for every instance (which spread to ListeningEmbeddingStore's 'delegate' and clashed with 'this').
    // A null written outside the class into the same kind of field is still the instance's.
    @DisplayName("a holder field's own null makes the field nullable, an outside null the instance's slot")
    @Test
    public void holderFieldOwnNull() {
        NullabilityPass.Report report = run("a.b.HF", """
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
                """, NullabilityPass.Policy.KOTLIN);
        Map<String, String> s = new java.util.TreeMap<>();
        report.verdicts().forEach((info, pt) -> s.put(label(info), k(pt)));
        assertEquals("E?", s.get("embedded"), s.toString());
        // X!: no claim about X, which is the point -- not X?
        assertEquals("Ctx<X!>", s.get("make()"), "the class's own null stays in the class: " + s);
        assertEquals("Box<String?>", s.get("box()"), "a null from outside is the instance's: " + s);
    }

    // a library return annotated nullable in its class file, with no hint: maddi-support's 'SetOnce.getOrDefaultNull'
    // is '@Nullable T getOrDefaultNull()' (libs/support hints are not loaded). Before 2026-10-10 only the hints seeded
    // a library return: nacos' io.kubernetes.client getters ('@jakarta.annotation.Nullable') never reached a
    // dereference. (JUnit 6's JSpecify-annotated 'ExtensionContext.Store.get' cannot serve: jspecify is not on the
    // test class path, so its annotations are skipped as unmappable.)
    @DisplayName("a library return annotated nullable in its class file seeds, like a hinted one")
    @Test
    public void libraryReturnAnnotatedInClassFile() {
        NullabilityPass.Report report = run("a.b.LR", """
                package a.b;
                import io.codelaser.maddi.support.SetOnce;
                class LR {
                    static String value(SetOnce<String> once) { return once.getOrDefaultNull(); }
                    static String trimmed(String s) { return s.trim(); }
                }
                """);
        Map<String, String> s = new java.util.TreeMap<>();
        report.verdicts().forEach((info, pt) -> s.put(label(info), k(pt)));
        assertEquals("String?", s.get("value()"), s.toString());
        assertEquals("String", s.get("trimmed()"), s.toString());
    }

    // Class.getClassLoader() is null only for a class of the bootstrap loader: never for this analysis' own types
    // (nacos MysqlHealthCheckProcessor-style 'getClass().getClassLoader()'), still for 'String.class'
    @DisplayName("getClassLoader() of an analysed type's class is not a null source")
    @Test
    public void applicationClassLoader() {
        NullabilityPass.Report report = run("a.b.CL", """
                package a.b;
                class CL {
                    ClassLoader own() { return getClass().getClassLoader(); }
                    static ClassLoader literal() { return CL.class.getClassLoader(); }
                    static ClassLoader of(CL cl) { return cl.getClass().getClassLoader(); }
                    static ClassLoader bootstrap() { return String.class.getClassLoader(); }
                }
                """);
        Map<String, String> s = new java.util.TreeMap<>();
        report.verdicts().forEach((info, pt) -> s.put(label(info), k(pt)));
        assertEquals("ClassLoader", s.get("own()"), s.toString());
        assertEquals("ClassLoader", s.get("literal()"), s.toString());
        assertEquals("ClassLoader", s.get("of()"), s.toString());
        assertEquals("ClassLoader?", s.get("bootstrap()"), "String is loaded by the bootstrap loader: " + s);
    }

    // CodeLaser/maddi-mod#22 gap 9 (nacos SearchableProperties): the return is nullable only through 'defaultValue', so the
    // result of a call passing a non-null default is not
    @Language("java")
    private static final String GAP9 = """
            package a.b;
            import java.util.HashMap;
            import java.util.Map;
            import java.util.Optional;
            class G9 {
                private final Map<String, Object> map = new HashMap<>();
                <T> Optional<T> search(String key, Class<T> type) { return Optional.ofNullable(type.cast(map.get(key))); }
                Integer getInteger(String key) { return getInteger(key, null); }
                Integer getInteger(String key, Integer defaultValue) {
                    return search(key, Integer.class).orElse(defaultValue);
                }
                int timeout() {
                    Integer n = getInteger("timeout", 5);
                    return n.intValue();
                }
                int retries() {
                    Integer r = getInteger("retries");
                    return r == null ? 0 : r;
                }
                Integer timeoutValue() { return getInteger("timeout", 5); }
                Integer retriesValue() { return getInteger("retries"); }
            }
            """;

    private List<io.codelaser.maddi.cst.api.expression.MethodCall> calls(String method, String callee) {
        MethodInfo mi = parsed.methods().stream().filter(m -> m.name().equals(method)).findFirst().orElseThrow();
        List<io.codelaser.maddi.cst.api.expression.MethodCall> out = new java.util.ArrayList<>();
        mi.methodBody().visit(e -> {
            if (e instanceof io.codelaser.maddi.cst.api.expression.MethodCall mc && mc.methodInfo() != null
                && mc.methodInfo().name().equals(callee)) out.add(mc);
            return true;
        });
        return out;
    }

    @DisplayName("gap 9: a return nullable only through a parameter is not nullable at a call whose argument is not")
    @Test
    public void returnThroughParameterPerCall() {
        NullabilityPass.Report report = run("a.b.G9", GAP9);
        Map<String, String> s = new java.util.TreeMap<>();
        report.verdicts().forEach((info, pt) -> s.put(label(info), k(pt)));
        assertEquals("Integer?", s.get("getInteger(1:defaultValue)"), s.toString());
        String r = s.entrySet().stream().filter(e -> e.getKey().equals("getInteger()")).map(Map.Entry::getValue)
                .collect(Collectors.joining(","));
        assertTrue(r.contains("Integer?"), "the declaration stays nullable: " + s);
        assertTrue(report.callResultNonNull(calls("timeout", "getInteger").getFirst()), report.explain(
                calls("timeout", "getInteger").getFirst().methodInfo()));
        assertFalse(report.callResultNonNull(calls("getInteger", "getInteger").getFirst()),
                "getInteger(key, null) passes the null");
    }

    @DisplayName("gap 9, Kotlin: a return assigned a call passing a non-null default is non-null")
    @Test
    public void returnThroughParameterPerCallKotlin() {
        NullabilityPass.Report report = run("a.b.G9", GAP9, NullabilityPass.Policy.KOTLIN);
        Map<String, String> s = new java.util.TreeMap<>();
        report.verdicts().forEach((info, pt) -> s.put(label(info), k(pt)));
        assertEquals("Integer", s.get("timeoutValue()"), s.toString());
        assertEquals("Integer?", s.get("retriesValue()"), s.toString());
    }

    // CodeLaser/maddi-mod#22 gap 8 (nacos ConfigCacheService): the same non-modifying call, after its own null test
    @DisplayName("gap 8: a repeated pure call after its own null check is not the library's null")
    @Test
    public void repeatedPureCall() {
        NullabilityPass.Report report = run("a.b.G8", """
                package a.b;
                import java.io.IOException;
                class G8 {
                    static boolean checked(IOException ioe) {
                        if (ioe.getMessage() != null) {
                            String errMsg = ioe.getMessage();
                            return errMsg.contains("x");
                        }
                        return false;
                    }
                    static boolean unchecked(IOException ioe) {
                        String errMsg = ioe.getMessage();
                        return errMsg != null && errMsg.isEmpty();
                    }
                    static boolean changed(IOException ioe) {
                        if (ioe.getMessage() != null) {
                            ioe.initCause(new RuntimeException());
                            String errMsg = ioe.getMessage();
                            return errMsg != null;
                        }
                        return false;
                    }
                }
                """);
        String l = locals(report);
        assertTrue(l.contains("checked.errMsg: String\n"), l);
        assertTrue(l.contains("unchecked.errMsg: String?"), l);
        assertTrue(l.contains("changed.errMsg: String?"), "initCause modifies the receiver: " + l);
    }

    // CodeLaser/maddi-mod#22 gap 7 (nacos MysqlHealthCheckProcessor): a local initialized null for the finally, assigned
    // a value no null reaches, then dereferenced: known non-null at that use, though its declaration stays nullable
    @DisplayName("gap 7: a local is non-null after an assignment no null reaches, whatever its other assignments")
    @Test
    public void reassignedLocalUseSite() {
        NullabilityPass.Report report = run("a.b.G7", """
                package a.b;
                class G7 {
                    interface Stmt { void timeout(int t); void close(); }
                    interface Conn { Stmt create(); }
                    static Stmt maybe(boolean b) { return b ? null : new Stmt() {
                        public void timeout(int t) { }
                        public void close() { }
                    }; }
                    static void health(Conn connection) {
                        Stmt statement = null;
                        try {
                            statement = connection.create();
                            statement.timeout(1);
                        } finally {
                            if (statement != null) statement.close();
                        }
                    }
                    static void risky(boolean b) {
                        Stmt statement = null;
                        statement = maybe(b);
                        statement.timeout(1);
                    }
                }
                """);
        assertTrue(locals(report).contains("health.statement: Stmt?"), locals(report));
        assertTrue(timeoutCallNonNull(report, "health"), "after 'statement = connection.create()'");
        assertFalse(timeoutCallNonNull(report, "risky"), "maybe(b) may return null");
    }

    private boolean timeoutCallNonNull(NullabilityPass.Report report, String method) {
        MethodInfo mi = parsed.methods().stream().filter(m -> m.name().equals(method)).findFirst().orElseThrow();
        boolean[] found = {false, false};
        mi.methodBody().visit(e -> {
            if (e instanceof io.codelaser.maddi.cst.api.statement.ExpressionAsStatement eas
                && eas.expression() instanceof io.codelaser.maddi.cst.api.expression.MethodCall mc
                && mc.methodInfo() != null && "timeout".equals(mc.methodInfo().name())
                && mc.object() instanceof io.codelaser.maddi.cst.api.expression.VariableExpression ve) {
                found[0] = true;
                found[1] = report.useSites().nonNullAt(eas, ve.variable());
            }
            return true;
        });
        assertTrue(found[0], "no 'statement.timeout(1)' in " + method);
        return found[1];
    }
}
