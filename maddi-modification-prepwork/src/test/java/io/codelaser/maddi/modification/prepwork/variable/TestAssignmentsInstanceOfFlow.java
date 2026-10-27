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
import io.codelaser.maddi.cst.api.statement.IfElseStatement;
import io.codelaser.maddi.cst.api.statement.Statement;
import org.intellij.lang.annotations.Language;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * instanceof-pattern flow scoping beyond the cases already covered by {@code TestAssignmentsInstanceOf}: the
 * pattern visible in the right-hand side of {@code &&}, nested record deconstruction, and — importantly — the
 * guard-clause idiom whose pattern flows into the rest of the block, which is currently BROKEN (see the bug test).
 */
public class TestAssignmentsInstanceOfFlow extends CommonTest {

    private MethodInfo analyse(String input) {
        TypeInfo X = javaInspector.parse(ABX, input);
        MethodInfo m = X.findUniqueMethod("m", 1);
        new PrepAnalyzer(runtime).doMethod(m);
        return m;
    }

    private static VariableInfo v(VariableData vd, String simpleName) {
        return vd.variableInfoStream().filter(x -> x.variable().simpleName().equals(simpleName))
                .findFirst().orElseThrow();
    }

    private static boolean has(VariableData vd, String simpleName) {
        return vd.variableInfoStream().anyMatch(x -> x.variable().simpleName().equals(simpleName));
    }

    @Language("java") private static final String AND = """
            package a.b;
            class X {
                static boolean m(Object o) {
                    return o instanceof String s && s.isEmpty();
                }
            }""";

    @DisplayName("instanceof pattern usable in the right-hand side of &&")
    @Test
    public void testAnd() {
        MethodInfo m = analyse(AND);
        VariableData vd = VariableDataImpl.of(m.methodBody().lastStatement());
        VariableInfo s = v(vd, "s");
        assertEquals("D:0, A:[0]", s.assignments().toString());
        assertEquals("0", s.reads().toString()); // s.isEmpty()
        assertEquals("0", v(vd, "o").reads().toString());
    }

    @Language("java") private static final String NESTED_RECORD = """
            package a.b;
            class X {
                record Point(int x, int y) {}
                record Line(Point a, Point b) {}
                static int m(Object o) {
                    if (o instanceof Line(Point(int x1, int y1), Point b)) {
                        return x1 + y1;
                    }
                    return 0;
                }
            }""";

    @DisplayName("nested record deconstruction binds all pattern variables in the then-block")
    @Test
    public void testNestedRecordPattern() {
        MethodInfo m = analyse(NESTED_RECORD);
        IfElseStatement ifElse = (IfElseStatement) m.methodBody().statements().get(0);
        VariableData vd = VariableDataImpl.of(ifElse.block().statements().get(0)); // 'return x1 + y1;'
        assertEquals("D:0-E, A:[0-E]", v(vd, "x1").assignments().toString());
        assertEquals("0.0.0", v(vd, "x1").reads().toString());
        assertEquals("D:0-E, A:[0-E]", v(vd, "y1").assignments().toString());
        assertEquals("0.0.0", v(vd, "y1").reads().toString());
        assertEquals("D:0-E, A:[0-E]", v(vd, "b").assignments().toString()); // bound but unused
    }

    @Language("java") private static final String GUARD_CLAUSE = """
            package a.b;
            class X {
                static int m(Object o) {
                    if (!(o instanceof String s)) return -1;
                    return s.length();
                }
            }""";

    @DisplayName("guard-clause pattern (negative instanceof + early return) flows into the rest of the block")
    @Test
    public void testGuardClause() {
        MethodInfo m = analyse(GUARD_CLAUSE);
        IfElseStatement ifElse = (IfElseStatement) m.methodBody().statements().get(0);

        // the pattern variable 's' is created in the condition of the if statement
        VariableData vdIf = VariableDataImpl.of(ifElse);
        assertTrue(has(vdIf, "s"));
        assertEquals("D:0-E, A:[0-E]", v(vdIf, "s").assignments().toString());

        // since the then-branch exits, Java flow-scopes 's' to the rest of the block: it is present at
        // 'return s.length();' and its read is recorded there (regression test for the Util.beyond scope fix)
        VariableData vdUse = VariableDataImpl.of(m.methodBody().statements().get(1));
        assertTrue(has(vdUse, "s"));
        assertEquals("1", v(vdUse, "s").reads().toString());
        assertEquals("D:0-E, A:[0-E]", v(vdUse, "s").assignments().toString());
    }
}
