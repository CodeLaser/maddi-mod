package io.codelaser.maddi.modification.link.impl;

import static io.codelaser.maddi.modification.link.impl.MethodLinkedVariablesImpl.METHOD_LINKS;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.intellij.lang.annotations.Language;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.codelaser.maddi.cst.api.info.MethodInfo;
import io.codelaser.maddi.cst.api.info.TypeInfo;
import io.codelaser.maddi.modification.link.CommonTest;
import io.codelaser.maddi.modification.link.LinkComputer;
import io.codelaser.maddi.modification.prepwork.PrepAnalyzer;

/**
 * CodeLaser/maddi-mod#127, the recursive call itself.
 * <ul>
 *     <li>A return variable is not cleared when its value arrives through an intermediate (a ternary, an object
 *     creation): {@code if (c) return n; return d ? null : find(n.first);} kept the second statement's value only,
 *     recursion or not ({@code fd}, {@code ff}).</li>
 *     <li>A method that calls itself is computed a second time with its first summary for that call
 *     ({@code swap}, {@code fill}): one unrolling, so {@code find} links {@code n} and {@code n.first}.</li>
 * </ul>
 */
public class TestRecursiveResult extends CommonTest {

    @Language("java")
    private static final String INPUT = """
            package a.b;
            import java.util.*;
            class X {
                public static class Node { public List<String> data; public Node first; public List<Node> kids; }
                public static Node find(Node n) { if (n.data != null) return n; return n.first == null ? null : find(n.first); }
                public static Node find1(Node n) { if (n.data != null) return n; return n.first; }
                public static Node fa(Node n) { if (n.data != null) return n; return fa(n.first); }
                public static Node fb(Node n) { return n.first == null ? null : fb(n.first); }
                public static Node fc(Node n) { if (n.data != null) return n; return null; }
                public static Node fd(Node n) { if (n.data != null) return n; return n.first == null ? null : find1(n.first); }
                public static Node fe(Node n) { if (n.data != null) return n; Node r = fe(n.first); return r; }
                public static Node ff(Node n) { if (n.data != null) return n; return new Node(); }
                public static Node fg(Node n, Node m) { if (n.data != null) return n; return m.first == null ? m : m.first; }
                public static Node swap(Node a, Node b) { if (a.data != null) return a; return swap(b, a); }
                public static void fill(Node a, List<Node> x, List<Node> y) { x.add(a); if (a.first != null) fill(a.first, y, x); }
                public static Node deep(Node n) { return n.first == null ? n : deep(n.first); }
                public static void collect(Node n, List<Node> out) { out.add(n); for (Node k : n.kids) collect(k, out); }
                public static void collect1(Node n, List<Node> out) { out.add(n); for (Node k : n.kids) out.add(k); }
                public static List<Node> all(Node n) { List<Node> l = new ArrayList<>(); l.add(n); for (Node k : n.kids) l.addAll(all(k)); return l; }
            }
            """;

    @DisplayName("#127: the result of a recursive call links as the direct result does")
    @Test
    public void test() {
        TypeInfo X = javaInspector.parse("a.b.X", INPUT);
        PrepAnalyzer analyzer = new PrepAnalyzer(runtime, new PrepAnalyzer.Options.Builder().build());
        analyzer.doPrimaryType(X);
        LinkComputer tlc = new LinkComputerImpl(javaInspector);
        tlc.doPrimaryType(X);
        StringBuilder sb = new StringBuilder();
        for (String name : new String[]{"find", "find1", "fa", "fb", "fc", "fd", "fe", "ff", "swap", "fill", "deep", "collect", "collect1", "all"}) {
            MethodInfo mi = X.findUniqueMethod(name, name.startsWith("collect") || name.equals("swap") || name.equals("fg") ? 2 : name.equals("fill") ? 3 : 1);
            sb.append(name).append('=').append(mi.analysis().getOrNull(METHOD_LINKS, MethodLinkedVariablesImpl.class))
                    .append('\n');
        }
        // without the changes (gates CLEARRETURN, NOSELFSUMMARY): find=[-] --> -, fd lacks '←0:n', ff=[-] --> -,
        // swap lacks '←1:b', fill lacks every link to 2:y, and fa, fe, deep lack '←0:n.first'.
        // 2:y in fill is unmarked: a link-only test has no verdicts, and the call back into the recursion marks
        // nothing while its own are undecided (MethodModification)
        assertEquals("""
                find=[-] --> find.first←$_ce2,find←$_ce2,find←0:n.first,find←0:n
                find1=[-] --> find1←0:n.first,find1←0:n
                fa=[-] --> fa←0:n.first,fa←0:n
                fb=[-] --> -
                fc=[-] --> fc←$_ce1,fc←0:n
                fd=[-] --> fd.first←$_ce2,fd←$_ce2,fd←0:n.first,fd←0:n
                fe=[-] --> fe←0:n.first,fe←0:n
                ff=[-] --> ff←$_v,ff←0:n
                swap=[-, -] --> swap←0:a,swap←1:b
                fill=[0:a∈1:x*.§$s,0:a.first≤1:x*.§$s,0:a.first∈2:y.§$s, 1:x*.§$s∋0:a,1:x*.§$s≥0:a.first,1:x*.§$s∩2:y.§$s, 2:y.§$s∋0:a.first,2:y.§$s∩1:x*.§$s] --> -
                deep=[-] --> deep←0:n.first,deep←0:n
                collect=[0:n∈1:out*.§$s,0:n.kids.§$s≤1:out*.§$s,0:n.kids.§$s≤1:out*, 1:out*.§$s∋0:n,1:out*.§$s≥0:n.kids.§$s,1:out*≥0:n.kids.§$s] --> -
                collect1=[0:n∈1:out*.§$s,0:n.kids.§$s≤1:out*.§$s,0:n.kids.§$s≤1:out*, 1:out*.§$s∋0:n,1:out*.§$s≥0:n.kids.§$s,1:out*≥0:n.kids.§$s] --> -
                all=[-] --> all.§$s∋0:n,all.§$s~0:n.kids.§$s
                """, sb.toString());
    }
}
