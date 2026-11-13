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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Stream;

import io.codelaser.maddi.cst.api.element.Element;
import io.codelaser.maddi.cst.api.expression.Assignment;
import io.codelaser.maddi.cst.api.expression.Cast;
import io.codelaser.maddi.cst.api.expression.ConstructorCall;
import io.codelaser.maddi.cst.api.expression.EnclosedExpression;
import io.codelaser.maddi.cst.api.expression.Expression;
import io.codelaser.maddi.cst.api.expression.Lambda;
import io.codelaser.maddi.cst.api.expression.MethodCall;
import io.codelaser.maddi.cst.api.expression.MethodReference;
import io.codelaser.maddi.cst.api.expression.VariableExpression;
import io.codelaser.maddi.cst.api.info.MethodInfo;
import io.codelaser.maddi.cst.api.info.ParameterInfo;
import io.codelaser.maddi.cst.api.statement.ExplicitConstructorInvocation;
import io.codelaser.maddi.cst.api.statement.LocalVariableCreation;
import io.codelaser.maddi.cst.api.variable.LocalVariable;
import io.codelaser.maddi.cst.api.variable.Variable;
import io.codelaser.maddi.cst.impl.analysis.PropertyImpl;
import io.codelaser.maddi.cst.impl.analysis.ValueImpl;
import io.codelaser.maddi.modification.link.impl.MethodLinkedVariablesImpl;
import io.codelaser.maddi.modification.prepwork.variable.Link;
import io.codelaser.maddi.modification.prepwork.variable.Links;
import io.codelaser.maddi.modification.prepwork.variable.MethodLinkedVariables;

/**
 * {@code BORROWED_PARAMETER} (docs/roadmap/ownership.md stage 2): nothing that outlives the call holds the argument's
 * object afterwards. The contents may be kept ({@code list.addAll(p)} borrows {@code p}); an immutable object that is
 * kept is kept ({@code name = s} does not borrow {@code s}), which is where this differs from independence.
 * <ol>
 *   <li><b>Links.</b> No link goes from the parameter itself to anything ({@code 0:l → this.stored},
 *   {@code 0:l ∈∈ this.all.§$ss}, {@code 1:l ∈ 0:m.§$$s}), and no link from elsewhere reaches the parameter itself
 *   ({@code ret ← 0:l}, {@code ret.held ← 0:l}). Links from or to its contents ({@code 0:l.§$s ~ this.list.§$s},
 *   {@code ret ∈ 0:l.§$s}) do not count.</li>
 *   <li><b>Captures</b>, which no link shows ({@code return () -> l.size();} has no link to {@code l}): the parameter,
 *   or a local assigned from it, is not mentioned by a lambda, {@code l::m}, or an anonymous class.</li>
 *   <li><b>{@code this}</b>, which has no links: the parameter is not the receiver of a method that may leak it
 *   ({@link ThisEscape}).</li>
 *   <li><b>Callees.</b> Passed to a method or constructor of the analysed sources, the callee must not keep it in a
 *   way its links do not show (rules 2 to 6, its "hidden" verdict): its link keeps are applied at the call site and
 *   are rule 1's in the caller, so {@code new Holder(l).size()} borrows {@code l} and {@code return new Holder(l)}
 *   does not. Passed to a library method that sources implement, every such implementation's parameter must be
 *   borrowed outright: the summary applied is not theirs. Other library methods are described by their hints.</li>
 *   <li><b>Dispatch.</b> A call through the method may run an override: every implementation's parameter must be
 *   borrowed. A source abstract method without implementations borrows nothing.</li>
 *   <li>A degraded method (its summary is the shallow one) borrows nothing.</li>
 * </ol>
 * Rules 4 and 5 are a greatest fixpoint, as for freshness. A {@code @Borrowed} contract decides a parameter of a
 * bodiless method.
 */
final class BorrowedParameters {
    private static final String BORROWED_ANNOTATION = "io.codelaser.maddi.annotation.Borrowed";

    private final Set<MethodInfo> analysed;
    // borrowed as far as rules 2 to 6 go: what a caller of a concrete callee needs, the callee's links reaching the
    // caller's summary at the call site
    private final Set<ParameterInfo> hidden = new LinkedHashSet<>();
    // rule 1's reason, when the parameter's own links keep it
    private final Map<ParameterInfo, String> linkReasons = new LinkedHashMap<>();
    private final Map<ParameterInfo, String> reasons = new LinkedHashMap<>();
    private final Map<ParameterInfo, Boolean> verdicts = new LinkedHashMap<>();

    /*
     A dependency: the parameter of a direct callee needs only the hidden verdict (its link keeps are applied at the
     call site); that of an implementation, a body other than the one whose summary is applied, needs the full one.
     */
    private record Dep(ParameterInfo parameter, boolean full) {
    }

    BorrowedParameters(Set<MethodInfo> analysed, ThisEscape thisEscape) {
        this.analysed = analysed;
        Map<ParameterInfo, Set<Dep>> dependencies = new LinkedHashMap<>();
        for (MethodInfo methodInfo : analysed) {
            for (ParameterInfo pi : methodInfo.parameters()) {
                if (!applies(pi)) continue;
                Set<Dep> deps = new LinkedHashSet<>();
                String r = candidate(methodInfo, pi, thisEscape, deps);
                if (r == null) {
                    hidden.add(pi);
                    dependencies.put(pi, deps);
                } else {
                    reasons.put(pi, r);
                }
            }
        }
        boolean changed = true;
        while (changed) {
            changed = false;
            for (ParameterInfo pi : List.copyOf(hidden)) {
                for (Dep dep : dependencies.get(pi)) {
                    boolean ok = dep.full() ? isBorrowed(dep.parameter()) : isHidden(dep.parameter());
                    if (!ok) {
                        hidden.remove(pi);
                        reasons.put(pi, "passed on to " + dep.parameter().methodInfo().fullyQualifiedName() + " "
                                        + dep.parameter().name() + ", which is not borrowed");
                        changed = true;
                        break;
                    }
                }
            }
        }
        for (MethodInfo methodInfo : analysed) {
            for (ParameterInfo pi : methodInfo.parameters()) {
                if (!applies(pi)) continue;
                verdicts.put(pi, isBorrowed(pi));
                if (linkReasons.containsKey(pi)) reasons.put(pi, linkReasons.get(pi));
            }
        }
    }

    Map<ParameterInfo, Boolean> verdicts() {
        return verdicts;
    }

    Map<ParameterInfo, String> reasons() {
        return reasons;
    }

    static boolean isContracted(ParameterInfo pi) {
        return pi.methodInfo().isAbstract() && pi.annotations().stream()
                .anyMatch(ae -> BORROWED_ANNOTATION.equals(ae.typeInfo().fullyQualifiedName()));
    }

    private static boolean applies(ParameterInfo pi) {
        return !pi.parameterizedType().isPrimitiveExcludingVoid();
    }

    /** A parameter of the analysed sources: the verdict; outside them: a contract, or a value of an earlier run. */
    boolean isBorrowed(ParameterInfo pi) {
        if (analysed.contains(pi.methodInfo())) return hidden.contains(pi) && !linkReasons.containsKey(pi);
        return pi.analysis().getOrDefault(PropertyImpl.BORROWED_PARAMETER, ValueImpl.BoolImpl.FALSE).isTrue();
    }

    private boolean isHidden(ParameterInfo pi) {
        return analysed.contains(pi.methodInfo()) ? hidden.contains(pi) : isBorrowed(pi);
    }

    /**
     * Why an argument passed at {@code index} to {@code callee} may be kept in a way the summary's links do not show;
     * null when it is not. Rule 4, for a caller's local or parameter.
     */
    String keptByCallee(MethodInfo callee, int index) {
        ParameterInfo formal = formal(callee, index);
        if (formal == null || !applies(formal)) return null;
        if (analysed.contains(callee)) {
            // the callee's link keeps are in the caller's links: only what they do not show
            return hidden.contains(formal) ? null
                    : "passed to " + callee.fullyQualifiedName() + " " + formal.name() + ", which may keep it: "
                      + reasons.get(formal);
        }
        for (ParameterInfo dependency : libraryDependencies(callee, index)) {
            if (!isBorrowed(dependency)) {
                return "passed to " + callee.fullyQualifiedName() + ", implemented by "
                       + dependency.methodInfo().fullyQualifiedName() + ", which does not borrow it";
            }
        }
        return null;
    }

    private static ParameterInfo formal(MethodInfo callee, int index) {
        List<ParameterInfo> parameters = callee.parameters();
        if (parameters.isEmpty()) return null;
        return parameters.get(Math.min(index, parameters.size() - 1));
    }

    // the source implementations of a library method: their parameter at 'index'
    private static List<ParameterInfo> libraryDependencies(MethodInfo callee, int index) {
        List<ParameterInfo> list = new ArrayList<>();
        for (MethodInfo implementation : OwnershipPass.externalImplementationsOf(callee)) {
            ParameterInfo p = formal(implementation, index);
            if (p != null) list.add(p);
        }
        return list;
    }

    private String candidate(MethodInfo methodInfo, ParameterInfo pi, ThisEscape thisEscape, Set<Dep> deps) {
        if (isContracted(pi)) {
            return pi.analysis().getOrDefault(PropertyImpl.BORROWED_PARAMETER, ValueImpl.BoolImpl.FALSE).isTrue()
                    ? null : "contract";
        }
        if (methodInfo.analysis().getOrDefault(PropertyImpl.DEGRADED_ANALYSIS_METHOD, ValueImpl.BoolImpl.FALSE)
                .isTrue()) {
            return "degraded";
        }
        // rule 5
        for (MethodInfo implementation : OwnershipPass.implementationsOf(methodInfo)) {
            ParameterInfo p = formal(implementation, pi.index());
            if (p != null) deps.add(new Dep(p, true));
        }
        if (methodInfo.isAbstract()) {
            return deps.isEmpty() ? "abstract, no implementation" : null;
        }
        // rule 1
        MethodLinkedVariables mlv = methodInfo.analysis().getOrNull(MethodLinkedVariablesImpl.METHOD_LINKS,
                MethodLinkedVariablesImpl.class);
        if (mlv == null) return "no summary";
        for (Links links : (Iterable<Links>) Stream.concat(Stream.of(mlv.ofReturnValue()),
                mlv.ofParameters().stream())::iterator) {
            for (Link link : links) {
                if (link.linkNature().isDecoration()) continue;
                String kept = pi.equals(link.from()) ? pi.name() + " " + link.linkNature() + " " + link.to()
                        : pi.equals(link.to()) ? link.from() + " " + link.linkNature() + " " + pi.name() : null;
                if (kept != null) {
                    linkReasons.putIfAbsent(pi, "kept: " + kept);
                    break;
                }
            }
        }
        if (methodInfo.methodBody() == null) return null;
        return body(methodInfo, pi::equals, true, pi.name(), thisEscape, deps);
    }

    /**
     * Rules 2 to 4 for a field, once the fixpoint is settled: why the body of {@code methodInfo} may leave what
     * {@code tracked} names (or a local assigned from it) held where no link shows; null when it does not. A lambda or
     * anonymous class that reads the field captures its owner, not the field's object: only a local alias captured
     * counts.
     */
    String keptInBody(MethodInfo methodInfo, Predicate<Variable> tracked, String name, ThisEscape thisEscape) {
        if (methodInfo.methodBody() == null) return null;
        Set<Dep> deps = new LinkedHashSet<>();
        String r = body(methodInfo, tracked, false, name, thisEscape, deps);
        if (r != null) return r;
        for (Dep dep : deps) {
            boolean ok = dep.full() ? isBorrowed(dep.parameter()) : isHidden(dep.parameter());
            if (!ok) {
                return "passed to " + dep.parameter().methodInfo().fullyQualifiedName() + " "
                       + dep.parameter().name() + ", which may keep it";
            }
        }
        return null;
    }

    // rules 2, 3, 4
    // 'capturedDirectly': a lambda mentioning what is tracked holds it (a parameter), or holds its owner (a field)
    private String body(MethodInfo methodInfo, Predicate<Variable> tracked, boolean capturedDirectly, String name,
                        ThisEscape thisEscape, Set<Dep> deps) {
        Set<Variable> locals = localAliases(methodInfo, tracked);
        Predicate<Variable> aliases = v -> tracked.test(v) || locals.contains(v);
        Predicate<Variable> captured = capturedDirectly ? aliases : locals::contains;
        String[] found = new String[1];
        methodInfo.methodBody().visit((Element e) -> {
            if (found[0] != null) return false;
            switch (e) {
                case Lambda lambda -> {
                    if (mentions(lambda.methodInfo(), captured)) found[0] = "captured by a lambda";
                    return false;
                }
                case MethodReference mr -> {
                    if (isAlias(mr.scope(), aliases)) found[0] = "captured by " + name + "::" + mr.methodInfo().name();
                    return false;
                }
                case ConstructorCall cc -> {
                    if (cc.anonymousClass() != null) {
                        if (cc.anonymousClass().constructorAndMethodStream()
                                .anyMatch(m -> m.methodBody() != null && mentions(m, captured))) {
                            found[0] = "captured by an anonymous class";
                            return false;
                        }
                    } else if (cc.constructor() != null) {
                        arguments(cc.constructor(), cc.parameterExpressions(), aliases, deps);
                    }
                }
                case MethodCall mc -> {
                    MethodInfo callee = mc.methodInfo();
                    if (callee != null) {
                        if (isAlias(mc.object(), aliases) && thisEscape.mayLeak(callee)) {
                            found[0] = "receiver of " + callee.fullyQualifiedName() + ", which may leak this";
                            return false;
                        }
                        arguments(callee, mc.parameterExpressions(), aliases, deps);
                    }
                }
                case ExplicitConstructorInvocation eci -> {
                    if (eci.methodInfo() != null) arguments(eci.methodInfo(), eci.parameterExpressions(), aliases, deps);
                }
                default -> {
                }
            }
            return true;
        });
        return found[0];
    }

    // rule 4: the formal parameters an alias is passed to
    private void arguments(MethodInfo callee, List<Expression> arguments, Predicate<Variable> aliases,
                           Set<Dep> deps) {
        for (int i = 0; i < arguments.size(); i++) {
            if (!isAlias(arguments.get(i), aliases)) continue;
            if (analysed.contains(callee)) {
                ParameterInfo formal = formal(callee, i);
                if (formal != null && applies(formal)) deps.add(new Dep(formal, false));
            } else {
                libraryDependencies(callee, i).forEach(p -> deps.add(new Dep(p, true)));
            }
        }
    }

    // the locals assigned from what is tracked (or from such a local)
    private static Set<Variable> localAliases(MethodInfo methodInfo, Predicate<Variable> tracked) {
        Set<Variable> aliases = new HashSet<>();
        List<Map.Entry<Variable, Expression>> assignments = new ArrayList<>();
        methodInfo.methodBody().visit((Element e) -> {
            if (e instanceof LocalVariableCreation lvc) {
                lvc.localVariableStream().forEach(lv -> assignments.add(Map.entry(lv, lv.assignmentExpression())));
            } else if (e instanceof Assignment a && a.target().variable() instanceof LocalVariable lv) {
                assignments.add(Map.entry(lv, a.value()));
            }
            return true;
        });
        boolean changed = true;
        while (changed) {
            changed = false;
            for (Map.Entry<Variable, Expression> a : assignments) {
                if (!aliases.contains(a.getKey()) && isAlias(a.getValue(), v -> tracked.test(v) || aliases.contains(v))) {
                    aliases.add(a.getKey());
                    changed = true;
                }
            }
        }
        return aliases;
    }

    private static boolean isAlias(Expression expression, Predicate<Variable> aliases) {
        Expression e = expression;
        while (e instanceof Cast || e instanceof EnclosedExpression) {
            e = e instanceof Cast c ? c.expression() : ((EnclosedExpression) e).inner();
        }
        return e instanceof VariableExpression ve && aliases.test(ve.variable());
    }

    private static boolean mentions(MethodInfo methodInfo, Predicate<Variable> aliases) {
        if (methodInfo == null || methodInfo.methodBody() == null) return true;
        boolean[] mentions = new boolean[1];
        methodInfo.methodBody().visit((Element e) -> {
            if (mentions[0]) return false;
            if (e instanceof VariableExpression ve && aliases.test(ve.variable())) {
                mentions[0] = true;
                return false;
            }
            return true;
        });
        return mentions[0];
    }
}
