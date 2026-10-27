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

package io.codelaser.maddi.modification.analyzer.clonebench;


import io.codelaser.maddi.modification.analyzer.CommonTest;
import io.codelaser.maddi.modification.prepwork.variable.VariableData;
import io.codelaser.maddi.modification.prepwork.variable.VariableInfo;
import io.codelaser.maddi.modification.prepwork.variable.impl.VariableDataImpl;
import io.codelaser.maddi.cst.api.expression.Assignment;
import io.codelaser.maddi.cst.api.info.Info;
import io.codelaser.maddi.cst.api.info.MethodInfo;
import io.codelaser.maddi.cst.api.info.ParameterInfo;
import io.codelaser.maddi.cst.api.info.TypeInfo;
import io.codelaser.maddi.cst.api.statement.Statement;
import io.codelaser.maddi.cst.api.variable.DependentVariable;
import io.codelaser.maddi.cst.api.variable.Variable;
import io.codelaser.maddi.cst.impl.analysis.PropertyImpl;
import io.codelaser.maddi.cst.impl.analysis.ValueImpl;
import org.intellij.lang.annotations.Language;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;


public class TestArrayVariable extends CommonTest {

    @Language("java")
    private static final String INPUT1 = """
            import java.lang.reflect.Array;
            public class C {
                public static void put(Object array, Object element, int index) {
                    if (array instanceof Object[]) {
                        ((Object[]) array)[index] = element;
                    } else {
                        Array.set(array, index, element);
                    }
                }
            }
            """;

    @DisplayName("array variable")
    @Test
    public void test1() {
        TypeInfo C = javaInspector.parse("C", INPUT1);
        List<Info> ao = prepWork(C);
        analyzer.go(ao);
        MethodInfo put = C.findUniqueMethod("put", 3);
        ParameterInfo put0 = put.parameters().getFirst();

        Statement s000 = put.methodBody().statements().getFirst().block().statements().getFirst();
        VariableData vd000 = VariableDataImpl.of(s000);

        Variable av = ((DependentVariable) ((Assignment) s000.expression()).variableTarget()).arrayVariable();
        assertSame(put0, av);

        VariableInfo viPut0 = vd000.variableInfo(put0);
        assertEquals("0:array∋1:element,0:array[2:index]←1:element", viPut0.linkedVariables().toString());

        /*
        Even though Object is immutable, it is immutable HC, so it can be downcast.
        This downcast information is available.
        Because of the downcast, modifications may occur; they are registered.
        */
        assertTrue(put0.isModified());
        ValueImpl.VariableToTypeInfoSetImpl downcast = (ValueImpl.VariableToTypeInfoSetImpl) put0.analysis().
                getOrDefault(PropertyImpl.DOWNCAST_PARAMETER, ValueImpl.VariableToTypeInfoSetImpl.EMPTY);
        assertEquals("C.put(Object,Object,int):0:array->[java.lang.Object]", downcast.nice());
    }

    @Language("java")
    private static final String INPUT2 = """
            package a.b;
            public class X {
              public void method() {
                for (int i = 0; i < 8; i++) {
                  for (int id = 0; id < 8; id++) {
                    OldBoard[i][id][move] = TheBoard[i][id];
                  }
                }
              }
            
              int[][] TheBoard;
              int[][][] OldBoard;
              int move = 0;
            }
            """;

    @DisplayName("array variable")
    @Test
    public void test2() {
        TypeInfo C = javaInspector.parse("a.b.X", INPUT2);
        List<Info> ao = prepWork(C);
        analyzer.go(ao);
    }
}
