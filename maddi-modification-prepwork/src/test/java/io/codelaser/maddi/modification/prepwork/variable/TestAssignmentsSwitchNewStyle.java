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
 * New-style (arrow) switch statement and pattern switch: assignment in every arm (including default) produces a
 * {@code =M} merge, so the variable is definitely assigned afterwards. Both single-expression arms
 * ({@code case 1 -> r=10;}) and block-bodied arms ({@code case 2 -> { r=20; }}) are indexed consistently as
 * statement 0 of their entry, i.e. {@code 1.0.0} / {@code 1.1.0} / {@code 1.2.0} (regression test for the
 * arrow-arm index fix in ParseStatement).
 */
public class TestAssignmentsSwitchNewStyle extends CommonTest {

    private VariableData analyse(String input) {
        TypeInfo X = javaInspector.parse(ABX, input);
        MethodInfo m = X.findUniqueMethod("m", 1);
        new PrepAnalyzer(runtime).doMethod(m);
        return VariableDataImpl.of(m);
    }

    private static VariableInfo local(VariableData vd, String simpleName) {
        return vd.variableInfoStream().filter(x -> x.variable().simpleName().equals(simpleName))
                .findFirst().orElseThrow();
    }

    @Language("java") private static final String SWITCH = """
            package a.b;
            class X {
                static int m(int x) {
                    int r;
                    switch (x) {
                        case 1 -> r = 10;
                        case 2 -> { r = 20; }
                        default -> r = 0;
                    }
                    return r;
                }
            }""";

    @DisplayName("arrow switch statement, assignment in all arms")
    @Test
    public void testSwitch() {
        VariableData vd = analyse(SWITCH);
        VariableInfo r = local(vd, "r");
        // expression arms 1.0.0 / 1.2.0 and block arm 1.1.0 are now consistently indexed
        assertEquals("D:0, A:[1.0.0, 1.1.0, 1.2.0, 1=M]", r.assignments().toString());
        assertEquals("2", r.reads().toString());
        assertTrue(r.hasBeenDefined("2")); // all arms (incl. default) assign -> defined after

        VariableInfo x = local(vd, "x");
        assertEquals("1-E", x.reads().toString());
    }

    @Language("java") private static final String PATTERN_SWITCH = """
            package a.b;
            class X {
                static String m(Object o) {
                    String s;
                    switch (o) {
                        case Integer i -> s = "int" + i;
                        case String str -> s = str;
                        default -> s = "?";
                    }
                    return s;
                }
            }""";

    @DisplayName("pattern switch statement, assignment in all arms")
    @Test
    public void testPatternSwitch() {
        VariableData vd = analyse(PATTERN_SWITCH);
        VariableInfo s = local(vd, "s");
        assertEquals("D:0, A:[1.0.0, 1.1.0, 1.2.0, 1=M]", s.assignments().toString());
        assertEquals("2", s.reads().toString());
        assertTrue(s.hasBeenDefined("2"));

        VariableInfo o = local(vd, "o");
        assertEquals("1-E", o.reads().toString());
    }
}
