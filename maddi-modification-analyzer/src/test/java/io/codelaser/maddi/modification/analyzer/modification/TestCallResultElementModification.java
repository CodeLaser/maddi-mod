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
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * CodeLaser/maddi-mod#96, fernflower's NestedClassProcessor.setLambdaVars reduced: an object reached as a call result
 * through a collection element ({@code parent.getWrapper().getMethods().getWithKey(k)}), modified. It is in the
 * parameter's object graph ({@code ≤}), not a field of it ({@code ≺}); the parameter is modified deeply.
 */
public class TestCallResultElementModification extends CommonTest {

    @Language("java")
    private static final String INPUT = """
            package a.b;
            import java.util.*;
            public class P {
                public static class VarProc {
                    private final Map<Integer, String> names = new HashMap<>();
                    public void setVarName(int i, String n) { names.put(i, n); }
                    public String getVarName(int i) { return names.get(i); }
                }
                public static class MethodWrapper {
                    public final VarProc varproc = new VarProc();
                }
                public static class KeyedList<E> extends ArrayList<E> {
                    private final Map<String, Integer> index = new HashMap<>();
                    public E getWithKey(String k) { Integer i = index.get(k); return i == null ? null : get(i); }
                }
                public static class ClassWrapper {
                    private final KeyedList<MethodWrapper> methods = new KeyedList<>();
                    public KeyedList<MethodWrapper> getMethods() { return methods; }
                }
                public static class Node {
                    public int type;
                    public Node parent;
                    private ClassWrapper wrapper;
                    public ClassWrapper getWrapper() {
                        Node node = this;
                        while (node.type == 1) node = node.parent;
                        return node.wrapper;
                    }
                }
                static void setVars(Node parent, String key) {
                    MethodWrapper method = parent.getWrapper().getMethods().getWithKey(key);
                    method.varproc.setVarName(0, "x");
                }
                static String readVars(Node parent, String key) {
                    MethodWrapper method = parent.getWrapper().getMethods().getWithKey(key);
                    return method.varproc.getVarName(0);
                }
            }
            """;

    @DisplayName("modifying an element reached through a chain of getters and a lookup modifies the parameter")
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
        TypeInfo p = javaInspector.parse("a.b.P", INPUT);
        List<Info> ao = prepWork(p);
        new IteratingAnalyzerImpl(javaInspector, new IteratingAnalyzerImpl.ConfigurationBuilder()
                .setMaxIterations(10).setModificationViaReachability(reach).build()).analyze(ao);
        String verdicts = Stream.of("setVars", "readVars").map(n -> n + "=" + p0(p, n))
                .collect(Collectors.joining(", "));
        assertEquals("setVars=false/true, readVars=true/true", verdicts);
    }

    private static String p0(TypeInfo typeInfo, String name) {
        MethodInfo mi = typeInfo.methods().stream().filter(m -> m.name().equals(name)).findFirst().orElseThrow();
        return mi.parameters().getFirst().isUnmodified() + "/" + mi.parameters().getFirst().isStructurallyUnmodified();
    }
}
