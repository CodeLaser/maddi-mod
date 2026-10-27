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
import static org.junit.jupiter.api.Assertions.assertNotNull;

public class TestAssignmentsReturn extends CommonTest {

    @Language("java")
    private static final String INPUT1 = """
            package a.b;
            class X {
                static int method(char c) {
                    int i;
                    if(c == '?') {
                        return 0;
                    } else {
                        i  = 1;
                    }
                    return i;
                }
            }
            """;

    @DisplayName("if-else with single return")
    @Test
    public void test1() {
        TypeInfo X = javaInspector.parse(ABX, INPUT1);
        MethodInfo method = X.findUniqueMethod("method", 1);
        PrepAnalyzer analyzer = new PrepAnalyzer(runtime);
        analyzer.doMethod(method);
        VariableData vdMethod = VariableDataImpl.of(method);
        assertNotNull(vdMethod);
        // alphabetically:
        assertEquals("a.b.X.method(char), a.b.X.method(char):0:c, i", vdMethod.knownVariableNamesToString());
        // in order of appearance:
        assertEquals("[i, a.b.X.method(char):0:c, a.b.X.method(char)]", vdMethod.knownVariableNames().toString());

        VariableInfo iVi = vdMethod.variableInfo("i");
        assertEquals("2", iVi.reads().toString());
        assertEquals("D:0, A:[1.1.0, 1=M]", iVi.assignments().toString());
    }

}
