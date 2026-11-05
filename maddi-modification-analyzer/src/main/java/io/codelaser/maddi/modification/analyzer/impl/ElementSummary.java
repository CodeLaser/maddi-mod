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

import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;

import io.codelaser.maddi.cst.api.analysis.Property;
import io.codelaser.maddi.cst.api.analysis.PropertyValueMap;
import io.codelaser.maddi.cst.api.info.Info;
import io.codelaser.maddi.cst.api.info.MethodInfo;
import io.codelaser.maddi.cst.api.info.ParameterInfo;
import io.codelaser.maddi.cst.api.info.TypeInfo;

/**
 * What a dependent can see of an element: its cross-type-derived values, and a method's parameters' values, as text
 * with names instead of identities, so that the summary of an element of one generation compares with the summary of
 * the element of the same name in the next. The early cutoff of a seeded analysis compares a re-parsed element's
 * summary with the previous generation's ({@link IteratingAnalyzerImpl#setPriorSummaries}): equal, its dependents'
 * carried values still hold.
 * <p>
 * A value whose text holds an identity makes two equal values differ; that costs a wake, never a wrong result.
 */
public class ElementSummary {

    private ElementSummary() {
    }

    public static String of(Info info) {
        StringBuilder sb = new StringBuilder();
        append(sb, "", info.analysis());
        if (info instanceof MethodInfo mi) {
            for (ParameterInfo pi : mi.parameters()) append(sb, pi.index() + ":", pi.analysis());
        }
        return sb.toString();
    }

    /** The summaries of every element of {@code primaryTypes}, theirs and their nested types', by name. */
    public static Map<String, String> ofTypes(Iterable<TypeInfo> primaryTypes) {
        Map<String, String> map = new HashMap<>();
        for (TypeInfo primaryType : primaryTypes) {
            primaryType.recursiveSubTypeStream().forEach(type -> {
                map.put(type.fullyQualifiedName(), of(type));
                type.constructorAndMethodStream().forEach(m -> map.put(m.fullyQualifiedName(), of(m)));
                type.fields().forEach(f -> map.put(f.fullyQualifiedName(), of(f)));
            });
        }
        return map;
    }

    /*
     Not compared: the values written after the iteration loop, which a seeded run has not written yet when the cutoff
     compares, and which are computed over the whole order afterwards anyway: nullability (NullabilityPass) and the
     culprits, the explanations of a verdict (GuardAnalyzerImpl, TypeCulprit), which feed no other verdict.
     */
    private static boolean afterTheLoop(Property property) {
        String key = property.key();
        return key.startsWith("nullability") || key.endsWith("Culprit") || key.contains("CulpritMethod");
    }

    private static void append(StringBuilder sb, String prefix, PropertyValueMap map) {
        TreeMap<String, String> sorted = new TreeMap<>();
        map.propertyValueStream()
                .filter(pv -> pv.property().analysisTier() == Property.AnalysisTier.CROSS_TYPE_DERIVED)
                .filter(pv -> !afterTheLoop(pv.property()))
                // a default is what an absent value reads as: a run that wrote it equals one that did not
                .filter(pv -> !pv.value().equals(pv.property().defaultValue()))
                .forEach(pv -> sorted.put(pv.property().key(), String.valueOf(pv.value())));
        sorted.forEach((k, v) -> sb.append(prefix).append(k).append('=').append(v).append('\n'));
    }
}
