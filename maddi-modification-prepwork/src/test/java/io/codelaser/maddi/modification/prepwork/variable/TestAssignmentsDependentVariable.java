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

public class TestAssignmentsDependentVariable extends CommonTest {

    @Language("java")
    String A = """
            package a;
            import java.util.Arrays;
            import java.util.List;
            
            public class A {
                public void m(List<String> strings) {
                    double[] x = strings.stream().mapToDouble(String::length).toArray();
                    double[] xCopy = Arrays.copyOf(x, x.length);
                    double xStart = xCopy[0];
                    double xEnd = xCopy[xCopy.length - 1];
                }
            }
            """;


    @DisplayName("index of creation of dependent variable")
    @Test
    public void test1() {
        TypeInfo X = javaInspector.parse("a.A", A);
        MethodInfo method = X.findUniqueMethod("m", 1);
        PrepAnalyzer analyzer = new PrepAnalyzer(runtime);
        analyzer.doMethod(method);
        VariableData vd3 = VariableDataImpl.of((method.methodBody().statements().getLast()));
        assertEquals("a.A.m(java.util.List):0:strings, x, xCopy, xCopy[0], xCopy[`10-29`], xEnd, xStart",
                vd3.knownVariableNamesToString());
        VariableInfo viXCopy = vd3.variableInfo("xCopy");
        assertEquals("D:1, A:[1]", viXCopy.assignments().toString());

        // the variable xCopy[xCopy.length-1] is not explicitly created in the code, but it appears
        // for the first time in statement 3
        VariableInfo viXCopyIndexed = vd3.variableInfo("xCopy[`10-29`]");
        assertEquals("xCopy[xCopy.length-1]", viXCopyIndexed.variable().toString());
        assertEquals("D:1, A:[]", viXCopyIndexed.assignments().toString());
    }
}
