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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import io.codelaser.maddi.cst.api.analysis.PropertyValueMap;
import io.codelaser.maddi.cst.api.info.Info;
import io.codelaser.maddi.cst.api.info.MethodInfo;
import io.codelaser.maddi.cst.api.info.ParameterInfo;
import io.codelaser.maddi.modification.link.impl.Gate;

/**
 * Experimental, gate {@code MODREACH_SCOPED}: the re-derivation after a MODREACH round that weakened, scoped to what
 * the change reaches instead of the whole order. The worklist starts at the changed elements and their dependents.
 * The first time the worklist re-analyses an element in this re-derivation, its derived family is cleared (as
 * {@code clearDerivedFamily} clears it for everything), after a snapshot of all its element-level values. Its
 * dependents are woken only when the re-analysis ends somewhere else than the snapshot: rewriting a cleared value
 * with the same content is not a change anyone downstream can see. The full verification passes stay as they are.
 */
final class ScopedRederivation {
    static final boolean ENABLED = Gate.isSet("MODREACH_SCOPED");

    private final Set<String> keys;
    private final Map<Info, Map<String, String>> snapshots = new HashMap<>();
    private final Set<Info> touchedThisPass = new HashSet<>();
    private int cleared;
    private int unchanged;

    ScopedRederivation(Set<String> keys) {
        this.keys = keys;
    }

    /** before a worklist pass: clear the elements the re-derivation reaches for the first time */
    void beforePass(List<Info> subset) {
        touchedThisPass.clear();
        for (Info info : subset) {
            if (snapshots.containsKey(info)) continue;
            snapshots.put(info, snapshot(info));
            touchedThisPass.add(info);
            cleared += clear(info.analysis());
            if (info instanceof MethodInfo mi) {
                for (ParameterInfo pi : mi.parameters()) cleared += clear(pi.analysis());
            }
        }
    }

    void afterPass() {
        touchedThisPass.clear();
    }

    /** an element cleared in this pass that came back where it was: its dependents need not run */
    boolean cameBack(Info orderElement) {
        if (orderElement == null || !touchedThisPass.contains(orderElement)) return false;
        boolean same = snapshot(orderElement).equals(snapshots.get(orderElement));
        if (same) unchanged++;
        return same;
    }

    String summary() {
        return snapshots.size() + " element(s) re-derived, " + cleared + " value(s) cleared, " + unchanged
               + " came back unchanged";
    }

    private int clear(PropertyValueMap analysis) {
        int[] n = {0};
        analysis.removeIf(p -> {
            boolean remove = keys.contains(p.key());
            if (remove) n[0]++;
            return remove;
        });
        return n[0];
    }

    private static Map<String, String> snapshot(Info info) {
        Map<String, String> map = new TreeMap<>();
        info.analysis().propertyValueStream().forEach(pv -> map.put(pv.property().key(), String.valueOf(pv.value())));
        if (info instanceof MethodInfo mi) {
            for (ParameterInfo pi : mi.parameters()) {
                pi.analysis().propertyValueStream().forEach(pv ->
                        map.put("p" + pi.index() + "." + pv.property().key(), String.valueOf(pv.value())));
            }
        }
        return map;
    }
}
