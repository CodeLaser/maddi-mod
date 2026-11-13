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

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.codelaser.maddi.cst.api.element.Element;
import io.codelaser.maddi.cst.api.expression.Expression;
import io.codelaser.maddi.cst.api.expression.Lambda;
import io.codelaser.maddi.cst.api.expression.MethodCall;
import io.codelaser.maddi.cst.api.expression.NullConstant;
import io.codelaser.maddi.cst.api.expression.VariableExpression;
import io.codelaser.maddi.cst.api.info.Info;
import io.codelaser.maddi.cst.api.info.MethodInfo;
import io.codelaser.maddi.cst.api.info.TypeInfo;
import io.codelaser.maddi.cst.api.variable.FieldReference;
import io.codelaser.maddi.cst.api.variable.LocalVariable;
import io.codelaser.maddi.cst.api.variable.Variable;
import io.codelaser.maddi.cst.impl.analysis.PropertyImpl;
import io.codelaser.maddi.cst.impl.analysis.ValueImpl;
import io.codelaser.maddi.modification.link.impl.LinkNatureImpl;
import io.codelaser.maddi.modification.link.impl.MethodLinkedVariablesImpl;
import io.codelaser.maddi.modification.prepwork.variable.Link;
import io.codelaser.maddi.modification.prepwork.variable.Links;
import io.codelaser.maddi.modification.prepwork.variable.MethodLinkedVariables;
import io.codelaser.maddi.modification.prepwork.variable.ObjectCreationVariable;
import io.codelaser.maddi.modification.prepwork.variable.ReturnVariable;

/**
 * The ownership pass (docs/roadmap/ownership.md): one-shot, after the analysis has converged, over the link
 * summaries it left. It adds properties and never changes one the iteration wrote; nothing in the iteration reads
 * what it writes.
 *
 * <h2>{@code FRESH_METHOD}</h2>
 * A method is fresh when every object it returns was created during the call, or is {@code null}, and nothing
 * outside the call holds it afterwards. The evidence is <b>positive</b>: the creation marker {@code ret ← oc:N} that
 * the link engine writes when object creations are tracked. Absence of links is never evidence: a library call
 * without a summary ({@code Collections.emptyList()}, a singleton) has no links at all.
 * <ol>
 *   <li>Every link from the return variable itself (not from a field of it: {@code copy.§$s ⊆ 0:l.§$s} says what a
 *   new object holds) is an assignment from, or identity with, a creation marker or the {@code null} marker
 *   {@code $_ce}; at least one is a creation marker. A link to a field, a parameter or a static, or from the return
 *   value into one ({@code ret → this.stored}, {@code ret ∈ 0:sink.§$s}), means the object is, or is also held,
 *   elsewhere.</li>
 *   <li>The creation marker travels through the callees' summaries ({@code return make();} carries {@code make}'s
 *   marker), and a summary is that of the declared callee's body: a call {@code base.make()} carries the marker of
 *   {@code Base.make} although {@code Sub.make} returns a field. So a callee whose summary carries one of the
 *   method's markers must itself be fresh, and a method is fresh only when all its implementations
 *   ({@code IMPLEMENTATIONS}, source overrides included since CodeLaser/maddi-mod#84) are.</li>
 *   <li>For the same reason, a local handed to a method whose summary is not the body that will run may be kept by
 *   that body: a call with a local as receiver or argument to a source abstract method, a library abstract method
 *   with source implementations, or a concrete method that sources override, refuses the method. (An abstract
 *   method's summary relates the argument's contents to the receiver, never the argument itself.) Stage 2's
 *   borrowed parameters, folded over the implementations, can relax this.</li>
 *   <li>A degraded method (its summary is the shallow one) is not fresh.</li>
 * </ol>
 * Rule 2 is decided as a greatest fixpoint: every candidate of rule 1 starts fresh, and a method whose
 * dependency is not fresh is removed until nothing changes. Recursion keeps its freshness that way, which is sound
 * because no member of a cycle can return an object that was not created by some member (rule 1 holds for each).
 * <p>
 * Requires {@code trackObjectCreations}: without it there are no creation markers and nothing is fresh.
 */
public final class OwnershipPass {
    private static final Logger LOGGER = LoggerFactory.getLogger(OwnershipPass.class);

    /** The verdicts, and for a method that is not fresh, the first reason found (the census's worklist). */
    public record Report(Map<MethodInfo, Boolean> fresh, Map<MethodInfo, String> notFreshReason) {
    }

    public Report go(List<Info> analysisOrder) {
        Set<MethodInfo> methods = methods(analysisOrder);
        Map<MethodInfo, String> reason = new LinkedHashMap<>();
        Map<MethodInfo, Set<ObjectCreationVariable>> markers = new LinkedHashMap<>();
        Set<MethodInfo> fresh = new LinkedHashSet<>();
        for (MethodInfo methodInfo : methods) {
            String r = candidate(methodInfo, markers);
            if (r == null) fresh.add(methodInfo);
            else reason.put(methodInfo, r);
        }
        Map<MethodInfo, List<MethodInfo>> dependencies = new LinkedHashMap<>();
        for (MethodInfo methodInfo : fresh) {
            dependencies.put(methodInfo, dependencies(methodInfo, markers.get(methodInfo)));
        }
        boolean changed = true;
        while (changed) {
            changed = false;
            for (MethodInfo methodInfo : List.copyOf(fresh)) {
                for (MethodInfo dependency : dependencies.get(methodInfo)) {
                    if (!fresh.contains(dependency)) {
                        fresh.remove(methodInfo);
                        reason.put(methodInfo, (isImplementation(methodInfo, dependency) ? "implementation "
                                : "callee ") + dependency.fullyQualifiedName() + " is not fresh");
                        changed = true;
                        break;
                    }
                }
            }
        }
        Map<MethodInfo, Boolean> verdicts = new LinkedHashMap<>();
        for (MethodInfo methodInfo : methods) verdicts.put(methodInfo, fresh.contains(methodInfo));
        LOGGER.info("Ownership pass: {} method(s) with a reference result, {} fresh", methods.size(), fresh.size());
        return new Report(verdicts, reason);
    }

    public static void write(Report report) {
        report.fresh().forEach((methodInfo, fresh) -> methodInfo.analysis()
                .setAllowControlledOverwrite(PropertyImpl.FRESH_METHOD, ValueImpl.BoolImpl.from(fresh)));
    }

    // the methods the property is about: a result that is an object
    private static Set<MethodInfo> methods(List<Info> analysisOrder) {
        Set<MethodInfo> methods = new LinkedHashSet<>();
        for (Info info : analysisOrder) {
            if (info instanceof MethodInfo methodInfo) {
                if (applies(methodInfo)) methods.add(methodInfo);
            } else if (info instanceof TypeInfo typeInfo) {
                // an order may list types only (tests); their methods are what the property is about
                typeInfo.methodStream().filter(OwnershipPass::applies).forEach(methods::add);
            }
        }
        return methods;
    }

    private static boolean applies(MethodInfo methodInfo) {
        return !methodInfo.isConstructor() && !methodInfo.isVoid()
               && !methodInfo.returnType().isPrimitiveExcludingVoid();
    }

    /*
     Rule 1 (and 4). Null when the method is a candidate; then its creation markers are in 'markers'. An abstract
     method is a candidate: its implementations decide (rule 2).
     */
    private static String candidate(MethodInfo methodInfo, Map<MethodInfo, Set<ObjectCreationVariable>> markers) {
        if (methodInfo.analysis().getOrDefault(PropertyImpl.DEGRADED_ANALYSIS_METHOD, ValueImpl.BoolImpl.FALSE)
                .isTrue()) {
            return "degraded";
        }
        if (methodInfo.isAbstract()) {
            if (implementations(methodInfo).isEmpty()) return "abstract, no implementation";
            markers.put(methodInfo, Set.of());
            return null;
        }
        MethodLinkedVariables mlv = methodInfo.analysis().getOrNull(MethodLinkedVariablesImpl.METHOD_LINKS,
                MethodLinkedVariablesImpl.class);
        if (mlv == null) return "no summary";
        Set<ObjectCreationVariable> created = new LinkedHashSet<>();
        for (Link link : mlv.ofReturnValue()) {
            if (link.linkNature().isDecoration() || !(link.from() instanceof ReturnVariable)) continue;
            Variable to = link.to();
            boolean assignedFrom = link.linkNature() == LinkNatureImpl.IS_ASSIGNED_FROM
                                   || link.linkNature().isIdenticalTo();
            if (assignedFrom && to instanceof ObjectCreationVariable oc) {
                created.add(oc);
            } else if (!(assignedFrom && isNullConstant(to))) {
                return "result " + link.linkNature() + " " + to;
            }
        }
        if (created.isEmpty()) return "no creation marker";
        String overridden = localPassedToOverriddenMethod(methodInfo);
        if (overridden != null) return "local passed to " + overridden + ", which may dispatch to another body";
        markers.put(methodInfo, created);
        return null;
    }

    /*
     Rule 3: a call, with a local as receiver or argument, whose summary is not that of the body that will run: an
     override may keep the local, which may be the result. The summary of an abstract method says how its arguments'
     contents relate to the receiver ('0:l.§m ≡ this.§m'), not that the argument itself is kept, although the fold
     over the implementations knows it is dependent (TestFreshMethod 'registered').
     */
    private static String localPassedToOverriddenMethod(MethodInfo methodInfo) {
        if (methodInfo.methodBody() == null) return null;
        String[] found = new String[1];
        methodInfo.methodBody().visit((Element e) -> {
            if (found[0] != null || e instanceof Lambda) return false;
            if (e instanceof MethodCall mc && mc.methodInfo() != null && mayDispatchElsewhere(mc.methodInfo())
                && Stream.concat(Stream.of(mc.object()), mc.parameterExpressions().stream())
                        .anyMatch(OwnershipPass::isLocal)) {
                found[0] = mc.methodInfo().fullyQualifiedName();
                return false;
            }
            return true;
        });
        return found[0];
    }

    // a constant marker ('$_ceN', the link module's MarkerVariable, seen through the API) holding the null constant;
    // as the nullability pass's MethodWalker.isNullMarker
    private static boolean isNullConstant(Variable variable) {
        return variable instanceof LocalVariable lv && lv.simpleName().startsWith("$_ce")
               && lv.assignmentExpression() instanceof NullConstant;
    }

    /*
     Rule 2: the methods whose freshness this one's rests on.
     */
    private static List<MethodInfo> dependencies(MethodInfo methodInfo, Set<ObjectCreationVariable> created) {
        List<MethodInfo> dependencies = new ArrayList<>(implementations(methodInfo));
        if (methodInfo.methodBody() == null) return dependencies;
        Set<MethodInfo> callees = new LinkedHashSet<>();
        methodInfo.methodBody().visit((Element e) -> {
            if (e instanceof Lambda) return false; // its calls are its own
            if (e instanceof MethodCall mc && mc.methodInfo() != null) callees.add(mc.methodInfo());
            return true;
        });
        for (MethodInfo callee : callees) {
            if (!Collections.disjoint(created, markersInSummary(callee))) dependencies.add(callee);
        }
        return dependencies;
    }

    /*
     A source abstract method: any implementation, seen or not. A library abstract method: its source implementations
     (a library method without them is described by its hints). A concrete method: its source overrides.
     */
    private static boolean mayDispatchElsewhere(MethodInfo callee) {
        if (callee.isAbstract()) {
            if (!callee.typeInfo().compilationUnit().externalLibrary()) return true;
            return !callee.analysis().getOrDefault(PropertyImpl.EXTERNAL_IMPLEMENTATIONS,
                    ValueImpl.SetOfMethodInfoImpl.EMPTY).isEmpty();
        }
        return !implementations(callee).isEmpty();
    }

    private static boolean isLocal(Expression expression) {
        if (!(expression instanceof VariableExpression ve)) return false;
        Variable v = ve.variable();
        while (v instanceof FieldReference fr && fr.scopeVariable() != null) v = fr.scopeVariable();
        return v instanceof LocalVariable;
    }

    // every creation marker in a summary: the return value's links and the parameters'
    private static Set<ObjectCreationVariable> markersInSummary(MethodInfo methodInfo) {
        MethodLinkedVariables mlv = methodInfo.analysis().getOrNull(MethodLinkedVariablesImpl.METHOD_LINKS,
                MethodLinkedVariablesImpl.class);
        if (mlv == null) return Set.of();
        Set<ObjectCreationVariable> set = new HashSet<>();
        Stream.concat(Stream.of(mlv.ofReturnValue()), mlv.ofParameters().stream())
                .flatMap(Links::stream)
                .flatMap(link -> Stream.concat(link.from().variableStreamDescend(), link.to().variableStreamDescend()))
                .forEach(v -> {
                    if (v instanceof ObjectCreationVariable oc) set.add(oc);
                });
        return set;
    }

    private static List<MethodInfo> implementations(MethodInfo methodInfo) {
        List<MethodInfo> list = new ArrayList<>();
        methodInfo.analysis().getOrDefault(PropertyImpl.IMPLEMENTATIONS, ValueImpl.SetOfMethodInfoImpl.EMPTY)
                .methodInfoSet().forEach(list::add);
        return list;
    }

    private static boolean isImplementation(MethodInfo methodInfo, MethodInfo dependency) {
        return implementations(methodInfo).contains(dependency);
    }
}
