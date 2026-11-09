package io.codelaser.maddi.modification.link.impl.graph;


import java.util.*;
import java.util.function.BinaryOperator;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

// not symmetric by design!

public class Closure<V, L> {
    private final Map<V, Map<V, L>> reachable = new HashMap<>();
    private final BinaryOperator<L> best;
    // bumped on every content change: the engine's implicit-overlap cache is valid for one value of it
    private long modCount;
    // per row: a value unique over all closures, renewed on every change of that row (#92: the implicit-'∩' creation
    // memo of IncrementalFixpointEngine is valid while the rows it read keep their versions)
    private static final java.util.concurrent.atomic.AtomicLong VERSIONS = new java.util.concurrent.atomic.AtomicLong();
    private final Map<V, Long> rowVersion = new HashMap<>();

    public long modCount() {
        return modCount;
    }

    public long rowVersion(V v) {
        return rowVersion.getOrDefault(v, 0L);
    }

    private void bump(V v) {
        rowVersion.put(v, VERSIONS.incrementAndGet());
    }

    public Closure(BinaryOperator<L> best) {
        this.best = best;
    }

    /** An independent copy of the facts (labels and vertices are immutable and shared). */
    public Closure<V, L> copy() {
        Closure<V, L> copy = new Closure<>(best);
        reachable.forEach((v, row) -> copy.reachable.put(v, new HashMap<>(row)));
        copy.rowVersion.putAll(rowVersion);
        return copy;
    }

    // every fact of the other closure added to this one (a pair present in both: the best label)
    public void unionWith(Closure<V, L> other) {
        other.reachable.forEach((from, row) -> row.forEach((to, label) -> add(from, to, label)));
    }

    public boolean add(V from, V to, L label) {
        assert !from.equals(to);

        Map<V, L> map = reachable.computeIfAbsent(from, _ -> new HashMap<>());

        L current = map.get(to);
        if (current == null) {
            map.put(to, label);
            modCount++;
            bump(from);
            return true;
        }
        L newLabel = best.apply(current, label);
        map.put(to, newLabel);
        boolean changed = !current.equals(newLabel);
        if (changed) {
            modCount++;
            bump(from);
        }
        return changed;
    }

    public Set<V> sources() {
        return reachable.keySet();
    }

    public int countFacts() {
        return reachable.values().stream().mapToInt(Map::size).sum();
    }

    public Stream<Fact<V, L>> factStream() {
        return reachable.entrySet().stream()
                .flatMap(e -> e.getValue().entrySet().stream()
                        .map(e2 -> new Fact<>(e.getKey(), e2.getKey(), e2.getValue())));
    }

    public L label(V from, V to) {
        return reachable.getOrDefault(from, Map.of()).get(to);
    }

    public Stream<Map.Entry<V, L>> successorStream(V target) {
        return reachable.getOrDefault(target, Map.of()).entrySet().stream();
    }

    public Iterable<Map.Entry<V, L>> successors(V target) {
        return reachable.getOrDefault(target, Map.of()).entrySet();
    }

    public String print(Function<V, String> vertexPrinter,
                        Comparator<V> vComparator, WitnessIndex<V, L> witnessIndex) {
        return reachable.entrySet().stream().sorted(Map.Entry.comparingByKey(vComparator))
                .flatMap(e ->
                        e.getValue().entrySet().stream().sorted(Map.Entry.comparingByKey(vComparator))
                                .map(e2 -> vertexPrinter.apply(e.getKey()) + " " + e2.getValue()
                                           + " " + vertexPrinter.apply(e2.getKey()) + "   "
                                           + witnessIndex.print(vertexPrinter,
                                        new Fact<>(e.getKey(), e2.getKey(), e2.getValue()))))
                .collect(Collectors.joining("\n", "", "\n"));
    }

    public List<Fact<V, L>> removeFacts(Set<V> vertices, Predicate<Fact<V, L>> acceptForRemoval) {
        List<Fact<V, L>> result = new LinkedList<>();
        for (V v : vertices) {
            Map<V, L> map = reachable.get(v);
            if (map != null) {
                map.entrySet().removeIf(entry -> {
                    Fact<V, L> fact = new Fact<>(v, entry.getKey(), entry.getValue());
                    if (acceptForRemoval.test(fact)) {
                        result.add(fact);
                        modCount++;
                        bump(v);
                        return true;
                    }
                    return false;
                });
            }
        }
        return result;
    }

    // returns the SURVIVING row owners that lost a fact to the column sweep: their closure content
    // changed, which extraction-reuse dirty tracking (remedy A) must know about
    public Set<V> removeVertices(Set<V> vertices) {
        modCount++;
        Set<V> affectedSurvivors = new HashSet<>();
        for (Map.Entry<V, Map<V, L>> entry : reachable.entrySet()) {
            if (vertices.contains(entry.getKey())) continue;
            if (entry.getValue().keySet().removeAll(vertices)) {
                affectedSurvivors.add(entry.getKey());
            }
        }
        affectedSurvivors.forEach(this::bump);
        reachable.keySet().removeAll(vertices);
        rowVersion.keySet().removeAll(vertices);
        return affectedSurvivors;
    }
}