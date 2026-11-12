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
import io.codelaser.maddi.cst.impl.analysis.ValueImpl;
import io.codelaser.maddi.modification.analyzer.CommonTest;
import io.codelaser.maddi.modification.analyzer.impl.IteratingAnalyzerImpl;
import org.intellij.lang.annotations.Language;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static io.codelaser.maddi.cst.impl.analysis.PropertyImpl.INDEPENDENT_METHOD;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * CodeLaser/maddi-mod#112, fernflower's Statement.getEdges: a new list holding the elements of one of a map field's
 * values shares those elements with the instance. When they are mutable, the method is dependent (the book's
 * Counters, §045), as a copy of a list field already was.
 */
public class TestCopiedMutableElements extends CommonTest {

    @Language("java")
    private static final String INPUT = """
            package a.b;
            import java.util.*;
            public class X {
                public static class Edge { public boolean labeled; }
                private final Map<String, List<Edge>> map = new HashMap<>();
                private final List<Edge> edges = new ArrayList<>();
                private final Map<String, List<String>> names = new HashMap<>();
                public List<Edge> copyField() { return new ArrayList<>(edges); }
                public List<Edge> copyMap(String t) {
                    List<Edge> res = map.get(t);
                    return res == null ? new ArrayList<>() : new ArrayList<>(res);
                }
                public Edge firstOfMap(String t) { return map.get(t).get(0); }
                public List<String> copyNames(String t) { return new ArrayList<>(names.get(t)); }
                static void reset(X x) { for (Edge e : x.copyMap("a")) e.labeled = true; }
            }
            """;

    @DisplayName("a copy of a map value's mutable elements is dependent")
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
        assertEquals("@Dependent", independent(x, "copyField"));
        assertEquals("@Dependent", independent(x, "copyMap"));
        assertEquals("@Dependent", independent(x, "firstOfMap"));
        assertEquals("@Independent", independent(x, "copyNames"));
        MethodInfo reset = find(x, "reset");
        assertEquals("false/true", reset.parameters().getFirst().isUnmodified() + "/"
                                   + reset.parameters().getFirst().isStructurallyUnmodified());
    }

    private static MethodInfo find(TypeInfo typeInfo, String name) {
        return typeInfo.methods().stream().filter(m -> m.name().equals(name)).findFirst().orElseThrow();
    }

    private static String independent(TypeInfo typeInfo, String name) {
        return "" + find(typeInfo, name).analysis().getOrNull(INDEPENDENT_METHOD, ValueImpl.IndependentImpl.class);
    }
}
