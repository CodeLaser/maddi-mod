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
 * The examples of road-to-immutability §080, "Propagating modifications" (CodeLaser/maddi#209): when a modification
 * of an element is structural, and when it is a modification of hidden content only.
 */
public class TestBook080Elements extends CommonTest {

    @Language("java")
    private static final String INPUT = """
            package a.b;
            import java.util.*;
            public class X {
                static class Node {
                    int mark;
                    final List<Node> children = new ArrayList<>();
                    Node first;
                    void clear() { mark = 0; }
                }
                static class Leaf {
                    int mark;
                    final List<Leaf> children = new ArrayList<>();
                    void clear() { mark = 0; }
                }
                static class Box<T> {
                    final List<T> items = new ArrayList<>();
                    T first;
                }
                static void clearChildren(Node node) { for (Node c : node.children) c.clear(); }
                static void clearFirst(Node node) { node.first.clear(); }
                static void clearList(List<Node> nodes) { for (Node c : nodes) c.clear(); }
                static void clearBox(Box<Node> box) { for (Node c : box.items) c.clear(); }
                static void addChild(Node node) { node.children.add(new Node()); }
                static void clearLeaves(Leaf leaf) { for (Leaf c : leaf.children) c.clear(); }

                static String useClearList(Node input) {
                    List<Node> list = new ArrayList<>();
                    list.add(input);
                    clearList(list);
                    return list.get(0).toString();
                }
                static String useLocal(Node input) {
                    List<Node> list = new LinkedList<>();
                    list.add(input);
                    for (Node c : list) c.clear();
                    return list.get(0).toString();
                }
            }
            """;

    @DisplayName("§080's examples, fixpoint")
    @Test
    public void fixpoint() {
        test(false);
    }

    @DisplayName("§080's examples, with the reachability pass")
    @Test
    public void reachability() {
        test(true);
    }

    private void test(boolean reach) {
        TypeInfo x = javaInspector.parse("a.b.X", INPUT);
        List<Info> ao = prepWork(x);
        new IteratingAnalyzerImpl(javaInspector, new IteratingAnalyzerImpl.ConfigurationBuilder()
                .setMaxIterations(10).setModificationViaReachability(reach).build()).analyze(ao);
        // deep/structural: true means unmodified
        assertEquals("false/false", p(x, "clearChildren"), "(1): Node.first may be one of the children");
        assertEquals("false/false", p(x, "clearFirst"), "(2)");
        assertEquals("false/true", p(x, "clearList"), "(3): the list changes in its elements only");
        assertEquals("false/true", p(x, "clearBox"), "(4): to Box, the elements are its type parameter T");
        assertEquals("false/false", p(x, "addChild"), "(5)");
        assertEquals("false/true", p(x, "clearLeaves"), "no accessible field a child could be");
        // the step back to the elements: done when the element is modified locally (CodeLaser/maddi-mod#113),
        // not yet through a callee
        assertEquals("true/true", p(x, "useClearList"));
        assertEquals("false", p(x, "useLocal").substring(0, 5));
    }

    private static String p(TypeInfo typeInfo, String name) {
        MethodInfo mi = typeInfo.methods().stream().filter(m -> m.name().equals(name)).findFirst().orElseThrow();
        return mi.parameters().getFirst().isUnmodified() + "/" + mi.parameters().getFirst().isStructurallyUnmodified();
    }
}
