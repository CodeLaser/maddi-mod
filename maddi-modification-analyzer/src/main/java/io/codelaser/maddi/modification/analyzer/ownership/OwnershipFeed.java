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

import java.util.List;
import java.util.Map;
import java.util.Set;

import io.codelaser.maddi.cst.api.analysis.Value;
import io.codelaser.maddi.cst.api.info.FieldInfo;
import io.codelaser.maddi.cst.api.info.TypeInfo;
import io.codelaser.maddi.cst.api.type.ParameterizedType;
import io.codelaser.maddi.cst.impl.analysis.ValueImpl;
import io.codelaser.maddi.modification.common.AnalysisHelper;
import io.codelaser.maddi.cst.api.info.ParameterInfo;
import io.codelaser.maddi.cst.api.variable.FieldReference;
import io.codelaser.maddi.cst.api.variable.This;
import io.codelaser.maddi.cst.api.variable.Variable;

/**
 * What the ownership pass feeds back into the fixpoint (docs/roadmap/ownership.md, "Fed into the fixpoint"): the
 * parameters handed over into an owned field. Every caller hands its argument over and the method keeps it only in
 * that field, so the link from the parameter to the field, or to its modification face, is no dependence: the caller
 * holds the object no more. What the object contains, the caller may still hold: in the link's place counts
 * {@link #contentIndependence}, the independence of the elements.
 * <p>
 * Written only by the ownership round at the terminal certification point ({@code IteratingAnalyzerImpl}), read by
 * the independence of fields and parameters. Empty unless the ownership pass is switched on; gate
 * {@code NOOWNERSHIPFEED} keeps it empty.
 */
public final class OwnershipFeed {

    public enum Delta {UNCHANGED, GROWN, SHRUNK}

    private volatile Map<ParameterInfo, FieldInfo> handedOver = Map.of();

    /** The parameter is handed over into {@code field}, an owned field of the same instance. */
    public boolean handedOverInto(ParameterInfo pi, FieldInfo field) {
        return field.equals(handedOver.get(pi));
    }

    /** The field a handed-over parameter is kept in, or null. */
    public FieldInfo fieldOf(ParameterInfo pi) {
        return handedOver.get(pi);
    }

    /**
     * Whether {@code to}, a link target of the parameter, is the field it is handed over into, or that field's
     * modification face: {@code this.f}, {@code this.f.§m}.
     */
    public boolean isItsField(ParameterInfo pi, Variable to) {
        FieldInfo field = handedOver.get(pi);
        if (field == null) return false;
        Variable v = to instanceof FieldReference face && "§m".equals(face.fieldInfo().name())
                ? face.scopeVariable() : to;
        return v instanceof FieldReference fr && fr.fieldInfo().equals(field)
               && (fr.scope() == null || fr.scopeVariable() instanceof This);
    }

    /**
     * What a handed-over object still shares with the caller: its elements. Only for a {@code java.util} container or
     * an array, whose content is exactly its type arguments or component; any other type may hold objects the caller
     * kept through fields of its own, and is dependent. Null while an element type's immutability is undecided.
     */
    public static Value.Independent contentIndependence(FieldInfo field, AnalysisHelper analysisHelper) {
        ParameterizedType type = field.type();
        List<ParameterizedType> contents;
        if (type.arrays() > 0) {
            contents = List.of(type.copyWithOneFewerArrays());
        } else {
            TypeInfo typeInfo = type.typeInfo();
            if (typeInfo == null || !"java.util".equals(typeInfo.packageName()) || type.parameters().isEmpty()) {
                return ValueImpl.IndependentImpl.DEPENDENT;
            }
            contents = type.parameters();
        }
        Value.Independent result = ValueImpl.IndependentImpl.INDEPENDENT;
        for (ParameterizedType content : contents) {
            Value.Immutable immutable = analysisHelper.typeImmutableNullIfUndecided(content);
            if (immutable == null) return null;
            if (immutable.isImmutable()) continue;
            Value.Independent independent = analysisHelper.typeIndependentFromImmutableOrNull(content);
            if (independent == null) return null;
            result = result.min(independent);
        }
        return result;
    }

    public Map<ParameterInfo, FieldInfo> handedOver() {
        return handedOver;
    }

    public boolean isEmpty() {
        return handedOver.isEmpty();
    }

    /** Replace the feed; the parameters whose entry was added, changed or removed go into {@code changed}. */
    public Delta update(Map<ParameterInfo, FieldInfo> next, Set<ParameterInfo> changed) {
        Map<ParameterInfo, FieldInfo> previous = handedOver;
        boolean lost = false;
        boolean gained = false;
        for (Map.Entry<ParameterInfo, FieldInfo> e : previous.entrySet()) {
            if (!e.getValue().equals(next.get(e.getKey()))) {
                lost = true;
                changed.add(e.getKey());
            }
        }
        for (Map.Entry<ParameterInfo, FieldInfo> e : next.entrySet()) {
            if (!e.getValue().equals(previous.get(e.getKey()))) {
                gained = true;
                changed.add(e.getKey());
            }
        }
        handedOver = Map.copyOf(next);
        return lost ? Delta.SHRUNK : gained ? Delta.GROWN : Delta.UNCHANGED;
    }

    public void clear() {
        handedOver = Map.of();
    }

}
