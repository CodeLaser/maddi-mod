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
import io.codelaser.maddi.cst.impl.analysis.PropertyImpl;
import io.codelaser.maddi.cst.impl.analysis.ValueImpl;
import io.codelaser.maddi.modification.analyzer.CommonTest;
import io.codelaser.maddi.modification.analyzer.impl.IteratingAnalyzerImpl;
import io.codelaser.maddi.modification.common.util.Culprits;
import org.intellij.lang.annotations.Language;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Why a method, parameter or field is {@code @Modified}: the reachability pass's cause chain, told as the statements
 * that carry it, the element's own first. Written only when a run asks for culprits.
 */
public class TestModifiedCulprit extends CommonTest {

    @Language("java")
    private static final String INPUT = """
            package a.b;
            import java.util.ArrayList;
            import java.util.List;
            public class Bag {
                private final List<String> items = new ArrayList<>();
                private int count;
                void add(String s) {
                    int n = s.length();
                    store(s);
                }
                private void store(String s) {
                    items.add(s);
                }
                void bump() { count = count + 1; }
                static void fill(Bag bag) { bag.add("x"); }
                int size() { return items.size(); }
            }
            """;

    private static String culprit(Info info) {
        var c = info.analysis().getOrNull(PropertyImpl.MODIFIED_CULPRIT, ValueImpl.CulpritImpl.class);
        return c == null ? null : c.steps().stream().map(st -> st.line() + " " + st.text())
                .collect(Collectors.joining(" <- "));
    }

    private TypeInfo analyze() {
        TypeInfo bag = javaInspector.parse("a.b.Bag", INPUT);
        List<Info> ao = prepAnalyzer.doPrimaryTypes(Set.of(bag));
        new IteratingAnalyzerImpl(javaInspector, new IteratingAnalyzerImpl.ConfigurationBuilder()
                .setMaxIterations(10)
                .setModificationViaReachability(true)
                .build()).analyze(ao);
        return bag;
    }

    @DisplayName("@Modified: the receiver chain into a private helper, to the library call; a field assignment")
    @Test
    public void test() {
        boolean before = Culprits.ENABLED;
        Culprits.ENABLED = true;
        try {
            TypeInfo bag = analyze();
            MethodInfo add = bag.findUniqueMethod("add", 1);
            assertFalse(add.isNonModifying());
            assertEquals("9 calls store(..) <- 12 calls items.add(..), a modifying method outside the analysis",
                    culprit(add));
            assertEquals("14 assigns this.count", culprit(bag.findUniqueMethod("bump", 0)));
            MethodInfo fill = bag.findUniqueMethod("fill", 1);
            assertEquals("15 calls bag.add(..) <- 9 calls store(..) <- 12 calls items.add(..), a modifying method"
                         + " outside the analysis", culprit(fill.parameters().getFirst()));
            assertNull(culprit(bag.findUniqueMethod("size", 0)), "not modified, no culprit");
        } finally {
            Culprits.ENABLED = before;
        }
    }

    @DisplayName("@Modified: no culprit unless a run asks for it")
    @Test
    public void offByDefault() {
        TypeInfo bag = analyze();
        MethodInfo add = bag.findUniqueMethod("add", 1);
        assertFalse(add.isNonModifying());
        assertNull(culprit(add));
    }
}
