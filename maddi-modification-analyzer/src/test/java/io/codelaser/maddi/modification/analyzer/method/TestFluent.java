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

package io.codelaser.maddi.modification.analyzer.method;

import io.codelaser.maddi.modification.analyzer.CommonTest;
import io.codelaser.maddi.modification.link.impl.MethodLinkedVariablesImpl;
import io.codelaser.maddi.modification.prepwork.variable.MethodLinkedVariables;
import io.codelaser.maddi.modification.prepwork.variable.VariableData;
import io.codelaser.maddi.modification.prepwork.variable.VariableInfo;
import io.codelaser.maddi.modification.prepwork.variable.impl.VariableDataImpl;
import io.codelaser.maddi.cst.api.analysis.Value;
import io.codelaser.maddi.cst.api.info.FieldInfo;
import io.codelaser.maddi.cst.api.info.Info;
import io.codelaser.maddi.cst.api.info.MethodInfo;
import io.codelaser.maddi.cst.api.info.TypeInfo;
import io.codelaser.maddi.cst.api.statement.IfElseStatement;
import io.codelaser.maddi.cst.api.statement.Statement;
import io.codelaser.maddi.cst.impl.analysis.PropertyImpl;
import io.codelaser.maddi.cst.impl.analysis.ValueImpl;
import org.intellij.lang.annotations.Language;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static io.codelaser.maddi.modification.link.impl.MethodLinkedVariablesImpl.METHOD_LINKS;
import static io.codelaser.maddi.cst.impl.analysis.ValueImpl.BoolImpl.FALSE;
import static io.codelaser.maddi.cst.impl.analysis.ValueImpl.BoolImpl.TRUE;
import static org.junit.jupiter.api.Assertions.*;

public class TestFluent extends CommonTest {

    @Language("java")
    private static final String INPUT1 = """
            package a.b;
            import java.util.Set;
            class B {
                private int i;
                B setI(int i) { this.i = i; return this; }
                B setI2(int i, boolean condition) {
                    if (condition) {
                        System.out.println("true, i = "+i);
                        return this;
                    } else {
                        System.out.println("false, i = "+i);
                        B b = this;
                        return b;
                    }
                }
            }
            """;

    @DisplayName("builder with fluent methods")
    @Test
    public void test1() {
        TypeInfo B = javaInspector.parse("a.b.B", INPUT1);
        List<Info> ao = prepWork(B);
        analyzer.go(ao);
        FieldInfo iField = B.getFieldByName("i", true);
        {
            // @GetSet @Fluent
            MethodInfo setI = B.findUniqueMethod("setI", 1);
            assertSame(FALSE, setI.analysis().getOrDefault(PropertyImpl.IDENTITY_METHOD, FALSE));
            assertSame(TRUE, setI.analysis().getOrDefault(PropertyImpl.FLUENT_METHOD, FALSE));
            assertTrue(setI.isFluent());
            Value.FieldValue fvI = setI.analysis().getOrDefault(PropertyImpl.GET_SET_FIELD, ValueImpl.GetSetValueImpl.EMPTY);
            assertSame(iField, fvI.field());
        }
        {
            // @Fluent, not @GetSet
            MethodInfo setI2 = B.findUniqueMethod("setI2", 2);

            IfElseStatement ifElse = (IfElseStatement) setI2.methodBody().statements().getFirst();
            {
                Statement s001 = ifElse.block().statements().get(1);
                VariableData vd001 = VariableDataImpl.of(s001);
                VariableInfo viRv001 = vd001.variableInfo(setI2.fullyQualifiedName());
                assertEquals("setI2←this", viRv001.linkedVariables().toString());
            }
            {
                Statement s012 = ifElse.elseBlock().statements().get(2);
                VariableData vd012 = VariableDataImpl.of(s012);
                VariableInfo viRv012 = vd012.variableInfo(setI2.fullyQualifiedName());
                assertEquals("setI2←this", viRv012.linkedVariables().toString());
            }
            {
                VariableData vd0 = VariableDataImpl.of(ifElse);
                VariableInfo viRv0 = vd0.variableInfo(setI2.fullyQualifiedName());
                assertEquals("setI2←this", viRv0.linkedVariables().toString());
            }

            assertSame(FALSE, setI2.analysis().getOrDefault(PropertyImpl.IDENTITY_METHOD, FALSE));
            Value.FieldValue fvI = setI2.analysis().getOrDefault(PropertyImpl.GET_SET_FIELD, ValueImpl.GetSetValueImpl.EMPTY);
            assertSame(ValueImpl.GetSetValueImpl.EMPTY, fvI);

            MethodLinkedVariables sv = setI2.analysis().getOrNull(METHOD_LINKS, MethodLinkedVariablesImpl.class);
            assertEquals("[-, -] --> setI2←this", sv.toString());

            assertSame(TRUE, setI2.analysis().getOrDefault(PropertyImpl.FLUENT_METHOD, FALSE));
            assertTrue(setI2.isFluent());
        }
    }
}
