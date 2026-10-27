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
 * Assignment used as a side effect inside a condition: the assignment index falls on the condition's evaluation
 * stage ({@code -E}/{@code ;E} for a while, {@code -E} for an if), not on a statement of its own.
 */
public class TestAssignmentsInConditions extends CommonTest {

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

    @Language("java") private static final String WHILE_COND = """
            package a.b;
            class X {
                static int m(int[] arr) {
                    int i = 0;
                    int sum = 0;
                    int v;
                    while ((v = arr[i]) > 0) {
                        sum += v;
                        i++;
                    }
                    return sum;
                }
            }""";

    @DisplayName("assignment in a while condition (v = arr[i]) evaluated at -E and ;E")
    @Test
    public void testWhileCondition() {
        VariableData vd = analyse(WHILE_COND);
        VariableInfo varV = v(vd, "v");
        assertEquals("D:2, A:[3-E, 3;E]", varV.assignments().toString()); // assigned in both condition evaluations
        assertEquals("3.0.0", varV.reads().toString());

        assertEquals("D:0, A:[0, 3.0.1]", v(vd, "i").assignments().toString());
        assertEquals("3-E, 3.0.1, 3;E", v(vd, "i").reads().toString());
        assertEquals("D:1, A:[1, 3.0.0]", v(vd, "sum").assignments().toString());
    }

    @Language("java") private static final String IF_COND = """
            package a.b;
            class X {
                static int m(int x) {
                    int y;
                    if ((y = x * 2) > 0) {
                        return y;
                    }
                    return 0;
                }
            }""";

    @DisplayName("assignment in an if condition (y = x*2) evaluated at -E")
    @Test
    public void testIfCondition() {
        VariableData vd = analyse(IF_COND);
        VariableInfo y = v(vd, "y");
        assertEquals("D:0, A:[1-E]", y.assignments().toString()); // assigned in the if condition
        assertEquals("1.0.0", y.reads().toString());              // read in the then-block
        assertEquals("1-E", v(vd, "x").reads().toString());
    }
}
