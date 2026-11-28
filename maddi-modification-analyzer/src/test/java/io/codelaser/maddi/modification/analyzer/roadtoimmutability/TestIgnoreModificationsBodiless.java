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

package io.codelaser.maddi.modification.analyzer.roadtoimmutability;

import org.intellij.lang.annotations.Language;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Map;

/**
 * CodeLaser/maddi-mod#161: an explicit {@code @IgnoreModifications} on a parameter of a bodiless source method must
 * not make that parameter {@code @Modified}, nor cost the interface its {@code @Container}.
 */
public class TestIgnoreModificationsBodiless extends BookTest {

    @Language("java")
    private static final String INPUT = """
            package a.b;
            import io.codelaser.maddi.annotation.rare.IgnoreModifications;
            import java.util.function.Consumer;
            public class X {
                interface Abstract<T>         { void visit(Consumer<T> consumer); }
                interface AbstractExplicit<T> { void visit(@IgnoreModifications Consumer<T> consumer); }

                static class Explicit<T> {
                    private final T t;
                    Explicit(T t) { this.t = t; }
                    public void visit(@IgnoreModifications Consumer<T> consumer) { consumer.accept(t); }
                }

                // the same declaration with an implementation, which inherits the disclaimer
                interface Implemented<T> { void visit(@IgnoreModifications Consumer<T> consumer); }
                static class Implementation<T> implements Implemented<T> {
                    private final T t;
                    Implementation(T t) { this.t = t; }
                    @Override public void visit(Consumer<T> consumer) { consumer.accept(t); }
                }
            }
            """;

    @Test
    public void test() throws IOException {
        Map<String, String> actual = verdicts("a.b.X", INPUT);
        assertVerdicts(actual,
                "Explicit.visit(consumer) = @NotModified",
                "Abstract.visit(consumer) = @NotModified",
                "Abstract = @Container",
                "AbstractExplicit.visit(consumer) = @NotModified",
                "AbstractExplicit = @Container",
                "Implementation.visit(consumer) = @NotModified",
                "Implemented.visit(consumer) = @NotModified",
                "Implemented = @Container");
    }
}
