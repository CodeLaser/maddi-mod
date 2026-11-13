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
 * CodeLaser/maddi-mod#117, fernflower's SequenceHelper.mergeFlatStatements: a Statement holds its children in
 * 'stats' and the first of them, again, in the accessible field 'first'. Modifying an element of 'stats' may modify
 * the object 'first' points to, so it is a structural modification of the statement: an element of hidden content
 * whose type is compatible with an accessible field's type may be that field's object.
 */
public class TestHiddenAliasesAccessible extends CommonTest {

    @Language("java")
    private static final String INPUT = """
            package a.b;
            import java.util.*;
            public class X {
                public static class St {
                    protected final List<St> stats = new ArrayList<>();
                    protected St first;
                    protected final List<String> exprents = new ArrayList<>();
                    public List<St> getStats() { return stats; }
                    public List<String> getExprents() { return exprents; }
                }
                public static class Item { int count; void inc() { count++; } }
                public static class Box {
                    private final List<Item> items = new ArrayList<>();
                    private String name;
                    public List<Item> getItems() { return items; }
                }
                static void merge(St sequence) {
                    for (int i = sequence.getStats().size() - 1; i >= 0; i--) {
                        St current = sequence.getStats().get(i);
                        current.getExprents().clear();
                    }
                }
                static void incAll(Box box) {
                    for (Item item : box.getItems()) item.inc();
                }
            }
            """;

    @DisplayName("an element of hidden content that may alias an accessible field is modified structurally")
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
        assertEquals("false/false", p(x, "merge"));
        // control: Box has no accessible field an Item could be
        assertEquals("false/true", p(x, "incAll"));
    }

    private static String p(TypeInfo typeInfo, String name) {
        MethodInfo mi = typeInfo.methods().stream().filter(m -> m.name().equals(name)).findFirst().orElseThrow();
        return mi.parameters().getFirst().isUnmodified() + "/" + mi.parameters().getFirst().isStructurallyUnmodified();
    }
}
