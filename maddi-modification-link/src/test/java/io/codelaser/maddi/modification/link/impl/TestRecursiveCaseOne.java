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
 * CodeLaser/maddi-mod#127, case 1: a recursive method reading its argument through a source callee with a body.
 * Inside the recursion such a callee (not recursive itself) hands back its return links only, at most
 * {@code RECURSIONRETURNCAP} of them (ExpressionVisitor.returnLinksOnly). A plain getter ({@code kidsOf}) linked
 * already; {@code kidsOrEmpty}, with a body the getter detection does not see through, linked nothing.
 */
public class TestRecursiveCaseOne extends CommonTest {

    @Language("java")
    private static final String INPUT = """
            package a.b;
            import java.util.*;
            class X {
                public static class Node {
                    public List<String> data; public List<Node> kids;
                    public List<Node> kidsOf() { return kids; }
                    public List<Node> kidsOrEmpty() { return kids == null ? List.of() : kids; }
                }
                public static Node leaf(Node n) { for (Node k : n.kidsOf()) return leaf(k); return n; }
                public static Node leaf2(Node n) { for (Node k : n.kidsOrEmpty()) return leaf2(k); return n; }
                public static Node leaf1(Node n) { for (Node k : n.kids) return leaf1(k); return n; }
            }
            """;

    @DisplayName("#127 case 1: a source callee's return links reach a recursive caller")
    @Test
    public void test() {
        TypeInfo X = javaInspector.parse("a.b.X", INPUT);
        PrepAnalyzer analyzer = new PrepAnalyzer(runtime, new PrepAnalyzer.Options.Builder().build());
        analyzer.doPrimaryType(X);
        LinkComputer tlc = new LinkComputerImpl(javaInspector);
        tlc.doPrimaryType(X);
        StringBuilder sb = new StringBuilder();
        for (String name : new String[]{"leaf", "leaf2", "leaf1"}) {
            MethodInfo mi = X.findUniqueMethod(name, 1);
            sb.append(name).append('=').append(mi.analysis().getOrNull(METHOD_LINKS, MethodLinkedVariablesImpl.class))
                    .append('\n');
        }
        // without it (gate NORECURSIONRETURNSUMMARIES): leaf2=[-] --> leaf2←0:n
        assertEquals("""
                leaf=[-] --> leaf←0:n,leaf∈0:n.kids.§$s,leaf.kids.§$s≤0:n.kids.§$s,leaf.kids.§$s←0:n.kids.§$s,leaf.kids.§m☷0:n.kids.§m
                leaf2=[-] --> leaf2.kids≤0:n.kids,leaf2.kids≡0:n.kids,leaf2.kids←0:n.kids,leaf2←0:n,leaf2.kids.§$s∋0:n,leaf2.kids.§$s≤0:n.kids.§$s,leaf2.kids.§$s≡0:n.kids.§$s,leaf2.kids.§$s←0:n.kids.§$s,leaf2.kids.§$s→0:n.kids.§$s,leaf2.kids.§m≡0:n.kids.§m
                leaf1=[-] --> leaf1←0:n,leaf1∈0:n.kids.§$s,leaf1.kids.§$s≤0:n.kids.§$s,leaf1.kids.§$s←0:n.kids.§$s,leaf1.kids.§m☷0:n.kids.§m
                """, sb.toString());
    }
}
