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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * New-style (arrow) switch completeness must be exhaustiveness-aware. A variable assigned in every arm is
 * definitely assigned after the switch (gets the {@code =M} merge marker) only if the switch is exhaustive:
 * it has an explicit {@code default}, or it is a pattern switch (which only compiles when exhaustive). A classic
 * constant switch (int/String/enum labels) without {@code default} is not exhaustive, so the variable is NOT
 * definitely assigned afterwards. See {@code Assignments.assignmentsRequiredForMerge}.
 */
public class TestSwitchNoDefaultMerge extends CommonTest {

    private VariableData analyse(@Language("java") String input) {
        TypeInfo X = javaInspector.parse(ABX, input);
        MethodInfo m = X.findUniqueMethod("m", 1);
        new PrepAnalyzer(runtime).doMethod(m);
        return VariableDataImpl.of(m);
    }

    private static VariableInfo local(VariableData vd, String simpleName) {
        return vd.variableInfoStream().filter(x -> x.variable().simpleName().equals(simpleName))
                .findFirst().orElseThrow();
    }

    // NOTE: r is pre-initialised so that the source compiles under the (javac-based) openjdk parser too;
    // a non-exhaustive switch could otherwise leave r unassigned at 'return r'. The point of the test is the
    // ABSENCE of the switch-level '1=M' merge marker: the case assignments do not "definitely assign" r.
    @Language("java") private static final String NO_DEFAULT = """
            package a.b;
            class X {
                static int m(int x) {
                    int r = 0;
                    switch (x) {
                        case 1 -> r = 10;
                        case 2 -> r = 20;
                    }
                    return r;
                }
            }""";

    @DisplayName("classic switch WITHOUT default: case assignments do not produce a switch-level =M merge")
    @Test
    public void testNoDefault() {
        VariableData vd = analyse(NO_DEFAULT);
        VariableInfo r = local(vd, "r");
        assertEquals("D:0, A:[0, 1.0.0, 1.1.0]", r.assignments().toString());
    }

    @Language("java") private static final String WITH_DEFAULT = """
            package a.b;
            class X {
                static int m(int x) {
                    int r;
                    switch (x) {
                        case 1 -> r = 10;
                        case 2 -> r = 20;
                        default -> r = 0;
                    }
                    return r;
                }
            }""";

    @DisplayName("classic switch WITH default: r IS definitely assigned")
    @Test
    public void testWithDefault() {
        VariableData vd = analyse(WITH_DEFAULT);
        VariableInfo r = local(vd, "r");
        assertEquals("D:0, A:[1.0.0, 1.1.0, 1.2.0, 1=M]", r.assignments().toString());
        assertTrue(r.hasBeenDefined("2"));
    }

    @Language("java") private static final String EXHAUSTIVE_SEALED = """
            package a.b;
            class X {
                sealed interface S permits A, B {}
                record A() implements S {}
                record B() implements S {}
                static int m(S s) {
                    int r;
                    switch (s) {
                        case A a -> r = 1;
                        case B b -> r = 2;
                    }
                    return r;
                }
            }""";

    @DisplayName("exhaustive pattern switch without explicit default: r IS definitely assigned")
    @Test
    public void testExhaustiveSealed() {
        VariableData vd = analyse(EXHAUSTIVE_SEALED);
        VariableInfo r = local(vd, "r");
        assertEquals("D:0, A:[1.0.0, 1.1.0, 1=M]", r.assignments().toString());
        assertTrue(r.hasBeenDefined("2"));
    }
}
