package io.codelaser.maddi.modification.link.impl.graph;

import java.util.*;
import java.util.function.BiPredicate;
import java.util.function.BinaryOperator;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.codelaser.maddi.modification.link.impl.Gate;

/*
Features:

1. label function has a reverse and combine; these do not play well together
   e.g. rev(∋ + ∈) = rev(~) != rev(∋)+rev(∈) == X
   it also has a test for valid labels (X is never stored) and a score function (see further)
2. LabeledGraph is symmetric: if a B c is present, then c rev(B) a is present as well
   we do not need to store the reverse graph for efficiency reasons
3. ClosureGraph is symmetric: if a B c is present, then c rev(B) a is present as well
   we do not need to store the reverse graph for efficiency reasons
4. Witnesses keep support history to avoid cyclic reasoning
5. The incremental fixpoint algorithm must have a forward and a backward phase, because the lack of operator symmetry.
   Each phase must do one direction at a time!
6. we prefer direct high value edges over lower value edges, e.g. ← + ∈ is preferred over ∈ + ~
   Their outcome is different!
   As a consequence, the forward propagation must go over the closure rather
   than the graph, because otherwise it cannot find the best combination (it will find 'a' combination).
   See TestDependent,2 for an example, where ∈? finally gets replaced by ∈ because of an already existing combination
   in the closure.
7. the Graph and MakeGraph classes try to keep this graph as simple as possible, whilst generally being
   the cause for this graph system being ways too large in complex situations.
8. modifications on certain variables can cause a selection of edges to be removed. This class provides a
   'repair' function to recompute the closure.
9. return values cannot be used to create composite facts, see acceptForComposite, TestSharedVariables

 */
public final class IncrementalFixpointEngine<V, L> {
    private static final Logger LOGGER = LoggerFactory.getLogger(IncrementalFixpointEngine.class);

    private final LabeledGraph<V, L> graph;
    private final Closure<V, L> closure;
    private final WitnessIndex<V, L> witnessIndex;
    private final BinaryOperator<L> combine;
    private final BinaryOperator<L> best;
    private final Predicate<L> valid;
    private final UnaryOperator<L> reverse;
    private final Function<V, String> vertexPrinter;
    private final Comparator<V> vertexComparator;
    private final Predicate<V> acceptForComposite;
    // a derived fact must also be acceptable as a whole (source, target, label); see LinkComputerImpl: no
    // identity, assignment or sharing derived between a variable and its own field path
    private final BiPredicate<Fact<V, L>, V> acceptComposite;

    // Per-method effort ceiling. The engine lives for ONE method (created in SourceMethodComputer);
    // one pathological method must not consume minutes of CPU and gigabytes of closure (elasticsearch
    // first contact: a single method ground a worker 30+ min and OOM'd the 8G heap; with a per-POP
    // counter the same method then burned 96 min WITHOUT tripping — the explosion lives INSIDE one
    // fact's propagation loops, so the ceiling counts per EDGE VISIT in propagateForward/Backward.
    // Tripping throws the established "cycle protection" contract, which LinkComputerImpl catches and
    // degrades to the method's shallow summary. Opt-out: NOWORKCEILING=1; tune with -Dmaddi.workCeiling=N.
    // The reason is carried on the exception: this ceiling is a BUDGET (raise it and the method may well
    // complete), whereas LinkGraph's round limit is structural, and a degraded corpus needs to tell them apart.
    private static final long WORK_CEILING = Long.getLong("maddi.workCeiling", 10_000_000L);
    private static final boolean NO_WORK_CEILING = Gate.isSet("NOWORKCEILING");
    private long work;

    // LINKTRACE forensics (set by LinkComputerImpl for the matching method): print every seed,
    // propagation step and witness decision. Volatile, off by default; zero overhead when false.
    public static volatile boolean TRACE = false;

    public static void traceOverlap(boolean on) {
        ImplicitOverlap.TRACE = on;
    }

    /*
    Extraction-reuse dirty tracking (remedy A, review 2026-08-05): the endpoints of every CLOSURE-CONTENT
    change (fact added, label upgraded, fact removed) since the last drain. Witness-only changes are
    deliberately NOT tracked: extraction reads closure rows, never witnesses. Drained once per statement by
    WriteLinksAndModification (via Graph.drainDirtyVariables), which closes the set over the link web and
    skips re-extraction for variables outside it.
     */
    private final Set<V> touched = new HashSet<>();

    /*
     CodeLaser/maddi-mod#92: the implicit label ('∩'). It absorbs every composition (anything composed with it gives it
     or nothing), so leaving it out of derivation changes no other fact. Stored as a composite it formed a near-complete
     pairwise set among the variables sharing content with a common one, most of the closure and most of the work.
     With an implicit label set, composites of that label are not derived; direct edges carrying it stay. The facts
     are computed on demand from the rest of the closure (implicitSuccessors, ImplicitOverlap), and the classes held
     together only by vertices being removed are frozen first (freezeImplicitGroups).
     */
    private L implicit;
    private long implicitCacheVersion = -1;
    private final Map<V, Map<V, L>> implicitCache = new HashMap<>();
    // reversed rows of the vertices no composite targets (a return variable): propagateBackward composes through them
    private Map<V, Map<V, L>> reversedRows;
    private ImplicitOverlap<V, L> currentOverlap;
    private Predicate<V> implicitMiddleOk = _ -> true;
    private BiPredicate<V, V> implicitPairOk = (_, _) -> true;
    private List<List<V>> frozenGroups = new ArrayList<>();

    public void setImplicitLabel(L label, Predicate<V> middleOk, BiPredicate<V, V> pairOk) {
        this.implicit = label;
        this.implicitMiddleOk = middleOk;
        this.implicitPairOk = pairOk;
        implicitCache.clear();
        currentOverlap = null;
    }

    private boolean derivable(L label) {
        return valid.test(label) && (implicit == null || !implicit.equals(label));
    }

    private boolean isImplicit(L label) {
        return implicit != null && implicit.equals(label);
    }

    /*
     #92, the alternative to an implicit label: a label that is derived and stored as before, but never composed with
     itself ('∩ ∘ ∩'). Composing every new '∩' fact with all the other '∩' facts of its endpoints was the cubic part of the
     work; the facts only that composition derives are 1-2% of the closure.
     */
    private L nonChaining;

    public void setNonChainingLabel(L label) {
        this.nonChaining = label;
    }

    private boolean chain(L l1, L l2) {
        return nonChaining != null && nonChaining.equals(l1) && nonChaining.equals(l2);
    }

    private void touch(Fact<V, L> fact) {
        touched.add(fact.source());
        touched.add(fact.target());
    }

    /** Vertices whose closure content changed since the previous drain. Returns a fresh mutable set. */
    public Set<V> drainTouched() {
        Set<V> copy = new HashSet<>(touched);
        touched.clear();
        return copy;
    }

    static void lt(String message) {
        System.out.println("LT " + message);
    }

    private void countWork() {
        if (!NO_WORK_CEILING && ++work > WORK_CEILING) {
            throw new DegradedAnalysisException(DegradedAnalysisException.Reason.WORK_CEILING);
        }
    }

    public IncrementalFixpointEngine(BinaryOperator<L> combine,
                                     BinaryOperator<L> best,
                                     Predicate<L> valid,
                                     Function<L, Integer> scoreFunction,
                                     UnaryOperator<L> reverse,
                                     Function<V, String> vertexPrinter,
                                     Comparator<V> vertexComparator,
                                     Predicate<V> acceptForComposite) {
        this(combine, best, valid, scoreFunction, reverse, vertexPrinter, vertexComparator, acceptForComposite,
                (_, _) -> true);
    }

    public IncrementalFixpointEngine(BinaryOperator<L> combine,
                                     BinaryOperator<L> best,
                                     Predicate<L> valid,
                                     Function<L, Integer> scoreFunction,
                                     UnaryOperator<L> reverse,
                                     Function<V, String> vertexPrinter,
                                     Comparator<V> vertexComparator,
                                     Predicate<V> acceptForComposite,
                                     BiPredicate<Fact<V, L>, V> acceptComposite) {
        this(new LabeledGraph<>(), new Closure<>(best), new WitnessIndex<>(scoreFunction, vertexComparator),
                combine, best, valid, reverse, vertexPrinter, vertexComparator, acceptForComposite,
                acceptComposite);
    }

    private IncrementalFixpointEngine(LabeledGraph<V, L> graph,
                                      Closure<V, L> closure,
                                      WitnessIndex<V, L> witnessIndex,
                                      BinaryOperator<L> combine,
                                      BinaryOperator<L> best,
                                      Predicate<L> valid,
                                      UnaryOperator<L> reverse,
                                      Function<V, String> vertexPrinter,
                                      Comparator<V> vertexComparator,
                                      Predicate<V> acceptForComposite,
                                      BiPredicate<Fact<V, L>, V> acceptComposite) {
        this.graph = graph;
        this.closure = closure;
        this.witnessIndex = witnessIndex;
        this.combine = Objects.requireNonNull(combine);
        this.valid = Objects.requireNonNull(valid);
        this.best = Objects.requireNonNull(best);
        this.reverse = Objects.requireNonNull(reverse);
        this.vertexPrinter = vertexPrinter;
        this.vertexComparator = vertexComparator;
        this.acceptForComposite = acceptForComposite;
        this.acceptComposite = Objects.requireNonNull(acceptComposite);
    }

    /*
     An independent copy of the state: base edges, closure, witnesses, the work count and the touched set. The
     copy has no vertex listener; its owner registers one (and rebuilds whatever index the listener maintains).
     */
    public IncrementalFixpointEngine<V, L> copy() {
        IncrementalFixpointEngine<V, L> copy = new IncrementalFixpointEngine<>(graph.copy(), closure.copy(),
                witnessIndex.copy(), combine, best, valid, reverse, vertexPrinter, vertexComparator,
                acceptForComposite, acceptComposite);
        copy.work = work;
        copy.touched.addAll(touched);
        copy.implicit = implicit;
        copy.nonChaining = nonChaining;
        copy.implicitMiddleOk = implicitMiddleOk;
        copy.implicitPairOk = implicitPairOk;
        copy.frozenGroups = new ArrayList<>(frozenGroups);
        return copy;
    }

    /*
     The JOIN of two alternatives: base edges, closure facts and witnesses of 'other' added to this engine, without
     propagation. Both engines are closed; their union is deliberately NOT re-closed, because a composition of a
     fact of one alternative with a fact of another describes no execution. A pair present in both gets the best
     label, which is also what linking the alternatives one after the other into one engine produced.
     */
    public void unionWith(IncrementalFixpointEngine<V, L> other) {
        graph.unionWith(other.graph, best);
        closure.unionWith(other.closure);
        witnessIndex.unionWith(other.witnessIndex);
        for (List<V> g : other.frozenGroups) if (!frozenGroups.contains(g)) frozenGroups.add(g);
        currentOverlap = null;
        touched.addAll(other.touched);
        touched.addAll(other.graph.vertices());
    }

    // register BEFORE the first edge/vertex enters the engine: the listener only sees mutations from
    // that point on (Graph does so in its constructor, when the graph is still empty)
    public void setVertexListener(LabeledGraph.VertexListener<V> listener) {
        graph.setListener(listener);
    }

    public Iterable<Map.Entry<V, L>> edges(V v) {
        return graph.successors(v);
    }

    public boolean isKnown(V from) {
        return graph.isKnown(from);
    }

    /** Edge visits spent on this method so far, against {@link #workCeiling()}. Read for diagnostics only. */
    public long work() {
        return work;
    }

    /** The ceiling {@link #work()} is measured against; {@code Long.MAX_VALUE} when opted out. */
    public static long workCeiling() {
        return NO_WORK_CEILING ? Long.MAX_VALUE : WORK_CEILING;
    }

    public int sizeOfClosure() {
        return closure.countFacts();
    }

    public int sizeOfWitnesses() {
        return witnessIndex.size();
    }

    public Stream<Map.Entry<V, L>> successorStream(V variable) {
        return closure.successorStream(variable);
    }

    public Iterable<Map.Entry<V, L>> successors(V variable) {
        return closure.successors(variable);
    }

    public Iterable<Map.Entry<V, Map<V, L>>> edges() {
        return graph.edges();
    }

    public String print() {
        return graph.print(vertexPrinter, vertexComparator);
    }

    public String print(Function<V, String> vertexPrinter, Comparator<V> comparator) {
        return graph.print(vertexPrinter, comparator);
    }

    public String printEdges() {
        return graph.printEdges(vertexComparator);
    }

    public String printClosure() {
        if (implicit == null) return closure.print(vertexPrinter, vertexComparator, witnessIndex);
        // the stored facts and the implicit ones (#92), in one order; an implicit fact prints 'implicit' as witness
        List<V> sources = new ArrayList<>(graph.vertices());
        sources.sort(vertexComparator);
        StringBuilder sb = new StringBuilder();
        for (V from : sources) {
            Map<V, L> row = new TreeMap<>(vertexComparator);
            closure.successors(from).forEach(e -> row.put(e.getKey(), e.getValue()));
            Map<V, L> implicitRow = implicitSuccessors(from);
            row.putAll(implicitRow);
            row.forEach((to, label) -> sb.append(vertexPrinter.apply(from)).append(' ').append(label).append(' ')
                    .append(vertexPrinter.apply(to)).append("   ")
                    .append(implicitRow.containsKey(to) ? "implicit"
                            : witnessIndex.print(vertexPrinter, new Fact<>(from, to, label)))
                    .append('\n'));
        }
        return sb.toString();
    }

    public boolean addVertex(V v) {
        return graph.addVertex(v);
    }

    public boolean removeVertices(Set<V> vertices) {
        if (!Gate.isSet("NOMAT")) {
            if (implicit != null) freezeImplicitGroups(vertices);
            materializeWitnessOrphans(vertices);
        }
        // dirty tracking: the removed vertices' knowledge vanishes, and surviving rows that lost a
        // fact to the column sweep changed content too
        touched.addAll(closure.removeVertices(vertices));
        touched.addAll(vertices);
        return graph.removeVertices(vertices);
    }

    /*
    A closure fact between two SURVIVING vertices whose (transitive) witness support touches a vertex being
    removed would die in the next recompute cascade — and whether it dies depends on WHICH witness the
    derivation happened to record among equally-valid paths (order-dependent: nondeterministic output).
    Link facts describe object-graph relationships that outlive the local variable that established them
    ('target.§is ∩ collections.§iss' remains true after the loop variable goes out of scope). So before removal,
    promote such facts to raw graph edges with a direct witness, making them self-supporting. (The old engine
    achieved the same by re-materializing derived links into each statement's graph via VariableData.)
    Deliberate fact-removal flows (replaceReturnAffected: ⊆→~ after modification) do not pass through
    removeVertices, so they are unaffected.
     */
    private void materializeWitnessOrphans(Set<V> dead) {
        List<Fact<V, L>> orphans = new ArrayList<>();
        closure.factStream().forEach(fact -> {
            if (dead.contains(fact.source()) || dead.contains(fact.target())) return;
            Witness<V, L> w = witnessIndex.get(fact);
            if (!(w instanceof Witness.CompositeWitness<V, L>)) return;
            boolean orphaned = w.support().stream().anyMatch(f ->
                    dead.contains(f.source()) || dead.contains(f.target()));
            if (orphaned) orphans.add(fact);
        });
        if (LAZY_MATERIALIZATION) {
            // decided for all orphans on the closure as it is, before any promotion strengthens a label: the outcome
            // must not depend on the order of the orphans
            Set<Fact<V, L>> orphanSet = new HashSet<>(orphans);
            List<Fact<V, L>> needed = orphans.stream().filter(f -> !rederivable(f, dead, orphanSet)).toList();
            orphans.retainAll(new HashSet<>(needed));
        }
        for (Fact<V, L> fact : orphans) {
            // The closure's two directions derive independently (feature #1: rev(combined) != combine(reversed))
            // and may hold different-strength labels (∩ one way, ~ the other). The consistency invariant demands
            // symmetric coherence of graph edges: closure(v,u) >= reverse(graph(u,v)). Facts are semantically
            // symmetric-by-reverse (features #2/3), so upgrade both directions to the strongest coherent label.
            L liveReverse = closure.label(fact.target(), fact.source());
            L strongest = liveReverse == null ? fact.label() : best.apply(fact.label(), reverse.apply(liveReverse));
            L strongestReverse = reverse.apply(strongest);
            graph.addSymmetricEdge(fact.source(), fact.target(), strongest, strongestReverse);
            closure.add(fact.source(), fact.target(), strongest);
            closure.add(fact.target(), fact.source(), strongestReverse);
            touch(fact);
            witnessIndex.putIfBetter(new Fact<>(fact.source(), fact.target(), strongest),
                    new Witness.DirectWitness<>(new Fact<>(fact.source(), fact.target(), strongest), "mat"));
            witnessIndex.putIfBetter(new Fact<>(fact.target(), fact.source(), strongestReverse),
                    new Witness.DirectWitness<>(new Fact<>(fact.target(), fact.source(), strongestReverse), "mat"));
        }
    }

    /*
     CodeLaser/maddi-mod#97, #92: most orphans are derivable again without the dead vertices. A temporary that many
     variables may share content with leaves a near-complete set of pairwise '∩' facts; promoting all of them to raw
     edges made them seeds of every later update (timefold's PillarSwapMoveSelectorFactory.buildBaseMoveSelector from
     79% to 96% of the work ceiling, fernflower's DomHelper.findGeneralStatement from 67% to 87%), while the closure
     reached the same size without them. An orphan is skipped when one surviving middle vertex re-derives it, under
     the same rules as propagateForward, from two facts that are not orphans themselves: those facts keep their
     support, so the next recompute derives the orphan again, at least as strong. Anything else is promoted as
     before. Gate NOLAZYMAT.
     */
    private static final boolean LAZY_MATERIALIZATION = !Gate.isSet("NOLAZYMAT");
    // diagnostic: LAZYMATANY applies the rule to every label, not only '∩'
    private static final boolean LAZY_ANY_LABEL = Gate.isSet("LAZYMATANY");

    private boolean rederivable(Fact<V, L> fact, Set<V> dead, Set<Fact<V, L>> orphanSet) {
        // '∩' only, the bulk of the orphans: a promoted fact keeps a fixed witness, a re-derived one stays tied to its
        // support, and the label rewrites that follow witnesses (replaceReturnAffected, ⊆ to ~) then reach it. For
        // ⊆/≥ facts that changed verdicts (fernflower's DomHelper.detectStatement); '∩' is not rewritten that way.
        if (!LAZY_ANY_LABEL && fact.label() != io.codelaser.maddi.modification.link.impl.LinkNatureImpl.OBJECT_GRAPH_OVERLAPS) {
            return false;
        }
        V source = fact.source();
        V target = fact.target();
        if (!acceptForComposite.test(target)) return false;
        for (Map.Entry<V, L> left : closure.successors(source)) {
            V middle = left.getKey();
            if (dead.contains(middle) || middle.equals(target) || middle.equals(source)) continue;
            L rightLabel = closure.label(middle, target);
            if (rightLabel == null) continue;
            L combined = combine.apply(left.getValue(), rightLabel);
            if (combined == null || !valid.test(combined) || !betterThanOrEqual(combined, fact.label())) continue;
            Fact<V, L> leftFact = new Fact<>(source, middle, left.getValue());
            Fact<V, L> rightFact = new Fact<>(middle, target, rightLabel);
            if (orphanSet.contains(leftFact) || orphanSet.contains(rightFact)) continue;
            if (!acceptComposite.test(new Fact<>(source, target, combined), middle)) continue;
            Witness<V, L> leftW = witnessIndex.get(leftFact);
            Witness<V, L> rightW = witnessIndex.get(rightFact);
            if (leftW == null || rightW == null || !doesNotCreateCycle(fact, leftW, rightW)) continue;
            if (touchesDead(leftW, dead) || touchesDead(rightW, dead)) continue;
            return true;
        }
        return false;
    }

    private boolean touchesDead(Witness<V, L> witness, Set<V> dead) {
        return witness.support().stream().anyMatch(f -> dead.contains(f.source()) || dead.contains(f.target()));
    }

    /*
     The implicit facts from 'a' (ImplicitOverlap): the class partners of 'a', minus the pairs the closure holds with
     a label of their own, minus the targets no composite takes (acceptForComposite), minus the pairs the endpoint
     filter rejects. Computed once per closure version.
     */
    public Map<V, L> implicitSuccessors(V a) {
        if (implicit == null) return Map.of();
        ImplicitOverlap<V, L> overlap = currentOverlap();
        Map<V, L> cached = implicitCache.get(a);
        if (cached != null) return cached;
        List<V> targets = new ArrayList<>();
        for (V b : SEARCH ? overlap.search(a) : overlap.partners(a)) {
            if (acceptForComposite.test(b) && closure.label(a, b) == null && implicitPairOk.test(a, b)) targets.add(b);
        }
        targets.sort(vertexComparator);
        Map<V, L> result = new LinkedHashMap<>();
        targets.forEach(v -> result.put(v, implicit));
        Map<V, L> frozen = Collections.unmodifiableMap(result);
        implicitCache.put(a, frozen);
        return frozen;
    }

    // gate OVERLAPSEARCH (diagnostic): the per-vertex search instead of the class partners
    private static final boolean SEARCH = Gate.isSet("OVERLAPSEARCH");

    private ImplicitOverlap<V, L> currentOverlap() {
        if (implicitCacheVersion != closure.modCount() || currentOverlap == null) {
            implicitCache.clear();
            reversedRows = null;
            implicitCacheVersion = closure.modCount();
            currentOverlap = buildOverlap(Set.of());
        }
        return currentOverlap;
    }

    private ImplicitOverlap<V, L> buildOverlap(Set<V> excluded) {
        if (reversedRows == null) {
            reversedRows = new HashMap<>();
            for (V r : closure.sources()) {
                if (acceptForComposite.test(r)) continue;
                for (Map.Entry<V, L> e : closure.successors(r)) {
                    if (closure.label(e.getKey(), r) == null) {
                        reversedRows.computeIfAbsent(e.getKey(), _ -> new HashMap<>()).put(r, reverse.apply(e.getValue()));
                    }
                }
            }
        }
        List<V> vertices = new ArrayList<>(graph.vertices());
        vertices.sort(vertexComparator);
        return new ImplicitOverlap<>(new ImplicitOverlap.Source<>() {
            @Override
            public List<V> vertices() {
                return vertices;
            }

            @Override
            public Iterable<Map.Entry<V, L>> edges(V v) {
                return searchEdges(v);
            }

            @Override
            public L stored(V from, V to) {
                return closure.label(from, to);
            }

            @Override
            public L combine(L l1, L l2) {
                return combine.apply(l1, l2);
            }

            @Override
            public L reverse(L l) {
                return reverse.apply(l);
            }

            @Override
            public boolean isImplicit(L l) {
                return IncrementalFixpointEngine.this.isImplicit(l);
            }

            @Override
            public List<List<V>> frozenGroups() {
                return frozenGroups;
            }
        }, implicit, implicitMiddleOk, excluded, vertexComparator);
    }

    // closure rows, plus the reversed rows of vertices no composite targets: propagateBackward composes through them
    private Iterable<Map.Entry<V, L>> searchEdges(V v) {
        Map<V, L> extra = reversedRows.get(v);
        if (extra == null) return closure.successors(v);
        List<Map.Entry<V, L>> all = new ArrayList<>(extra.entrySet());
        closure.successors(v).forEach(all::add);
        return all;
    }

    /*
     Before 'dead' leaves the graph: a class whose survivors would no longer be one class without the dead vertices
     (a removed temporary was the middle that created it, or a member that held it together) is frozen: its survivors
     stay one group, the way materializeWitnessOrphans keeps derived facts as direct edges. One group per class
     instead of an edge per pair. Frozen groups lose their members as those are removed in turn.
     */
    private void freezeImplicitGroups(Set<V> dead) {
        ImplicitOverlap<V, L> before = currentOverlap();
        ImplicitOverlap<V, L> after = buildOverlap(dead);
        List<List<V>> newGroups = new ArrayList<>();
        for (Set<V> cls : before.classes()) {
            List<V> survivors = cls.stream().filter(v -> !dead.contains(v)).sorted(vertexComparator).toList();
            if (survivors.size() < 2) continue;
            V first = survivors.getFirst();
            boolean intact = survivors.stream().allMatch(v -> v.equals(first) || after.overlap(first, v));
            if (!intact) newGroups.add(survivors);
        }
        before.leafPairs().forEach((l1, others) -> {
            if (dead.contains(l1)) return;
            for (V l2 : others) {
                if (!dead.contains(l2) && vertexComparator.compare(l1, l2) < 0 && !after.overlap(l1, l2)) {
                    newGroups.add(List.of(l1, l2));
                }
            }
        });
        newGroups.sort(Comparator.comparing(g -> vertexPrinter.apply(g.getFirst())));
        List<List<V>> kept = new ArrayList<>();
        for (List<V> g : frozenGroups) {
            List<V> live = g.stream().filter(v -> !dead.contains(v)).toList();
            if (live.size() >= 2) kept.add(live);
        }
        kept.addAll(newGroups);
        frozenGroups = kept;
        if (!newGroups.isEmpty()) {
            newGroups.forEach(touched::addAll);
            currentOverlap = null;
        }
    }

    public boolean isValid(L label) {
        return valid.test(label);
    }

    public int addSymmetricEdge(V from, V to, L label, String statementIndex) {
        assert valid.test(label) : "invalid label " + label + " on edge " + from + " -> " + to
                                   + " at statement " + statementIndex;
        L reverseLabel = reverse.apply(label);
        graph.addSymmetricEdge(from, to, label, reverseLabel);
        // List.of, NOT Set.of: the two seeds enter the FIFO derivation queue in this order, and a
        // 2-element Set.of iterates in per-JVM SALTED order (java.util.ImmutableCollections) — the
        // natural-orientation-first order here was literally a coin flip per run. Seed order decides
        // which derivation paths fire first, and derived-fact survival is order-sensitive (see
        // completeSymmetrically's history), so this flip was a run-to-run bistability seed
        // (docs/design/eventual-info-hierarchy.md §"The seed-order round").
        return incrementalUpdate(List.of(new Fact<>(from, to, label), new Fact<>(to, from, reverseLabel)),
                statementIndex);
    }

    private int incrementalUpdate(Collection<Fact<V, L>> seeds, String statementIndex) {
        // canonical seed order at THE single entry point: derivation follows the FIFO queue, and
        // derived-fact survival is order-sensitive, so iteration order of the caller's collection
        // (several were per-JVM salted: Set.of pairs, toUnmodifiableSet re-adds) must not leak into
        // the closure. Sorting here makes every update a function of the seed SET.
        List<Fact<V, L>> sortedSeeds = new ArrayList<>(seeds);
        sortedSeeds.sort(Comparator
                .comparing((Fact<V, L> f) -> vertexPrinter.apply(f.source()))
                .thenComparing(f -> vertexPrinter.apply(f.target()))
                .thenComparing(f -> String.valueOf(f.label())));
        int newFacts = 0;
        Deque<Fact<V, L>> queue = new ArrayDeque<>();
        for (Fact<V, L> fact : sortedSeeds) {
            boolean added = closure.add(fact.source(), fact.target(), fact.label());
            // a seed IS a graph edge: register its direct witness even when the fact was already derived
            // (closure.add false). A direct witness beats any composite (putIfBetter), makes the fact robust
            // against removal of the intermediates the composite depended on, and makes witness choice
            // independent of whether the direct edge arrived before or after the derivation.
            boolean replaced = witnessIndex.putIfBetter(fact, new Witness.DirectWitness<>(fact, statementIndex));
            if (TRACE) lt("seed@" + statementIndex + " " + fact.print(vertexPrinter)
                          + " added=" + added + " witnessReplaced=" + replaced);
            if (added) {
                newFacts++;
                queue.add(fact);
                touch(fact);
            }
        }
        if (queue.isEmpty()) {
            return 0;
        }
        LOGGER.debug("New information, statement {}", statementIndex);
        Deque<Fact<V, L>> queueCopy = new ArrayDeque<>(queue);

        while (!queue.isEmpty()) {
            countWork();
            Fact<V, L> fact = queue.removeFirst();
            if (LOGGER.isDebugEnabled()) LOGGER.debug("-- inference phase: process {}", fact.print(vertexPrinter));
            propagateForward(fact, queue, false, null);
            propagateBackward(fact, queue, false, null);
        }
        // see e.g. TestDependent,2,3 -- they need this optimization phase
        Set<Fact<V, L>> history = new HashSet<>();
        while (!queueCopy.isEmpty()) {
            countWork();
            Fact<V, L> fact = queueCopy.removeFirst();
            if (history.add(fact)) {
                if (LOGGER.isDebugEnabled()) LOGGER.debug("-- optimization phase: process {}", fact.print(vertexPrinter));
                propagateForward(fact, queueCopy, true, history);
                propagateBackward(fact, queueCopy, true, history);
            }
        }
        LOGGER.debug("End of update, statement {}", statementIndex);
        assert consistencyCheckSucceeds();
        return newFacts;
    }

    private void propagateForward(Fact<V, L> fact, Deque<Fact<V, L>> queue, boolean optimize, Set<Fact<V, L>> history) {
        if (isImplicit(fact.label())) return;
        Iterable<Map.Entry<V, L>> successors = optimize
                ? closure.successors(fact.target())
                : graph.successors(fact.target());
        for (Map.Entry<V, L> edge : successors) {
            if (isImplicit(edge.getValue()) || chain(fact.label(), edge.getValue())) continue;
            countWork();
            L nextLabel = combine.apply(fact.label(), edge.getValue());
            V source = fact.source();
            V target = edge.getKey();
            if (acceptForComposite.test(target) && !source.equals(target) && derivable(nextLabel)) {
                Fact<V, L> next = new Fact<>(source, target, nextLabel);
                if (!acceptComposite.test(next, fact.target())) continue;
                if (history == null || addToHistory(history, next)) {
                    Fact<V, L> newFact = new Fact<>(fact.target(), target, edge.getValue());
                    Witness<V, L> leftW = witnessIndex.get(fact);
                    Witness<V, L> rightW = witnessIndex.get(newFact);
                    if (leftW != null && rightW != null && doesNotCreateCycle(next, leftW, rightW)) {
                        Witness.CompositeWitness<V, L> candidate = Witness.CompositeWitness.of(leftW, rightW, fact,
                                newFact, !optimize);
                        boolean improved = witnessIndex.putIfBetter(next, candidate);
                        boolean added = closure.add(next.source(), next.target(), next.label());
                        if (added) touch(next);
                        if (TRACE && (added || improved)) lt((optimize ? "opt-fwd " : "fwd ")
                                + next.print(vertexPrinter) + " added=" + added + " improved=" + improved
                                + " via " + fact.print(vertexPrinter) + " + " + newFact.print(vertexPrinter));
                        if (added || improved) {
                            if (LOGGER.isDebugEnabled()) LOGGER.debug(" -- -- forward, {} {} {} witness {}",
                                    next.print(vertexPrinter),
                                    added ? "added" : "",
                                    improved ? "improved" : "",
                                    candidate.print(vertexPrinter));
                            queue.addLast(next);
                            if (added) completeSymmetrically(next, fact.target(), fact, newFact, candidate, queue, optimize);
                        }
                    }
                }
            }
        }
    }

    /*
    Facts are semantically symmetric-by-reverse (features #2/3; materializeWitnessOrphans already upgrades both
    directions on removal). But the closure's two directions DERIVE independently, so whether the mirror of a
    derived fact exists depended on whether the insertion order happened to enable its own derivation path — a
    fact-level order dependence (the m∩copy flake: 'copy ∩ list' derivable in one order only). Complete the
    mirror immediately. Its witness is the naturally-oriented reversed composition (rev(f∘g) = rev(g)∘rev(f)),
    whose sub-facts exist because edges and mirrors are themselves symmetric — so witness choice stays canonical
    across insertion orders (the diamond pin). acceptForComposite guards feature #9 (no composites TARGETING a
    return variable or a someValue marker).
     */
    private void completeSymmetrically(Fact<V, L> next, V middle, Fact<V, L> left, Fact<V, L> right,
                                       Witness<V, L> fallback, Deque<Fact<V, L>> queue, boolean optimize) {
        L revLabel = reverse.apply(next.label());
        if (!derivable(revLabel) || !acceptForComposite.test(next.source())) return;
        Fact<V, L> mirror = new Fact<>(next.target(), next.source(), revLabel);
        if (!acceptComposite.test(mirror, middle)) return;
        boolean added = closure.add(mirror.source(), mirror.target(), mirror.label());
        if (added) {
            touch(mirror);
            Fact<V, L> revRight = new Fact<>(right.target(), right.source(), reverse.apply(right.label()));
            Fact<V, L> revLeft = new Fact<>(left.target(), left.source(), reverse.apply(left.label()));
            Witness<V, L> leftW = witnessIndex.get(revRight);
            Witness<V, L> rightW = witnessIndex.get(revLeft);
            Witness<V, L> witness = leftW != null && rightW != null
                    ? Witness.CompositeWitness.of(leftW, rightW, revRight, revLeft, !optimize)
                    : fallback;
            witnessIndex.putIfBetter(mirror, witness);
            if (TRACE) lt("mirror " + mirror.print(vertexPrinter) + " of " + next.print(vertexPrinter));
            if (LOGGER.isDebugEnabled()) LOGGER.debug(" -- -- symmetric completion, {}", mirror.print(vertexPrinter));
            queue.addLast(mirror);
        }
    }

    private boolean addToHistory(Set<Fact<V, L>> history, Fact<V, L> fact) {
        return history.add(fact) && (!reverse.apply(fact.label()).equals(fact.label())
                                     || history.add(new Fact<>(fact.target(), fact.source(), fact.label())));
    }

    private boolean doesNotCreateCycle(Fact<V, L> target, Witness<V, L> left, Witness<V, L> right) {
        return !left.support().contains(target) && !right.support().contains(target);
    }

    private void propagateBackward(Fact<V, L> fact, Deque<Fact<V, L>> queue, boolean optimize, Set<Fact<V, L>> history) {
        if (isImplicit(fact.label())) return;
        V source = fact.source();
        for (Map.Entry<V, L> edge : closure.successors(source)) {
            if (isImplicit(edge.getValue()) || chain(edge.getValue(), fact.label())) continue;
            countWork();
            V p = edge.getKey();
            V target = fact.target();
            if (acceptForComposite.test(target) && !p.equals(source) && !p.equals(target)) {
                L label = edge.getValue();
                L predLabel = reverse.apply(label); // because we're following the successors!
                L combined = combine.apply(predLabel, fact.label());
                if (derivable(combined)) {
                    Fact<V, L> next = new Fact<>(p, target, combined);
                    if (!acceptComposite.test(next, source)) continue;
                    if (history == null || addToHistory(history, next)) {
                        Fact<V, L> newFact = new Fact<>(p, source, predLabel);
                        Witness<V, L> leftW = witnessIndex.get(newFact);
                        Witness<V, L> rightW = witnessIndex.get(fact);
                        if (leftW != null && rightW != null && doesNotCreateCycle(next, leftW, rightW)) {
                            Witness.CompositeWitness<V, L> candidate = Witness.CompositeWitness.of(leftW, rightW, newFact,
                                    fact, !optimize);
                            // (the doesNotCreateCycle check above IS 'candidate.support() does not contain next';
                            // asserting it again would materialize every candidate's lazy support)
                            boolean added = closure.add(next.source(), next.target(), next.label());
                            if (added) touch(next);
                            boolean improved = witnessIndex.putIfBetter(next, candidate);
                            if (TRACE && (added || improved)) lt((optimize ? "opt-bwd " : "bwd ")
                                    + next.print(vertexPrinter) + " added=" + added + " improved=" + improved
                                    + " via " + newFact.print(vertexPrinter) + " + " + fact.print(vertexPrinter));
                            if (added || improved) {
                                if (LOGGER.isDebugEnabled()) LOGGER.debug(" -- -- backward, {} {} {} witness {}",
                                        added ? "added" : "",
                                        improved ? "improved" : "",
                                        next.print(vertexPrinter), candidate.print(vertexPrinter));
                                queue.addLast(next);
                                if (added) completeSymmetrically(next, source, newFact, fact, candidate, queue, optimize);
                            }
                        }
                    }
                }
            }
        }
    }

    // meant for assertions only!
    boolean consistencyCheckSucceeds() {
        boolean success = true;
        for (Map.Entry<V, Map<V, L>> entry : graph.edges()) {
            for (Map.Entry<V, L> entry2 : entry.getValue().entrySet()) {
                if (singleEdgeConsistencyCheckFails(entry.getKey(), entry2.getKey(), entry2.getValue())) {
                    success = false;
                    // keep going to produce more error messages
                }
                if (singleEdgeConsistencyCheckFails(entry2.getKey(), entry.getKey(), reverse.apply(entry2.getValue()))) {
                    success = false;
                }
            }
        }
        return success;
    }

    private boolean singleEdgeConsistencyCheckFails(V from, V to, L label) {
        L inClosure = closure.label(from, to);
        if (inClosure == null) {
            LOGGER.error("Not in closure: {} {} {}", from, label, to);
            return true;
        }
        if (!betterThanOrEqual(inClosure, label)) {
            LOGGER.error("Worse! {} {} {} but have {} in closure", from, label, to, inClosure);
            return true;
        }
        Fact<V, L> fact = new Fact<>(from, to, label);
        Witness<V, L> witness = witnessIndex.get(fact);
        // edges of the label graph must be witnessed as such
        if (witness instanceof Witness.DirectWitness<V, L> dw) {
            boolean different = !(dw.fact().equals(fact));
            if (different) {
                LOGGER.error("Direct witness different! {} vs {} {} {}", dw, from, label, to);
            }
            return different;
        }
        return false;
    }

    private boolean betterThanOrEqual(L l1, L l2) {
        assert l1 != null;
        assert l2 != null;
        return l1.equals(best.apply(l1, l2));
    }

    public Set<V> replaceReturnAffected(V from, V to, L currentLabel, L newLabel, String skipStatementIndex,
                                        Predicate<Fact<V, L>> acceptRaw) {
        return replaceReturnAffected(new Fact<>(from, to, currentLabel), newLabel, reverse.apply(newLabel),
                skipStatementIndex, acceptRaw, new HashSet<>());
    }

    private Set<V> replaceReturnAffected(Fact<V, L> fact, L newLabel, L reverseNewLabel, String skipStatementIndex,
                                         Predicate<Fact<V, L>> acceptRaw, Set<Fact<V, L>> visited) {
        // the witness DAG is meant to be acyclic, but on deeply self-referential structures it can contain a cycle
        // (io.codelaser...parseq); guard against re-entering a fact to avoid unbounded recursion. Fact equality is
        // on source+target, and graph.replace is idempotent, so skipping an already-visited fact is safe.
        if (!visited.add(fact)) return Set.of();
        Witness<V, L> witness = witnessIndex.get(fact);
        if (witness instanceof Witness.DirectWitness<V, L> dw) {
            // a raw edge inserted at skipStatementIndex is the POST-STATE of the very evaluation whose
            // modification triggered this replacement ('1:rr.§$s ⊇ 0:in.§$s' established by the modifying
            // 'rr.embed(in)' itself) — not invalidated knowledge; leave it in place. acceptRaw restricts the
            // rewrite to raw edges the modification actually owns (a composite entailed by intact edges of
            // OTHER variables is not invalidated; see WriteLinksAndModification, gate NOFLIPOWN).
            if (skipStatementIndex != null && skipStatementIndex.equals(dw.statementIndex())) return Set.of();
            if (!acceptRaw.test(fact)) return Set.of();
            boolean replaced = graph.replace(fact.source(), fact.target(), newLabel, reverseNewLabel);
            if (replaced) touch(fact);
            return replaced ? Set.of(fact.source(), fact.target()) : Set.of();
        }
        if (witness instanceof Witness.CompositeWitness<V, L> cw) {
            Fact<V, L> left = cw.left();
            Fact<V, L> right = cw.right();
            Set<V> set1 = left.label().equals(fact.label()) ? replaceReturnAffected(left, newLabel, reverseNewLabel, skipStatementIndex, acceptRaw, visited) : Set.of();
            Set<V> set2 = right.label().equals(fact.label()) ? replaceReturnAffected(right, newLabel, reverseNewLabel, skipStatementIndex, acceptRaw, visited) : Set.of();
            return Stream.concat(Stream.concat(Stream.of(fact.source(), fact.target()), set1.stream()), set2.stream())
                    .collect(Collectors.toUnmodifiableSet());
        }
        return Set.of();
    }

    public void recompute(Set<V> affected, String statementIndex, Predicate<Fact<V, L>> acceptRemoval) {
        if (TRACE) lt("recompute@" + statementIndex + " affected="
                      + affected.stream().map(vertexPrinter).sorted().collect(Collectors.joining(",")));
        // remove affected region
        Set<V> remove = new HashSet<>(affected);
        while (true) {
            List<Fact<V, L>> removedFacts = closure.removeFacts(remove, acceptRemoval);
            removedFacts.forEach(this::touch);
            Set<V> extra = witnessIndex.removeFacts(remove, removedFacts);
            if (!remove.addAll(extra)) break;
        }
        // rebuild it. NOTE: seed from the full 'remove' set, not just 'affected': removeFacts above grows 'remove'
        // to include vertices ('extra') whose facts were removed because their witnesses depended on removed facts.
        // Re-seeding only 'affected' left those extra vertices' base edges out of the closure (a base edge present
        // in the graph but missing from the closure -- consistencyCheck failure on '$__sv_ ⊇ $__rv.§ts').
        List<Fact<V, L>> seeds = new ArrayList<>();
        for (V u : remove) {
            for (var edge : graph.successors(u)) {
                seeds.add(new Fact<>(u, edge.getKey(), edge.getValue()));
                seeds.add(new Fact<>(edge.getKey(), u, reverse.apply(edge.getValue())));
            }
        }
        incrementalUpdate(seeds, statementIndex);
    }

    public Set<V> vertices() {
        return graph.vertices();
    }
}