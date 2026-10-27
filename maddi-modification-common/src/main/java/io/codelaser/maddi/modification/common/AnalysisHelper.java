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

package io.codelaser.maddi.modification.common;

import io.codelaser.maddi.cst.api.analysis.Value;
import io.codelaser.maddi.cst.api.element.SourceSet;
import io.codelaser.maddi.cst.api.info.TypeInfo;
import io.codelaser.maddi.cst.api.type.ParameterizedType;
import io.codelaser.maddi.cst.impl.analysis.PropertyImpl;
import io.codelaser.maddi.cst.impl.analysis.ValueImpl;

import java.util.Map;

import static io.codelaser.maddi.cst.impl.analysis.ValueImpl.BoolImpl.FALSE;
import static io.codelaser.maddi.cst.impl.analysis.ValueImpl.BoolImpl.TRUE;
import static io.codelaser.maddi.cst.impl.analysis.ValueImpl.IndependentImpl.*;

public class AnalysisHelper {

    public Value.Immutable typeImmutableNullIfUndecided(ParameterizedType parameterizedType) {
        return typeImmutable(parameterizedType, Map.of(), true);
    }

    public Value.Immutable typeImmutable(ParameterizedType parameterizedType) {
        return typeImmutable(parameterizedType, Map.of(), false);
    }

    /*
     Why dynamic value? See MethodCall.dynamicImmutable().
     If the dynamic value is IMMUTABLE_HC and the computed value is MUTABLE, we still need to go through the
     immutableDeterminedByTypeParameters() code... therefore a simple remote 'max()' operation does not work.
     */
    public Value.Immutable typeImmutable(ParameterizedType parameterizedType,
                                         Map<ParameterizedType, Value.Immutable> dynamicValues) {
        return typeImmutable(parameterizedType, dynamicValues, false);
    }

    private Value.Immutable typeImmutable(ParameterizedType parameterizedType,
                                          Map<ParameterizedType, Value.Immutable> dynamicValues,
                                          boolean nullIfUndecided) {
        if (parameterizedType.arrays() > 0) {
            return ValueImpl.ImmutableImpl.FINAL_FIELDS;
        }
        if (parameterizedType.isTypeOfNullConstant() || parameterizedType.isVoid()) {
            return ValueImpl.ImmutableImpl.NO_VALUE;
        }
        TypeInfo bestType = parameterizedType.bestTypeInfo();
        if (bestType == null) {
            return ValueImpl.ImmutableImpl.IMMUTABLE_HC;
        }
        if (bestType.isPrimitiveExcludingVoid()) {
            return ValueImpl.ImmutableImpl.IMMUTABLE;
        }

        Value.Immutable dynamicBaseValue;
        Value.Immutable immutableOfCurrent = dynamicValues.get(parameterizedType);
        if (immutableOfCurrent != null) {
            dynamicBaseValue = immutableOfCurrent;
        } else {
            Value.Immutable inMap = bestType.analysis().getOrNull(PropertyImpl.IMMUTABLE_TYPE, ValueImpl.ImmutableImpl.class);
            if (inMap == null) {
                if (bestType.isSynthetic() && bestType.simpleName().startsWith("§")) {
                    // the virtual-field container of a multi-parameter type (Map's key/value pair §KV, §$$): final
                    // fields, nothing else, so as immutable as its components. Without analysis it defaulted to
                    // MUTABLE, and new HashMap<>(field) over String/Integer was dependent (CodeLaser/maddi-mod#64)
                    return containerTypeImmutable(bestType, dynamicValues, nullIfUndecided);
                }
                if (nullIfUndecided && isPartOfSourceCode(bestType)) return null;
                // java.lang.Object is pure hidden content, like an unbound type parameter: the shallow analyzer
                // (ShallowMethodAnalyzer.computeMethodIndependentReturnValue) already says so for a bodiless
                // method returning Object, whatever hints are loaded. Without the JDK hints (which store
                // @ImmutableContainer(hc=true) on Object), the deep side defaulted to MUTABLE and the two
                // disagreed on every Object getter implementing such a declaration: the abstract-method fold
                // then tried to downgrade @Independent(hc=true) to @Dependent on every pass (refused, and
                // reported at certification; refactor-base's support module, maddi-mod#53).
                dynamicBaseValue = bestType.isJavaLangObject()
                        ? ValueImpl.ImmutableImpl.IMMUTABLE_HC
                        : ValueImpl.ImmutableImpl.MUTABLE;
            } else {
                dynamicBaseValue = inMap;
            }
        }
        if (dynamicBaseValue.isAtLeastImmutableHC() && !parameterizedType.parameters().isEmpty()) {
            Value.Bool ignoreTypeParameters = bestType.analysis().getOrDefault(PropertyImpl.IMMUTABLE_TYPE_INDEPENDENT_OF_TYPE_PARAMETERS,
                    FALSE);
            if (ignoreTypeParameters.isFalse()) {
                return parameterizedType.parameters().stream()
                        .map(pt -> typeImmutable(pt, dynamicValues))
                        .reduce(ValueImpl.ImmutableImpl.IMMUTABLE, Value.Immutable::min);
            }
        }
        return dynamicBaseValue;
    }

    private Value.Immutable containerTypeImmutable(TypeInfo containerType,
                                                   Map<ParameterizedType, Value.Immutable> dynamicValues,
                                                   boolean nullIfUndecided) {
        Value.Immutable result = ValueImpl.ImmutableImpl.IMMUTABLE;
        for (var fieldInfo : containerType.fields()) {
            Value.Immutable component = typeImmutable(fieldInfo.type(), dynamicValues, nullIfUndecided);
            if (component == null) return null;
            result = result.min(component);
        }
        return result;
    }

    /*
    If a type is external, the shallow analyzer/annotated API system has provided values.
    Then, we must fall back to the default.
     */
    private boolean isPartOfSourceCode(TypeInfo bestType) {
        SourceSet sourceSet = bestType.compilationUnit().sourceSet();
        return sourceSet != null && !sourceSet.externalLibrary();
    }

    public Value.Immutable typeImmutable(TypeInfo currentType, ParameterizedType type) {
        if (type.typeInfo() == null) {
            return ValueImpl.ImmutableImpl.IMMUTABLE_HC;
        }
        if (currentType.primaryType().equals(type.typeInfo().primaryType())) {
            return ValueImpl.ImmutableImpl.MUTABLE; // self-ref; ALWAYS mutable
        }
        return typeImmutable(type, Map.of());
    }


    public Value.Independent typeIndependent(ParameterizedType parameterizedType) {
        if (parameterizedType.arrays() > 0) {
            // because the "fields" of the array, i.e. the cells, can be mutated
            return ValueImpl.IndependentImpl.DEPENDENT;
        }
        TypeInfo bestType = parameterizedType.bestTypeInfo();
        if (bestType == null) {
            // unbound type parameter, null constant
            return ValueImpl.IndependentImpl.INDEPENDENT_HC;
        }
        if (bestType.isPrimitiveExcludingVoid()) {
            return ValueImpl.IndependentImpl.INDEPENDENT;
        }
        Value.Immutable immutable = bestType.analysis().getOrDefault(PropertyImpl.IMMUTABLE_TYPE,
                ValueImpl.ImmutableImpl.MUTABLE);
        if (immutable.isImmutableHC() && !parameterizedType.parameters().isEmpty()) {
            Value.Bool tpBool = bestType.analysis().getOrDefault(PropertyImpl.IMMUTABLE_TYPE_INDEPENDENT_OF_TYPE_PARAMETERS,
                    FALSE);
            if (tpBool.isFalse()) {
                return parameterizedType.parameters().stream()
                        .map(this::typeIndependent)
                        .reduce(ValueImpl.IndependentImpl.INDEPENDENT, Value.Independent::min);
            }
        }
        Value.Independent independent = bestType.analysis().getOrNull(PropertyImpl.INDEPENDENT_TYPE,
                ValueImpl.IndependentImpl.class);
        if (independent != null) return independent;
        // see typeImmutable: Object without a stored value is hidden content
        return bestType.isJavaLangObject() ? ValueImpl.IndependentImpl.INDEPENDENT_HC : ValueImpl.IndependentImpl.DEPENDENT;
    }

    /** What the type alone says: a primitive is non-null; anything else is not decided by its type. */
    public Value.Nullability nullabilityOfType(ParameterizedType parameterizedType) {
        if (parameterizedType.isPrimitiveExcludingVoid()) return ValueImpl.NullabilityImpl.NONNULL;
        return ValueImpl.NullabilityImpl.UNSPECIFIED;
    }

    public Value.Bool typeContainer(ParameterizedType parameterizedType) {
        if (parameterizedType.arrays() > 0) {
            return TRUE;
        }
        if (parameterizedType.isTypeOfNullConstant()) {
            return FALSE;
        }
        if (parameterizedType.isUnboundTypeParameter()) {
            return TRUE;
        }
        TypeInfo bestType = parameterizedType.bestTypeInfo();
        if (bestType == null) {
            return TRUE;
        }
        return bestType.analysis().getOrDefault(PropertyImpl.CONTAINER_TYPE, FALSE);
    }

    public Value.Independent typeIndependentFromImmutableOrNull(TypeInfo currentType, ParameterizedType type) {
        Value.Independent independent = typeIndependentFromImmutableOrNull(type);
        if (independent == null && currentType.equals(type.bestTypeInfo())) {
            return DEPENDENT; // self-reference
        }
        return independent;
    }

    public Value.Independent typeIndependentFromImmutableOrNull(ParameterizedType type) {
        Value.Immutable immutable = typeImmutableNullIfUndecided(type);
        if (immutable == null) return null;
        if (immutable.isMutable()) return DEPENDENT;
        if (immutable.isImmutableHC()) return INDEPENDENT_HC;
        return INDEPENDENT;
    }
}
