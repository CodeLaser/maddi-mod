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

import io.codelaser.maddi.cst.api.expression.Expression;
import io.codelaser.maddi.cst.api.info.FieldInfo;
import io.codelaser.maddi.cst.api.info.MethodInfo;
import io.codelaser.maddi.cst.api.info.ParameterInfo;
import io.codelaser.maddi.cst.api.type.ParameterizedType;
import io.codelaser.maddi.cst.api.variable.LocalVariable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static io.codelaser.maddi.modification.analyzer.nullability.Types.primitive;
import static io.codelaser.maddi.modification.analyzer.nullability.Types.testCode;

/**
 * The graph of one round of the nullability pass: its nodes (fields, parameters, methods standing for their return,
 * {@link Local}s, and the slots {@link Content}, {@link Arg}, {@link Keyed}, {@link AnonymousThis}, {@link CallResult},
 * {@link SiteArg}), the edges in the direction null travels, the flows that tie content slots, and the per-call
 * bookkeeping of gap 9. Filled by the walk and the rules that complete it; read by the closure and the verdicts.
 */
final class NullGraph {
    private final NullabilityPass.Policy policy;
    private final Trace trace;
    // Culprits: the statement the walk is at, where an edge built now comes from
    private final WalkSite site;

    NullGraph(NullabilityPass.Policy policy, Trace trace, WalkSite site) {
        this.policy = policy;
        this.trace = trace;
        this.site = site;
    }

    // every flow between two nodes, also one dropped as known non-null: the array Contents they tie (coupleContent)
    final List<List<Object>> flows = new ArrayList<>();
    // Kotlin's invariance between an instantiation's slot and the receiver's: {from, to, the formal slot}; added as
    // edges in once, unless the class itself makes the formal slot nullable (instantiatedSlots)
    final List<Object[]> invariantTies = new ArrayList<>();
    // holderFields under Policy.assertContentWrites: a field's edges to the instances' slots, added only when a null
    // written outside the field's class reaches it
    final Map<FieldInfo, List<Arg>> pendingHolders = new LinkedHashMap<>();
    final Map<Object, Set<Object>> successors = new LinkedHashMap<>();

    final boolean recordSites = io.codelaser.maddi.modification.common.util.Culprits.ENABLED;
    final Map<Object, Map<Object, Site>> edgeSites = new HashMap<>();
    // the analysed method a local belongs to (a lambda's local: the method the lambda is in)
    final Map<Local, MethodInfo> localOwner = new HashMap<>();
    // link-derived edges, decided after every statement was seen: dropped when the source is known non-null at every
    // statement that assigns it directly to the recipient (M4)
    final Map<List<Object>, int[]> linkEdges = new LinkedHashMap<>();
    final Map<Local, LocalVariable> declaredLocals = new LinkedHashMap<>();
    // CodeLaser/maddi-mod#22 gap 9: a call's own arguments. Each analysed call gets an id; SiteArg(id, i) receives what
    // argument i of that call carries, and nothing more (no successors). A call's result is the callee's return only
    // where that return is nullable by itself, or through a parameter whose argument at THIS call is nullable
    final Map<Expression, Integer> callIds = new IdentityHashMap<>();
    final Map<Integer, MethodInfo> callees = new HashMap<>();
    final List<GatedSite> gatedSites = new ArrayList<>();
    final Set<Integer> argumentsSeen = new HashSet<>();
    final Map<Local, ParameterInfo> shadows = new LinkedHashMap<>();

    void addEdge(Object from, Object to) {
        if (from == null || to == null || from.equals(to)) return;
        if (!policy.testNulls() && testCode(from) && !testCode(to)) return;
        // Kotlin: a primitive value carries no null on: unboxing into it already threw. guava's
        // 'Boolean apply(T t) { return predicate.apply(t); }' over 'boolean Predicate.apply' was 'Boolean?' through a
        // test's MockEquivalence.doEquivalent and the boolean equivalent()/doEquivalent() family (Functions.kt:103,
        // 2026-10-18). Gate NOPRIMITIVECUTS.
        if (policy.callResults() && !PRIMITIVE_CUTS_OFF.get() && !(from instanceof Content) && primitive(typeOf(from))) return;
        flows.add(List.of(from, to));
        if (recordSites && site.element != null) {
            edgeSites.computeIfAbsent(from, _ -> new HashMap<>()).putIfAbsent(to, new Site(site.method, site.element));
        }
        if (successors.computeIfAbsent(from, _ -> new LinkedHashSet<>()).add(to) && to instanceof Content c
            && trace.why(c.of())) {
            trace.log(from instanceof Content ? "content edge" : "element write", c.of(),
                    "from " + Trace.name(from) + " (" + from.getClass().getSimpleName() + ")");
        }
    }

    private static final Switches.Gate PRIMITIVE_CUTS_OFF = Switches.gate("NOPRIMITIVECUTS");

    Map<Object, Set<Object>> predecessors;
    int predecessorsOf = -1;
    final Map<MethodInfo, Set<ParameterInfo>> passThroughMemo = new HashMap<>();

    Map<Object, Set<Object>> predecessors() {
        int size = flows.size() + gatedSites.size();
        if (predecessors == null || predecessorsOf != size) {
            predecessors = new HashMap<>();
            successors.forEach((from, tos) -> tos.forEach(to ->
                    predecessors.computeIfAbsent(to, _ -> new HashSet<>()).add(from)));
            // a gated result's predecessor is its callee: over-approximates "by itself" (conservative)
            gatedSites.forEach(g -> predecessors.computeIfAbsent(g.target(), _ -> new HashSet<>()).add(g.callee()));
            predecessorsOf = size;
            passThroughMemo.clear();
        }
        return predecessors;
    }

    /* the callee's own (non-varargs) parameters from which the graph reaches its return value */
    Set<ParameterInfo> passThrough(MethodInfo callee) {
        Map<Object, Set<Object>> pred = predecessors();
        return passThroughMemo.computeIfAbsent(callee, m -> {
            Set<ParameterInfo> out = new HashSet<>();
            Set<Object> seen = new HashSet<>();
            Deque<Object> todo = new ArrayDeque<>(List.of(m));
            while (!todo.isEmpty()) {
                Object n = todo.removeFirst();
                if (!seen.add(n)) continue;
                if (n instanceof ParameterInfo pi && pi.methodInfo() == m && !pi.isVarArgs()) {
                    out.add(pi);
                    continue;
                }
                todo.addAll(pred.getOrDefault(n, Set.of()));
            }
            return out;
        });
    }

    // the declared type of a node; null when unknown (a local the pass did not see declared)
    ParameterizedType typeOf(Object node) {
        return switch (node) {
            case Arg a -> {
                ParameterizedType outer = typeOf(a.of());
                yield outer == null || outer.arrays() > 0 || a.index() >= outer.parameters().size() ? null
                        : outer.parameters().get(a.index());
            }
            case Keyed k -> typeOf(k.slot());
            case AnonymousThis at -> at.type().parentClass();
            case CallResult cr -> cr.call().concreteReturnType();
            case ParameterInfo pi -> pi.parameterizedType();
            case FieldInfo fi -> fi.type();
            case MethodInfo mi -> mi.returnType();
            case Local local -> declaredLocals.containsKey(local) ? declaredLocals.get(local).parameterizedType()
                    : shadows.containsKey(local) ? shadows.get(local).parameterizedType() : null;
            case Content c -> {
                ParameterizedType outer = typeOf(c.of());
                yield outer == null || outer.arrays() == 0 ? null : outer.componentType();
            }
            default -> null;
        };
    }

    /*
     Kotlin: a varargs parameter takes a copy ('newParameterizedType(Map.class, *typesIn)'), so its elements do not
     flow back into the caller's array, whatever the method or the caller does with either afterwards. TypesTest's
     'typesIn[0] = null' after the call (testing guava's defensive copy) reached resolveTypes' result through
     newParameterizedType(Class, Type...); ImmutableList.construct(Object... elements)'s annotated elements reached
     Types.newTypeVariableImpl's and newArtificialTypeVariable's bounds and captureAsTypeVariable's upperBounds
     (TypeResolver.kt:234, Types.kt:325). Exactly the (array, varargs parameter) pairs of such calls: the caller's
     elements still flow forward into the parameter's ('isVarReferenced(obj, *whitelist)' with a nullable element,
     fernflower VarExprent.kt:205), only the back edge and the forward edge of the reversed link go. An override
     family's varargs parameters stay tied as any other (ImmutableCollection.Builder.add(E...)). Only under
     Policy.callResults. Gate NOVARARGCOPIES.
     */
    final Set<List<Object>> varargsPasses = new HashSet<>();

    /*
     Kotlin: the loop variable over a library view reads the map's slots, but what the loop writes through it does not
     flow back. jenkins' EnvVars.resolve 'entry.setValue(Util.replaceMacro(entry.getValue(), env))' (an @Nullable
     return, null only for a null input) made masterEnvVars, EnvVars(Map) and through them EnvVars a map of 'String?'
     (EnvVars.kt:22/25/79, AbstractBuild.kt:179); DiskSpaceMonitorDescriptor.monitor() a Map<Computer, DiskSpace?>
     against its superclass's (DiskSpaceMonitorDescriptor.kt:20/42): 127 -> 133 errors (worktree-maddi-all
     2026-10-22). The write stays one mismatch at the loop. The (view slot, loop variable) flows, and the slot pairs
     under them. Gate NOVIEWLOOPWRITES.
     */
    final Set<List<Object>> viewLoopReads = new HashSet<>();

    int callId(Expression call, MethodInfo callee) {
        Integer id = callIds.get(call);
        if (id == null) {
            id = callIds.size();
            callIds.put(call, id);
            callees.put(id, callee);
        }
        return id;
    }
}
