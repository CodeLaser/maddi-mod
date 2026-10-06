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

    private NullabilityPass.Report run(String fqn, String src) {
        TypeInfo t = javaInspector.parse(fqn, src);
        List<Info> ao = prepWork(t);
        ModAnalyzerForTesting analyzer = new SingleIterationAnalyzerImpl(javaInspector,
                new IteratingAnalyzerImpl.ConfigurationBuilder().setNullability(true).build());
        analyzer.go(ao, 3);
        return new NullabilityPass(NullabilityPass.Policy.NULL_MARKED).go(ao);
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
}
