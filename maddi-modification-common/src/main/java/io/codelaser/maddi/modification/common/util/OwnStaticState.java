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


package io.codelaser.maddi.modification.common.util;

import io.codelaser.maddi.annotation.rare.IgnoreModifications;
import io.codelaser.maddi.cst.api.expression.Assignment;
import io.codelaser.maddi.cst.api.expression.MethodCall;
import io.codelaser.maddi.cst.api.expression.VariableExpression;
import io.codelaser.maddi.cst.api.info.FieldInfo;
import io.codelaser.maddi.cst.api.info.MethodInfo;
import io.codelaser.maddi.cst.api.info.TypeInfo;
import io.codelaser.maddi.cst.api.variable.FieldReference;
import io.codelaser.maddi.cst.api.variable.Variable;

/**
 * The static state a method's type owns: that of its primary type (a nested type included) and of its parent types.
 * Modifying it is a modification like any other, "modifications are modifications" (road-to-immutability §050,
 * the book's {@code CountAccess.getK()} is {@code @Modified}); modifying another type's is a static side effect.
 * Static state behind an {@code @IgnoreModifications} field is disclaimed.
 */
public final class OwnStaticState {
    private static final String IGNORE_MODIFICATIONS_FQN = IgnoreModifications.class.getCanonicalName();

    private OwnStaticState() {
    }

    /** The complement of {@code StaticSideEffectAnalyzerImpl.escapes}. */
    public static boolean owns(MethodInfo methodInfo, TypeInfo owner) {
        return owner.primaryType() == methodInfo.typeInfo().primaryType()
               || methodInfo.typeInfo().superTypesExcludingJavaLangObject().contains(owner);
    }

    /**
     * The static field a field chain starts from: {@code COUNT} for {@code COUNT}, {@code STATE.count} and
     * {@code STATE.list}; null when the chain starts elsewhere, or passes an {@code @IgnoreModifications} field.
     */
    public static FieldReference staticRoot(Variable variable) {
        Variable v = variable;
        while (v instanceof FieldReference fr && !ignored(fr.fieldInfo())) {
            if (fr.fieldInfo().isStatic()) return fr;
            v = fr.scopeVariable();
        }
        return null;
    }

    /*
     The materialized property, or on a source field the annotation itself: the property is written by the analyzer
     after the methods of the first pass have been linked, and before any analysis at all in prepwork.
     */
    private static boolean ignored(FieldInfo fieldInfo) {
        return fieldInfo.isIgnoreModifications()
               || fieldInfo.annotations().stream()
                       .anyMatch(ae -> IGNORE_MODIFICATIONS_FQN.equals(ae.typeInfo().fullyQualifiedName()));
    }

    /** An assignment to the method's own static state: {@code ++count}, {@code STATE.count = 5}. */
    public static boolean assigns(MethodInfo methodInfo, Assignment assignment) {
        return staticRoot(assignment.variableTarget()) instanceof FieldReference fr
               && owns(methodInfo, fr.fieldInfo().owner());
    }

    /**
     * A call on the method's own static state, {@code COUNTER.incrementAndGet()}, of a method that does not
     * disclaim its modifications ({@code LOG.info(...)} does). Whether the callee modifies is the caller's question.
     */
    public static boolean callOn(MethodInfo methodInfo, MethodCall methodCall) {
        return methodCall.object() instanceof VariableExpression ve
               && staticRoot(ve.variable()) instanceof FieldReference fr
               && owns(methodInfo, fr.fieldInfo().owner())
               && !methodCall.methodInfo().isIgnoreModification();
    }
}
