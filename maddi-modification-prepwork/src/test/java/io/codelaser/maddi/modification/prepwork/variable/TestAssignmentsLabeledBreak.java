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
 * Labeled {@code break}/{@code continue} (none of which were covered) and a plain {@code continue}. The
 * conditionally-assigned variable is never definitely re-assigned, so no merge appears; it stays defined through
 * its initial value.
 */
public class TestAssignmentsLabeledBreak extends CommonTest {

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

    @Language("java") private static final String LABELED_BREAK = """
            package a.b;
            class X {
                static int m(int[][] g) {
                    int found = -1;
                    outer:
                    for (int i = 0; i < g.length; i++) {
                        for (int j = 0; j < g[i].length; j++) {
                            if (g[i][j] == 0) {
                                found = i;
                                break outer;
                            }
                        }
                    }
                    return found;
                }
            }""";

    @DisplayName("labeled break out of nested loops")
    @Test
    public void testLabeledBreak() {
        VariableData vd = analyse(LABELED_BREAK);
        VariableInfo found = local(vd, "found");
        assertEquals("2", found.reads().toString());
        // conditional assignment deep in the inner loop; no merge (not definitely re-assigned)
        assertEquals("D:0, A:[0, 1.0.0.0.0.0.0]", found.assignments().toString());
        assertTrue(found.hasBeenDefined("2")); // initial value at 0 survives

        VariableInfo g = local(vd, "g");
        assertEquals("1-E, 1.0.0-E, 1.0.0.0.0-E, 1.0.0;E, 1;E", g.reads().toString());
    }

    @Language("java") private static final String LABELED_CONTINUE = """
            package a.b;
            class X {
                static int m(int n) {
                    int count = 0;
                    outer:
                    for (int i = 0; i < n; i++) {
                        for (int j = 0; j < n; j++) {
                            if (j > i) continue outer;
                            count++;
                        }
                    }
                    return count;
                }
            }""";

    @DisplayName("labeled continue to the outer loop")
    @Test
    public void testLabeledContinue() {
        VariableData vd = analyse(LABELED_CONTINUE);
        VariableInfo count = local(vd, "count");
        assertEquals("1.0.0.0.1, 2", count.reads().toString());
        assertEquals("D:0, A:[0, 1.0.0.0.1]", count.assignments().toString());

        VariableInfo n = local(vd, "n");
        assertEquals("1-E, 1.0.0-E, 1.0.0;E, 1;E", n.reads().toString());
    }

    @Language("java") private static final String PLAIN_CONTINUE = """
            package a.b;
            class X {
                static int m(int n) {
                    int count = 0;
                    for (int i = 0; i < n; i++) {
                        if (i % 2 == 0) continue;
                        count++;
                    }
                    return count;
                }
            }""";

    @DisplayName("plain continue guarding an assignment")
    @Test
    public void testPlainContinue() {
        VariableData vd = analyse(PLAIN_CONTINUE);
        VariableInfo count = local(vd, "count");
        assertEquals("1.0.1, 2", count.reads().toString());
        assertEquals("D:0, A:[0, 1.0.1]", count.assignments().toString());
    }
}
