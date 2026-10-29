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

package io.codelaser.maddi.modification.common.defaults;

import io.codelaser.maddi.modification.common.FunctionTypes;

import io.codelaser.maddi.modification.common.util.TolerantWrite;
import io.codelaser.maddi.modification.common.AnalysisHelper;
import io.codelaser.maddi.cst.api.analysis.Message;
import io.codelaser.maddi.cst.api.analysis.Property;
import io.codelaser.maddi.cst.api.analysis.Value;
import io.codelaser.maddi.cst.api.expression.AnnotationExpression;
import io.codelaser.maddi.cst.api.info.Info;
import io.codelaser.maddi.cst.api.info.MethodInfo;
import io.codelaser.maddi.cst.api.info.ParameterInfo;
import io.codelaser.maddi.cst.api.info.TypeInfo;
import io.codelaser.maddi.cst.api.runtime.Runtime;
import io.codelaser.maddi.cst.api.type.NullableState;
import io.codelaser.maddi.cst.api.type.ParameterizedType;
import io.codelaser.maddi.cst.impl.analysis.MessageImpl;
import io.codelaser.maddi.cst.impl.analysis.PropertyImpl;
import io.codelaser.maddi.cst.impl.analysis.ValueImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static io.codelaser.maddi.modification.common.defaults.ShallowAnalyzer.AnnotationOrigin.*;
import static io.codelaser.maddi.cst.impl.analysis.PropertyImpl.*;
import static io.codelaser.maddi.cst.impl.analysis.ValueImpl.BoolImpl.FALSE;
import static io.codelaser.maddi.cst.impl.analysis.ValueImpl.BoolImpl.TRUE;
import static io.codelaser.maddi.cst.impl.analysis.ValueImpl.IndependentImpl.*;

/*
Important to note that, because it is used in the modification analyzer (first called by MethodModAnalyzer
then values are overwritten by the AbstractInfoAnalyzer), only non-default values are to be written to the
property-value map (analysis()).
 */
public class ShallowMethodAnalyzer extends AnnotationToProperty {
    private static final Logger LOGGER = LoggerFactory.getLogger(ShallowMethodAnalyzer.class);
    private final AnalysisHelper analysisHelper;
    // shared instance, reachable from concurrently analyzed elements
    private final List<Message> messages = Collections.synchronizedList(new LinkedList<>());


    public ShallowMethodAnalyzer(Runtime runtime, AnnotationProvider annotationProvider) {
        super(runtime, annotationProvider);
        this.analysisHelper = new AnalysisHelper();
    }

    public Map<Info, ShallowAnalyzer.InfoData> analyze(MethodInfo methodInfo) {
        // atomic claim: check-then-set on the same monitor as the analysis map, so two threads reaching the
        // same (callee's) method don't both pass the guard and double-set the write-once property
        synchronized (methodInfo.analysis()) {
            if (methodInfo.analysis().getOrDefault(DEFAULTS_ANALYZER, FALSE).isTrue()) {
                return Map.of(); // already done
            }
            methodInfo.analysis().set(DEFAULTS_ANALYZER, TRUE);
        }
        Map<Info, ShallowAnalyzer.InfoData> dataMap = new HashMap<>();

        boolean explicitlyEmpty = methodInfo.explicitlyEmptyMethod();

        List<AnnotationExpression> annotations = annotationProvider.annotations(methodInfo);
        Map<Property, Value> map = annotationsToMap(methodInfo, annotations);
        Map<Property, ValueOrigin> voMap = new HashMap<>();
        map.forEach((p, v) -> voMap.put(p, new ValueOrigin(v, ShallowAnalyzer.AnnotationOrigin.ANNOTATED)));

        methodPropertiesBeforeParameters(methodInfo, voMap, explicitlyEmpty);

        for (ParameterInfo parameterInfo : methodInfo.parameters()) {
            Map<Property, ValueOrigin> parameterVoMap = handleParameter(parameterInfo, voMap, explicitlyEmpty);
            ShallowAnalyzer.InfoData parameterData = new ShallowAnalyzer.InfoData(new HashMap<>());
            dataMap.put(parameterInfo, parameterData);
            parameterVoMap.forEach((p, vo) -> {
                if (!vo.value().isDefault() || vo.origin() == ANNOTATED) {
                    TolerantWrite.setAllowControlledOverwrite(parameterInfo.analysis(), p, vo.value());
                }
                parameterData.put(p, vo.origin());
            });
            if (assumed(parameterVoMap.get(UNMODIFIED_PARAMETER)) && !stated(UNMODIFIED_PARAMETER,
                    methodInfo.overrides().stream().map(mi -> mi.parameters().get(parameterInfo.index())))) {
                markAssumed(parameterInfo, UNMODIFIED_PARAMETER);
            }
        }

        methodPropertiesAfterParameters(methodInfo, voMap);

        ShallowAnalyzer.InfoData methodData = new ShallowAnalyzer.InfoData(new HashMap<>());
        dataMap.put(methodInfo, methodData);
        voMap.forEach((p, vo) -> {
            if (!vo.value().isDefault() || vo.origin() == ANNOTATED) {
                TolerantWrite.setAllowControlledOverwrite(methodInfo.analysis(), p, vo.value());
            }
            methodData.put(p, vo.origin());
        });
        ValueOrigin fluent = voMap.get(FLUENT_METHOD);
        // a static method has no receiver to modify: its default says nothing
        if (!methodInfo.isConstructor() && !methodInfo.isStatic() && (fluent == null || !fluent.valueAsBool().isTrue())
            && assumed(voMap.get(NON_MODIFYING_METHOD)) && !stated(NON_MODIFYING_METHOD, methodInfo.overrides().stream())) {
            markAssumed(methodInfo, NON_MODIFYING_METHOD);
        }
        return dataMap;
    }

    /*
    A FALSE reached by default (no annotation; the constructor and fluent rules, and static methods, aside) is an assumption, unless an
    overridden method states the property: then it is inherited. An override's FALSE that was itself assumed, as
    read back from the compiled hints, states nothing.
     */
    private static boolean assumed(ValueOrigin vo) {
        return vo != null && vo.origin() == DEFAULT && vo.valueAsBool().isFalse();
    }

    private static boolean stated(Property property, Stream<? extends Info> overridden) {
        return overridden.anyMatch(i -> i.analysis().haveAnalyzedValueFor(property)
                                        && !i.analysis().getOrDefault(ASSUMED_DEFAULTS,
                ValueImpl.SetOfStringsImpl.EMPTY_SET).set().contains(property.key()));
    }

    private static void markAssumed(Info info, Property property) {
        TolerantWrite.setAllowControlledOverwrite(info.analysis(), ASSUMED_DEFAULTS,
                new ValueImpl.SetOfStringsImpl(Set.of(property.key())));
    }

    private ValueOrigin computeMethodContainer(MethodInfo methodInfo) {
        ParameterizedType returnType = methodInfo.returnType();
        if (returnType.arrays() > 0 || returnType.isPrimitiveExcludingVoid() || returnType.isVoid()) {
            return FROM_TYPE_TRUE;
        }
        if (returnType.isReturnTypeOfConstructor()) {
            return FROM_TYPE_NO_VALUE;
        }
        TypeInfo bestType = returnType.bestTypeInfo();
        if (bestType == null) {
            return DEFAULT_FALSE; // unbound type parameter
        }

        // check formal return type
        Value.Bool fromReturnType = bestType.analysis().getOrDefault(CONTAINER_TYPE, FALSE);
        Value.Bool bestOfOverrides = methodInfo.overrides().stream()
                .map(mi -> mi.analysis().getOrDefault(CONTAINER_TYPE, FALSE))
                .reduce(FALSE, Value.Bool::or);
        Value.Bool formal = bestOfOverrides.or(fromReturnType);
        if (formal.isTrue()) return FROM_TYPE_TRUE;

        // check identity and parameter contract
        if (methodInfo.analysis().getOrDefault(IDENTITY_METHOD, FALSE).isTrue()) {
            ParameterInfo p0 = methodInfo.parameters().getFirst();
            return new ValueOrigin(p0.analysis().getOrDefault(CONTAINER_PARAMETER, FALSE), FROM_PARAMETER);
        }
        return DEFAULT_FALSE;
    }

    private void methodPropertiesAfterParameters(MethodInfo methodInfo, Map<Property, ValueOrigin> map) {
        ValueOrigin c = map.get(CONTAINER_METHOD);
        if (c == null) {
            map.put(CONTAINER_METHOD, computeMethodContainer(methodInfo));
        }
        ValueOrigin imm = map.get(IMMUTABLE_METHOD);
        if (imm == null) {
            map.put(IMMUTABLE_METHOD, computeMethodImmutable(methodInfo));
        }
        ValueOrigin ind = map.get(INDEPENDENT_METHOD);
        if (ind == null) {
            map.put(INDEPENDENT_METHOD, computeMethodIndependent(methodInfo, map));
        }
        ValueOrigin nn = map.get(NULLABILITY_METHOD);
        if (nn == null && !methodInfo.isConstructor() && !methodInfo.isVoid()) {
            map.put(NULLABILITY_METHOD, computeMethodNullability(methodInfo, map));
        }
    }

    private static final ValueOrigin NONNULL_FROM_METHOD = new ValueOrigin(ValueImpl.NullabilityImpl.NONNULL,
            ShallowAnalyzer.AnnotationOrigin.FROM_METHOD);
    static final List<NullableState> RETURN_PRIORITY = List.of(NullableState.NONNULL, NullableState.NULLABLE);
    static final List<NullableState> PARAMETER_PRIORITY = List.of(NullableState.NULLABLE, NullableState.NONNULL);
    private static final ValueOrigin NONNULL_FROM_TYPE = new ValueOrigin(ValueImpl.NullabilityImpl.NONNULL,
            ShallowAnalyzer.AnnotationOrigin.FROM_TYPE);

    // a return: non-null when the type, fluency, or an overridden method says so; else nullable when an overridden
    // method says that (an override may narrow, but nothing says this one does); else unspecified
    private ValueOrigin computeMethodNullability(MethodInfo methodInfo, Map<Property, ValueOrigin> map) {
        if (methodInfo.returnType().isPrimitiveExcludingVoid()) return NONNULL_FROM_TYPE;
        ValueOrigin fluent = map.get(FLUENT_METHOD);
        if (fluent.valueAsBool().isTrue()) return NONNULL_FROM_METHOD;
        Set<NullableState> overridden = methodInfo.overrides().stream().filter(MethodInfo::isPublic)
                .map(mi -> mi.analysis().getOrDefault(NULLABILITY_METHOD, ValueImpl.NullabilityImpl.UNSPECIFIED)
                        .state())
                .collect(Collectors.toUnmodifiableSet());
        for (NullableState state : RETURN_PRIORITY) {
            if (overridden.contains(state)) {
                return new ValueOrigin(inherited(state, methodInfo.returnType(), methodInfo.overrides().stream()
                        .filter(MethodInfo::isPublic)
                        .map(mi -> new Declared(mi.returnType(), mi.analysis().getOrDefault(NULLABILITY_METHOD,
                                ValueImpl.NullabilityImpl.UNSPECIFIED)))
                        .toList(), RETURN_PRIORITY), FROM_OVERRIDE);
            }
        }
        return UNSPECIFIED_DEFAULT;
    }

    record Declared(ParameterizedType type, Value.Nullability nullability) {
    }

    /*
     The state an override inherits, with the overridden declarations' type-argument states when the override declares
     the same shape (the same class or array of it): guava's 'Object[] toArray()' inherits Collection.toArray()'s
     nullable elements, N(Q). A different shape ('List<E> subList' over a raw or re-parameterized type) takes the
     state only: the positions would not correspond.
     Several declarations of the same shape are combined position by position, with the priority of the top level
     ('preferred' first): Provider.compute overrides Properties.compute N(U,Q,U) and Map.compute N(U,Q,Q), and as a
     parameter gets N(U,Q,Q) whatever the order of overrides() (maddi-mod#47).
     */
    static Value.Nullability inherited(NullableState state, ParameterizedType declared, List<Declared> overridden,
                                       List<NullableState> preferred) {
        Value.Nullability combined = null;
        for (Declared d : overridden) {
            if (d.nullability().state() != state || d.nullability().arguments().isEmpty()) continue;
            if (declared.arrays() == d.type().arrays() && declared.typeInfo() != null
                && declared.typeInfo() == d.type().typeInfo()
                && declared.parameters().size() == d.type().parameters().size()) {
                combined = combined == null ? d.nullability() : combine(combined, d.nullability(), preferred);
            }
        }
        if (combined != null) return combined;
        return state == NullableState.NONNULL ? ValueImpl.NullabilityImpl.NONNULL : ValueImpl.NullabilityImpl.NULLABLE;
    }

    private static ValueImpl.NullabilityImpl combine(Value.Nullability a, Value.Nullability b,
                                                     List<NullableState> preferred) {
        NullableState state = preferred.stream()
                .filter(s -> a.state() == s || b.state() == s)
                .findFirst().orElse(NullableState.UNSPECIFIED);
        int n = Math.max(a.arguments().size(), b.arguments().size());
        List<ValueImpl.NullabilityImpl> arguments = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            arguments.add(combine(argument(a, i), argument(b, i), preferred));
        }
        return new ValueImpl.NullabilityImpl(state, arguments);
    }

    private static Value.Nullability argument(Value.Nullability nullability, int i) {
        return i < nullability.arguments().size() ? nullability.arguments().get(i) : ValueImpl.NullabilityImpl.UNSPECIFIED;
    }

    private static final ValueOrigin MUTABLE_DEFAULT = new ValueOrigin(ValueImpl.ImmutableImpl.MUTABLE, DEFAULT);

    private ValueOrigin computeMethodImmutable(MethodInfo methodInfo) {
        ParameterizedType returnType = methodInfo.returnType();
        Immutable immutable = analysisHelper.typeImmutable(returnType);
        if (immutable == null) {
            LOGGER.warn("No immutable value for {}", returnType);
            return MUTABLE_DEFAULT;
        }
        return immutable.equals(ValueImpl.ImmutableImpl.MUTABLE) ? MUTABLE_DEFAULT : new ValueOrigin(immutable, FROM_TYPE);
    }


    private ValueOrigin computeMethodIndependent(MethodInfo methodInfo, Map<Property, ValueOrigin> map) {
        ValueOrigin returnValueIndependentVo = computeMethodIndependentReturnValue(methodInfo, map);
        Value.Independent returnValueIndependent = (Independent) returnValueIndependentVo.value();

        // typeIndependent is set by hand in AnnotatedAPI files
        Value.Independent typeIndependent = methodInfo.typeInfo().analysis().getOrDefault(INDEPENDENT_TYPE, DEPENDENT);
        Value.Independent bestOfOverrides = methodInfo.overrides().stream()
                .filter(MethodInfo::isPublic)
                .map(mi -> mi.analysis().getOrDefault(INDEPENDENT_METHOD, DEPENDENT))
                .reduce(DEPENDENT, Value.Independent::max);
        ShallowAnalyzer.AnnotationOrigin origin;
        Value.Independent max = returnValueIndependent.max(bestOfOverrides).max(typeIndependent);
        if (max.isDependent()) {
            origin = DEFAULT;
        } else if (typeIndependent.equals(max)) {
            origin = FROM_OWNER;
        } else if (bestOfOverrides.equals(max)) {
            origin = FROM_OVERRIDE;
        } else {
            origin = FROM_TYPE;
        }
        if (max.isIndependentHc() && methodInfo.isFactoryMethod()) {
            // at least one of the parameters must be independent HC!!
            boolean hcParam = methodInfo.parameters().stream()
                    .anyMatch(pa -> pa.analysis().getOrDefault(INDEPENDENT_PARAMETER, DEPENDENT).isIndependentHc());
            if (!hcParam) {
                LOGGER.warn("@Independent(hc=true) factory method must have at least one @Independent(hc=true) parameter");
            }
        }
        return new ValueOrigin(max, origin);
    }

    private static final ValueOrigin INDEPENDENT_FROM_METHOD = new ValueOrigin(INDEPENDENT, FROM_METHOD);
    private static final ValueOrigin INDEPENDENT_FROM_TYPE = new ValueOrigin(INDEPENDENT, FROM_TYPE);
    private static final ValueOrigin INDEPENDENT_HC_FROM_TYPE = new ValueOrigin(INDEPENDENT_HC, FROM_TYPE);
    private static final ValueOrigin DEPENDENT_DEFAULT = new ValueOrigin(DEPENDENT, DEFAULT);

    private ValueOrigin computeMethodIndependentReturnValue(MethodInfo methodInfo, Map<Property, ValueOrigin> map) {
        if (methodInfo.isConstructor() || methodInfo.isVoid()) {
            return INDEPENDENT_FROM_METHOD;
        }
        if (methodInfo.isStatic() && !methodInfo.isFactoryMethod()) {
            // if factory method, we link return value to parameters, otherwise independent by default
            return INDEPENDENT_FROM_METHOD;
        }
        ValueOrigin identity = map.get(IDENTITY_METHOD);
        ValueOrigin modified = map.get(NON_MODIFYING_METHOD);
        if (identity.valueAsBool().isTrue() && modified.valueAsBool().isFalse()) {
            return INDEPENDENT_FROM_METHOD; // @Identity + @NotModified -> must be @Independent
        }
        // from here on we're assuming the result is linked to the fields.

        ParameterizedType pt = methodInfo.returnType();
        if (pt.arrays() > 0) {
            // array type, like int[]
            return DEPENDENT_DEFAULT;
        }
        TypeInfo bestType = pt.bestTypeInfo();
        if (bestType == null || bestType.isJavaLangObject()) {
            // unbound type parameter T, or unbound with array T[], T[][]
            return INDEPENDENT_HC_FROM_TYPE;
        }
        if (bestType.isPrimitiveExcludingVoid()) {
            return INDEPENDENT_FROM_TYPE;
        }
        Value.Immutable imm = (Immutable) map.get(IMMUTABLE_METHOD).value();
        if (imm.isAtLeastImmutableHC()) {
            return new ValueOrigin(imm.toCorrespondingIndependent(), FROM_TYPE);
        }
        return DEPENDENT_DEFAULT;
    }


    private void methodPropertiesBeforeParameters(MethodInfo methodInfo,
                                                  Map<Property, ValueOrigin> map,
                                                  boolean explicitlyEmpty) {
        if (methodInfo.isConstructor()) {
            map.put(FLUENT_METHOD, DEFAULT_FALSE);
            map.put(IDENTITY_METHOD, DEFAULT_FALSE);
            map.put(NON_MODIFYING_METHOD, DEFAULT_FALSE);
            map.putIfAbsent(METHOD_ALLOWS_INTERRUPTS, DEFAULT_FALSE);
        } else {
            ValueOrigin fluent = map.get(FLUENT_METHOD);
            if (fluent != null) {
                if (fluent.valueAsBool().isTrue() && explicitlyEmpty) {
                    LOGGER.warn("Impossible! how can a method without statements be @Fluent?");
                }
            } else {
                map.put(FLUENT_METHOD, explicitlyEmpty ? DEFAULT_FALSE : computeMethodFluent(methodInfo));
            }
            ValueOrigin identity = map.get(IDENTITY_METHOD);
            if (identity != null) {
                if (identity.valueAsBool().isTrue() && explicitlyEmpty) {
                    LOGGER.warn("Impossible! how can a method without statements be @Identity?");
                }
                if (identity.valueAsBool().isTrue() && (methodInfo.parameters().isEmpty()
                                                        || !methodInfo.returnType().equals(methodInfo.parameters()
                        .getFirst().parameterizedType()))) {
                    LOGGER.warn("@Identity method must have return type identical to formal type of first parameter");
                }
            } else {
                map.put(IDENTITY_METHOD, explicitlyEmpty || methodInfo.parameters().isEmpty()
                        ? DEFAULT_FALSE : computeMethodIdentity(methodInfo));
            }

            ValueOrigin ignoreModification = map.get(IGNORE_MODIFICATION_METHOD);
            if (ignoreModification != null) {
                if (ignoreModification.valueAsBool().isTrue() && explicitlyEmpty) {
                    messages.add(MessageImpl.warn(methodInfo,
                            "Impossible! how can a method without statements need @IgnoreModifications?"));
                }
            } else {
                map.put(IGNORE_MODIFICATION_METHOD, explicitlyEmpty ? DEFAULT_FALSE
                        : computeIgnoreModificationMethod(methodInfo));
            }

            ValueOrigin nonModifying = map.get(NON_MODIFYING_METHOD);
            if ((nonModifying == null || nonModifying.valueAsBool().isFalse()) && explicitlyEmpty) {
                messages.add(MessageImpl.warn(methodInfo,
                        "Impossible! how can a method without statements be @Modified?"));
            }
            if (nonModifying == null) {
                map.put(NON_MODIFYING_METHOD, explicitlyEmpty ? FROM_METHOD_TRUE
                        : computeMethodNonModifying(methodInfo, map));
            }
            ValueOrigin allowsInterrupt = map.get(METHOD_ALLOWS_INTERRUPTS);
            if (allowsInterrupt != null) {
                if (allowsInterrupt.valueAsBool().isTrue() && explicitlyEmpty) {
                    messages.add(MessageImpl.warn(methodInfo,
                            "Impossible! how can a method without statements be @AllowInterrupt?"));
                }
            } else {
                map.put(METHOD_ALLOWS_INTERRUPTS, explicitlyEmpty ? DEFAULT_FALSE : computeAllowInterrupt(methodInfo));
            }
            ValueOrigin getSetField = map.get(GET_SET_FIELD);
            if (getSetField == null) {
                Value.FieldValue fromOverride = getSetFromOverride(methodInfo);
                if (fromOverride != null) {
                    map.put(GET_SET_FIELD, new ValueOrigin(fromOverride, FROM_OVERRIDE));
                }
            }
            // is on constructors, and factory methods, which don't get overloaded, and also with "equivalent = true"
            ValueOrigin getSetEquivalent = map.get(GET_SET_EQUIVALENT);
            if (getSetEquivalent == null) {
                Value.GetSetEquivalent fromOverride = getSetEquivalentFromOverride(methodInfo);
                if (fromOverride != null) {
                    map.put(GET_SET_EQUIVALENT, new ValueOrigin(fromOverride, FROM_OVERRIDE));
                }
            }
        }
    }

    private FieldValue getSetFromOverride(MethodInfo methodInfo) {
        for (MethodInfo override : methodInfo.overrides()) {
            FieldValue fv = override.getSetField();
            if (fv != null && fv.field() != null) return fv;
        }
        return null;
    }

    private GetSetEquivalent getSetEquivalentFromOverride(MethodInfo methodInfo) {
        for (MethodInfo override : methodInfo.overrides()) {
            GetSetEquivalent fv = override.getSetEquivalents();
            if (fv != null && !fv.isDefault()) return fv;
        }
        return null;
    }

    private Map<Property, ValueOrigin> handleParameter(ParameterInfo parameterInfo,
                                                       Map<Property, ValueOrigin> methodMap,
                                                       boolean explicitlyEmpty) {
        List<AnnotationExpression> annotations = annotationProvider.annotations(parameterInfo);
        Map<Property, Value> annotatedMap = annotationsToMap(parameterInfo, annotations);
        Map<Property, ValueOrigin> map = new HashMap<>();
        annotatedMap.forEach((p, v) -> map.put(p, new ValueOrigin(v, ShallowAnalyzer.AnnotationOrigin.ANNOTATED)));

        if (explicitlyEmpty) {
            ValueOrigin ind = map.get(INDEPENDENT_PARAMETER);
            if (ind != null && !ind.value().equals(INDEPENDENT)) {
                LOGGER.warn("Parameter {} must be independent", parameterInfo);
            }
            map.put(INDEPENDENT_PARAMETER, INDEPENDENT_FROM_METHOD);
            map.put(UNMODIFIED_PARAMETER, FROM_METHOD_TRUE);
            Value.Nullability ofType = analysisHelper.nullabilityOfType(parameterInfo.parameterizedType());
            map.put(NULLABILITY_PARAMETER, ofType.isDefault() ? UNSPECIFIED_DEFAULT : new ValueOrigin(ofType, FROM_TYPE));
            map.putIfAbsent(IGNORE_MODIFICATIONS_PARAMETER, DEFAULT_FALSE);
        } else {
            ValueOrigin imm = map.get(IMMUTABLE_PARAMETER);
            if (imm == null) {
                Immutable value = analysisHelper.typeImmutable(parameterInfo.parameterizedType());
                if (value == null) {
                    LOGGER.warn("Have no @Immutable value for {}", parameterInfo.parameterizedType());
                    value = ValueImpl.ImmutableImpl.MUTABLE;
                }
                map.put(IMMUTABLE_PARAMETER, value.equals(ValueImpl.ImmutableImpl.MUTABLE) ? MUTABLE_DEFAULT
                        : new ValueOrigin(value, FROM_TYPE));
            }

            ValueOrigin ind = map.get(INDEPENDENT_PARAMETER);
            if (ind == null) {
                map.put(INDEPENDENT_PARAMETER, computeParameterIndependent(parameterInfo, methodMap, map));
            }
            ValueOrigin ign = map.get(IGNORE_MODIFICATIONS_PARAMETER);
            ValueOrigin ignComputed;
            if (ign == null) {
                ignComputed = computeParameterIgnoreModifications(parameterInfo);
                map.put(IGNORE_MODIFICATIONS_PARAMETER, ignComputed);
            } else {
                ignComputed = ign;
            }
            ValueOrigin mod = map.get(UNMODIFIED_PARAMETER);
            if (mod == null) {
                map.put(UNMODIFIED_PARAMETER, computeParameterUnmodified(parameterInfo, ignComputed));
            }
            ValueOrigin nn = map.get(NULLABILITY_PARAMETER);
            if (nn == null) {
                map.put(NULLABILITY_PARAMETER, computeParameterNullability(parameterInfo));
            }
            ValueOrigin c = map.get(CONTAINER_PARAMETER);
            if (c == null) {
                map.put(CONTAINER_PARAMETER, computeParameterContainer(parameterInfo));
            }
        }
        return map;
    }

    private ValueOrigin computeParameterContainer(ParameterInfo parameterInfo) {
        Value.Bool container = analysisHelper.typeContainer(parameterInfo.parameterizedType());
        return container.isFalse() ? DEFAULT_FALSE : FROM_TYPE_TRUE;
    }

    private ValueOrigin computeParameterIgnoreModifications(ParameterInfo parameterInfo) {
        ParameterizedType pt = parameterInfo.parameterizedType();
        return FunctionTypes.isLibraryFunctionType(pt) ? FROM_TYPE_TRUE : DEFAULT_FALSE;
    }

    // a parameter has one nullability along its override chain (Kotlin's rule): nullable when an overridden method
    // accepts null, else non-null when one demands non-null, else unspecified
    private ValueOrigin computeParameterNullability(ParameterInfo parameterInfo) {
        ParameterizedType pt = parameterInfo.parameterizedType();
        if (pt.isPrimitiveExcludingVoid()) return NONNULL_FROM_TYPE;
        Set<NullableState> overridden = parameterInfo.methodInfo().overrides().stream()
                .filter(MethodInfo::isPublic)
                .map(mi -> mi.parameters().get(parameterInfo.index()))
                .map(pi -> pi.analysis().getOrDefault(NULLABILITY_PARAMETER, ValueImpl.NullabilityImpl.UNSPECIFIED)
                        .state())
                .collect(Collectors.toUnmodifiableSet());
        for (NullableState state : PARAMETER_PRIORITY) {
            if (overridden.contains(state)) {
                return new ValueOrigin(inherited(state, pt, parameterInfo.methodInfo().overrides().stream()
                        .filter(MethodInfo::isPublic)
                        .map(mi -> mi.parameters().get(parameterInfo.index()))
                        .map(pi -> new Declared(pi.parameterizedType(), pi.analysis().getOrDefault(NULLABILITY_PARAMETER,
                                ValueImpl.NullabilityImpl.UNSPECIFIED)))
                        .toList(), PARAMETER_PRIORITY), FROM_OVERRIDE);
            }
        }
        return UNSPECIFIED_DEFAULT;
    }

    private ValueOrigin computeParameterUnmodified(ParameterInfo parameterInfo, ValueOrigin ign) {
        MethodInfo methodInfo = parameterInfo.methodInfo();
        if (((Value.Bool) ign.value()).isTrue()) {
            return new ValueOrigin(FALSE, ign.origin());
        }
        Value.Bool typeContainer = methodInfo.typeInfo().analysis().getOrDefault(CONTAINER_TYPE, FALSE);
        if (typeContainer.isTrue()) {
            return FROM_OWNER_TRUE;
        }
        ParameterizedType type = parameterInfo.parameterizedType();
        if (type.isPrimitiveStringClass()) {
            return FROM_TYPE_TRUE;
        }
        Value.Immutable typeImmutable = analysisHelper.typeImmutable(type);
        if (typeImmutable != null && typeImmutable.isAtLeastImmutableHC()) {
            return FROM_TYPE_TRUE;
        }
        Value.Bool override = methodInfo.overrides().stream()
                .map(mi -> mi.parameters().get(parameterInfo.index()))
                .map(pi -> pi.analysis().getOrDefault(UNMODIFIED_PARAMETER, ValueImpl.BoolImpl.NO_VALUE))
                .reduce(ValueImpl.BoolImpl.NO_VALUE, Value.Bool::or);
        if (override.isTrue()) {
            return FROM_OVERRIDE_TRUE;
        }
        return DEFAULT_FALSE;
    }

    private ValueOrigin computeParameterIndependent(ParameterInfo parameterInfo,
                                                    Map<Property, ValueOrigin> methodMap,
                                                    Map<Property, ValueOrigin> map) {
        ParameterizedType type = parameterInfo.parameterizedType();
        Value.Immutable immutable = (Immutable) map.get(PropertyImpl.IMMUTABLE_PARAMETER).value();
        MethodInfo methodInfo = parameterInfo.methodInfo();

        if (type.isPrimitiveExcludingVoid() || immutable.isImmutable()) {
            return INDEPENDENT_FROM_TYPE;
        }


        Value.Bool nonModifyingMethod = methodMap.get(PropertyImpl.NON_MODIFYING_METHOD).valueAsBool();
        if (nonModifyingMethod.isTrue() && !methodInfo.isFactoryMethod()) {
            return INDEPENDENT_FROM_METHOD;
        }

        // note that an unbound type parameter is by default @Dependent, not @Independent1!!
        Value.Independent independentFromImmutable = immutable.toCorrespondingIndependent();
        TypeInfo ownerType = parameterInfo.methodInfo().typeInfo();
        Value.Independent independentType = ownerType.analysis().getOrDefault(INDEPENDENT_TYPE, DEPENDENT);
        Value.Independent override = methodInfo.overrides().stream()
                .filter(MethodInfo::isPublic)
                .map(mi -> mi.parameters().get(parameterInfo.index()))
                .filter(pi -> pi.analysis().haveAnalyzedValueFor(INDEPENDENT_PARAMETER))
                .map(pi -> pi.analysis().getOrDefault(INDEPENDENT_PARAMETER, DEPENDENT))
                .reduce(DEPENDENT, Value.Independent::max);
        Value.Independent max = override.max(independentType.max(independentFromImmutable));
        if (max.isDependent()) return DEPENDENT_DEFAULT;
        if (!max.equals(override)) return new ValueOrigin(max, FROM_TYPE);
        return new ValueOrigin(max, FROM_OVERRIDE);
    }


    private ValueOrigin computeMethodNonModifying(MethodInfo methodInfo, Map<Property, ValueOrigin> map) {
        if (methodInfo.isConstructor()) return DEFAULT_FALSE; // almost by default, constructors modify the fields
        ValueOrigin fluent = map.get(FLUENT_METHOD);
        if (fluent != null && fluent.valueAsBool().isTrue()) return DEFAULT_FALSE; // modifying--what else would it do?

        Value.Immutable typeImmutable = methodInfo.typeInfo().analysis().getOrDefault(IMMUTABLE_TYPE,
                ValueImpl.ImmutableImpl.MUTABLE);
        if (typeImmutable.isAtLeastImmutableHC()) return FROM_TYPE_TRUE;

        return commonBooleanFromOverride(NON_MODIFYING_METHOD, methodInfo);
    }

    private ValueOrigin computeMethodFluent(MethodInfo methodInfo) {
        if (methodInfo.returnType().typeInfo() != methodInfo.typeInfo()) return DEFAULT_FALSE;
        return commonBooleanFromOverride(FLUENT_METHOD, methodInfo);
    }

    private ValueOrigin computeMethodIdentity(MethodInfo methodInfo) {
        return commonBooleanFromOverride(IDENTITY_METHOD, methodInfo);
    }

    private ValueOrigin computeIgnoreModificationMethod(MethodInfo methodInfo) {
        return commonBooleanFromOverride(IGNORE_MODIFICATION_METHOD, methodInfo);
    }

    private ValueOrigin computeAllowInterrupt(MethodInfo methodInfo) {
        return commonBooleanFromOverride(METHOD_ALLOWS_INTERRUPTS, methodInfo);
    }

    private ValueOrigin commonBooleanFromOverride(Property property, MethodInfo methodInfo) {
        Value.Bool v = FALSE;
        for (MethodInfo mi : methodInfo.overrides()) {
            if (mi.isPublic()) {
                Value.Bool o = mi.analysis().getOrNull(property, ValueImpl.BoolImpl.class);
                if (o != null) {
                    v = v.or(o);
                }
            }
        }
        return v.isFalse() ? DEFAULT_FALSE : FROM_OVERRIDE_TRUE;
    }

    public List<Message> messages() {
        return messages;
    }
}
