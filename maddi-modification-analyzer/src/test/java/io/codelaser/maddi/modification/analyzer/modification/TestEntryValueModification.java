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

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.intellij.lang.annotations.Language;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.codelaser.maddi.cst.api.info.Info;
import io.codelaser.maddi.cst.api.info.MethodInfo;
import io.codelaser.maddi.cst.api.info.TypeInfo;
import io.codelaser.maddi.modification.analyzer.CommonTest;
import io.codelaser.maddi.modification.analyzer.impl.IteratingAnalyzerImpl;

/**
 * CodeLaser/maddi-mod#147: a modification of an entry's value, the entry an element of a list of entries over a field's
 * own objects, reaches the field's owner. fernflower's {@code StackVarsProcessor.replaceSingleVar}: the entries come
 * from {@code SFormsFastMapDirect.entryList()} (anonymous entries, #43; a two-dimensional field, #148), and
 * {@code FastSparseSet.complement} writes the value. Shapes s1 to s4 grow toward it: a for-each, an explicit iterator
 * with a local value, the map in a field of the parameter, and the node out of a list in the parameter's graph.
 * <p>
 * Modification is per-parameter "unmodified/structurally unmodified" (U or M). The written {@code Bits} is accessible
 * content of a {@code BitsMap} (a plain array field) and so of a {@code Node} (#130): structural. A {@code Node} out of
 * the {@code Graph}'s list is hidden content of the {@code Ssa}: deep only.
 */
public class TestEntryValueModification extends CommonTest {

    @Language("java")
    private static final String INPUT = """
            package a.b;
            import java.util.*;
            class Z {
                static class Bits {
                    final int[] data = new int[4];
                    void complement(Bits other) { data[0] &= ~other.data[0]; }
                    boolean isEmpty() { return data[0] == 0; }
                }
                static class BitsMap {
                    private final Bits[][] elements = new Bits[3][];
                    List<Map.Entry<Integer, Bits>> entryList() {
                        List<Map.Entry<Integer, Bits>> list = new ArrayList<>();
                        for (int i = 2; i >= 0; i--) {
                            for (final Bits ent : elements[i]) {
                                if (ent != null) list.add(new Map.Entry<>() {
                                    private final Bits val = ent;
                                    public Integer getKey() { return 0; }
                                    public Bits getValue() { return val; }
                                    public Bits setValue(Bits v) { return null; }
                                });
                            }
                        }
                        return list;
                    }
                }
                static class Node { final BitsMap live = new BitsMap(); }
                static class Graph { final List<Node> nodes = new ArrayList<>(); Node get(int k) { return nodes.get(k); } }
                static class Ssa { private final Graph g = new Graph(); Graph getG() { return g; } }

                static void s1(BitsMap map, Bits o) { for (Map.Entry<Integer, Bits> e : map.entryList()) e.getValue().complement(o); }
                static void s2(BitsMap map, Bits o) {
                    for (Iterator<Map.Entry<Integer, Bits>> it = map.entryList().iterator(); it.hasNext(); ) {
                        Map.Entry<Integer, Bits> ent = it.next();
                        Bits set = ent.getValue();
                        set.complement(o);
                        if (set.isEmpty()) it.remove();
                    }
                }
                static void s3(Node node, Bits o) {
                    for (Iterator<Map.Entry<Integer, Bits>> it = node.live.entryList().iterator(); it.hasNext(); ) {
                        Map.Entry<Integer, Bits> ent = it.next();
                        Bits set = ent.getValue();
                        set.complement(o);
                    }
                }
                static void s4(Ssa ssa, int k, Bits o) {
                    Node node = ssa.getG().get(k);
                    for (Iterator<Map.Entry<Integer, Bits>> it = node.live.entryList().iterator(); it.hasNext(); ) {
                        Map.Entry<Integer, Bits> ent = it.next();
                        Bits set = ent.getValue();
                        set.complement(o);
                    }
                }
            }
            """;

    @DisplayName("an entry's value modified: the owner of the entries' values, with the reachability pass")
    @Test
    public void reachability() {
        assertEquals("s1=MM s2=MM s3=MM s4=MU", run(true));
    }

    // KNOWN GAP, the fixpoint engine only (production runs the reachability pass): the explicit iterator shapes
    @DisplayName("idem, the fixpoint engine: the for-each only")
    @Test
    public void fixpoint() {
        assertEquals("s1=MU s2=UU s3=UU s4=UU", run(false));
    }

    /*
     The control: a holder whose own field is rebound ('r.element = x') is modified itself; it holds nothing modified,
     so nothing passes on to the list it may hold an element of. The fixpoint rule took any modified holder and marked
     xs (the gate, TestKotlinLinkFunctions.varAssignedInLambda's capturedRefInit).
     */
    @Language("java")
    private static final String REF = """
            package a.b;
            import java.util.*;
            class R {
                static class Ref<T> { T element; }
                static StringBuilder capturedRefInit(List<StringBuilder> xs, StringBuilder a) {
                    Ref<StringBuilder> r = new Ref<>();
                    r.element = a;
                    xs.forEach(x -> { r.element = x; });
                    return r.element;
                }
                static StringBuilder loopRef(List<StringBuilder> xs, StringBuilder a) {
                    Ref<StringBuilder> r = new Ref<>();
                    r.element = a;
                    for (StringBuilder x : xs) r.element = x;
                    return r.element;
                }
            }
            """;

    @DisplayName("control: a holder whose field is rebound holds nothing modified")
    @Test
    public void rebindingIsNoModificationOfTheHeld() throws Exception {
        for (boolean reach : new boolean[]{false, true}) {
            beforeEach();
            TypeInfo r = javaInspector.parse("a.b.R", REF);
            List<Info> ao = prepWork(r);
            new IteratingAnalyzerImpl(javaInspector, new IteratingAnalyzerImpl.ConfigurationBuilder()
                    .setMaxIterations(10).setModificationViaReachability(reach).build()).analyze(ao);
            for (String n : List.of("capturedRefInit", "loopRef")) {
                MethodInfo m = r.methods().stream().filter(x -> x.name().equals(n)).findFirst().orElseThrow();
                assertEquals("xs=true a=true", "xs=" + m.parameters().get(0).isUnmodified()
                                                + " a=" + m.parameters().get(1).isUnmodified(), n + " reach=" + reach);
            }
        }
    }

    private String run(boolean reach) {
        TypeInfo z = javaInspector.parse("a.b.Z", INPUT);
        List<Info> ao = prepWork(z);
        new IteratingAnalyzerImpl(javaInspector, new IteratingAnalyzerImpl.ConfigurationBuilder()
                .setMaxIterations(10).setModificationViaReachability(reach).build()).analyze(ao);
        StringBuilder sb = new StringBuilder();
        for (String n : List.of("s1", "s2", "s3", "s4")) {
            MethodInfo m = z.methods().stream().filter(x -> x.name().equals(n)).findFirst().orElseThrow();
            if (!sb.isEmpty()) sb.append(' ');
            sb.append(n).append('=').append(m.parameters().getFirst().isUnmodified() ? 'U' : 'M')
                    .append(m.parameters().getFirst().isStructurallyUnmodified() ? 'U' : 'M');
        }
        return sb.toString();
    }
}
