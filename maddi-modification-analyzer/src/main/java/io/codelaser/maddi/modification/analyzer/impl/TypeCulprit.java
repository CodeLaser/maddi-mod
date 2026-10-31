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
import io.codelaser.maddi.cst.api.element.Source;
import io.codelaser.maddi.cst.api.expression.Assignment;
import io.codelaser.maddi.cst.api.info.FieldInfo;
import io.codelaser.maddi.cst.api.info.Info;
import io.codelaser.maddi.cst.api.info.MethodInfo;
import io.codelaser.maddi.cst.api.info.ParameterInfo;
import io.codelaser.maddi.cst.api.info.TypeInfo;
import io.codelaser.maddi.cst.api.type.ParameterizedType;
import io.codelaser.maddi.cst.api.variable.FieldReference;
import io.codelaser.maddi.cst.impl.analysis.PropertyImpl;
import io.codelaser.maddi.cst.impl.analysis.ValueImpl;
import io.codelaser.maddi.modification.common.AnalysisHelper;
import io.codelaser.maddi.modification.common.util.Culprits;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

import static io.codelaser.maddi.cst.impl.analysis.PropertyImpl.*;
import static io.codelaser.maddi.cst.impl.analysis.ValueImpl.ImmutableImpl.*;

/**
 * Why a type stops below {@code @Immutable}: {@code IMMUTABLE_CULPRIT}, the first rule it fails at the member that
 * fails it, then that member's own culprit; and {@code DEPENDENT_CULPRIT} on a dependent type. Written after the
 * fixpoint, from final values, beside the guard and with its evidence ({@code culpritsModifying},
 * {@code culpritsExposing}), so an explanation blames what a contract violation would.
 * <p>
 * The reasons mirror {@code TypeImmutableAnalyzerImpl.computeImmutableType0}, level by level: MUTABLE comes only from
 * an assignable field, a setter or a MUTABLE supertype; FINAL_FIELDS from a capping supertype, a modified field, a
 * non-private field of a mutable type, a dependent field or method, or a modifying abstract method; IMMUTABLE_HC from
 * extensibility, hidden content shared, or a field whose type is not deeply immutable. A reason given is a reason
 * that holds; when none of them can be pinned to a member, nothing is written rather than something vague.
 * <p>
 * And why a type is not {@code @Container}: {@code CONTAINER_CULPRIT}, each modified parameter of a non-private
 * constructor or method.
 */
final class TypeCulprit {
    private static final int TEXT = 60;

    private final GuardAnalyzerImpl guard;
    private final AnalysisHelper analysisHelper;

    TypeCulprit(GuardAnalyzerImpl guard, AnalysisHelper analysisHelper) {
        this.guard = guard;
        this.analysisHelper = analysisHelper;
    }

    void write(TypeInfo typeInfo) {
        if (typeInfo.compilationUnit() == null || typeInfo.compilationUnit().externalLibrary()) return;
        Value.Immutable immutable = typeInfo.analysis().getOrNull(IMMUTABLE_TYPE, ValueImpl.ImmutableImpl.class);
        if (immutable != null && !immutable.isImmutable() && !guard.isEventual(typeInfo)) {
            List<Value.Culprit.Step> steps = new ArrayList<>();
            if (MUTABLE.equals(immutable)) mutable(typeInfo, steps);
            else if (FINAL_FIELDS.equals(immutable)) finalFields(typeInfo, steps);
            else if (IMMUTABLE_HC.equals(immutable)) hiddenContent(typeInfo, steps);
            set(typeInfo, IMMUTABLE_CULPRIT, steps);
        }
        Value.Independent independent = typeInfo.analysis().getOrNull(INDEPENDENT_TYPE, ValueImpl.IndependentImpl.class);
        if (independent != null && independent.isDependent()) {
            List<Value.Culprit.Step> steps = new ArrayList<>();
            for (FieldInfo field : typeInfo.fields()) {
                if (dependentField(field, steps, "")) break;
            }
            set(typeInfo, DEPENDENT_CULPRIT, steps);
        }
        Value.Bool container = typeInfo.analysis().getOrNull(CONTAINER_TYPE, ValueImpl.BoolImpl.class);
        if (container != null && container.isFalse()) {
            List<Value.Culprit.Step> steps = new ArrayList<>();
            notContainer(typeInfo, steps);
            set(typeInfo, CONTAINER_CULPRIT, steps);
        }
    }

    // ---- not a container: the modified parameters, as TypeContainerAnalyzerImpl enumerates them ----

    /*
     One step per blocking parameter, so that the number of steps is the number of parameters standing between the
     type and @Container: at the statement that modifies it (its own culprit's first step), else at the parameter.
     */
    private void notContainer(TypeInfo typeInfo, List<Value.Culprit.Step> steps) {
        for (MethodInfo method : typeInfo.constructorAndMethodStream().toList()) {
            if (method.access().isPrivate()) continue;
            for (ParameterInfo parameter : method.parameters()) {
                if (parameter.isUnmodified()) continue;
                String what = "parameter " + parameter.name() + " of "
                              + (method.isConstructor() ? "the constructor" : method.name() + "(..)") + " is modified";
                if (method.isAbstract()) {
                    MethodInfo implementation = modifyingImplementation(method, parameter.index());
                    steps.add(Culprits.step(parameter, parameter, implementation == null ? what : what + ": "
                            + implementation.typeInfo().simpleName() + "." + implementation.name()
                            + "(..), which implements it, modifies it"));
                    continue;
                }
                List<Value.Culprit.Step> own = culpritOf(parameter, MODIFIED_CULPRIT);
                Value.Culprit.Step first = own.stream().filter(st -> st.line() > 0).findFirst().orElse(null);
                steps.add(first == null ? Culprits.step(parameter, parameter, what)
                        : new Value.Culprit.Step(first.uri(), first.line(), first.column(), what + ": "
                                                                                            + first.text()));
            }
        }
    }

    private static MethodInfo modifyingImplementation(MethodInfo method, int index) {
        Value.SetOfMethodInfo implementations = method.analysis().getOrDefault(IMPLEMENTATIONS,
                ValueImpl.SetOfMethodInfoImpl.EMPTY);
        for (MethodInfo implementation : implementations.methodInfoSet()) {
            if (index < implementation.parameters().size() && !implementation.parameters().get(index).isUnmodified()) {
                return implementation;
            }
        }
        return null;
    }

    // ---- MUTABLE: rule 0, setters, a mutable supertype ----

    private void mutable(TypeInfo typeInfo, List<Value.Culprit.Step> steps) {
        for (FieldInfo field : typeInfo.fields()) {
            if (field.isPropertyFinal()) continue;
            Culprits.add(steps, Culprits.step(field, field, "field " + field.name() + " is assigned after construction"
                                                            + " (rule 0: all fields effectively final)"));
            Assigned assigned = assignmentAfterConstruction(typeInfo, field);
            if (assigned != null) {
                Culprits.add(steps, Culprits.step(assigned.method(), assigned.assignment(), "assigned in "
                        + assigned.method().name() + "(..): " + text(assigned.assignment())));
            }
            return;
        }
        for (MethodInfo method : typeInfo.methods()) {
            if (method.getSetField() != null && method.getSetField().setter()) {
                Culprits.add(steps, Culprits.step(method, method, method.name() + "(..) is a setter"));
                return;
            }
        }
        supertype(typeInfo, steps, true);
    }

    private record Assigned(MethodInfo method, Element assignment) {
    }

    /* the first assignment to the field outside a constructor; non-private methods first, as the likelier culprits */
    private static Assigned assignmentAfterConstruction(TypeInfo typeInfo, FieldInfo field) {
        for (boolean nonPrivate : new boolean[]{true, false}) {
            for (MethodInfo mi : typeInfo.constructorAndMethodStream().toList()) {
                if (mi.isConstructor() || mi.methodBody() == null || mi.methodBody().isEmpty()) continue;
                if (nonPrivate == mi.access().isPrivate()) continue;
                Element[] at = {null};
                mi.methodBody().visit((Element e) -> {
                    if (at[0] != null) return false;
                    if (e instanceof Assignment a && a.variableTarget() instanceof FieldReference fr
                        && fr.fieldInfo() == field) {
                        at[0] = e;
                    }
                    return at[0] == null;
                });
                if (at[0] != null) return new Assigned(mi, at[0]);
            }
        }
        return null;
    }

    // ---- FINAL_FIELDS: a capping supertype, rules 1, 2, 3, a modifying abstract method ----

    private void finalFields(TypeInfo typeInfo, List<Value.Culprit.Step> steps) {
        for (FieldInfo field : typeInfo.fields()) {
            if (isSelf(field)) continue; // rules 1 and 2 skip a self-referencing field
            int rule = guard.immutableFieldFailingRule(field);
            if (rule == 1 && !field.isIgnoreModifications()) {
                modifiedField(typeInfo, field, steps);
                return;
            }
            if (rule == 2) {
                Culprits.add(steps, Culprits.step(field, field, "field " + field.name() + " is not private, and its"
                        + " type " + field.type().simpleString() + " is not immutable (rule 2)"));
                return;
            }
        }
        for (FieldInfo field : typeInfo.fields()) {
            if (dependentField(field, steps, " (rule 3)")) return;
        }
        for (MethodInfo method : typeInfo.methods()) {
            if (method.isAbstract() && isFalse(method, NON_MODIFYING_METHOD)) {
                modifyingAbstractMethod(method, steps);
                return;
            }
        }
        for (MethodInfo method : typeInfo.methods()) {
            Value.Independent independent = method.analysis().getOrNull(INDEPENDENT_METHOD,
                    ValueImpl.IndependentImpl.class);
            if (!method.access().isPrivate() && independent != null && independent.isDependent()) {
                Culprits.add(steps, Culprits.step(method, method, method.name() + "(..) is @Dependent: it exposes"
                                                                  + " the type's mutable content (rule 3)"));
                culpritOf(method, DEPENDENT_CULPRIT).forEach(s -> Culprits.add(steps, s));
                return;
            }
        }
        supertype(typeInfo, steps, false);
    }

    private void modifiedField(TypeInfo typeInfo, FieldInfo field, List<Value.Culprit.Step> steps) {
        Culprits.add(steps, Culprits.step(field, field, "field " + field.name() + " is modified (rule 1: all fields"
                                                        + " @NotModified)"));
        List<GuardAnalyzerImpl.Culprit> modifying = guard.culpritsModifying(typeInfo, field);
        if (!modifying.isEmpty()) {
            steps.add(step(modifying.getFirst()));
        } else {
            culpritOf(field, MODIFIED_CULPRIT).forEach(s -> Culprits.add(steps, s));
        }
    }

    /* rule 3, at a field: the statement exposing it, then the field; false when the field is not dependent */
    private boolean dependentField(FieldInfo field, List<Value.Culprit.Step> steps, String rule) {
        Value.Independent independent = field.analysis().getOrNull(INDEPENDENT_FIELD, ValueImpl.IndependentImpl.class);
        if (independent == null || !independent.isDependent() || isSelf(field)) return false;
        List<GuardAnalyzerImpl.Culprit> exposing = guard.culpritsExposing(field);
        if (exposing.isEmpty()) {
            // a non-private field needs no method to expose it (FieldAnalyzerImpl.computeIndependent)
            if (field.access().isPrivate()) return false;
            Culprits.add(steps, Culprits.step(field, field, "field " + field.name() + " is not private: the outside"
                    + " world can modify its " + field.type().simpleString() + " directly" + rule));
            return true;
        }
        Culprits.add(steps, Culprits.step(field, field, "field " + field.name() + " is dependent: exposed to, or"
                                                        + " shared with, the outside world" + rule));
        steps.add(step(exposing.getFirst()));
        return true;
    }

    /*
     An abstract method is modifying because an implementation is (the abstract-method fold): the method, then the
     first modifying implementation with its own culprit -- the reverse of the usual direction, which is exactly what
     makes the verdict surprising (Shape.area() modifying because Circle.area() counts its calls).
     */
    private void modifyingAbstractMethod(MethodInfo method, List<Value.Culprit.Step> steps) {
        Culprits.add(steps, Culprits.step(method, method, "abstract method " + method.name() + "(..) is @Modified"
                                                          + " (abstract methods must be @NotModified)"));
        Value.SetOfMethodInfo implementations = method.analysis().getOrDefault(IMPLEMENTATIONS,
                ValueImpl.SetOfMethodInfoImpl.EMPTY);
        for (MethodInfo implementation : implementations.methodInfoSet()) {
            if (!isFalse(implementation, NON_MODIFYING_METHOD)) continue;
            Value.Culprit.Step because = Culprits.step(implementation, implementation, "because "
                    + implementation.typeInfo().simpleName() + "." + implementation.name() + "(..), which implements"
                    + " it, is @Modified");
            List<Value.Culprit.Step> inImplementation = culpritOf(implementation, MODIFIED_CULPRIT);
            // the implementation's own chain starts at its declaration too: one step, saying both, not the second only
            if (!inImplementation.isEmpty() && samePlace(because, inImplementation.getFirst())) {
                Culprits.add(steps, new Value.Culprit.Step(because.uri(), because.line(), because.column(),
                        because.text() + ": " + inImplementation.getFirst().text()));
                inImplementation.subList(1, inImplementation.size()).forEach(s -> Culprits.add(steps, s));
            } else {
                Culprits.add(steps, because);
                inImplementation.forEach(s -> Culprits.add(steps, s));
            }
            return;
        }
    }

    /*
     A supertype that caps the type: at MUTABLE (mutable == true) a MUTABLE one, else one below IMMUTABLE_HC. Source
     interfaces are walked by their abstract methods rather than capping by verdict, so only a superclass or a
     library interface is blamed here.
     */
    private void supertype(TypeInfo typeInfo, List<Value.Culprit.Step> steps, boolean mutable) {
        List<ParameterizedType> supers = new ArrayList<>();
        if (typeInfo.parentClass() != null) supers.add(typeInfo.parentClass());
        typeInfo.interfacesImplemented().stream()
                .filter(i -> i.typeInfo() != null && i.typeInfo().compilationUnit() != null
                             && i.typeInfo().compilationUnit().externalLibrary())
                .forEach(supers::add);
        for (ParameterizedType pt : supers) {
            TypeInfo superType = pt.typeInfo();
            if (superType == null || "java.lang.Object".equals(superType.fullyQualifiedName())) continue;
            Value.Immutable immutable = superType.analysis().getOrNull(IMMUTABLE_TYPE, ValueImpl.ImmutableImpl.class);
            if (immutable == null) {
                // undecided at the end (a library type without hints, typically): cycle breaking counts it as at
                // most FINAL_FIELDS -- never as MUTABLE, so it explains only the FINAL_FIELDS cap
                if (!mutable) {
                    Culprits.add(steps, Culprits.step(typeInfo, typeInfo, (superType.isInterface() ? "implements "
                            : "extends ") + superType.fullyQualifiedName() + ", for which there is no immutability"
                            + " verdict: counted as at most @FinalFields"));
                    return;
                }
                continue;
            }
            if (mutable ? MUTABLE.equals(immutable) : !immutable.isAtLeastImmutableHC()) {
                String verdict = MUTABLE.equals(immutable) ? "@Mutable" : "@FinalFields";
                Culprits.add(steps, Culprits.step(typeInfo, typeInfo, (typeInfo.isInterface() || superType.isInterface()
                        ? "implements " : "extends ") + superType.simpleName() + ", which is " + verdict));
                culpritOf(superType, IMMUTABLE_CULPRIT).forEach(s -> Culprits.add(steps, s));
                return;
            }
        }
    }

    // ---- IMMUTABLE_HC: extensible, hidden content shared, or a field not deeply immutable ----

    private void hiddenContent(TypeInfo typeInfo, List<Value.Culprit.Step> steps) {
        for (FieldInfo field : typeInfo.fields()) {
            if (isSelf(field)) continue;
            ParameterizedType type = field.type();
            if (type.isTypeParameter()) {
                Culprits.add(steps, Culprits.step(field, field, "field " + field.name() + " is of type parameter "
                        + type.simpleString() + ": its objects are hidden content, of any type"));
                return;
            }
            Value.Immutable fieldType = analysisHelper.typeImmutableNullIfUndecided(type);
            if (fieldType != null && !fieldType.isImmutable()) {
                Culprits.add(steps, Culprits.step(field, field, "field " + field.name() + " holds "
                        + type.simpleString() + ", which is not deeply immutable: hidden content"));
                return;
            }
        }
        if (typeInfo.isExtensible()) {
            Culprits.add(steps, Culprits.step(typeInfo, typeInfo, typeInfo.simpleName() + " can be extended: a"
                    + " subtype may add content this type cannot see"));
        }
    }

    // ---- helpers ----

    private static boolean samePlace(Value.Culprit.Step a, Value.Culprit.Step b) {
        return a.line() == b.line() && a.column() == b.column() && java.util.Objects.equals(a.uri(), b.uri());
    }

    private static boolean isSelf(FieldInfo field) {
        TypeInfo bestType = field.type().bestTypeInfo();
        return bestType != null && bestType.equals(field.owner());
    }

    private static boolean isFalse(Info info, io.codelaser.maddi.cst.api.analysis.Property property) {
        Value.Bool b = info.analysis().getOrNull(property, ValueImpl.BoolImpl.class);
        return b != null && b.isFalse();
    }

    private static List<Value.Culprit.Step> culpritOf(Info info, io.codelaser.maddi.cst.api.analysis.Property property) {
        ValueImpl.CulpritImpl culprit = info.analysis().getOrNull(property, ValueImpl.CulpritImpl.class);
        return culprit == null ? List.of() : culprit.steps();
    }

    private static Value.Culprit.Step step(GuardAnalyzerImpl.Culprit culprit) {
        Source source = culprit.source();
        URI uri = culprit.method().compilationUnit() == null ? null : culprit.method().compilationUnit().uri();
        return new Value.Culprit.Step(uri == null ? null : uri.toString(), source == null ? 0 : source.beginLine(),
                source == null ? 0 : source.beginPos(), culprit.what());
    }

    private static void set(Info info, io.codelaser.maddi.cst.api.analysis.Property property,
                            List<Value.Culprit.Step> steps) {
        if (steps.isEmpty()) return;
        ValueImpl.CulpritImpl culprit = new ValueImpl.CulpritImpl(steps);
        if (info.analysis().haveAnalyzedValueFor(property)) info.analysis().overwrite(property, culprit);
        else info.analysis().set(property, culprit);
    }

    private static String text(Element e) {
        String s = e.toString().replaceAll("\\s+", " ").strip();
        return s.length() <= TEXT ? s : s.substring(0, TEXT - 1) + "…";
    }
}
