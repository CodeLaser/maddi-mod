package io.codelaser.maddi.modification.analyzer.integration;

import static io.codelaser.maddi.modification.link.impl.MethodLinkedVariablesImpl.METHOD_LINKS;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.intellij.lang.annotations.Language;
import org.junit.jupiter.api.Test;

import io.codelaser.maddi.cst.api.info.Info;
import io.codelaser.maddi.cst.api.info.MethodInfo;
import io.codelaser.maddi.cst.api.info.TypeInfo;
import io.codelaser.maddi.modification.analyzer.CommonTest;
import io.codelaser.maddi.modification.link.impl.MethodLinkedVariablesImpl;

/**
 * CodeLaser/maddi-mod#121: a lambda's or anonymous class's method that only reads a captured local was reported
 * modifying when the enclosing method modifies that local (fernflower VarVersionsProcessor.setNewVarIndices: the
 * lambda passed to iterateExprents only calls get on a map the method filled with put). Whether it did depended on
 * which of several equal instances of the captured variable the engine held (NOINTERNLINKS, NOCANONICALVF); on
 * 2026-11-20 it was wrong either way.
 * <p>
 * Only {@code viaList} reproduces it: the value read from the map is stored into an element of a list the lambda
 * builds ({@code lst.add(exprent)}, then {@code ((ConstExprent) expr).setConstType(maxType)}), which links
 * {@code maxTypes.§$$s[-2] ∩ lst.§$s}; modifying that element made the map modified through the #134 rule, whose
 * type check let any slice overlap anything (Util.elementsMayOverlap, gate NOSLICETYPES). The other methods are
 * controls that were right before the fix too.
 */
public class TestCapturedModifiedLocal extends CommonTest {

    @Language("java")
    private static final String INPUT = """
            package a.b;
            import java.util.HashMap;
            import java.util.List;
            import java.util.Map;
            class X {
                interface Visitor { int visit(String s); }
                static class VarType { int t; VarType(int t) { this.t = t; } }
                static class Const { VarType type; void setConstType(VarType vt) { this.type = vt; } }
                interface ConstVisitor { int visit(Const c); }
                static void iterateConsts(List<Const> items, ConstVisitor v) { for (Const c : items) v.visit(c); }

                static void stored(List<Const> items) {
                    Map<String, VarType> maxTypes = new HashMap<>();
                    for (Const c : items) maxTypes.put("k" + c.hashCode(), new VarType(1));
                    iterateConsts(items, c -> {
                        VarType maxType = maxTypes.get("k" + c.hashCode());
                        if (maxType != null) c.setConstType(maxType);
                        return 0;
                    });
                }
                static class TypeProcessor {
                    private final Map<String, VarType> maxTypes = new HashMap<>();
                    Map<String, VarType> getMaxTypes() { return maxTypes; }
                }
                static void fromGetter(TypeProcessor tp, List<Const> items) {
                    final Map<String, VarType> maxTypes = tp.getMaxTypes();
                    for (Const c : items) maxTypes.put("k" + c.hashCode(), new VarType(1));
                    iterateConsts(items, c -> {
                        VarType maxType = maxTypes.get("k" + c.hashCode());
                        if (maxType != null) c.setConstType(maxType);
                        return 0;
                    });
                }
                static abstract class Exprent {
                    int id;
                    List<Exprent> getAllExprents(boolean all) { return new java.util.ArrayList<>(); }
                }
                static class ConstExprent extends Exprent {
                    VarType constType;
                    void setConstType(VarType constType) {
                        if (constType == null) constType = new VarType(0);
                        this.constType = constType;
                    }
                }
                interface ExprentIterator { int processExprent(Exprent exprent); }
                static void iterateExprents(List<Exprent> all, ExprentIterator it) { for (Exprent e : all) it.processExprent(e); }
                static void viaList(TypeProcessor tp, List<Exprent> all) {
                    final Map<String, VarType> maxTypes = tp.getMaxTypes();
                    maxTypes.put("k", new VarType(1));
                    iterateExprents(all, exprent -> {
                        List<Exprent> lst = exprent.getAllExprents(true);
                        lst.add(exprent);
                        for (Exprent expr : lst) {
                            VarType maxType = maxTypes.get("k" + expr.id);
                            if (maxType != null && maxType.t == 2) {
                                ((ConstExprent) expr).setConstType(maxType);
                            }
                        }
                        return 0;
                    });
                }
                static void iterate(List<String> items, Visitor v) { for (String s : items) v.visit(s); }

                static void lambda(List<String> items) {
                    Map<String, Integer> counts = new HashMap<>();
                    for (String s : items) counts.put(s, s.length());
                    iterate(items, s -> {
                        Integer c = counts.get(s);
                        return c == null ? 0 : c;
                    });
                }

                static void anonymous(List<String> items) {
                    Map<String, Integer> counts = new HashMap<>();
                    for (String s : items) counts.put(s, s.length());
                    iterate(items, new Visitor() {
                        @Override
                        public int visit(String s) {
                            Integer c = counts.get(s);
                            return c == null ? 0 : c;
                        }
                    });
                }

                static void runnable(StringBuilder sb) {
                    sb.append("x");
                    Runnable r = new Runnable() {
                        @Override
                        public void run() { System.out.println(sb.length()); }
                    };
                    r.run();
                }
            }
            """;

    @Test
    public void test() {
        TypeInfo X = javaInspector.parse("a.b.X", INPUT);
        List<Info> analysisOrder = prepWork(X);
        analyzer.go(analysisOrder, 3);
        StringBuilder wrong = new StringBuilder();
        int seen = 0;
        for (Info info : analysisOrder) {
            if (info instanceof MethodInfo mi && mi.typeInfo() != X && mi.typeInfo().primaryType() == X
                && !mi.typeInfo().simpleName().equals("Visitor") && !mi.typeInfo().simpleName().equals("ConstVisitor")
                && !mi.typeInfo().simpleName().equals("VarType") && !mi.typeInfo().simpleName().equals("Const")
                && !mi.typeInfo().simpleName().equals("TypeProcessor")
                && !mi.typeInfo().simpleName().equals("Exprent") && !mi.typeInfo().simpleName().equals("ConstExprent")
                && !mi.typeInfo().simpleName().equals("ExprentIterator")) {
                seen++;
                var mlv = mi.analysis().getOrNull(METHOD_LINKS, MethodLinkedVariablesImpl.class);
                System.out.println(mi + ": nonModifying=" + mi.isNonModifying() + " " + mlv
                                   + " modified=" + (mlv == null ? null : mlv.modified()));
                if (!mi.isNonModifying()) wrong.append(mi).append(' ');
            }
        }
        assertTrue(seen >= 3, "the lambda and both anonymous methods are analysed: " + seen);
        assertTrue(wrong.isEmpty(), "reading a captured local modifies nothing: " + wrong);
    }
}
