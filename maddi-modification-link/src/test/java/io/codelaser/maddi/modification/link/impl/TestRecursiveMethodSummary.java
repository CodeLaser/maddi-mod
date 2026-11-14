package io.codelaser.maddi.modification.link.impl;

import static io.codelaser.maddi.modification.link.impl.MethodLinkedVariablesImpl.METHOD_LINKS;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.intellij.lang.annotations.Language;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.codelaser.maddi.cst.api.info.MethodInfo;
import io.codelaser.maddi.cst.api.info.TypeInfo;
import io.codelaser.maddi.cst.impl.analysis.PropertyImpl;
import io.codelaser.maddi.cst.impl.analysis.ValueImpl;
import io.codelaser.maddi.modification.link.CommonTest;
import io.codelaser.maddi.modification.link.LinkComputer;
import io.codelaser.maddi.modification.prepwork.PrepAnalyzer;

/**
 * CodeLaser/maddi-mod#127: in a method that is part of a recursion, a call to a callee OUTSIDE the recursion (a library
 * method, a plain helper) keeps its summary. Only a call back into the recursion gets the empty one. The recursive
 * variants must link {@code t} exactly as the flat ones do.
 */
public class TestRecursiveMethodSummary extends CommonTest {

    @Language("java")
    private static final String INPUT = """
            package a.b;
            import java.util.*;
            class Scc {
                static class Tree { final List<Tree> children = new ArrayList<>(); }
                private final Deque<Tree> component = new ArrayDeque<>();
                private final Map<Tree, Integer> indices = new HashMap<>();
                void visit(Tree t) { component.push(t); indices.put(t, indices.size()); for (Tree c : t.children) visit(c); }
                void flat(Tree t)  { component.push(t); indices.put(t, indices.size()); }
                void loop(Tree t)  { component.push(t); indices.put(t, indices.size()); for (Tree c : t.children) flat(c); }
                static Set<Tree> labelLoop(Tree t) { Set<Tree> s = new HashSet<>(); for (Tree c : t.children) s.addAll(labelFlat(c)); s.add(t); return s; }
                static Set<Tree> label(Tree t) { Set<Tree> s = new HashSet<>(); for (Tree c : t.children) s.addAll(label(c)); s.add(t); return s; }
                static Set<Tree> labelFlat(Tree t) { Set<Tree> s = new HashSet<>(); s.add(t); return s; }
            }
            """;

    @DisplayName("#127: a recursive method keeps the summaries of callees outside its recursion")
    @Test
    public void test() {
        TypeInfo X = javaInspector.parse("a.b.Scc", INPUT);
        PrepAnalyzer analyzer = new PrepAnalyzer(runtime, new PrepAnalyzer.Options.Builder().build());
        analyzer.doPrimaryType(X);
        LinkComputer tlc = new LinkComputerImpl(javaInspector);
        tlc.doPrimaryType(X);

        MethodInfo visit = X.findUniqueMethod("visit", 1);
        assertEquals("true", visit.analysis().getOrDefault(PropertyImpl.RECURSIVE_METHOD,
                ValueImpl.BoolImpl.FALSE).toString(), "precondition: visit is RECURSIVE_METHOD");
        String summary = "visit=" + mlv(X, "visit") + "\nflat=" + mlv(X, "flat")
                         + "\nloop=" + mlv(X, "loop")
                         + "\nlabel=" + mlv(X, "label") + "\nlabelFlat=" + mlv(X, "labelFlat")
                         + "\nlabelLoop=" + mlv(X, "labelLoop");
        // before #127 (gate NONARROWRECURSION): visit=[-] --> - and label=[-] --> -, nothing linked to t.
        // The recursive methods now link t as the flat ones do, and the descendants as their non-recursive loop twins
        // do ('loop', 'labelLoop'); the '*' on t and t.children comes from the for-each, as in 'loop'
        assertEquals("""
                visit=[0:t*∈this.component*.§$s,0:t*∈this.indices*.§$$s[-1],0:t.children*.§$s≤this.component*.§$s,\
                0:t.children*.§$s≤this.indices*.§$$s,0:t.children*.§$s≤this.indices*.§$$s[-1]] --> -
                flat=[0:t∈this.component*.§$s,0:t∈this.indices*.§$$s[-1]] --> -
                loop=[0:t*∈this.component*.§$s,0:t*∈this.indices*.§$$s[-1],0:t.children*.§$s≤this.component*.§$s,\
                0:t.children*.§$s≤this.indices*.§$$s,0:t.children*.§$s≤this.indices*.§$$s[-1],\
                0:t.children*.§$s≤this.component*] --> -
                label=[-] --> label.§$s∋0:t*,label.§$s≥0:t.children*.§$s
                labelFlat=[-] --> labelFlat.§$s∋0:t
                labelLoop=[-] --> labelLoop.§$s∋0:t,labelLoop.§$s~0:t.children.§$s""", summary);
    }

    private static String mlv(TypeInfo X, String name) {
        MethodInfo mi = X.findUniqueMethod(name, 1);
        return mi.analysis().getOrNull(METHOD_LINKS, MethodLinkedVariablesImpl.class).toString();
    }
}
