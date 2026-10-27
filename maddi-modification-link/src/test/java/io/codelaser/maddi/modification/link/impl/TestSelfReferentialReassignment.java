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

package io.codelaser.maddi.modification.link.impl;

import io.codelaser.maddi.cst.api.info.TypeInfo;
import io.codelaser.maddi.modification.link.CommonTest;
import io.codelaser.maddi.modification.prepwork.PrepAnalyzer;
import io.codelaser.maddi.modification.prepwork.variable.MethodLinkedVariables;
import org.intellij.lang.annotations.Language;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A local reassigned from its own face, {@code n = n.next} or {@code node = node.map.get(s)}, and a local reassigned
 * in a loop (CodeLaser/maddi-mod#72, item 4a). Three gaps, each with its gate:
 * <ul>
 *     <li>the right-hand side spells the OLD value, which the reassignment clears before the statement's links go
 *     in (gate NOOLDVALUE): the walk forgot where it came from;</li>
 *     <li>a while, for or for-each body was linked as if it always runs, and a for statement's updaters before the
 *     body (gate NOLOOPFORK): the value from before the loop was lost;</li>
 *     <li>a reassigned member left its group with the assignments that tied the others together, {@code r = n; n =
 *     n.next} (gate NOBRIDGE): r lost root.</li>
 * </ul>
 * The body of a loop is still linked once, from the state before the loop: a value that only the second iteration
 * has ({@code r = n} after {@code n = o}) is not seen.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class TestSelfReferentialReassignment extends CommonTest {

    @Language("java")
    private static final String INPUT = """
            package a.b;
            import java.util.*;
            public class C {
                static class Node {
                    Node next;
                    List<String> data;
                    Map<String, Node> map;
                }
                private final Node root = new Node();

                Node viaMapNoReassign(String s) { Node node = root; Node next = node.map.get(s); return next; }
                Node viaMap(String s) { Node node = root; node = node.map.get(s); return node; }
                Node viaField() { Node n = root; n = n.next; return n; }
                void modifyNoReassign(String s) { Node n = root; Node m = n.next; m.data.add(s); }
                void modifyAfterStep(String s) { Node n = root; n = n.next; n.data.add(s); }
                Node condSelf(boolean c) { Node n = root; if (c) n = n.next; return n; }
                Node loopOther(Node other) { Node n = root; while (n.next != null) n = other; return n; }
                Node last() { Node n = root; while (n.next != null) n = n.next; return n; }
                Node forLast() { Node n = root; for (; n.next != null; n = n.next) { } return n; }
                Node forDeclLast() { Node r = null; for (Node n = root; n != null; n = n.next) { r = n; } return r; }
                Node whileEquiv() { Node r = null; Node n = root; while (n != null) { r = n; n = n.next; } return r; }
                void forModify(String s) { for (Node n = root; n != null; n = n.next) { n.data.add(s); } }
                void walkAndModify(String[] ss) {
                    Node n = root;
                    for (String s : ss) n = n.map.computeIfAbsent(s, k -> new Node());
                    n.data.add("x");
                }
            }
            """;

    // parsed + prepped + linked once, shared by all tests (PER_CLASS instance keeps this cache alive)
    private Map<String, String> cache;

    private String mlv(String name) {
        if (cache == null) {
            TypeInfo c = javaInspector.parse("a.b.C", INPUT);
            new PrepAnalyzer(runtime, new PrepAnalyzer.Options.Builder().build()).doPrimaryType(c);
            LinkComputerImpl lc = new LinkComputerImpl(javaInspector);
            cache = new HashMap<>();
            c.methodStream().forEach(m -> {
                MethodLinkedVariables mlv = lc.doMethod(m);
                cache.put(m.name(), mlv + "  MOD[" + mlv.sortedModifiedString() + "]");
            });
        }
        return cache.get(name);
    }

    @DisplayName("node = node.map.get(s): the return value is a value of root's map, as without the reassignment")
    @Test
    public void viaMap() {
        assertEquals("[-] --> viaMapNoReassign∈this.root.map.§$$s[-2]  MOD[]", mlv("viaMapNoReassign"));
        assertEquals("[-] --> viaMap∈this.root.map.§$$s[-2]  MOD[]", mlv("viaMap"));
    }

    @DisplayName("n = n.next: the return value is root's next; conditionally, root or root's next")
    @Test
    public void viaField() {
        assertEquals("[] --> viaField←this.root.next  MOD[]", mlv("viaField"));
        assertEquals("[-] --> condSelf←this.root.next,condSelf←this.root  MOD[]", mlv("condSelf"));
    }

    @DisplayName("a modification through the walked-to node reaches root")
    @Test
    public void modifyAfterStep() {
        assertEquals("[0:s≤this.root*] --> -  MOD[this, this.root]", mlv("modifyNoReassign"));
        assertEquals("[0:s≤this.root*,0:s∈this.root*.next.data.§$s] --> -  MOD[this, this.root]",
                mlv("modifyAfterStep"));
    }

    @DisplayName("a loop may not run: the value from before it stays")
    @Test
    public void loops() {
        assertEquals("[-] --> loopOther←0:other,loopOther←this.root  MOD[]", mlv("loopOther"));
        assertEquals("[] --> last←this.root.next,last←this.root  MOD[]", mlv("last"));
        assertEquals("[] --> forLast←this.root.next,forLast←this.root  MOD[]", mlv("forLast"));
        assertEquals("[] --> forDeclLast←$_ce0,forDeclLast←this.root  MOD[]", mlv("forDeclLast"));
        assertEquals("[] --> whileEquiv←$_ce0,whileEquiv←this.root.next,whileEquiv←this.root  MOD[]",
                mlv("whileEquiv"));
    }

    @DisplayName("a loop walk that modifies: the modification reaches root")
    @Test
    public void loopsThatModify() {
        assertEquals("[0:s∈this.root*.data.§$s] --> -  MOD[this, this.root]", mlv("forModify"));
        assertEquals("""
                [0:ss.§ts∩this.root*.map.§$$s,0:ss.§ts∩this.root*.map.§$$s[-1],0:ss.§ts∩this.root*.map.§$$s[-2]] \
                --> -  MOD[this, this.root]\
                """, mlv("walkAndModify"));
    }
}
