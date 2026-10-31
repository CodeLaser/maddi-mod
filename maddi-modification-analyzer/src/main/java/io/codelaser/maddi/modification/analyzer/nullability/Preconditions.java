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

package io.codelaser.maddi.modification.analyzer.nullability;

import io.codelaser.maddi.cst.api.element.Element;
import io.codelaser.maddi.cst.api.expression.Assignment;
import io.codelaser.maddi.cst.api.expression.BinaryOperator;
import io.codelaser.maddi.cst.api.expression.ConstructorCall;
import io.codelaser.maddi.cst.api.expression.Expression;
import io.codelaser.maddi.cst.api.expression.MethodCall;
import io.codelaser.maddi.cst.api.expression.NullConstant;
import io.codelaser.maddi.cst.api.expression.VariableExpression;
import io.codelaser.maddi.cst.api.info.MethodInfo;
import io.codelaser.maddi.cst.api.info.ParameterInfo;
import io.codelaser.maddi.cst.api.statement.ExplicitConstructorInvocation;
import io.codelaser.maddi.cst.api.statement.ExpressionAsStatement;
import io.codelaser.maddi.cst.api.statement.Statement;
import io.codelaser.maddi.cst.api.type.ParameterizedType;
import io.codelaser.maddi.modification.analyzer.nullability.NullabilityPass.NullArgument;
import io.codelaser.maddi.modification.analyzer.nullability.NullabilityPass.Policy;
import io.codelaser.maddi.modification.analyzer.nullability.NullabilityPass.World;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static io.codelaser.maddi.modification.analyzer.nullability.Types.*;

/**
 * Parameters a null argument makes throw before their method completes (CodeLaser/maddi-mod#41), with the calls
 * that may pass null to one ({@link NullabilityPass.Report#nullArguments}); those declared non-null under
 * Policy.preconditionsNonNull; and those a method rejects with its own exception (CodeLaser/maddi-mod#28).
 */
final class Preconditions {
    private final Policy policy;
    private final Index index;
    private final Nodes nodes;
    private final NonNullFacts facts;
    final Set<ParameterInfo> preconditions;

    Preconditions(Policy policy, Index index, Nodes nodes, NonNullFacts facts, Set<ParameterInfo> preconditions) {
        this.policy = policy;
        this.index = index;
        this.nodes = nodes;
        this.facts = facts;
        this.preconditions = preconditions;
    }

    private final Set<ParameterInfo> nullLiteralParameters = new HashSet<>();
    private final List<Object[]> preconditionArguments = new ArrayList<>();

    // gate NOFAMILYPRECONDITIONS: only methods no override replaces have preconditions, as before CodeLaser/maddi-mod#41
    private static final Switches.Gate FAMILY_PRECONDITIONS_OFF = Switches.gate("NOFAMILYPRECONDITIONS");

    Set<ParameterInfo> preconditions() {
        Set<ParameterInfo> set = new HashSet<>();
        Map<MethodInfo, Set<MethodInfo>> families = policy.world() == World.CLOSED && !FAMILY_PRECONDITIONS_OFF.get()
                ? index.families() : Map.of();
        for (MethodInfo mi : index.analysed) {
            if (families.containsKey(mi)) continue;
            // a call runs this body only when no override replaces it: an analysed one; in an open world any
            boolean overridable = !mi.isConstructor() && !mi.isStatic() && !mi.access().isPrivate()
                                  && !mi.isFinal() && !mi.typeInfo().isFinal();
            if (overridable && (policy.world() != World.CLOSED || index.implementations.containsKey(mi))) continue;
            for (ParameterInfo pi : mi.parameters()) {
                if (facts.nonNullAtExit(mi, pi)) set.add(pi);
            }
        }
        Set<Set<MethodInfo>> distinct = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        distinct.addAll(families.values());
        for (Set<MethodInfo> family : distinct) familyPreconditions(family, set);
        return Set.copyOf(set);
    }

    /*
     CodeLaser/maddi-mod#41. In a closed world a call to a method an override may replace runs one of its family: the
     analysed methods connected to it by overriding, up and down (an interface method, its implementations, their
     overrides, the lambdas implementing it). Kotlin and C# need the family to agree on a parameter's type anyway
     (f7c595bd, reverted: fernflower's ExprentIterator.processExprent kept a nullable interface parameter under non-null
     implementations). A library method does not join a family: a call to it is the library's contract, and Kotlin
     lets each override of a Java declaration choose ('equals(Object)' would otherwise make all of them one family).
     A parameter position is a precondition of the whole family when
     - no method reference implements one of its methods: the referenced method is not a member the pass sees;
     - every member with a body throws on null there: by its own body (NonNullFacts.nonNullAtExit), or by handing the
       parameter to a member that does, through 'super' (fernflower's IfStatement.replaceStatement);
     - at least one member has a body.
     */
    private void familyPreconditions(Set<MethodInfo> family, Set<ParameterInfo> set) {
        MethodInfo first = family.iterator().next();
        int n = first.parameters().size();
        for (MethodInfo m : family) {
            if (m.parameters().size() != n || this.index.referencedSams.contains(m)) {
                return;
            }
        }
        for (int i = 0; i < n; i++) {
            int index = i;
            Set<MethodInfo> throwing = new HashSet<>();
            for (MethodInfo m : family) {
                if (hasBody(m) && facts.nonNullAtExit(m, m.parameters().get(index))) throwing.add(m);
            }
            boolean added = true;
            while (added) {
                added = false;
                for (MethodInfo m : family) {
                    if (hasBody(m) && !throwing.contains(m) && passesToSuper(m, index, throwing)) {
                        added = throwing.add(m);
                    }
                }
            }
            if (throwing.isEmpty() || family.stream().anyMatch(m -> hasBody(m) && !throwing.contains(m))) continue;
            family.forEach(m -> set.add(m.parameters().get(index)));
        }
    }

    // what a call passes to a precondition parameter: per alternative of the argument, the literal null or its node,
    // decided after the closure (nullArguments). Not the caller's own precondition parameter passed on
    // ('super.replace(oldstat, newstat)'): a null there is reported where it enters the caller
    void preconditionArgument(MethodInfo mi, Scope scope, Statement statement, Expression call,
                                      ParameterInfo parameter, Expression argument) {
        if (NonNullFacts.unwrap(argument) instanceof VariableExpression own && own.variable() instanceof ParameterInfo pi
            && preconditions.contains(pi) && !this.nodes.reassigned.contains(pi)) {
            return;
        }
        boolean literal = false;
        List<Object> nodes = new ArrayList<>();
        for (Expression alternative : alternatives(argument)) {
            Expression unwrapped = NonNullFacts.unwrap(alternative);
            if (unwrapped instanceof NullConstant) {
                literal = true;
            } else if (!(unwrapped instanceof VariableExpression ve
                         && (call != null && facts.nonNullWhenCalled(call, ve.variable())
                             || facts.nonNullAt(statement, ve.variable())))) {
                Object node = this.nodes.argumentNode(mi, scope, alternative);
                if (node != null) nodes.add(node);
            }
        }
        if (literal) nullLiteralParameters.add(parameter);
        preconditionArguments.add(new Object[]{call != null ? call : statement, parameter, argument, literal, nodes});
    }

    List<NullArgument> nullArguments(Set<Object> reached) {
        List<NullArgument> list = new ArrayList<>();
        for (Object[] a : preconditionArguments) {
            boolean literal = (Boolean) a[3];
            @SuppressWarnings("unchecked") List<Object> nodes = (List<Object>) a[4];
            if (literal || nodes.stream().anyMatch(reached::contains)) {
                list.add(new NullArgument((Element) a[0], (ParameterInfo) a[1], (Expression) a[2], literal));
            }
        }
        return List.copyOf(list);
    }

    /*
     Policy.preconditionsNonNull: the precondition parameters declared non-null, a family at a time (its members' types
     must agree). Not a family a call passes the literal null to ('f(null)' always throws, and 'f(null!!)' says nothing
     more), nor one a method reference hands to outside code, a member overriding a library method calls back
     (Policy.libraryCallbacks), or JUnit fills from a null source. Nor one a member compares with null: its
     'if (p == null) throw new IllegalArgumentException(...)' is the method's answer to null, which the caller's
     assertion would replace by an NPE (langchain4j's ensureNotNull; CodeLaser/maddi-mod#28).
     */
    Set<ParameterInfo> declaredPreconditions() {
        Map<MethodInfo, Set<MethodInfo>> families = policy.world() == World.CLOSED && !FAMILY_PRECONDITIONS_OFF.get()
                ? index.families() : Map.of();
        Set<ParameterInfo> declared = new HashSet<>();
        for (ParameterInfo pi : preconditions) {
            Set<MethodInfo> family = families.getOrDefault(pi.methodInfo(), Set.of(pi.methodInfo()));
            boolean excluded = family.stream().anyMatch(m -> m.parameters().size() <= pi.index()
                    || nullLiteralParameters.contains(m.parameters().get(pi.index()))
                    || index.referencedMethods.contains(m) || junitNullSource(m)
                    || hasBody(m) && comparedWithNull(m, m.parameters().get(pi.index()), new HashSet<>())
                    || overrides(m).stream().anyMatch(o -> !index.analysed.contains(o)));
            if (!excluded) declared.add(pi);
        }
        return declared;
    }

    /*
     CodeLaser/maddi-mod#28: the parameters whose method answers null with its own check rather than a dereference. A
     precondition (needed on every normal exit) that the method, or an analysed callee it hands it to, compares with
     null first: 'if (p == null) throw new IllegalArgumentException(...)', langchain4j's ensureNotBlank. The printer
     reports them where they stay non-null; Policy.rejectionsNullable makes them nullable.
     */
    Set<ParameterInfo> rejectsNull() {
        Set<ParameterInfo> set = new LinkedHashSet<>();
        for (ParameterInfo pi : preconditions) {
            if (rejects(pi)) set.add(pi);
        }
        return Set.copyOf(set);
    }

    boolean rejects(ParameterInfo pi) {
        ParameterizedType type = pi.parameterizedType();
        MethodInfo m = pi.methodInfo();
        return preconditions.contains(pi) && !(type.isPrimitiveExcludingVoid() && type.arrays() == 0) && hasBody(m)
               && comparedWithNull(m, pi, new HashSet<>());
    }

    /*
     The parameter is compared with null in m's body, or handed to an analysed method that compares its own parameter
     with null, transitively: langchain4j's 'if (isNullOrBlank(string)) throw illegalArgument(...)' in ensureNotBlank,
     where isNullOrBlank says 'string == null || ...' (CodeLaser/maddi#125, the Kotlin measurement).
     */
    private boolean comparedWithNull(MethodInfo m, ParameterInfo pi, Set<ParameterInfo> seen) {
        if (!seen.add(pi)) return false;
        boolean[] found = {false};
        m.methodBody().visit(e -> {
            if (e instanceof BinaryOperator bo) {
                Expression other = bo.lhs() instanceof NullConstant ? bo.rhs()
                        : bo.rhs() instanceof NullConstant ? bo.lhs() : null;
                if (other instanceof VariableExpression ve && pi.equals(ve.variable())) found[0] = true;
            } else {
                // a call, a constructor call (Document.from(text) is 'new DefaultDocument(text)'), this(...), super(...)
                MethodInfo callee = null;
                List<Expression> arguments = null;
                if (e instanceof MethodCall mc) {
                    callee = mc.methodInfo();
                    arguments = mc.parameterExpressions();
                } else if (e instanceof ConstructorCall cc && cc.constructor() != null && cc.anonymousClass() == null) {
                    callee = cc.constructor();
                    arguments = cc.parameterExpressions();
                } else if (e instanceof ExplicitConstructorInvocation eci && eci.methodInfo() != null) {
                    callee = eci.methodInfo();
                    arguments = eci.parameterExpressions();
                }
                if (callee != null && index.analysed.contains(callee) && hasBody(callee)
                    && passedOnAndTested(pi, callee, arguments, seen)) {
                    found[0] = true;
                }
            }
            return !found[0];
        });
        return found[0];
    }

    private boolean passedOnAndTested(ParameterInfo pi, MethodInfo callee, List<Expression> arguments,
                                      Set<ParameterInfo> seen) {
        List<ParameterInfo> calleeParameters = callee.parameters();
        for (int i = 0; i < arguments.size() && i < calleeParameters.size(); i++) {
            if (arguments.get(i) instanceof VariableExpression ve && pi.equals(ve.variable())
                && !calleeParameters.get(i).isVarArgs()
                && comparedWithNull(callee, calleeParameters.get(i), seen)) {
                return true;
            }
        }
        return false;
    }

    /*
     The method hands its parameter to a member of 'to' through 'super', as a statement of its body that every normal
     exit passes (nothing before it may leave), and never assigns the parameter.
     */
    private static boolean passesToSuper(MethodInfo m, int index, Set<MethodInfo> to) {
        ParameterInfo pi = m.parameters().get(index);
        boolean[] assigned = {false};
        m.methodBody().visit(e -> {
            if (e instanceof Assignment a && pi.equals(a.variableTarget())) assigned[0] = true;
            return !assigned[0];
        });
        if (assigned[0]) return false;
        for (Statement statement : m.methodBody().statements()) {
            if (statement instanceof ExpressionAsStatement eas && eas.expression() instanceof MethodCall mc
                && to.contains(mc.methodInfo()) && index < mc.parameterExpressions().size()
                && mc.object() instanceof VariableExpression receiver
                && receiver.variable() instanceof io.codelaser.maddi.cst.api.variable.This t && t.writeSuper()
                && NonNullFacts.unwrap(mc.parameterExpressions().get(index)) instanceof VariableExpression ve
                && pi.equals(ve.variable())) {
                return true;
            }
            if (mayLeave(statement)) return false;
        }
        return false;
    }
}
