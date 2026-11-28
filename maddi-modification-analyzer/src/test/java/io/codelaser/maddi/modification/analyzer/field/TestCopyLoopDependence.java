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

package io.codelaser.maddi.modification.analyzer.field;

import static io.codelaser.maddi.cst.impl.analysis.PropertyImpl.INDEPENDENT_METHOD;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.intellij.lang.annotations.Language;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.codelaser.maddi.cst.api.info.Info;
import io.codelaser.maddi.cst.api.info.MethodInfo;
import io.codelaser.maddi.cst.api.info.TypeInfo;
import io.codelaser.maddi.cst.impl.analysis.ValueImpl;
import io.codelaser.maddi.modification.analyzer.CommonTest;
import io.codelaser.maddi.modification.analyzer.impl.IteratingAnalyzerImpl;

/**
 * A copy of an element of the instance's array field, which shares the element's factory: {@code first}'s summary
 * says {@code first.factory ← this.elements[0].factory}, and LinkToField did not count a field of an array element of
 * the instance as the instance's (gate NOARRAYELEMENTINDEP). fernflower's {@code SFormsFastMapDirect.getCopy}, whose
 * sets share their factory with the receiver's (the dynamic check's 2c).
 * <p>
 * Still independent, and asserted so: {@code M1.copy}, a loop filling a new array ({@code copy.elements ∩
 * this.elements}, an overlap between variables, unjudged on purpose), and {@code M2.copy}, the same over an array of
 * arrays (fernflower's shape), whose summary is empty (CodeLaser/maddi-mod#170).
 */
public class TestCopyLoopDependence extends CommonTest {

    @Language("java")
    private static final String INPUT = """
            package a.b;
            public class X {
                static class F { int n; void inc() { n++; } }
                static class S {
                    final F factory; int[] data;
                    S(F f, int[] d) { factory = f; data = d; }
                    S copy() { return new S(factory, data.clone()); }
                }
                static class M1 {
                    S[] elements = new S[3];
                    M1 copy() {
                        M1 m = new M1();
                        for (int p = 0; p < elements.length; p++) { S s = elements[p]; if (s != null) m.elements[p] = s.copy(); }
                        return m;
                    }
                    S first() { return elements[0].copy(); }
                }
                static class M2 {
                    S[][] elements = new S[3][];
                    M2 copy() {
                        M2 m = new M2();
                        for (int i = 2; i >= 0; i--) {
                            S[] arr = elements[i];
                            S[] arrnew = new S[arr.length];
                            m.elements[i] = arrnew;
                            for (int p = 0; p < arr.length; p++) { S s = arr[p]; if (s != null) arrnew[p] = s.copy(); }
                        }
                        return m;
                    }
                }
            }
            """;

    @DisplayName("a copy holding the elements' dependent copies is dependent")
    @Test
    public void test() {
        TypeInfo x = javaInspector.parse("a.b.X", INPUT);
        List<Info> ao = prepWork(x);
        new IteratingAnalyzerImpl(javaInspector, new IteratingAnalyzerImpl.ConfigurationBuilder()
                .setMaxIterations(10).build()).analyze(ao);
        assertEquals("S.copy=@Dependent M1.first=@Dependent M1.copy=@Independent M2.copy=@Independent",
                "S.copy=" + ind(x.findSubType("S").findUniqueMethod("copy", 0))
                + " M1.first=" + ind(x.findSubType("M1").findUniqueMethod("first", 0))
                + " M1.copy=" + ind(x.findSubType("M1").findUniqueMethod("copy", 0))
                + " M2.copy=" + ind(x.findSubType("M2").findUniqueMethod("copy", 0)));
    }

    private static Object ind(MethodInfo m) {
        return m.analysis().getOrNull(INDEPENDENT_METHOD, ValueImpl.IndependentImpl.class);
    }
}
