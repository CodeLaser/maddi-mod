package io.codelaser.maddi.modification.link.impl.graph;

import java.util.*;
import java.util.function.Predicate;

/*
CodeLaser/maddi-mod#92: the facts of the implicit label ('∩') of one closure, as classes.

'∩' absorbs every composition, and composes with itself ('∩ ∘ ∩ = ∩'); the derived facts are therefore, up to the
filters below, the pairs of one class:
- a class starts where two stored facts compose into '∩' through a middle vertex (a 'creation': 'x ⊆ m', 'm ~ y'),
  at a direct '∩' edge, and at a frozen group (what a removed vertex held together, see IncrementalFixpointEngine);
- it grows along every stored fact whose label keeps '∩' ('∩ ∘ ⊆ = ∩'; the reverse labels are exactly the ones
  that keep it from the left);
- a vertex that cannot be the middle of a composition (a constant marker) joins as a leaf: it overlaps the class,
  but connects nothing through itself.
A pair the closure holds with a label of its own keeps that label; a pair never targets a vertex no composite targets
(a return variable); a pair must pass the engine's endpoint filter.
Built with a union-find in time linear in the closure, for one closure version.
 */
final class ImplicitOverlap<V, L> {

    interface Source<V, L> {
        List<V> vertices(); // in a canonical order

        Iterable<Map.Entry<V, L>> edges(V v);

        L stored(V from, V to);

        L combine(L l1, L l2);

        L reverse(L l);

        boolean isImplicit(L l);

        List<List<V>> frozenGroups();

        // the creations through hub 'm', computed by 'compute'; a source may remember them while nothing they
        // depend on changed (IncrementalFixpointEngine: the rows of 'm' and of its neighbours)
        default List<Creation<V, L>> creations(V m, java.util.function.Supplier<List<Creation<V, L>>> compute) {
            return compute.get();
        }
    }

    // one creation through a hub: every x of xs overlaps every y of ys ('x in . out y' composes into '∩')
    record Creation<V, L>(List<V> xs, L in, L out, List<V> ys) {
    }

    private final Source<V, L> source;
    private final Predicate<V> middleOk;
    private final Set<V> excluded;
    private final Comparator<V> comparator;
    private final Map<V, V> parent = new HashMap<>();
    private final Set<V> activeRoots = new HashSet<>();
    private final Map<V, Set<V>> leafAnchors = new HashMap<>();
    private final Map<V, Set<V>> leafPairs = new HashMap<>();
    private final Map<L, Boolean> keepsToRight = new HashMap<>();
    private final L implicit;

    // for the per-vertex search: creation groups (xs on one side, ys on the other), indexed per vertex and side
    private record Group<V>(List<V> xs, List<V> ys) {
    }

    private final List<Group<V>> groups = new ArrayList<>();
    private final Map<V, List<Integer>> groupsOfX = new HashMap<>();
    private final Map<V, List<Integer>> groupsOfY = new HashMap<>();
    private final Map<V, List<Integer>> frozenOf = new HashMap<>();
    private final Map<L, Boolean> keepsFromLeft = new HashMap<>();
    private final List<List<V>> liveFrozen = new ArrayList<>();

    // resolved
    private final Map<V, Set<V>> members = new HashMap<>();
    private final Map<V, Set<V>> leaves = new HashMap<>();

    ImplicitOverlap(Source<V, L> source, L implicit, Predicate<V> middleOk, Set<V> excluded, Comparator<V> comparator) {
        this.source = source;
        this.implicit = implicit;
        this.middleOk = middleOk;
        this.excluded = excluded;
        this.comparator = comparator;
        build();
    }

    private boolean keepsToRight(L m) {
        return keepsToRight.computeIfAbsent(m, l -> source.isImplicit(source.combine(implicit, l)));
    }

    private V find(V v) {
        V p = parent.get(v);
        if (p == null) return v;
        V root = find(p);
        if (root != p) parent.put(v, root);
        return root;
    }

    private boolean union(V a, V b) {
        V ra = find(a);
        V rb = find(b);
        if (ra.equals(rb)) {
            activeRoots.add(ra);
            return false;
        }
        V root = comparator.compare(ra, rb) <= 0 ? ra : rb;
        V other = root == ra ? rb : ra;
        parent.put(other, root);
        activeRoots.remove(other);
        activeRoots.add(root);
        return true;
    }

    private void activate(V v) {
        activeRoots.add(find(v));
    }

    private boolean isActive(V v) {
        return activeRoots.contains(find(v));
    }

    private void attach(V leaf, V anchor) {
        leafAnchors.computeIfAbsent(leaf, _ -> new HashSet<>()).add(anchor);
        activate(anchor);
    }

    private void pairLeaves(V l1, V l2) {
        if (l1.equals(l2)) return;
        leafPairs.computeIfAbsent(l1, _ -> new HashSet<>()).add(l2);
        leafPairs.computeIfAbsent(l2, _ -> new HashSet<>()).add(l1);
    }

    static volatile boolean TRACE = false;

    private void recordGroup(List<V> xs, List<V> ys) {
        int index = groups.size();
        groups.add(new Group<>(xs, ys));
        for (V x : xs) groupsOfX.computeIfAbsent(x, _ -> new ArrayList<>()).add(index);
        for (V y : ys) groupsOfY.computeIfAbsent(y, _ -> new ArrayList<>()).add(index);
    }

    // the creations between every x of xs and y of ys (x != y)
    private void create(List<V> xs, List<V> ys) {
        recordGroup(xs, ys);
        List<V> xm = xs.stream().filter(middleOk).toList();
        List<V> ym = ys.stream().filter(middleOk).toList();
        List<V> xl = xs.stream().filter(v -> !middleOk.test(v)).toList();
        List<V> yl = ys.stream().filter(v -> !middleOk.test(v)).toList();
        if (!xm.isEmpty() && !ym.isEmpty() && !(xm.size() == 1 && ym.size() == 1 && xm.getFirst().equals(ym.getFirst()))) {
            V first = xm.getFirst();
            for (V v : xm) union(first, v);
            for (V v : ym) union(first, v);
        }
        for (V leaf : yl) for (V x : xm) if (!leaf.equals(x)) attach(leaf, x);
        for (V leaf : xl) for (V y : ym) if (!leaf.equals(y)) attach(leaf, y);
        for (V l1 : xl) for (V l2 : yl) pairLeaves(l1, l2);
    }

    private boolean hasUnstoredPair(List<V> xs, List<V> ys) {
        for (V x : xs) {
            for (V y : ys) {
                if (!x.equals(y) && source.stored(x, y) == null) return true;
            }
        }
        return false;
    }

    // the creations through hub 'm': its in-labels and out-labels whose composition is '∩', with an unstored pair
    List<Creation<V, L>> hubCreations(V m) {
        Map<L, List<V>> in = new LinkedHashMap<>();
        Map<L, List<V>> out = new LinkedHashMap<>();
        for (Map.Entry<V, L> e : source.edges(m)) {
            V w = e.getKey();
            L label = e.getValue();
            if (excluded.contains(w) || source.isImplicit(label)) continue;
            out.computeIfAbsent(label, _ -> new ArrayList<>()).add(w);
            // the engine composes through 'm' with the stored 'w → m' (forward) and with the reversed 'm → w'
            // (backward); both are in-labels
            L reversed = source.reverse(label);
            in.computeIfAbsent(reversed, _ -> new ArrayList<>()).add(w);
            L stored = source.stored(w, m);
            if (stored != null && !stored.equals(reversed) && !source.isImplicit(stored)) {
                in.computeIfAbsent(stored, _ -> new ArrayList<>()).add(w);
            }
        }
        List<Creation<V, L>> result = new ArrayList<>();
        for (Map.Entry<L, List<V>> ein : in.entrySet()) {
            for (Map.Entry<L, List<V>> eout : out.entrySet()) {
                if (source.isImplicit(source.combine(ein.getKey(), eout.getKey()))
                    && hasUnstoredPair(ein.getValue(), eout.getValue())) {
                    result.add(new Creation<>(List.copyOf(ein.getValue()), ein.getKey(), eout.getKey(),
                            List.copyOf(eout.getValue())));
                }
            }
        }
        return result;
    }

    private void build() {
        long t0 = OverlapStats.ON ? System.nanoTime() : 0;
        List<V> vertices = source.vertices().stream().filter(v -> !excluded.contains(v)).toList();
        // creations through a middle vertex
        for (V m : vertices) {
            if (!middleOk.test(m)) continue;
            List<Creation<V, L>> creations = excluded.isEmpty()
                    ? source.creations(m, () -> hubCreations(m)) : hubCreations(m);
            for (Creation<V, L> c : creations) {
                if (TRACE) System.out.println("OVL create via " + m + ": " + c.xs() + " " + c.in()
                                              + " . " + c.out() + " " + c.ys());
                create(c.xs(), c.ys());
            }
        }
        // direct implicit facts
        for (V x : vertices) {
            for (Map.Entry<V, L> e : source.edges(x)) {
                if (source.isImplicit(e.getValue()) && !excluded.contains(e.getKey())) {
                    create(List.of(x), List.of(e.getKey()));
                }
            }
        }
        // frozen groups
        List<List<V>> frozenGroups = source.frozenGroups();
        for (int i = 0; i < frozenGroups.size(); i++) {
            List<V> live = frozenGroups.get(i).stream().filter(v -> !excluded.contains(v)).toList();
            if (live.size() < 2) continue;
            for (V v : live) frozenOf.computeIfAbsent(v, _ -> new ArrayList<>()).add(liveFrozen.size());
            liveFrozen.add(live);
            List<V> middles = live.stream().filter(middleOk).toList();
            List<V> ls = live.stream().filter(v -> !middleOk.test(v)).toList();
            if (!middles.isEmpty()) {
                for (V v : middles) union(middles.getFirst(), v);
                activate(middles.getFirst());
                for (V leaf : ls) attach(leaf, middles.getFirst());
            } else {
                for (V l1 : ls) for (V l2 : ls) pairLeaves(l1, l2);
            }
        }
        // growth along the labels that keep '∩'
        long t1 = OverlapStats.ON ? System.nanoTime() : 0;
        int passes = 0;
        boolean changed = true;
        while (changed) {
            passes++;
            changed = false;
            for (V v : vertices) {
                if (!middleOk.test(v) || !isActive(v)) continue;
                for (Map.Entry<V, L> e : source.edges(v)) {
                    V w = e.getKey();
                    if (excluded.contains(w) || !keepsToRight(e.getValue())) continue;
                    if (middleOk.test(w)) {
                        if (TRACE && find(w) != find(v)) System.out.println("OVL grow " + v + " " + e.getValue() + " " + w);
                        if (find(w) != find(v) && union(v, w)) changed = true;
                    } else {
                        Set<V> anchors = leafAnchors.computeIfAbsent(w, _ -> new HashSet<>());
                        anchors.add(v);
                    }
                }
            }
        }
        if (OverlapStats.ON) OverlapStats.buildPhases(t1 - t0, System.nanoTime() - t1, passes);
        // resolve
        for (V v : vertices) {
            if (middleOk.test(v) && isActive(v)) members.computeIfAbsent(find(v), _ -> new HashSet<>()).add(v);
        }
        leafAnchors.forEach((leaf, anchors) -> {
            if (excluded.contains(leaf)) return;
            for (V anchor : anchors) leaves.computeIfAbsent(find(anchor), _ -> new HashSet<>()).add(leaf);
        });
    }

    private Set<V> rootsOf(V a) {
        if (middleOk.test(a)) {
            return isActive(a) ? Set.of(find(a)) : Set.of();
        }
        Set<V> anchors = leafAnchors.get(a);
        if (anchors == null) return Set.of();
        Set<V> roots = new HashSet<>();
        for (V anchor : anchors) roots.add(find(anchor));
        return roots;
    }

    // everything 'a' overlaps, before the pair filters
    Set<V> partners(V a) {
        Set<V> result = new HashSet<>();
        for (V root : rootsOf(a)) {
            result.addAll(members.getOrDefault(root, Set.of()));
            result.addAll(leaves.getOrDefault(root, Set.of()));
        }
        result.addAll(leafPairs.getOrDefault(a, Set.of()));
        result.remove(a);
        return result;
    }

    private boolean keepsFromLeft(L l) {
        return keepsFromLeft.computeIfAbsent(l, x -> source.isImplicit(source.combine(x, implicit)));
    }

    /*
     The pairs of 'a', following the derivation more closely than the class: a vertex 'v' is reached ('a ∩ v') only
     when the closure holds no label of its own for '(a, v)', and only a reached vertex composes on, as in the engine:
     an intermediate '∩' fact must exist to be composed with. From 'a' and from every reached middle-capable vertex:
     its creation groups, direct '∩' edges and frozen groups reach their other side (R); from a reached vertex, a label
     that keeps '∩' to the right reaches its target (R ∘ ⊆ = R), and a label that keeps it from the left makes its
     target an intermediate whose own groups count (R ∘ (⊇ ∘ R) = R). From 'a' itself, such a label does the same
     ((⊇ ∘ R) = R).
     */
    Set<V> search(V a) {
        Set<V> reached = new HashSet<>();
        Set<V> pending = new HashSet<>();
        Set<Long> expanded = new HashSet<>();
        Deque<V> rQueue = new ArrayDeque<>();
        Deque<V> pQueue = new ArrayDeque<>();
        java.util.function.Consumer<V> reach = v -> {
            if (!v.equals(a) && !excluded.contains(v) && source.stored(a, v) == null && reached.add(v)) rQueue.add(v);
        };
        java.util.function.Consumer<V> base = v -> {
            for (int g : groupsOfX.getOrDefault(v, List.of())) {
                if (expanded.add(2L * g)) groups.get(g).ys().forEach(reach);
            }
            for (int g : groupsOfY.getOrDefault(v, List.of())) {
                if (expanded.add(2L * g + 1)) groups.get(g).xs().forEach(reach);
            }
            for (int f : frozenOf.getOrDefault(v, List.of())) {
                if (expanded.add(-1L - f)) liveFrozen.get(f).forEach(reach);
            }
        };
        base.accept(a);
        for (Map.Entry<V, L> e : source.edges(a)) {
            V w = e.getKey();
            if (excluded.contains(w)) continue;
            if (source.isImplicit(e.getValue())) reach.accept(w);
            else if (keepsFromLeft(e.getValue()) && pending.add(w)) pQueue.add(w);
        }
        while (!rQueue.isEmpty() || !pQueue.isEmpty()) {
            if (!pQueue.isEmpty()) {
                V y = pQueue.removeFirst();
                if (!middleOk.test(y)) continue;
                base.accept(y);
                for (Map.Entry<V, L> e : source.edges(y)) {
                    if (source.isImplicit(e.getValue()) && !excluded.contains(e.getKey())) reach.accept(e.getKey());
                }
                continue;
            }
            V v = rQueue.removeFirst();
            if (!middleOk.test(v)) continue;
            base.accept(v);
            for (Map.Entry<V, L> e : source.edges(v)) {
                V w = e.getKey();
                if (excluded.contains(w)) continue;
                L label = e.getValue();
                if (source.isImplicit(label) || keepsToRight(label)) reach.accept(w);
                if (keepsFromLeft(label) && !source.isImplicit(label) && pending.add(w)) pQueue.add(w);
            }
        }
        return reached;
    }

    // the classes (members and leaves together), for freezing
    Collection<Set<V>> classes() {
        Map<V, Set<V>> all = new HashMap<>();
        members.forEach((r, ms) -> all.computeIfAbsent(r, _ -> new HashSet<>()).addAll(ms));
        leaves.forEach((r, ls) -> all.computeIfAbsent(r, _ -> new HashSet<>()).addAll(ls));
        return all.values();
    }

    Map<V, Set<V>> leafPairs() {
        return leafPairs;
    }

    // same class (or leaf pair), as a single test
    boolean overlap(V a, V b) {
        if (leafPairs.getOrDefault(a, Set.of()).contains(b)) return true;
        Set<V> ra = rootsOf(a);
        for (V r : rootsOf(b)) if (ra.contains(r)) return true;
        return false;
    }
}
