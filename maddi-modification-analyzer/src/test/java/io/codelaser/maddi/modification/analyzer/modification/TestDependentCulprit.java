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
 * Why a method or parameter is dependent: the statement linking the return value or the parameter to a mutable field
 * of the instance, then the field. Written only when a run asks for culprits.
 */
public class TestDependentCulprit extends CommonTest {

    @Language("java")
    private static final String INPUT = """
            package a.b;
            import java.util.ArrayList;
            import java.util.List;
            public class Holder {
                private final List<String> items = new ArrayList<>();
                private List<String> other;
                List<String> items() {
                    if (items.isEmpty()) return null;
                    return items;
                }
                List<String> viaHelper() { return items(); }
                void keep(List<String> list, int n) {
                    if (n > list.size()) return;
                    this.other = list;
                }
                int size() { return items.size(); }
            }
            """;

    private static String culprit(Info info) {
        var c = info.analysis().getOrNull(PropertyImpl.DEPENDENT_CULPRIT, ValueImpl.CulpritImpl.class);
        return c == null ? null : c.steps().stream().map(st -> st.line() + " " + st.text())
                .collect(Collectors.joining(" <- "));
    }

    private TypeInfo analyze() {
        TypeInfo holder = javaInspector.parse("a.b.Holder", INPUT);
        List<Info> ao = prepAnalyzer.doPrimaryTypes(Set.of(holder));
        new IteratingAnalyzerImpl(javaInspector, new IteratingAnalyzerImpl.ConfigurationBuilder()
                .setMaxIterations(10)
                .setModificationViaReachability(true)
                .build()).analyze(ao);
        return holder;
    }

    @DisplayName("@Dependent: the return of the field, through a getter; the parameter stored in a field (not a setter: no verdict there)")
    @Test
    public void test() {
        boolean before = Culprits.ENABLED;
        Culprits.ENABLED = true;
        try {
            TypeInfo holder = analyze();
            MethodInfo items = holder.findUniqueMethod("items", 0);
            assertTrue(items.analysis().getOrDefault(PropertyImpl.INDEPENDENT_METHOD,
                    ValueImpl.IndependentImpl.INDEPENDENT).isDependent());
            assertEquals("9 returns this.items <- 5 field items is mutable (List<String>)", culprit(items));
            assertEquals("11 returns items() <- 9 returns this.items <- 5 field items is mutable (List<String>)",
                    culprit(holder.findUniqueMethod("viaHelper", 0)));
            MethodInfo keep = holder.findUniqueMethod("keep", 2);
            assertEquals("14 assigns other = list <- 6 field other is mutable (List<String>)",
                    culprit(keep.parameters().getFirst()));
            assertNull(culprit(holder.findUniqueMethod("size", 0)), "independent, no culprit");
        } finally {
            Culprits.ENABLED = before;
        }
    }

    @DisplayName("@Dependent: no culprit unless a run asks for it")
    @Test
    public void offByDefault() {
        assertNull(culprit(analyze().findUniqueMethod("items", 0)));
    }
}
