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

import java.util.ArrayDeque;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;

import io.codelaser.maddi.cst.api.info.Info;
import io.codelaser.maddi.cst.api.info.MethodInfo;
import io.codelaser.maddi.cst.api.info.ParameterInfo;

/**
 * Diagnostic, gate {@code MODREACH_REACHLOG} (presence-only, no effect on the analysis): how far does a MODREACH
 * round's re-derivation actually reach? At the clear, it snapshots every property value of the analysis order
 * (elements and their parameters) and measures the distance of each element from the round's changed elements in
 * the worklist's reverse adjacency. At the next terminal point, it reports which elements moved and at what
 * distance. Moved elements all close to the changed ones say a scoped clear-and-re-derive would do; moved elements
 * the adjacency does not reach say it would not.
 */
final class ModReachReachLog {
    static final boolean ENABLED = System.getenv("MODREACH_REACHLOG") != null;
    private static final int UNREACHED = Integer.MAX_VALUE;

    private final String round;
    private final int changedCount;
    private final Map<Info, Map<String, String>> before;
    private final Map<Info, Integer> depth;
    private final long startNanos = System.nanoTime();

    private ModReachReachLog(String round, int changedCount, Map<Info, Map<String, String>> before,
                             Map<Info, Integer> depth) {
        this.round = round;
        this.changedCount = changedCount;
        this.before = before;
        this.depth = depth;
    }

    static ModReachReachLog start(String round, Collection<? extends Info> changed, List<Info> analysisOrder,
                                  Map<Info, Set<Info>> dependersOf, Function<Info, Info> toOrderElement) {
        Map<Info, Map<String, String>> before = new HashMap<>();
        for (Info info : analysisOrder) before.put(info, snapshot(info));
        // BFS over the raw vertices, as the worklist walks them; distance per order element
        Map<Info, Integer> depth = new HashMap<>();
        Map<Info, Integer> seen = new HashMap<>();
        ArrayDeque<Info> queue = new ArrayDeque<>();
        for (Info c : changed) {
            Info owner = c instanceof ParameterInfo pi ? pi.methodInfo() : c;
            if (seen.putIfAbsent(owner, 0) == null) queue.add(owner);
        }
        while (!queue.isEmpty()) {
            Info v = queue.poll();
            int d = seen.get(v);
            Info oe = toOrderElement.apply(v);
            if (oe != null) depth.merge(oe, d, Math::min);
            if (dependersOf == null) continue;
            for (Info w : dependersOf.getOrDefault(v, Set.of())) {
                if (seen.putIfAbsent(w, d + 1) == null) queue.add(w);
            }
        }
        return new ModReachReachLog(round, changed.size(), before, depth);
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

    void report() {
        Map<Integer, Integer> movedByDepth = new TreeMap<>();
        Map<Integer, Integer> reachedByDepth = new TreeMap<>();
        Map<String, Integer> movedKeys = new TreeMap<>();
        Set<String> farExamples = new java.util.TreeSet<>();
        int moved = 0;
        for (Map.Entry<Info, Map<String, String>> e : before.entrySet()) {
            Info info = e.getKey();
            int d = depth.getOrDefault(info, UNREACHED);
            reachedByDepth.merge(d, 1, Integer::sum);
            Map<String, String> now = snapshot(info);
            Set<String> keys = new HashSet<>(now.keySet());
            keys.addAll(e.getValue().keySet());
            boolean any = false;
            for (String k : keys) {
                if (!java.util.Objects.equals(now.get(k), e.getValue().get(k))) {
                    any = true;
                    movedKeys.merge(k.replaceFirst("^p\\d+\\.", "param."), 1, Integer::sum);
                }
            }
            if (any) {
                moved++;
                movedByDepth.merge(d, 1, Integer::sum);
                if ((d == UNREACHED || d > 3) && farExamples.size() < 10) {
                    farExamples.add(info.fullyQualifiedName() + "@" + (d == UNREACHED ? "-" : d));
                }
            }
        }
        System.out.println("MODREACH_REACH round " + round + ": " + changedCount + " changed; re-derivation took "
                           + (System.nanoTime() - startNanos) / 1_000_000 + " ms; " + moved + " of " + before.size()
                           + " order elements moved");
        System.out.println("MODREACH_REACH round " + round + " moved by distance: " + label(movedByDepth));
        System.out.println("MODREACH_REACH round " + round + " order by distance: " + label(reachedByDepth));
        System.out.println("MODREACH_REACH round " + round + " moved keys: " + movedKeys);
        if (!farExamples.isEmpty()) {
            System.out.println("MODREACH_REACH round " + round + " far: " + farExamples);
        }
    }

    private static String label(Map<Integer, Integer> byDepth) {
        StringBuilder sb = new StringBuilder();
        byDepth.forEach((d, n) -> sb.append(d == UNREACHED ? "unreached" : "d" + d).append('=').append(n).append(' '));
        return sb.toString().trim();
    }
}
