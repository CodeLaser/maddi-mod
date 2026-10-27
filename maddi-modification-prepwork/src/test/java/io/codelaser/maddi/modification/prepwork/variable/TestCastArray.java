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
import io.codelaser.maddi.cst.api.expression.Assignment;
import io.codelaser.maddi.cst.api.expression.Cast;
import io.codelaser.maddi.cst.api.expression.EnclosedExpression;
import io.codelaser.maddi.cst.api.info.MethodInfo;
import io.codelaser.maddi.cst.api.info.ParameterInfo;
import io.codelaser.maddi.cst.api.info.TypeInfo;
import io.codelaser.maddi.cst.api.statement.ExpressionAsStatement;
import io.codelaser.maddi.cst.api.statement.Statement;
import io.codelaser.maddi.cst.api.variable.DependentVariable;
import org.intellij.lang.annotations.Language;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class TestCastArray extends CommonTest {

    @Language("java")
    private static final String INPUT = """
            import java.lang.reflect.Array;
            
            public class X {
            
                public static void put(Object array, Object element, int index) {
                    try {
                        if (array instanceof Object[]) {
                            try {
                                ((Object[]) array)[index] = element;
                            } catch (ArrayStoreException e) {
                                throw new IllegalArgumentException();
                            }
                        }
                    } catch (ArrayIndexOutOfBoundsException e) {
                        throw new ArrayIndexOutOfBoundsException(index + " into " + Array.getLength(array));
                    }
                }
            }
            """;

    @DisplayName("variable should exist")
    @Test
    public void test1() {
        TypeInfo X = javaInspector.parse("X", INPUT);
        PrepAnalyzer analyzer = new PrepAnalyzer(runtime);
        analyzer.doPrimaryType(X);
        MethodInfo put = X.findUniqueMethod("put", 3);
        ParameterInfo p0 = put.parameters().getFirst();
        Statement s000 = put.methodBody().statements().getFirst().block().statements().getFirst();
        Statement s00000 = s000.block().statements().getFirst();
        Statement s000000 = s00000.block().statements().getFirst();
        if (s000000 instanceof ExpressionAsStatement eas) {
            if (eas.expression() instanceof Assignment a) {
                if (a.variableTarget() instanceof DependentVariable dv) {
                    assertTrue(dv.arrayExpression() instanceof EnclosedExpression ee && ee.expression() instanceof Cast);
                    assertSame(p0, dv.arrayVariable());
                } else fail();
            } else fail();
        } else fail();

        VariableData vd000000 = VariableDataImpl.of(s000000);
        VariableInfo vi000000av = vd000000.variableInfo(p0);
        assertEquals("D:-, A:[]", vi000000av.assignments().toString());
        assertEquals("0.0.0-E, 0.0.0.0.0.0.0", vi000000av.reads().toString());
        assertEquals("""
                X.put(Object,Object,int):0:array, X.put(Object,Object,int):0:array[X.put(Object,Object,int):2:index], \
                X.put(Object,Object,int):1:element, X.put(Object,Object,int):2:index\
                """, vd000000.knownVariableNamesToString());
    }
}