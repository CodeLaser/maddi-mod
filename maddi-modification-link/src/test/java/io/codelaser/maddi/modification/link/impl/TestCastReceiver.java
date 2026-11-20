package io.codelaser.maddi.modification.link.impl;

import static io.codelaser.maddi.modification.link.impl.MethodLinkedVariablesImpl.METHOD_LINKS;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.intellij.lang.annotations.Language;
import org.junit.jupiter.api.Test;

import io.codelaser.maddi.cst.api.info.MethodInfo;
import io.codelaser.maddi.cst.api.info.TypeInfo;
import io.codelaser.maddi.modification.link.CommonTest;
import io.codelaser.maddi.modification.link.LinkComputer;
import io.codelaser.maddi.modification.prepwork.PrepAnalyzer;

/*
 CodeLaser/maddi-mod#155. A pattern `instanceof` gave its binding's links (`o ≡ x`) as the VALUE of the boolean
 expression, so `return o instanceof T x && t == x.t` returned `←0:o`, and every caller's `a.equals(b)` was linked to
 `b`. Through a cast receiver, `((F) o).getVt()`, whose callee summary translates onto `o`'s own fields, that related
 `o`'s fields to the caller's: fernflower's VarExprent.equals, reached through Kotlin's `!=` (maddi-mod#145).
 ⛔ viaCast keeps a smaller relation: getVt's two alternatives, `T.UNKNOWN` and an element of `processor.types`,
 are conflated, and two receivers' maps relate through the shared constant. Pinned.
 */
public class TestCastReceiver extends CommonTest {

    @Language("java")
    private static final String INPUT = """
            package a.b;
            import java.util.HashMap;
            class X {
                static class T {
                    final int t;
                    static final T UNKNOWN = new T(0);
                    T(int t) { this.t = t; }
                    @Override public boolean equals(Object o) { return o instanceof T x && t == x.t; }
                    @Override public int hashCode() { return t; }
                }
                static class U {
                    final int t; U(int t) { this.t = t; }
                    boolean e1(Object o) { return o instanceof U x && t == x.t; }
                    boolean e2(Object o) { if (!(o instanceof U x)) return false; return t == x.t; }
                    boolean e3(Object o) { if (!(o instanceof U)) return false; return t == ((U) o).t; }
                    boolean e4(Object o) { return o instanceof U; }
                    boolean e5(Object o) { return o == this; }
                }
                static class Proc { final HashMap<Integer, T> types = new HashMap<>(); T get(int k) { return types.get(k); } }
                static class F {
                    private final Proc processor; private T inferred; private T varType;
                    F(Proc p, T i, T v) { processor = p; inferred = i; varType = v; }
                    T getVt() {
                        if (inferred != null) return inferred;
                        T vt = null;
                        if (processor != null) vt = processor.get(1);
                        if (vt == null || varType != null) vt = varType;
                        return vt == null ? T.UNKNOWN : vt;
                    }
                    boolean viaCast(Object o) {
                        if (!(o instanceof F)) return false;
                        return getVt().equals(((F) o).getVt());
                    }
                    boolean viaPattern(Object o) {
                        if (!(o instanceof F x)) return false;
                        return getVt().equals(x.getVt());
                    }
                    T getVt2() { return inferred != null ? inferred : varType; }
                    boolean viaCast2(Object o) {
                        if (!(o instanceof F)) return false;
                        return getVt2().equals(((F) o).getVt2());
                    }
                    T getVt3() { return inferred; }
                    boolean viaCast3(Object o) {
                        if (!(o instanceof F)) return false;
                        return getVt3().equals(((F) o).getVt3());
                    }
                    static boolean eq4(T a, T b) { return a.equals(b); }
                    static boolean eq5(F a, F b) { return a.getVt3().equals(b.getVt3()); }
                    static boolean eq6(F a, T b) { return a.getVt3().equals(b); }
                    static boolean eq7(T a, F b) { return a.equals(b.getVt3()); }
                    static boolean eq8(F a, F b) { T x = a.getVt3(); T y = b.getVt3(); return x.equals(y); }
                    T castOnly(Object o) { return ((F) o).getVt(); }
                    T patternOnly(Object o) { return o instanceof F x ? x.getVt() : null; }
                }
            }
            """;

    @Test
    public void aPatternInstanceOfHasNoValueLinks() {
        TypeInfo X = javaInspector.parse("a.b.X", INPUT);
        new PrepAnalyzer(runtime, new PrepAnalyzer.Options.Builder().build()).doPrimaryType(X);
        LinkComputer tlc = new LinkComputerImpl(javaInspector);
        TypeInfo F = X.findSubType("F");
        StringBuilder sb = new StringBuilder();
        TypeInfo T = X.findSubType("T");
        MethodInfo teq = T.methods().stream().filter(m -> m.name().equals("equals")).findFirst().orElseThrow();
        sb.append("T.equals: ").append(teq.analysis().getOrCreate(METHOD_LINKS, () -> tlc.doMethod(teq))).append('\n');
        TypeInfo U = X.findSubType("U");
        for (String name : new String[]{"e1", "e2", "e3", "e4", "e5"}) {
            MethodInfo mi = U.methods().stream().filter(m -> m.name().equals(name)).findFirst().orElseThrow();
            sb.append("U.").append(name).append(": ").append(mi.analysis().getOrCreate(METHOD_LINKS, () -> tlc.doMethod(mi))).append('\n');
        }
        for (String name : new String[]{"getVt", "castOnly", "patternOnly", "viaCast", "viaPattern", "getVt2", "viaCast2", "getVt3", "viaCast3", "eq4", "eq5", "eq6", "eq7", "eq8"}) {
            MethodInfo mi = F.methods().stream().filter(m -> m.name().equals(name)).findFirst().orElseThrow();
            sb.append(name).append(": ").append(mi.analysis().getOrCreate(METHOD_LINKS, () -> tlc.doMethod(mi))).append('\n');
        }
        String out = sb.toString();
        for (String clean : new String[]{"T.equals: [-] --> -", "U.e1: [-] --> -", "U.e2: [-] --> -",
                "viaPattern: [-] --> -", "viaCast2: [-] --> -", "viaCast3: [-] --> -", "eq4: [-, -] --> -",
                "eq5: [-, -] --> -", "eq6: [-, -] --> -", "eq7: [-, -] --> -", "eq8: [-, -] --> -"}) {
            assertTrue(out.contains(clean + "\n"), clean + " in\n" + out);
        }
        // ⛔ the alternatives of getVt conflated through T.UNKNOWN; the boolean result itself is clean
        assertTrue(out.contains("viaCast: [0:o≻T.UNKNOWN,0:o.inferred∈this.processor.types.§$$s[-2],"), out);
        assertTrue(out.contains("] --> -\ngetVt: "), out);
    }
}
