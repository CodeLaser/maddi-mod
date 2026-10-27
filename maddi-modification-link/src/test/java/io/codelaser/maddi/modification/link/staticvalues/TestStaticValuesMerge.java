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

package io.codelaser.maddi.modification.link.staticvalues;

import io.codelaser.maddi.modification.link.CommonTest;
import io.codelaser.maddi.modification.link.LinkComputer;
import io.codelaser.maddi.modification.link.impl.LinkComputerImpl;
import io.codelaser.maddi.modification.prepwork.PrepAnalyzer;
import io.codelaser.maddi.modification.prepwork.variable.VariableData;
import io.codelaser.maddi.modification.prepwork.variable.VariableInfo;
import io.codelaser.maddi.modification.prepwork.variable.impl.VariableDataImpl;
import io.codelaser.maddi.cst.api.info.MethodInfo;
import io.codelaser.maddi.cst.api.info.TypeInfo;
import io.codelaser.maddi.cst.api.statement.Statement;
import org.intellij.lang.annotations.Language;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static io.codelaser.maddi.modification.link.impl.MethodLinkedVariablesImpl.METHOD_LINKS;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class TestStaticValuesMerge extends CommonTest {

    @Language("java")
    private static final String INPUT1 = """
            package a.b;
            class X {
                void method(int[] array) {
                    int y = array[0];
                    if(array[1]>10) {
                        y += 9;
                    }
                    System.out.println(array[0] +" ? "+y);
                    array[0] = y;
                }
            }
            """;

    @DisplayName("test the 'erase' system, 1")
    @Test
    public void test1() {
        TypeInfo X = javaInspector.parse("a.b.X", INPUT1);
        PrepAnalyzer analyzer = new PrepAnalyzer(runtime, new PrepAnalyzer.Options.Builder().build());
        analyzer.doPrimaryType(X);

        LinkComputer tlc = new LinkComputerImpl(javaInspector);
        MethodInfo method = X.findUniqueMethod("method", 1);
        method.analysis().getOrCreate(METHOD_LINKS, () -> tlc.doMethod(method));

        VariableData vd0 = VariableDataImpl.of(method.methodBody().statements().getFirst());
        VariableInfo vi0y = vd0.variableInfo("y");
        assertEquals("y∈0:array,y←0:array[0]", vi0y.linkedVariables().toString());

        Statement s1 = method.methodBody().statements().get(1);
        VariableData vd100 = VariableDataImpl.of(s1.block().statements().getFirst());
        VariableInfo vi100y = vd100.variableInfo("y");
        assertEquals("y←$_ce3,y∈0:array,y←0:array[0]", vi100y.linkedVariables().toString()); // multiple values

        Statement s2 = method.methodBody().statements().get(2);
        VariableData vd1 = VariableDataImpl.of(s2);
        VariableInfo vi1y = vd1.variableInfo("y");
                assertEquals("y←$_ce3,y∈0:array,y←0:array[0]", vi1y.linkedVariables().toString()); // multiple values

    }

    @Language("java")
    private static final String INPUT2 = """
            package a.b;
            class X {
                void method(int[] array) {
                    int y = array[0];
                    if(array[1]>10) {
                        y = 9 * array[3] - array[4];
                    }
                    System.out.println(array[0] +" ? "+y);
                    array[0] = y;
                }
            }
            """;

    @DisplayName("test the 'erase' system, 2")
    @Test
    public void test2() {
        TypeInfo X = javaInspector.parse("a.b.X", INPUT2);
        PrepAnalyzer analyzer = new PrepAnalyzer(runtime, new PrepAnalyzer.Options.Builder().build());
        analyzer.doPrimaryType(X);

        LinkComputer tlc = new LinkComputerImpl(javaInspector);
        MethodInfo method = X.findUniqueMethod("method", 1);
        method.analysis().getOrCreate(METHOD_LINKS, () -> tlc.doMethod(method));

        VariableData vd0 = VariableDataImpl.of(method.methodBody().statements().getFirst());
        VariableInfo vi0y = vd0.variableInfo("y");
        assertEquals("y∈0:array,y←0:array[0]", vi0y.linkedVariables().toString());

        VariableData vd100 = VariableDataImpl.of(method.methodBody().statements().get(1).block().statements().getFirst());
        VariableInfo vi100y = vd100.variableInfo("y");
        assertEquals("y∈0:array,y←0:array[0]", vi100y.linkedVariables().toString()); // binary operator

        VariableData vd2 = VariableDataImpl.of(method.methodBody().statements().get(2));
        VariableInfo vi1y = vd2.variableInfo("y");
        assertEquals("y∈0:array,y←0:array[0]", vi1y.linkedVariables().toString()); // value + empty -> empty

    }

}
