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
import io.codelaser.maddi.cst.api.info.FieldInfo;
import io.codelaser.maddi.cst.api.type.ParameterizedType;
import io.codelaser.maddi.cst.impl.analysis.PropertyImpl;
import io.codelaser.maddi.cst.impl.analysis.ValueImpl;
import io.codelaser.maddi.modification.common.AnalysisHelper;

/**
 * What a field HOLDS, as opposed to what its declared type says it may hold.
 * <p>
 * A {@code List<String>} field is DEPENDENT by declared type: handing it out shares a mutable object. But if the
 * object it actually holds is immutable — the defensive copy the constructor stored — then handing it out shares
 * nothing, and the field transmits no dependence. That "dynamic type" information lives in
 * {@link PropertyImpl#IMMUTABLE_FIELD}.
 * <p>
 * Extracted because three separate places grade a field by its declared type and must give the same answer:
 * {@code FieldAnalyzerImpl.computeIndependent} (the field's own independence),
 * {@code TypeModIndyAnalyzerImpl.handleGetterSetter} (a getter's independence), and
 * {@link LinkToField#immutableOfLinkedField} (a return value's link to a field, which the guard also uses to
 * explain a violation). If they drifted, a type could be graded independent while the accessor handing out its
 * field was graded dependent, or the guard would blame a link the verdict did not rest on.
 *
 * <h2>Whole object only</h2>
 * {@code @Immutable(hc=true)} on a field says the container it holds cannot change. It says nothing about the
 * elements, which are its hidden content. So this value may only be applied where the whole field object is
 * reached, never on a content tier ({@code §es}, {@code ∋}, {@code ∈}, or an indexed getter): otherwise a promise
 * about the container would silently be read as a promise about everything inside it. Each caller is responsible
 * for that check; this class only supplies the value.
 *
 * <h2>The holder's view (#159)</h2>
 * The elements are hidden content of the container, not of the type that holds it: a type that declares
 * {@code List<C> list} wrote {@code C}, and to it the elements are C objects it can call {@code increment()} on. A
 * {@code List.copyOf(in)} of mutable C shares those objects with the caller exactly as {@code new ArrayList<>(in)}
 * does. So the promise counts for the holder only when every type argument it wrote on the field's type is immutable
 * (with hidden content, then recursively its own arguments) or hidden content of the holder as well (a type
 * parameter, {@code Object}): {@link #ofField}. {@link #ofFieldObject} is the promise about the object itself, for
 * the inference that passes it from field to field and for the guard.
 *
 * <h2>Contract only, for now</h2>
 * Nothing infers the property yet — only a hand-written annotation, via {@code SourceContractMaterializer}. On
 * unannotated code every method here returns null and the declared type decides exactly as before, which is why
 * this whole feature is inert on the corpus. Inferring it is inter-procedural; see
 * {@code docs/design/dynamic-immutability-feasibility.md}.
 */
final class DynamicImmutability {

    private DynamicImmutability() {
        // utility
    }

    /**
     * The field's contracted dynamic immutability, or {@code null} when there is none or it is too weak to say
     * anything about sharing. Below immutable-HC is ignored rather than returned: a contracted
     * {@code @FinalFields} must not be read as an independence claim.
     */
    static Value.Immutable ofField(FieldInfo fieldInfo) {
        Value.Immutable dynamic = ofFieldObject(fieldInfo);
        if (dynamic == null) return null;
        return argumentsImmutableForHolder(fieldInfo.type(), 0) ? dynamic : null;
    }

    /** The promise about the object the field holds, whatever its elements are to the field's owner. */
    static Value.Immutable ofFieldObject(FieldInfo fieldInfo) {
        Value.Immutable dynamic = fieldInfo.analysis().getOrNull(PropertyImpl.IMMUTABLE_FIELD,
                ValueImpl.ImmutableImpl.class);
        return dynamic != null && dynamic.isAtLeastImmutableHC() ? dynamic : null;
    }

    private static final AnalysisHelper ANALYSIS_HELPER = new AnalysisHelper();
    private static final int MAX_ARGUMENT_DEPTH = 4;

    /*
     An undecided argument reads as mutable (AnalysisHelper.typeImmutable): no promise yet, the declared type decides,
     and a later pass improves the verdict once the argument is decided.
     */
    private static boolean argumentsImmutableForHolder(ParameterizedType type, int depth) {
        for (ParameterizedType arg : type.parameters()) {
            if (arg.isTypeParameter() || arg.bestTypeInfo() == null
                || arg.arrays() == 0 && arg.bestTypeInfo().isJavaLangObject()) {
                continue; // hidden content of the holder too
            }
            Value.Immutable immutable = ANALYSIS_HELPER.typeImmutable(arg);
            if (immutable == null || !immutable.isAtLeastImmutableHC()) return false;
            if (!immutable.isImmutable()
                && (depth >= MAX_ARGUMENT_DEPTH || !argumentsImmutableForHolder(arg, depth + 1))) {
                return false;
            }
        }
        return true;
    }

    /**
     * {@code fromDeclaredType} improved by the field's dynamic immutability. Combined with {@code max}, never
     * replaced: dynamic immutability is extra evidence and may only improve a verdict, so a contract can never
     * lower a field whose declared type is already immutable.
     * <p>
     * A null {@code fromDeclaredType} means "undecided, wait". A definite contract removes the reason to wait,
     * because the declared type's own immutability can no longer change the answer downwards.
     */
    static Value.Independent improve(Value.Independent fromDeclaredType, FieldInfo fieldInfo) {
        Value.Immutable dynamic = ofField(fieldInfo);
        if (dynamic == null) return fromDeclaredType;
        Value.Independent fromDynamic = dynamic.toCorrespondingIndependent();
        return fromDeclaredType == null ? fromDynamic : fromDeclaredType.max(fromDynamic);
    }
}
