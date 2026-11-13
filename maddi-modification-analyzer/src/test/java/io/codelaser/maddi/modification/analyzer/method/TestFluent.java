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

import static io.codelaser.maddi.cst.impl.analysis.ValueImpl.BoolImpl.FALSE;
import static io.codelaser.maddi.cst.impl.analysis.ValueImpl.BoolImpl.TRUE;
import static io.codelaser.maddi.modification.link.impl.MethodLinkedVariablesImpl.METHOD_LINKS;
import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

import org.intellij.lang.annotations.Language;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.codelaser.maddi.cst.api.analysis.Value;
import io.codelaser.maddi.cst.api.info.FieldInfo;
import io.codelaser.maddi.cst.api.info.Info;
import io.codelaser.maddi.cst.api.info.MethodInfo;
import io.codelaser.maddi.cst.api.info.TypeInfo;
import io.codelaser.maddi.cst.api.statement.IfElseStatement;
import io.codelaser.maddi.cst.api.statement.Statement;
import io.codelaser.maddi.cst.impl.analysis.PropertyImpl;
import io.codelaser.maddi.cst.impl.analysis.ValueImpl;
import io.codelaser.maddi.modification.analyzer.CommonTest;
import io.codelaser.maddi.modification.link.impl.MethodLinkedVariablesImpl;
import io.codelaser.maddi.modification.prepwork.variable.MethodLinkedVariables;
import io.codelaser.maddi.modification.prepwork.variable.VariableData;
import io.codelaser.maddi.modification.prepwork.variable.VariableInfo;
import io.codelaser.maddi.modification.prepwork.variable.impl.VariableDataImpl;

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

    @Language("java")
    private static final String INPUT2 = """
            package a.b;
            class N {
                N next;
                N parent;
                int type;
                N either(boolean c) { return c ? this : next; }
                N walk() { N node = this; while (node.type == 1) node = node.parent; return node; }
                N always(boolean c) { if (c) { return this; } N self = this; return self; }
            }
            """;

    @DisplayName("fluent: every value returned is this, not just one of them")
    @Test
    public void test2() {
        TypeInfo N = javaInspector.parse("a.b.N", INPUT2);
        List<Info> ao = prepWork(N);
        analyzer.go(ao);
        // 'c ? this : next', and a walk that may stop at this (#72): this is one of the values, not the only one
        assertSame(FALSE, N.findUniqueMethod("either", 1).analysis().getOrDefault(PropertyImpl.FLUENT_METHOD, FALSE));
        assertSame(FALSE, N.findUniqueMethod("walk", 0).analysis().getOrDefault(PropertyImpl.FLUENT_METHOD, FALSE));
        assertSame(TRUE, N.findUniqueMethod("always", 1).analysis().getOrDefault(PropertyImpl.FLUENT_METHOD, FALSE));
    }

    @Language("java")
    private static final String INPUT3 = """
            package a.b;
            class X {
                interface Context { }
                interface RT {
                    default RT solve(Context context) { return this; }
                }
                static class W implements RT {
                    private final boolean ext;
                    private final RT bound;
                    private int k;
                    W(boolean ext, RT bound) { this.ext = ext; this.bound = bound; }
                    boolean isExtends() { return ext; }
                    static W extendsBound(RT t) { return new W(true, t); }
                    @Override
                    public RT solve(Context context) {
                        if (isExtends()) {
                            return extendsBound(bound.solve(context));
                        }
                        return this;
                    }
                    W fresh(boolean c) { return c ? this : new W(c, bound); }
                    W setK(int k) { this.k = k; return this; }
                    W chained() { return setK(3); }
                    RT viaSuper(Context c) { return RT.super.solve(c); }
                    boolean flag() { setK(1); return k > 0; }
                }
            }
            """;

    @DisplayName("fluent: a return of something unlinked (a new object) is not this (maddi-mod#31)")
    @Test
    public void test3() {
        TypeInfo X = javaInspector.parse("a.b.X", INPUT3);
        List<Info> ao = prepWork(X);
        analyzer.go(ao);
        TypeInfo W = X.findSubType("W");
        TypeInfo RT = X.findSubType("RT");
        assertSame(TRUE, RT.findUniqueMethod("solve", 1).analysis().getOrDefault(PropertyImpl.FLUENT_METHOD, FALSE));
        // one path returns a new W: links see only 'this'
        assertSame(FALSE, W.findUniqueMethod("solve", 1).analysis().getOrDefault(PropertyImpl.FLUENT_METHOD, FALSE));
        assertSame(FALSE, W.findUniqueMethod("fresh", 1).analysis().getOrDefault(PropertyImpl.FLUENT_METHOD, FALSE));
        // a fluent call on this, on super: still this
        assertSame(TRUE, W.findUniqueMethod("setK", 1).analysis().getOrDefault(PropertyImpl.FLUENT_METHOD, FALSE));
        assertSame(TRUE, W.findUniqueMethod("chained", 0).analysis().getOrDefault(PropertyImpl.FLUENT_METHOD, FALSE));
        assertSame(TRUE, W.findUniqueMethod("viaSuper", 1).analysis().getOrDefault(PropertyImpl.FLUENT_METHOD, FALSE));
        assertSame(FALSE, W.findUniqueMethod("flag", 0).analysis().getOrDefault(PropertyImpl.FLUENT_METHOD, FALSE));
    }
}
