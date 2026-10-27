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
import io.codelaser.maddi.cst.api.info.Info;
import io.codelaser.maddi.cst.api.info.MethodInfo;
import io.codelaser.maddi.cst.api.info.TypeInfo;
import io.codelaser.maddi.cst.api.statement.Statement;
import org.intellij.lang.annotations.Language;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

public class TestCast extends CommonTest {

    @Language("java")
    private static final String INPUT1 = """
            package a.b;
            public class X {
                interface Exit {}
                record ExceptionThrown(Exception exception) implements Exit {
                }
                interface Interface {
                    Exception exception();
                }
                static class I implements Interface {
                    Exit exit;
            
                    @Override
                    public Exception exception() {
                        if (exit instanceof ExceptionThrown) {
                            ExceptionThrown et = ((ExceptionThrown) exit);
                            return et.exception();
                        }
                        return null;
                    }
                }
            }
            """;


    @DisplayName("field of getter should exist, analysis order")
    @Test
    public void test1() {
        TypeInfo X = javaInspector.parse(ABX, INPUT1);
        PrepAnalyzer analyzer = new PrepAnalyzer(runtime);
        List<Info> analysisOrder = analyzer.doPrimaryType(X);
        // Exit should come before ExceptionThrown
        String analysisOrderToString = analysisOrder.stream().map(Info::fullyQualifiedName).collect(Collectors.joining(", "));
        if (openJdkParser) {
            assertEquals("""
                    a.b.X.<init>(), a.b.X.ExceptionThrown.<init>(Exception), a.b.X.ExceptionThrown.equals(Object), \
                    a.b.X.ExceptionThrown.exception(), a.b.X.ExceptionThrown.hashCode(), a.b.X.ExceptionThrown.toString(), \
                    a.b.X.Exit, a.b.X.I.<init>(), a.b.X.Interface.exception(), a.b.X.ExceptionThrown.exception, \
                    a.b.X.Interface, a.b.X.ExceptionThrown, a.b.X.I.exception(), a.b.X.I.exit, a.b.X.I, a.b.X\
                    """, analysisOrderToString);
        } else {
            assertEquals("""
                    a.b.X.<init>(), a.b.X.ExceptionThrown.<init>(Exception), a.b.X.ExceptionThrown.exception(), \
                    a.b.X.Exit, a.b.X.I.<init>(), a.b.X.Interface.exception(), a.b.X.ExceptionThrown.exception, \
                    a.b.X.Interface, a.b.X.ExceptionThrown, a.b.X.I.exception(), a.b.X.I.exit, a.b.X.I, a.b.X\
                    """, analysisOrderToString);
        }

        TypeInfo exceptionThrown = X.findSubType("ExceptionThrown");
        MethodInfo exceptionAccessor = exceptionThrown.findUniqueMethod("exception", 0);
        assertNotNull(exceptionAccessor.getSetField().field());

        TypeInfo implementation = X.findSubType("I");
        MethodInfo exception = implementation.findUniqueMethod("exception", 0);
        Statement s001 = exception.methodBody().statements().getFirst().block().statements().get(1);
        VariableData vd001 = VariableDataImpl.of(s001);
        assertEquals("a.b.X.ExceptionThrown.exception#et, a.b.X.I.exception(), a.b.X.I.exit, a.b.X.I.this, et",
                vd001.knownVariableNamesToString());
    }

    @Language("java")
    private static final String INPUT2 = """
            package a.b;
            public class X {
                interface Exit {}
                record ExceptionThrown(Exception exception) implements Exit {
                }
                interface Interface {
                    Exception exception();
                }
                static class I implements Interface {
                    Exit exit;
            
                    @Override
                    public Exception exception() {
                        if (exit instanceof ExceptionThrown) return ((ExceptionThrown) exit).exception();
                        return null;
                    }
                }
            }
            """;


    @DisplayName("field of getter should exist, in one statement")
    @Test
    public void test2() {
        TypeInfo X = javaInspector.parse(ABX, INPUT2);
        PrepAnalyzer analyzer = new PrepAnalyzer(runtime);
        analyzer.doPrimaryType(X);
        TypeInfo exceptionThrown = X.findSubType("ExceptionThrown");
        MethodInfo exceptionAccessor = exceptionThrown.findUniqueMethod("exception", 0);
        assertNotNull(exceptionAccessor.getSetField().field());

        TypeInfo implementation = X.findSubType("I");
        MethodInfo exception = implementation.findUniqueMethod("exception", 0);
        Statement s000 = exception.methodBody().statements().get(0).block().statements().get(0);
        VariableData vd000 = VariableDataImpl.of(s000);
        assertEquals("a.b.X.ExceptionThrown.exception#a.b.X.I.exit, a.b.X.I.exception(), a.b.X.I.exit, a.b.X.I.this",
                vd000.knownVariableNamesToString());
    }
}
