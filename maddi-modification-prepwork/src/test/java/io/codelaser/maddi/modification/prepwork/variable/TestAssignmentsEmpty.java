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
import io.codelaser.maddi.cst.api.statement.Statement;
import org.intellij.lang.annotations.Language;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class TestAssignmentsEmpty extends CommonTest {


    @Language("java")
    private static final String INPUT1 = """
            package a.b;
            class X {
                public static void method(String[] strings) {
                    for(String s: strings) {
                        int n = s.length();
                        if(n > 0) { }
                    }
                }
            }
            """;


    @DisplayName("merge and empty block")
    @Test
    public void test1() {
        TypeInfo X = javaInspector.parse(ABX, INPUT1);
        MethodInfo method = X.findUniqueMethod("method", 1);
        PrepAnalyzer analyzer = new PrepAnalyzer(runtime);
        analyzer.doMethod(method);
        VariableData vdMethod = VariableDataImpl.of(method);
        assertNotNull(vdMethod);

        assertEquals("a.b.X.method(String[]):0:strings, s", vdMethod.knownVariableNamesToString());

        Statement s0 = method.methodBody().statements().getFirst();
        VariableData vd0 = VariableDataImpl.of(s0);
        assertEquals("a.b.X.method(String[]):0:strings, s", vd0.knownVariableNamesToString());

        Statement s001 = s0.block().statements().get(1);
        VariableData vd001 = VariableDataImpl.of(s001);
        assertEquals("a.b.X.method(String[]):0:strings, n, s", vd001.knownVariableNamesToString());
        VariableInfoContainer vicN = vd001.variableInfoContainerOrNull("n");
        assertTrue(vicN.hasMerge());
        assertTrue(vicN.hasEvaluation());
        assertSame(vicN.best(Stage.EVALUATION), vicN.best(Stage.MERGE));
    }

}
