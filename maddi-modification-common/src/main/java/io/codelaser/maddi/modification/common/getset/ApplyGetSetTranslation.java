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

package io.codelaser.maddi.modification.common.getset;

import static io.codelaser.maddi.cst.impl.analysis.PropertyImpl.GET_SET_FIELD;

import io.codelaser.maddi.cst.api.analysis.Value;
import io.codelaser.maddi.cst.api.element.Source;
import io.codelaser.maddi.cst.api.expression.*;
import io.codelaser.maddi.cst.api.info.MethodInfo;
import io.codelaser.maddi.cst.api.runtime.Runtime;
import io.codelaser.maddi.cst.api.translate.TranslationMap;
import io.codelaser.maddi.cst.api.type.ParameterizedType;
import io.codelaser.maddi.cst.api.variable.FieldReference;
import io.codelaser.maddi.cst.api.variable.Variable;
import io.codelaser.maddi.cst.impl.analysis.ValueImpl;

public record ApplyGetSetTranslation(Runtime runtime,
                                     java.util.function.BiConsumer<MethodInfo, Value.FieldValue> observer)
        implements TranslationMap {

    public ApplyGetSetTranslation(Runtime runtime) {
        this(runtime, (_, _) -> {
        });
    }

    /**
     * A callee's getter/setter status as this translation reads it, for a caller that must know what its result
     * depended on: prep records it per type, so that a rewired type can tell whether its carried prep data still holds
     * ({@code PrepAnalyzer.GET_SET_ASSUMPTIONS}).
     */
    public static String describe(Value.FieldValue getSet) {
        if (getSet == null || getSet.field() == null) return "-";
        return (getSet.setter() ? "set " : "get ") + getSet.field().fullyQualifiedName() + "/"
               + getSet.parameterIndexOfIndex() + (getSet.list() ? " list" : "");
    }

    /** The status {@link #translateExpression} would read, computed on demand as it does. */
    public static Value.FieldValue currentGetSet(MethodInfo methodInfo) {
        ensureGetSet(methodInfo);
        return methodInfo.analysis().getOrDefault(GET_SET_FIELD, ValueImpl.GetSetValueImpl.EMPTY);
    }

    @Override
    public Variable translateVariableRecursively(Variable variable) {
        return runtime.translateVariableRecursively(this, variable);
    }

    @Override
    public boolean isClearAnalysis() {
        return false;
    }

    /*
    a chain of @Fluent setters will be translated to a chain of comma expressions, as follows:

    new Builder().setA(a).setB(b).setC(c).build()

    becomes

    (e.a = a, e.b = b, e.c = c, e.build())  with e the 'new Builder()' object.

    This ensures that all assignments have the same scope object.
    To do this properly, we must (a) properly implement comma expressions, (b) have simple @Fluent already analyzed

     */
    @Override
    public Expression translateExpression(Expression expression) {
        if (expression instanceof Lambda || expression instanceof ConstructorCall cc && cc.anonymousClass() != null) {
            return null; // keep hands off
        }
        if (expression instanceof SwitchExpression se) {
            Expression tSelect = se.selector().translate(this);
            if (tSelect == se.selector()) return null; // no change, we'll not go deeper here but someplace else
            return se.withSelector(tSelect);
        }
        if (expression instanceof MethodCall mc) {
            ensureGetSet(mc.methodInfo());

            Value.FieldValue getSet = mc.methodInfo().analysis().getOrDefault(GET_SET_FIELD,
                    ValueImpl.GetSetValueImpl.EMPTY);
            observer.accept(mc.methodInfo(), getSet);
            if (getSet.field() != null) {
                ParameterizedType type = mc.concreteReturnType();
                Source objectSourceIn = mc.object().source();
                Source objectSource = objectSourceIn == null ? runtime.noSource() : objectSourceIn;
                assert objectSource != null;

                if (getSet.setter()) {
                    // setter
                    return replaceByAssignmentOrComma(mc, getSet, type, objectSource);
                }
                // getter
                return replaceByVariableExpression(mc, getSet, type, objectSource);
            }
        }
        return expression;
    }

    // getter
    private VariableExpression replaceByVariableExpression(MethodCall mc,
                                                           Value.FieldValue getSet,
                                                           ParameterizedType type,
                                                           Source objectSource) {
        Expression object = unwrap(mc.object().translate(this));
        Variable variable;
        if (mc.parameterExpressions().size() == 1) {
            ParameterizedType arrayType = type.copyWithArrays(type.arrays() + 1);
            FieldReference fr = runtime.newFieldReference(getSet.field(), object, arrayType);
            Expression index = mc.parameterExpressions().getFirst();
            Expression replacedIndex = index.translate(this);
            VariableExpression arrayExpression = runtime.newVariableExpressionBuilder()
                    .setVariable(fr)
                    .setSource(objectSource)
                    .build();
            variable = runtime.newDependentVariable(arrayExpression, replacedIndex, type);
        } else {
            variable = runtime.newFieldReference(getSet.field(), object, type);
        }
        return runtime.newVariableExpressionBuilder().setVariable(variable).setSource(mc.source()).build();
    }

    // setter
    private Expression replaceByAssignmentOrComma(MethodCall mc,
                                                  Value.FieldValue getSet,
                                                  ParameterizedType type,
                                                  Source objectSource) {

        Expression translatedObject = mc.object().translate(this);
        Expression object;
        CommaExpression previousFluent;
        if (translatedObject instanceof CommaExpression ce) {
            previousFluent = ce;
            object = ce.expressions().getLast();
        } else {
            object = unwrap(translatedObject);
            previousFluent = null;
        }
        Variable v;
        if (getSet.hasIndex()) {
            Expression replacedIndex = mc.parameterExpressions().get(getSet.parameterIndexOfIndex())
                    .translate(this);
            ParameterizedType arrayType = type.copyWithArrays(type.arrays() + 1);
            FieldReference fr = runtime.newFieldReference(getSet.field(), object, arrayType);
            VariableExpression arrayExpression = runtime.newVariableExpressionBuilder()
                    .setVariable(fr)
                    .setSource(objectSource)
                    .build();
            v = runtime.newDependentVariable(arrayExpression, replacedIndex, type);
        } else {
            //    v = runtime.newFieldReference(getSet.field(), object, getSet.field().type());
            v = runtime.newFieldReference(getSet.field(), object, object.parameterizedType());
        }
        VariableExpression target = runtime.newVariableExpressionBuilder().setVariable(v).setSource(mc.source()).build();
        int index = getSet.parameterIndexOfValue();
        Expression parameterExpression = index < mc.parameterExpressions().size() ? mc.parameterExpressions().get(index)
                : runtime.newEmptyExpression();
        Expression replacedValue = parameterExpression.translate(this);
        Assignment assignment = runtime.newAssignmentBuilder()
                .setSource(mc.source()).
                setTarget(target).setValue(replacedValue).build();

        if (mc.methodInfo().isFluent() || previousFluent != null) {
            CommaExpression.Builder builder = runtime().newCommaBuilder();
            if (previousFluent != null) {
                builder.addExpressions(previousFluent.expressions().subList(0, previousFluent.expressions().size() - 1));
            }
            builder.addExpression(assignment);
            builder.addExpression(object);
            return builder.build();
        }
        return assignment;
    }

    private static Expression unwrap(Expression object) {
        if (object instanceof EnclosedExpression ee) return unwrap(ee.inner());
        if (object instanceof Cast cast && cast.expression() instanceof VariableExpression ve) {
            return unwrap(ve);
        }
        return object;
    }

    /*
    Depending on the order of the primary types in "PrepAnalyzer.doPrimaryTypes", the getSet analysis may have
    happened already, or not. TestCallGraph2 contains an example where this can go wrong, but note that the execution
    order for the modification analyzer does not catch this problem.

     */
    private static void ensureGetSet(MethodInfo methodInfo) {
        // A qualified explicit constructor invocation 'outer.super(...)' (JLS 8.8.7.1) is represented as a MethodCall
        // whose methodInfo() is the superclass constructor. Get/set analysis is meaningless for a constructor, and
        // GetSetHelper.doGetSetAnalysis asserts against being given one, so exclude constructors here.
        // A method resolved from a compiled (byte-code) type has no source body; get/set cannot be computed for it
        // and doGetSetAnalysis asserts methodBody != null, so exclude those too.
        if (!methodInfo.isAbstract() && !methodInfo.isConstructor() && methodInfo.methodBody() != null) {
            GetSetHelper.doGetSetAnalysis(methodInfo, methodInfo.methodBody());
        }
    }
}
