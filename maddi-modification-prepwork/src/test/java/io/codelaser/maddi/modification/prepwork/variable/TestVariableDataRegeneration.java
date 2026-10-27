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

package io.codelaser.maddi.modification.prepwork.variable;

import io.codelaser.maddi.modification.prepwork.CommonTest;
import io.codelaser.maddi.modification.prepwork.PrepAnalyzer;
import io.codelaser.maddi.modification.prepwork.variable.impl.VariableDataImpl;
import io.codelaser.maddi.cst.api.info.MethodInfo;
import io.codelaser.maddi.cst.api.info.TypeInfo;
import io.codelaser.maddi.cst.api.statement.Statement;
import org.intellij.lang.annotations.Language;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 0 (GO/NO-GO) for the VariableData flatten-snapshot design
 * ({@code maddi-modification-analyzer/DESIGN-vardata-flatten.md}): the regeneration leg rests on being
 * able to drop a single method's per-statement VariableData mid-run and rebuild it from the body via
 * prepwork. This proves exactly that — clear the VARIABLE_DATA (write-once, cleared with removeIf so a
 * re-set is legal), re-run {@link PrepAnalyzer#doMethod}, and assert the rebuilt per-statement data is
 * identical. If prepwork could not be re-invoked per method, the design's Phase 3 would need rework.
 */
public class TestVariableDataRegeneration extends CommonTest {

    @Language("java")
    private static final String INPUT = """
            package a.b;
            class X {
                static int method(int p) {
                    int a = p + 1;
                    int b = a * 2;
                    int c = b - a;
                    return c;
                }
            }
            """;

    @DisplayName("drop a method's VariableData and regenerate it from the body")
    @Test
    public void regenerate() {
        TypeInfo X = javaInspector.parse(ABX, INPUT);
        MethodInfo method = X.findUniqueMethod("method", 1);
        new PrepAnalyzer(runtime).doMethod(method);

        List<Statement> statements = method.methodBody().statements();
        assertEquals(4, statements.size(), "int a; int b; int c; return c;");

        // capture the accumulative per-statement picture: a={p,a}, b={p,a,b}, c={p,a,b,c}, return={p,a,b,c}
        String methodBefore = VariableDataImpl.of(method).knownVariableNamesToString();
        List<String> before = statements.stream()
                .map(s -> VariableDataImpl.of(s).knownVariableNamesToString()).toList();
        assertTrue(before.get(0).contains("a") && before.get(2).contains("c"), () -> before.toString());

        // DROP: removeIf clears the write-once guard so a later set() is legal again
        method.analysis().removeIf(p -> p == VariableDataImpl.VARIABLE_DATA);
        statements.forEach(s -> s.analysis().removeIf(p -> p == VariableDataImpl.VARIABLE_DATA));
        assertNull(VariableDataImpl.of(method), "method VD cleared");
        statements.forEach(s -> assertNull(VariableDataImpl.of(s), "statement VD cleared"));

        // REGENERATE from the body
        new PrepAnalyzer(runtime).doMethod(method);

        assertEquals(methodBefore, VariableDataImpl.of(method).knownVariableNamesToString(),
                "method-level VD identical after regeneration");
        List<String> after = statements.stream()
                .map(s -> VariableDataImpl.of(s).knownVariableNamesToString()).toList();
        assertEquals(before, after, "per-statement VD identical after regeneration");
    }
}
