package io.codelaser.maddi.modification.link.nullflow;

import io.codelaser.maddi.cst.api.info.MethodInfo;
import io.codelaser.maddi.cst.api.info.TypeInfo;
import io.codelaser.maddi.cst.api.variable.Variable;
import io.codelaser.maddi.modification.link.CommonTest;
import io.codelaser.maddi.modification.link.LinkComputer;
import io.codelaser.maddi.modification.link.impl.LinkComputerImpl;
import io.codelaser.maddi.modification.link.impl.MethodLinkedVariablesImpl;
import io.codelaser.maddi.modification.link.impl.localvar.MarkerVariable;
import io.codelaser.maddi.modification.prepwork.PrepAnalyzer;
import io.codelaser.maddi.modification.prepwork.variable.Link;
import io.codelaser.maddi.modification.prepwork.variable.Links;
import io.codelaser.maddi.modification.prepwork.variable.MethodLinkedVariables;
import io.codelaser.maddi.modification.prepwork.variable.VariableData;
import io.codelaser.maddi.modification.prepwork.variable.VariableInfo;
import io.codelaser.maddi.modification.prepwork.variable.impl.VariableDataImpl;
import org.intellij.lang.annotations.Language;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static io.codelaser.maddi.modification.link.impl.MethodLinkedVariablesImpl.METHOD_LINKS;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Nullability feasibility probe (2026-10-06): does a {@code null} literal survive in the link graph, as a constant
 * marker ({@code $_ceN}) whose expression is the null constant, along the flows a nullability analyzer for a
 * Java→Kotlin translation needs? Pins CURRENT behaviour, gaps included, so a change in what reaches a
 * nullability consumer shows up here.
 * <p>
 * Two places carry the facts: the method summary (METHOD_LINKS, what a caller sees) and the method-level
 * variable data (the links of fields and locals at the end of the method, what a field analyzer reads).
 * A marker prints as its name; {@link #markers} resolves each to its constant ({@code null} vs a literal), the
 * distinction a nullability consumer needs, since non-null literals are constant markers too.
 * <p>
 * Findings, in the order of the tests:
 * <ol>
 *     <li>A returned null reaches the summary when it shares the return with a real source (ternary), but a
 *     return made ONLY of markers is emptied ({@code LinkComputerImpl.emptyIfOnlySomeValue}): {@code direct}
 *     has an empty summary, and a caller of it sees only {@code $_v} (test 3, {@code viaCall}).</li>
 *     <li>Field assignments of null are in the variable data ({@code this.f←$_ce0}).</li>
 *     <li>A setter called with null puts null into the field at the call site, also on another object.</li>
 *     <li>Content: {@code list.add(null)} gives {@code §$s∋null}; a map separates key slice [-1] from value
 *     slice [-2]; an array initializer is index-precise.</li>
 *     <li>A source generic holder: {@code box.set(null)} gives {@code box.t←null}; a returned new holder with a
 *     null field loses it in the summary (same marker-only rule as 1).</li>
 *     <li>No condition tracking: a value guarded by {@code if (x == null) x = "default"} still links to the
 *     possibly-null source.</li>
 * </ol>
 */
public class TestNullFlowProbe extends CommonTest {

    private TypeInfo analyze(String fqn, String src) {
        return analyze(fqn, src, LinkComputer.Options.TEST);
    }

    private TypeInfo analyze(String fqn, String src, LinkComputer.Options options) {
        TypeInfo type = javaInspector.parse(fqn, src);
        new PrepAnalyzer(runtime, new PrepAnalyzer.Options.Builder().build()).doPrimaryType(type);
        new LinkComputerImpl(javaInspector, options).doPrimaryType(type);
        return type;
    }

    // TEST, plus Options.nullConstantReturns
    private static final LinkComputer.Options NULL_RETURNS = new LinkComputer.Options.Builder()
            .setRecurse(true).setCheckDuplicateNames(true).setNullConstantReturns(true).build();

    private static MethodInfo method(TypeInfo type, String name) {
        return type.methodStream().filter(m -> m.name().equals(name)).findFirst().orElseThrow();
    }

    private static MethodLinkedVariables mlv(TypeInfo type, String method) {
        return method(type, method).analysis().getOrNull(METHOD_LINKS, MethodLinkedVariablesImpl.class);
    }

    private static String summary(TypeInfo type, String method) {
        return mlv(type, method).toString();
    }

    // the links of 'variable' (simple name) at the end of the method
    private static String links(TypeInfo type, String method, String variable) {
        VariableData vd = VariableDataImpl.of(method(type, method));
        return vd.variableInfoStream()
                .filter(vi -> vi.variable().simpleName().equals(variable))
                .map(VariableInfo::linkedVariables)
                .map(String::valueOf)
                .findFirst().orElse("<unknown>");
    }

    // every marker mentioned in the summary or the variable data of the method, resolved to its expression
    private static String markers(TypeInfo type, String method) {
        Map<String, String> legend = new TreeMap<>();
        MethodInfo mi = method(type, method);
        MethodLinkedVariables mlv = mlv(type, method);
        Stream<Links> fromSummary = Stream.concat(Stream.of(mlv.ofReturnValue()), mlv.ofParameters().stream());
        VariableData vd = VariableDataImpl.of(mi);
        Stream<Links> fromVd = vd == null ? Stream.of() : vd.variableInfoStream().map(VariableInfo::linkedVariables);
        Stream.concat(fromSummary, fromVd).forEach(links -> {
            if (links == null) return;
            for (Link link : links) {
                Stream.of(link.from(), link.to()).flatMap(Variable::variableStreamDescend).forEach(w -> {
                    if (w instanceof MarkerVariable mv && mv.isConstant()) {
                        legend.put(mv.simpleName(), mv.assignmentExpression().toString().startsWith("null")
                                ? "null" : "nonNull");
                    }
                });
            }
        });
        return legend.entrySet().stream().map(e -> e.getKey() + "=" + e.getValue())
                .collect(Collectors.joining(","));
    }

    @DisplayName("1 return null, directly and through a ternary")
    @Test
    public void returnNull() {
        @Language("java") String src = """
                package a.b;
                class N1 {
                    String direct() { return null; }
                    String ternary(boolean b, String s) { return b ? s : null; }
                    String earlyExit(String s) { if (s == null) return ""; return s; }
                }
                """;
        TypeInfo t = analyze("a.b.N1", src);
        // GAP: the only link is to a marker, so the summary is emptied; the fact lives in the variable data only
        assertEquals("[] --> -", summary(t, "direct"));
        assertEquals("direct←$_ce0", links(t, "direct", "return direct"));
        assertEquals("$_ce0=null", markers(t, "direct"));

        assertEquals("[-, -] --> ternary←$_ce0,ternary←1:s", summary(t, "ternary"));
        assertEquals("$_ce0=null", markers(t, "ternary"));

        // the "" is a non-null constant marker: evidence FOR non-null, distinguishable from null
        assertEquals("[-] --> earlyExit←$_ce1,earlyExit←0:s", summary(t, "earlyExit"));
        assertEquals("$_ce1=nonNull", markers(t, "earlyExit"));
    }

    @DisplayName("2 fields: assigned null, assigned a parameter, lazily initialized")
    @Test
    public void fields() {
        @Language("java") String src = """
                package a.b;
                class N2 {
                    private String f;
                    private String g = "x";
                    void clear() { f = null; }
                    void set(String s) { this.f = s; }
                    String lazy() { if (g == null) g = "y"; return g; }
                }
                """;
        TypeInfo t = analyze("a.b.N2", src);
        assertEquals("[] --> -", summary(t, "clear"));
        assertEquals("this.f←$_ce0", links(t, "clear", "f"));
        assertEquals("$_ce0=null", markers(t, "clear"));

        assertEquals("[0:s→this*.f] --> -", summary(t, "set"));

        assertEquals("[] --> lazy←$_ce1,lazy←this*.g", summary(t, "lazy"));
        assertEquals("$_ce1=nonNull", markers(t, "lazy"));
    }

    @DisplayName("3 interprocedural: callee returns null, caller passes null to a setter")
    @Test
    public void interprocedural() {
        @Language("java") String src = """
                package a.b;
                class N3 {
                    private String f;
                    String source() { return null; }
                    String viaCall() { return source(); }
                    void set(String s) { this.f = s; }
                    void caller() { set(null); }
                    void callerOther(N3 other) { other.set(null); }
                }
                """;
        TypeInfo t = analyze("a.b.N3", src);
        // GAP (follows from test 1): source()'s empty summary leaves the caller with an opaque someValue
        assertEquals("[] --> -", summary(t, "source"));
        assertEquals("viaCall←$_v", links(t, "viaCall", "return viaCall"));

        assertEquals("this.f←$_ce0", links(t, "caller", "f"));
        assertEquals("$_ce0=null", markers(t, "caller"));

        assertEquals("[0:other*.f←$_ce0*] --> -", summary(t, "callerOther"));
        assertEquals("$_ce0=null", markers(t, "callerOther"));
    }

    @DisplayName("4 collection and map content: null elements, null values, null keys")
    @Test
    public void content() {
        @Language("java") String src = """
                package a.b;
                import java.util.*;
                class N4 {
                    void addNull(List<String> list) { list.add(null); }
                    void putNullValue(Map<String, String> map, String k) { map.put(k, null); }
                    void putNullKey(Map<String, String> map, String v) { map.put(null, v); }
                    List<String> listOfNull() { List<String> l = new ArrayList<>(); l.add(null); return l; }
                    String[] arrayWithNull() { return new String[]{"a", null}; }
                }
                """;
        TypeInfo t = analyze("a.b.N4", src);
        // List<String?>
        assertEquals("[0:list*.§$s∋$_ce0] --> -", summary(t, "addNull"));
        assertEquals("$_ce0=null", markers(t, "addNull"));

        // Map<String, String?>: the null is in the VALUE slice [-2], the key in [-1]
        assertEquals("[0:map*.§$$s[-1]∋1:k,0:map*.§$$s[-2]∋$_ce0, 1:k∈0:map*.§$$s[-1]] --> -",
                summary(t, "putNullValue"));
        // Map<String?, String>
        assertEquals("[0:map*.§$$s[-1]∋$_ce0,0:map*.§$$s[-2]∋1:v, 1:v∈0:map*.§$$s[-2]] --> -",
                summary(t, "putNullKey"));

        // GAP: the return's content link is to a marker only, so the summary is empty
        assertEquals("[] --> -", summary(t, "listOfNull"));
        assertEquals("listOfNull.§$s∋$_ce1", links(t, "listOfNull", "return listOfNull"));
        assertEquals("$_ce1=null", markers(t, "listOfNull"));

        // array initializer: index-precise
        assertEquals("arrayWithNull←$_v,arrayWithNull[0]←$_ce0,arrayWithNull[1]←$_ce1",
                links(t, "arrayWithNull", "return arrayWithNull"));
        assertEquals("$_ce0=nonNull,$_ce1=null", markers(t, "arrayWithNull"));
    }

    @DisplayName("5 generic holder: null through a constructor and a setter of a source type")
    @Test
    public void holder() {
        @Language("java") String src = """
                package a.b;
                class N5 {
                    static class Box<T> {
                        private T t;
                        Box(T t) { this.t = t; }
                        void set(T t) { this.t = t; }
                        T get() { return t; }
                    }
                    Box<String> make() { return new Box<>(null); }
                    void reset(Box<String> box) { box.set(null); }
                    String read() { Box<String> b = new Box<>(null); return b.get(); }
                }
                """;
        TypeInfo t = analyze("a.b.N5", src);
        assertEquals("[0:box*.t←$_ce0*] --> -", summary(t, "reset"));
        assertEquals("$_ce0=null", markers(t, "reset"));

        // GAP: both carry the null in the variable data, neither in the summary
        assertEquals("[] --> -", summary(t, "make"));
        assertEquals("make.t←$_ce1,make←$_v", links(t, "make", "return make"));
        assertEquals("[] --> -", summary(t, "read"));
        assertEquals("read←$_ce1", links(t, "read", "return read"));
        assertEquals("$_ce1=null", markers(t, "read"));
    }

    @DisplayName("6 locals: null default overwritten in one branch; a null check does not refine")
    @Test
    public void locals() {
        @Language("java") String src = """
                package a.b;
                class N6 {
                    private String f;
                    void m(boolean b, String s) {
                        String x = null;
                        if (b) x = s;
                        this.f = x;
                    }
                    void guarded(String s) {
                        String x = s;
                        if (x == null) x = "default";
                        this.f = x;
                    }
                }
                """;
        TypeInfo t = analyze("a.b.N6", src);
        // the null survives the if-without-else join into the field
        assertEquals("this.f←$_ce0,this.f←x", links(t, "m", "f"));
        assertEquals("$_ce0=null", markers(t, "m"));

        // no condition tracking: x still links to s, which may be null; Kotlin would type f as String here
        assertEquals("x←$_ce1,x→this.f,x←0:s", links(t, "guarded", "x"));
        assertEquals("[0:s→this*.f] --> -", summary(t, "guarded"));
        assertEquals("$_ce1=nonNull", markers(t, "guarded"));
    }

    @DisplayName("7 Options.nullConstantReturns closes the marker-only-return gap of tests 1, 3, 4 and 5")
    @Test
    public void nullConstantReturns() {
        @Language("java") String src = """
                package a.b;
                import java.util.*;
                class N7 {
                    static class Box<T> {
                        private T t;
                        Box(T t) { this.t = t; }
                        T get() { return t; }
                    }
                    String direct() { return null; }
                    String viaCall() { return direct(); }
                    String emptyString() { return ""; }
                    List<String> listOfNull() { List<String> l = new ArrayList<>(); l.add(null); return l; }
                    Box<String> make() { return new Box<>(null); }
                    String read() { Box<String> b = new Box<>(null); return b.get(); }
                    String ternary(boolean b, String s) { return b ? s : null; }
                }
                """;
        TypeInfo t = analyze("a.b.N7", src, NULL_RETURNS);
        assertEquals("[] --> direct←$_ce0", summary(t, "direct"));
        assertEquals("$_ce0=null", markers(t, "direct"));
        // the null now crosses the call
        assertEquals("[] --> viaCall←$_ce0", summary(t, "viaCall"));
        assertEquals("$_ce0=null", markers(t, "viaCall"));
        // only NULL constants are kept: a returned non-null literal still empties the summary
        assertEquals("[] --> -", summary(t, "emptyString"));
        assertEquals("[] --> listOfNull.§$s∋$_ce1", summary(t, "listOfNull"));
        assertEquals("[] --> make.t←$_ce1", summary(t, "make"));
        assertEquals("[] --> read←$_ce1", summary(t, "read"));
        // unchanged where the summary was not marker-only
        assertEquals("[-, -] --> ternary←$_ce0,ternary←1:s", summary(t, "ternary"));
    }
}
