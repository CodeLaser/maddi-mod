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
 * Why a field, parameter or method return is nullable: the nullability pass's chain back to the null, told as the
 * statements that carry it, the seed's origin last. Written only when a run asks for culprits.
 */
public class TestNullableCulprit extends CommonTest {

    @Language("java")
    private static final String INPUT = """
            package a.b;
            public class Maybe {
                private String name;
                String find(int k) {
                    if (k > 0) return null;
                    return "x";
                }
                String viaFind(int k) {
                    String s = find(k);
                    return s;
                }
                void reset() { name = null; }
                String name() { return name; }
                int length(String t) { return t == null ? 0 : t.length(); }
                int callsLength() { return length(null); }
            }
            """;

    private static String culprit(Info info) {
        var c = info.analysis().getOrNull(PropertyImpl.NULLABLE_CULPRIT, ValueImpl.CulpritImpl.class);
        return c == null ? null : c.steps().stream().map(st -> st.line() + " " + st.text())
                .collect(Collectors.joining(" <- "));
    }

    private TypeInfo analyze() {
        TypeInfo maybe = javaInspector.parse("a.b.Maybe", INPUT);
        List<Info> ao = prepAnalyzer.doPrimaryTypes(Set.of(maybe));
        new IteratingAnalyzerImpl(javaInspector, new IteratingAnalyzerImpl.ConfigurationBuilder()
                .setMaxIterations(10)
                .setModificationViaReachability(true)
                .setNullability(true)
                .build()).analyze(ao);
        return maybe;
    }

    @DisplayName("@Nullable: the return of null, through a call and a local; a field assigned null; a null argument")
    @Test
    public void test() {
        boolean before = Culprits.ENABLED;
        Culprits.ENABLED = true;
        try {
            TypeInfo maybe = analyze();
            assertEquals("5 return null; (null in a.b.Maybe.find(int))", culprit(maybe.findUniqueMethod("find", 1)));
            assertEquals("10 return s; <- 5 return null; (null in a.b.Maybe.find(int))",
                    culprit(maybe.findUniqueMethod("viaFind", 1)));
            assertEquals("12 this.name=null; (null in a.b.Maybe.reset())",
                    culprit(maybe.getFieldByName("name", true)));
            assertEquals("14 return t==null?0:t.length(); (compared with null in a.b.Maybe.length(String))",
                    culprit(maybe.findUniqueMethod("length", 1).parameters().getFirst()));
            assertNull(culprit(maybe.findUniqueMethod("callsLength", 0)), "an int is never null");
        } finally {
            Culprits.ENABLED = before;
        }
    }

    @DisplayName("@Nullable: no culprit unless a run asks for it")
    @Test
    public void offByDefault() {
        assertNull(culprit(analyze().findUniqueMethod("find", 1)));
    }
}
