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
 * CodeLaser/maddi-mod#139: a modification two element-levels down, an element of a gathered list (#134) of an element
 * of the parameter's list. fernflower's {@code SwitchPatternHelper.SwitchOnReferenceCandidate.normalizeCaseLabels}
 * indexes the switch's case statements and sets {@code labeled} on a case's one successor edge. The edges are behind
 * lists, hidden content of the parameter: modified deep only, "MU".
 */
public class TestNestedGatheredElements extends CommonTest {

    @Language("java")
    private static final String INPUT = """
            package a.b;
            import java.util.*;
            class X {
                public static class Edge { public boolean labeled; }
                public static class St {
                    private final Map<String, List<Edge>> succ = new HashMap<>();
                    private final List<St> stats = new ArrayList<>();
                    public List<St> getStats() { return stats; }
                    public List<Edge> edges() {
                        List<Edge> res = new ArrayList<>();
                        for (String k : succ.keySet()) { List<Edge> l = succ.get(k); if (l != null) res.addAll(l); }
                        return res;
                    }
                }
                public static class Sw extends St {
                    private final List<St> cases = new ArrayList<>();
                    public List<St> getCases() { return cases; }
                }
                static void forEach(Sw sw) {
                    for (St c : sw.getCases()) { List<Edge> e = c.edges(); if (e.size() == 1) e.get(0).labeled = false; }
                }
                static void indexed(Sw sw) {
                    List<St> cs = sw.getCases();
                    for (int i = 0; i < cs.size(); i++) {
                        St c = cs.get(i); List<Edge> e = c.edges(); if (e.size() == 1) e.get(0).labeled = false;
                    }
                }
                static void reassigned(Sw sw) {
                    for (St c : sw.getCases()) {
                        List<Edge> e = c.edges();
                        if (e.isEmpty() && !c.getStats().isEmpty()) e = c.getStats().get(c.getStats().size() - 1).edges();
                        if (e.size() == 1) e.get(0).labeled = false;
                    }
                }
                // a map's value modified is no modification of what its keys overlap: the control for the face
                static boolean keysOnly(Sw sw) {
                    Map<St, Set<Edge>> ranges = new HashMap<>();
                    for (St c : sw.getCases()) ranges.computeIfAbsent(c, k -> new HashSet<>()).addAll(c.edges());
                    return ranges.size() > 1;
                }
                // one level, right since #134: the control
                static void oneLevel(St c) {
                    List<Edge> e = c.edges(); if (e.size() == 1) e.get(0).labeled = false;
                }
            }
            """;

    // the receiver-chain rule, a call result's container overlapping another's hidden content
    // (ShadowModificationPass.overlappingOwners); 'oneLevel' too was lost before
    @DisplayName("two element-levels down, with the reachability pass")
    @Test
    public void reachability() {
        assertEquals("forEach=MU indexed=MU reassigned=MU oneLevel=MU keysOnly=UU", run(true));
    }

    /*
     KNOWN GAP, the fixpoint engine only (production runs the reachability pass): 'indexed'. WriteLinksAndModification
     completes the statement's modifications in variable order, and 'c', modified through 'c.succ.§$$s ∩ e.§$s', comes
     after 'cs', 'sw.cases' and 'sw', which hold it (∋) and were decided already; in 'forEach' c comes first.
     And 'keysOnly' (a false modification): the engine's overlap rules have no element-type check, and the map's
     values slice overlaps what sw's cases hold.
     */
    @DisplayName("two element-levels down, the fixpoint engine")
    @Test
    public void fixpoint() {
        assertEquals("forEach=MU indexed=UU reassigned=MU oneLevel=MU keysOnly=MU", run(false));
    }

    private String run(boolean reach) {
        TypeInfo x = javaInspector.parse("a.b.X", INPUT);
        List<Info> ao = prepWork(x);
        new IteratingAnalyzerImpl(javaInspector, new IteratingAnalyzerImpl.ConfigurationBuilder()
                .setMaxIterations(10).setModificationViaReachability(reach).build()).analyze(ao);
        StringBuilder sb = new StringBuilder();
        for (String n : List.of("forEach", "indexed", "reassigned", "oneLevel", "keysOnly")) {
            MethodInfo m = x.methods().stream().filter(mi -> mi.name().equals(n)).findFirst().orElseThrow();
            if (!sb.isEmpty()) sb.append(' ');
            sb.append(n).append('=').append(m.parameters().getFirst().isUnmodified() ? 'U' : 'M')
                    .append(m.parameters().getFirst().isStructurallyUnmodified() ? 'U' : 'M');
        }
        return sb.toString();
    }
}
