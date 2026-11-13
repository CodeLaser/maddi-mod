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

package io.codelaser.maddi.modification.prepwork.variable.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.List;

import org.intellij.lang.annotations.Language;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.codelaser.maddi.cst.api.expression.Expression;
import io.codelaser.maddi.cst.api.info.FieldInfo;
import io.codelaser.maddi.cst.api.info.TypeInfo;
import io.codelaser.maddi.cst.api.type.ParameterizedType;
import io.codelaser.maddi.cst.api.variable.FieldReference;
import io.codelaser.maddi.cst.api.variable.LocalVariable;
import io.codelaser.maddi.modification.prepwork.CommonTest;

public class TestLinkEndInterner extends CommonTest {

    @Language("java")
    private static final String INPUT = """
            package a.b;
            class X {
                Object delegate;
            }
            """;

    @DisplayName("'a.delegate' is one end only when its 'a' carries the same assignment (maddi-mod#122)")
    @Test
    public void test() {
        TypeInfo X = javaInspector.parse(ABX, INPUT);
        FieldInfo delegate = X.getFieldByName("delegate", true);
        ParameterizedType xPt = X.asParameterizedType();
        Expression init = runtime.newNullConstant(List.of(), runtime.noSource());
        LocalVariable a1 = runtime.newLocalVariable("a", xPt, init);
        LocalVariable sameAssignment = runtime.newLocalVariable("a", xPt, init);
        // 'a' of a sibling block ('if (..) { X a = ..; } else { X a = ..; }'), another (here: equal) expression
        LocalVariable sibling = runtime.newLocalVariable("a", xPt, runtime.newNullConstant(List.of(), runtime.noSource()));
        assertEquals(a1, sibling);

        FieldReference f1 = ref(delegate, a1);
        FieldReference fSame = ref(delegate, sameAssignment);
        FieldReference fSibling = ref(delegate, sibling);
        assertEquals(f1, fSibling);

        try (LinkEndInterner _ = LinkEndInterner.open()) {
            assertSame(f1, LinkEndInterner.intern(f1));
            assertSame(f1, LinkEndInterner.intern(fSame));
            assertNotSame(f1, LinkEndInterner.intern(fSibling));
            // a local that is the end itself is shared by name, as before
            assertSame(a1, LinkEndInterner.intern(a1));
            assertSame(a1, LinkEndInterner.intern(sibling));
        }
    }

    private FieldReference ref(FieldInfo fieldInfo, LocalVariable scope) {
        return runtime.newFieldReference(fieldInfo, runtime.newVariableExpression(scope), fieldInfo.type());
    }
}
