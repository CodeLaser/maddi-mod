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
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.codelaser.maddi.cst.api.info.FieldInfo;
import io.codelaser.maddi.cst.api.info.Info;
import io.codelaser.maddi.cst.api.info.MethodInfo;
import io.codelaser.maddi.cst.api.info.TypeInfo;
import io.codelaser.maddi.graph.G;

/**
 * CodeLaser/maddi-mod#158: the call cycles of a dependency graph. A lambda, a local or an anonymous class and their
 * members count as the method that encloses them; an edge between two such methods is a call (or a method
 * reference). A strongly connected component of more than one method is a call cycle: mutual recursion.
 */
final class CallCycles {
    private CallCycles() {
    }

    /** @return a cycle id for every method that is in a call cycle; a method not in one is absent */
    static Map<MethodInfo, Integer> of(G<Info> graph) {
        Map<MethodInfo, Set<MethodInfo>> calls = new LinkedHashMap<>();
        graph.edgeStream().forEach(e -> {
            MethodInfo from = method(e.from().t());
            MethodInfo to = method(e.to().t());
            if (from != null && to != null && from != to) {
                calls.computeIfAbsent(from, _ -> new LinkedHashSet<>()).add(to);
            }
        });
        // Tarjan's strongly connected components, without recursion
        Map<MethodInfo, Integer> index = new HashMap<>();
        Map<MethodInfo, Integer> low = new HashMap<>();
        Map<MethodInfo, Integer> cycleOf = new HashMap<>();
        Deque<MethodInfo> stack = new ArrayDeque<>();
        Set<MethodInfo> onStack = new HashSet<>();
        int nextIndex = 0;
        int nextCycle = 0;
        record Frame(MethodInfo method, Iterator<MethodInfo> callees) {
        }
        for (MethodInfo root : calls.keySet()) {
            if (index.containsKey(root)) continue;
            Deque<Frame> work = new ArrayDeque<>();
            index.put(root, nextIndex);
            low.put(root, nextIndex++);
            stack.push(root);
            onStack.add(root);
            work.push(new Frame(root, calls.getOrDefault(root, Set.of()).iterator()));
            while (!work.isEmpty()) {
                Frame frame = work.peek();
                MethodInfo m = frame.method();
                if (frame.callees().hasNext()) {
                    MethodInfo callee = frame.callees().next();
                    if (!index.containsKey(callee)) {
                        index.put(callee, nextIndex);
                        low.put(callee, nextIndex++);
                        stack.push(callee);
                        onStack.add(callee);
                        work.push(new Frame(callee, calls.getOrDefault(callee, Set.of()).iterator()));
                    } else if (onStack.contains(callee)) {
                        low.put(m, Math.min(low.get(m), index.get(callee)));
                    }
                } else {
                    work.pop();
                    if (!work.isEmpty()) {
                        MethodInfo parent = work.peek().method();
                        low.put(parent, Math.min(low.get(parent), low.get(m)));
                    }
                    if (low.get(m).equals(index.get(m))) {
                        List<MethodInfo> component = new ArrayList<>();
                        MethodInfo top;
                        do {
                            top = stack.pop();
                            onStack.remove(top);
                            component.add(top);
                        } while (top != m);
                        if (component.size() > 1) {
                            int id = nextCycle++;
                            for (MethodInfo c : component) cycleOf.put(c, id);
                        }
                    }
                }
            }
        }
        return cycleOf;
    }

    private static MethodInfo method(Info info) {
        MethodInfo m;
        if (info instanceof MethodInfo mi) {
            m = mi;
        } else if (info instanceof TypeInfo ti) {
            m = ti.enclosingMethod();
        } else if (info instanceof FieldInfo fi) {
            m = fi.owner().enclosingMethod();
        } else {
            m = null;
        }
        if (m == null) return null;
        MethodInfo enclosing;
        while ((enclosing = m.typeInfo().enclosingMethod()) != null) m = enclosing;
        return m;
    }
}
