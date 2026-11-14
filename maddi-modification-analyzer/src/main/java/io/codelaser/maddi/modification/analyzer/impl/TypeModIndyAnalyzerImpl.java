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

package io.codelaser.maddi.modification.analyzer.impl;

import static io.codelaser.maddi.cst.impl.analysis.PropertyImpl.*;
import static io.codelaser.maddi.cst.impl.analysis.ValueImpl.BoolImpl.FALSE;
import static io.codelaser.maddi.cst.impl.analysis.ValueImpl.BoolImpl.TRUE;
import static io.codelaser.maddi.cst.impl.analysis.ValueImpl.IndependentImpl.*;
import static io.codelaser.maddi.modification.link.impl.MethodLinkedVariablesImpl.METHOD_LINKS;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

import io.codelaser.maddi.cst.api.analysis.Message;
import io.codelaser.maddi.cst.api.analysis.Value;
import io.codelaser.maddi.cst.api.element.Element;
import io.codelaser.maddi.cst.api.expression.Assignment;
import io.codelaser.maddi.cst.api.expression.Cast;
import io.codelaser.maddi.cst.api.expression.ConstructorCall;
import io.codelaser.maddi.cst.api.expression.EnclosedExpression;
import io.codelaser.maddi.cst.api.expression.Expression;
import io.codelaser.maddi.cst.api.expression.InlineConditional;
import io.codelaser.maddi.cst.api.expression.Lambda;
import io.codelaser.maddi.cst.api.expression.MethodCall;
import io.codelaser.maddi.cst.api.expression.VariableExpression;
import io.codelaser.maddi.cst.api.info.FieldInfo;
import io.codelaser.maddi.cst.api.info.MethodInfo;
import io.codelaser.maddi.cst.api.info.ParameterInfo;
import io.codelaser.maddi.cst.api.info.TypeInfo;
import io.codelaser.maddi.cst.api.statement.LocalVariableCreation;
import io.codelaser.maddi.cst.api.statement.ReturnStatement;
import io.codelaser.maddi.cst.api.statement.Statement;
import io.codelaser.maddi.cst.api.type.ParameterizedType;
import io.codelaser.maddi.cst.api.variable.FieldReference;
import io.codelaser.maddi.cst.api.variable.LocalVariable;
import io.codelaser.maddi.cst.api.variable.This;
import io.codelaser.maddi.cst.api.variable.Variable;
import io.codelaser.maddi.cst.impl.analysis.PropertyImpl;
import io.codelaser.maddi.cst.impl.analysis.ValueImpl;
import io.codelaser.maddi.modification.analyzer.IteratingAnalyzer;
import io.codelaser.maddi.modification.analyzer.TypeModIndyAnalyzer;
import io.codelaser.maddi.modification.common.AnalysisHelper;
import io.codelaser.maddi.modification.common.util.Culprits;
import io.codelaser.maddi.modification.common.util.TolerantWrite;
import io.codelaser.maddi.modification.link.impl.LinkNatureImpl;
import io.codelaser.maddi.modification.link.impl.MethodLinkedVariablesImpl;
import io.codelaser.maddi.modification.prepwork.Util;
import io.codelaser.maddi.modification.prepwork.variable.Link;
import io.codelaser.maddi.modification.prepwork.variable.Links;
import io.codelaser.maddi.modification.prepwork.variable.MethodLinkedVariables;

/*
Phase 3.

Does modification and independence of parameters, and independence, fluent, identity of methods.
Also breaks internal cycles.

Modification of methods and linking of variables is done in Phase 1.
Linking, modification and independence of fields is done in Phase 2.
Immutable, independence of types is done in Phase 4.1.

Strategy:
method independence, parameter independence can directly be read from linked variables computed in field analyzer.
parameter modification is computed as the combination of links to fields and local modifications.

 */
public class TypeModIndyAnalyzerImpl extends CommonAnalyzerImpl implements TypeModIndyAnalyzer {
    // see worstLinkToFields
    private static final boolean CB_METHOD_PESSIMISM = System.getenv("NOCBMETHODPESSIMISM") == null;

    private final AnalysisHelper analysisHelper = new AnalysisHelper();

    public TypeModIndyAnalyzerImpl(IteratingAnalyzer.Configuration configuration, AtomicInteger propertiesChanged, List<Message> analyzerMessages) {
        super(configuration, propertiesChanged, analyzerMessages);
    }

    @Override
    public void go(TypeInfo typeInfo, boolean cycleBreakingActive) {
        typeInfo.constructorAndMethodStream().filter(mi -> !mi.isAbstract())
                .forEach(mi -> go(cycleBreakingActive, mi));
        fromNonFinalFieldToParameter(typeInfo);
    }

    private void go(boolean cycleBreakingActive, MethodInfo methodInfo) {
        FieldValue fieldValue = methodInfo.analysis().getOrDefault(GET_SET_FIELD, ValueImpl.GetSetValueImpl.EMPTY);
        if (fieldValue.field() != null) {
            handleGetterSetter(methodInfo, fieldValue);
            // only a setter ending in 'return this' returns a value (GetSetHelper.checkSetMethodEnsureFluent)
            accessorOrEmptyFluent(methodInfo, fieldValue.setter() && methodInfo.hasReturnValue());
        } else if (methodInfo.explicitlyEmptyMethod()) {
            handleExplicitlyEmptyMethod(methodInfo);
            accessorOrEmptyFluent(methodInfo, false);
        } else if (!methodInfo.methodBody().isEmpty()) {
            handleNormalMethod(cycleBreakingActive, methodInfo);
        } else {
            accessorOrEmptyFluent(methodInfo, false);
        }
    }

    /*
     Prep (GetSetHelper) decides @Fluent of an accessor and of an empty method, and doFluentAnalysis only sees the
     others. A seeded analysis clears the derived tier before it re-analyses an element (ClearDerived), fluentMethod
     included, and prep does not run again on it: the value was gone, the element's summary differed from the
     previous generation's, and the early cutoff fell back to the cold analysis (fernflower,
     BytecodeSourceMapper.addTotalOffset, on a comment-only edit). Write the same value prep writes; on a cold run
     it is already there and nothing changes.
     */
    private void accessorOrEmptyFluent(MethodInfo methodInfo, boolean fluent) {
        if (methodInfo.isConstructor()) return;
        if (TolerantWrite.setAllowControlledOverwrite(methodInfo.analysis(), FLUENT_METHOD,
                ValueImpl.BoolImpl.from(fluent), methodInfo)) {
            DECIDE.debug("MI: Decide @Fluent of accessor or empty method {} = {}", methodInfo, fluent);
            propertyChanges.incrementAndGet();
        }
    }

    private void handleNormalMethod(boolean cycleBreakingActive, MethodInfo methodInfo) {
        Statement lastStatement = methodInfo.methodBody().lastStatement();
        assert lastStatement != null;
        MethodLinkedVariables mlv = methodInfo.analysis().getOrNull(METHOD_LINKS, MethodLinkedVariablesImpl.class);
        if (mlv != null) {
            doIdentityAnalysis(methodInfo, mlv);
            doUnmodifiableView(cycleBreakingActive, methodInfo);
            doFluentAnalysis(methodInfo, mlv);
            doIndependent(cycleBreakingActive, methodInfo, mlv);

            for (ParameterInfo pi : methodInfo.parameters()) {
                handleParameter(cycleBreakingActive, pi, mlv);
            }
        } else {
            UNDECIDED.debug("MI: method {} delayed, no MLV", methodInfo);
        }
    }

    // gate NOALLVALUESFLUENT: fluent when any return link reaches this (the old rule, A/B)
    // gate NOVIEWOFTYPEPARAMETER: a call whose formal result is a type parameter is a view by the callee's verdict
    private static final boolean NO_VIEW_OF_TYPE_PARAMETER = System.getenv("NOVIEWOFTYPEPARAMETER") != null;
    private static final boolean ALL_VALUES_FLUENT_OFF = System.getenv("NOALLVALUESFLUENT") != null;
    private static final boolean NO_FLUENT_RETURNS = System.getenv("NOFLUENTRETURNS") != null;

    private static boolean isThis(MethodInfo methodInfo, Variable v) {
        return v instanceof This thisVar
               && (thisVar.typeInfo() == methodInfo.typeInfo()
                   || thisVar.typeInfo().typeHierarchyExcludingJLOStream().anyMatch(h -> h.equals(methodInfo.typeInfo()))
                   // 'super' / 'I.super': the same object, seen as a supertype (maddi-mod#31)
                   || thisVar.writeSuper() && methodInfo.typeInfo().typeHierarchyExcludingJLOStream()
                           .anyMatch(h -> h.equals(thisVar.typeInfo())));
    }

    private void doFluentAnalysis(MethodInfo methodInfo, MethodLinkedVariables mlv) {
        boolean identityFluent;
        // a primitive cannot be the receiver (maddi-mod#31: javaparser's 'boolean Node.remove(Node)' came out @Fluent)
        if (methodInfo.hasReturnValue() && !methodInfo.returnType().isPrimitiveExcludingVoid()
            && !mlv.ofReturnValue().isEmpty()) {
            if (ALL_VALUES_FLUENT_OFF) {
                identityFluent = mlv.ofReturnValue().stream().anyMatch(v -> isThis(methodInfo, v.to()));
            } else {
                // fluent: every value the method returns is 'this'. Not 'c ? this : next' ('←this.next,←this'),
                // nor a field of this ('getWrapper ≺ this'), nor a face of the result ('either.next ← this.next')
                Variable rv = mlv.ofReturnValue().primary();
                List<Variable> values = mlv.ofReturnValue().stream()
                        .filter(v -> v.from().equals(rv)
                                     && (v.linkNature().isIdenticalTo() || v.linkNature().isAssignedFrom()))
                        .map(Link::to).toList();
                identityFluent = !values.isEmpty() && values.stream().allMatch(v -> isThis(methodInfo, v))
                                 // the links do not see a return of something unlinked (a new object): every
                                 // return must yield this (maddi-mod#31); gate NOFLUENTRETURNS
                                 && (NO_FLUENT_RETURNS || everyReturnYields(methodInfo, v -> isThis(methodInfo, v)));
            }
        } else {
            identityFluent = false;
        }
        if (TolerantWrite.setAllowControlledOverwrite(methodInfo.analysis(), FLUENT_METHOD, ValueImpl.BoolImpl.from(identityFluent), methodInfo)) {
            DECIDE.debug("MI: Decide @Fluent of {} = {}", methodInfo, identityFluent);
            propertyChanges.incrementAndGet();
        }
    }

    /**
     * UNMODIFIABLE_VIEW_METHOD (CodeLaser/maddi-mod#52), computed from the values the body returns: every one is an
     * unmodifiable view (a call of a method with the property), an instance of an UNMODIFIABLE_TYPE, at least
     * immutable-HC (its type, or a call whose result is, as DynamicImmutabilityInference reads it), a field that
     * provably holds one, or null. Null is neutral, not sufficient: a body that returns only null is FALSE, or every
     * {@code return null;} default an override replaces would claim a view (fernflower's
     * {@code Exprent.checkExprTypeBounds}). A source callee not yet decided delays the verdict; under cycle breaking
     * it is FALSE, the safe side. Mirrors nothing about the container aspect, and never writes IMMUTABLE_METHOD.
     */
    private void doUnmodifiableView(boolean cycleBreakingActive, MethodInfo methodInfo) {
        if (methodInfo.isConstructor() || !methodInfo.hasReturnValue() || methodInfo.returnType().isPrimitiveExcludingVoid()
            || methodInfo.returnType().arrays() > 0 || methodInfo.returnType().typeParameter() != null) {
            return;
        }
        List<Expression> returned = new ArrayList<>();
        methodInfo.methodBody().visit((Element e) -> {
            if (e instanceof Lambda) return false; // its returns are its own
            if (e instanceof ReturnStatement rs && rs.expression() != null
                && !rs.expression().isEmpty()) {
                returned.add(rs.expression());
            }
            return true;
        });
        Boolean result = returned.stream().allMatch(TypeModIndyAnalyzerImpl::onlyNull) ? Boolean.FALSE : Boolean.TRUE;
        for (int i = 0; !Boolean.FALSE.equals(result) && i < returned.size(); i++) {
            Boolean one = unmodifiableValue(returned.get(i), 0);
            if (one == null) result = null;
            else if (!one) result = false;
        }
        if (result == null && cycleBreakingActive) result = false;
        if (result == null) {
            UNDECIDED.debug("MI: unmodifiable view of {} delayed", methodInfo);
            return;
        }
        if (TolerantWrite.setAllowControlledOverwrite(methodInfo.analysis(), UNMODIFIABLE_VIEW_METHOD,
                ValueImpl.BoolImpl.from(result), methodInfo)) {
            DECIDE.debug("MI: Decide @UnmodifiableView of {} = {}", methodInfo, result);
            propertyChanges.incrementAndGet();
        }
    }

    /** Whether every value the expression can take is the null constant (true for an empty stream of returns). */
    private static boolean onlyNull(Expression value) {
        return switch (value) {
            case EnclosedExpression ee -> onlyNull(ee.inner());
            case Cast cast -> onlyNull(cast.expression());
            case InlineConditional ic -> onlyNull(ic.ifTrue()) && onlyNull(ic.ifFalse());
            default -> value.isNullConstant();
        };
    }

    /** TRUE, FALSE, or null when a source callee has not been decided yet. */
    private Boolean unmodifiableValue(Expression value, int depth) {
        if (value.isNullConstant()) return true;
        if (depth > 3) return false;
        switch (value) {
            case EnclosedExpression ee -> { return unmodifiableValue(ee.inner(), depth + 1); }
            case Cast cast -> { return unmodifiableValue(cast.expression(), depth + 1); }
            case InlineConditional ic -> {
                Boolean t = unmodifiableValue(ic.ifTrue(), depth + 1);
                if (t != null && !t) return false;
                Boolean f = unmodifiableValue(ic.ifFalse(), depth + 1);
                if (f != null && !f) return false;
                return t == null || f == null ? null : Boolean.TRUE;
            }
            default -> { }
        }
        ParameterizedType type = value.parameterizedType();
        if (type != null) {
            Value.Immutable ofType = analysisHelper.typeImmutable(type);
            if (ofType != null && ofType.isAtLeastImmutableHC()) return true;
            if (type.arrays() == 0 && type.typeInfo() != null) {
                Value.Bool unmodifiableType = type.typeInfo().analysis().getOrNull(UNMODIFIABLE_TYPE, ValueImpl.BoolImpl.class);
                if (unmodifiableType != null && unmodifiableType.isTrue()) return true;
                if (unmodifiableType == null && !(value instanceof MethodCall)
                    && type.typeInfo().compilationUnit() != null && type.typeInfo().compilationUnit().sourceSet() != null
                    && !type.typeInfo().compilationUnit().sourceSet().externalLibrary()) {
                    return null; // a source type: its verdict arrives with the type analyzers
                }
            }
        }
        if (value instanceof MethodCall mc && mc.methodInfo() != null) {
            MethodInfo callee = mc.methodInfo();
            // a callee whose result is a type parameter ('map.get(k)', 'list.get(i)'): its own verdict is about the
            // hidden content, immutable-HC as far as it knows; what the result is here is the concrete type's
            // business, decided above when at least immutable-HC. A mutable element is no view (fernflower's
            // StructContext.getClass, 'classes.get(name)', whose StructClass getPool() loads lazily)
            if (!NO_VIEW_OF_TYPE_PARAMETER && callee.returnType().isTypeParameter() && type != null
                && !type.isTypeParameter()) {
                Value.Immutable ofType = analysisHelper.typeImmutable(type);
                return ofType == null ? null : Boolean.FALSE;
            }
            Boolean ofOverrides = unmodifiableOverrides(callee);
            if (Boolean.FALSE.equals(ofOverrides)) return false;
            Boolean ofCallee = unmodifiableCallee(callee);
            return ofCallee == null || !ofCallee ? ofCallee : ofOverrides;
        }
        if (value instanceof VariableExpression ve
            && ve.variable() instanceof FieldReference fr) {
            Value.Immutable ofField = fr.fieldInfo().analysis().getOrNull(IMMUTABLE_FIELD, ValueImpl.ImmutableImpl.class);
            return ofField != null && ofField.isAtLeastImmutableHC();
        }
        return false;
    }

    /** The callee's own verdict, as a call result: TRUE, FALSE, or null while a source callee is undecided. */
    private static Boolean unmodifiableCallee(MethodInfo callee) {
        Value.Bool view = callee.analysis().getOrNull(UNMODIFIABLE_VIEW_METHOD, ValueImpl.BoolImpl.class);
        if (view != null) return view.isTrue();
        Value.Bool identity = callee.analysis().getOrNull(IDENTITY_METHOD, ValueImpl.BoolImpl.class);
        boolean isIdentity = identity != null && identity.isTrue();
        Value.Immutable ofMethod = callee.analysis().getOrNull(IMMUTABLE_METHOD, ValueImpl.ImmutableImpl.class);
        if (!isIdentity && ofMethod != null && ofMethod.isAtLeastImmutableHC()) return true;
        // a source method with a body gets the property from this very pass: wait for it
        boolean sourceWithBody = callee.methodBody() != null && !callee.methodBody().isEmpty()
                                 && callee.typeInfo().compilationUnit() != null
                                 && callee.typeInfo().compilationUnit().sourceSet() != null
                                 && !callee.typeInfo().compilationUnit().sourceSet().externalLibrary();
        return sourceWithBody ? null : Boolean.FALSE;
    }

    /**
     * The overrides a call through a concrete method may dispatch to (CodeLaser/maddi-mod#84): the callee's verdict
     * is its own body's, so the result is a view only when each source override's is one too. TRUE when none
     * says otherwise, FALSE when one does not, null while one is undecided. An abstract method or a placeholder
     * already holds the fold over its implementations (AbstractMethodAnalyzerImpl.methodUnmodifiableView).
     */
    private static Boolean unmodifiableOverrides(MethodInfo callee) {
        if (Util.unionOverImplementations(callee)) return Boolean.TRUE;
        Boolean result = Boolean.TRUE;
        for (MethodInfo override : callee.analysis().getOrDefault(IMPLEMENTATIONS,
                ValueImpl.SetOfMethodInfoImpl.EMPTY).methodInfoSet()) {
            Value.Bool view = override.analysis().getOrNull(UNMODIFIABLE_VIEW_METHOD, ValueImpl.BoolImpl.class);
            if (view == null) result = null;
            else if (view.isFalse()) return Boolean.FALSE;
        }
        return result;
    }

    private void doIdentityAnalysis(MethodInfo methodInfo, MethodLinkedVariables mlv) {
        boolean identity;
        if (methodInfo.hasReturnValue()
            && !methodInfo.parameters().isEmpty()
            && !mlv.ofReturnValue().isEmpty()) {
            Variable primaryFrom = mlv.ofReturnValue().primary();
            Variable p0 = methodInfo.parameters().getFirst();
            // POSITIVE evidence required: a direct rv ≡/← p0 link. The allMatch alone was vacuously true on
            // faces-only summaries (rv.field ← ..., no direct rv link at all) — the shape of every fresh-fill
            // factory 'T q = new T(); q.x = p.y; return q' — certifying methods that return a different type
            // than p0 as @Identity. Consumers alias the call away (jfocus-standardize replaced the result
            // variable by the argument, TestIdentity.test7 / closed-core getConditionItem).
            identity = mlv.ofReturnValue().stream().anyMatch(link -> link.from().equals(primaryFrom)
                                                                     && link.linkNature().isIdenticalToOrAssignedFromTo()
                                                                     && link.to().equals(p0))
                       && mlv.ofReturnValue().stream().allMatch(link -> !link.from().equals(primaryFrom) || link.to().equals(p0))
                       && everyReturnYields(methodInfo, v -> v.equals(p0));
        } else {
            identity = false;
        }
        if (TolerantWrite.setAllowControlledOverwrite(methodInfo.analysis(), IDENTITY_METHOD, ValueImpl.BoolImpl.from(identity), methodInfo)) {
            DECIDE.debug("MI: Decide @Identity of {} = {}", methodInfo, identity);
            propertyChanges.incrementAndGet();
        }
    }

    /*
    The links cannot see a branch that returns something unlinked: in 'return l == null ? List.of() : l', List.of()
    links to nothing, so only 'rv ← 0:l' remains and the link test passes. A consumer that aliases an @Identity call
    away then drops the null guard (refactor-base rewrote 'nonNull(steps).size() == 0' to 'steps.isEmpty()'). So
    every return must yield the parameter itself: the parameter, through casts, parentheses and both branches of a
    conditional; a local variable every assignment of which does; or the first argument of an @Identity call that
    does. Anything else (a constant, null, a new object, another call) is not the parameter. A method without a body
    (shallow, hinted) keeps the link verdict.
    The same holds for @Fluent with 'this' as the target (CodeLaser/maddi-mod#31): 'return isX() ? extendsBound(t)
    : this' links only to this. A call of a @Fluent method on something that yields the target yields it too
    ('return setComment(null)', 'return super.solve(c)').
     */
    private static boolean everyReturnYields(MethodInfo methodInfo, Predicate<Variable> target) {
        if (methodInfo.methodBody() == null || methodInfo.methodBody().isEmpty()) return true;
        List<Expression> returned = new ArrayList<>();
        Map<LocalVariable, List<Expression>> assigned = new java.util.HashMap<>();
        methodInfo.methodBody().visit((Element e) -> {
            if (e instanceof Lambda) return false; // its returns are its own
            if (e instanceof ConstructorCall cc && cc.anonymousClass() != null) return false; // so are its methods'
            if (e instanceof ReturnStatement rs && rs.expression() != null && !rs.expression().isEmpty()) {
                returned.add(rs.expression());
            } else if (e instanceof LocalVariableCreation lvc) {
                List<LocalVariable> lvs = new ArrayList<>();
                lvs.add(lvc.localVariable());
                lvs.addAll(lvc.otherLocalVariables());
                for (LocalVariable lv : lvs) {
                    if (lv.assignmentExpression() != null && !lv.assignmentExpression().isEmpty()) {
                        assigned.computeIfAbsent(lv, _ -> new ArrayList<>()).add(lv.assignmentExpression());
                    }
                }
            } else if (e instanceof Assignment a && a.variableTarget() instanceof LocalVariable lv) {
                assigned.computeIfAbsent(lv, _ -> new ArrayList<>()).add(a.value());
            }
            return true;
        });
        if (returned.isEmpty()) return false;
        return returned.stream().allMatch(r -> yields(r, target, assigned, new HashSet<>()));
    }

    private static boolean yields(Expression expression, Predicate<Variable> target,
                                  Map<LocalVariable, List<Expression>> assigned,
                                  Set<LocalVariable> seen) {
        if (expression instanceof Cast cast) return yields(cast.expression(), target, assigned, seen);
        if (expression instanceof EnclosedExpression enclosed) {
            return yields(enclosed.inner(), target, assigned, seen);
        }
        if (expression instanceof InlineConditional ic) {
            return yields(ic.ifTrue(), target, assigned, seen) && yields(ic.ifFalse(), target, assigned, seen);
        }
        if (expression instanceof VariableExpression ve) {
            if (target.test(ve.variable())) return true;
            if (ve.variable() instanceof LocalVariable lv) {
                if (!seen.add(lv)) return true; // a cycle adds no other value
                List<Expression> values = assigned.get(lv);
                return values != null && !values.isEmpty()
                       && values.stream().allMatch(v -> yields(v, target, assigned, seen));
            }
            return false;
        }
        if (expression instanceof MethodCall mc && mc.methodInfo() != null) {
            if (mc.methodInfo().isIdentity() && !mc.parameterExpressions().isEmpty()
                && yields(mc.parameterExpressions().getFirst(), target, assigned, seen)) {
                return true;
            }
            return mc.methodInfo().isFluent() && mc.object() != null && !mc.object().isEmpty()
                   && yields(mc.object(), target, assigned, seen);
        }
        return false;
    }

    private void handleParameter(boolean cycleBreakingActive, ParameterInfo pi, MethodLinkedVariables mlv) {
        Value.Immutable imm = analysisHelper.typeImmutable(pi.parameterizedType());
        Bool unmodifiedInMethod;
        if (imm.isImmutable()) {
            unmodifiedInMethod = TRUE;
        } else {
            // a parameter handed to one of our functional parameters is recorded, not marked (PassedFunction): it is
            // still modified -- for SOME function -- only its callers may know better
            boolean throughPassedFunction = !pi.analysis().getOrDefault(PropertyImpl.MODIFIED_THROUGH_PASSED_FUNCTION,
                    ValueImpl.SetOfStringsImpl.EMPTY_SET).set().isEmpty();
            unmodifiedInMethod = ValueImpl.BoolImpl.from(!mlv.modified().contains(pi) && !throughPassedFunction);
            Links links = mlv.ofParameters().get(pi.index());
            if (!links.isEmpty() && unmodifiedInMethod.isTrue()) {
                for (Link link : links) {
                    FieldReference fr;
                    if ((fr = relevantLinkForModification(link)) != null) {
                        Bool unmodifiedField = fr.fieldInfo().analysis().getOrNull(UNMODIFIED_FIELD,
                                ValueImpl.BoolImpl.class);
                        if (unmodifiedField == null && !RESPELLED_FIELD_OFF) {
                            FieldInfo declared = declaredField(fr.fieldInfo());
                            if (declared == null) continue;
                            unmodifiedField = declared.analysis().getOrNull(UNMODIFIED_FIELD, ValueImpl.BoolImpl.class);
                        }
                        if (unmodifiedField == null) {
                            unmodifiedInMethod = null;
                            break;
                        } else if (unmodifiedField.isFalse()) {
                            unmodifiedInMethod = FALSE;
                            break;
                        }
                    }
                }
            }
        }
        if (unmodifiedInMethod != null) {
            if (TolerantWrite.setAllowControlledOverwrite(pi.analysis(), UNMODIFIED_PARAMETER, unmodifiedInMethod, pi)) {
                DECIDE.debug("MI: unmodified of parameter {} = {}", pi, unmodifiedInMethod);
                propertyChanges.incrementAndGet();
            }
        } else if (cycleBreakingActive) {
            UNDECIDED.info("MI: Unmodified of parameter {} undecided", pi);
        } else {
            UNDECIDED.debug("MI: Unmodified of parameter {} undecided", pi);
        }
    }

    // gate NORESPELLEDFIELD: a field declared nowhere keeps the parameter undecided (the old rule, A/B)
    private static final boolean RESPELLED_FIELD_OFF = System.getenv("NORESPELLEDFIELD") != null;

    /*
    The link engine respells a real field onto the type of the scope it is read through: a Singleton's 'element' read
    through 'EMPTY', an IList, becomes 'IList.element', a fresh field that IList does not declare, and whose
    modification is therefore never decided. Use the field the owner's hierarchy declares under that name; when there
    is none, the field belongs to a subtype, and its modification reaches the parameter through the call that stored
    the parameter in it (guava's ImmutableList.construct, CodeLaser/maddi-mod#72 4a). Null: no such field.
     */
    private static FieldInfo declaredField(FieldInfo fieldInfo) {
        if (Util.virtual(fieldInfo)) return fieldInfo;
        for (TypeInfo t = fieldInfo.owner(); t != null; ) {
            FieldInfo f = t.getFieldByName(fieldInfo.name(), false);
            if (f != null) return f;
            ParameterizedType parent = t.parentClass();
            t = parent == null ? null : parent.typeInfo();
        }
        return null;
    }

    private FieldReference relevantLinkForModification(Link link) {
        if (link.linkNature().isIdenticalTo()
            && Util.isVirtualModification(link.from())
            && Util.isVirtualModification(link.to())
            && Util.firstRealVariable(link.to()) instanceof FieldReference fr) return fr;
        if (LinkNatureImpl.IS_ASSIGNED_TO.equals(link.linkNature())
            && !Util.virtual(link.from())
            && !Util.virtual(link.to()) && link.to() instanceof FieldReference fr) return fr;
        return null;
    }

    private void handleExplicitlyEmptyMethod(MethodInfo methodInfo) {
        // DISPATCHUNION=1: an empty body overridden by modifying methods is no evidence for a call through it
        boolean union = Util.dispatchUnion(methodInfo);
        Bool nonModifying = union && Util.implementationModifies(methodInfo, false) ? FALSE : TRUE;
        if (TolerantWrite.setAllowControlledOverwrite(methodInfo.analysis(), PropertyImpl.NON_MODIFYING_METHOD, nonModifying, methodInfo)) {
            DECIDE.debug("MI: Decide non-modifying of method {} = {}", methodInfo, nonModifying);
            propertyChanges.incrementAndGet();
        }
        if (TolerantWrite.setAllowControlledOverwrite(methodInfo.analysis(), PropertyImpl.INDEPENDENT_METHOD, INDEPENDENT, methodInfo)) {
            DECIDE.debug("MI: Decide independent of method {} = independent", methodInfo);
            propertyChanges.incrementAndGet();
        }
        for (ParameterInfo pi : methodInfo.parameters()) {
            Bool unmodified = union && Util.implementationModifiesParameter(methodInfo, pi.index(), false) ? FALSE : TRUE;
            if (TolerantWrite.setAllowControlledOverwrite(pi.analysis(), PropertyImpl.UNMODIFIED_PARAMETER, unmodified, pi)) {
                DECIDE.debug("MI: Decide unmodified of parameter {} = {}", pi, unmodified);
                propertyChanges.incrementAndGet();
            }
            if (TolerantWrite.setAllowControlledOverwrite(pi.analysis(), PropertyImpl.INDEPENDENT_PARAMETER, INDEPENDENT, pi)) {
                DECIDE.debug("MI: Decide independent of parameter {} = independent", pi);
                propertyChanges.incrementAndGet();
            }
        }
    }

    private void handleGetterSetter(MethodInfo methodInfo, FieldValue fieldValue) {
        assert !methodInfo.isConstructor();
        // getter, setter
        // DISPATCHUNION=1: a getter overridden by modifying methods is modifying through a call to it
        Bool nonModifying = ValueImpl.BoolImpl.from(!fieldValue.setter()
                && !(Util.dispatchUnion(methodInfo) && Util.implementationModifies(methodInfo, false)));
        if (TolerantWrite.setAllowControlledOverwrite(methodInfo.analysis(), NON_MODIFYING_METHOD, nonModifying, methodInfo)) {
            propertyChanges.incrementAndGet();
        }
        if (fieldValue.setter()
            && TolerantWrite.setAllowControlledOverwrite(methodInfo.analysis(), INDEPENDENT_METHOD, INDEPENDENT, methodInfo)) {
            // a setter returns nothing or this (fluent): independent by definition, whatever the field holds, as
            // doIndependentMethod has it for every method. Setters never reached that, and had no verdict at all
            DECIDE.debug("MI: Decide independent of setter {} = independent", methodInfo);
            propertyChanges.incrementAndGet();
        }
        ParameterizedType type;
        boolean wholeField;
        if (fieldValue.hasIndex() && !fieldValue.list()) {
            type = fieldValue.field().type().copyWithOneFewerArrays();
            wholeField = false; // an indexed getter hands out an ELEMENT, not the container
        } else {
            type = fieldValue.field().type();
            wholeField = true;
        }
        Independent independentFromType = analysisHelper.typeIndependentFromImmutableOrNull(type);
        if (wholeField) {
            // the getter returns what the field holds, so its dynamic immutability decides as much as the
            // declared type does; see DynamicImmutability
            independentFromType = DynamicImmutability.improve(independentFromType, fieldValue.field());
        }
        if (independentFromType == null) {
            UNDECIDED.debug("MI: Independent of method {} undecided", methodInfo);
        } else if (fieldValue.setter()) {
            handleSetter(methodInfo, fieldValue, independentFromType);
        } else {
            handleGetter(methodInfo, independentFromType);
            if (Culprits.ENABLED && independentFromType.isDependent()) {
                DependentCulprit.writeForGetter(methodInfo, fieldValue.field());
            }
        }
    }

    private void handleGetter(MethodInfo methodInfo, Independent independentFromType) {
        if (TolerantWrite.setAllowControlledOverwrite(methodInfo.analysis(), INDEPENDENT_METHOD, independentFromType, methodInfo)) {
            DECIDE.debug("MI: Decide independent of method {} = {}}", methodInfo, independentFromType);
            propertyChanges.incrementAndGet();
        }
        for (ParameterInfo pi : methodInfo.parameters()) {
            if (TolerantWrite.setAllowControlledOverwrite(pi.analysis(), PropertyImpl.UNMODIFIED_PARAMETER, TRUE, pi)) {
                DECIDE.debug("MI: Decide unmodified of getter parameter: {} = true", pi);
                propertyChanges.incrementAndGet();
            }
            if (TolerantWrite.setAllowControlledOverwrite(pi.analysis(), INDEPENDENT_PARAMETER, INDEPENDENT, pi)) {
                DECIDE.debug("MI: Decide @Independent of getter parameter: {} = true", pi);
                propertyChanges.incrementAndGet();
            }
        }
    }

    private void handleSetter(MethodInfo methodInfo, FieldValue fieldValue, Independent independentFromType) {
        if (independentFromType.isIndependent()) {
            // must be unmodified, otherwise, we'll have to wait for a value to come from the field
            for (ParameterInfo pi : methodInfo.parameters()) {
                if (TolerantWrite.setAllowControlledOverwrite(pi.analysis(), PropertyImpl.UNMODIFIED_PARAMETER, TRUE, pi)) {
                    DECIDE.debug("MI: Decide unmodified of parameter because independent: {} = true", pi);
                    propertyChanges.incrementAndGet();
                }
            }
        } else {
            Bool unmodifiedField = fieldValue.field().analysis().getOrNull(UNMODIFIED_FIELD,
                    ValueImpl.BoolImpl.class);
            if (unmodifiedField == null) {
                UNDECIDED.debug("MI: Unmodified of setter parameter of {}", methodInfo);

            } else {
                for (ParameterInfo pi : methodInfo.parameters()) {
                    if (pi.index() == fieldValue.parameterIndexOfIndex()) {
                        if (TolerantWrite.setAllowControlledOverwrite(pi.analysis(), PropertyImpl.UNMODIFIED_PARAMETER, TRUE, pi)) {
                            DECIDE.debug("MI: Decide unmodified of setter index parameter: {} = true", pi);
                            propertyChanges.incrementAndGet();
                        }
                    } else {
                        if (TolerantWrite.setAllowControlledOverwrite(pi.analysis(), PropertyImpl.UNMODIFIED_PARAMETER, unmodifiedField, pi)) {
                            DECIDE.debug("MI: Decide unmodified of setter parameter: {} = {}", pi, unmodifiedField);
                            propertyChanges.incrementAndGet();
                        }
                    }
                }
            }
        }
    }

    /*
constructors: independent
void methods: independent
fluent methods: because we return the same object that the caller already has, no more opportunity to make
    changes is leaked than what as already there. Independent!
accessors: independent directly related to the immutability of the field being returned
normal methods: does a modification to the return value imply any modification in the method's object?
    independent directly related to the immutability of the fields to which the return value links.
 */
    private void doIndependent(boolean cycleBreakingActive, MethodInfo methodInfo, MethodLinkedVariables mlv) {

        Independent independentMethod = doIndependentMethod(methodInfo, mlv, cycleBreakingActive);
        if (independentMethod != null) {
            if (TolerantWrite.setAllowControlledOverwrite(methodInfo.analysis(), PropertyImpl.INDEPENDENT_METHOD, independentMethod, methodInfo)) {
                DECIDE.debug("MI: Decide independent of method {} = {}", methodInfo, independentMethod);
                propertyChanges.incrementAndGet();
            }
            if (Culprits.ENABLED && independentMethod.isDependent()) {
                DependentCulprit.writeForMethod(methodInfo, mlv, analysisHelper);
            }
        } else if (cycleBreakingActive) {
            // only break a cycle for a value that never decided: an undecided RE-derivation (a link to a field
            // whose immutability was cleared and not yet re-decided) must not erase a stored honest verdict
            if (methodInfo.analysis().getOrNull(PropertyImpl.INDEPENDENT_METHOD, ValueImpl.IndependentImpl.class) == null
                && TolerantWrite.setAllowControlledOverwrite(methodInfo.analysis(), PropertyImpl.INDEPENDENT_METHOD, INDEPENDENT, methodInfo)) {
                propertyChanges.incrementAndGet();
                DECIDE.info("MI: Decide independent of method {} = INDEPENDENT by {}", methodInfo, CYCLE_BREAKING);
            }
        } else {
            UNDECIDED.debug("MI: Independent of method undecided: {}", methodInfo);
        }
        for (ParameterInfo pi : methodInfo.parameters()) {
            Independent independent = doIndependentParameter(pi, mlv, cycleBreakingActive);
            if (independent != null) {
                if (TolerantWrite.setAllowControlledOverwrite(pi.analysis(), PropertyImpl.INDEPENDENT_PARAMETER, independent, pi)) {
                    DECIDE.debug("MI: Decide independent of parameter {} = {}", pi, independent);
                    propertyChanges.incrementAndGet();
                }
                if (Culprits.ENABLED && independent.isDependent()) DependentCulprit.writeForParameter(pi, mlv, analysisHelper);
            } else if (cycleBreakingActive) {
                // same rule as the method above: cycle breaking decides the never-decided, it does not overwrite
                if (pi.analysis().getOrNull(PropertyImpl.INDEPENDENT_PARAMETER, ValueImpl.IndependentImpl.class) == null
                    && TolerantWrite.setAllowControlledOverwrite(pi.analysis(), PropertyImpl.INDEPENDENT_PARAMETER, INDEPENDENT, pi)) {
                    DECIDE.info("MI: Decide independent of parameter {} = INDEPENDENT by {}", pi, CYCLE_BREAKING);
                    propertyChanges.incrementAndGet();
                }
            } else {
                UNDECIDED.debug("MI: Independent of parameter {} undecided", pi);
            }
        }
    }

    private Independent doIndependentParameter(ParameterInfo pi, MethodLinkedVariables mlv,
                                               boolean cycleBreakingActive) {
        boolean typeIsImmutable = analysisHelper.typeImmutable(pi.parameterizedType()).isImmutable();
        if (typeIsImmutable) return INDEPENDENT;
        if (pi.methodInfo().isAbstract() || pi.methodInfo().methodBody().isEmpty()) return DEPENDENT;
        assert pi.index() < mlv.ofParameters().size();
        Links links = mlv.ofParameters().get(pi.index());
        return worstLinkToFields(links, pi.methodInfo(), cycleBreakingActive);
    }

    private Independent doIndependentMethod(MethodInfo methodInfo, MethodLinkedVariables mlv,
                                            boolean cycleBreakingActive) {
        if (methodInfo.isConstructor() || methodInfo.noReturnValue()) return INDEPENDENT;
        assert !methodInfo.isAbstract() : "Code only called when there is a method body";
        boolean fluent = methodInfo.analysis().getOrDefault(FLUENT_METHOD, FALSE).isTrue();
        if (fluent) return INDEPENDENT;
        boolean typeIsImmutable = analysisHelper.typeImmutable(methodInfo.returnType()).isImmutable();
        if (typeIsImmutable) return INDEPENDENT;
        Independent worst = worstLinkToFields(mlv.ofReturnValue(), methodInfo, cycleBreakingActive);
        if (worst == null || worst.isDependent()) return worst;
        return withDependentExceptions(methodInfo, mlv.ofReturnValue(), worst);
    }

    /*
    'return new Iter()' of an inner class whose remove() modifies the outer instance carries
    'method.§m ☷{remove} this.§m' (ExpressionVisitor.linkInnerClassToOuterInstance): the returned object is independent
    of this object except through those methods -- the computed form of @Independent(hc = true, except = "remove").
     */
    private static Independent withDependentExceptions(MethodInfo methodInfo, Links ofReturnValue, Independent worst) {
        Set<MethodInfo> exceptions = new HashSet<>();
        for (Link link : ofReturnValue) {
            if (!link.linkNature().pass().isEmpty()
                && Util.isVirtualModification(link.from()) && Util.isVirtualModification(link.to())
                && Util.firstRealVariable(link.to()) instanceof This thisVar
                && thisVar.typeInfo() == methodInfo.typeInfo()) {
                exceptions.addAll(link.linkNature().pass());
            }
        }
        if (exceptions.isEmpty()) return worst;
        // state them on the DECLARED return type, as @Independent(except = "remove") does (the pass set also holds
        // the inner class's own overriding methods, which a caller of the declared type never names)
        TypeInfo returnType = methodInfo.returnType().bestTypeInfo();
        if (returnType != null) {
            Set<MethodInfo> onReturnType = new HashSet<>();
            for (MethodInfo m : exceptions) {
                if (m.typeInfo() == returnType || returnType.superTypesExcludingJavaLangObject().contains(m.typeInfo())) {
                    onReturnType.add(m);
                }
            }
            if (!onReturnType.isEmpty()) exceptions = onReturnType;
        }
        List<MethodInfo> sorted = exceptions.stream()
                .sorted(Comparator.comparing(MethodInfo::fullyQualifiedName)).toList();
        return new ValueImpl.IndependentImpl(worst.isIndependent() ? 2 : 1, Map.of(), sorted);
    }

    /**
     * {@code null} = undecided: some link reaches a field of the instance whose immutability is not yet known.
     * Deciding on such a sample froze whichever transient the iteration order happened to serve (empty links →
     * INDEPENDENT, cleared-not-yet-rederived field type → MUTABLE default → DEPENDENT): the composed-dogfood
     * hc↔FF fork of the statement family. A decided MUTABLE still concludes DEPENDENT immediately — the bottom
     * dominates whatever the undecided links might become.
     * <p>
     * Under cycle breaking, an undecided field counts as MUTABLE: the result is DEPENDENT where a link reaches one,
     * and unchanged where none does. Undecided, the method's (parameter's) value was written INDEPENDENT outright,
     * and a cold run settled above what a seeded run, with the fields decided, computes (fernflower's
     * Statement.findObject, VarVersionsProcessor.getVarType, setVarType's parameter); the early cutoff fell back.
     * NOCBMETHODPESSIMISM restores the old behaviour.
     */
    private Independent worstLinkToFields(Links links, MethodInfo methodInfo, boolean cycleBreakingActive) {
        boolean immutableHc = false;
        boolean undecided = false;
        for (Link link : links) {
            Immutable fieldImmutable = LinkToField.immutableOfLinkedField(link, methodInfo, analysisHelper);
            if (fieldImmutable == null) {
                if (LinkToField.reachesJudgeableField(link, methodInfo)) undecided = true;
            } else {
                if (fieldImmutable.isMutable()) return DEPENDENT;
                if (fieldImmutable.isImmutableHC()) immutableHc = true;
            }
        }
        if (undecided) return cycleBreakingActive && CB_METHOD_PESSIMISM ? DEPENDENT : null;
        return immutableHc ? INDEPENDENT_HC : INDEPENDENT;
    }


    private void fromNonFinalFieldToParameter(TypeInfo typeInfo) {
       /*   FIXME   Map<ParameterInfo, StaticValues> svMapParameters = collectReverseFromNonFinalFieldsToParameters(typeInfo);
            svMapParameters.forEach((pi, sv) -> {
                if (!pi.analysis().haveAnalyzedValueFor(STATIC_VALUES_PARAMETER)) {
                    TolerantWrite.setOnce(pi.analysis(), STATIC_VALUES_PARAMETER, sv, pi);
                }
                if (sv.expression() instanceof VariableExpression ve && ve.variable() instanceof FieldReference fr && fr.scopeIsThis()) {
                    if (!pi.analysis().haveAnalyzedValueFor(PARAMETER_ASSIGNED_TO_FIELD)) {
                        TolerantWrite.setOnce(pi.analysis(), PARAMETER_ASSIGNED_TO_FIELD, new ValueImpl.AssignedToFieldImpl(Set.of(fr.fieldInfo())), pi);
                    }
                }
            });
        }

        private Map<ParameterInfo, StaticValues> collectReverseFromNonFinalFieldsToParameters(TypeInfo typeInfo) {
            Map<ParameterInfo, StaticValues> svMapParameters = new HashMap<>();
            typeInfo.fields()
                    .stream()
                    .filter(f -> !f.isPropertyFinal())
                    .forEach(fieldInfo -> {
                        StaticValues sv = fieldInfo.analysis().getOrNull(STATIC_VALUES_FIELD, StaticValuesImpl.class);
                        if (sv != null
                            && sv.expression() instanceof VariableExpression ve
                            && ve.variable() instanceof ParameterInfo pi) {
                            VariableExpression reverseVe = runtime.newVariableExpressionBuilder()
                                    .setVariable(runtime.newFieldReference(fieldInfo))
                                    .setSource(pi.source())
                                    .build();
                            StaticValues reverse = StaticValuesImpl.of(reverseVe);
                            StaticValues prev = svMapParameters.put(pi, reverse);
                            if (prev != null && !prev.equals(sv)) throw new UnsupportedOperationException("TODO");
                        } else {
                            // FIXME waitFor
                        }
                    });
            return svMapParameters;*/
    }
}

