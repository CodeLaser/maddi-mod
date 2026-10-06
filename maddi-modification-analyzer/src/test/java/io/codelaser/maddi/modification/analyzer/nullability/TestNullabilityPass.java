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
        String base = pt.typeParameter() != null ? pt.typeParameter().simpleName() : pt.typeInfo().simpleName();
        String args = pt.parameters().isEmpty() ? ""
                : pt.parameters().stream().map(TestNullabilityPass::k).collect(Collectors.joining(", ", "<", ">"));
        String suffix = switch (pt.nullable()) {
            case NULLABLE -> "?";
            case NONNULL -> "";
            case UNSPECIFIED -> "!";
        };
        return base + args + "[]".repeat(pt.arrays()) + suffix;
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
                getLazy(): String?
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
                tolerant(): String?
                tolerant(0:t): String?""", verdicts(report));
        // tolerant(): String? is the flow-insensitivity of the pass: after the test, t is not null where it is
        // returned. The use-site pass (M4) is where that is seen.
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
                first(): Entry<String!, String!>?
                hashCode(): int
                lookup(): String?
                lookup(0:k): String
                map: Map<String!, String!>
                nav: NavigableMap<String!, String!>
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
        // downward: f.apply(null) may run any implementation (sound; ToString cannot actually be an
        // Fn<String,String>, which a type-aware dispatch would see)
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
}
