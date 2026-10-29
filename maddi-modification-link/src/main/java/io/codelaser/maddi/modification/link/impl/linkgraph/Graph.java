package io.codelaser.maddi.modification.link.impl.linkgraph;

import io.codelaser.maddi.modification.link.impl.Gate;
import io.codelaser.maddi.modification.link.impl.LinkNatureImpl;
import io.codelaser.maddi.modification.link.impl.graph.Fact;
import io.codelaser.maddi.modification.link.impl.graph.IncrementalFixpointEngine;
import io.codelaser.maddi.modification.link.impl.graph.LabeledGraph;
import io.codelaser.maddi.modification.prepwork.variable.ReturnVariable;
import io.codelaser.maddi.modification.link.impl.localvar.IntermediateVariable;
import io.codelaser.maddi.modification.link.impl.localvar.MarkerVariable;
import io.codelaser.maddi.modification.link.impl.localvar.SharedVariable;
import io.codelaser.maddi.modification.link.impl.translate.VariableTranslationMap;
import io.codelaser.maddi.modification.prepwork.Util;
import io.codelaser.maddi.modification.prepwork.variable.Link;
import io.codelaser.maddi.modification.prepwork.variable.LinkNature;
import io.codelaser.maddi.modification.prepwork.variable.impl.LinksImpl;
import io.codelaser.maddi.cst.api.runtime.Runtime;
import io.codelaser.maddi.cst.api.variable.DependentVariable;
import io.codelaser.maddi.cst.api.variable.FieldReference;
import io.codelaser.maddi.cst.api.variable.This;
import io.codelaser.maddi.cst.api.variable.Variable;

import java.util.*;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static io.codelaser.maddi.modification.link.impl.LinkNatureImpl.CONTAINS_AS_FIELD;

public class Graph {
    private final Runtime runtime;
    private IncrementalFixpointEngine<Variable, LinkNature> engine;
    private VirtualModificationIdenticals virtualModificationIdenticals = new VirtualModificationIdenticals();
    private final SharedVariables sharedVariables;
    private final LabeledGraph.VertexListener<Variable> vertexListener = new LabeledGraph.VertexListener<>() {
        @Override
        public void vertexAdded(Variable v) {
            indexVertex(v);
        }

        @Override
        public void vertexRemoved(Variable v) {
            unindexVertex(v);
        }
    };

    public Graph(Runtime runtime, IncrementalFixpointEngine<Variable, LinkNature> engine) {
        this.engine = engine;
        this.sharedVariables = new SharedVariables(runtime);
        this.runtime = runtime;
        // the engine's graph is still empty here, so the index sees every vertex from the start
        engine.setVertexListener(vertexListener);
    }

    /*
     Everything the graph knows at one point of the method, detached from this object. A statement with
     alternative sub-blocks (if/else, switch cases, a loop body that may not run) links each alternative from its
     own copy of the state before the statement, so that one alternative's strong update (an assignment erases
     what was known about its target) cannot destroy what a sibling alternative established. The Graph object
     itself stays: FollowGraph, MakeGraph and LinkGraph hold it, so a state is installed in place (restore).
     Caches and dirt tracking are not part of a state; restore drops the former and declares the latter global.
     */
    public record State(IncrementalFixpointEngine<Variable, LinkNature> engine,
                        VirtualModificationIdenticals virtualModificationIdenticals,
                        SharedVariables.State sharedVariables,
                        Map<Variable, Set<Variable>> verticesByScopePart,
                        Set<Variable> verticesWithRepInScope,
                        Set<Variable> verticesWithReturnPrimary,
                        Set<Variable> freshObjectReturns,
                        Map<Variable, Set<Variable>> patternBindings,
                        Set<Set<Variable>> mediatedPairs,
                        Map<List<Object>, LinkNature> directEdges) {
        public State copy() {
            return copyWith(sharedVariables.copy());
        }

        // a copy of everything but the groups, which the caller has copied already
        private State copyWith(SharedVariables.State sharedVariablesCopy) {
            Map<Variable, Set<Variable>> byScopePart = new HashMap<>();
            verticesByScopePart.forEach((k, v) -> byScopePart.put(k, new LinkedHashSet<>(v)));
            Map<Variable, Set<Variable>> bindings = new HashMap<>();
            patternBindings.forEach((k, v) -> bindings.put(k, new HashSet<>(v)));
            return new State(engine.copy(), virtualModificationIdenticals.copy(), sharedVariablesCopy, byScopePart, new LinkedHashSet<>(verticesWithRepInScope),
                    new LinkedHashSet<>(verticesWithReturnPrimary), new HashSet<>(freshObjectReturns), bindings,
                    new HashSet<>(mediatedPairs), new HashMap<>(directEdges));
        }
    }

    /** An independent copy of the current state. */
    public State snapshot() {
        return new State(engine, virtualModificationIdenticals, null, verticesByScopePart, verticesWithRepInScope,
                verticesWithReturnPrimary, freshObjectReturns, patternBindings, mediatedPairs, directEdges)
                .copyWith(sharedVariables.snapshot());
    }

    /**
     * The current state itself, not a copy, for a join: this graph must not be used again before the next
     * restore or join.
     */
    public State detach() {
        return new State(engine, virtualModificationIdenticals, sharedVariables.detach(), verticesByScopePart,
                verticesWithRepInScope, verticesWithReturnPrimary, freshObjectReturns, patternBindings,
                mediatedPairs, directEdges);
    }

    /** Install a state; it is owned by this graph from now on (pass a copy to keep using it). */
    public void restore(State state) {
        engine = state.engine();
        engine.setVertexListener(vertexListener);
        virtualModificationIdenticals = state.virtualModificationIdenticals();
        sharedVariables.restore(state.sharedVariables());
        verticesByScopePart = state.verticesByScopePart();
        verticesWithRepInScope = state.verticesWithRepInScope();
        verticesWithReturnPrimary = state.verticesWithReturnPrimary();
        freshObjectReturns = state.freshObjectReturns();
        patternBindings = state.patternBindings();
        mediatedPairs = state.mediatedPairs();
        directEdges = state.directEdges();
        clearRepExpansionCache();
        touchedVariables.clear();
        dirtyEverything = true;
    }

    /*
     The JOIN of the alternatives of a statement (each linked from its own copy of the state before it): their
     union. The engines are united without re-closing (IncrementalFixpointEngine.unionWith); the assignment groups
     keep only the memberships all alternatives agree on (SharedVariables.joinAll), and an evicted member is
     linked to each alternative's rep by a plain edge. The alternatives are owned by this graph from now on.
     */
    public void join(List<State> alternatives, String statementIndex) {
        SharedVariables.JoinPlan plan = sharedVariables.planJoin(alternatives.stream()
                .map(State::sharedVariables).toList());
        List<State> linked = new java.util.ArrayList<>(alternatives.size());
        for (int i = 0; i < alternatives.size(); i++) {
            List<SharedVariables.JoinEdge> edges = plan.edgesPerAlternative().get(i);
            if (edges.isEmpty()) {
                linked.add(alternatives.get(i));
            } else {
                restore(alternatives.get(i));
                for (SharedVariables.JoinEdge edge : edges) {
                    engine.addSymmetricEdge(edge.from(), edge.to(), LinkNatureImpl.IS_ASSIGNED_FROM, statementIndex);
                    mirrorFaces(edge.from(), edge.to(), statementIndex);
                }
                linked.add(detach());
            }
        }
        restore(linked.getFirst());
        for (State other : linked.subList(1, linked.size())) {
            engine.unionWith(other.engine());
            virtualModificationIdenticals.unionWith(other.virtualModificationIdenticals());
            freshObjectReturns.addAll(other.freshObjectReturns());
            other.patternBindings().forEach((k, v) -> patternBindings.computeIfAbsent(k, _ -> new HashSet<>())
                    .addAll(v));
            mediatedPairs.addAll(other.mediatedPairs());
            directEdges.putAll(other.directEdges());
        }
        sharedVariables.restore(plan.joined());
        clearRepExpansionCache();
        dirtyEverything = true;
    }

    /*
     A member of a group shares the group's field faces ('h.value' IS the rep's 'value'); the join edge that replaces
     an evicted membership ('h ← rep', or 'rep ← h') does not carry them. So, inside the alternative, the member's
     faces -- graph vertices, or members of their own group -- are copied edge by edge onto the rep ('rep.f ← h.f').
     Without them, 'h = new H(v)' in one branch and 'return h' after the statement lost 'add.value ← 0:v' (timefold's
     ToMapPerKeyCounter.add). Not the other way round: after the join the member's name also stands for what it held
     in the other alternatives -- for a reassigned parameter, the caller's object -- so the rep's faces copied onto
     it ('broker.b ← rep.b') read as facts about THAT object's fields (activemq's BrokerService.addInterceptors,
     where 'plugins' then lost its modification).
     */
    private void mirrorFaces(Variable from, Variable to, String statementIndex) {
        if (from instanceof SharedVariable) mirrorFacesOf(to, from, statementIndex);
        else if (to instanceof SharedVariable) mirrorFacesOf(from, to, statementIndex);
    }

    // for each face 'owner.f' (a vertex, or a group member), add 'other.f ← vertex of owner.f'
    private void mirrorFacesOf(Variable owner, Variable other, String statementIndex) {
        Set<Variable> faces = new LinkedHashSet<>();
        Set<Variable> vertices = verticesByScopePart.get(owner);
        if (vertices != null) faces.addAll(vertices);
        faces.addAll(sharedVariables.membersRootedAt(owner));
        if (faces.isEmpty()) return;
        VariableTranslationMap vtm = new VariableTranslationMap(runtime).put(owner, other);
        for (Variable face : List.copyOf(faces)) {
            if (face.equals(owner)) continue;
            Variable mirrored = vtm.translateVariableRecursively(face);
            if (mirrored == null || mirrored.equals(face)) continue;
            Variable vertex = sharedVariables.translateForward(face);
            if (vertex == null || vertex.equals(mirrored)) continue;
            engine.addSymmetricEdge(mirrored, vertex, LinkNatureImpl.IS_ASSIGNED_FROM, statementIndex);
        }
    }

    public Set<SharedVariable> detachedReps(Variable variable) {
        return sharedVariables.detachedReps(variable);
    }

    // see SharedVariables.detachedAliases
    public Set<Variable> detachedAliases(Variable variable) {
        return sharedVariables.detachedAliases(variable);
    }

    // the variables a statement assigns: see SharedVariables.dropDetached
    public void dropDetached(Set<Variable> assigned) {
        assigned.forEach(sharedVariables::dropDetached);
    }

    /*
     The variables a statement assigns that are a source of their group leave it BEFORE any of the statement's links
     go in (CodeLaser/maddi-mod#77, see mergeEdgeBi). Done at the assignment link alone, the store
     'hashTable[b] = newEntry' first added 'hashTable[b] ∈ hashTable' onto the OLD group (bucketHead's), and the new
     entry never became an element: 'entries ~ table' was lost.
     */
    public void evictReassignedSources(Set<Variable> assigned, String statementIndex) {
        if (NO_SRC_EVICT) return;
        for (Variable v : assigned) {
            if (sharedVariables.isKnown(v) && sharedVariables.isSourceAtOtherStatement(v, statementIndex)
                && !sharedVariables.isReassignment(v, statementIndex)
                && !(v instanceof io.codelaser.maddi.cst.api.info.ParameterInfo)
                && !sharedVariables.groupHoldsReturnValue(v)) {
                clearRepExpansionCache();
                touchVariable(v);
                Variable rep = sharedVariables.translateForward(v);
                if (rep != v) touchVariable(rep);
                touchAll(sharedVariables.allShared(v));
                sharedVariables.evictSource(v);
            }
        }
    }

    /*
     An assigned variable's faces (its fields and elements, 'r.element') that are members of a group describe its OLD
     object. They are no graph vertices, so the removal of every vertex rooted at the variable (LinkGraph.compute)
     missed them, and 'r = new Singleton<>(a[0]); r = new Regular<>(b); return r' read 'return.element ← 0:a[0]'.
     guava's ImmutableList.copyOf(E[]) switches between of(elements[0]) and construct(..): its summary had the
     list's element flow INTO the argument's, and Invokable.getParameters' annotations elements turned nullable
     (CodeLaser/maddi-mod#72 4a). As for the variable itself (mergeEdgeBi): a face that is a source at another
     statement is evicted, its records stay; any other face leaves its group. Gate NOFACEDROP.
     */
    private static final boolean NO_FACE_DROP = Gate.isSet("NOFACEDROP");

    public void dropReassignedFaces(Set<Variable> assigned, String statementIndex) {
        if (NO_FACE_DROP) return;
        for (Variable v : assigned) {
            for (Variable face : List.copyOf(sharedVariables.membersRootedAt(v))) {
                if (face.equals(v)) continue;
                clearRepExpansionCache();
                touchVariable(face);
                Variable rep = sharedVariables.translateForward(face);
                if (rep != face) touchVariable(rep);
                touchAll(sharedVariables.allShared(face));
                if (sharedVariables.isSourceAtOtherStatement(face, statementIndex)
                    && !sharedVariables.groupHoldsReturnValue(face)) {
                    sharedVariables.evictSource(face);
                } else {
                    sharedVariables.remove(face);
                }
            }
        }
    }

    public void enterAlternatives() {
        sharedVariables.enterAlternatives();
    }

    public void leaveAlternatives() {
        sharedVariables.leaveAlternatives();
    }

    // set by restore: every variable's next extraction may differ from its previous one
    private boolean dirtyEverything;

    /*
    Vertex indexes, maintained through the engine's vertex listener. They replace full vertex scans that ran
    inside per-statement/per-variable loops — the dominant cost on long straight-line methods (review
    2026-08-05, remedy C):
    - verticesByScopePart: scope-chain member -> vertices whose variableAndScopes contain it. Answers
      isKnownInGraph (mergeEdgeBi's collapse, clear's descendant sweep), FollowGraph's fromList "part of
      primary" scan, and sharedAssignmentEdgeStream's field-mirror scan.
    - verticesWithRepInScope: vertices with a SharedVariable rep in their scope chain — exactly those for
      which expandRepToMembers is non-trivial (FollowGraph's rep-expansion branch).
    - verticesWithReturnPrimary: vertices whose primary is a ReturnVariable (FollowGraph's reverse-return
      block).
    LinkedHashSets: the sets' iteration order is the vertices' engine insertion order, i.e. the same
    relative order the replaced graph.variables() scans produced for the matching subset — emission order
    feeds rank-stable sorts downstream.
     */
    private Map<Variable, Set<Variable>> verticesByScopePart = new HashMap<>();
    private Set<Variable> verticesWithRepInScope = new LinkedHashSet<>();
    private Set<Variable> verticesWithReturnPrimary = new LinkedHashSet<>();

    private void indexVertex(Variable v) {
        boolean[] rep = {false};
        Util.variableAndScopes(v).forEach(part -> {
            verticesByScopePart.computeIfAbsent(part, _ -> new LinkedHashSet<>()).add(v);
            if (part instanceof SharedVariable) rep[0] = true;
        });
        if (rep[0]) verticesWithRepInScope.add(v);
        if (Util.primary(v) instanceof ReturnVariable) verticesWithReturnPrimary.add(v);
    }

    private void unindexVertex(Variable v) {
        Util.variableAndScopes(v).forEach(part -> {
            Set<Variable> set = verticesByScopePart.get(part);
            if (set != null) {
                set.remove(v);
                if (set.isEmpty()) verticesByScopePart.remove(part);
            }
        });
        verticesWithRepInScope.remove(v);
        verticesWithReturnPrimary.remove(v);
    }

    // vertices v with base ∈ variableAndScopes(v), equivalently Util.isPartOf(base, v). Unmodifiable VIEW:
    // do not hold across graph mutations (isKnownInGraph copies for exactly that reason)
    public Set<Variable> verticesPartOf(Variable base) {
        Set<Variable> set = verticesByScopePart.get(base);
        Set<Variable> result = set == null ? Set.of() : Collections.unmodifiableSet(set);
        assert indexMatchesScan(base, result);
        return result;
    }

    public Set<Variable> verticesWithRepInScope() {
        return Collections.unmodifiableSet(verticesWithRepInScope);
    }

    public Set<Variable> verticesWithReturnPrimary() {
        return Collections.unmodifiableSet(verticesWithReturnPrimary);
    }

    /*
    Extraction-reuse dirty tracking, graph side (remedy A, review 2026-08-05). The engine tracks closure
    changes itself; everything ELSE an extraction reads — shared-variable groups, VMI groups, mediation
    pairs, pattern bindings — is recorded here, EAGERLY at the mutation site: a membership query at drain
    time sees post-mutation state, which no longer knows the old siblings.
     */
    private final Set<Variable> touchedVariables = new HashSet<>();

    // per-statement walk budget for drainDirtyVariables; exhaustion degrades to dirty-all, never wrong
    private static final long REUSE_BUDGET = Long.getLong("maddi.reuseBudget", 200_000L);

    /*
    Memo for expandRepToMembers along the drain BFS: rebuilding the member spellings allocates fresh
    FieldReferences per member per node, and dense methods re-walk the same component EVERY statement —
    66% of the remaining drain CPU after the prefix-table fix (asprof 2026-08-05, round 2). An
    expansion depends only on the vertex's structure and the rep memberships in its chain, so the memo
    is invalidated wholesale at every shared-group mutation (rare) — see clearRepExpansionCache callers.
     */
    private final Map<Variable, List<Variable>> repExpansionCache = new HashMap<>();

    private void clearRepExpansionCache() {
        repExpansionCache.clear();
    }

    private void touchVariable(Variable v) {
        touchedVariables.add(v);
    }

    private void touchAll(Collection<Variable> vs) {
        touchedVariables.addAll(vs);
    }

    /*
    The set of variables whose NEXT extraction may differ from their previous one, i.e. the union of the
    "link webs" touched since the last drain — or null when the dirt is global (a return-primary vertex
    changed: the reverse-return block in FollowGraph feeds every primary from return rows).

    The BFS closes the raw dirt over every mechanism that can carry knowledge between variables during
    extraction: closure facts (rows), structural containment (a vertex change affects all its scope
    prefixes; a variable change affects vertices spelled through it — the C index), shared-variable groups
    (reconstruction + faceKeyed/derivedFaceKeyed rehoming, reached via group co-members and the
    members-rooted-at-prefix table), and VMI groups (§m folds). Closure of the dirt over this web is what
    makes per-variable reuse sound: RedundantLinks' cross-variable guard edges come from links, links stay
    within a web, so every web is either entirely reused or entirely recomputed.
     */
    // cheap drain for the NOREUSE opt-out: empty the tracking sets without computing the closure
    public void drainRaw() {
        engine.drainTouched();
        touchedVariables.clear();
        dirtyEverything = false;
    }

    public Set<Variable> drainDirtyVariables() {
        Set<Variable> seeds = engine.drainTouched();
        seeds.addAll(touchedVariables);
        touchedVariables.clear();
        if (dirtyEverything) {
            dirtyEverything = false;
            return null;
        }
        if (seeds.isEmpty()) return Set.of();
        // dedup at PUSH time: the queue stays bounded by the dirty set — a dense closure component
        // otherwise queues O(k^2) duplicate entries (heap blow-up on the bench shapes)
        Set<Variable> dirty = new HashSet<>();
        Deque<Variable> work = new ArrayDeque<>();
        // budgeted: on a saturated closure the walk is Θ(k^2) per statement and the dirty web is the
        // whole graph — reuse cannot pay off there, so bail to dirty-all (the pre-reuse behavior)
        // instead of grinding memory and CPU (the 512m test-worker OOM). Per-statement budget.
        long[] budget = {REUSE_BUDGET};
        java.util.function.Consumer<Variable> push = v -> {
            budget[0]--;
            if (dirty.add(v)) work.push(v);
        };
        seeds.forEach(push);
        while (!work.isEmpty()) {
            if (budget[0] < 0) return null; // dirty-all
            Variable d = work.pop();
            if (d instanceof ReturnVariable || Util.primary(d) instanceof ReturnVariable) {
                return null; // global dirt
            }
            engine.successorStream(d).forEach(e -> push.accept(e.getKey()));
            verticesByScopePart.getOrDefault(d, Set.of()).forEach(push);
            // prefix web: rep-expansion only when a rep actually sits in the chain — the stream-and-
            // rebuild machinery, run unconditionally, was 37% of the drain's CPU (asprof 2026-08-05);
            // the common case is a plain iterative scope walk, allocation-free
            if (hasRepInChain(d)) {
                for (Variable m : repExpansionCache.computeIfAbsent(d, v -> expandRepToMembers(v).toList())) {
                    push.accept(m);
                    Util.scopeVariables(m).forEach(push);
                }
            } else {
                Util.scopeVariables(d).forEach(push);
            }
            sharedVariables.allShared(d).forEach(push);
            // persistent prefix indexes, maintained at group-mutation time (SharedVariables/VMI); the
            // per-statement table rebuild they replace was 46% of the drain's CPU
            sharedVariables.membersRootedAt(d).forEach(push);
            for (Variable f : virtualModificationIdenticals.membersRootedAt(d)) {
                virtualModificationIdenticals.groupsOfMember(f).forEach(g -> g.members().forEach(push));
            }
        }
        return dirty;
    }

    private static boolean hasRepInChain(Variable v) {
        Variable x = v;
        while (x != null) {
            if (x instanceof SharedVariable) return true;
            x = x instanceof FieldReference fr ? fr.scopeVariable()
                    : x instanceof DependentVariable dv ? dv.arrayVariable() : null;
        }
        return false;
    }

    // assertion-only: the index must agree with the scan it replaced
    private boolean indexMatchesScan(Variable base, Set<Variable> fromIndex) {
        Set<Variable> scanned = engine.vertices().stream()
                .filter(v -> Util.variableAndScopes(v).anyMatch(base::equals))
                .collect(Collectors.toUnmodifiableSet());
        assert scanned.equals(fromIndex) : "vertex index out of sync for " + base
                                           + ": index " + fromIndex + " vs scan " + scanned;
        return true;
    }

    public Collection<Variable> allShared(Variable variable) {
        return sharedVariables.allShared(variable);
    }

    public Set<Variable> derivedShared(Variable variable) {
        return sharedVariables.derivedShared(variable);
    }

    public Set<Variable> assignmentSources(Variable variable) {
        return sharedVariables.assignmentSources(variable);
    }

    // return variables that were assigned a fresh, unanalyzable object ('return new URL(...)') whose
    // reduced intermediate never entered the graph; handleReturnVariable adds the '← $_v' marker for them
    private Set<Variable> freshObjectReturns = new HashSet<>();

    // side-band, like freshObjectReturns: record-pattern bindings ('i instanceof R(Object o)' ⟹ o is a
    // genuine component of i). The containment filter (isInvalidFieldContainment) cannot distinguish a
    // pattern binding from an accessor-copy expansion — the distinction is made HERE, at the binding site.
    private Map<Variable, Set<Variable>> patternBindings = new HashMap<>();

    public void markPatternBinding(Variable container, Variable binding) {
        patternBindings.computeIfAbsent(container, _ -> new HashSet<>()).add(binding);
        touchVariable(container);
        touchVariable(binding);
    }

    // 'part' (or any of its whole-object group aliases — the cast 'set' of a bound 'o') is a marked
    // pattern binding of 'container'
    public boolean isPatternBindingOrAlias(Variable container, Variable part) {
        Set<Variable> bindings = patternBindings.get(container);
        if (bindings == null) return false;
        if (bindings.contains(part)) return true;
        for (Variable alias : sharedVariables.allShared(part)) {
            if (bindings.contains(alias)) return true;
        }
        return false;
    }

    // a type-pattern alias of an existing binding ('o instanceof Set set' where o is a bound component of i):
    // the alias is the same object, so it is a component of every container o is bound in
    public void markPatternBindingAlias(Variable original, Variable alias) {
        for (Map.Entry<Variable, Set<Variable>> entry : patternBindings.entrySet()) {
            if (entry.getValue().contains(original)) {
                entry.getValue().add(alias);
                touchVariable(entry.getKey());
            }
        }
        touchVariable(original);
        touchVariable(alias);
    }

    // side-band mediation provenance (task #39): variable pairs whose direct link was produced through a
    // syntactic mediation (pattern binding, cast). Populated at insertion (simpleAddToGraph) from
    // Link.mediated(); consulted at extraction (WriteLinksAndModification) when statement-level links are
    // rebuilt — both the engine's facts and the shared-variable collapse erase the flag. Unordered pairs:
    // the engine stores both orientations of an edge. Direct pairs only; a chain composed ACROSS a
    // mediated hop is not (yet) tainted — record before consuming this for declared-type decisions.
    private Set<Set<Variable>> mediatedPairs = new HashSet<>();

    public void markMediated(Variable a, Variable b) {
        if (!a.equals(b)) {
            mediatedPairs.add(Set.of(a, b));
            touchVariable(a);
            touchVariable(b);
        }
    }

    public boolean isMediatedPair(Variable a, Variable b) {
        return !a.equals(b) && mediatedPairs.contains(Set.of(a, b));
    }

    // the edges a statement's own evaluation inserted (LinkGraph.compute), in both orientations, keyed on the
    // nature's rank: the ☷ pass-variant of ≡ is the same edge. Consulted at extraction, as mediatedPairs is, to
    // set Link.derived() on what the closure and the shared-variable reconstruction emit: a fact that was never
    // inserted is a composition. A cleared (reassigned) or removed variable's edges go with it, as its mediation
    // does; the fork/join of a statement unites the alternatives' edges, as it unites their facts.
    // An intermediate ('$__rvN', the value of a switch expression whose block arms are statements of their own, so
    // the reduction within one statement cannot remove it) is a carrier of the statement: the composition
    // across it ('r ← $__rv' + '$__rv ← s' = 'r ← s') is what the reduction would have inserted, and is direct.
    private Map<List<Object>, LinkNature> directEdges = new HashMap<>();

    private static List<Object> directKey(Variable from, LinkNature linkNature, Variable to) {
        return List.of(from, linkNature.rank(), to);
    }

    public void markDirect(Variable from, LinkNature linkNature, Variable to) {
        if (from.equals(to) || directEdges.containsKey(directKey(from, linkNature, to))) return;
        directEdges.put(directKey(from, linkNature, to), linkNature);
        directEdges.put(directKey(to, linkNature.reverse(), from), linkNature.reverse());
        if (to instanceof IntermediateVariable) composeDirect(from, linkNature, to);
        if (from instanceof IntermediateVariable) composeDirect(to, linkNature.reverse(), from);
    }

    // every direct edge leaving the intermediate 'via' continues the edge 'from -ln-> via'
    private void composeDirect(Variable from, LinkNature linkNature, Variable via) {
        List<Map.Entry<List<Object>, LinkNature>> onward = directEdges.entrySet().stream()
                .filter(e -> via.equals(e.getKey().getFirst())).toList();
        for (Map.Entry<List<Object>, LinkNature> e : onward) {
            Variable target = (Variable) e.getKey().get(2);
            LinkNature combined = linkNature.combine(e.getValue());
            if (combined != null && combined.valid() && engine.isValid(combined) && !target.equals(from)) {
                markDirect(from, combined, target);
            }
        }
    }

    public boolean isDirect(Variable from, LinkNature linkNature, Variable to) {
        return directEdges.containsKey(directKey(from, linkNature, to));
    }

    /*
     What a vertex about to go carried between two survivors stays direct, as the engine materializes the
     compositions it orphans (IncrementalFixpointEngine.materializeWitnessOrphans): 'r ← s' when the block local
     's' of 'r = switch (k) { case 1 -> { String s = null; yield s; } ... }' goes out of scope, and 'y ← a' from
     'y = x' when 'x = a' is overwritten.
     */
    private void forgetDirect(Set<Variable> gone) {
        for (Variable g : gone) {
            List<Map.Entry<List<Object>, LinkNature>> into = directEdges.entrySet().stream()
                    .filter(e -> g.equals(e.getKey().get(2)) && !gone.contains((Variable) e.getKey().getFirst()))
                    .toList();
            List<Map.Entry<List<Object>, LinkNature>> outOf = directEdges.entrySet().stream()
                    .filter(e -> g.equals(e.getKey().getFirst()) && !gone.contains((Variable) e.getKey().get(2)))
                    .toList();
            for (Map.Entry<List<Object>, LinkNature> in : into) {
                for (Map.Entry<List<Object>, LinkNature> out : outOf) {
                    Variable a = (Variable) in.getKey().getFirst();
                    Variable b = (Variable) out.getKey().get(2);
                    if (a.equals(b)) continue;
                    LinkNature combined = in.getValue().combine(out.getValue());
                    if (combined != null && combined.valid() && engine.isValid(combined)) markDirect(a, combined, b);
                }
            }
        }
        directEdges.keySet().removeIf(key -> gone.contains((Variable) key.get(0))
                                             || gone.contains((Variable) key.get(2)));
    }

    public void markFreshObjectReturn(Variable returnVariable) {
        freshObjectReturns.add(returnVariable);
        touchVariable(returnVariable);
    }

    public boolean isFreshObjectReturn(Variable returnVariable) {
        return freshObjectReturns.contains(returnVariable);
    }

    public IncrementalFixpointEngine<Variable, LinkNature> engine() {
        return engine;
    }

    public boolean containsVariable(Variable primary) {
        return engine.vertices().contains(primary);
    }

    public Iterable<Map.Entry<Variable, Map<Variable, LinkNature>>> edges() {
        return engine.edges();
    }

    public Iterable<Map.Entry<Variable, Map<Variable, LinkNature>>> edgesWithEquivalence() {
        List<Map.Entry<Variable, Map<Variable, LinkNature>>> res = new LinkedList<>();
        engine.edges().forEach(res::add);
        virtualModificationIdenticals.edges().forEach(res::add);
        return res;
    }

    public Stream<Variable> eqVariables() {
        return virtualModificationIdenticals.variables();
    }

    public Stream<Variable> eqVariables(Variable variable) {
      return  virtualModificationIdenticals.equivalentStream(variable);
    }

    public Stream<VirtualModificationIdenticals.Group> eqGroups(Variable variable) {
        return virtualModificationIdenticals.groupsOf(variable);
    }

    /*
    §m-directional inheritance (consumption-aware; see catalogue): §m ≡ facts are routed into VMI, never the
    graph, so the closure cannot compose 'r.§m ≡ rr.§m' with the graph edge 'rr.§m → 0:in.§m'. For the strict-≡
    (no ☷ pass) groups of 'owner', read each sibling's closure and return the §m-to-§m facts rehomed onto
    owner's face. The CALLER decides when these enter a builder: they must be added AFTER the modification
    decision and the ⊇→~ rewrite collection — emitted earlier, they leak into verdicts (the reverted VMIFP
    experiment: ⊇→~ fired, 'newly created cannot be modified' nearly flipped).
     */
    public java.util.List<Link> vmiDirectionalFacts(Variable owner) {
        java.util.List<Link> result = new ArrayList<>();
        virtualModificationIdenticals.groupsOf(owner).forEach(group -> {
            if (!group.linkNature().pass().isEmpty()) return;
            Variable face = group.members().stream()
                    .filter(v -> owner.equals(Util.firstRealVariable(v)))
                    // a stacked face (x.§m.§m, legal inside VMI bookkeeping) cannot be represented as a Link:
                    // skip it rather than trip the LinkImpl constructor assert (timefold constraint streams)
                    .filter(LinksImpl.LinkImpl::doNotStackMOnTopOfVirtualField)
                    .findFirst().orElse(null);
            if (face == null) return;
            for (Variable sib : group.members()) {
                if (!sib.equals(face) && containsVariable(sib)) {
                    closureStream(sib).forEach(entry -> {
                        if (Util.isVirtualModification(entry.getKey())
                            && !entry.getKey().equals(face)
                            && !group.members().contains(entry.getKey())
                            && LinksImpl.LinkImpl.doNotStackMOnTopOfVirtualField(entry.getKey())) {
                            result.add(new LinksImpl.LinkImpl(face, entry.getValue(), entry.getKey()));
                        }
                    });
                }
            }
        });
        return result;
    }

    public void removeEquivalence(Set<Variable> allToRemove2) {
        // eager: after remove() the groups no longer know these members' old siblings
        for (Variable v : allToRemove2) {
            touchVariable(v);
            virtualModificationIdenticals.groupsOfMember(v).forEach(g -> touchAll(g.members()));
        }
        virtualModificationIdenticals.remove(allToRemove2);
    }

    private boolean invalidEdge(Variable from, LinkNature label, Variable to) {
        if (Util.isVirtualModification(from) != Util.isVirtualModification(to)) return true;
        if (Util.virtual(from) == Util.virtual(to)) return false;
        // an edge between a real variable and a virtual (hidden-content) field is legitimate for element/membership
        // (∈ ∋), for assignment of the content itself (← →): a value read out of a container's hidden content is
        // assigned from it, e.g. 'X x = optional.orElseGet(...)' yields 'x ← optional.§x' — and for content
        // subset/superset against a single value (⊆ ⊇, gate NORVSUB): 'Stream.generate(() -> alt)' yields
        // 'genParam.§xs ⊆ 0:alt' (all the stream's content IS repetitions of alt).
        if (label == LinkNatureImpl.CONTAINS_AS_MEMBER
            || label == LinkNatureImpl.IS_ELEMENT_OF
            || label == LinkNatureImpl.IS_ASSIGNED_FROM
            || label == LinkNatureImpl.IS_ASSIGNED_TO) return false;
        if (!Gate.isSet("NORVSUB")
            && (label == LinkNatureImpl.IS_SUBSET_OF || label == LinkNatureImpl.IS_SUPERSET_OF)) return false;
        // The owner ≻ own-virtual-field spine (the old engine's AddEdge.addField added it unconditionally): a
        // variable genuinely contains its own hidden content. These edges are load-bearing: the varargs fan-out
        // 'target.§is ~ collection.§is ≺ collection ∈ collections.§iss' closes to 'target.§is ∩ collections.§iss'
        // only through them. The general real↔virtual ≺/≻ ban (a graph-size reduction) stays for CROSS-variable
        // containment, which is malformed.
        if (Gate.isSet("NOSPINE")) return true;
        if (label == LinkNatureImpl.CONTAINS_AS_FIELD && Util.virtual(to) && from.equals(fieldScopeRoot(to))) {
            return false;
        }
        if (label == LinkNatureImpl.IS_FIELD_OF && Util.virtual(from) && to.equals(fieldScopeRoot(from))) {
            return false;
        }
        return true;
    }

    public Stream<Link> sharedAssignmentEdgeStream(Variable primary) {
        List<Link> result = new ArrayList<>();
        sharedVariables.assignmentEdgeStream(primary, this::isMediatedPair).forEach(link -> {
            result.add(link);
            // Field-level mirrors of a reconstructed whole-object assignment: the collapse hides the ← edge from
            // the engine, so the field projections the old engine's sub-propagation derived (combine.§is ←
            // target.§is for 'return target'; setI.i ← this.i for a fluent setter) never arise. The group's field
            // vertices live re-keyed on the rep; project each onto both endpoints of the intra-group link.
            Variable from = link.from(), to = link.to();
            boolean direct = isDirect(from, link.linkNature(), to);
            Variable repFrom = sharedVariables.translateForward(from);
            if (!Gate.isSet("NOMIRROR")
                && repFrom instanceof SharedVariable && repFrom.equals(sharedVariables.translateForward(to))) {
                for (Variable v : verticesPartOf(repFrom)) {
                    if (!v.equals(repFrom)) {
                        Variable fromSub = rehome(v, repFrom, from);
                        Variable toSub = rehome(v, repFrom, to);
                        // rehoming a §m member onto an endpoint that is itself x.§m stacks §m.§m — not
                        // representable as a Link; skip (same policy as vmiDirectionalFacts)
                        if (LinksImpl.LinkImpl.doNotStackMOnTopOfVirtualField(fromSub)
                            && LinksImpl.LinkImpl.doNotStackMOnTopOfVirtualField(toSub)) {
                            // a field-level mirror of a mediated whole-object link inherits the provenance; of a
                            // statement's own link, its directness ('return out' gives 'ret.§$s ⊆ out.§$s', the
                            // content copied, see Link.derived())
                            if (direct) markDirect(fromSub, link.linkNature(), toSub);
                            result.add(new LinksImpl.LinkImpl(fromSub, link.linkNature(), toSub, link.mediated()));
                        }
                    }
                }
                // A field of the SOURCE face may itself be collapsed into a DIFFERENT group ('this.i' lives in
                // {this.i, 0:i}), so its vertex sits under that group's rep and is invisible above. Project such
                // member-fields of the source onto the recipient: 'setI ← this' mirrors to 'setI.i ← this.i'
                // (source knowledge transfers to the recipient, techniques §1.2; never the reverse).
                if (link.linkNature().isAssignedFrom()) {
                    sharedVariables.memberFieldsOf(to).forEach(m -> {
                        Variable rehomed = rehome(m, to, from);
                        if (LinksImpl.LinkImpl.doNotStackMOnTopOfVirtualField(rehomed)
                            && LinksImpl.LinkImpl.doNotStackMOnTopOfVirtualField(m)) {
                            if (direct) markDirect(rehomed, link.linkNature(), m);
                            result.add(new LinksImpl.LinkImpl(rehomed, link.linkNature(), m, link.mediated()));
                        }
                    });
                }
            }
        });
        return result.stream();
    }

    // another member of 'variable's shared group: it survives clearing 'variable' (a reassignment) and stands for
    // the same object until then. Null when 'variable' is in no group, or alone in it.
    public Variable survivingAlias(Variable variable) {
        return sharedVariables.allShared(variable).stream()
                .filter(v -> !v.equals(variable) && !(v instanceof IntermediateVariable))
                .min(Variable::compareTo).orElse(null);
    }

    public boolean isPureAssignmentSource(Variable variable) {
        return sharedVariables.isPureAssignmentSource(variable);
    }

    public Stream<Link> virtualModificationEdgeStream(Variable primary) {
        Set<Variable> variables = virtualModificationIdenticals.variablesPartOf(primary);
        Stream<Link> own = variables.stream()
                .flatMap(v -> virtualModificationIdenticals.groupsOfMember(v).flatMap(g -> g.expand(v)));
        // §m knowledge of an assignment SOURCE transfers to the recipient: 'return zs' collapses {return, zs}, and
        // zs.§m ≡ 0:in.§m (subList returns a view) must surface as return.§m ≡ 0:in.§m on the return's summary.
        // The VMI members are keyed on the source (primary(zs.§m) = zs), so variablesPartOf(primary) misses them;
        // rehome each source-face member onto the primary. Only the SOURCE direction transfers (see
        // isPureAssignmentSource for why the reverse must not).
        List<Link> inherited = new ArrayList<>();
        for (Variable face : sharedVariables.assignmentSources(primary)) {
            for (Variable v : virtualModificationIdenticals.variablesPartOf(face)) {
                Variable rehomed = rehome(v, face, primary);
                virtualModificationIdenticals.groupsOfMember(v)
                        .flatMap(g -> g.expand(rehomed)).forEach(inherited::add);
            }
        }
        return Stream.concat(own, inherited.stream());
    }


    public void clear(Variable variable, String statementIndex) {
        // eager: capture the group siblings AND the group's rep before the membership is severed — the
        // group's facts live on the REP vertex, so only the rep connects the BFS to the variables whose
        // rep-expansion is about to shrink (TestSimpleSharedVariable test4: this.field ∈ $__sv_copy.§$s)
        touchVariable(variable);
        Variable rep = sharedVariables.translateForward(variable);
        if (rep != variable) touchVariable(rep);
        touchAll(sharedVariables.allShared(variable));
        clearRepExpansionCache();
        sharedVariables.remove(variable);
        // remove the variable AND every graph vertex whose scope chain contains it (its virtual fields 'v.§f',
        // array accesses 'v.f[i]', ...): with the owner≻own-virtual-field spine each variable owns such vertices,
        // and leaving them orphaned (scope pointing at a removed variable) pollutes the graph and later closures.
        // materializeWitnessOrphans (inside removeVertices) first preserves knowledge between SURVIVORS whose
        // witnesses routed through any of these.
        Set<Variable> set;
        if (Gate.isSet("NODESC")) {
            set = Set.of(variable);
        } else {
            set = new HashSet<>(isKnownInGraph(variable));
            set.add(variable);
        }
        if (engine.removeVertices(set)) {
            engine.recompute(set, statementIndex, _ -> true);
        }
        // a cleared (reassigned) variable's mediation provenance is stale: the pair described the OLD value
        mediatedPairs.removeIf(pair -> {
            boolean remove = pair.stream().anyMatch(set::contains);
            if (remove) touchAll(pair); // the surviving partner's emissions change too
            return remove;
        });
        forgetDirect(set);
    }

    // diagnostic: NOSV=1 in the environment disables the shared-variable collapse (assignments stay first-class
    // edges), so the O(N^2) part-of link explosion is not bounded. Used to demonstrate what sv prevents.
    private static final boolean NOSV = Gate.isSet("NOSV");
    // CodeLaser/maddi-mod#77: gate NOSRCEVICT restores the rep edge for a reassigned source
    private static final boolean NO_SRC_EVICT = Gate.isSet("NOSRCEVICT");

    boolean mergeEdgeBi(Variable from, LinkNature linkNature, Variable to, String statementIndex) {
        if (Gate.isSet("TRACEVAR")
            && (from.toString().contains(Gate.get("TRACEVAR")) || to.toString().contains(Gate.get("TRACEVAR")))) {
            System.out.println("TRACE mergeEdgeBi " + statementIndex + ": " + from + " " + linkNature + " " + to);
        }
        // boundary filter for the Options.objectGraphLinks label cut: PRODUCTION excludes ∩/≤/≥ from the
        // engine entirely, but some emitters produce them unconditionally (MakeGraph's slice ≤ base edge,
        // 'mapRight.§tts[-1] ≤ mapRight.§tts') — drop them here instead of tripping the engine's assert
        if (!engine.isValid(linkNature)) {
            return false;
        }
        if (from.equals(to)) {
            return engine.addVertex(from); // safety measure, is technically possible
        }
        if (invalidEdge(from, linkNature, to)) return false;
        if (linkNature.isIdenticalTo() && Util.isVirtualModification(from)) {
            boolean change = virtualModificationIdenticals.add(from, linkNature, to);
            // a VMI change never touches the engine, so record it here; the merged group's members all
            // gain each other's §m knowledge
            touchVariable(from);
            touchVariable(to);
            virtualModificationIdenticals.groupsOfMember(from).forEach(g -> touchAll(g.members()));
            virtualModificationIdenticals.groupsOfMember(to).forEach(g -> touchAll(g.members()));
            return change;
        }
        // only collapse whole-object assignment aliases; a hidden-content virtual field ('x ← optional.§x' — x is a
        // copy of the content, not an alias of the container) stays a first-class edge so 'x ← optional.§x' survives.
        if (!NOSV && linkNature.isAssignedFrom() && !(to instanceof MarkerVariable)
            && !Util.virtual(from) && !Util.virtual(to)) {
            // eager: capture both sides' current groups AND reps before any membership mutation below
            // (the rep vertex carries the group's facts; see clear())
            clearRepExpansionCache();
            touchVariable(from);
            touchVariable(to);
            Variable oldRepFrom = sharedVariables.translateForward(from);
            if (oldRepFrom != from) touchVariable(oldRepFrom);
            Variable oldRepTo = sharedVariables.translateForward(to);
            if (oldRepTo != to) touchVariable(oldRepTo);
            touchAll(sharedVariables.allShared(from));
            touchAll(sharedVariables.allShared(to));
            boolean fromInGroups = sharedVariables.isKnown(from);
            if (fromInGroups && !(from instanceof io.codelaser.maddi.modification.prepwork.variable.ReturnVariable)
                && sharedVariables.isReassignment(from, statementIndex)) {
                // genuine reassignment ('from' was assigned at an earlier statement, now assigned again): drop its
                // old group membership. A second value assigned in the SAME statement (multi-valued 'm = cond ? a : b'
                // -> 'm ← a' and 'm ← b') is NOT a reassignment; keep 'from' so both sources join one group.
                // A ReturnVariable is exempt: a second 'return' statement is a merge over paths, not a
                // reassignment — removing it abandons the group rep carrying the first path's knowledge.
                sharedVariables.remove(from);
                // TODO what with fromInGraph?
            } else if (!NO_SRC_EVICT && fromInGroups && sharedVariables.isSourceAtOtherStatement(from, statementIndex)
                       && !(from instanceof io.codelaser.maddi.cst.api.info.ParameterInfo)
                       && !sharedVariables.groupHoldsReturnValue(from)) {
                // a source is assigned a new value: the group keeps the old one. The rep edge below made every
                // other member hold the new value as well ('x = h.next; h.next = y; return x' read '← 1:y', and
                // guava's 'bucketHead = hashTable[b]; .. hashTable[b] = newEntry' made bucketHead the new entry,
                // CodeLaser/maddi-mod#77). Only a return value accumulates: it is assigned on several paths. A
                // parameter keeps the rep edge for now: evicted, its new value ('0:p ← 1:y') would read as the
                // caller's.
                sharedVariables.evictSource(from);
            } else if (!Gate.isSet("NORSRC")
                       && fromInGroups && sharedVariables.isSourceAtOtherStatement(from, statementIndex)) {
                // 'from' was a pure SOURCE in its group ('method ← 1:num' at an earlier statement) and is now
                // being assigned ('num = amb'): its past source-participation stays valid for the OLD value, but
                // the new value must not join the group as an alias. Keep the edge as a plain graph edge onto the
                // rep: extraction then yields both 'method ← 1:num' (intra-group) and 'method ← 0:amb' (rep edge).
                Variable tFromR = sharedVariables.translateForward(from);
                Variable tToR = sharedVariables.translateForward(to);
                if (tFromR.equals(tToR)) return engine.addVertex(tFromR);
                return engine.addSymmetricEdge(tFromR, tToR, linkNature, statementIndex) > 0;
            }
            SharedVariable sv = sharedVariables.isAssignedFrom(from, to, statementIndex);
            SharedVariable mergedAway = sharedVariables.consumeLastMergedAway();
            if (sv != null) {
                touchVariable(sv);
                touchAll(sv.variables());
            }
            if (mergedAway != null) {
                touchVariable(mergedAway);
                touchAll(mergedAway.variables());
            }
            if (mergedAway != null && sv != null) {
                // two existing groups were bridged: re-key the discarded rep's graph vertices onto the survivor
                Set<Variable> inGraph = isKnownInGraph(mergedAway);
                if (!inGraph.isEmpty()) {
                    transformToSharedVariable(mergedAway, inGraph, sv, statementIndex);
                }
                return true;
            }
            Set<Variable> fromInGraph = isKnownInGraph(from);
            Set<Variable> toInGraph = isKnownInGraph(to);
            if (sv == null) {
                // both sides are already direct members of the same group. Residual vertices spelled through a
                // member (faces added by statements AFTER it joined — e.g. the summary import of a later
                // 'v = wrap(v)' call in a reassignment-through-wrapper chain, timefold
                // ValueSelectorFactory.buildValueSelector) are legal on real code; re-key them onto the rep,
                // exactly as the sv != null path below does. (Historically an assert: "should already have been
                // removed" — the no-residue assumption only holds on the suite's simpler shapes.)
                SharedVariable group = sharedVariables.groupOf(from) != null
                        ? sharedVariables.groupOf(from) : sharedVariables.groupOf(to);
                if (group == null) return false;
                boolean change = false;
                if (!fromInGraph.isEmpty()) {
                    transformToSharedVariable(from, fromInGraph, group, statementIndex);
                    change = true;
                }
                Set<Variable> toResidue = isKnownInGraph(to);
                if (!toResidue.isEmpty()) {
                    transformToSharedVariable(to, toResidue, group, statementIndex);
                    change = true;
                }
                return change;
            }
            if (!fromInGraph.isEmpty()) {
                transformToSharedVariable(from, fromInGraph, sv, statementIndex);
            }
            // re-key the to-side as well (recomputed: the from-pass may already have consumed shared vertices).
            // Removing just the bare 'to' vertex instead left its scope-descendants ('to.§$$s' with its edges)
            // orphaned in the graph — the next edge on the same pair then hit the sv==null assert — and silently
            // deleted the to-side knowledge instead of re-keying it onto the rep.
            Set<Variable> toInGraphAfter = isKnownInGraph(to);
            if (!toInGraphAfter.isEmpty()) {
                transformToSharedVariable(to, toInGraphAfter, sv, statementIndex);
            }
            return true;
        }
        Variable tFrom = sharedVariables.translateForward(from);
        Variable tTo = sharedVariables.translateForward(to);
        if (tFrom.equals(tTo)) {
            // distinct 'from' and 'to' both translate to the same shared-variable representative (they are in one
            // assignment group): the edge would be a self-loop, which the engine forbids (Fact asserts
            // source != target). Nothing to link; just make sure the vertex exists. Mirrors the from.equals(to)
            // guard at the top of this method, now applied after translateForward.
            return engine.addVertex(tFrom);
        }
        return engine.addSymmetricEdge(tFrom, tTo, linkNature, statementIndex) > 0;
    }

    private Set<Variable> isKnownInGraph(Variable variable) {
        // COPY, not view: every caller removes these vertices from the engine (clear,
        // transformToSharedVariable), which mutates the underlying index set via the listener.
        // LinkedHashSet keeps engine-insertion order (deterministic), like the view.
        return Collections.unmodifiableSet(new LinkedHashSet<>(verticesPartOf(variable)));
    }


    private void transformToSharedVariable(Variable variable,
                                           Set<Variable> variablesInGraph,
                                           SharedVariable sharedVariable,
                                           String statementIndex) {
        // FQN-sorted, NOT set-iteration order: variablesInGraph is an unmodifiable set (per-JVM SALTED
        // iteration), and the loop below re-adds each member's edges to the engine in this order — seed
        // order decides which derivation paths fire first (see IncrementalFixpointEngine.addSymmetricEdge)
        var forwardLinksList = variablesInGraph
                .stream()
                .sorted(java.util.Comparator.comparing(Variable::fullyQualifiedName))
                .map(v -> new AbstractMap.SimpleEntry<>(v, engine.edges(v)))
                .toList();
        engine.removeVertices(variablesInGraph);
        engine.addVertex(sharedVariable);
        for (Map.Entry<Variable, Iterable<Map.Entry<Variable, LinkNature>>> forwardLinks : forwardLinksList) {
            VariableTranslationMap vtm = new VariableTranslationMap(runtime);
            vtm.put(variable, sharedVariable);
            Variable newFrom = vtm.translateVariableRecursively(forwardLinks.getKey());
            for (Map.Entry<Variable, LinkNature> link : forwardLinks.getValue()) {
                // translate BOTH endpoints: an edge between two members of the group (e.g. the owner ≻ own-virtual-
                // field spine 'target ≻ target.§is') must be re-keyed on both sides ('$__sv ≻ $__sv.§is'); leaving
                // the to-side untranslated resurrects the removed member vertex and produces a half-translated edge
                // ('$__sv ≻ target.§is') that invalidEdge then rightly drops — severing the spine at every collapse.
                Variable newTo = Gate.isSet("NOBOTH") ? link.getKey()
                        : vtm.translateVariableRecursively(link.getKey());
                // re-homing a member's edges onto the shared representative can make the source coincide with the
                // target (the edge pointed at another member of the same group); skip the resulting self-loop,
                // which the engine forbids (Fact asserts source != target).
                if (newFrom.equals(newTo)) continue;
                if (Gate.isSet("TRACEVAR")
                    && (newFrom.toString().contains(Gate.get("TRACEVAR"))
                        || newTo.toString().contains(Gate.get("TRACEVAR")))) {
                    System.out.println("TRACE transform " + variable + "->" + sharedVariable + " re-add: "
                                       + newFrom + " " + link.getValue() + " " + newTo);
                }
                engine.addSymmetricEdge(newFrom, newTo, link.getValue(), statementIndex);
            }
        }
        engine.recompute(Set.of(sharedVariable), statementIndex, _ -> true);
    }

    public Variable translateForward(Variable variable) {
        return sharedVariables.translateForward(variable);
    }

    // The inverse of translateForward for extraction: expand a graph vertex whose scope chain contains a
    // shared-variable rep back to its member forms (a rep as the whole vertex, or nested in a field scope such
    // as '$__sv_list1.§$s'). Mirrors WriteLinksAndModification.iterateOverShared. A vertex with no rep maps to
    // itself.
    public Stream<Variable> expandRepToMembers(Variable variable) {
        if (variable instanceof SharedVariable sv) {
            return sv.variables().stream();
        }
        if (variable instanceof FieldReference fr && fr.scopeVariable() != null) {
            return expandRepToMembers(fr.scopeVariable())
                    .map(scope -> scope.equals(fr.scopeVariable())
                            ? fr
                            : runtime.newFieldReference(fr.fieldInfo(),
                            runtime.newVariableExpression(scope), fr.parameterizedType()));
        }
        if (variable instanceof DependentVariable dv && dv.arrayVariable() != null) {
            // an array-indexed rep, e.g. '$__sv_g[0]' -> 'g[0]'; rebuild the access on the expanded array member.
            // keep the original element type: a member may be statically Object-typed (a downcast slot such as
            // 'ld.variables[1]' grouped with 'float[][] matrix'), where recomputing via copyWithOneFewerArrays fails.
            return expandRepToMembers(dv.arrayVariable())
                    .map(arr -> arr.equals(dv.arrayVariable())
                            ? dv
                            : runtime.newDependentVariable(runtime.newVariableExpression(arr), dv.indexExpression(),
                            dv.parameterizedType()));
        }
        return Stream.of(variable);
    }

    // rebuild 'variable' with every occurrence of 'from' in its scope chain replaced by 'to'
    // (e.g. rehome(a.list1.§$s, a, $__sv_return) = $__sv_return.list1.§$s).
    public Variable rehome(Variable variable, Variable from, Variable to) {
        VariableTranslationMap vtm = new VariableTranslationMap(runtime);
        vtm.put(from, to);
        return vtm.translateVariableRecursively(variable);
    }

    public String printEquivalence(Function<Variable, String> variablePrinter) {
        return virtualModificationIdenticals.print(variablePrinter);
    }

    public String printShared(Function<Variable, String> variablePrinter) {
        return sharedVariables.print(variablePrinter);
    }


    boolean simpleAddToGraph(Variable from, LinkNature linkNature, Variable to, String statementIndex) {
        return simpleAddToGraph(from, linkNature, to, statementIndex, false);
    }

    // a link of the statement's own evaluation (LinkGraph.compute): recorded as direct, see Link.derived()
    boolean addStatementLink(Variable from, LinkNature linkNature, Variable to, String statementIndex,
                             boolean mediated) {
        if (engine.isValid(linkNature) && !from.equals(to)) markDirect(from, linkNature, to);
        return simpleAddToGraph(from, linkNature, to, statementIndex, mediated);
    }

    boolean simpleAddToGraph(Variable from, LinkNature linkNature, Variable to, String statementIndex,
                             boolean mediated) {
        if (mediated) markMediated(from, to);
        boolean change = mergeEdgeBi(from, linkNature, to, statementIndex);
        change |= addField(from, Util.primary(from), statementIndex);
        change |= addField(to, Util.primary(to), statementIndex);
        return change;
    }

    static Variable fieldScopeRoot(Variable v) {
        if (v instanceof FieldReference fr) {
            if (fr.scopeVariable() instanceof This) return v;
            if (fr.scopeVariable() != null) return fieldScopeRoot(fr.scopeVariable());
        }
        return v;
    }

    boolean addField(Variable from, Variable primary, String statementIndex) {
        if (primary == null) return false; // e.g. an array access on an expression base: no primary
        if (!from.equals(primary) && !(primary instanceof This)
            && from instanceof FieldReference fr && primary.equals(fieldScopeRoot(from))) {
            // intermediate spine (gate NOSPINEI): a DEEP face 'entry.§xy.§x' also materializes the mid-level
            // chain 'entry ≻ entry.§xy ≻ entry.§xy.§x' — without the intermediate vertex the closure cannot
            // key facts on the mid-level face ('entry.§xy ≺ 0:optional', TestSupplier test7). The direct
            // 'entry ≻ entry.§xy.§x' fact still derives by ≻∘≻ composition.
            if (!Gate.isSet("NOSPINEI") && fr.scopeVariable() instanceof FieldReference parent
                && Util.virtual(from) && Util.virtual(parent.fieldInfo())) {
                boolean change = addField(parent, primary, statementIndex);
                change |= mergeEdgeBi(parent, CONTAINS_AS_FIELD, from, statementIndex);
                return change;
            }
            return mergeEdgeBi(primary, CONTAINS_AS_FIELD, from, statementIndex);
        }
        return false;
    }

    public String print() {
        return engine.print();
    }

    public String printClosure() {
        return engine.printClosure();
    }

    public void recompute(Set<Variable> affected,
                          String statementIndex,
                          Predicate<Fact<Variable, LinkNature>> acceptRemoval) {
        engine.recompute(affected, statementIndex, acceptRemoval);
    }

    /*
     An element at an index that goes out of scope ('for (int i = 0; ..; i++) a[i] = new Entry(in.get(i)); return a;')
     is dropped with its index, and so are its links: the returned array exported nothing about 'in'. Before the
     removal, each link of such an element to a variable that stays is lifted to the array through 'a[i] ∈ a', as the
     summary does for an element at a local index (LinkComputerImpl.liftLocallyIndexed). CodeLaser/maddi-mod#79.
     Gate NOSCOPELIFT.
     */
    private static final boolean NO_SCOPE_LIFT = Gate.isSet("NOSCOPELIFT");

    public void liftElementsLeavingScope(Set<Variable> toRemove, String statementIndex) {
        if (NO_SCOPE_LIFT || toRemove.isEmpty()) return;
        List<Link> lifted = new ArrayList<>();
        Set<Variable> vertices = variables();
        for (Variable removed : toRemove) {
            if (!(removed instanceof DependentVariable dv) || dv.arrayVariable() == null || dv.indexVariable() == null
                || !toRemove.contains(dv.indexVariable()) || toRemove.contains(dv.arrayVariable())
                || !vertices.contains(removed)) {
                continue;
            }
            Variable array = dv.arrayVariable();
            // the element and what holds the same object: its shared group's rep ('a[i] ← $__sv_a[i]')
            Set<Variable> same = new HashSet<>();
            same.add(removed);
            closureStream(removed).filter(e -> e.getValue().isIdenticalToOrAssignedFromTo())
                    .forEach(e -> same.add(e.getKey()));
            for (Variable vertex : vertices) {
                // up the vertex's field chain to the object ('$__sv_a[i].e ≺ $__sv_a[i]'), then into the array
                LinkNature fieldUp = null;
                Variable cur = vertex;
                while (!same.contains(cur) && cur instanceof FieldReference fr && fr.scopeVariable() != null) {
                    fieldUp = fieldUp == null ? LinkNatureImpl.IS_FIELD_OF.reverse()
                            : LinkNatureImpl.IS_FIELD_OF.reverse().combine(fieldUp);
                    cur = fr.scopeVariable();
                }
                if (!same.contains(cur)) continue;
                LinkNature up = fieldUp == null ? LinkNatureImpl.IS_ELEMENT_OF.reverse()
                        : LinkNatureImpl.IS_ELEMENT_OF.reverse().combine(fieldUp);
                if (!engine.isValid(up)) continue;
                closureStream(vertex).forEach(e -> {
                    Variable to = e.getKey();
                    if (same.contains(to) || to.equals(array) || Util.isPartOf(array, to)
                        || expandRepToMembers(to).allMatch(m -> leavesScope(m, toRemove))) {
                        return;
                    }
                    LinkNature nature = up.combine(e.getValue());
                    if (engine.isValid(nature)) lifted.add(new LinksImpl.LinkImpl(array, nature, to, false, true));
                });
            }
        }
        // the graph has no ∩ between a real variable and hidden content ('a ∩ in.§$s', invalidEdge): of the rest, the
        // finer link speaks for the coarser
        lifted.removeIf(l -> invalidEdge(l.from(), l.linkNature(), l.to()));
        lifted.removeIf(l -> lifted.stream().anyMatch(f -> f.from().equals(l.from()) && !f.to().equals(l.to())
                                                          && Util.isPartOf(l.to(), f.to())));
        lifted.forEach(l -> simpleAddToGraph(l.from(), l.linkNature(), l.to(), statementIndex, false));
    }

    // the variable, or a scope or index on its chain, is being removed
    private static boolean leavesScope(Variable v, Set<Variable> toRemove) {
        for (Variable cur = v; cur != null; ) {
            if (toRemove.contains(cur)) return true;
            if (cur instanceof FieldReference fr) cur = fr.scopeVariable();
            else if (cur instanceof DependentVariable dv) {
                if (dv.indexVariable() != null && toRemove.contains(dv.indexVariable())) return true;
                cur = dv.arrayVariable();
            } else cur = null;
        }
        return false;
    }

    public void remove(Set<Variable> toRemove) {
        engine.removeVertices(toRemove);
        mediatedPairs.removeIf(pair -> {
            boolean remove = pair.stream().anyMatch(toRemove::contains);
            if (remove) touchAll(pair);
            return remove;
        });
        forgetDirect(toRemove);
    }

    public Set<Variable> replaceReturnAffected(Variable from, Variable to,
                                               LinkNature currentLinkNature,
                                               LinkNature newLinkNature,
                                               String skipStatementIndex,
                                               Predicate<Fact<Variable, LinkNature>> acceptRaw) {
        return engine.replaceReturnAffected(from, to, currentLinkNature, newLinkNature, skipStatementIndex,
                acceptRaw);
    }

    public int size() {
        return variables().size();
    }

    public int sizeOfClosure() {
        return engine.sizeOfClosure();
    }

    public int sizeOfWitnesses() {
        return engine.sizeOfWitnesses();
    }

    Set<Variable> variables() {
        return engine.vertices();
    }

    Stream<Map.Entry<Variable, LinkNature>> closureStream(Variable variable) {
        return engine.successorStream(variable);
    }

    public Iterable<Map.Entry<Variable, LinkNature>> closure(Variable variable) {
        return engine.successors(variable);
    }
}
