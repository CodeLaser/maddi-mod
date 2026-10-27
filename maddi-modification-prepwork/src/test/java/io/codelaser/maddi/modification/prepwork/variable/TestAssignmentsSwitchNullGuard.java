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
import org.intellij.lang.annotations.Language;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@code case null} and guarded ({@code when}) patterns. Note these use block-bodied or expression arms in an
 * actual switch <em>expression</em>; the block arms index correctly ({@code 0.0.0} ...), in contrast with the
 * malformed expression-arm index bug captured in {@code TestAssignmentsSwitchNewStyle}.
 */
public class TestAssignmentsSwitchNullGuard extends CommonTest {

    private VariableData analyse(String input) {
        TypeInfo X = javaInspector.parse(ABX, input);
        MethodInfo m = X.findUniqueMethod("m", 1);
        new PrepAnalyzer(runtime).doMethod(m);
        return VariableDataImpl.of(m);
    }

    private static VariableInfo v(VariableData vd, String simpleName) {
        return vd.variableInfoStream().filter(x -> x.variable().simpleName().equals(simpleName))
                .findFirst().orElseThrow();
    }

    private static VariableInfo rv(VariableData vd) {
        return vd.variableInfoStream().filter(x -> x.variable() instanceof ReturnVariable).findFirst().orElseThrow();
    }

    @Language("java") private static final String CASE_NULL = """
            package a.b;
            class X {
                static int m(String s) {
                    switch (s) {
                        case null -> { return -1; }
                        case "x" -> { return 1; }
                        default -> { return 0; }
                    }
                }
            }""";

    @DisplayName("switch statement with a case null arm (block-bodied arrows)")
    @Test
    public void testCaseNull() {
        VariableData vd = analyse(CASE_NULL);
        assertEquals("0-E", v(vd, "s").reads().toString());
        // three arms each return; block arms get well-formed indices and a merge
        assertEquals("D:-, A:[0.0.0, 0.1.0, 0.2.0, 0=M]", rv(vd).assignments().toString());
    }

    @Language("java") private static final String GUARDED = """
            package a.b;
            class X {
                static int m(Object o) {
                    return switch (o) {
                        case Integer i when i > 0 -> i;
                        case Integer i -> -i;
                        default -> 0;
                    };
                }
            }""";

    @DisplayName("guarded (when) pattern in a switch expression")
    @Test
    public void testGuardedPattern() {
        VariableData vd = analyse(GUARDED);
        assertEquals("0", v(vd, "o").reads().toString());
        assertEquals("D:-, A:[0]", rv(vd).assignments().toString());
    }
}
