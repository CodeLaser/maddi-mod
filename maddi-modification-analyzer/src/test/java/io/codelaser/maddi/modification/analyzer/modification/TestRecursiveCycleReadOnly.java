/*
 * maddi: a modification analyzer for duplication detection and immutability.
 * Copyright 2020-2025, Bart Naudts, https://github.com/CodeLaser/maddi
 *
 * This program is free software: you can redistribute it and/or modify it under the
 * terms of the GNU Lesser General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later version.
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE.  See the GNU Lesser General Public License for
 * more details. You should have received a copy of the GNU Lesser General Public
 * License along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package io.codelaser.maddi.modification.analyzer.modification;

import io.codelaser.maddi.cst.api.info.Info;
import io.codelaser.maddi.cst.api.info.MethodInfo;
import io.codelaser.maddi.cst.api.info.TypeInfo;
import io.codelaser.maddi.modification.analyzer.CommonTest;
import io.codelaser.maddi.modification.analyzer.impl.IteratingAnalyzerImpl;
import org.intellij.lang.annotations.Language;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * CodeLaser/maddi-mod#55: a parameter, or the receiver, that a recursion cycle only reads. The plain fixpoint keeps
 * the cycle's FALSE (the undecided callee folds as modifying, and the cycle sustains it, as in
 * TestRecursionThroughAbstract); the reachability pass, on in production (AnalysisEngineImpl), upgrades it.
 * <p>
 * One condition on the corpus shapes: {@code lst.get(i).containsExpr(expr)} needs a METHOD_LINKS summary of
 * {@code List.get} to project the receiver chain. The link computer gives every callee of a RECURSIVE_METHOD an
 * empty summary without storing one (ExpressionVisitor.recurseIntoLinkComputer), so when only recursive methods call
 * {@code List.get}, the pass taints the method's parameters and keeps their FALSE. {@code firstOf} is the caller
 * that a corpus normally has.
 */
public class TestRecursiveCycleReadOnly extends CommonTest {

    @Language("java")
    private static final String INPUT = """
            package a.b;
            import java.util.ArrayList;
            import java.util.HashMap;
            import java.util.List;
            import java.util.Map;
            class Node {
                Node parent; final Map<String, String> props = new HashMap<>(); final List<Node> children = new ArrayList<>();
                boolean leaf; Node first;
                boolean contains(Node x) { if (this == x) return true; for (Node c : children) if (c.contains(x)) return true; return false; }
                static String get(Node n, String k) { String v = n.props.get(k); return v != null || n.parent == null ? v : get(n.parent, k); }
                static int size(Node n) { return 1 + sizeAll(n.children); }
                static int sizeAll(List<Node> ns) { int s = 0; for (Node n : ns) s += size(n); return s; }
                static int depth(Node n) { return n.parent == null ? 0 : 1 + n.parent.hashCode(); }
                Node head() { return leaf ? this : first.head(); }
                Node headLoop() { Node s = this; while (!s.leaf) s = s.first; return s; }
            }
            """;

    @Language("java")
    private static final String CORPUS_SHAPES = """
            package a.b;
            import java.util.ArrayList;
            import java.util.List;
            class Shapes {
                // C: dolphinscheduler's AuditModelTypeDto
                enum Kind {
                    A("a", List.of()), B("b", List.of(A));
                    private final String name; private final List<Kind> child;
                    Kind(String name, List<Kind> child) { this.name = name; this.child = child; }
                    String getName() { return name; }
                    List<Kind> getChild() { return child; }
                }
                static class Dto {
                    private String name; private List<Dto> child;
                    void setName(String name) { this.name = name; }
                    void setChild(List<Dto> child) { this.child = child; }
                }
                static Dto toDto(Kind kind) {
                    Dto dto = new Dto();
                    dto.setName(kind.getName());
                    if (!kind.getChild().isEmpty()) dto.setChild(toDtos(new ArrayList<>(), kind.getChild()));
                    return dto;
                }
                static List<Dto> toDtos(List<Dto> dtos, List<Kind> kinds) {
                    for (Kind kind : kinds) dtos.add(toDto(kind));
                    return dtos;
                }
                // A, fernflower's Exprent.containsExprent
                static class Expr {
                    List<Expr> getAllExprents() { return new ArrayList<>(); }
                    boolean containsExpr(Expr expr) {
                        if (equals(expr)) return true;
                        List<Expr> lst = getAllExprents();
                        for (int i = lst.size() - 1; i >= 0; i--) if (lst.get(i).containsExpr(expr)) return true;
                        return false;
                    }
                    boolean noEquals(Expr expr) {
                        if (this == expr) return true;
                        List<Expr> lst = getAllExprents();
                        for (int i = lst.size() - 1; i >= 0; i--) if (lst.get(i).noEquals(expr)) return true;
                        return false;
                    }
                    boolean forEach(Expr expr) {
                        if (equals(expr)) return true;
                        for (Expr e : getAllExprents()) if (e.forEach(expr)) return true;
                        return false;
                    }
                    boolean onlyEquals(Expr expr) { return equals(expr); }
                    // a caller of List.get outside the cycle: see the class comment
                    Expr firstOf() { return getAllExprents().get(0); }
                }
                static final class Call extends Expr {
                    private final List<Expr> args = new ArrayList<>();
                    @Override List<Expr> getAllExprents() { return new ArrayList<>(args); }
                }
                // D: guava's Graphs.TransposedGraph
                record Pair(Object a, Object b) { Pair swap() { return new Pair(b, a); } }
                interface Graph { boolean hasEdge(Pair p); }
                static final class Transposed implements Graph {
                    private final Graph delegate;
                    Transposed(Graph delegate) { this.delegate = delegate; }
                    public boolean hasEdge(Pair p) { return delegate.hasEdge(transpose(p)); }
                    private static Pair transpose(Pair p) { return p.swap(); }
                }
                static final class Plain implements Graph {
                    public boolean hasEdge(Pair p) { return p.a() == p.b(); }
                }
            }
            """;

    @DisplayName("the corpus shapes of #55: reachability")
    @Test
    public void corpusShapes() {
        TypeInfo X = javaInspector.parse("a.b.Shapes", CORPUS_SHAPES);
        List<Info> ao = prepWork(X);
        new IteratingAnalyzerImpl(javaInspector, new IteratingAnalyzerImpl.ConfigurationBuilder()
                .setMaxIterations(30)
                .setModificationViaReachability(true)
                .build()).analyze(ao);
        String s = "toDto=" + method(X, "toDto").parameters().getFirst().isUnmodified()
                   + " toDtos=" + method(X, "toDtos").parameters().get(1).isUnmodified()
                   + " containsExpr=" + method(X.findSubType("Expr"), "containsExpr").parameters().getFirst().isUnmodified()
                   + " noEquals=" + method(X.findSubType("Expr"), "noEquals").parameters().getFirst().isUnmodified()
                   + " forEach=" + method(X.findSubType("Expr"), "forEach").parameters().getFirst().isUnmodified()
                   + " onlyEquals=" + method(X.findSubType("Expr"), "onlyEquals").parameters().getFirst().isUnmodified()
                   + " Graph=" + method(X.findSubType("Graph"), "hasEdge").parameters().getFirst().isUnmodified()
                   + " Transposed=" + method(X.findSubType("Transposed"), "hasEdge").parameters().getFirst().isUnmodified();
        assertEquals("toDto=true toDtos=true containsExpr=true noEquals=true forEach=true onlyEquals=true"
                     + " Graph=true Transposed=true", s);
    }

    @Language("java")
    private static final String REMAINING = """
            package a.b;
            import java.math.BigInteger;
            import java.util.ArrayList;
            import java.util.List;
            import java.util.ListIterator;
            import java.util.Properties;
            class Rem {
                static class Pps {
                    private final Properties properties = new Properties();
                    private Pps parent;
                    String getProperty(String key) { return getProperty(this, key); }
                    private String getProperty(Pps pps, String key) {
                        final String value = pps.properties.getProperty(key);
                        if (value != null) return value;
                        final Pps parent = pps.parent;
                        if (parent == null) return null;
                        return getProperty(parent, key);
                    }
                    boolean containsKey(Pps pps, String key) {
                        final boolean exist = pps.properties.containsKey(key);
                        if (exist) return true;
                        final Pps parent = pps.parent;
                        if (parent == null) return false;
                        return containsKey(parent, key);
                    }
                    Properties asProperties() {
                        List<Properties> list = new ArrayList<>(8);
                        list = lookForProperties(this, list);
                        Properties ret = new Properties();
                        final ListIterator<Properties> it = list.listIterator(list.size());
                        while (it.hasPrevious()) ret.putAll(it.previous());
                        return ret;
                    }
                    List<Properties> lookForProperties(Pps pps, List<Properties> list) {
                        list.add(pps.properties);
                        final Pps parent = pps.parent;
                        if (parent == null) return list;
                        return lookForProperties(parent, list);
                    }
                    synchronized void setProperty(String key, String value) { properties.setProperty(key, value); }
                }
                static final class Avl {
                    private Avl left, right;
                    private int distinctElements;
                    private long totalCount;
                    private int elemCount;
                    private Avl rebalance() { return this; }
                    private Avl removeMin(Avl node) {
                        if (left == null) {
                            return right;
                        } else {
                            left = left.removeMin(node);
                            distinctElements--;
                            totalCount -= node.elemCount;
                            return rebalance();
                        }
                    }
                }
                static final class Avl2 {
                    private Avl2 left, right, succ;
                    private int distinctElements;
                    private long totalCount;
                    private int elemCount;
                    private Avl2 rebalance() { return this; }
                    private Avl2 removeMin(Avl2 node) {
                        if (left == null) return right;
                        left = left.removeMin(node);
                        distinctElements--;
                        totalCount -= node.elemCount;
                        return rebalance();
                    }
                    // guava's AvlNode.deleteMe: the argument is modified by the CALLER, after the call
                    Avl2 deleteMe() {
                        Avl2 newTop = succ;
                        newTop.right = right.removeMin(newTop);
                        newTop.left = left;
                        return newTop.rebalance();
                    }
                }
                static BigInteger listProduct(List<BigInteger> nums, int start, int end) {
                    switch (end - start) {
                        case 0: return BigInteger.ONE;
                        case 1: return nums.get(start);
                        case 2: return nums.get(start).multiply(nums.get(start + 1));
                        default:
                            int m = (end + start) >>> 1;
                            return listProduct(nums, start, m).multiply(listProduct(nums, m, end));
                    }
                }
            }
            """;

    // shapes still FALSE on the corpora once the pass completes, nacos' PropertiesPropertySource and guava's
    @DisplayName("the corpus shapes left after #55's pass failure: reachability")
    @Test
    public void remaining() {
        TypeInfo X = javaInspector.parse("a.b.Rem", REMAINING);
        List<Info> ao = prepWork(X);
        new IteratingAnalyzerImpl(javaInspector, new IteratingAnalyzerImpl.ConfigurationBuilder()
                .setMaxIterations(30)
                .setModificationViaReachability(true)
                .build()).analyze(ao);
        TypeInfo pps = X.findSubType("Pps");
        String s = "getProperty=" + pps.methods().stream().filter(m -> m.name().equals("getProperty")
                                                                   && m.parameters().size() == 2).findFirst().orElseThrow()
                           .parameters().getFirst().isUnmodified()
                   + " containsKey=" + method(pps, "containsKey").parameters().getFirst().isUnmodified()
                   + " lookFor=" + method(pps, "lookForProperties").parameters().getFirst().isUnmodified()
                   + " removeMin=" + method(X.findSubType("Avl"), "removeMin").parameters().getFirst().isUnmodified()
                   + " removeMin2=" + method(X.findSubType("Avl2"), "removeMin").parameters().getFirst().isUnmodified()
                   + " listProduct=" + method(X, "listProduct").parameters().getFirst().isUnmodified();
        // PINS a defect: listProduct's 'nums.get(start).multiply(..)' is a receiver chain through List.get, which has no
        // METHOD_LINKS when only a RECURSIVE_METHOD calls it (see the class comment); on guava another caller has it
        assertEquals("getProperty=true containsKey=true lookFor=true removeMin=true removeMin2=true listProduct=false", s);
    }

    // nacos' ClassUtils.resolveGenericTypeByInterface: a call on an element of an array that a call returns. The summary's
    // modified variable is that element, a DependentVariable without an array variable, and seeding it failed the whole
    // pass ("MODREACH pass failed"), so every fixpoint FALSE stayed: depth's parameter here
    @Language("java")
    private static final String ARRAY = """
            package a.b;
            class Arr {
                static final class Box { int v; Box bump() { v++; return this; } }
                private final Box[] boxes = new Box[2];
                Arr next;
                static Arr of(Arr a) { return a; }
                Box[] boxes() { return boxes; }
                static Box first(Arr a) { return of(a).boxes()[0].bump(); }
                static int depth(Arr a) { return a.next == null ? 0 : 1 + depth(a.next); }
            }
            """;

    @DisplayName("an element of an array expression in a summary does not fail the reachability pass")
    @Test
    public void expressionArray() {
        TypeInfo X = javaInspector.parse("a.b.Arr", ARRAY);
        List<Info> ao = prepWork(X);
        new IteratingAnalyzerImpl(javaInspector, new IteratingAnalyzerImpl.ConfigurationBuilder()
                .setMaxIterations(30)
                .setModificationViaReachability(true)
                .build()).analyze(ao);
        assertEquals("depth=true", "depth=" + method(X, "depth").parameters().getFirst().isUnmodified());
    }

    private static MethodInfo method(TypeInfo typeInfo, String name) {
        return typeInfo.methods().stream().filter(m -> m.name().equals(name)).findFirst().orElseThrow();
    }

    private String verdicts(boolean modReach) {
        TypeInfo X = javaInspector.parse("a.b.Node", INPUT);
        List<Info> ao = prepWork(X);
        new IteratingAnalyzerImpl(javaInspector, new IteratingAnalyzerImpl.ConfigurationBuilder()
                .setMaxIterations(30)
                .setModificationViaReachability(modReach)
                .build()).analyze(ao);
        StringBuilder sb = new StringBuilder();
        for (String name : List.of("contains", "get", "size", "sizeAll", "depth")) {
            sb.append(name).append('=').append(method(X, name).parameters().getFirst().isUnmodified()).append(' ');
        }
        for (String name : List.of("head", "headLoop")) {
            sb.append(name).append('=').append(method(X, name).isNonModifying()).append(' ');
        }
        return sb.toString().trim();
    }

    private static final String EXPECTED = "contains=true get=true size=true sizeAll=true depth=true head=true headLoop=true";

    // PINS THE DEFECT, deliberately: the fixpoint alone keeps the cycles' FALSE; the reachability pass is the authority
    @DisplayName("without reachability the read-only cycles stay modified")
    @Test
    public void fixpoint() {
        assertEquals("contains=false get=false size=false sizeAll=false depth=true head=false headLoop=true",
                verdicts(false));
    }

    @DisplayName("a cycle that only reads leaves its parameters and receiver unmodified: reachability")
    @Test
    public void reachability() {
        assertEquals(EXPECTED, verdicts(true));
    }
}
