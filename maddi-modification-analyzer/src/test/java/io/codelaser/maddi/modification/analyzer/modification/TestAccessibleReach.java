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
import io.codelaser.maddi.modification.prepwork.Util;
import org.intellij.lang.annotations.Language;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CodeLaser/maddi-mod#130, the definition of a structural modification: a write to an object that is the parameter's
 * accessible content, by SOME path of accessible fields, whichever path the method took to it; accessible content
 * judged by declared types. fernflower's LabelHelper: an edge comes out of the statement's edge list (hidden content),
 * and the statement it points to is modified. Statements are accessible content of a statement ('parent', 'first'),
 * so the write may be to the parameter's own state: structural. Writing the edge itself is not: no field of St
 * declares an Edge (IfSt's does, a subclass: hidden content of St, accessible content of an IfSt).
 */
public class TestAccessibleReach extends CommonTest {

    @Language("java")
    private static final String INPUT = """
            package a.b;
            import java.util.*;
            public class X {
                public static class St {
                    St parent;
                    St first;
                    private final List<Edge> edges = new ArrayList<>();
                    private final Set<Edge> labels = new HashSet<>();
                    public List<Edge> getEdges() { return edges; }
                    public void addLabel(Edge e) { labels.add(e); }
                }
                public static class IfSt extends St {
                    Edge ifedge;
                }
                public static class Edge {
                    St dest;
                    boolean labeled;
                    public St getDest() { return dest; }
                }
                public static class Item {
                    int n;
                    public void touch() { n++; }
                }
                public static class Bag {
                    final List<Item> items = new ArrayList<>();
                }
                // liftClosures: the edge's destination, a statement, gets a label: it may be s itself
                static void lift(St s) {
                    for (Edge e : s.getEdges()) e.getDest().addLabel(e);
                }
                // resetAllEdges: the edges themselves are written, and St declares no Edge field
                static void reset(St s) {
                    for (Edge e : s.getEdges()) e.labeled = true;
                }
                // ... but an IfSt does
                static void resetIf(IfSt s) {
                    for (Edge e : s.getEdges()) e.labeled = true;
                }
                // a container of items that hold nothing back: deep only, as before
                static void touchAll(Bag b) {
                    for (Item i : b.items) i.touch();
                }
            }
            """;

    @DisplayName("hidden content that may be accessible content too is modified structurally")
    @Test
    public void fixpoint() {
        test(false);
    }

    @DisplayName("idem, with the reachability pass")
    @Test
    public void reachability() {
        test(true);
    }

    private void test(boolean reach) {
        TypeInfo x = javaInspector.parse("a.b.X", INPUT);
        List<Info> ao = prepWork(x);
        new IteratingAnalyzerImpl(javaInspector, new IteratingAnalyzerImpl.ConfigurationBuilder()
                .setMaxIterations(10).setModificationViaReachability(reach).build()).analyze(ao);
        assertEquals("false/false", p(x, "lift"));
        // the reachability pass knows the edge's own field is written (exact); the engine knows only that the edge is
        // modified, maybe in what it holds, a St: structural, sound but coarser
        assertEquals(reach ? "false/true" : "false/false", p(x, "reset"));
        assertEquals("false/false", p(x, "resetIf"));
        assertEquals("false/true", p(x, "touchAll"));
    }

    /**
     * A name is not a type. The gate runs many tests in one JVM, and three of them declare their own {@code a.b.X.St}:
     * the accessible types cached for the first answered for the others, and 'lift' lost its structural verdict
     * (gate 44510614747). Two parses of one name with different fields must each get their own.
     */
    @DisplayName("the accessible types of a type are its own, not those of an earlier type of the same name")
    @Test
    public void sameNameAnotherType() throws Exception {
        TypeInfo x1 = javaInspector.parse("a.b.X", """
                package a.b;
                public class X {
                    public static class St { int n; }
                }
                """);
        TypeInfo st1 = x1.findSubType("St");
        assertEquals(List.of(), Util.accessibleTypes(st1.asParameterizedType()));
        beforeEach(); // a fresh inspector, as the next test class gets
        TypeInfo x2 = javaInspector.parse("a.b.X", INPUT);
        TypeInfo st2 = x2.findSubType("St");
        assertNotSame(st1, st2);
        assertTrue(Util.accessibleTypes(st2.asParameterizedType()).stream()
                .anyMatch(pt -> pt.typeInfo() == st2), "St is accessible content of St, through 'parent'");
    }

    private static String p(TypeInfo typeInfo, String name) {
        MethodInfo mi = typeInfo.methods().stream().filter(m -> m.name().equals(name)).findFirst().orElseThrow();
        return mi.parameters().getFirst().isUnmodified() + "/" + mi.parameters().getFirst().isStructurallyUnmodified();
    }
}
