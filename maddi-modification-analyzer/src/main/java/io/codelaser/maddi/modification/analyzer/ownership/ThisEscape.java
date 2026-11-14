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

package io.codelaser.maddi.modification.analyzer.ownership;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import io.codelaser.maddi.cst.api.element.Element;
import io.codelaser.maddi.cst.api.expression.ConstructorCall;
import io.codelaser.maddi.cst.api.expression.Expression;
import io.codelaser.maddi.cst.api.expression.Lambda;
import io.codelaser.maddi.cst.api.expression.MethodCall;
import io.codelaser.maddi.cst.api.expression.MethodReference;
import io.codelaser.maddi.cst.api.expression.VariableExpression;
import io.codelaser.maddi.cst.api.info.MethodInfo;
import io.codelaser.maddi.cst.api.statement.ExplicitConstructorInvocation;
import io.codelaser.maddi.cst.api.statement.ReturnStatement;
import io.codelaser.maddi.cst.api.variable.FieldReference;
import io.codelaser.maddi.cst.api.variable.This;
import io.codelaser.maddi.cst.impl.analysis.PropertyImpl;
import io.codelaser.maddi.cst.impl.analysis.ValueImpl;

/**
 * Which methods and constructors may leave {@code this} held by something that outlives the call. The link summaries
 * have no links for {@code this}: {@code void leak() { ALL.add(this); }} has an empty summary, so a caller's
 * {@code k.leak()} shows nothing although {@code k} is now held by a static. (A constructor is the exception: its
 * {@code this} is the new object, and {@code bus.register(this)} in a constructor reaches the caller as a link.)
 * <p>
 * Conservative, from the bodies. A method or constructor leaks {@code this} when its body
 * <ul>
 *   <li>uses {@code this} as a value: an argument, an assigned value, an element; not as the receiver of a call, the
 *   scope of a field, or a returned value ({@code return this}: what the caller does with the result is the
 *   caller's links);</li>
 *   <li>creates something that captures it: a lambda that mentions {@code this}, {@code this::m}, an anonymous class,
 *   an instance of an inner class;</li>
 *   <li>calls, on {@code this} ({@code this.m()}, {@code m()}, {@code this(..)}, {@code super(..)}), a method or
 *   constructor that leaks it, or one that may dispatch to a body that does ({@link #mayLeak}).</li>
 * </ul>
 * A least fixpoint over the analysed methods: a method leaks once one of its own calls on {@code this} does.
 */
final class ThisEscape {
    private final Map<MethodInfo, String> leaking = new LinkedHashMap<>();
    private final Set<MethodInfo> analysed;

    ThisEscape(Set<MethodInfo> analysed) {
        this.analysed = analysed;
        Map<MethodInfo, Set<MethodInfo>> callsOnThis = new LinkedHashMap<>();
        for (MethodInfo methodInfo : analysed) {
            if (methodInfo.isStatic() || methodInfo.methodBody() == null) continue;
            Set<MethodInfo> calls = new LinkedHashSet<>();
            String direct = direct(methodInfo, calls);
            if (direct != null) leaking.put(methodInfo, direct);
            else callsOnThis.put(methodInfo, calls);
        }
        boolean changed = true;
        while (changed) {
            changed = false;
            for (Map.Entry<MethodInfo, Set<MethodInfo>> e : callsOnThis.entrySet()) {
                if (leaking.containsKey(e.getKey())) continue;
                for (MethodInfo callee : e.getValue()) {
                    if (mayLeak(callee)) {
                        leaking.put(e.getKey(), "calls " + callee.fullyQualifiedName() + " on this, which may leak it");
                        changed = true;
                        break;
                    }
                }
            }
        }
    }

    /**
     * Whether a call to {@code callee}, with some object as receiver, may leave that object held elsewhere: the callee
     * leaks {@code this}, or may dispatch to a body that does. A library method is described by its hints, unless
     * sources implement it.
     */
    boolean mayLeak(MethodInfo callee) {
        if (callee.isStatic()) return false;
        if (leaking.containsKey(callee)) return true;
        Set<MethodInfo> implementations = OwnershipPass.implementationsOf(callee);
        if (implementations.stream().anyMatch(leaking::containsKey)) return true;
        if (callee.typeInfo().compilationUnit().externalLibrary()) {
            return OwnershipPass.externalImplementationsOf(callee).stream()
                    .anyMatch(i -> leaking.containsKey(i) || !analysed.contains(i));
        }
        // a source abstract method without implementations in the sources: any implementation, seen or not
        return callee.isAbstract() && implementations.isEmpty();
    }

    /** Why {@code methodInfo} leaks {@code this}; null when it does not (as far as the bodies say). */
    String why(MethodInfo methodInfo) {
        return leaking.get(methodInfo);
    }

    // the direct reasons; the calls on 'this' go to 'calls'
    private static String direct(MethodInfo methodInfo, Set<MethodInfo> calls) {
        Set<Expression> allowed = Collections.newSetFromMap(new IdentityHashMap<>());
        String[] found = new String[1];
        boolean[] explicitConstructorInvocation = new boolean[1];
        methodInfo.methodBody().visit((Element e) -> {
            if (found[0] != null) return false;
            switch (e) {
                case Lambda lambda -> {
                    if (mentionsThis(lambda)) found[0] = "a lambda captures this";
                    return false;
                }
                case MethodReference mr -> {
                    if (isThis(mr.scope())) found[0] = "this::" + mr.methodInfo().name();
                    return false;
                }
                case ConstructorCall cc -> {
                    if (cc.anonymousClass() != null) {
                        found[0] = "an anonymous class captures this";
                        return false;
                    }
                    if (cc.callsConstructorOfCreatedType() && cc.constructor().typeInfo().isInnerClass() && cc.object() == null) {
                        found[0] = "an inner class instance captures this";
                        return false;
                    }
                }
                case MethodCall mc -> {
                    if (isThis(mc.object())) {
                        allowed.add(mc.object());
                        if (mc.methodInfo() != null) calls.add(mc.methodInfo());
                    }
                }
                case ExplicitConstructorInvocation eci -> {
                    explicitConstructorInvocation[0] = true;
                    if (eci.methodInfo() != null) calls.add(eci.methodInfo());
                }
                case ReturnStatement rs -> {
                    if (isThis(rs.expression())) allowed.add(rs.expression());
                }
                case VariableExpression ve -> {
                    if (ve.variable() instanceof FieldReference fr && isThis(fr.scope())) {
                        allowed.add(fr.scope());
                    } else if (ve.variable() instanceof This && !allowed.contains(ve)) {
                        found[0] = "this as a value";
                        return false;
                    }
                }
                default -> {
                }
            }
            return true;
        });
        if (found[0] == null && methodInfo.isConstructor() && !explicitConstructorInvocation[0]) {
            // the implicit super(): the parent's constructor without parameters runs on this object too
            var parent = methodInfo.typeInfo().parentClass();
            if (parent != null && parent.typeInfo() != null) {
                parent.typeInfo().constructors().stream().filter(c -> c.parameters().isEmpty()).forEach(calls::add);
            }
        }
        return found[0];
    }

    private static boolean isThis(Expression expression) {
        return expression instanceof VariableExpression ve && ve.variable() instanceof This;
    }

    private static boolean mentionsThis(Lambda lambda) {
        if (lambda.methodInfo() == null || lambda.methodInfo().methodBody() == null) return true;
        boolean[] mentions = new boolean[1];
        lambda.methodInfo().methodBody().visit((Element e) -> {
            if (mentions[0]) return false;
            if (e instanceof VariableExpression ve && (ve.variable() instanceof This
                                                       || ve.variable() instanceof FieldReference fr
                                                          && fr.scopeIsRecursivelyThis())
                || e instanceof MethodCall mc && isThis(mc.object())) {
                mentions[0] = true;
                return false;
            }
            return true;
        });
        return mentions[0];
    }
}
