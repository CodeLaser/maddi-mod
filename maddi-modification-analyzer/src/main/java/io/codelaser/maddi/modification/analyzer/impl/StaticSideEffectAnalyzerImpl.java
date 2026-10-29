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

import io.codelaser.maddi.modification.common.util.OwnStaticState;
import io.codelaser.maddi.modification.common.util.TolerantWrite;
import io.codelaser.maddi.cst.api.analysis.Value;
import io.codelaser.maddi.cst.api.element.Element;
import io.codelaser.maddi.cst.api.expression.Assignment;
import io.codelaser.maddi.cst.api.expression.ConstructorCall;
import io.codelaser.maddi.cst.api.expression.Expression;
import io.codelaser.maddi.cst.api.expression.MethodReference;
import io.codelaser.maddi.cst.api.expression.MethodCall;
import io.codelaser.maddi.cst.api.expression.VariableExpression;
import io.codelaser.maddi.cst.api.info.FieldInfo;
import io.codelaser.maddi.cst.api.info.MethodInfo;
import io.codelaser.maddi.cst.api.info.TypeInfo;
import io.codelaser.maddi.cst.api.statement.ExplicitConstructorInvocation;
import io.codelaser.maddi.cst.api.variable.FieldReference;
import io.codelaser.maddi.cst.impl.analysis.PropertyImpl;
import io.codelaser.maddi.cst.impl.analysis.ValueImpl;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Computes {@link PropertyImpl#STATIC_STATE_MODIFIED_METHOD}, the types whose static state a method modifies, and
 * from it {@link PropertyImpl#STATIC_SIDE_EFFECTS_METHOD}: a method has a static side effect when that set holds a
 * type <em>other</em> than its own primary type and its parent types (road-to-immutability §050, "Static side
 * effects"). A method adds to its set the owner of each static field it assigns or makes a modifying call on
 * ({@code Other.COUNTER.incrementAndGet()}; not {@code System.out.println(...)}: {@code System.out} is
 * {@code @IgnoreModifications}, nor a logger's {@code LOG.info(...)}, an {@code @IgnoreModifications} method), and the sets of the methods and
 * constructors it calls or references ({@code Counter::next}, as a lambda calling it), static or instance. A
 * constructor's set includes what the instance fields' initializers modify: they run as part of it. So {@code Counter.next()} incrementing its own static counter has no
 * static side effect, while {@code Client.twice()} calling it has one: Counter's state is not Client's
 * (CodeLaser/maddi-mod#65). A callee contracted {@code @StaticSideEffects} on the library's safe surface (an AAPI
 * declaration such as {@code System.setOut}, whose effect is not visible from source; see
 * {@code maddi-aapi-archive .../jdk/JavaLang.java}) adds {@code java.lang.Object}: global state the contract does not
 * name, never a caller's own.
 * <p>
 * It is informational — it does not cap immutability (which inspects only the type's own fields) — and it is the
 * <b>global-escape arm</b> of the {@code @IgnoreModifications} confinement guard: a modification reached through
 * the ignored stratum that is a static side effect has left it.
 * <p>
 * Additive: it writes only its own properties and changes no existing verdict. On by default; a run can switch it
 * off ({@code ModificationOptions.staticSideEffects}).
 */
public class StaticSideEffectAnalyzerImpl {
    // non-final: a run's ModificationOptions can switch it off
    public static boolean ENABLED = true;
    /*
     Off by default: a run's ModificationOptions.culprits switches on STATIC_SIDE_EFFECTS_CULPRIT_METHOD, the first
     statement responsible for a static side effect, followed into the callees (the IDE daemon asks for it).
     */
    public static boolean CULPRITS = false;
    // how far a culprit is followed into the callees
    private static final int CULPRIT_DEPTH = 6;

    private final AtomicInteger propertyChanges;
    private final TypeInfo global;

    /**
     * @param global {@code java.lang.Object}: what a contracted {@code @StaticSideEffects} callee adds to its
     *               caller's set
     */
    public StaticSideEffectAnalyzerImpl(AtomicInteger propertyChanges, TypeInfo global) {
        this.propertyChanges = propertyChanges;
        this.global = global;
    }

    /*
     The methods of one type are decided together, to a fixpoint: a method calling another of the same type (or
     itself) takes that one's set as it grows, instead of waiting for it. A method whose set depends on a decision
     still out elsewhere (a callee of another type, a modifying call whose NON_MODIFYING_METHOD is not in) waits, as
     does every method of the type that calls it; under cycle breaking it decides with what is known.
     */
    public void go(TypeInfo typeInfo, boolean activateCycleBreaking) {
        if (!ENABLED) return;
        List<MethodInfo> group = typeInfo.constructorAndMethodStream()
                .filter(mi -> !decided(mi) && computable(mi)).toList();
        if (group.isEmpty()) return;
        Map<MethodInfo, Set<TypeInfo>> sets = new LinkedHashMap<>();
        Set<MethodInfo> waiting = new HashSet<>();
        group.forEach(mi -> sets.put(mi, new HashSet<>()));
        boolean changed = true;
        while (changed) {
            changed = false;
            for (MethodInfo mi : group) {
                Set<MethodInfo> inGroup = new HashSet<>();
                boolean waits = collect(mi, sets, inGroup, activateCycleBreaking);
                if (!waits && inGroup.stream().anyMatch(waiting::contains)) waits = true;
                if (waits && waiting.add(mi)) changed = true;
                for (MethodInfo callee : inGroup) {
                    if (sets.get(mi).addAll(sets.get(callee))) changed = true;
                }
            }
        }
        for (MethodInfo mi : group) {
            if (waiting.contains(mi)) continue;
            Set<TypeInfo> set = sets.get(mi);
            boolean sse = set.stream().anyMatch(owner -> escapes(mi, owner));
            if (TolerantWrite.setAllowControlledOverwrite(mi.analysis(), PropertyImpl.STATIC_STATE_MODIFIED_METHOD,
                    new ValueImpl.SetOfTypeInfoImpl(Set.copyOf(set)), mi)) {
                propertyChanges.incrementAndGet();
            }
            if (TolerantWrite.setAllowControlledOverwrite(mi.analysis(), PropertyImpl.STATIC_SIDE_EFFECTS_METHOD,
                    ValueImpl.BoolImpl.from(sse), mi)) {
                propertyChanges.incrementAndGet();
            }
            if (sse && CULPRITS && !mi.analysis().haveAnalyzedValueFor(PropertyImpl.STATIC_SIDE_EFFECTS_CULPRIT_METHOD)) {
                List<Value.Culprit.Step> steps = culprit(mi, owner -> escapes(mi, owner), sets, 0, new HashSet<>());
                // display data: not a verdict, so not counted as a property change
                if (!steps.isEmpty()) {
                    mi.analysis().set(PropertyImpl.STATIC_SIDE_EFFECTS_CULPRIT_METHOD, new ValueImpl.CulpritImpl(steps));
                }
            }
        }
    }

    /*
     The first statement of mi's body (in visiting order) that modifies the static state of a type 'wanted' accepts:
     an assignment to, or a modifying call on, such a static field, or a call of a method or constructor whose set
     holds one. A call is followed into the callee for the type it contributes, so the chain ends at the
     assignment or modifying call itself, or at a callee without a body (a contract); a call whose callee yields no
     chain is passed over. Empty when nothing is found.
     */
    private List<Value.Culprit.Step> culprit(MethodInfo mi, java.util.function.Predicate<TypeInfo> wanted,
                                            Map<MethodInfo, Set<TypeInfo>> group, int depth, Set<MethodInfo> visited) {
        if (!visited.add(mi) || depth > CULPRIT_DEPTH) return List.of();
        List<Value.Culprit.Step> found = new java.util.ArrayList<>();
        java.util.function.Predicate<Element> visitor = e -> {
            if (!found.isEmpty()) return false;
            FieldReference direct = directStaticModification(e);
            if (direct != null && wanted.test(direct.fieldInfo().owner())) {
                String what = e instanceof Assignment ? "assigns " : "modifies ";
                found.add(step(mi, e, what + name(direct)));
                return false;
            }
            MethodInfo callee = callee(e);
            if (callee != null) {
                Set<TypeInfo> set = group.containsKey(callee) ? group.get(callee) : modified(callee);
                TypeInfo owner = set.stream().filter(wanted).findFirst().orElse(null);
                if (owner != null) {
                    if (!computable(callee)) {
                        found.add(step(mi, e, describeCall(e, callee) + ", contracted @StaticSideEffects"));
                        return false;
                    }
                    // a call whose callee yields no chain is no culprit: a recursive call (the callee is already
                    // being walked, LambdaProcessor.processClass(child) first in its own body), or one past the
                    // depth. The walk goes on to the next statement.
                    List<Value.Culprit.Step> inCallee = culprit(callee, owner::equals, group, depth + 1, visited);
                    if (!inCallee.isEmpty()) {
                        found.add(step(mi, e, describeCall(e, callee)));
                        found.addAll(inCallee);
                        return false;
                    }
                }
            }
            return true;
        };
        mi.methodBody().visit(visitor);
        if (found.isEmpty() && mi.isConstructor()) instanceInitializers(mi.typeInfo()).forEach(i -> i.visit(visitor));
        return found;
    }

    private static Value.Culprit.Step step(MethodInfo in, Element e, String text) {
        io.codelaser.maddi.cst.api.element.Source source = e.source();
        java.net.URI uri = in.compilationUnit() == null ? null : in.compilationUnit().uri();
        return new Value.Culprit.Step(uri == null ? null : uri.toString(),
                source == null ? 0 : source.beginLine(), source == null ? 0 : source.beginPos(), text);
    }

    private static String name(FieldReference fr) {
        return fr.fieldInfo().owner().simpleName() + "." + fr.fieldInfo().name();
    }

    private static String describeCall(Element e, MethodInfo callee) {
        String type = callee.typeInfo().simpleName();
        return switch (e) {
            case ConstructorCall _ -> "new " + type + "(..)";
            case ExplicitConstructorInvocation _ -> "calls " + type + "'s constructor";
            case MethodReference _ -> "references " + type + "::" + callee.name();
            default -> "calls " + type + "." + callee.name() + "(..)";
        };
    }

    /*
     The static field (or the static root of a field chain) that e assigns, or makes a modifying call on; null for
     anything else, an @IgnoreModifications field or method included. A call whose NON_MODIFYING_METHOD is not in
     counts, as it does in collect under cycle breaking, the only time one is still out at a decision.
     */
    private static FieldReference directStaticModification(Element e) {
        if (e instanceof Assignment a && OwnStaticState.staticRoot(a.variableTarget()) instanceof FieldReference fr) {
            return fr;
        }
        if (e instanceof MethodCall mc && mc.object() instanceof VariableExpression ve
            && OwnStaticState.staticRoot(ve.variable()) instanceof FieldReference fr && !mc.methodInfo().isIgnoreModification()) {
            Value.Bool nonModifying = mc.methodInfo().analysis()
                    .getOrNull(PropertyImpl.NON_MODIFYING_METHOD, ValueImpl.BoolImpl.class);
            if (nonModifying == null || nonModifying.isFalse()) return fr;
        }
        return null;
    }

    private static MethodInfo callee(Element e) {
        return switch (e) {
            case MethodCall mc -> mc.methodInfo();
            case ConstructorCall cc -> cc.constructor();
            case ExplicitConstructorInvocation eci -> eci.methodInfo();
            case MethodReference mr -> mr.methodInfo();
            default -> null;
        };
    }

    /*
     Adds to sets.get(mi) what mi's body modifies itself and what its decided callees modify; collects the callees
     of the group in inGroup. Returns true when the set depends on a decision still out.
     */
    private boolean collect(MethodInfo mi, Map<MethodInfo, Set<TypeInfo>> sets, Set<MethodInfo> inGroup,
                            boolean activateCycleBreaking) {
        Set<TypeInfo> set = sets.get(mi);
        boolean[] waits = {false};
        java.util.function.Predicate<Element> visitor = e -> {
            // assignment to a static field or a field of one: 'Other.STATIC = ...', 'count++', 'Other.STATIC.count
            // = 5'; not to an @IgnoreModifications one
            if (e instanceof Assignment a && OwnStaticState.staticRoot(a.variableTarget()) instanceof FieldReference fr) {
                set.add(fr.fieldInfo().owner());
            }
            // modifying call on a static field or a field of one: 'Other.STATIC.add(...)', 'Other.STATIC.list.add(..)'.
            // Not on an @IgnoreModifications field ('System.out.println(...)', road §050), nor of an
            // @IgnoreModifications method ('LOG.info(...)')
            if (e instanceof MethodCall mc && mc.object() instanceof VariableExpression ve
                && OwnStaticState.staticRoot(ve.variable()) instanceof FieldReference fr && !mc.methodInfo().isIgnoreModification()) {
                Value.Bool nonModifying = mc.methodInfo().analysis()
                        .getOrNull(PropertyImpl.NON_MODIFYING_METHOD, ValueImpl.BoolImpl.class);
                if (nonModifying == null && !activateCycleBreaking) waits[0] = true;
                else if (nonModifying == null || nonModifying.isFalse()) set.add(fr.fieldInfo().owner());
            }
            // what the callee modifies, static or instance method, constructor, this(...) or super(...); a method
            // or constructor reference as a lambda calling it, whose body counts as if it ran here
            MethodInfo callee = callee(e);
            if (callee != null) {
                if (sets.containsKey(callee)) inGroup.add(callee);
                else if (decided(callee)) set.addAll(modified(callee));
                else if (computable(callee) && !activateCycleBreaking) waits[0] = true;
            }
            return true;
        };
        mi.methodBody().visit(visitor);
        // an instance field's initializer runs in every constructor, as if written there ('int id = next++;')
        if (mi.isConstructor()) instanceInitializers(mi.typeInfo()).forEach(i -> i.visit(visitor));
        return waits[0];
    }

    // the initializers of the type's instance fields; a static field's is the construction of the static state
    private static List<Expression> instanceInitializers(TypeInfo typeInfo) {
        return typeInfo.fields().stream()
                .filter(fi -> !fi.isStatic() && fi.initializer() != null && !fi.initializer().isEmpty())
                .map(FieldInfo::initializer).toList();
    }

    // decided: computed here, or contracted (SourceContractMaterializer, an AAPI declaration)
    private static boolean decided(MethodInfo mi) {
        return mi.analysis().haveAnalyzedValueFor(PropertyImpl.STATIC_SIDE_EFFECTS_METHOD);
    }

    // a body this analyzer will decide; a shallow or abstract method is a static side effect only by contract
    private static boolean computable(MethodInfo mi) {
        return !mi.isAbstract() && (!mi.methodBody().isEmpty()
                                    || mi.isConstructor() && !instanceInitializers(mi.typeInfo()).isEmpty());
    }

    private Set<TypeInfo> modified(MethodInfo callee) {
        Set<TypeInfo> set = callee.analysis().getOrDefault(PropertyImpl.STATIC_STATE_MODIFIED_METHOD,
                ValueImpl.SetOfTypeInfoImpl.EMPTY).typeInfoSet();
        boolean contracted = callee.analysis().getOrDefault(PropertyImpl.STATIC_SIDE_EFFECTS_METHOD,
                ValueImpl.BoolImpl.FALSE).isTrue();
        if (!contracted || !set.isEmpty()) return set;
        return Set.of(global); // a contract: global state it does not name
    }

    /**
     * Does a modification of the static state of {@code owner} escape {@code methodInfo}'s own? The static state of
     * its primary type (or a nested type of it) is the type's own, a modification of the method, not a side effect;
     * so is a parent type's (road §050, {@link OwnStaticState}).
     */
    private static boolean escapes(MethodInfo methodInfo, TypeInfo owner) {
        return !OwnStaticState.owns(methodInfo, owner);
    }
}
