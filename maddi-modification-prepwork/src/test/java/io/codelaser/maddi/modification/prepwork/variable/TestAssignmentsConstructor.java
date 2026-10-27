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

/** Constructor bodies: this(...)/super(...) argument reads and field assignments — none previously covered. */
public class TestAssignmentsConstructor extends CommonTest {

    private VariableData analyse(MethodInfo constructor) {
        new PrepAnalyzer(runtime).doMethod(constructor);
        return VariableDataImpl.of(constructor);
    }

    private static VariableInfo v(VariableData vd, String simpleName) {
        return vd.variableInfoStream().filter(x -> x.variable().simpleName().equals(simpleName))
                .findFirst().orElseThrow();
    }

    @Language("java") private static final String THIS_DELEGATION =
            "package a.b; class X { X(int a) { this(a, 0); } X(int a, int b) { } }";

    @DisplayName("this(...) delegation reads the forwarded argument")
    @Test
    public void testThisDelegation() {
        TypeInfo X = javaInspector.parse(ABX, THIS_DELEGATION);
        VariableData vd = analyse(X.findConstructor(1));
        assertEquals("0", v(vd, "a").reads().toString());
    }

    @Language("java") private static final String FIELD_ASSIGNS =
            "package a.b; class X { int a, b; X(int x, int y) { a = x; b = y; } }";

    @DisplayName("field assignments in a constructor (this.a = x, this.b = y)")
    @Test
    public void testFieldAssignments() {
        TypeInfo X = javaInspector.parse(ABX, FIELD_ASSIGNS);
        VariableData vd = analyse(X.findConstructor(2));
        assertEquals("D:-, A:[0]", v(vd, "a").assignments().toString()); // field a
        assertEquals("D:-, A:[1]", v(vd, "b").assignments().toString()); // field b
        assertEquals("0", v(vd, "x").reads().toString());
        assertEquals("1", v(vd, "y").reads().toString());
        assertEquals("0, 1", v(vd, "this").reads().toString()); // implicit this on each field assignment
    }

    @Language("java") private static final String SUPER_CALL =
            "package a.b; class X { static class P { P(int x) {} } static class C extends P { C(int a) { super(a); } } }";

    @DisplayName("super(...) reads the forwarded argument")
    @Test
    public void testSuperCall() {
        TypeInfo X = javaInspector.parse(ABX, SUPER_CALL);
        VariableData vd = analyse(X.findSubType("C", true).findConstructor(1));
        assertEquals("0", v(vd, "a").reads().toString());
    }
}
