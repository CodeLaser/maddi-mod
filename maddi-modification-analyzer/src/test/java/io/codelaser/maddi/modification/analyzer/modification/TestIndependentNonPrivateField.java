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

import io.codelaser.maddi.cst.api.analysis.Property;
import io.codelaser.maddi.cst.api.analysis.Value;
import io.codelaser.maddi.cst.api.info.Info;
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
 * A non-private field is modified from outside without any method handing it out (road 080: an external
 * modification is one "on a field, directly accessed from the object"): a type with a non-private field of a
 * dependent type, static or not, is @Dependent. A setter, fluent or void, is @Independent by definition.
 */
public class TestIndependentNonPrivateField extends CommonTest {

    @Language("java")
    private static final String INPUT = """
            package a.b;
            import java.util.List;
            public class X {
                static class AddNewClass {
                    int i;
                    public List<String> list;
                    public AddNewClass setI(int i) {
                        this.i = i;
                        return this;
                    }
                    public int getI() { return i; }
                }
                static class Shared {
                    public static List<String> shared;
                    int count;
                }
                static class Named {
                    public String name;
                    int count;
                    void setCount(int count) { this.count = count; }
                }
            }
            """;

    private static TypeInfo nested(TypeInfo x, String name) {
        return x.subTypes().stream().filter(t -> name.equals(t.simpleName())).findFirst().orElseThrow();
    }

    private static Value.Independent independent(Info info, Property property) {
        return info.analysis().getOrNull(property, ValueImpl.IndependentImpl.class);
    }

    @DisplayName("a public List makes its type @Dependent; a public String does not; setters are @Independent")
    @Test
    public void test() {
        boolean before = Culprits.ENABLED;
        Culprits.ENABLED = true;
        try {
            TypeInfo x = javaInspector.parse("a.b.X", INPUT);
            List<Info> ao = prepAnalyzer.doPrimaryTypes(Set.of(x));
            new IteratingAnalyzerImpl(javaInspector, new IteratingAnalyzerImpl.ConfigurationBuilder()
                    .setMaxIterations(10)
                    .setModificationViaReachability(true)
                    .build()).analyze(ao);

            TypeInfo add = nested(x, "AddNewClass");
            assertEquals(ValueImpl.IndependentImpl.DEPENDENT, independent(add, PropertyImpl.INDEPENDENT_TYPE));
            assertEquals(ValueImpl.IndependentImpl.DEPENDENT,
                    independent(add.getFieldByName("list", true), PropertyImpl.INDEPENDENT_FIELD));
            assertEquals(ValueImpl.IndependentImpl.INDEPENDENT,
                    independent(add.getFieldByName("i", true), PropertyImpl.INDEPENDENT_FIELD));
            // fluent: it hands back the object the caller already holds
            assertEquals(ValueImpl.IndependentImpl.INDEPENDENT,
                    independent(add.findUniqueMethod("setI", 1), PropertyImpl.INDEPENDENT_METHOD));
            var culprit = add.analysis().getOrNull(PropertyImpl.DEPENDENT_CULPRIT, ValueImpl.CulpritImpl.class);
            assertNotNull(culprit);
            assertEquals("6 field list is not private: the outside world can modify its List<String> directly",
                    culprit.steps().stream().map(st -> st.line() + " " + st.text())
                            .collect(Collectors.joining(" <- ")));

            // static: modified from outside just the same
            TypeInfo shared = nested(x, "Shared");
            assertEquals(ValueImpl.IndependentImpl.DEPENDENT, independent(shared, PropertyImpl.INDEPENDENT_TYPE));

            TypeInfo named = nested(x, "Named");
            assertEquals(ValueImpl.IndependentImpl.INDEPENDENT, independent(named, PropertyImpl.INDEPENDENT_TYPE));
            // a void setter
            assertEquals(ValueImpl.IndependentImpl.INDEPENDENT,
                    independent(named.findUniqueMethod("setCount", 1), PropertyImpl.INDEPENDENT_METHOD));
        } finally {
            Culprits.ENABLED = before;
        }
    }
}
