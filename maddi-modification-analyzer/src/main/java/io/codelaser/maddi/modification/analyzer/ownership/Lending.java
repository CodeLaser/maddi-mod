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
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

import io.codelaser.maddi.cst.api.analysis.Value;
import io.codelaser.maddi.cst.api.element.Element;
import io.codelaser.maddi.cst.api.expression.Assignment;
import io.codelaser.maddi.cst.api.expression.BinaryOperator;
import io.codelaser.maddi.cst.api.expression.ConstructorCall;
import io.codelaser.maddi.cst.api.expression.Expression;
import io.codelaser.maddi.cst.api.expression.Lambda;
import io.codelaser.maddi.cst.api.expression.MethodCall;
import io.codelaser.maddi.cst.api.expression.MethodReference;
import io.codelaser.maddi.cst.api.expression.VariableExpression;
import io.codelaser.maddi.cst.api.info.FieldInfo;
import io.codelaser.maddi.cst.api.info.MethodInfo;
import io.codelaser.maddi.cst.api.info.ParameterInfo;
import io.codelaser.maddi.cst.api.statement.ExpressionAsStatement;
import io.codelaser.maddi.cst.api.statement.ForEachStatement;
import io.codelaser.maddi.cst.api.statement.LocalVariableCreation;
import io.codelaser.maddi.cst.api.statement.ReturnStatement;
import io.codelaser.maddi.cst.api.variable.LocalVariable;
import io.codelaser.maddi.cst.api.variable.Variable;
import io.codelaser.maddi.cst.impl.analysis.PropertyImpl;
import io.codelaser.maddi.cst.impl.analysis.ValueImpl;

/**
 * Lending (docs/roadmap/ownership.md): a method's result is <em>lent</em> when every caller only uses the object it
 * returns, and none keeps it: Rust's {@code fn children(&self) -> &Vec<T>}. A getter that returns an owned field (or
 * one of its owned elements) then leaves the field owned.
 * <ol>
 *   <li><b>Every call site known</b>, stage 5's rule 1: private or package-private, or a closed world; it overrides
 *   nothing, nothing overrides it, no method reference names it, no field initializer calls it; at least one call.</li>
 *   <li><b>Every use borrows</b>: at each call, the result (or a local that holds it) is only the receiver of a call
 *   that keeps nothing (it neither may leak {@code this} nor dispatch elsewhere, and its result is discarded, cannot
 *   hold an object, or is an element: {@code getList().add(x)}, {@code .size()}, {@code .get(i)}), a for-each's
 *   iterable, an operand, or an argument the callee does not keep: stage 2's hidden verdict for the analysed sources;
 *   in a library, an independent parameter, or one independent with hidden content whose type is not a type
 *   parameter ({@code addAll(Collection)} keeps the elements, not the collection; {@code add(E)} keeps its
 *   argument). Not returned, stored, or captured.</li>
 * </ol>
 * Elements reached through the lent object are not judged here: element ownership is.
 */
final class Lending {

    private record Site(MethodInfo caller, MethodCall call) {
    }

    private final ThisEscape thisEscape;
    private final BorrowedParameters borrowedParameters;
    private final boolean closedWorld;
    private final Map<MethodInfo, List<Site>> sites = new HashMap<>();
    private final Set<MethodInfo> referenced = new HashSet<>();
    private final Set<MethodInfo> calledByFieldInitializer = new HashSet<>();
    private final Map<MethodInfo, String> reasons = new LinkedHashMap<>();
    private final Set<MethodInfo> lent = new HashSet<>();

    Lending(Set<MethodInfo> analysed, ThisEscape thisEscape, BorrowedParameters borrowedParameters,
            boolean closedWorld) {
        this.thisEscape = thisEscape;
        this.borrowedParameters = borrowedParameters;
        this.closedWorld = closedWorld;
        for (MethodInfo caller : analysed) {
            if (caller.methodBody() == null) continue;
            caller.methodBody().visit((Element e) -> {
                if (e instanceof MethodReference mr && mr.methodInfo() != null) referenced.add(mr.methodInfo());
                if (e instanceof MethodCall mc && mc.methodInfo() != null) {
                    sites.computeIfAbsent(mc.methodInfo(), _ -> new ArrayList<>()).add(new Site(caller, mc));
                }
                return true;
            });
        }
        Set<FieldInfo> seen = new HashSet<>();
        for (MethodInfo m : analysed) {
            for (FieldInfo f : m.typeInfo().fields()) {
                if (!seen.add(f) || f.initializer() == null || f.initializer().isEmpty()) continue;
                f.initializer().visit((Element e) -> {
                    if (e instanceof MethodCall mc && mc.methodInfo() != null) calledByFieldInitializer.add(mc.methodInfo());
                    return true;
                });
            }
        }
    }

    /** Whether every caller of {@code m} only uses its result; computed once per method. */
    boolean isLent(MethodInfo m) {
        if (lent.contains(m)) return true;
        if (reasons.containsKey(m)) return false;
        String r = compute(m);
        if (r == null) lent.add(m);
        else reasons.put(m, r);
        return r == null;
    }

    String reason(MethodInfo m) {
        return reasons.get(m);
    }

    Set<MethodInfo> lent() {
        return lent;
    }

    private String compute(MethodInfo m) {
        if (m.isConstructor() || m.isVoid() || m.methodBody() == null) return "no result to lend";
        if (m.isAbstract()) return "abstract";
        if (!m.overrides().isEmpty()) return "overrides a method";
        if (!OwnershipPass.implementationsOf(m).isEmpty()) return "overridden";
        if (referenced.contains(m)) return "named by a method reference";
        if (calledByFieldInitializer.contains(m)) return "called by a field initializer";
        var access = m.effectiveAccess();
        if (!closedWorld && !access.isPrivate() && !access.isPackage()) return "callable from outside";
        List<Site> list = sites.get(m);
        if (list == null) return "no call site in a method body";
        for (Site site : list) {
            String r = uses(site.caller(), site.call());
            if (r != null) return "in " + site.caller().fullyQualifiedName() + ": " + r;
        }
        return null;
    }

    /*
     Counting, as OwnedElements: the call and every mention of a local holding its result are uses; each one in a
     place that only borrows is counted again.
     */
    private String uses(MethodInfo caller, MethodCall call) {
        Set<LocalVariable> tracked = new HashSet<>();
        Set<Element> discarded = new HashSet<>();
        caller.methodBody().visit((Element e) -> {
            if (e instanceof ExpressionAsStatement eas) discarded.add(eas.expression());
            if (e instanceof LocalVariableCreation lvc) {
                lvc.localVariableStream().forEach(lv -> {
                    if (lv.assignmentExpression() != null && Transfers.unwrap(lv.assignmentExpression()) == call) {
                        tracked.add(lv);
                    }
                });
            }
            if (e instanceof Assignment a && a.target().variable() instanceof LocalVariable lv
                && Transfers.unwrap(a.value()) == call) {
                tracked.add(lv);
            }
            return true;
        });
        Predicate<Expression> isLent = x -> {
            Expression u = Transfers.unwrap(x);
            return u == call || u instanceof VariableExpression ve && ve.variable() instanceof LocalVariable lv
                                && tracked.contains(lv);
        };
        Predicate<Variable> isTracked = v -> v instanceof LocalVariable lv && tracked.contains(lv);
        int[] uses = {1};
        int[] allowed = {0};
        String[] fail = new String[1];
        caller.methodBody().visit((Element e) -> {
            if (fail[0] != null) return false;
            if (e instanceof Lambda || e instanceof MethodReference) {
                if (Transfers.mentions(e, isTracked) > 0 || contains(e, call)) fail[0] = "captured by a function";
                return false;
            }
            if (e instanceof VariableExpression ve && isTracked.test(ve.variable())) uses[0]++;
            if (e == call && discarded.contains(call)) allowed[0]++;
            switch (e) {
                case MethodCall c -> {
                    if (c.object() != null && isLent.test(c.object()) && c.methodInfo() != null) {
                        MethodInfo callee = c.methodInfo();
                        if (!thisEscape.mayLeak(callee) && !OwnershipPass.mayDispatchElsewhere(callee)
                            && (discarded.contains(c) || Transfers.cannotHold(c.parameterizedType())
                                || Transfers.returnsElement(callee))) {
                            allowed[0]++;
                        }
                    }
                    arguments(c.parameterExpressions(), c.methodInfo(), isLent, allowed);
                }
                case ConstructorCall cc -> arguments(cc.parameterExpressions(), cc.constructor(), isLent, allowed);
                case BinaryOperator bo -> {
                    if (isLent.test(bo.lhs())) allowed[0]++;
                    if (isLent.test(bo.rhs())) allowed[0]++;
                }
                case ForEachStatement fe -> {
                    if (isLent.test(fe.expression())) allowed[0]++;
                }
                case LocalVariableCreation lvc -> lvc.localVariableStream().forEach(lv -> {
                    if (tracked.contains(lv) && lv.assignmentExpression() != null
                        && isLent.test(lv.assignmentExpression())) {
                        allowed[0]++;
                    }
                });
                case Assignment a -> {
                    if (a.target().variable() instanceof LocalVariable lv && tracked.contains(lv)) {
                        allowed[0]++; // the target
                        if (isLent.test(a.value())) allowed[0]++;
                    }
                }
                case ReturnStatement _ -> {
                    // returned: the caller's caller may keep it (no transitive lending yet)
                }
                default -> {
                }
            }
            return true;
        });
        if (fail[0] != null) return fail[0];
        return uses[0] == allowed[0] ? null : "its result is used where it may be kept";
    }

    private void arguments(List<Expression> arguments, MethodInfo callee, Predicate<Expression> isLent, int[] allowed) {
        if (callee == null || callee.parameters().isEmpty()) return;
        for (int i = 0; i < arguments.size(); i++) {
            if (!isLent.test(arguments.get(i))) continue;
            ParameterInfo pi = callee.parameters().get(Math.min(i, callee.parameters().size() - 1));
            if (!pi.isVarArgs() && keepsNothing(pi)) allowed[0]++;
        }
    }

    // the argument's object is not kept by the callee (its contents may be)
    private boolean keepsNothing(ParameterInfo pi) {
        if (!pi.methodInfo().typeInfo().compilationUnit().externalLibrary()) return borrowedParameters.isHidden(pi);
        if (pi.parameterizedType().isTypeParameter()) return false;
        Value.Independent independent = pi.analysis().getOrDefault(PropertyImpl.INDEPENDENT_PARAMETER,
                ValueImpl.IndependentImpl.DEPENDENT);
        return independent.isAtLeastIndependentHc();
    }

    private static boolean contains(Element element, Element target) {
        boolean[] found = new boolean[1];
        element.visit((Element e) -> {
            if (e == target) found[0] = true;
            return !found[0];
        });
        return found[0];
    }
}
