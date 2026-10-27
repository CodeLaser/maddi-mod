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
import io.codelaser.maddi.cst.api.statement.ExplicitConstructorInvocation;
import io.codelaser.maddi.cst.api.statement.Statement;
import org.intellij.lang.annotations.Language;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class TestRecord extends CommonTest {

    @Language("java")
    private static final String INPUT1 = """
            package a.b;
            public record R(int a, String b) {}
            """;

    @DisplayName("record")
    @Test
    public void test1() {
        TypeInfo R = javaInspector.parse("a.b.R", INPUT1);
        MethodInfo syntheticConstructor = R.findConstructor(2);
        assertTrue(syntheticConstructor.isSyntheticConstructor());
        PrepAnalyzer analyzer = new PrepAnalyzer(runtime);
        analyzer.doMethod(syntheticConstructor);
        Statement s0 = syntheticConstructor.methodBody().statements().stream()
                .filter(st -> !(st instanceof ExplicitConstructorInvocation))
                .findFirst().orElseThrow();
        assertEquals("this.a=a;", s0.toString());

        VariableData vd0 = VariableDataImpl.of(s0);
        assertEquals("a.b.R.<init>(int,String):0:a, a.b.R.a, a.b.R.this", vd0.knownVariableNamesToString());

        VariableInfo vi0FieldA = vd0.variableInfo("a.b.R.a");
        assertEquals("D:-, A:[0]", vi0FieldA.assignments().toString());
        assertEquals("-", vi0FieldA.reads().toString());

        VariableInfo vi0ParamA = vd0.variableInfo("a.b.R.<init>(int,String):0:a");
        assertEquals("D:-, A:[]", vi0ParamA.assignments().toString());
        assertEquals("0", vi0ParamA.reads().toString());
    }


    @Language("java")
    private static final String INPUT2 = """
            package a.b;
            import java.util.Set;
            class X {
                static class M { int i; int get() { return i; } void set(int i) { this.i = i; }}
                record R(M a, M b) {}
                static void modifyA(R r) {
                    M a = r.a;
                    M b = r.b;
                    a.set(3);
                }
            }
            """;

    @DisplayName("modify one component")
    @Test
    public void test2() {
        TypeInfo X = javaInspector.parse("a.b.X", INPUT2);
        PrepAnalyzer analyzer = new PrepAnalyzer(runtime);
        analyzer.doPrimaryType(X);
        MethodInfo modifyA = X.findUniqueMethod("modifyA", 1);
        {
            Statement s0 = modifyA.methodBody().statements().getFirst();
            VariableData vd0 = VariableDataImpl.of(s0);
            // r, a, and r.a should exist in the first statement
            assertEquals("a, a.b.X.R.a#a.b.X.modifyA(a.b.X.R):0:r, a.b.X.modifyA(a.b.X.R):0:r",
                    vd0.knownVariableNamesToString());
        }
    }

}
