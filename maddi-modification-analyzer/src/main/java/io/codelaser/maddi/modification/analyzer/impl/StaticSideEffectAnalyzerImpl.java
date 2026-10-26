/*
 * maddi: a modification analyzer for duplication detection and immutability.
 * Copyright 2020-2025, Bart Naudts, https://github.com/CodeLaser/maddi
 *
 * This program is free software: you can redistribute it and/or modify it under the
 * terms of the GNU Lesser General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later version.
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE.  See the GNU Lesser General Public License for
 * more details. You should have received a copy of the GNU Lesser General Public
 * License along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package io.codelaser.maddi.modification.analyzer.impl;

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
        }
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
            // assignment to a static field: 'Other.STATIC = ...', 'count++'; not to an @IgnoreModifications one
            if (e instanceof Assignment a && a.variableTarget() instanceof FieldReference fr
                && fr.fieldInfo().isStatic() && !fr.fieldInfo().isIgnoreModifications()) {
                set.add(fr.fieldInfo().owner());
            }
            // modifying call on a static field: 'Other.STATIC.add(...)'. Not on an @IgnoreModifications field
            // ('System.out.println(...)', road §050), nor of an @IgnoreModifications method ('LOG.info(...)')
            if (e instanceof MethodCall mc && mc.object() instanceof VariableExpression ve
                && ve.variable() instanceof FieldReference fr && fr.fieldInfo().isStatic()
                && !fr.fieldInfo().isIgnoreModifications() && !mc.methodInfo().isIgnoreModification()) {
                Value.Bool nonModifying = mc.methodInfo().analysis()
                        .getOrNull(PropertyImpl.NON_MODIFYING_METHOD, ValueImpl.BoolImpl.class);
                if (nonModifying == null && !activateCycleBreaking) waits[0] = true;
                else if (nonModifying == null || nonModifying.isFalse()) set.add(fr.fieldInfo().owner());
            }
            // what the callee modifies, static or instance method, constructor, this(...) or super(...); a method
            // or constructor reference as a lambda calling it, whose body counts as if it ran here
            MethodInfo callee = switch (e) {
                case MethodCall mc -> mc.methodInfo();
                case ConstructorCall cc -> cc.constructor();
                case ExplicitConstructorInvocation eci -> eci.methodInfo();
                case MethodReference mr -> mr.methodInfo();
                default -> null;
            };
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
     * its primary type (or a nested type of it) is the type's own, handled by the static-field /
     * {@code @IgnoreModifications} rules, not a side effect; so is a parent type's (road §050).
     */
    private static boolean escapes(MethodInfo methodInfo, TypeInfo owner) {
        if (owner.primaryType() == methodInfo.typeInfo().primaryType()) return false;
        return methodInfo.typeInfo().superTypesExcludingJavaLangObject().stream().noneMatch(st -> st == owner);
    }
}
