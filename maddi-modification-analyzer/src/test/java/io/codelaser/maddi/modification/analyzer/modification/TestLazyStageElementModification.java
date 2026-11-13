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
 * CodeLaser/maddi-mod#115, fernflower's GenericMain.getGenericTypeAnnotations: a lambda handed to a lazy stream stage
 * (filter, map, anyMatch) that modifies the element modifies the stream's source, deep only, as #99's forEach does.
 */
public class TestLazyStageElementModification extends CommonTest {

    @Language("java")
    private static final String INPUT = """
            package a.b;
            import java.util.*;
            import java.util.stream.*;
            public class X {
                public static class H {
                    private final Deque<String> paths = new ArrayDeque<>();
                    public Deque<String> getPaths() { return paths; }
                    public int size() { return paths.size(); }
                }
                static List<H> generic(int i, List<H> helpers) {
                    return helpers.stream().filter(h -> {
                        String e = h.getPaths().peek();
                        boolean in = e != null && e.length() == i;
                        if (in) h.getPaths().pop();
                        return in;
                    }).collect(Collectors.toList());
                }
                static long count(List<H> helpers) {
                    return helpers.stream().filter(h -> h.getPaths().pop() != null).count();
                }
                static boolean any(List<H> helpers) {
                    return helpers.stream().anyMatch(h -> h.getPaths().add("x"));
                }
                static List<Integer> map(List<H> helpers) {
                    return helpers.stream().map(h -> { h.getPaths().clear(); return 1; }).toList();
                }
                static List<H> readOnly(List<H> helpers) {
                    return helpers.stream().filter(h -> h.size() > 2).toList();
                }
                interface Info { String links(); }
                interface Container { Info variableInfo(); }
                static List<Info> viaInterface(Map<String, Container> map) {
                    return map.entrySet().stream().filter(e -> e.getKey().startsWith("$"))
                        .map(e -> e.getValue().variableInfo()).toList();
                }
                static List<Integer> mapFresh(List<H> helpers) {
                    return helpers.stream().map(h -> h.size()).toList();
                }
            }
            """;

    @DisplayName("a lazy stream stage's lambda that modifies the element modifies the source")
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
        assertEquals("false/true", p(x, "generic", 1));
        assertEquals("false/true", p(x, "count", 0));
        assertEquals("false/true", p(x, "any", 0));
        assertEquals("false/true", p(x, "map", 0));
        assertEquals("true/true", p(x, "readOnly", 0));
        assertEquals("true/true", p(x, "mapFresh", 0));
        // an abstract method without implementation in the lambda is read as non-modifying by the analyzer (the
        // link-only harness of TestConsumers reads it as modifying)
        assertEquals("true/true", p(x, "viaInterface", 0));
    }

    private static String p(TypeInfo typeInfo, String name, int index) {
        MethodInfo mi = typeInfo.methods().stream().filter(m -> m.name().equals(name)).findFirst().orElseThrow();
        return mi.parameters().get(index).isUnmodified() + "/" + mi.parameters().get(index).isStructurallyUnmodified();
    }
}
