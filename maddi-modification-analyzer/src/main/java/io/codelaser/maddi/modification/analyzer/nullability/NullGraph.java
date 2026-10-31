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

import io.codelaser.maddi.cst.api.element.Element;
import io.codelaser.maddi.cst.api.expression.Assignment;
import io.codelaser.maddi.cst.api.expression.ConstructorCall;
import io.codelaser.maddi.cst.api.expression.Lambda;
import io.codelaser.maddi.cst.api.expression.MethodCall;
import io.codelaser.maddi.cst.api.expression.VariableExpression;
import io.codelaser.maddi.cst.api.info.TypeInfo;
import io.codelaser.maddi.cst.api.statement.LocalVariableCreation;
import io.codelaser.maddi.cst.api.type.NullableState;
import io.codelaser.maddi.cst.api.variable.DependentVariable;
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

import static io.codelaser.maddi.modification.analyzer.nullability.Types.*;

/**
 * The graph of one round of the nullability pass: its nodes (fields, parameters, methods standing for their return,
 * {@link Local}s, and the slots {@link Content}, {@link Arg}, {@link Keyed}, {@link AnonymousThis}, {@link CallResult},
 * {@link SiteArg}), the edges in the direction null travels, the flows that tie content slots, and the per-call
 * bookkeeping of gap 9. Filled by the walk and the rules that complete it; read by the closure and the verdicts.
 */
final class NullGraph {
    private final Policy policy;
    private final Trace trace;
    // Culprits: the statement the walk is at, where an edge built now comes from
    private final WalkSite site;
    private final Index index;
    private final TypeUses typeUses;
    private final Seeds seeds;
    private final Contracts contracts;

    NullGraph(Policy policy, Trace trace, WalkSite site, Index index, TypeUses typeUses, Seeds seeds,
              Contracts contracts) {
        this.policy = policy;
        this.trace = trace;
        this.site = site;
        this.index = index;
        this.typeUses = typeUses;
        this.seeds = seeds;
        this.contracts = contracts;
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

    final Map<Arg, Set<Object>> keysUsed = new LinkedHashMap<>();
    // what a written null reaches (the strict closure, from strictSeeds), as of the last closure: Report.writtenReach
    Set<Object> writtenReach = Set.of();
    // gate NOGAP9 for A/B: every call's result is the callee's return, as before
    static final Switches.Gate GAP9_OFF = Switches.gate("NOGAP9");
    // per method (a lambda is its own), the declarations of each name: the fallback when scopes do not resolve one
    final Map<MethodInfo, Map<String, List<Local>>> declaredByName = new HashMap<>();

    Set<Object> closure(Map<Object, Object> cause) {
        if (!policy.assertContentWrites()) {
            // the strict closure only for the Report (CodeLaser/maddi-mod#49): the verdicts are the full closure's
            writtenReach = Set.copyOf(closure(new HashMap<>(), this.seeds.strictSeeds, null));
            return closure(cause, this.seeds.seedOrigin.keySet(), null);
        }
        // Policy.assertContentWrites: first what null reaches without the library contracts; a value outside it
        // enters a slot only as an asserted write, so that edge is not followed, and a library seed on a slot itself
        // is an asserted write too
        Map<Object, Object> strictCause = new HashMap<>();
        Set<Object> strict = closure(strictCause, this.seeds.strictSeeds, null);
        writtenReach = Set.copyOf(strict);
        Set<Object> seeds = new LinkedHashSet<>(this.seeds.seedOrigin.keySet());
        seeds.removeIf(n -> isSlot(n) && !this.seeds.strictSeeds.contains(n));
        Set<Object> reached = closure(cause, seeds, strict);
        // explain a node null reaches by a written null with that chain: it is the one that lets the node's value
        // into a slot, where the first chain found may begin at a library contract or a default value
        strict.forEach(n -> {
            if (this.seeds.strictSeeds.contains(n)) cause.remove(n);
            else if (strictCause.containsKey(n)) cause.put(n, strictCause.get(n));
        });
        return reached;
    }

    /*
     The value an Optional holds is never null: 'Optional.ofNullable(x)' is empty for a null x, 'Optional.of(null)'
     throws. So its type argument stays non-null, as the interfaces declare it (langchain4j
     DefaultAiServiceStartedEvent.systemMessage(): 'Optional.ofNullable(this.systemMessage)' of a @Nullable field
     is an Optional<SystemMessage>, and Kotlin refused the override returning Optional<SystemMessage?>).
     */
    private boolean optionalValue(Object node) {
        if (!(node instanceof Arg arg) || arg.index() != 0) return false;
        ParameterizedType type = typeOf(arg.of());
        return type != null && type.arrays() == 0 && type.typeInfo() != null
               && "java.util.Optional".equals(type.typeInfo().fullyQualifiedName());
    }

    /*
     The elements of a varargs parameter are not stored content: the call builds the array from its arguments, so an
     argument nullable only through indirect evidence ('if (i == null || i < 0) throw illegalArgument("%s", i)')
     still makes them nullable. Asserting at the call instead ('i!!') would throw where Java formats "null"
     (langchain4j Exceptions.illegalArgument(format, vararg args: Any?)).
     */
    private static boolean isVarargElements(Object node) {
        return node instanceof Content c && c.of() instanceof ParameterInfo pi && pi.isVarArgs();
    }

    /*
     Kotlin: the slot of a type parameter bounded by something other than Object ('Range<C extends Comparable>',
     'EnumSet<E extends Enum<E>>') cannot hold a nullable argument, so null stops there as at a non-null contract; a
     write of a value that may be null is asserted (guava, worktree-maddi-all 2026-10-16: 'DiscreteDomain<C?>',
     'TreeRangeMap<K?, ...>', 'EnumSet<E?>', most of 129 "type argument is not within its bounds").
     */
    private boolean boundedSlot(Object node) {
        if (!policy.callResults() || !(node instanceof Arg a)) return false;
        Boolean known = boundedSlots.get(node);
        if (known != null) return known;
        ParameterizedType outer = typeOf(a.of());
        TypeInfo ti = outer == null || outer.arrays() > 0 ? null : outer.typeInfo();
        boolean bounded = !BOUNDED_SLOTS_OFF.get() && ti != null && a.index() < ti.typeParameters().size()
                          && ti.typeParameters().get(a.index()).typeBounds().stream()
                                  .anyMatch(b -> b.typeParameter() == null && b.typeInfo() != null
                                                 && !b.typeInfo().isJavaLangObject())
                          // a slot holding a type variable stays that variable ('Converter<A, B>', never
                          // 'Converter<A?, B>'): it already ranges over nullable instantiations, and Kotlin's
                          // invariant generics make the two incompatible (guava's Converter, Maps, TreeRangeSet, ...)
                          || !TYPE_VARIABLE_SLOTS_OFF.get() && outer != null && a.index() < outer.parameters().size()
                             && isTypeVariable(outer.parameters().get(a.index()))
                             && !typeVariableElementSlots.contains(a)
                          || erasedSingleton(a, outer)
                          || nonNullSlot(a)
                          || tiedToNonNullSlots().contains(a);
        boundedSlots.put(node, bounded);
        return bounded;
    }

    // the slot of a null-marked type parameter, or one holding a type variable the null-marked bounds hold non-null
    private boolean nonNullSlot(Arg a) {
        if (typeUses.nonNullTypeParameters.isEmpty()) return false;
        ParameterizedType outer = typeOf(a.of());
        if (outer == null || outer.arrays() > 0) return false;
        TypeInfo ti = outer.typeInfo();
        if (ti != null && a.index() < ti.typeParameters().size()
            && typeUses.nonNullTypeParameters.contains(ti.typeParameters().get(a.index()))) return true;
        if (a.index() >= outer.parameters().size()) return false;
        ParameterizedType p = outer.parameters().get(a.index());
        return p.typeParameter() != null && (p.wildcard() == null || !p.wildcard().isSuper())
               && typeUses.nonNullTypeParameters.contains(p.typeParameter());
    }

    /*
     Kotlin's generics are invariant, and an 'out' projection of a non-null bound is non-null too: a container given
     to a null-marked slot ('LinkedHashMultiset<Object> multiset' given to '<E : Any> copyOf(Iterable<out E>)')
     is bounded as well, so null stops there and its writes are asserted ('multiset.add(elements[i]!!, ...)').
     ImmutableMultiset.kt:182 had 'LinkedHashMultiset<Any?>' (2026-10-19). Not into a '? super' slot. Computed once,
     when the graph is complete. Gate NOTIEDBOUNDS.
     */
    private Set<Arg> tiedToNonNullSlots() {
        if (tiedToNonNull != null) return tiedToNonNull;
        tiedToNonNull = new HashSet<>();
        if (TIED_BOUNDS_OFF.get() || typeUses.nonNullTypeParameters.isEmpty()) return tiedToNonNull;
        List<String> why = TypeUses.NULL_MARKED_DEBUG == null ? null : new ArrayList<>();
        // the container itself given to a null-marked slot that the graph does not tie, a method type variable's:
        // 'copyOf(multiset)'. Not along the slot edges: those carry values ('accessQueue.add(table.get(i))'), which
        // Kotlin asserts at the write, and had made Segment.table's elements non-null through
        // ImmutableList.copyOf(accessQueue), where Segment.clear() writes null (guava, 2026-10-19)
        for (List<Object> flow : flows) {
            ParameterizedType t0 = typeOf(flow.get(0));
            ParameterizedType t1 = typeOf(flow.get(1));
            if (t0 == null || t1 == null || t0.arrays() > 0 || t1.arrays() > 0
                || t0.parameters().size() != t1.parameters().size() || fedByUnboundWildcard(flow.get(0))) continue;
            for (int i = 0; i < t0.parameters().size(); i++) {
                Arg from = new Arg(flow.get(0), i);
                if (nonNullSlot(new Arg(flow.get(1), i)) && tiedToNonNull.add(from) && why != null) {
                    why.add(from + " <- " + flow.get(1));
                }
            }
        }
        if (why != null) {
            try {
                java.nio.file.Files.write(java.nio.file.Path.of(TypeUses.NULL_MARKED_DEBUG + ".tied"), why);
            } catch (java.io.IOException ignored) {
            }
        }
        return tiedToNonNull;
    }

    /*
     a local made from a '?' argument ('List<Object> result = newArrayList(keys)' of an 'Iterable<? extends Object>'):
     Kotlin infers its initializer's elements as 'Any?' from the '*', so it cannot be declared non-null. The model
     does not tell '? extends Object' from '?' (Striped.kt:34, 2026-10-19)
     */
    private static boolean fedByUnboundWildcard(Object node) {
        if (!(node instanceof Local l) || !(l.declaration() instanceof LocalVariableCreation lvc)) return false;
        return lvc.localVariableStream().filter(lv -> lv.simpleName().equals(l.name()))
                .map(lv -> lv.assignmentExpression() == null ? null : NonNullFacts.unwrap(lv.assignmentExpression()))
                .anyMatch(init -> {
                    List<Expression> args = switch (init) {
                        case MethodCall mc -> mc.parameterExpressions();
                        case ConstructorCall cc -> cc.parameterExpressions();
                        case null, default -> List.of();
                    };
                    return args.stream().map(Expression::parameterizedType).anyMatch(t -> t != null
                            && t.parameters().stream().anyMatch(p -> p.wildcard() != null && p.wildcard().isUnbound()));
                });
    }

    private Set<Arg> tiedToNonNull;
    private static final Switches.Gate TIED_BOUNDS_OFF = Switches.gate("NOTIEDBOUNDS");

    /*
     Kotlin: a static final singleton with Object type arguments ('RegularImmutableMap.EMPTY', an
     ImmutableMap<Object, Object>) is cast to each instantiation at its use; its slots hold nothing. Tied to every
     Object-keyed map that the links see as it, it carried a test's null ('read from *' in
     ImmutableMapFloodingTest) into ImmutableListMultimap.readObject's tmpMap (guava 2026-10-17, after 594f20b7).
     */
    private static boolean erasedSingleton(Arg a, ParameterizedType outer) {
        return !ERASED_SINGLETONS_OFF.get() && a.of() instanceof FieldInfo fi && fi.isStatic() && fi.isFinal()
               && outer != null && outer.arrays() == 0 && !outer.parameters().isEmpty()
               && outer.parameters().stream().allMatch(p -> p.wildcard() == null && p.typeParameter() == null
                                                            && p.arrays() == 0 && p.typeInfo() != null
                                                            && p.typeInfo().isJavaLangObject());
    }

    // gate NOERASEDSINGLETONS: an Object-typed singleton's slots take null as any other (A/B)
    private static final Switches.Gate ERASED_SINGLETONS_OFF = Switches.gate("NOERASEDSINGLETONS");

    private final Map<Object, Boolean> boundedSlots = new HashMap<>();
    // slots holding a type variable that a 'T[]' of the same method fills (arrayElementsPassedThrough): 'List<T?>'
    // where the array is an 'Array<T?>'
    final Set<Arg> typeVariableElementSlots = new HashSet<>();
    // gate NOBOUNDEDSLOTS: a bounded type parameter's slot takes null as any other (A/B)
    private static final Switches.Gate BOUNDED_SLOTS_OFF = Switches.gate("NOBOUNDEDSLOTS");
    // gate NOWILDCARDSLOTS: a slot flows back out of an unbound '?' parameter (A/B)
    private static final Switches.Gate WILDCARD_SLOTS_OFF = Switches.gate("NOWILDCARDSLOTS");
    // gate NOUNCHECKEDSLOTS: slots of different concrete type arguments are tied as any other (A/B)
    private static final Switches.Gate UNCHECKED_SLOTS_OFF = Switches.gate("NOUNCHECKEDSLOTS");
    // gate NOTYPEVARSLOTS: a slot holding a type variable takes null as any other (A/B)
    private static final Switches.Gate TYPE_VARIABLE_SLOTS_OFF = Switches.gate("NOTYPEVARSLOTS");

    // strict: when not null, an edge from a value into a slot is followed only from a node in it
    Set<Object> closure(Map<Object, Object> cause, Set<Object> seeds, Set<Object> strict) {
        // a seed on a non-null contract is the caller's error (an M5 finding), not a source of null
        Set<Object> reached = new LinkedHashSet<>(seeds);
        reached.removeAll(contracts.nonNullContracts);
        reached.removeIf(this::optionalValue);
        reached.removeIf(this::boundedSlot);
        Deque<Object> queue = new ArrayDeque<>(reached);
        while (true) {
            while (!queue.isEmpty()) {
                Object n = queue.removeFirst();
                for (Object s : successors.getOrDefault(n, Set.of())) {
                    if (strict != null && isSlot(s) && !isSlot(n) && !strict.contains(n) && !isVarargElements(s)) continue;
                    if (optionalValue(s) || boundedSlot(s)) continue;
                    if (!contracts.nonNullContracts.contains(s) && reached.add(s)) {
                        cause.put(s, n);
                        queue.addLast(s);
                    }
                }
            }
            // gap 9: the gated call results, once nothing else moves; monotone, so repeated until none opens
            Map<MethodInfo, Boolean> intrinsic = new HashMap<>();
            for (GatedSite site : gatedSites) {
                if (!reached.contains(site.callee()) || reached.contains(site.target())
                    || contracts.nonNullContracts.contains(site.target())
                    || !callResultNullable(site.callee(), site.call(), reached, reached::contains, intrinsic)) continue;
                reached.add(site.target());
                cause.put(site.target(), site.callee());
                queue.addLast(site.target());
            }
            if (queue.isEmpty()) return reached;
        }
    }

    /*
     Gap 9. Whether the result of call 'call' to 'callee' may be null, the callee's return being reached: by itself
     (reached without passing through one of its own parameters), or through a parameter whose argument at this call is
     (its SiteArg reached). SearchableProperties.getInteger(key, defaultValue) is 'search(key).orElse(defaultValue)', and
     getInteger(key) calls getInteger(key, null): only that call's result is nullable, not 'getInteger(KEY, 5)'.
     */
    boolean callResultNullable(MethodInfo callee, int call, Set<Object> reached,
                               java.util.function.Predicate<Object> isReached,
                               Map<MethodInfo, Boolean> intrinsicMemo) {
        if (GAP9_OFF.get() || !argumentsSeen.contains(call)) return true;
        Set<ParameterInfo> through = passThrough(callee);
        if (through.isEmpty()) return true;
        if (intrinsicMemo.computeIfAbsent(callee, m -> reachedWithout(m, through, reached))) return true;
        for (ParameterInfo pi : through) {
            if (isReached.test(new SiteArg(call, pi.index()))) return true;
        }
        return false;
    }

    /* whether null reaches 'callee' backwards from a seed, without passing through one of 'through' */
    private boolean reachedWithout(MethodInfo callee, Set<ParameterInfo> through, Set<Object> reached) {
        Map<Object, Set<Object>> pred = predecessors();
        Set<Object> seen = new HashSet<>();
        Deque<Object> todo = new ArrayDeque<>(List.of(callee));
        while (!todo.isEmpty()) {
            Object n = todo.removeFirst();
            if (!seen.add(n) || through.contains(n) || !reached.contains(n)) continue;
            if (seeds.seedOrigin.containsKey(n)) return true;
            todo.addAll(pred.getOrDefault(n, Set.of()));
        }
        return false;
    }

    /*
     Arrays are invariant in what they hold (Kotlin's Array<T>; Java's covariance is a store check at run time, not a
     licence): wherever an array flows, both ends denote the same objects, so their elements are one slot. Every
     flow between two array nodes, also one the top-level verdict drops because the array is known non-null there,
     ties their Contents both ways, recursively for nested arrays.
     */
    /*
     Kotlin: an array passed where an array of a wider element type is expected ('asImmutableList(entryArray)' with
     an ImmutableEntry<E>[] for guava's 'asImmutableList(@Nullable Object[] elements)') is cast by the printer
     ('entryArray as Array<Any?>'): the two are no one Array<T>. Elements flow on from the narrower array into the
     wider one, but not back, unless the wider one is written through. Without this, the parameter's nullable
     elements made RegularImmutableMultiset.create's entryArray, and with it entries, getEntry and
     IndexedImmutableSet.get, nullable (worktree-maddi-all, 2026-10-27). True when 'to' holds a proper subtype of what
     'from' holds. Gate NOARRAYWIDENING.
     */
    private boolean narrowedArray(ParameterizedType from, ParameterizedType to) {
        if (!policy.callResults() || ARRAY_WIDENING_OFF.get() || from.arrays() != to.arrays()) return false;
        TypeInfo f = from.copyWithoutArrays().typeInfo();
        TypeInfo t = to.copyWithoutArrays().typeInfo();
        if (f == null || t == null || f == t || t.isJavaLangObject()) return false;
        return f.isJavaLangObject() || t.superTypesExcludingJavaLangObject().contains(f);
    }

    // gate NOARRAYWIDENING: a wider array parameter's element nulls flow back into the argument (A/B)
    private static final Switches.Gate ARRAY_WIDENING_OFF = Switches.gate("NOARRAYWIDENING");

    void coupleContent() {
        Set<Object> writtenThrough = writtenThrough(true);
        // narrowedArray: an element annotation alone writes nothing into the caller's array
        Set<Object> written = policy.callResults() && !ARRAY_WIDENING_OFF.get() ? writtenThrough(false) : writtenThrough;
        Set<ParameterInfo> readOnlyArrays = readOnlyArrayParameters();
        Deque<List<Object>> queue = new ArrayDeque<>(flows);
        Set<List<Object>> seen = new HashSet<>();
        while (!queue.isEmpty()) {
            List<Object> flow = queue.removeFirst();
            if (!seen.add(flow)) continue;
            // not through a library method: its parameter would tie together the arrays of all its callers
            if (index.library(flow.get(0)) || index.library(flow.get(1))) continue;
            ParameterizedType t0 = typeOf(flow.get(0));
            ParameterizedType t1 = typeOf(flow.get(1));
            if (t0 == null || t1 == null) continue;
            if (t0.arrays() > 0 && t1.arrays() > 0) {
                Content c0 = new Content(flow.get(0));
                Content c1 = new Content(flow.get(1));
                // the links run both ways: 'join(upperBounds)' also gives a flow from join's 'parts' to the caller's
                // array. A read-only parameter flows on only into another or a library's (readOnlyArrayParameters);
                // anything else is that reversed link
                if ((!readOnlyArrayParameter(flow.get(0), readOnlyArrays)
                     || readOnlyArrayParameter(flow.get(1), readOnlyArrays))
                    && !varargsPasses.contains(List.of(flow.get(1), flow.get(0)))
                    && !(narrowedArray(t0, t1) && !written.contains(flow.get(0)))) {
                    successors.computeIfAbsent(c0, _ -> new LinkedHashSet<>()).add(c1);
                    if (trace.why(c1.of())) trace.log("coupled forward", c1.of(), "from " + Trace.name(c0.of()) + " " + t0 + " -> " + t1);
                }
                if (!readOnlyArrayParameter(flow.get(1), readOnlyArrays) && !varargsPasses.contains(flow)
                    && (narrowedArray(t1, t0) ? written.contains(flow.get(1))
                        : policy.assertContentWrites() || writtenThrough.contains(flow.get(1)))) {
                    successors.computeIfAbsent(c1, _ -> new LinkedHashSet<>()).add(c0);
                    if (trace.why(c0.of())) trace.log("coupled back", c0.of(), "from " + Trace.name(c1.of()) + " " + t1 + " -> " + t0);
                }
                queue.add(List.of(c0, c1));
            } else if (t0.arrays() == 0 && t1.arrays() == 0 && !t0.parameters().isEmpty()
                       && t0.parameters().size() == t1.parameters().size()) {
                // generic types are invariant too (List<String?> is not a List<String>), except into a
                // '? extends' argument, which only receives; matched by position (ArrayList<E> -> List<E>)
                for (int i = 0; i < t0.parameters().size(); i++) {
                    ParameterizedType p0 = t0.parameters().get(i);
                    ParameterizedType p1 = t1.parameters().get(i);
                    // not through a generic method's own type variable: each call instantiates it anew
                    // ('ImmutableMap.copyOf(Map<? extends K, ? extends V>)' would tie the maps of all its callers)
                    if (isMethodTypeVariable(p0) || isMethodTypeVariable(p1)) continue;
                    // Kotlin: nor between two different concrete arguments, which only an unchecked conversion
                    // relates ('(ImmutableMap<K, V>) RegularImmutableMap.EMPTY', an ImmutableMap<Object, Object> the
                    // links see as every caller's map): each conversion starts an instantiation of its own
                    if (policy.callResults() && !UNCHECKED_SLOTS_OFF.get() && uncheckedConversion(p0, p1)) continue;
                    // A slot typed by a CLASS type variable ('FastFixedSet<E> spawnEmptySet()' in Factory<E>,
                    // 'union(FastFixedSet<E> set)', 'Factory(Collection<E> set)') is parametric: it receives nothing
                    // from one instantiation's slot ('FastFixedSet<Statement?> tmpSet'), or Kotlin would see 'E?'
                    // inside the class (fernflower FastFixedSetFactory.kt:19/33). What the class itself puts into
                    // such a slot reaches every instantiation, so that direction stays. The instantiation's null
                    // belongs to the receiver's slot: instantiatedSlots ties it there.
                    boolean class0 = isClassTypeVariable(p0);
                    boolean class1 = isClassTypeVariable(p1);
                    Arg a0 = new Arg(flow.get(0), i);
                    Arg a1 = new Arg(flow.get(1), i);
                    if (!class1 || class0) successors.computeIfAbsent(a0, _ -> new LinkedHashSet<>()).add(a1);
                    // Kotlin: nor out of an unbound '?' ('Joiner.MapJoiner.join(Map<?, ?>)'): Kotlin's '*' only
                    // receives too. Back out, JoinerTest's null reached every map passed to join, and through
                    // ImmutableMap.of()'s RegularImmutableMap.EMPTY all of guava's empty maps (2026-10-17)
                    boolean unbound = policy.callResults() && !WILDCARD_SLOTS_OFF.get() && p1.wildcard() != null
                                      && p1.wildcard().isUnbound();
                    // Kotlin: nor out of a position declared nullable ('Predicate<@Nullable Object> equalTo1 =
                    // Equivalence.equals().equivalentTo("1")'): it is 'T?' whatever flows in, and its null is not the
                    // source's (EquivalenceTest's 'equalTo1.apply(null)' made Equivalence.equals() an
                    // Equivalence<Any?>, Equivalence.kt:98/99, 2026-10-18)
                    boolean declaredNullable = policy.callResults() && !NULLABLE_PARAMETER_SLOTS_OFF.get()
                                               && explicit(p1.annotations()) == NullableState.NULLABLE;
                    boolean read = viewLoopReads.contains(flow);
                    if (!isExtendsWildcard(p1) && !unbound && !declaredNullable && (!class0 || class1) && !read) {
                        successors.computeIfAbsent(a1, _ -> new LinkedHashSet<>()).add(a0);
                    }
                    if (read) viewLoopReads.add(List.of(a0, a1));
                    queue.add(List.of(a0, a1));
                }
            }
        }
    }

    /*
     Kotlin: an array parameter the method only reads prints as 'Array<out T>' (the printer, maddi cd8372ee4), which
     takes the caller's Array<Type> as it is: its elements need not flow back. Joiner.join(@Nullable Object[] parts)
     had pushed its '?' into every array joined, TypeResolver's upperBounds and from there resolveTypes' result and
     TypeToken (guava kotlinc-97a.log, 2026-10-17). "Only reads", as the printer decides it, from the source: the
     method assigns no element ('parts[i] = ...'; not writtenThrough, which also counts the elements a caller puts
     into 'join(new Object[]{x, y})', nor the unmodified verdict, absent where the modification analysis did not
     run); no lambda or anonymous class captures the array (AbstractCompositeHashFunction's fromHashers hands it on
     from there); and the array flows on only into other such parameters (cycles allowed) or a library's: not
     returned, assigned, or held. Not a varargs parameter, nor one of an override or an overridden method.
     Gate NOREADONLYARRAYS.
     */
    private Set<ParameterInfo> readOnlyArrayParameters() {
        if (READ_ONLY_ARRAYS_OFF.get() || !policy.callResults()) return Set.of();
        Set<ParameterInfo> candidates = new HashSet<>();
        Map<ParameterInfo, Set<Object>> out = new HashMap<>();
        for (List<Object> flow : flows) {
            if (flow.get(0) instanceof ParameterInfo pi) out.computeIfAbsent(pi, _ -> new HashSet<>()).add(flow.get(1));
        }
        for (MethodInfo mi : index.analysed) {
            if (!overrides(mi).isEmpty() || index.implementations.containsKey(mi)) continue;
            for (ParameterInfo pi : mi.parameters()) {
                ParameterizedType t = pi.parameterizedType();
                if (t.arrays() == 1 && !pi.isVarArgs() && !t.copyWithoutArrays().isPrimitiveExcludingVoid()
                    && !writesOrCaptures(mi, pi)) {
                    candidates.add(pi);
                }
            }
        }
        boolean changed = true;
        while (changed) {
            changed = false;
            for (java.util.Iterator<ParameterInfo> it = candidates.iterator(); it.hasNext(); ) {
                ParameterInfo pi = it.next();
                for (Object to : out.getOrDefault(pi, Set.of())) {
                    if (to instanceof ParameterInfo target && (candidates.contains(target) || index.library(target))) continue;
                    it.remove();
                    changed = true;
                    break;
                }
            }
        }
        return candidates;
    }

    // does the method's body assign an element of the array parameter, or capture it in a lambda or anonymous class
    private static boolean writesOrCaptures(MethodInfo mi, ParameterInfo pi) {
        if (mi.methodBody() == null) return true;
        boolean[] found = {false};
        mi.methodBody().visit(e -> {
            if (found[0]) return false;
            if (e instanceof Assignment a && a.variableTarget() instanceof DependentVariable dv
                && pi.equals(dv.arrayVariable())) {
                found[0] = true;
            } else if (e instanceof Lambda lambda) {
                if (lambda.methodBody() != null && mentionsParameter(lambda.methodBody(), pi)) found[0] = true;
                return false;
            } else if (e instanceof ConstructorCall cc && cc.anonymousClass() != null) {
                if (cc.parameterExpressions().stream().anyMatch(x -> mentionsParameter(x, pi))) found[0] = true;
                for (MethodInfo m : cc.anonymousClass().methods()) {
                    if (m.methodBody() != null && mentionsParameter(m.methodBody(), pi)) found[0] = true;
                }
                return false;
            }
            return true;
        });
        return found[0];
    }

    private static boolean mentionsParameter(Element e, ParameterInfo pi) {
        boolean[] found = {false};
        e.visit(x -> {
            if (x instanceof VariableExpression ve && pi.equals(ve.variable())) found[0] = true;
            return !found[0];
        });
        return found[0];
    }

    private static boolean readOnlyArrayParameter(Object node, Set<ParameterInfo> readOnlyArrays) {
        Object root = node;
        while (root instanceof Content c) root = c.of();
        return root instanceof ParameterInfo pi && readOnlyArrays.contains(pi);
    }

    private static final Switches.Gate READ_ONLY_ARRAYS_OFF = Switches.gate("NOREADONLYARRAYS");

    /*
     For Java annotations an array's elements need to flow only forward: Java arrays are covariant, and so is
     JSpecify. Backward only where the downstream array is written through ('void f(Object[] a) { a[0] = null; }'
     puts the null in the caller's array), directly or via another array it flows on into. Kotlin's Array<T> is
     invariant, so under Policy.assertContentWrites both ways regardless. Guava 2026-10-07: coupling both ways made
     ImmutableMap.Builder.entries, ImmutableList.array, Joiner.join(Object[]) and TypeToken's Type[] one slot.
     */
    private Set<Object> writtenThrough(boolean annotations) {
        // 'annotations': whether an element annotation counts ('join(@Nullable Object[] parts)'); not for narrowedArray
        Set<Object> written = new HashSet<>();
        // NULLWHY: why a node is written, its downstream node or the write itself
        Map<Object, String> because = !trace.enabled() ? null : new HashMap<>();
        successors.forEach((from, tos) -> {
            // narrowedArray: nor is an element "written" from a lifted field ('RegularImmutableList.array ∋
            // RegularImmutableList.element'), the links' stand-in for any element, not code writing into the array
            if (from instanceof Content || !annotations && liftedField(from)) return;
            for (Object to : tos) {
                if (to instanceof Content c && written.add(c.of()) && because != null) {
                    because.put(c.of(), "written from " + Trace.name(from));
                }
            }
        });
        seeds.seedOrigin.forEach((node, origin) -> {
            if (node instanceof Content c && !origin.startsWith("array created") && !origin.startsWith("null in an array initializer")
                && (annotations || !origin.startsWith("annotated"))) {
                if (written.add(c.of()) && because != null) because.put(c.of(), "seed " + origin);
            }
        });
        Map<Object, List<Object>> upstream = new HashMap<>();
        for (List<Object> flow : flows) upstream.computeIfAbsent(flow.get(1), _ -> new ArrayList<>()).add(flow.get(0));
        Deque<Object> queue = new ArrayDeque<>(written);
        while (!queue.isEmpty()) {
            Object down = queue.removeFirst();
            if (!annotations && (liftedField(down) || index.library(down))) {
                if (because != null && trace.why(down)) trace.print("NULLWHY written(false) stops at " + Trace.name(down));
                continue;
            }
            for (Object up : upstream.getOrDefault(down, List.of())) {
                // narrowedArray: an array is written through arrays only, not through a type argument's slot, a
                // return value or a call's argument that happens to link to it
                if (!annotations && !LIFTED_WRITES_OFF.get() && !(isArray(up) && isArray(down))) continue;
                if (written.add(up)) {
                    queue.add(up);
                    if (because != null) because.put(up, "flows into " + Trace.name(down));
                }
            }
        }
        // narrowedArray: nor is the lifted field itself written, nor does it pass its elements on as if it were
        if (!annotations) written.removeIf(NullGraph::liftedField);
        if (because != null) {
            for (Object node : written) {
                if (!trace.why(node)) continue;
                StringBuilder sb = new StringBuilder("NULLWHY written(" + annotations + ") " + Trace.name(node)
                        + (liftedField(node) ? " (lifted)" : ""));
                Object n = node;
                for (int i = 0; i < 40 && because.get(n) != null; i++) {
                    String b = because.get(n);
                    sb.append("\n    ").append(b);
                    if (!b.startsWith("flows into ")) break;
                    n = written.stream().filter(w -> Trace.name(w).equals(b.substring(11))).findFirst().orElse(null);
                    if (n == null) break;
                }
                trace.print(sb);
            }
        }
        return written;
    }

    /*
     Kotlin, for narrowedArray: a write booked on a field its owner does not declare does not travel upstream to what
     flows into it. Such a field is the link engine's lift of a subtype's field onto a supertype
     (VariableTranslationAllowHierarchy): 'ImmutableList.array' for RegularImmutableList.array, the content of every
     list of the type. So 'ImmutableList.of(e1, ..)' filling its own array before that became a list would count as a
     write into every array passed in, guava's 'asImmutableList(@Nullable Object[], int)' included: it made
     RegularImmutableMultiset.create's entryArray, and with it entries and IndexedImmutableSet.get, nullable
     (CodeLaser/maddi-mod#74). For the same reason the lifted field is not written itself, and its elements, linked
     into a narrower array, are not that array's (liftedIntoNarrower): they are what the other arrays of the type
     hold. Nor does a write travel upstream out of a library method's parameter, which ties together the arrays of
     all its callers (as in coupleContent), or through anything but arrays. The declared field, written through,
     still counts. Gate NOLIFTEDWRITES.
     */
    private static boolean liftedField(Object node) {
        Object root = node;
        while (root instanceof Content c) root = c.of(); // also its elements, at any depth
        return root instanceof FieldInfo fi && !LIFTED_WRITES_OFF.get() && fi.owner() != null
               && !fi.owner().fields().contains(fi);
    }

    private boolean isArray(Object node) {
        if (node instanceof Content || node instanceof Arg) return false;
        ParameterizedType t = typeOf(node);
        return t != null && t.arrays() > 0;
    }

    // the elements of a lifted field (liftedField) linked into a narrower array: what other arrays of the type hold
    boolean liftedIntoNarrower(Object from, Object to) {
        if (!(from instanceof Content cf) || !(to instanceof Content ct) || !liftedField(cf)) return false;
        ParameterizedType tf = typeOf(cf.of());
        ParameterizedType tt = typeOf(ct.of());
        return tf != null && tt != null && narrowedArray(tf, tt);
    }

    private static final Switches.Gate LIFTED_WRITES_OFF = Switches.gate("NOLIFTEDWRITES");

    /*
     Kotlin: two generic types that differ in a concrete type argument at some depth are not one object, whatever
     the links say. fernflower's 'class_mapping = mapping.computeIfAbsent(className, k -> new LinkedHashMap<>())' has
     the link class_mapping ← mapping: a Map<String, Map<String, Map<Integer, Integer>>> into a Map<String,
     Map<Integer, Integer>>. Matched by position, mapping's key slot (a nullable className) became class_mapping's,
     and through the view loops of dumpMapping lstBytecodeOffsets a List<Int?> that Collections.sort rejects
     (BytecodeSourceMapper.kt:40, worktree-maddi-all 2026-10-23). Link edges between such nodes, or between their slots
     at the same depth, are dropped. uncheckedConversion skips only the one position that differs. Gate NODIFFERENTSHAPES.
     */
    boolean differentShapes(Object from, Object to) {
        while (from instanceof Arg(Object f, int _) && to instanceof Arg(Object t, int _)) {
            from = f;
            to = t;
        }
        ParameterizedType t0 = typeOf(from);
        ParameterizedType t1 = typeOf(to);
        return t0 != null && t1 != null && t0.arrays() == 0 && t1.arrays() == 0 && t0.typeInfo() != null
               && t0.typeInfo() == t1.typeInfo() && differentShapes(t0, t1);
    }

    static boolean differentShapes(ParameterizedType t0, ParameterizedType t1) {
        for (int i = 0; i < t0.parameters().size() && i < t1.parameters().size(); i++) {
            ParameterizedType p0 = t0.parameters().get(i);
            ParameterizedType p1 = t1.parameters().get(i);
            if (uncheckedConversion(p0, p1)) return true;
            if (p0.wildcard() == null && p1.wildcard() == null && p0.typeParameter() == null
                && p1.typeParameter() == null && p0.typeInfo() != null && p0.typeInfo() == p1.typeInfo()
                && p0.arrays() == p1.arrays() && differentShapes(p0, p1)) return true;
        }
        return false;
    }

    static final Switches.Gate DIFFERENT_SHAPES_OFF = Switches.gate("NODIFFERENTSHAPES");

    private static boolean uncheckedConversion(ParameterizedType p0, ParameterizedType p1) {
        return p0.wildcard() == null && p1.wildcard() == null && p0.typeParameter() == null
               && p1.typeParameter() == null && p0.typeInfo() != null && p1.typeInfo() != null
               && p0.arrays() == p1.arrays() && p0.typeInfo() != p1.typeInfo();
    }

    Keyed keyed(Arg slot, Object key) {
        keysUsed.computeIfAbsent(slot, _ -> new LinkedHashSet<>()).add(key == null ? NO_KEY : key);
        return new Keyed(slot, key);
    }

    static final Object NO_KEY = new Object();

    // gate NONULLABLEPARAMETERSLOTS: a null into a parameter declared nullable writes the receiver's slot too (A/B)
    static final Switches.Gate NULLABLE_PARAMETER_SLOTS_OFF = Switches.gate("NONULLABLEPARAMETERSLOTS");
}
