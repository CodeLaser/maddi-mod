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

import io.codelaser.maddi.cst.api.analysis.Value;
import io.codelaser.maddi.cst.api.element.Element;
import io.codelaser.maddi.cst.api.expression.Assignment;
import io.codelaser.maddi.cst.api.expression.Cast;
import io.codelaser.maddi.cst.api.expression.ConstructorCall;
import io.codelaser.maddi.cst.api.expression.EnclosedExpression;
import io.codelaser.maddi.cst.api.expression.Expression;
import io.codelaser.maddi.cst.api.expression.Lambda;
import io.codelaser.maddi.cst.api.expression.MethodCall;
import io.codelaser.maddi.cst.api.expression.VariableExpression;
import io.codelaser.maddi.cst.api.info.FieldInfo;
import io.codelaser.maddi.cst.api.info.MethodInfo;
import io.codelaser.maddi.cst.api.info.ParameterInfo;
import io.codelaser.maddi.cst.api.statement.ReturnStatement;
import io.codelaser.maddi.cst.api.variable.FieldReference;
import io.codelaser.maddi.cst.api.variable.Variable;
import io.codelaser.maddi.cst.impl.analysis.PropertyImpl;
import io.codelaser.maddi.cst.impl.analysis.ValueImpl;
import io.codelaser.maddi.modification.common.AnalysisHelper;
import io.codelaser.maddi.modification.common.util.Culprits;
import io.codelaser.maddi.modification.prepwork.variable.Links;
import io.codelaser.maddi.modification.prepwork.variable.MethodLinkedVariables;

import java.util.ArrayList;
import java.util.List;

/**
 * The culprit of a DEPENDENT verdict ({@link PropertyImpl#DEPENDENT_CULPRIT}), written beside it when a run asks for
 * culprits. The reason is the link {@code TypeModIndyAnalyzerImpl.worstLinkToFields} rests on, the first one to a
 * mutable field of the instance ({@link LinkToField#firstDependentLinkToField}, which the guard's blame reads too).
 * Linking follows the object, not the syntax, so the statement is found by the field it reaches: for a method, the
 * first {@code return} whose value mentions that field, else the first {@code return}, followed into a call on this
 * object that has a culprit of its own; for a parameter, the first statement mentioning both the parameter and the
 * field, else the first mentioning the parameter. The last step is the field, and why it counts: its type is mutable.
 */
final class DependentCulprit {
    private static final int TEXT = 60;

    private DependentCulprit() {
    }

    static void writeForMethod(MethodInfo methodInfo, MethodLinkedVariables mlv, AnalysisHelper analysisHelper) {
        Variable reached = LinkToField.firstDependentLinkToField(mlv.ofReturnValue(), methodInfo, analysisHelper);
        if (reached != null) writeForReturn(methodInfo, v -> same(v, reached), fieldStep(methodInfo, reached));
    }

    /** A getter ({@code GET_SET_FIELD}) is dependent on the field it returns, decided from the field's type. */
    static void writeForGetter(MethodInfo methodInfo, FieldInfo field) {
        writeForReturn(methodInfo, v -> v instanceof FieldReference fr && fr.fieldInfo().equals(field),
                fieldStep(field));
    }

    private static void writeForReturn(MethodInfo methodInfo, java.util.function.Predicate<Variable> reached,
                                       Value.Culprit.Step last) {
        if (methodInfo.methodBody() == null) return;
        List<ReturnStatement> returns = new ArrayList<>();
        methodInfo.methodBody().visit((Element e) -> {
            if (e instanceof Lambda) return false; // its returns are its own
            if (e instanceof ReturnStatement rs && rs.expression() != null && !rs.expression().isEmpty()) returns.add(rs);
            return true;
        });
        if (returns.isEmpty()) return;
        ReturnStatement rs = returns.stream().filter(r -> mentions(r.expression(), reached)).findFirst()
                .orElse(returns.getFirst());
        List<Value.Culprit.Step> steps = new ArrayList<>();
        Culprits.add(steps, Culprits.step(methodInfo, rs, "returns " + text(rs.expression())));
        if (unwrap(rs.expression()) instanceof MethodCall mc && mc.methodInfo() != null && mc.methodInfo() != methodInfo
            && mc.methodInfo().typeInfo().primaryType() == methodInfo.typeInfo().primaryType()) {
            ValueImpl.CulpritImpl inCallee = mc.methodInfo().analysis().getOrNull(PropertyImpl.DEPENDENT_CULPRIT,
                    ValueImpl.CulpritImpl.class);
            if (inCallee != null) {
                inCallee.steps().forEach(s -> Culprits.add(steps, s));
                write(methodInfo, steps);
                return;
            }
        }
        Culprits.add(steps, last);
        write(methodInfo, steps);
    }

    static void writeForParameter(ParameterInfo pi, MethodLinkedVariables mlv, AnalysisHelper analysisHelper) {
        MethodInfo methodInfo = pi.methodInfo();
        if (pi.index() >= mlv.ofParameters().size() || methodInfo.methodBody() == null) return;
        Links links = mlv.ofParameters().get(pi.index());
        Variable reached = LinkToField.firstDependentLinkToField(links, methodInfo, analysisHelper);
        if (reached == null) return;
        Element[] both = {null};
        Element[] parameterOnly = {null};
        methodInfo.methodBody().visit((Element e) -> {
            if (both[0] != null) return false;
            if (e instanceof Assignment || e instanceof MethodCall || e instanceof ConstructorCall) {
                boolean p = mentions(e, v -> v.equals(pi));
                if (p && mentions(e, v -> same(v, reached))) {
                    both[0] = e;
                    return false;
                }
                if (p && parameterOnly[0] == null) parameterOnly[0] = e;
            }
            return true;
        });
        Element at = both[0] != null ? both[0] : parameterOnly[0];
        List<Value.Culprit.Step> steps = new ArrayList<>();
        if (at != null) Culprits.add(steps, Culprits.step(methodInfo, at, describe(at)));
        Culprits.add(steps, fieldStep(methodInfo, reached));
        write(pi, steps);
    }

    private static Value.Culprit.Step fieldStep(FieldInfo fi) {
        return Culprits.step(fi, fi, "field " + fi.name() + " is mutable (" + fi.type().simpleString() + ")");
    }

    private static Value.Culprit.Step fieldStep(MethodInfo methodInfo, Variable reached) {
        if (reached instanceof FieldReference fr) return fieldStep(fr.fieldInfo());
        return Culprits.step(methodInfo, methodInfo, "captures " + reached.simpleName() + ", which is mutable");
    }

    private static void write(io.codelaser.maddi.cst.api.info.Info info, List<Value.Culprit.Step> steps) {
        ValueImpl.CulpritImpl culprit = new ValueImpl.CulpritImpl(steps);
        if (info.analysis().haveAnalyzedValueFor(PropertyImpl.DEPENDENT_CULPRIT)) {
            info.analysis().overwrite(PropertyImpl.DEPENDENT_CULPRIT, culprit);
        } else {
            info.analysis().set(PropertyImpl.DEPENDENT_CULPRIT, culprit);
        }
    }

    // whether the element reads or writes the variable: the same field (whatever its scope), or the variable itself
    private static boolean mentions(Element element, java.util.function.Predicate<Variable> variable) {
        boolean[] found = {false};
        element.visit((Element e) -> {
            if (found[0]) return false;
            if (e instanceof VariableExpression ve && variable.test(ve.variable())) found[0] = true;
            if (e instanceof Assignment a && a.variableTarget() != null && variable.test(a.variableTarget())) {
                found[0] = true;
            }
            return !found[0];
        });
        return found[0];
    }

    private static boolean same(Variable v, Variable target) {
        if (target instanceof FieldReference tfr) {
            return v instanceof FieldReference fr && fr.fieldInfo().equals(tfr.fieldInfo());
        }
        return v.equals(target);
    }

    private static Expression unwrap(Expression e) {
        return switch (e) {
            case EnclosedExpression ee -> unwrap(ee.inner());
            case Cast c -> unwrap(c.expression());
            default -> e;
        };
    }

    private static String describe(Element e) {
        return switch (e) {
            case Assignment a when a.variableTarget() instanceof FieldReference fr ->
                    "assigns " + fr.fieldInfo().name() + " = " + text(a.value());
            case MethodCall mc when mc.methodInfo() != null -> Culprits.describeCall(mc, mc.methodInfo());
            case ConstructorCall cc when cc.constructor() != null -> Culprits.describeCall(cc, cc.constructor());
            default -> "links it";
        };
    }

    private static String text(Expression e) {
        String s = e.toString().replaceAll("\\s+", " ").strip();
        return s.length() <= TEXT ? s : s.substring(0, TEXT - 1) + "…";
    }
}
