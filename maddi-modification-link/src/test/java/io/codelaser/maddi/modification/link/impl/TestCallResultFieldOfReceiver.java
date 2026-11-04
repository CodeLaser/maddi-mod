/*
 * maddi: a modification analyzer for duplication detection and immutability.
 * Copyright 2020-2026, CodeLaser BV, https://github.com/CodeLaser/maddi
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.codelaser.maddi.modification.link.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.Map;

import org.intellij.lang.annotations.Language;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import io.codelaser.maddi.cst.api.info.TypeInfo;
import io.codelaser.maddi.modification.link.CommonTest;
import io.codelaser.maddi.modification.prepwork.PrepAnalyzer;
import io.codelaser.maddi.modification.prepwork.variable.MethodLinkedVariables;

/**
 * A call result that is a field of the receiver, or of an object reachable from it ('getWrapper ≺ this.parent'),
 * carries a modification of the result back to the receiver (fernflower's ClassNode.getWrapper,
 * CodeLaser/maddi-mod#80).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class TestCallResultFieldOfReceiver extends CommonTest {

    @Language("java")
    private static final String INPUT = """
            package a.b;
            public class C {
                static class Wrapper { int x; void set(int v) { x = v; } }
                static class Node {
                    Wrapper wrapper;
                    int type;
                    Node parent;
                    Wrapper getWrapper() {
                        Node node = this;
                        while (node.type == 1) {
                            node = node.parent;
                        }
                        return node.wrapper;
                    }
                    Wrapper direct() { return wrapper; }
                    Wrapper ofParent() { return parent.wrapper; }
                }
                static void viaLoop(Node parent) { Wrapper w = parent.getWrapper(); w.x = 1; }
                static void viaField(Node parent) { Wrapper w = parent.wrapper; w.x = 1; }
                static void viaDirect(Node parent) { Wrapper w = parent.direct(); w.x = 1; }
                static void viaOfParent(Node parent) { Wrapper w = parent.ofParent(); w.x = 1; }
                static void viaChain(Node parent) { parent.getWrapper().x = 1; }
                static void viaCall(Node parent) { parent.getWrapper().set(1); }
                static void viaLocalCall(Node parent) { Wrapper w = parent.getWrapper(); w.set(1); }
                static class Holder {
                    java.util.List<Wrapper> list = new java.util.ArrayList<>();
                    Wrapper first() { return list.get(0); }
                }
                static class W2 { Holder h = new Holder(); Holder getH() { return h; } }
                static class N2 {
                    W2 wrapper;
                    int type;
                    N2 parent;
                    W2 getWrapper() {
                        N2 node = this;
                        while (node.type == 1) {
                            node = node.parent;
                        }
                        return node.wrapper;
                    }
                }
                static void viaGetter(N2 parent) { parent.getWrapper().getH().list.clear(); }
                static void viaElement(N2 parent) { Wrapper w = parent.getWrapper().getH().first(); w.x = 1; }
                static void viaElementField(N2 parent) { Wrapper w = parent.wrapper.getH().first(); w.x = 1; }
                static int readOnly(Node parent) { Wrapper w = parent.getWrapper(); return w.x; }
            }
            """;

    private Map<String, String> cache;

    private String mlv(String name) {
        if (cache == null) {
            TypeInfo c = javaInspector.parse("a.b.C", INPUT);
            new PrepAnalyzer(runtime, new PrepAnalyzer.Options.Builder().build()).doPrimaryType(c);
            LinkComputerImpl lc = new LinkComputerImpl(javaInspector);
            cache = new LinkedHashMap<>();
            c.recursiveSubTypeStream().flatMap(TypeInfo::constructorAndMethodStream).forEach(m -> {
                MethodLinkedVariables mlv = lc.doMethod(m);
                cache.put(m.name(), mlv + "  MOD[" + mlv.sortedModifiedString() + "]");
            });
        }
        return cache.get(name);
    }

    @DisplayName("the callee walks a parent chain: its result is a field of this or of an object it reaches")
    @Test
    public void summary() {
        assertEquals("[] --> getWrapper≺this.parent,getWrapper≺this  MOD[]", mlv("getWrapper"));
    }

    @DisplayName("a modification of the result, held in a local, assigned through, or by a call, reaches the receiver")
    @Test
    public void modification() {
        // was 'MOD[]' each (gate NOCALLRESULTFIELD): the containment filter dropped 'w ≺ 0:parent', as it drops an
        // accessor copy's
        assertEquals("[-] --> -  MOD[a.b.C.viaLoop(a.b.C.Node):0:parent]", mlv("viaLoop"));
        assertEquals("[0:parent*.parent≻parent.getWrapper().x,0:parent*≻parent.getWrapper().x] --> -"
                     + "  MOD[a.b.C.viaChain(a.b.C.Node):0:parent]", mlv("viaChain"));
        assertEquals("[-] --> -  MOD[$_ce1, a.b.C.viaCall(a.b.C.Node):0:parent]", mlv("viaCall"));
        assertEquals("[-] --> -  MOD[$_ce1, a.b.C.viaLocalCall(a.b.C.Node):0:parent]", mlv("viaLocalCall"));
    }

    @DisplayName("a getter on the result, and an element of a collection on the result held in a local")
    @Test
    public void deeper() {
        // was 'MOD[]': the inlined getter dropped its receiver's links (gate NOGETTERRECEIVER)
        assertEquals("[-] --> -  MOD[a.b.C.viaGetter(a.b.C.N2):0:parent]", mlv("viaGetter"));
        // KNOWN GAP, fernflower's setLambdaVars ('parent.getWrapper().getMethods().getWithKey(..)', then a modification
        // of it): the element held in 'w' only gets '0:parent.parent ≥ w', and a modification does not travel back
        // over ≥. Letting it travel (deep-only, as ∈) also reads a list iterated read-only as modified, since the
        // iterator sits in its object graph (TestModificationThroughView), so it needs a sharper link than ≤ here
        assertEquals("[-] --> -  MOD[]", mlv("viaElement"));
        assertEquals("[-] --> -  MOD[a.b.C.viaElementField(a.b.C.N2):0:parent, parent.wrapper, parent.wrapper.h]",
                mlv("viaElementField"));
    }

    @DisplayName("as through the field itself, or through a getter returning it")
    @Test
    public void reference() {
        assertEquals("[0:parent.wrapper*.x←$_ce0,0:parent.wrapper*.x≺0:parent.wrapper*] --> -"
                     + "  MOD[a.b.C.viaField(a.b.C.Node):0:parent, parent.wrapper]", mlv("viaField"));
        assertEquals(mlv("viaField").replace("viaField", "viaDirect"), mlv("viaDirect"));
    }

    @DisplayName("reading the result modifies nothing")
    @Test
    public void readOnly() {
        assertEquals("[-] --> readOnly≺0:parent.parent,readOnly≺0:parent  MOD[]", mlv("readOnly"));
    }
}
