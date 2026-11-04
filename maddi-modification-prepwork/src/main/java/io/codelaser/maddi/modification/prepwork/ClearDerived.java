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

import java.util.function.Consumer;
import java.util.function.Predicate;

import io.codelaser.maddi.cst.api.analysis.Property;
import io.codelaser.maddi.cst.api.analysis.PropertyValueMap;
import io.codelaser.maddi.cst.api.element.Element;
import io.codelaser.maddi.cst.api.expression.ConstructorCall;
import io.codelaser.maddi.cst.api.expression.Lambda;
import io.codelaser.maddi.cst.api.info.FieldInfo;
import io.codelaser.maddi.cst.api.info.Info;
import io.codelaser.maddi.cst.api.info.MethodInfo;
import io.codelaser.maddi.cst.api.info.TypeInfo;
import io.codelaser.maddi.cst.api.statement.LocalTypeDeclaration;
import io.codelaser.maddi.cst.api.statement.Statement;
import io.codelaser.maddi.modification.prepwork.variable.impl.VariableDataImpl;

/**
 * Clear-before-recompute for a seeded analysis: before an element that holds the values of an earlier analysis is
 * analysed again, everything the link computer and the analyzer wrote on it goes, so that the new analysis starts
 * where a cold one would. A value only moves up (the monotonic guards, {@code setLinkedVariables}), so a value left
 * in place is a value the new analysis cannot lower.
 * <p>
 * The cross-type-derived tier ({@link Property.AnalysisTier#CROSS_TYPE_DERIVED}), on: the element; a method's
 * parameters; and, through the body, every statement and the linked variables and variable-level values of every
 * {@code VariableInfo} in the prep data (method, statements, field initializer), the bodies of the anonymous, lambda and
 * local types declared there included, which are not analysis-order elements of their own. Prep's output (the
 * intrinsic tier) stays: the variables, their assignments and reads, are what prep computed from the body.
 * <p>
 * A type element clears only its own values: its members are elements of the order and cleared when the worklist
 * pulls them in.
 */
public class ClearDerived {

    public static final Predicate<Property> DERIVED =
            p -> p.analysisTier() == Property.AnalysisTier.CROSS_TYPE_DERIVED;

    private ClearDerived() {
    }

    /** The hook for {@code IteratingAnalyzer.analyze(order, graph, seed, beforeFirstRecompute)}. */
    public static void element(Info info) {
        info.analysis().removeIf(DERIVED);
        switch (info) {
            case MethodInfo mi -> method(mi);
            case FieldInfo fi -> {
                clear(fi.analysisOfInitializer());
                if (fi.initializer() != null && !fi.initializer().isEmpty()) body(fi.initializer());
            }
            default -> {
            }
        }
    }

    private static void method(MethodInfo mi) {
        mi.analysis().removeIf(DERIVED);
        clear(mi.analysis());
        mi.parameters().forEach(pi -> pi.analysis().removeIf(DERIVED));
        if (mi.methodBody() != null) body(mi.methodBody());
    }

    private static void type(TypeInfo typeInfo) {
        typeInfo.recursiveSubTypeStream().forEach(type -> {
            type.analysis().removeIf(DERIVED);
            type.constructorAndMethodStream().forEach(ClearDerived::method);
            type.fields().forEach(fi -> {
                fi.analysis().removeIf(DERIVED);
                element(fi);
            });
        });
    }

    private static void body(Element root) {
        root.visit(e -> {
            if (e instanceof Statement statement) {
                statement.analysis().removeIf(DERIVED);
                clear(statement.analysis());
            }
            Consumer<TypeInfo> declared = ClearDerived::type;
            if (e instanceof ConstructorCall cc && cc.anonymousClass() != null) {
                declared.accept(cc.anonymousClass());
            } else if (e instanceof Lambda lambda) {
                method(lambda.methodInfo());
                return false;
            } else if (e instanceof LocalTypeDeclaration ltd) {
                declared.accept(ltd.typeInfo());
                return false;
            }
            return true;
        });
    }

    // the variable-level values in the prep data a map holds
    private static void clear(PropertyValueMap analysis) {
        VariableDataImpl vd = analysis.getOrNull(VariableDataImpl.VARIABLE_DATA, VariableDataImpl.class);
        if (vd != null) vd.clearDerived(DERIVED);
    }
}
