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
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

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
import io.codelaser.maddi.cst.api.info.TypeInfo;
import io.codelaser.maddi.cst.api.statement.ExpressionAsStatement;
import io.codelaser.maddi.cst.api.statement.ForEachStatement;
import io.codelaser.maddi.cst.api.statement.LocalVariableCreation;
import io.codelaser.maddi.cst.api.type.ParameterizedType;
import io.codelaser.maddi.cst.api.variable.DependentVariable;
import io.codelaser.maddi.cst.api.variable.FieldReference;
import io.codelaser.maddi.cst.api.variable.LocalVariable;
import io.codelaser.maddi.cst.api.variable.Variable;
import io.codelaser.maddi.modification.common.AnalysisHelper;
import io.codelaser.maddi.modification.link.impl.MethodLinkedVariablesImpl;
import io.codelaser.maddi.modification.prepwork.variable.Link;
import io.codelaser.maddi.modification.prepwork.variable.Links;
import io.codelaser.maddi.modification.prepwork.variable.MethodLinkedVariables;
import io.codelaser.maddi.modification.prepwork.variable.ObjectCreationVariable;
import io.codelaser.maddi.modification.prepwork.variable.impl.LinksImpl;

/**
 * {@code OWNED_ELEMENTS_FIELD} (docs/roadmap/ownership.md, element ownership): the elements of an owned collection or
 * map are reachable only through it. Judged for a field whose type has a mutable type argument (a {@code List<N>}, a
 * {@code Map<String, N>}); a {@code List<String>} has nothing to own.
 * <ol>
 *   <li><b>E1, the collection is owned</b> ({@code OWNED_FIELD}): otherwise its elements are reachable through
 *   whoever else holds it.</li>
 *   <li><b>E2, every element arrives created, and none leaves</b>, in the field's links: each link of an element
 *   virtual field ({@code this.f.§$s}, a map's {@code §$$s[-1]} keys and {@code §$$s[-2]} values) goes to a creation
 *   ({@code ∋ oc}), {@code null}, an immutable value (a {@code String} key), or a parameter that every caller hands
 *   over (stage 5) and that its method keeps only in these elements. A link to a method's result ({@code ∋ get}), a
 *   shared or copied collection ({@code ~}, {@code ⊆}), or anything else fails.</li>
 *   <li><b>E3, no element leaves where no link shows</b>, in every body of the primary type: an element (a for-each
 *   variable over the field or its {@code values()}/{@code keySet()}, a local assigned an element, a call returning
 *   one: {@code get}, {@code remove}, {@code poll}) may be the receiver of a call that keeps nothing (it neither may
 *   leak {@code this} nor dispatch elsewhere, and its result is discarded or cannot hold an object), an argument of a
 *   call on the field itself ({@code f.remove(n)}), an argument that the callee does not keep (stage 2's hidden
 *   verdict), or an operand of an operator. The field itself may be the receiver of a call whose result is
 *   discarded, cannot hold an object, or is an element; or a for-each's iterable. A lambda or method reference
 *   touching the field or an element, a call with a function argument ({@code forEach}, {@code computeIfAbsent}), a
 *   view ({@code stream()}, {@code iterator()}, {@code subList}), or the field passed as an argument: fails.
 *   Deliberately conservative.</li>
 * </ol>
 */
final class OwnedElements {

    private final Map<FieldInfo, Boolean> verdicts = new LinkedHashMap<>();
    private final Map<FieldInfo, String> reasons = new LinkedHashMap<>();

    OwnedElements(Map<FieldInfo, OwnershipPass.Owned> ownedFields, ThisEscape thisEscape,
                  BorrowedParameters borrowedParameters, Transfers transfers) {
        AnalysisHelper analysisHelper = new AnalysisHelper();
        for (Map.Entry<FieldInfo, OwnershipPass.Owned> entry : ownedFields.entrySet()) {
            FieldInfo f = entry.getKey();
            if (!hasMutableElements(f.type(), analysisHelper)) continue;
            String r = entry.getValue() != OwnershipPass.Owned.OWNED ? "the collection is not owned"
                    : links(f, analysisHelper, borrowedParameters, transfers);
            if (r == null) {
                for (MethodInfo m : bodies(f)) {
                    r = escapes(m, f, thisEscape, borrowedParameters);
                    if (r != null) {
                        r = "in " + m.name() + ": " + r;
                        break;
                    }
                }
            }
            verdicts.put(f, r == null);
            if (r != null) reasons.put(f, r);
        }
    }

    Map<FieldInfo, Boolean> verdicts() {
        return verdicts;
    }

    Map<FieldInfo, String> reasons() {
        return reasons;
    }

    // a type argument whose objects can be modified: something to own
    private static boolean hasMutableElements(ParameterizedType type, AnalysisHelper analysisHelper) {
        if (type.parameters().isEmpty()) return false;
        for (ParameterizedType p : type.parameters()) {
            if (p.isTypeParameter() || p.typeInfo() == null) return true; // anything, a mutable object included
            if (!analysisHelper.typeImmutable(p).isImmutable()) return true;
        }
        return false;
    }

    private static List<MethodInfo> bodies(FieldInfo fieldInfo) {
        List<MethodInfo> list = new ArrayList<>();
        fieldInfo.owner().primaryType().recursiveSubTypeStreamIncludingLocal()
                .flatMap(TypeInfo::constructorAndMethodStream).forEach(list::add);
        return list;
    }

    // ---- E2

    private static String links(FieldInfo f, AnalysisHelper analysisHelper, BorrowedParameters borrowedParameters,
                                Transfers transfers) {
        Links links = f.analysis().getOrDefault(LinksImpl.LINKS, LinksImpl.EMPTY);
        for (Link link : links) {
            if (link.linkNature().isDecoration() || !isElement(link.from(), f)) continue;
            Variable to = link.to();
            Variable root = root(to);
            if (root instanceof ObjectCreationVariable || OwnershipPass.isNullConstant(to)) continue;
            ParameterizedType type = to.parameterizedType();
            if (type != null && !type.isTypeParameter() && type.typeInfo() != null
                && analysisHelper.typeImmutable(type).isImmutable()) {
                continue;
            }
            if (to instanceof ParameterInfo pi && transfers.isTransferred(pi) && borrowedParameters.isHidden(pi)
                && keptOnlyInElements(pi, f)) {
                continue;
            }
            return "link: " + link.from() + " " + link.linkNature() + " " + to;
        }
        return null;
    }

    // an element virtual field of this.f: this.f.§$s, this.f.§$$s[-2]
    private static boolean isElement(Variable variable, FieldInfo f) {
        Variable v = variable instanceof DependentVariable dv ? dv.arrayVariable() : variable;
        return v instanceof FieldReference vf && vf.fieldInfo().name().startsWith("§")
               && !"§m".equals(vf.fieldInfo().name()) && Transfers.isThisField(vf.scopeVariable(), f);
    }

    private static Variable root(Variable variable) {
        Variable v = variable;
        while (true) {
            if (v instanceof FieldReference fr && fr.scopeVariable() != null) v = fr.scopeVariable();
            else if (v instanceof DependentVariable dv) v = dv.arrayVariable();
            else return v;
        }
    }

    // the method links the parameter only into f's elements: 'fromParam.add(n)' is 0:n ∈ this.fromParam.§$s
    private static boolean keptOnlyInElements(ParameterInfo pi, FieldInfo f) {
        MethodLinkedVariables mlv = pi.methodInfo().analysis().getOrNull(MethodLinkedVariablesImpl.METHOD_LINKS,
                MethodLinkedVariablesImpl.class);
        if (mlv == null) return false;
        if (mlv.ofReturnValue().stream().anyMatch(l -> pi.equals(root(l.to())))) return false;
        boolean into = false;
        for (Links links : mlv.ofParameters()) {
            for (Link link : links) {
                if (link.linkNature().isDecoration()) continue;
                if (pi.equals(link.from())) {
                    if (!isElement(link.to(), f)) return false;
                    into = true;
                } else if (pi.equals(root(link.to()))) {
                    return false;
                }
            }
        }
        return into;
    }

    // ---- E3

    /*
     Counting, as Transfers.usesBefore: every mention of the field, of an element local, and every call returning an
     element is one use; each use in an allowed place is counted again. A difference is a use where an element may be
     kept.
     */
    private static String escapes(MethodInfo m, FieldInfo f, ThisEscape thisEscape,
                                  BorrowedParameters borrowedParameters) {
        if (m.methodBody() == null) return null;
        Predicate<Variable> isField = v -> Transfers.isThisField(v, f);
        Set<LocalVariable> tracked = new HashSet<>();
        Set<Element> discarded = new HashSet<>();
        Set<Element> iterables = new HashSet<>();
        String[] fail = new String[1];
        // the element locals, the discarded calls, the iterables
        m.methodBody().visit((Element e) -> {
            if (e instanceof ExpressionAsStatement eas) discarded.add(eas.expression());
            if (e instanceof ForEachStatement fe) {
                Expression it = Transfers.unwrap(fe.expression());
                boolean overElements = isFieldExpr(it, isField)
                        || it instanceof MethodCall mc && isFieldExpr(Transfers.unwrap(mc.object()), isField)
                           && ("values".equals(mc.methodInfo().name()) || "keySet".equals(mc.methodInfo().name()));
                if (overElements) {
                    iterables.add(it);
                    tracked.add(fe.initializer().localVariable());
                }
            }
            if (e instanceof LocalVariableCreation lvc) {
                lvc.localVariableStream().forEach(lv -> {
                    if (lv.assignmentExpression() != null && isElementExpr(lv.assignmentExpression(), isField)) {
                        tracked.add(lv);
                    }
                });
            }
            if (e instanceof Assignment a && a.target().variable() instanceof LocalVariable lv
                && isElementExpr(a.value(), isField)) {
                tracked.add(lv);
            }
            return true;
        });
        Predicate<Expression> isElement = x -> {
            Expression u = Transfers.unwrap(x);
            return u instanceof VariableExpression ve && ve.variable() instanceof LocalVariable lv && tracked.contains(lv)
                   || isElementExpr(u, isField);
        };
        int[] uses = {0};
        int[] allowed = {0};
        m.methodBody().visit((Element e) -> {
            if (fail[0] != null) return false;
            if (e instanceof Lambda || e instanceof MethodReference) {
                if (mentionsAny(e, isField, tracked)) fail[0] = "a function captures the field or an element";
                return false;
            }
            if (e instanceof VariableExpression ve && (isField.test(ve.variable())
                                                       || ve.variable() instanceof LocalVariable lv && tracked.contains(lv))) {
                uses[0]++;
            }
            if (e instanceof MethodCall mc && isElementExpr(mc, isField)) uses[0]++;
            switch (e) {
                case MethodCall c -> {
                    Expression object = c.object() == null ? null : Transfers.unwrap(c.object());
                    MethodInfo callee = c.methodInfo();
                    boolean onField = object != null && isFieldExpr(object, isField);
                    if (onField) {
                        if (c.parameterExpressions().stream().anyMatch(a -> a instanceof Lambda
                                                                            || a instanceof MethodReference)) {
                            fail[0] = "a function receives the elements (" + callee.name() + ")";
                            return false;
                        }
                        if (callee != null && (Transfers.returnsElement(callee) || discarded.contains(c)
                                               || Transfers.cannotHold(c.parameterizedType()) || iterables.contains(c))) {
                            allowed[0]++;
                        }
                    }
                    if (object != null && isElement.test(object) && callee != null) {
                        boolean keepsNothing = !thisEscape.mayLeak(callee) && !OwnershipPass.mayDispatchElsewhere(callee)
                                               && (discarded.contains(c) || Transfers.cannotHold(c.parameterizedType()));
                        if (keepsNothing) allowed[0]++;
                    }
                    if (isElementExpr(c, isField) && discarded.contains(c)) allowed[0]++;
                    arguments(c.parameterExpressions(), callee, onField, isElement, borrowedParameters, allowed);
                }
                case ConstructorCall cc -> arguments(cc.parameterExpressions(), cc.constructor(), false, isElement,
                        borrowedParameters, allowed);
                case BinaryOperator bo -> {
                    if (isElement.test(bo.lhs())) allowed[0]++;
                    if (isElement.test(bo.rhs())) allowed[0]++;
                }
                case LocalVariableCreation lvc -> lvc.localVariableStream().forEach(lv -> {
                    if (tracked.contains(lv) && lv.assignmentExpression() != null
                        && isElement.test(lv.assignmentExpression())) {
                        allowed[0]++;
                    }
                });
                case Assignment a -> {
                    if (a.target().variable() instanceof LocalVariable lv && tracked.contains(lv)) {
                        allowed[0]++; // the target
                        if (isElement.test(a.value())) allowed[0]++;
                    }
                }
                case ForEachStatement fe -> {
                    Expression it = Transfers.unwrap(fe.expression());
                    if (iterables.contains(it) && isFieldExpr(it, isField)) allowed[0]++;
                }
                default -> {
                }
            }
            return true;
        });
        if (fail[0] != null) return fail[0];
        if (uses[0] != allowed[0]) return "an element, or the collection, is used where it may be kept";
        return null;
    }

    private static void arguments(List<Expression> arguments, MethodInfo callee, boolean onField,
                                  Predicate<Expression> isElement, BorrowedParameters borrowedParameters,
                                  int[] allowed) {
        for (int i = 0; i < arguments.size(); i++) {
            if (!isElement.test(arguments.get(i))) continue;
            if (onField) {
                allowed[0]++; // back into, or looked up in, the same collection
            } else if (callee != null && !callee.parameters().isEmpty()) {
                ParameterInfo pi = callee.parameters().get(Math.min(i, callee.parameters().size() - 1));
                if (!pi.isVarArgs() && borrowedParameters.isHidden(pi)) allowed[0]++;
            }
        }
    }

    private static boolean isFieldExpr(Expression e, Predicate<Variable> isField) {
        return e instanceof VariableExpression ve && isField.test(ve.variable());
    }

    // a call on the field returning one of its elements: f.get(i), map.get(k), f.remove(0), queue.poll()
    private static boolean isElementExpr(Expression expression, Predicate<Variable> isField) {
        Expression e = Transfers.unwrap(expression);
        return e instanceof MethodCall mc && mc.methodInfo() != null && mc.object() != null
               && isFieldExpr(Transfers.unwrap(mc.object()), isField) && Transfers.returnsElement(mc.methodInfo());
    }

    private static boolean mentionsAny(Element element, Predicate<Variable> isField, Set<LocalVariable> tracked) {
        return Transfers.mentions(element, v -> isField.test(v) || v instanceof LocalVariable lv && tracked.contains(lv))
               > 0;
    }
}
