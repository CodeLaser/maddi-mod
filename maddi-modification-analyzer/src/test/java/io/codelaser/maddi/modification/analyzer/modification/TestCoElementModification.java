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
 * CodeLaser/maddi-mod#113 (shape 2, fernflower's ClearStructHelper.clearStatements), the local half of #26: an object
 * put into a local collection and modified after it comes out again is modified. The engine knows only that both are
 * elements of the same hidden content ('0:root ∈ l.§$s', 's ∈ l.§$s'). Since #130 the verdict follows the element's:
 * clearTemp() writes the element's own field, so root, which may be that element, is structurally modified too.
 */
public class TestCoElementModification extends CommonTest {

    @Language("java")
    private static final String INPUT = """
            package a.b;
            import java.util.*;
            public class X {
                public static class St {
                    int mark;
                    private final List<St> stats = new ArrayList<>();
                    public List<St> getStats() { return stats; }
                    public void clearTemp() { mark = 0; }
                    public int read() { return mark; }
                    public List<St> all() { return new ArrayList<>(stats); }
                }
                static void viaList(St root) {
                    List<St> l = new ArrayList<>();
                    l.add(root);
                    St s = l.get(0);
                    s.clearTemp();
                }
                static void viaStack(St root) {
                    LinkedList<St> stack = new LinkedList<>();
                    stack.add(root);
                    while (!stack.isEmpty()) {
                        St s = stack.removeFirst();
                        s.clearTemp();
                        stack.addAll(s.getStats());
                    }
                }
                static void addedAfter(St root) {
                    List<St> l = new ArrayList<>();
                    l.add(new St());
                    St s = l.get(0);
                    s.clearTemp();
                    l.add(root);
                }
                static int readOnly(St root) {
                    List<St> l = new ArrayList<>();
                    l.add(root);
                    St s = l.get(0);
                    return s.read();
                }
                // fernflower's setExprentVersionsToNull: the parts collected, the parameter added, every one written
                static void collected(St root) {
                    List<St> lst = root.all();
                    lst.add(root);
                    for (St s : lst) s.clearTemp();
                }
                static void otherList(St root) {
                    List<St> l = new ArrayList<>();
                    List<St> k = new ArrayList<>();
                    l.add(root);
                    k.add(new St());
                    St s = k.get(0);
                    s.clearTemp();
                }
            }
            """;

    @DisplayName("an element modified after it comes out of a local collection modifies what went in")
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
        // #130: clearTemp() writes the element's own field, and the element may be root: structural too
        assertEquals("false/false", p(x, "viaList"));
        assertEquals("false/false", p(x, "viaStack"));
        assertEquals("true/true", p(x, "addedAfter"));
        assertEquals("true/true", p(x, "readOnly"));
        assertEquals("true/true", p(x, "otherList"));
        assertEquals("false/false", p(x, "collected"));
    }

    private static String p(TypeInfo typeInfo, String name) {
        MethodInfo mi = typeInfo.methods().stream().filter(m -> m.name().equals(name)).findFirst().orElseThrow();
        return mi.parameters().getFirst().isUnmodified() + "/" + mi.parameters().getFirst().isStructurallyUnmodified();
    }
}
