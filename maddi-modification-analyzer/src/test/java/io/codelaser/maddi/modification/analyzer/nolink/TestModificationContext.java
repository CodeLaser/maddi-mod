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

package io.codelaser.maddi.modification.analyzer.nolink;

import io.codelaser.maddi.modification.analyzer.CommonTest;
import io.codelaser.maddi.cst.api.info.Info;
import io.codelaser.maddi.cst.api.info.MethodInfo;
import io.codelaser.maddi.cst.api.info.TypeInfo;
import org.intellij.lang.annotations.Language;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Modification detected in less-obvious contexts: a static field mutated from a static method, an enclosing instance's
 * field mutated from an inner class, and a fluent method that both mutates and returns {@code this}.
 */
public class TestModificationContext extends CommonTest {

    private TypeInfo analyze(String input) {
        TypeInfo X = javaInspector.parse("a.b.X", input);
        List<Info> ao = prepWork(X);
        analyzer.go(ao);
        return X;
    }

    @Language("java") private static final String STATIC_FIELD = """
            package a.b;
            import java.util.ArrayList; import java.util.List;
            class X {
                static class Registry {
                    private static final List<String> ALL = new ArrayList<>();
                    static void register(String s) { ALL.add(s); }
                }
            }
            """;

    @DisplayName("a static field mutated from a static method is detected as modified")
    @Test
    public void staticField() {
        TypeInfo registry = analyze(STATIC_FIELD).findSubType("Registry");
        assertTrue(registry.getFieldByName("ALL", true).isModified());
    }

    @Language("java") private static final String INNER = """
            package a.b;
            class X {
                private int count;
                class Inner { void bump() { count++; } }
                int read() { return count; }
            }
            """;

    @DisplayName("an enclosing instance field mutated from an inner class is detected as modified")
    @Test
    public void innerClass() {
        TypeInfo X = analyze(INNER);
        assertTrue(X.getFieldByName("count", true).isModified());
    }

    @Language("java") private static final String FLUENT = """
            package a.b;
            class X {
                static class Builder {
                    private final StringBuilder sb = new StringBuilder();
                    Builder append(String s) { sb.append(s); return this; }
                    String build() { return sb.toString(); }
                }
            }
            """;

    @DisplayName("a fluent method that mutates a field and returns this is both @Fluent and modifying")
    @Test
    public void fluent() {
        TypeInfo builder = analyze(FLUENT).findSubType("Builder");
        MethodInfo append = builder.findUniqueMethod("append", 1);
        assertTrue(append.isFluent());
        assertTrue(append.isModifying());
        assertTrue(builder.getFieldByName("sb", true).isModified());
    }
}
