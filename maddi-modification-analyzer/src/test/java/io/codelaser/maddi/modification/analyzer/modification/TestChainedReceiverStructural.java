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

import io.codelaser.maddi.cst.api.info.FieldInfo;
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
 * CodeLaser/maddi-mod#114, fernflower's Statement.addEdgeDirectInternal: in 'm.computeIfAbsent(k, ..).add(e)' the first
 * call modifies m structurally (it may put a key), the second modifies one of m's values. The chained call kept only
 * the second, deep-only. 'm.get(k).add(e)' modifies a value only: structurally non-modifying.
 */
public class TestChainedReceiverStructural extends CommonTest {

    @Language("java")
    private static final String INPUT = """
            package a.b;
            import java.util.*;
            public class X {
                private final Map<String, List<String>> pred = new HashMap<>();
                private final Map<String, List<String>> succ = new HashMap<>();
                private final Map<String, List<String>> other = new HashMap<>();
                public void addTernary(boolean b, String t, String e) {
                    Map<String, List<String>> m = b ? pred : succ;
                    m.computeIfAbsent(t, k -> new ArrayList<>()).add(e);
                }
                public void addLocal(String t, String e) {
                    Map<String, List<String>> m = pred;
                    m.computeIfAbsent(t, k -> new ArrayList<>()).add(e);
                }
                public void addDirect(String t, String e) {
                    pred.computeIfAbsent(t, k -> new ArrayList<>()).add(e);
                }
                public void addToValue(String t, String e) {
                    other.get(t).add(e);
                }
                static void param(Map<String, List<String>> map, String t) {
                    map.computeIfAbsent(t, k -> new ArrayList<>()).add("x");
                }
                static void paramValue(Map<String, List<String>> map, String t) {
                    map.get(t).add("x");
                }
            }
            """;

    @DisplayName("a chained call keeps its receiver call's structural modification")
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
        assertEquals("false/false", m(x, "addTernary"));
        assertEquals("false/false", m(x, "addLocal"));
        assertEquals("false/false", m(x, "addDirect"));
        assertEquals("false/true", m(x, "addToValue"));
        assertEquals("false/false", p(x, "param"));
        assertEquals("false/true", p(x, "paramValue"));
        assertEquals("false/false", f(x, "pred"));
        assertEquals("false/false", f(x, "succ"));
        assertEquals("false/true", f(x, "other"));
    }

    private static MethodInfo find(TypeInfo typeInfo, String name) {
        return typeInfo.methods().stream().filter(m -> m.name().equals(name)).findFirst().orElseThrow();
    }

    private static String m(TypeInfo typeInfo, String name) {
        MethodInfo mi = find(typeInfo, name);
        return mi.isNonModifying() + "/" + mi.isStructurallyNonModifying();
    }

    private static String p(TypeInfo typeInfo, String name) {
        MethodInfo mi = find(typeInfo, name);
        return mi.parameters().getFirst().isUnmodified() + "/" + mi.parameters().getFirst().isStructurallyUnmodified();
    }

    private static String f(TypeInfo typeInfo, String name) {
        FieldInfo fi = typeInfo.getFieldByName(name, true);
        return fi.isUnmodified() + "/" + fi.isStructurallyUnmodified();
    }
}
