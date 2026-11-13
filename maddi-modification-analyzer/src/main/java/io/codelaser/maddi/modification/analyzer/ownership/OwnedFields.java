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

import io.codelaser.maddi.cst.api.element.Element;
import io.codelaser.maddi.cst.api.expression.Assignment;
import io.codelaser.maddi.cst.api.expression.EmptyExpression;
import io.codelaser.maddi.cst.api.expression.Expression;
import io.codelaser.maddi.cst.api.expression.VariableExpression;
import io.codelaser.maddi.cst.api.info.FieldInfo;
import io.codelaser.maddi.cst.api.info.Info;
import io.codelaser.maddi.cst.api.info.MethodInfo;
import io.codelaser.maddi.cst.api.info.ParameterInfo;
import io.codelaser.maddi.cst.api.info.TypeInfo;
import io.codelaser.maddi.cst.api.statement.ReturnStatement;
import io.codelaser.maddi.cst.api.variable.DependentVariable;
import io.codelaser.maddi.cst.api.variable.FieldReference;
import io.codelaser.maddi.cst.api.variable.LocalVariable;
import io.codelaser.maddi.cst.api.variable.This;
import io.codelaser.maddi.cst.api.variable.Variable;
import io.codelaser.maddi.cst.impl.analysis.PropertyImpl;
import io.codelaser.maddi.cst.impl.analysis.ValueImpl;
import io.codelaser.maddi.modification.common.AnalysisHelper;
import io.codelaser.maddi.modification.prepwork.variable.Link;
import io.codelaser.maddi.modification.prepwork.variable.Links;
import io.codelaser.maddi.modification.prepwork.variable.ObjectCreationVariable;
import io.codelaser.maddi.modification.prepwork.variable.ReturnVariable;
import io.codelaser.maddi.modification.prepwork.variable.impl.LinksImpl;

/**
 * {@code OWNED_FIELD} and {@code SHARED_IMMUTABLE_FIELD} (docs/roadmap/ownership.md stage 3): the object a field holds
 * can only be reached through the object that holds the field. Decided after freshness and borrowing, whose verdicts
 * it reads.
 * <ol>
 *   <li><b>Private.</b> Otherwise anyone may read the field and keep its object.</li>
 *   <li><b>No degraded referrer.</b> No method of the primary type is degraded (its summary is the shallow one).</li>
 *   <li><b>Arrives fresh.</b> The initializer, and every value assigned to the field anywhere in the primary type,
 *   is created for it: freshness's per-path evidence (a construction, null, a call to a fresh method, a local assigned
 *   only such values), and a local assigned to the field must not be handed elsewhere where no link shows.</li>
 *   <li><b>Never leaves</b>, in its links ({@code LinksImpl.LINKS}, which unlike a method summary record where the
 *   field's object goes): no link from the field itself to anything but a creation or {@code null} marker
 *   ({@code this.exposed → getExposed}, {@code this.f ∈ X.CACHE.§$s}, {@code this.f ← 0:p}); and its modification
 *   face not shared with anything outside ({@code this.viewed.§m ≡ view.§m}: a view handed out holds the object, and
 *   that face is all the links show of it).</li>
 *   <li><b>Never leaves</b>, where no link shows (stage 2's rules for a body): not captured through a local alias or
 *   {@code f::m}, not the receiver of a method that may leak {@code this}, not passed to a method that may keep it. A
 *   lambda reading the field captures the owner, not the field's object.</li>
 * </ol>
 * Stage 5 (transfer, {@link Transfers}): in rules 3 and 4, a parameter of the assigning method whose every caller
 * hands over its argument counts as created, provided the method keeps it in this field only. In rules 4 and 5, a
 * {@code @Finalizer} of the field's object that moves the field out (hands it over, or returns it) keeps nothing:
 * the owner is dead after the call.
 * <p>
 * A field that fails only rules 3 to 5 or 1, and whose type is immutable (at least with hidden content), is
 * shared-immutable rather than not owned: several holders, nobody modifies, no cycle (Rust's {@code Rc}). Static and
 * primitive fields are not about ownership and get no verdict. A collection field's elements are not judged.
 */
final class OwnedFields {

    private final Map<FieldInfo, OwnershipPass.Owned> verdicts = new LinkedHashMap<>();
    private final Map<FieldInfo, String> reasons = new LinkedHashMap<>();

    OwnedFields(List<Info> analysisOrder, Set<MethodInfo> analysedResults, Set<MethodInfo> fresh,
                ThisEscape thisEscape, BorrowedParameters borrowedParameters, Transfers transfers) {
        AnalysisHelper analysisHelper = new AnalysisHelper();
        for (FieldInfo fieldInfo : fields(analysisOrder)) {
            String hard = hard(fieldInfo);
            String sharing = hard != null ? null
                    : sharing(fieldInfo, analysedResults, fresh, thisEscape, borrowedParameters, transfers);
            OwnershipPass.Owned verdict;
            if (hard == null && sharing == null) {
                verdict = OwnershipPass.Owned.OWNED;
            } else {
                boolean immutable = analysisHelper.typeImmutable(fieldInfo.type()).isAtLeastImmutableHC();
                verdict = immutable && (sharing != null || hard.equals(NOT_PRIVATE))
                        ? OwnershipPass.Owned.SHARED_IMMUTABLE : OwnershipPass.Owned.NOT_OWNED;
                reasons.put(fieldInfo, hard != null ? hard : sharing);
            }
            verdicts.put(fieldInfo, verdict);
        }
    }

    Map<FieldInfo, OwnershipPass.Owned> verdicts() {
        return verdicts;
    }

    Map<FieldInfo, String> reasons() {
        return reasons;
    }

    private static Set<FieldInfo> fields(List<Info> analysisOrder) {
        Set<FieldInfo> set = new LinkedHashSet<>();
        for (Info info : analysisOrder) {
            if (info instanceof FieldInfo fieldInfo) set.add(fieldInfo);
            else if (info instanceof TypeInfo typeInfo) set.addAll(typeInfo.fields());
        }
        set.removeIf(f -> f.isStatic() || f.type().isPrimitiveExcludingVoid());
        return set;
    }

    private static final String NOT_PRIVATE = "not private";

    // rules 1 and 2
    private static String hard(FieldInfo fieldInfo) {
        boolean degraded = bodies(fieldInfo).stream().anyMatch(m -> m.analysis().getOrDefault(
                PropertyImpl.DEGRADED_ANALYSIS_METHOD, ValueImpl.BoolImpl.FALSE).isTrue());
        if (degraded) return "a method of " + fieldInfo.owner().primaryType().simpleName() + " is degraded";
        if (!fieldInfo.access().isPrivate()) return NOT_PRIVATE;
        return null;
    }

    // every body that may refer to a private field: the primary type's, nested and local types included
    private static List<MethodInfo> bodies(FieldInfo fieldInfo) {
        List<MethodInfo> list = new ArrayList<>();
        fieldInfo.owner().primaryType().recursiveSubTypeStreamIncludingLocal()
                .flatMap(TypeInfo::constructorAndMethodStream).forEach(list::add);
        return list;
    }

    // rules 3, 4, 5
    private static String sharing(FieldInfo fieldInfo, Set<MethodInfo> analysedResults, Set<MethodInfo> fresh,
                                  ThisEscape thisEscape, BorrowedParameters borrowedParameters,
                                  Transfers transfers) {
        Predicate<Variable> isField = v -> v instanceof FieldReference fr && fr.fieldInfo().equals(fieldInfo);
        // rule 3
        Set<MethodInfo> deps = new LinkedHashSet<>();
        Expression initializer = fieldInfo.initializer();
        if (initializer != null && !(initializer instanceof EmptyExpression)) {
            OwnershipPass.Body body = new OwnershipPass.Body(Set.of(), List.of(), analysedResults, deps, thisEscape,
                    new HashSet<>());
            String r = body.created(initializer);
            if (r != null) return "initializer " + r;
        }
        for (MethodInfo methodInfo : bodies(fieldInfo)) {
            if (methodInfo.methodBody() == null) continue;
            List<Assignment> assignments = new ArrayList<>();
            methodInfo.methodBody().visit((Element e) -> {
                if (e instanceof Assignment a && isField.test(a.target().variable())) assignments.add(a);
                return true;
            });
            if (assignments.isEmpty()) continue;
            OwnershipPass.Body body = OwnershipPass.Body.of(methodInfo, analysedResults, deps, thisEscape);
            for (Assignment a : assignments) {
                if (handedOver(a.value(), methodInfo, fieldInfo, transfers, borrowedParameters)) continue;
                if (Transfers.unwrap(a.value()) instanceof VariableExpression ve
                    && ve.variable() instanceof ParameterInfo pi && pi.methodInfo().equals(methodInfo)) {
                    String why = transfers.isTransferred(pi) ? "kept elsewhere too" : transfers.reason(pi);
                    return "assigned in " + methodInfo.name() + " from parameter " + pi.name() + ": " + why;
                }
                String r = body.created(a.value());
                if (r != null) return "assigned in " + methodInfo.name() + ": " + r;
                if (a.value() instanceof VariableExpression ve && ve.variable() instanceof LocalVariable) {
                    String kept = OwnershipPass.localHandedToBodyThatMayKeepIt(methodInfo, thisEscape,
                            borrowedParameters);
                    if (kept != null) return "assigned in " + methodInfo.name() + " from a " + kept;
                }
            }
        }
        for (MethodInfo dep : deps) {
            if (!fresh.contains(dep)) return "assigned the result of " + dep.fullyQualifiedName() + ", not fresh";
        }
        // rule 4
        Links links = fieldInfo.analysis().getOrDefault(LinksImpl.LINKS, LinksImpl.EMPTY);
        for (Link link : links) {
            if (link.linkNature().isDecoration()) continue;
            Variable toRoot = root(link.to());
            boolean toCreated = toRoot instanceof ObjectCreationVariable
                                || toRoot instanceof ParameterInfo pi && transfers.isTransferred(pi)
                                   && (Transfers.keptOnlyIn(pi, fieldInfo, borrowedParameters)
                                       || transfers.receivesMoved(pi, fieldInfo))
                                || toRoot instanceof ReturnVariable rv && movesOut(rv.methodInfo(), fieldInfo, transfers);
            if (isThisField(link.from(), fieldInfo)) {
                if (!toCreated && !OwnershipPass.isNullConstant(link.to())) {
                    return "link: " + fieldInfo.name() + " " + link.linkNature() + " " + link.to();
                }
            } else if (link.from() instanceof FieldReference face && "§m".equals(face.fieldInfo().name())
                       && isThisField(face.scopeVariable(), fieldInfo) && !toCreated) {
                return "its object is shared: " + link.from() + " " + link.linkNature() + " " + link.to();
            }
        }
        // rule 5
        for (MethodInfo methodInfo : bodies(fieldInfo)) {
            if (movesOut(methodInfo, fieldInfo, transfers)) continue;
            String kept = borrowedParameters.keptInBody(methodInfo, isField, fieldInfo.name(), thisEscape);
            if (kept != null) return "in " + methodInfo.name() + ": " + kept;
        }
        return null;
    }

    // stage 5: a parameter of the assigning method that every caller hands over, kept by it only in this field
    private static boolean handedOver(Expression value, MethodInfo methodInfo, FieldInfo fieldInfo,
                                      Transfers transfers, BorrowedParameters borrowedParameters) {
        return Transfers.unwrap(value) instanceof VariableExpression ve
               && ve.variable() instanceof ParameterInfo pi && pi.methodInfo().equals(methodInfo)
               && transfers.isTransferred(pi) && Transfers.keptOnlyIn(pi, fieldInfo, borrowedParameters);
    }

    /**
     * Stage 5: a finalizer of the field's object moves the field out, handing it over to a parameter that transfers
     * or returning it ({@code fn into_data(self) -> Vec}); the owner is dead after the call, so it keeps nothing.
     */
    private static boolean movesOut(MethodInfo methodInfo, FieldInfo fieldInfo, Transfers transfers) {
        if (!methodInfo.isFinalizer() || methodInfo.methodBody() == null) return false;
        if (transfers.movesOut(methodInfo, fieldInfo)) return true;
        Element[] returned = new Element[1];
        methodInfo.methodBody().visit((Element e) -> {
            if (e instanceof ReturnStatement rs && rs.expression() != null
                && Transfers.unwrap(rs.expression()) instanceof VariableExpression ve
                && isThisField(ve.variable(), fieldInfo)) {
                returned[0] = rs;
            }
            return returned[0] == null;
        });
        return returned[0] != null && Transfers.moveOut(methodInfo, fieldInfo, returned[0]) == null;
    }

    private static boolean isThisField(Variable variable, FieldInfo fieldInfo) {
        return variable instanceof FieldReference fr && fr.fieldInfo().equals(fieldInfo)
               && (fr.scopeVariable() == null || fr.scopeVariable() instanceof This);
    }

    private static Variable root(Variable variable) {
        Variable v = variable;
        while (true) {
            if (v instanceof FieldReference fr && fr.scopeVariable() != null) v = fr.scopeVariable();
            else if (v instanceof DependentVariable dv) v = dv.arrayVariable();
            else return v;
        }
    }
}
