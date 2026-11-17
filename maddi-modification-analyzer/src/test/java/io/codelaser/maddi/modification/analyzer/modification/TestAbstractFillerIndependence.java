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
 * CodeLaser/maddi-mod#135, fernflower's {@code Exprent.getAllExprents()}: a new list handed to an abstract filler,
 * whose overrides add the receiver's own parts, shares the instance's content. The abstract method's links say so
 * ('l.§m ≡ this.§m'), and the caller's result is dependent, as it is when the filler is concrete.
 */
public class TestAbstractFillerIndependence extends CommonTest {

    @Language("java")
    private static final String INPUT = """
            package a.b;
            import java.util.*;
            public class X {
                public abstract static class E {
                    public final List<E> all() { List<E> l = new ArrayList<>(); fill(l); return l; }
                    public abstract List<E> fill(List<E> l);
                    public final E self() { return this; }
                    public final List<E> deep(boolean r) { List<E> l = new ArrayList<>(); deep(r, l); return l; }
                    private List<E> deep(boolean r, List<E> l) {
                        int s = l.size(); fill(l); int e = l.size();
                        if (r) { for (int i = e - 1; i >= s; i--) l.get(i).deep(true, l); }
                        return l;
                    }
                }
                public static class Leaf extends E { public List<E> fill(List<E> l) { return l; } }
                public static class Pair extends E {
                    E left; E right;
                    public List<E> fill(List<E> l) { l.add(left); l.add(right); return l; }
                    public List<E> all2() { List<E> l = new ArrayList<>(); fill(l); return l; }
                }
            }
            """;

    @DisplayName("a list filled by an abstract method's overrides is dependent")
    @Test
    public void test() {
        TypeInfo x = javaInspector.parse("a.b.X", INPUT);
        List<Info> ao = prepWork(x);
        new IteratingAnalyzerImpl(javaInspector, new IteratingAnalyzerImpl.ConfigurationBuilder()
                .setMaxIterations(10).build()).analyze(ao);
        TypeInfo e = x.findSubType("E");
        assertEquals("@Dependent", independent(e, "all"));
        assertEquals("@Dependent", independent(x.findSubType("Pair"), "all2"));
        // CodeLaser/maddi-mod#127: the recursive collector calls the abstract filler, a callee without a body, which
        // keeps its summary inside a recursive method (fernflower's Exprent.getAllExprents(boolean, List))
        assertEquals("@Dependent", independent(e, "deep"));
        assertEquals("@Independent", independent(x.findSubType("Leaf"), "fill"));
    }

    private static String independent(TypeInfo typeInfo, String name) {
        MethodInfo mi = typeInfo.methods().stream().filter(m -> m.name().equals(name)).findFirst().orElseThrow();
        return "" + mi.analysis().getOrNull(INDEPENDENT_METHOD, ValueImpl.IndependentImpl.class);
    }
}
