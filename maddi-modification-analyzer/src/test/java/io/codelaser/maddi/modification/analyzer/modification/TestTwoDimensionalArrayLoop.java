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
import io.codelaser.maddi.cst.impl.analysis.PropertyImpl;
import io.codelaser.maddi.cst.impl.analysis.ValueImpl;
import io.codelaser.maddi.modification.analyzer.CommonTest;
import io.codelaser.maddi.modification.analyzer.impl.IteratingAnalyzerImpl;
import io.codelaser.maddi.modification.link.impl.MethodLinkedVariablesImpl;

/**
 * CodeLaser/maddi-mod#148: a for-each over an element of a two-dimensional array field, at a loop index
 * ({@code for (ent : this.elements[i])}), lost the loop variable's link to {@code this}: the returned list of the
 * field's own sets read independent. The one-dimensional loop is the control. fernflower's
 * {@code SFormsFastMapDirect.entryList()} has this shape.
 */
public class TestTwoDimensionalArrayLoop extends CommonTest {

    @Language("java")
    private static final String INPUT = """
            package a.b;
            import java.util.ArrayList;
            import java.util.List;
            class X {
                static class FSet { final int[] data = new int[4]; }
                private final FSet[][] elements = new FSet[3][];
                private final FSet[] flat = new FSet[3];
                public List<FSet> twoDimensional() {
                    List<FSet> list = new ArrayList<>();
                    for (int i = 2; i >= 0; i--) {
                        for (final FSet ent : elements[i]) {
                            if (ent != null) list.add(ent);
                        }
                    }
                    return list;
                }
                public List<FSet> oneDimensional() {
                    List<FSet> list = new ArrayList<>();
                    for (final FSet ent : flat) {
                        if (ent != null) list.add(ent);
                    }
                    return list;
                }
            }
            """;

    @DisplayName("the elements of this.elements[i] are this's content, as those of this.flat are")
    @Test
    public void test() {
        TypeInfo X = javaInspector.parse("a.b.X", INPUT);
        List<Info> ao = prepWork(X);
        new IteratingAnalyzerImpl(javaInspector, new IteratingAnalyzerImpl.ConfigurationBuilder()
                .setMaxIterations(10).build()).analyze(ao);
        MethodInfo one = X.methods().stream().filter(x -> x.name().equals("oneDimensional")).findFirst().orElseThrow();
        MethodInfo two = X.methods().stream().filter(x -> x.name().equals("twoDimensional")).findFirst().orElseThrow();
        assertEquals("[] --> oneDimensional.§$s∩this.flat.§ts", links(one), "the control");
        assertEquals("@Dependent", independent(one), "the control");
        // the row's face, lifted past the loop index to the array's own (LinkComputerImpl.liftHiddenContentFace)
        assertEquals("[] --> twoDimensional.§$s∩this.elements.§$ss", links(two));
        assertEquals("@Dependent", independent(two));
    }

    private static String links(MethodInfo m) {
        return String.valueOf(m.analysis().getOrNull(MethodLinkedVariablesImpl.METHOD_LINKS,
                MethodLinkedVariablesImpl.class));
    }

    private static String independent(MethodInfo m) {
        return String.valueOf(m.analysis().getOrNull(PropertyImpl.INDEPENDENT_METHOD, ValueImpl.IndependentImpl.class));
    }
}
