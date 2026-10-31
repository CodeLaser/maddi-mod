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

package io.codelaser.maddi.modification.analyzer.nullability;

import io.codelaser.maddi.cst.api.info.MethodInfo;
import io.codelaser.maddi.cst.api.info.ParameterInfo;
import io.codelaser.maddi.cst.api.type.ParameterizedType;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static io.codelaser.maddi.modification.analyzer.nullability.Types.*;

/**
 * {@link World#OPEN} and {@link World#OPEN_VISIBILITY}: the methods code outside the
 * analysis may call, and what the values it passes them reach ({@link #externalClosure}): UNSPECIFIED, where null
 * does not reach.
 */
final class OutsideCallers {
    private final Policy policy;
    private final Index index;
    private final Contracts contracts;
    private final NullGraph graph;
    private final NonNullFacts facts;

    OutsideCallers(Policy policy, Index index, Contracts contracts, NullGraph graph, NonNullFacts facts) {
        this.policy = policy;
        this.index = index;
        this.contracts = contracts;
        this.graph = graph;
        this.facts = facts;
    }

    // ------------------------------------------------------------------ outside callers (World.OPEN*)

    /*
     The parameters an outside caller may pass anything to, and what they flow into, along the same edges as null.
     Only the unreached ones matter: a reached node is nullable anyway.
     */
    Set<Object> externalClosure(List<MethodInfo> methods, Set<Object> reached) {
        Set<Object> set = new LinkedHashSet<>();
        for (MethodInfo mi : methods) {
            if (!externallyCallable(mi)) continue;
            for (ParameterInfo pi : mi.parameters()) {
                if (reached.contains(pi) || contracts.nonNullContracts.contains(pi)
                    || policy.world() == World.OPEN && facts.nonNullAtExit(mi, pi)
                    || contracts.libraryNonNullParameter(mi, pi.index())) {
                    addContent(set, pi, pi.parameterizedType(), reached);
                    continue;
                }
                set.add(pi);
                addContent(set, pi, pi.parameterizedType(), reached);
            }
        }
        Deque<Object> queue = new ArrayDeque<>(set);
        while (!queue.isEmpty()) {
            Object n = queue.removeFirst();
            for (Object s : graph.successors.getOrDefault(n, Set.of())) {
                if (!contracts.nonNullContracts.contains(s) && !reached.contains(s) && set.add(s)) queue.add(s);
            }
        }
        return set;
    }

    // an outside caller chooses the elements of an array it passes, whatever it does with the array itself
    private void addContent(Set<Object> set, Object node, ParameterizedType type, Set<Object> reached) {
        if (type.arrays() > 0) {
            Content content = new Content(node);
            if (!reached.contains(content) && !contracts.nonNullContracts.contains(content)) set.add(content);
            addContent(set, content, type.componentType(), reached);
            return;
        }
        for (int i = 0; i < type.parameters().size(); i++) {
            Arg arg = new Arg(node, i);
            if (!reached.contains(arg) && !contracts.nonNullContracts.contains(arg)) set.add(arg);
            addContent(set, arg, type.parameters().get(i), reached);
        }
    }

    boolean externallyCallable(MethodInfo mi) {
        if (visibleOutside(mi)) return true;
        // an override is called through what it overrides: from outside, or by the library itself
        return overrides(mi).stream().anyMatch(o -> !index.analysed.contains(o) || visibleOutside(o));
    }
}
