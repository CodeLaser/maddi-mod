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

package io.codelaser.maddi.modification.prepwork;

import java.util.ArrayList;
import java.util.List;

import io.codelaser.maddi.cst.api.analysis.PropertyValueMap;
import io.codelaser.maddi.cst.api.element.Element;
import io.codelaser.maddi.cst.api.expression.ConstructorCall;
import io.codelaser.maddi.cst.api.expression.Expression;
import io.codelaser.maddi.cst.api.expression.MethodCall;
import io.codelaser.maddi.cst.api.expression.Lambda;
import io.codelaser.maddi.cst.api.info.FieldInfo;
import io.codelaser.maddi.cst.api.info.InfoMapView;
import io.codelaser.maddi.cst.api.info.MethodInfo;
import io.codelaser.maddi.cst.api.info.ParameterInfo;
import io.codelaser.maddi.cst.api.info.TypeInfo;
import io.codelaser.maddi.cst.api.statement.LocalTypeDeclaration;
import io.codelaser.maddi.cst.api.statement.Statement;
import io.codelaser.maddi.modification.prepwork.variable.Links;
import io.codelaser.maddi.modification.prepwork.variable.impl.VariableDataImpl;
import io.codelaser.maddi.modification.prepwork.variable.impl.VariableInfoContainerImpl;
import io.codelaser.maddi.modification.prepwork.variable.impl.VariableInfoImpl;

/**
 * The mirror of {@link ClearDerived}: carry the cross-type-derived tier of a {@code REWIRE} type, the analyzer's
 * output, from the previous generation onto the rewired copy, so that a seeded analysis can start from the changed
 * types alone and spare the cone (docs/design/incremental-and-overlay.md §3 item 3). Call after prep, which skips a
 * rewired type with its intrinsic tier carried, and before the seeded analysis.
 * <p>
 * Every level {@link ClearDerived} clears is carried: the type and its nested, anonymous and local types, the methods
 * (lambdas included) and their parameters, the fields and their initializers, and per variable of the variable data
 * the links and the derived values the link computer and the analyzer wrote. A rewired {@code VariableInfo} drops
 * those ({@link VariableInfoImpl#rewire}); without them a field of the cone, analysed again because a callee changed,
 * would read its methods' links as undecided. The copies are found through the rewire's own memo
 * ({@link InfoMapView#rewiredIfPresent}); a statement's own analysis map is not memoized and is not carried, which is
 * what {@link ClearDerived} would clear before a recompute anyway.
 * <p>
 * The target's derived tier is cleared first: prep may have written some of it, and {@code setAll} refuses to
 * overwrite. The carried values are the previous analysis' for a type whose source did not change; when an input
 * did change, the seeded worklist reaches the type, and {@link ClearDerived} clears it before its first recompute.
 */
public class CarryDerived {

    private CarryDerived() {
    }

    /** Carry everything of {@code oldType}, a primary type the rewire copied, onto its copy in {@code view}. */
    public static void primaryType(TypeInfo oldType, InfoMapView view) {
        type(oldType, view);
    }

    private static void type(TypeInfo oldType, InfoMapView view) {
        oldType.recursiveSubTypeStream().forEach(type -> {
            TypeInfo newType = view.typeInfoNullIfAbsent(type);
            if (newType == null || newType == type) return;
            map(type.analysis(), newType.analysis(), view);
            type.constructorAndMethodStream().forEach(m -> method(m, view));
            for (FieldInfo f : type.fields()) field(f, view);
        });
    }

    private static void method(MethodInfo oldMethod, InfoMapView view) {
        MethodInfo newMethod = view.methodInfo(oldMethod);
        if (newMethod == oldMethod) return;
        map(oldMethod.analysis(), newMethod.analysis(), view);
        variableData(oldMethod.analysis(), view);
        for (ParameterInfo p : oldMethod.parameters()) {
            map(p.analysis(), view.parameterInfo(p).analysis(), view);
        }
        if (oldMethod.methodBody() != null) {
            body(oldMethod.methodBody(), view);
            callSites(oldMethod.methodBody(), newMethod.methodBody(), view);
        }
    }

    private static void field(FieldInfo oldField, InfoMapView view) {
        FieldInfo newField = view.fieldInfo(oldField);
        if (newField == oldField) return;
        map(oldField.analysis(), newField.analysis(), view);
        map(oldField.analysisOfInitializer(), newField.analysisOfInitializer(), view);
        variableData(oldField.analysisOfInitializer(), view);
        if (oldField.initializer() != null && !oldField.initializer().isEmpty()) {
            body(oldField.initializer(), view);
            callSites(oldField.initializer(), newField.initializer(), view);
        }
    }

    /*
     The call sites' own analysis maps: the link computer's argument links (LINKED_VARIABLES_ARGUMENTS), which the
     MODREACH reachability reads for its caller-to-callee edges. The rewire memoizes no expression, so the old and new
     calls are paired by walking both bodies in the same order, which a type whose source did not change allows; a
     pairing that does not line up carries nothing. Without these, every call site of a rewired type read as one
     without argument links, and the early cutoff's MODREACH round rewrote hundreds of verdicts outside the seed
     (fernflower: a one-line edit with 196 dependent types).
     */
    private static void callSites(Element oldRoot, Element newRoot, InfoMapView view) {
        if (newRoot == null || oldRoot == newRoot) return;
        List<Expression> oldCalls = calls(oldRoot);
        List<Expression> newCalls = calls(newRoot);
        if (oldCalls.size() != newCalls.size()) return;
        for (int i = 0; i < oldCalls.size(); i++) {
            Expression o = oldCalls.get(i);
            Expression n = newCalls.get(i);
            if (o.getClass() != n.getClass() || !callee(o).equals(callee(n))) return;
        }
        for (int i = 0; i < oldCalls.size(); i++) {
            PropertyValueMap from = oldCalls.get(i).analysis();
            PropertyValueMap to = newCalls.get(i).analysis();
            if (from == to) continue;
            from.propertyValueStream()
                    .filter(pv -> ClearDerived.DERIVED.test(pv.property()))
                    .filter(pv -> !to.haveAnalyzedValueFor(pv.property()))
                    .forEach(pv -> {
                        try {
                            to.set(pv.property(), pv.value().rewire(view));
                        } catch (UnsupportedOperationException e) {
                            // a value without a rewire: not carried, the recompute derives it again
                        }
                    });
        }
    }

    // the calls of a body in visit order, as body() walks it: a lambda's and a local type's are their own methods'
    private static List<Expression> calls(Element root) {
        List<Expression> list = new ArrayList<>();
        root.visit(e -> {
            if (e instanceof MethodCall || e instanceof ConstructorCall) list.add((Expression) e);
            return !(e instanceof Lambda) && !(e instanceof LocalTypeDeclaration);
        });
        return list;
    }

    private static String callee(Expression call) {
        MethodInfo mi = call instanceof MethodCall mc ? mc.methodInfo() : ((ConstructorCall) call).constructor();
        return mi == null ? "" : mi.fullyQualifiedName();
    }

    // as ClearDerived.body: the statements' variable data, and the types and lambdas declared inside
    private static void body(Element root, InfoMapView view) {
        root.visit(e -> {
            if (e instanceof Statement statement) {
                variableData(statement.analysis(), view);
            }
            if (e instanceof ConstructorCall cc && cc.anonymousClass() != null) {
                type(cc.anonymousClass(), view);
            } else if (e instanceof Lambda lambda) {
                method(lambda.methodInfo(), view);
                return false;
            } else if (e instanceof LocalTypeDeclaration ltd) {
                type(ltd.typeInfo(), view);
                return false;
            }
            return true;
        });
    }

    private static void map(PropertyValueMap from, PropertyValueMap to, InfoMapView view) {
        if (from == to) return;
        to.removeIf(ClearDerived.DERIVED);
        to.setAll(from.rewire(view, ClearDerived.DERIVED));
    }

    // per variable: the links and the derived analysis, onto the copy the rewire made of each VariableInfo
    private static void variableData(PropertyValueMap analysis, InfoMapView view) {
        VariableDataImpl vd = analysis.getOrNull(VariableDataImpl.VARIABLE_DATA, VariableDataImpl.class);
        if (vd == null) return;
        vd.variableInfoContainerStream().forEach(vic -> {
            if (vic instanceof VariableInfoContainerImpl impl) {
                impl.forEachOwnInfo(vi -> variableInfo(vi, view));
            }
        });
    }

    private static void variableInfo(VariableInfoImpl oldVi, InfoMapView view) {
        VariableInfoImpl newVi = view.rewiredIfPresent(oldVi);
        if (newVi == null || newVi == oldVi) return;
        Links links = (Links) oldVi.peek(null); // without recording an access
        if (links != null && newVi.peek(null) == null) {
            newVi.setLinkedVariables((Links) links.rewire(view));
        }
        map(oldVi.analysis(), newVi.analysis(), view);
    }
}
