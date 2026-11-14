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

package io.codelaser.maddi.modification.prepwork.callgraph;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

import io.codelaser.maddi.cst.api.analysis.Property;
import io.codelaser.maddi.cst.api.expression.And;
import io.codelaser.maddi.cst.api.expression.Assignment;
import io.codelaser.maddi.cst.api.expression.BinaryOperator;
import io.codelaser.maddi.cst.api.expression.EnclosedExpression;
import io.codelaser.maddi.cst.api.expression.Expression;
import io.codelaser.maddi.cst.api.expression.VariableExpression;
import io.codelaser.maddi.cst.api.info.FieldInfo;
import io.codelaser.maddi.cst.api.info.MethodInfo;
import io.codelaser.maddi.cst.api.statement.IfElseStatement;
import io.codelaser.maddi.cst.api.statement.LocalVariableCreation;
import io.codelaser.maddi.cst.api.type.ParameterizedType;
import io.codelaser.maddi.cst.api.variable.FieldReference;
import io.codelaser.maddi.cst.api.variable.LocalVariable;
import io.codelaser.maddi.cst.api.variable.Variable;
import io.codelaser.maddi.cst.impl.analysis.PropertyImpl;
import io.codelaser.maddi.cst.impl.analysis.ValueImpl;

/**
 * The lazily cached value (CodeLaser/maddi#208, maddi-mod#124): a field that, outside construction, is only written
 * once its value is still the "not computed yet" constant,
 * <pre>
 * private int hashCode = -1;
 * public int hashCode() {
 *     if (hashCode == -1) hashCode = var * 3 + version;
 *     return hashCode;
 * }
 * </pre>
 * or, as {@code String.hashCode()} does it, through a local copy: {@code int h = hash; if (h == 0) { h = ...; hash = h; }}.
 * Such a write is a memo, not a modification: {@code String}'s cached hash is why the JDK hints declare it
 * {@code @NotModified}. Treating it as one made every key put into, or looked up in, a {@code HashMap} modified (the
 * map calls {@code hashCode()} on it), and through that the types holding such keys mutable. hashCode, equals,
 * compareTo and toString are queries by contract; the alternative to recognising the idiom was asking the author for
 * an {@code @IgnoreModifications} on the cache field, which is what this decides for them.
 * <p>
 * The recognised field is marked {@link PropertyImpl#IGNORE_MODIFICATIONS_FIELD}, exactly as the annotation would,
 * so that every consumer agrees. The rule is narrow on purpose: an instance field of a primitive or {@code String}
 * type (the cached value has no content of its own to modify), written only by parameterless instance methods of its
 * own type, each write guarded by an {@code ==} test of that field (or a local copy of it) against a constant.
 */
public final class LazyCacheField {

    /**
     * INTRINSIC: the field is a lazily cached value. Derived from the bodies with {@code FINAL_FIELD}; it is what
     * {@code ClearDerived} restores {@link PropertyImpl#IGNORE_MODIFICATIONS_FIELD} from, because that property is in
     * the cross-type-derived tier, which a seeded analysis clears.
     */
    public static final Property LAZY_CACHE_FIELD = new PropertyImpl("lazyCacheField", ValueImpl.BoolImpl.FALSE,
            Property.AnalysisTier.INTRINSIC);

    private LazyCacheField() {
    }

    public static boolean isLazyCacheField(FieldInfo fieldInfo) {
        return fieldInfo.analysis().getOrDefault(LAZY_CACHE_FIELD, ValueImpl.BoolImpl.FALSE).isTrue();
    }

    /**
     * Write both properties. Called once a non-final field's assigning methods are known.
     */
    static void mark(FieldInfo fieldInfo) {
        if (!fieldInfo.analysis().haveAnalyzedValueFor(LAZY_CACHE_FIELD)) {
            fieldInfo.analysis().set(LAZY_CACHE_FIELD, ValueImpl.BoolImpl.TRUE);
        }
        restoreIgnoreModifications(fieldInfo);
    }

    /**
     * After a clear of the derived tier: put the disclaimer back on a recognised field.
     */
    public static void restoreIgnoreModifications(FieldInfo fieldInfo) {
        if (isLazyCacheField(fieldInfo)
            && !fieldInfo.analysis().haveAnalyzedValueFor(PropertyImpl.IGNORE_MODIFICATIONS_FIELD)) {
            fieldInfo.analysis().set(PropertyImpl.IGNORE_MODIFICATIONS_FIELD, ValueImpl.BoolImpl.TRUE);
        }
    }

    /**
     * @param assigningMethods every method, outside the field's construction, that assigns the field; not empty
     */
    static boolean isLazyCache(FieldInfo fieldInfo, Collection<MethodInfo> assigningMethods) {
        if (assigningMethods.isEmpty() || fieldInfo.isStatic() || fieldInfo.access() == null
            || !fieldInfo.access().isPrivate() || !valueType(fieldInfo.type())) {
            return false;
        }
        for (MethodInfo methodInfo : assigningMethods) {
            if (methodInfo.isStatic() || !methodInfo.parameters().isEmpty() || methodInfo.typeInfo() != fieldInfo.owner()
                || methodInfo.methodBody() == null || !onlyGuardedWrites(methodInfo, fieldInfo)) {
                return false;
            }
        }
        return true;
    }

    private static boolean valueType(ParameterizedType type) {
        return type.arrays() == 0 && (type.isPrimitiveExcludingVoid() || type.isJavaLangString());
    }

    private static boolean onlyGuardedWrites(MethodInfo methodInfo, FieldInfo fieldInfo) {
        // locals initialised with a read of the field: 'int h = hash;'
        Set<Variable> copies = new HashSet<>();
        methodInfo.methodBody().visit(e -> {
            if (e instanceof LocalVariableCreation lvc) {
                lvc.localVariableStream().forEach(lv -> {
                    if (readsField(lv.assignmentExpression(), fieldInfo)) copies.add(lv);
                });
            }
            return true;
        });
        // the writes inside the then-block of 'if (f == K)' / 'if (copy == K)'
        Set<Assignment> guarded = new HashSet<>();
        methodInfo.methodBody().visit(e -> {
            if (e instanceof IfElseStatement ifElse && guardTest(ifElse.expression(), fieldInfo, copies)) {
                ifElse.block().visit(inner -> {
                    if (inner instanceof Assignment a && isWriteOf(a, fieldInfo)) guarded.add(a);
                    return true;
                });
            }
            return true;
        });
        boolean[] ok = {true};
        methodInfo.methodBody().visit(e -> {
            if (e instanceof Assignment a && isWriteOf(a, fieldInfo) && !guarded.contains(a)) ok[0] = false;
            return ok[0];
        });
        return ok[0];
    }

    private static boolean isWriteOf(Assignment a, FieldInfo fieldInfo) {
        return a.variableTarget() instanceof FieldReference fr && fr.fieldInfo() == fieldInfo;
    }

    // 'f == K', 'K == f', possibly one conjunct of '&&'
    private static boolean guardTest(Expression condition, FieldInfo fieldInfo, Set<Variable> copies) {
        Expression c = unwrap(condition);
        if (c instanceof And and) {
            return and.expressions().stream().anyMatch(e -> guardTest(e, fieldInfo, copies));
        }
        if (!(c instanceof BinaryOperator bo)) return false;
        String op = bo.operator().name();
        if ("&&".equals(op)) {
            return guardTest(bo.lhs(), fieldInfo, copies) || guardTest(bo.rhs(), fieldInfo, copies);
        }
        if (!"==".equals(op)) return false;
        Expression lhs = unwrap(bo.lhs());
        Expression rhs = unwrap(bo.rhs());
        return cacheRead(lhs, fieldInfo, copies) && constant(rhs) || cacheRead(rhs, fieldInfo, copies) && constant(lhs);
    }

    private static boolean cacheRead(Expression e, FieldInfo fieldInfo, Set<Variable> copies) {
        return readsField(e, fieldInfo)
               || e instanceof VariableExpression ve && ve.variable() instanceof LocalVariable && copies.contains(ve.variable());
    }

    private static boolean readsField(Expression e, FieldInfo fieldInfo) {
        return e != null && unwrap(e) instanceof VariableExpression ve && ve.variable() instanceof FieldReference fr
               && fr.fieldInfo() == fieldInfo && (fr.isDefaultScope() || fr.scopeIsThis());
    }

    private static boolean constant(Expression e) {
        return e.isConstant() || e.isNullConstant() || e.numericValue() != null;
    }

    private static Expression unwrap(Expression e) {
        Expression x = e;
        while (x instanceof EnclosedExpression ee) x = ee.inner();
        return x;
    }
}
