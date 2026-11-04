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

package io.codelaser.maddi.modification.analyzer.integration;

import io.codelaser.maddi.cst.api.analysis.Value;
import io.codelaser.maddi.cst.api.info.Info;
import io.codelaser.maddi.cst.api.info.TypeInfo;
import io.codelaser.maddi.cst.impl.analysis.ValueImpl;
import io.codelaser.maddi.modification.analyzer.CommonTest;
import io.codelaser.maddi.modification.analyzer.impl.IteratingAnalyzerImpl;
import org.intellij.lang.annotations.Language;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static io.codelaser.maddi.cst.impl.analysis.PropertyImpl.IMMUTABLE_TYPE;
import static io.codelaser.maddi.cst.impl.analysis.PropertyImpl.INDEPENDENT_TYPE;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * CodeLaser/maddi-mod#81: a parameterized type's hidden content is replaced by its type arguments' immutability only
 * when the hidden content comes from the type parameters alone. An extensible type is hidden content whatever its
 * type arguments are: a field of type {@code Wrap<String>} may hold any implementation of {@code Wrap}. A final
 * class whose only hidden content is its type-parameter fields ({@code Box}) keeps the substitution; one with
 * another hidden-content field ({@code Mixed}'s {@code Object}) does not.
 */
public class TestExtensibleHiddenContent extends CommonTest {

    @Language("java")
    private static final String INPUT = """
            package a.b;
            class X {
                interface Wrap<T> { T get(); }

                static final class Constant<T> implements Wrap<T> {
                    private final T t;
                    Constant(T t) { this.t = t; }
                    @Override public T get() { return t; }
                }

                static final class Holder {
                    private final Wrap<String> w;
                    Holder(Wrap<String> w) { this.w = w; }
                    String value() { return w.get(); }
                }

                static final class Box<T> {
                    private final T t;
                    Box(T t) { this.t = t; }
                    T get() { return t; }
                }

                static final class UsesBox {
                    private final Box<String> b;
                    UsesBox(Box<String> b) { this.b = b; }
                    String value() { return b.get(); }
                }

                static final class Mixed<T> {
                    private final T t;
                    private final Object o;
                    Mixed(T t, Object o) { this.t = t; this.o = o; }
                    T get() { return t; }
                    Object other() { return o; }
                }

                static final class UsesMixed {
                    private final Mixed<String> m;
                    UsesMixed(Mixed<String> m) { this.m = m; }
                    String value() { return m.get(); }
                }
            }
            """;

    @DisplayName("#81: substitution of type arguments only when the hidden content is the type parameters'")
    @Test
    public void test() throws java.io.IOException {
        AnalyzerBundle bundle = buildAnalyzerBundle();
        TypeInfo X = bundle.javaInspector().parse("a.b.X", INPUT);
        List<Info> ao = bundle.prepAnalyzer().doPrimaryType(X);
        new IteratingAnalyzerImpl(bundle.javaInspector(),
                new IteratingAnalyzerImpl.ConfigurationBuilder().setMaxIterations(10).build()).analyze(ao);
        for (String name : List.of("Wrap", "Holder", "Box", "UsesBox", "Mixed", "UsesMixed")) {
            TypeInfo t = X.findSubType(name);
            System.out.println("### " + name + " " + immutable(t) + " " + independent(t));
        }
        assertEquals("@Immutable(hc=true)", immutable(X.findSubType("Wrap")).toString(), "precondition");
        assertEquals("@Immutable(hc=true)", immutable(X.findSubType("Holder")).toString());
        assertEquals("@Immutable(hc=true)", immutable(X.findSubType("Box")).toString(), "precondition");
        assertEquals("@Immutable", immutable(X.findSubType("UsesBox")).toString());
        assertEquals("@Immutable(hc=true)", immutable(X.findSubType("UsesMixed")).toString());
    }

    private static Value.Immutable immutable(TypeInfo t) {
        return t.analysis().getOrNull(IMMUTABLE_TYPE, ValueImpl.ImmutableImpl.class);
    }

    private static Value.Independent independent(TypeInfo t) {
        return t.analysis().getOrNull(INDEPENDENT_TYPE, ValueImpl.IndependentImpl.class);
    }
}
