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

/** synchronized (lock evaluated, body merged) and assert (condition and message both read). */
public class TestAssignmentsSyncAssert extends CommonTest {

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

    @Language("java") private static final String SYNCHRONIZED =
            "package a.b; class X { static void m(Object lock, int[] a) { synchronized (lock) { a[0] = 1; } } }";

    @DisplayName("synchronized: lock read at eval, body assignment merged")
    @Test
    public void testSynchronized() {
        VariableData vd = analyse(SYNCHRONIZED, 2);
        assertEquals("0-E", v(vd, "lock").reads().toString());
        assertEquals("0.0.0", v(vd, "a").reads().toString());
        assertEquals("D:-, A:[0.0.0, 0=M]", v(vd, "a[0]").assignments().toString());
    }

    @Language("java") private static final String ASSERT =
            "package a.b; class X { static void m(int x) { assert x > 0 : \"msg \" + x; } }";

    @DisplayName("assert: both the condition and the message read the variable")
    @Test
    public void testAssert() {
        VariableData vd = analyse(ASSERT, 1);
        assertEquals("0", v(vd, "x").reads().toString());
    }
}
