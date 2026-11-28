package io.codelaser.maddi.modification.link.impl;

import java.util.HashSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.codelaser.maddi.cst.api.analysis.Value;
import io.codelaser.maddi.cst.api.expression.Expression;
import io.codelaser.maddi.cst.api.expression.Lambda;
import io.codelaser.maddi.cst.api.expression.MethodCall;
import io.codelaser.maddi.cst.api.expression.MethodReference;
import io.codelaser.maddi.cst.api.expression.TypeExpression;
import io.codelaser.maddi.cst.api.expression.VariableExpression;
import io.codelaser.maddi.cst.api.info.MethodInfo;
import io.codelaser.maddi.cst.api.info.ParameterInfo;
import io.codelaser.maddi.cst.api.info.TypeInfo;
import io.codelaser.maddi.cst.api.runtime.Runtime;
import io.codelaser.maddi.cst.api.translate.TranslationMap;
import io.codelaser.maddi.modification.common.AnalysisHelper;
import io.codelaser.maddi.cst.api.type.ParameterizedType;
import io.codelaser.maddi.cst.api.variable.DependentVariable;
import io.codelaser.maddi.cst.api.variable.FieldReference;
import io.codelaser.maddi.cst.api.variable.This;
import io.codelaser.maddi.cst.api.variable.Variable;
import io.codelaser.maddi.cst.impl.analysis.PropertyImpl;
import io.codelaser.maddi.cst.impl.analysis.ValueImpl;
import io.codelaser.maddi.modification.link.impl.translate.VariableTranslationMap;
import io.codelaser.maddi.modification.prepwork.Util;
import io.codelaser.maddi.modification.prepwork.variable.MethodLinkedVariables;
import io.codelaser.maddi.modification.prepwork.variable.Stage;
import io.codelaser.maddi.modification.prepwork.variable.VariableData;

/**
 * @param currentMethod           the method whose body contains {@code mc}
 * @param throughPassedFunction   receives (parameter, entry) when a parameter of {@code currentMethod} is handed to
 *                                one of its own functional parameters: recorded instead of marked modified, see
 *                                {@link PassedFunction}
 * @param dependsOn               receives every method whose verdict this call's modification decision rests on
 *                                beyond the callee itself (the cone rule of {@link ReceiverLevel}), for the worklist
 */
public record MethodModification(Runtime runtime, VariableData variableData, Stage stage, MethodCall mc,
                                 MethodInfo currentMethod,
                                 BiConsumer<ParameterInfo, String> throughPassedFunction,
                                 Consumer<MethodInfo> dependsOn) {
    private static final Logger LOGGER = LoggerFactory.getLogger(MethodModification.class);
    // gate NOELEMENTFUNCTION: a function handed the receiver's elements by a library callee does not modify them (#99)
    public static final boolean NO_ELEMENT_FUNCTION = Gate.isSet("NOELEMENTFUNCTION");

    /**
     * The variables this call modifies, and among them those whose objects are modified only in their hidden content
     * (CodeLaser/maddi-mod#25): the argument to a parameter that is structurally unmodified yet modified, the receiver
     * of a method that is structurally non-modifying yet modifying, the callee's own deep-only modifications
     * translated into this scope. A variable is deep-only when every reason to mark it is.
     */
    public record Modified(Set<Variable> all, Set<Variable> deepOnly) {
    }

    /** Collects the two sets of {@link Modified} as the four recording sites of {@link #go} find them. */
    private static class Collector {
        private final Set<Variable> structural = new HashSet<>();
        private final Set<Variable> deepOnlyCandidates = new HashSet<>();

        void add(Variable v, boolean structural) {
            if (structural) this.structural.add(v);
            else this.deepOnlyCandidates.add(v);
        }

        /** The variable and its scopes, which hold it as (part of) their accessible content: the same kind. */
        void addWithScopes(Variable v, boolean structural) {
            Util.variableAndScopes(v).filter(x -> !x.isIgnoreModifications()).forEach(x -> add(x, structural));
        }

        Modified build() {
            Set<Variable> all = new HashSet<>(structural);
            all.addAll(deepOnlyCandidates);
            Set<Variable> deepOnly = new HashSet<>(deepOnlyCandidates);
            deepOnly.removeAll(structural);
            return new Modified(all, deepOnly);
        }
    }

    public Modified go(Variable objectPrimary, List<Result> params, MethodLinkedVariables methodLinkedVariables) {
        Collector modified = new Collector();
        MethodInfo methodInfo = mc.methodInfo();
        // The RECEIVER carries @IgnoreModifications: the author has declared that whatever this object does is
        // not their modification. That must cover what it does to the ARGUMENTS it is handed, not only the
        // object itself -- `f.apply(this._1, this._2)` must not mark `this._1` modified when `f` is disclaimed.
        // Without this, the disclaimer reached only the receiver (just below), while the decision about
        // arguments was taken solely from the CALLEE's declared parameters -- for a functional interface that is
        // java.util.function.Function.apply(@Modified T t) in the JDK hints, which nothing at the call site can
        // influence. Measured on vavr 2026-09-22: @IgnoreModifications on all five of io.vavr.Tuple2's function
        // parameters set the six ignoreModsParameter properties and changed nothing else, because of exactly
        // this. The codebase's own motivating example, Element.visit(@IgnoreModifications Predicate p) calling
        // p.test(this), has the same shape and worked only because Predicate.test's ARGUMENT was moved to
        // @NotModified on 2026-07-23 -- fixed on the callee side because the receiver's disclaimer did not reach.
        boolean receiverDisclaimed = objectPrimary != null
                                     && Util.variableAndScopes(objectPrimary).anyMatch(Variable::isIgnoreModifications);
        // the static type of the receiver EXPRESSION (a cast counts), for the receiver rules (ReceiverLevel)
        ParameterizedType receiverType = mc.object() == null ? null : mc.object().parameterizedType();
        boolean receiverExcused = false;
        /*
         #127: a call back into the current recursion. Its verdicts are the cycle's own, and while undecided they read
         as modifying, which the cycle then sustains: 'parameters.getFirst().bestTypeInfo()' in a read-only recursive
         bestTypeInfo() made 'parameters' modified once List.getFirst kept its summary. An undecided in-recursion
         verdict marks nothing; a decided FALSE (the body modifies directly) marks when the worklist recomputes.
         */
        boolean inRecursion = !ExpressionVisitor.NO_NARROW_RECURSION && currentMethod != null
                              && ExpressionVisitor.inRecursion(currentMethod, methodInfo);
        // the decision rests on the callee's verdict: recompute this method when it changes (worklist)
        if (inRecursion) dependsOn.accept(methodInfo);
        if (objectPrimary != null && !methodInfo.isFinalizer()
            && !(inRecursion && methodInfo.analysis().getOrNull(PropertyImpl.NON_MODIFYING_METHOD,
                ValueImpl.BoolImpl.class) == null)) {
            if (methodInfo.isModifying() && !methodInfo.isIgnoreModification()) {
                if (ReceiverLevel.enabled() && receiverExcused(objectPrimary, receiverType, methodInfo)) {
                    receiverExcused = true;
                } else {
                    LOGGER.debug("Mark object primary {} as modified by {}", objectPrimary, methodInfo);
                    // structurally non-modifying (a method touching only the elements of its receiver): deep-only
                    modified.addWithScopes(objectPrimary, !methodInfo.isStructurallyNonModifying());
                }
            }
        }
        // the receiver is one of OUR functional parameters and this is its SAM: what it does to its arguments
        // depends on the function our caller passes (PassedFunction)
        ParameterInfo functionalReceiver = objectPrimary instanceof ParameterInfo fp
                                           && fp.methodInfo() == currentMethod
                                           && PassedFunction.sam(methodInfo)
                                           && PassedFunction.functional(fp) ? fp : null;
        Set<Variable> recordedThroughPassedFunction = new HashSet<>();
        if (!receiverDisclaimed) {
            for (ParameterInfo pi : methodInfo.parameters()) {
                // an unmodified parameter with entries: it, or an element of it, reaches a function (PassedFunction)
                boolean throughOnly = !pi.isModified() && !pi.isVarArgs() && PassedFunction.hasEntries(pi);
                if (inRecursion && pi.analysis().getOrNull(PropertyImpl.UNMODIFIED_PARAMETER,
                        ValueImpl.BoolImpl.class) == null) {
                    continue; // #127: undecided in the recursion, see above
                }
                if ((!pi.isModified() && !throughOnly) || pi.isIgnoreModifications()
                    || parameterExcusedByCone(pi, receiverType)) {
                    continue;
                }
                // deep-modified but structurally unmodified (its elements are modified, it is not): deep-only
                boolean structural = !pi.isStructurallyUnmodified();
                if (pi.isVarArgs()) {
                    for (int i = methodInfo.parameters().size() - 1; i < mc.parameterExpressions().size(); i++) {
                        Result rp = params.get(i);
                        handleModifiedParameter(mc.parameterExpressions().get(i), rp, modified, structural);
                    }
                    continue;
                }
                Result rp = params.get(pi.index());
                Expression argument = mc.parameterExpressions().get(pi.index());
                PassedFunction.Effect effect = PassedFunction.effectAtCallSite(pi, mc.parameterExpressions());
                if (effect == PassedFunction.Effect.UNMODIFIED) {
                    LOGGER.debug("Argument {} of {} not modified: the function passed leaves it alone", pi.index(),
                            methodInfo);
                } else if (effect != null) {
                    // the callee hands the argument (ANY) or only its elements (ELEMENTS) to functions we pass, and
                    // one of them may modify it; when those are our own functional parameters, passed on, the entries
                    // become ours, for our caller to resolve
                    if (throughPassedFunction == null || !forward(pi, rp)) {
                        handleModifiedParameter(argument, rp, modified, effect == PassedFunction.Effect.ANY);
                    }
                } else if (functionalReceiver != null && throughPassedFunction != null
                           && rp.links() != null && rp.links().isEmpty()
                           && rp.links().primary() instanceof ParameterInfo ownParameter
                           && ownParameter.methodInfo() == currentMethod) {
                    // our parameter is the SAM's argument: not modified here, whatever this function does to it is
                    // for our caller to resolve (CodeLaser/maddi-mod#69)
                    throughPassedFunction.accept(ownParameter,
                            PassedFunction.entry(functionalReceiver.index(), pi.index()));
                    recordedThroughPassedFunction.add(ownParameter);
                } else {
                    // ... and the same for an element of our parameter (CodeLaser/maddi-mod#67)
                    Set<ParameterInfo> elementOf = functionalReceiver == null || throughPassedFunction == null
                            ? Set.of() : PassedFunction.elementOf(rp.links(), variableData, stage, currentMethod);
                    if (elementOf.isEmpty()) {
                        handleModifiedParameter(argument, rp, modified, structural);
                    } else {
                        for (ParameterInfo owner : elementOf) {
                            throughPassedFunction.accept(owner,
                                    PassedFunction.elementEntry(functionalReceiver.index(), pi.index()));
                        }
                        recordedThroughPassedFunction.add(rp.links().primary());
                    }
                }
            }
        }
        if (!receiverDisclaimed && objectPrimary != null && !NO_ELEMENT_FUNCTION
            && handsElementsToModifyingFunction(mc)) {
            // the receiver's elements are modified by the function they are handed to: its hidden content, deep-only
            LOGGER.debug("Elements of {} modified by the function passed to {}", objectPrimary, methodInfo);
            modified.addWithScopes(objectPrimary, false);
            for (Variable holder : structuralHolders(objectPrimary, handedElementTypes(objectPrimary))) {
                modified.add(holder, true);
            }
        }
        // the same reasoning for the callee's OWN propagated modifications: if none of this call's effects are
        // the author's, that includes the ones the callee's body performs on variables translated into our scope
        // MethodLinkedVariables.isEmpty() looks at the links only: a summary with no links and a modified set (a void
        // method without parameters modifying a field's object, 'fix() { p.set(i); }': this, this.p) is not empty
        // here (CodeLaser/maddi-mod#150, the fixpoint's mkV). Gate NOMODIFIEDONLYSUMMARY (presence): skipped, as before.
        boolean calleeHasEffects = NO_MODIFIED_ONLY_SUMMARY ? !methodLinkedVariables.isEmpty()
                : !methodLinkedVariables.isEmpty() || !methodLinkedVariables.modified().isEmpty();
        if (!receiverDisclaimed && calleeHasEffects && objectPrimary != null) {
            Variable methodThis = runtime.newThis(methodInfo.typeInfo().asParameterizedType());
            VariableTranslationMap vtm = new VariableTranslationMap(runtime).put(methodThis, objectPrimary);
            if (!NO_FOREIGN_MODIFIED) {
                // the callee's body reaches its supertypes' code: their 'this' is the same object (#126)
                for (var superType : methodInfo.typeInfo().superTypesExcludingJavaLangObject()) {
                    vtm.put(runtime.newThis(superType.asParameterizedType()), objectPrimary);
                }
            }
            TranslationMap tm = vtm;
            for (Variable mv : methodLinkedVariables.modified()) {
                Variable translated = tm.translateVariableRecursively(mv);
                // the SAM's own summary says its argument is modified: for a recorded parameter, that is the same
                // conditional modification, not a second one
                if (recordedThroughPassedFunction.contains(translated)) continue;
                // the receiver rules excused the object: the callee's own claim on its 'this' (a shallow summary
                // of an abstract callee claims exactly the union verdict) is the same claim, not a second one
                if (receiverExcused && Util.variableAndScopes(translated).anyMatch(objectPrimary::equals)) continue;
                if (NO_FOREIGN_MODIFIED
                    ? translated.equals(mv)
                      || variableData != null && variableData.isKnown(translated.fullyQualifiedName())
                    : inCallerScope(translated, translated.equals(mv))) {
                    LOGGER.debug("Propagated modification to {}", translated);
                    modified.addWithScopes(translated, !methodLinkedVariables.deepOnlyModified().contains(mv));
                }
            }
        }
        return modified.build();
    }

    /*
     CodeLaser/maddi-mod#126. The callee's modified set enters ours after translating the callee's 'this', and that of
     each of its supertypes (the code it inherits or calls with 'super'), onto the receiver. What translation leaves
     unchanged entered as it was, meant for static state. But a summary also carries the parameters of the callee and
     of its own callees, and, from deep in those callees, a 'this' of an unrelated type. The parameters do no harm: they repeat what those methods' own summaries claim (TestWriteAnalysis2 pins
     'setAdd(R):0:r' in its caller's set). A foreign 'this' does: jenkins' BuildStepCompatibilityLayerTest.testPerform()
     took 'this.§$' from BuildStepCompatibilityLayer's overloads, the shadow pass read it as the test instance, and a
     method that only uses locals was reported modifying. So a variable rooted in a 'this' enters only for the
     caller's own type or a type enclosing it (an inner class modifying its outer instance), or a supertype of
     either. Other roots as before.
     Gate NOFOREIGNMODIFIED: a 'this' as well.
     */
    private static final boolean NO_FOREIGN_MODIFIED = Gate.isSet("NOFOREIGNMODIFIED");
    private static final boolean NO_MODIFIED_ONLY_SUMMARY = Gate.isSet("NOMODIFIEDONLYSUMMARY");

    // the caller's own object, or the outer instance of an enclosing type, seen through any of their supertypes
    private boolean ownThis(This thisVar) {
        for (TypeInfo t = currentMethod.typeInfo(); t != null; t = t.enclosingType()) {
            if (t == thisVar.typeInfo() || t.superTypesExcludingJavaLangObject().contains(thisVar.typeInfo())) {
                return true;
            }
        }
        return false;
    }

    private boolean inCallerScope(Variable translated, boolean untranslated) {
        Variable root = translated;
        while (true) {
            if (root instanceof FieldReference fr && fr.scopeVariable() != null) {
                root = fr.scopeVariable();
            } else if (root instanceof DependentVariable dv && dv.arrayVariable() != null) {
                root = dv.arrayVariable();
            } else {
                break;
            }
        }
        if (root instanceof This thisVar) return ownThis(thisVar);
        return untranslated || variableData != null && variableData.isKnown(translated.fullyQualifiedName());
    }

    /*
    The callee's entries for this argument carry over to our own parameter when every function that may modify is one
    of our functional parameters: as they are when the argument IS our parameter, as element entries when it is an
    element of our parameter. False when they cannot be carried over.
     */
    private boolean forward(ParameterInfo calleeParameter, Result rp) {
        List<String> forwarded = PassedFunction.forwardedEntries(calleeParameter, mc.parameterExpressions(),
                currentMethod);
        if (forwarded == null) return false;
        if (rp.links() != null && rp.links().isEmpty() && rp.links().primary() instanceof ParameterInfo own
            && own.methodInfo() == currentMethod) {
            forwarded.forEach(entry -> throughPassedFunction.accept(own, entry));
            return true;
        }
        Set<ParameterInfo> owners = PassedFunction.elementOf(rp.links(), variableData, stage, currentMethod);
        for (ParameterInfo owner : owners) {
            forwarded.forEach(entry -> throughPassedFunction.accept(owner, PassedFunction.asElementEntry(entry)));
        }
        return !owners.isEmpty();
    }

    /*
    ReceiverLevel: the level rule (the object held cannot be modified), then the cone rule (only the callee's
    implementations below the receiver's static type can run, and none of them modifies).
     */
    private boolean receiverExcused(Variable objectPrimary, ParameterizedType receiverType, MethodInfo methodInfo) {
        if (ReceiverLevel.immutableObject(objectPrimary, receiverType)) {
            LOGGER.debug("Receiver {} of {} is immutable: not modified", objectPrimary, methodInfo);
            return true;
        }
        Value.Bool cone = ReceiverLevel.coneNonModifying(methodInfo, receiverType, dependsOn);
        if (cone != null && cone.isTrue()) {
            LOGGER.debug("Receiver {} of {}: no modifying implementation in the cone of {}", objectPrimary, methodInfo,
                    receiverType);
            return true;
        }
        return false;
    }

    private boolean parameterExcusedByCone(ParameterInfo pi, ParameterizedType receiverType) {
        if (!ReceiverLevel.enabled()) return false;
        Value.Bool cone = ReceiverLevel.coneUnmodifiedParameter(pi, receiverType, dependsOn);
        return cone != null && cone.isTrue();
    }

    private void handleModifiedParameter(Expression argument, Result rp, Collector modified, boolean structural) {
        if (rp.links() != null && rp.links().primary() != null && !Util.isHiddenContentField(rp.links().primary())) {
            if (ReceiverLevel.enabled()
                && ReceiverLevel.immutableObject(rp.links().primary(), argument == null ? null : argument.parameterizedType())) {
                LOGGER.debug("Argument primary {} of {} is immutable: not modified", rp.links().primary(), mc.methodInfo());
                if (argument instanceof MethodReference mr) propagateModificationOfObject(modified, mr);
                return;
            }
            LOGGER.debug("Mark argument primary {} as modified by {}", rp.links().primary(), mc.methodInfo());
            // the LAST of go()'s four modification-recording sites to get this filter (fix C, 2026-09-22): a
            // disclaimed face never implicates its own node, whichever site reaches it. Handing a field that is
            // @IgnoreModifications to a callee's @Modified parameter is the author saying that what the callee
            // does to it is not this type's modification -- exactly what the receiver and propagated sites
            // already honoured. ShadowModificationPass.project() has ALWAYS filtered it here (its FieldReference
            // arm), so before this the two implementations disagreed on the argument site by construction.
            modified.addWithScopes(rp.links().primary(), structural);
        }
        if (argument instanceof MethodReference mr) {
            propagateModificationOfObject(modified, mr);
        }
    }

    private void propagateModificationOfObject(Collector modified, MethodReference mr) {
        if (mr.methodInfo().isModifying() && mr.scope() instanceof VariableExpression ve) {
            modified.addWithScopes(ve.variable(), !mr.methodInfo().isStructurallyNonModifying());
        }
    }
    /*
     A library callee that hands its receiver's elements to a function passed to it ('list.forEach(h -> h.touch())',
     Map.forEach, Optional.ifPresent): its functional parameter's type arguments are the receiver type's own type
     parameters (Consumer<? super T> on Iterable<T>), what its hidden content stands for. The independence the hints
     give such a parameter is no guide (HC on Iterable.forEach, plain on ArrayList.forEach and Optional.ifPresent).
     When the function passed modifies its argument, the elements are modified
     (CodeLaser/maddi-mod#99). The loop form ('for (H h : hs) h.touch()') was always seen; a source callee records
     the same through MODIFIED_THROUGH_PASSED_FUNCTION (#67), which a library callee has no body for. Only a lambda or
     a method reference: an opaque function argument is handled where the callee's parameter is modified.
     */
    private static boolean ownerTypeParameter(ParameterizedType typeArgument) {
        return typeArgument.typeParameter() != null && !typeArgument.typeParameter().isMethodTypeParameter();
    }

    /*
     #159, the holder's view, for elements modified by a function they are handed to ('this.lvs.forEach(lv -> lv.sig =
     s)', 'lvs.stream().findFirst().ifPresent(..)'): the container itself is modified in its hidden content, deep-only,
     but a scope whose declared type wrote the element type on its field ('List<LV> lvs' in Z) holds the elements as
     accessible content: modified structurally, as the loop form's holderElementModified has it. fernflower's
     StructLocalVariableTableAttribute.mergeSignatures (the dynamic check's 2a). Gate NOELEMENTFUNCTIONREACH.
     */
    public static final boolean NO_ELEMENT_FUNCTION_REACH = Gate.isSet("NOELEMENTFUNCTIONREACH");

    /** The concrete, mutable type arguments of the variable's type: the elements a function is handed. */
    public static List<ParameterizedType> handedElementTypes(Variable container) {
        if (NO_ELEMENT_FUNCTION_REACH || container == null || container.parameterizedType() == null) return List.of();
        List<ParameterizedType> out = new ArrayList<>();
        for (ParameterizedType arg : container.parameterizedType().parameters()) {
            if (arg.isTypeParameter() || arg.wildcard() != null || arg.bestTypeInfo() == null
                || arg.bestTypeInfo().isJavaLangObject()) continue;
            if (new AnalysisHelper().typeImmutable(arg).isAtLeastImmutableHC()) continue;
            out.add(arg);
        }
        return out;
    }

    /** The scopes of {@code container} (not itself) whose declared type may hold one of the elements accessibly. */
    public static List<Variable> structuralHolders(Variable container, List<ParameterizedType> elementTypes) {
        if (elementTypes.isEmpty()) return List.of();
        List<Variable> out = new ArrayList<>();
        Util.variableAndScopes(container).filter(x -> x != container && !x.isIgnoreModifications()).forEach(x -> {
            ParameterizedType owner = x.parameterizedType();
            if (owner != null && elementTypes.stream().anyMatch(e -> Util.mayBeAccessibleContent(e, owner, false,
                    pt -> new AnalysisHelper().typeImmutable(pt).isAtLeastImmutableHC()))) {
                out.add(x);
            }
        });
        return out;
    }

    public static boolean handsElementsToModifyingFunction(MethodCall mc) {
        MethodInfo methodInfo = mc.methodInfo();
        if (!methodInfo.typeInfo().compilationUnit().externalLibrary() || methodInfo.isStatic()) return false;
        List<Expression> arguments = mc.parameterExpressions();
        for (ParameterInfo pi : methodInfo.parameters()) {
            if (pi.isVarArgs() || pi.index() >= arguments.size() || !PassedFunction.functional(pi)) continue;
            // the function takes what the receiver's type parameters stand for: Consumer<? super T> on Iterable<T>
            if (pi.parameterizedType().parameters().stream().noneMatch(MethodModification::ownerTypeParameter)) continue;
            MethodInfo sam = pi.parameterizedType().typeInfo() == null ? null
                    : pi.parameterizedType().typeInfo().singleAbstractMethod();
            // a function called for its effect (Consumer, BiConsumer: forEach, ifPresent, forEachRemaining); a lazy
            // stream stage's Predicate or Function is not where the element is changed, and starring the stream
            // reshapes its links (TestConsumers)
            if (sam == null || !sam.returnType().isVoid()) continue;
            Expression function = arguments.get(pi.index());
            for (int j = 0; j < sam.parameters().size(); j++) {
                if (structurallyModifiesArgument(function, j)) return true;
            }
        }
        return false;
    }
    /*
     CodeLaser/maddi-mod#115: a lazy stream stage's function ('list.stream().filter(h -> { h.touch(); return true; })',
     map, anyMatch) is handed the stream's elements as #99's Consumer is, but the stream must not be starred (#99,
     TestConsumers). The engine already links the lambda's parameter as an element of the stream's source
     ('0:list.§$s ∋ 0:h'); what it does not know is that the parameter is modified. These are the lambda parameters that
     are, structurally; marked modified (deep-only) at the call, the ∋ completion carries that to the source.
     Lambdas only: a method reference has no parameter variable to link. Gate NOLAZYSTAGEELEMENT.
     */
    public static final boolean NO_LAZY_STAGE_ELEMENT = Gate.isSet("NOLAZYSTAGEELEMENT");

    public static Set<Variable> lazyStageModifiedLambdaParameters(MethodCall mc) {
        if (NO_LAZY_STAGE_ELEMENT || NO_ELEMENT_FUNCTION) return Set.of();
        MethodInfo methodInfo = mc.methodInfo();
        if (!methodInfo.typeInfo().compilationUnit().externalLibrary() || methodInfo.isStatic()) return Set.of();
        List<Expression> arguments = mc.parameterExpressions();
        Set<Variable> result = new HashSet<>();
        for (ParameterInfo pi : methodInfo.parameters()) {
            if (pi.isVarArgs() || pi.index() >= arguments.size() || !PassedFunction.functional(pi)) continue;
            if (pi.parameterizedType().parameters().stream().noneMatch(MethodModification::ownerTypeParameter)) continue;
            MethodInfo sam = pi.parameterizedType().typeInfo() == null ? null
                    : pi.parameterizedType().typeInfo().singleAbstractMethod();
            if (sam == null || sam.returnType().isVoid()) continue; // void: #99, handsElementsToModifyingFunction
            if (!(arguments.get(pi.index()) instanceof Lambda lambda)) continue;
            for (int j = 0; j < sam.parameters().size(); j++) {
                if (structurallyModifiesArgument(lambda, j)) result.add(lambda.methodInfo().parameters().get(j));
            }
        }
        return result;
    }

    /*
     Whether the function, applied, changes the object it is handed: its parameter (or an unbound receiver's method) is
     STRUCTURALLY modified. The deep verdict also stars an argument that is only stored into a modified container
     ('j -> add(j)', '0:ii*∈this.set*.§$s', TestForEachLambda): the container changes, not the element.
     */
    private static boolean structurallyModifiesArgument(Expression function, int samArgumentIndex) {
        MethodInfo target;
        int index = samArgumentIndex;
        if (function instanceof MethodReference mr) {
            target = mr.methodInfo();
            if (!target.isStatic() && !target.isConstructor() && mr.scope() instanceof TypeExpression) {
                if (index == 0) {
                    Value.Bool nonModifying = target.analysis().getOrNull(PropertyImpl.STRUCTURALLY_NON_MODIFYING_METHOD,
                            ValueImpl.BoolImpl.class);
                    return nonModifying != null && nonModifying.isFalse();
                }
                index--;
            }
        } else if (function instanceof Lambda lambda) {
            target = lambda.methodInfo();
        } else {
            return false;
        }
        if (index >= target.parameters().size() || target.parameters().get(index).isVarArgs()) return false;
        Value.Bool unmodified = target.parameters().get(index).analysis()
                .getOrNull(PropertyImpl.STRUCTURALLY_UNMODIFIED_PARAMETER, ValueImpl.BoolImpl.class);
        return unmodified != null && unmodified.isFalse();
    }
}
