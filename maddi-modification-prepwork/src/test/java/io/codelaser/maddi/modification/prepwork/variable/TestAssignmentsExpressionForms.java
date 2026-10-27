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

/** Expression forms that are classic index/merge bug sources: ternary, chained assignment, side-effecting array indices. */
public class TestAssignmentsExpressionForms extends CommonTest {

    private VariableData analyse(String input, int params) {
        TypeInfo X = javaInspector.parse(ABX, input);
        MethodInfo m = X.findUniqueMethod("m", params);
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

    @Language("java") private static final String TERNARY =
            "package a.b; class X { static int m(boolean c, int a, int b) { int r = c ? a : b; return r; } }";

    @DisplayName("ternary: condition and both arms are read unconditionally")
    @Test
    public void testTernary() {
        VariableData vd = analyse(TERNARY, 3);
        assertEquals("0", v(vd, "c").reads().toString());
        assertEquals("0", v(vd, "a").reads().toString());
        assertEquals("0", v(vd, "b").reads().toString());
        assertEquals("D:0, A:[0]", v(vd, "r").assignments().toString());
        assertEquals("D:-, A:[1]", rv(vd).assignments().toString());
    }

    @Language("java") private static final String NESTED_TERNARY =
            "package a.b; class X { static int m(int x) { return x > 0 ? 1 : x < 0 ? -1 : 0; } }";

    @DisplayName("nested ternary in a return")
    @Test
    public void testNestedTernary() {
        VariableData vd = analyse(NESTED_TERNARY, 1);
        assertEquals("0", v(vd, "x").reads().toString());
        assertEquals("D:-, A:[0]", rv(vd).assignments().toString());
    }

    @Language("java") private static final String CHAINED =
            "package a.b; class X { static int m() { int a, b; a = b = 5; return a + b; } }";

    @DisplayName("chained assignment a = b = 5: both assigned at the same statement")
    @Test
    public void testChained() {
        VariableData vd = analyse(CHAINED, 0);
        assertEquals("D:0, A:[1]", v(vd, "a").assignments().toString());
        assertEquals("D:0, A:[1]", v(vd, "b").assignments().toString());
        assertEquals("2", v(vd, "a").reads().toString());
        assertEquals("2", v(vd, "b").reads().toString());
    }

    @Language("java") private static final String ARRAY_SIDE_EFFECT =
            "package a.b; class X { static void m(int[] a, int[] b) { int i = 0, j = 0; a[i++] = b[j++]; } }";

    @DisplayName("side-effecting array indices a[i++] = b[j++]")
    @Test
    public void testArraySideEffectIndices() {
        VariableData vd = analyse(ARRAY_SIDE_EFFECT, 2);
        // i and j are both read and (re)assigned by the ++ at statement 1
        assertEquals("1", v(vd, "i").reads().toString());
        assertEquals("D:0, A:[0, 1]", v(vd, "i").assignments().toString());
        assertEquals("1", v(vd, "j").reads().toString());
        assertEquals("D:0, A:[0, 1]", v(vd, "j").assignments().toString());
        assertEquals("1", v(vd, "a").reads().toString());
        assertEquals("1", v(vd, "b").reads().toString());
    }
}
