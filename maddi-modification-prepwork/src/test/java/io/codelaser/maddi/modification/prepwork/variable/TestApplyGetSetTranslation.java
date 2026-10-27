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
import io.codelaser.maddi.modification.common.getset.ApplyGetSetTranslation;
import io.codelaser.maddi.cst.api.expression.Expression;
import io.codelaser.maddi.cst.api.info.MethodInfo;
import io.codelaser.maddi.cst.api.info.TypeInfo;
import io.codelaser.maddi.cst.api.statement.LocalVariableCreation;
import org.intellij.lang.annotations.Language;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class TestApplyGetSetTranslation extends CommonTest {

    @Language("java")
    private static final String INPUT1 = """
            package a.b;
            class X {
                static class I {
                    private int i;
                    private int getI() { return i; }
                    private void setI(int i) { this.i = i; }
                }
                void method(I j) {
                    j.setI(3);
                }
            }
            """;

    @DisplayName("basic setter")
    @Test
    public void test1() {
        TypeInfo X = javaInspector.parse(ABX, INPUT1);
        MethodInfo method = X.findUniqueMethod("method", 1);
        PrepAnalyzer analyzer = new PrepAnalyzer(runtime);
        analyzer.doMethod(method);
        Expression expression = method.methodBody().lastStatement().expression();
        assertEquals("j.setI(3)", expression.toString());
        Expression translated = expression.translate(new ApplyGetSetTranslation(runtime));
        assertEquals("j.i=3", translated.toString());
    }


    @Language("java")
    private static final String INPUT2 = """
            package a.b;
            class X {
                record R(int i, int j, int k) {}
                static class Builder {
                    private int i;
                    private int j;
                    private int k;
                    Builder setI(int i) { this.i = i; return this; }
                    Builder setJ(int j) { this.j = j; return this; }
                    Builder setK(int k) { this.k = k; return this; }
                    R build() { return new R(i, j, k); }
                }
                void method(Builder b) {
                    b.setI(3);
                    b.setI(4).setJ(5);
                    R r = new Builder().setI(3).setJ(4).setK(5).build();
                }
            }
            """;

    @DisplayName("basic builder")
    @Test
    public void test2() {
        TypeInfo X = javaInspector.parse(ABX, INPUT2);
        MethodInfo method = X.findUniqueMethod("method", 1);
        PrepAnalyzer analyzer = new PrepAnalyzer(runtime);
        analyzer.doMethod(method);
        {
            Expression expression = method.methodBody().statements().getFirst().expression();
            assertEquals("b.setI(3)", expression.toString());
            Expression translated = expression.translate(new ApplyGetSetTranslation(runtime));
            assertEquals("b.i=3,b", translated.toString());
        }
        {
            Expression expression = method.methodBody().statements().get(1).expression();
            assertEquals("b.setI(4).setJ(5)", expression.toString());
            Expression translated = expression.translate(new ApplyGetSetTranslation(runtime));
            assertEquals("b.i=4,b.j=5,b", translated.toString());
        }
        {
            LocalVariableCreation lvc = (LocalVariableCreation)method.methodBody().statements().get(2);
            Expression expression = lvc.localVariable().assignmentExpression();
            Expression translated = expression.translate(new ApplyGetSetTranslation(runtime));
            assertEquals("(new Builder().i=3,new Builder().j=4,new Builder().k=5,new Builder()).build()",
                    translated.toString());
        }
    }

}
