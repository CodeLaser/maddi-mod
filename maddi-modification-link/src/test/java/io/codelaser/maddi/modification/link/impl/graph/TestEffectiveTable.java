package io.codelaser.maddi.modification.link.impl.graph;

import static io.codelaser.maddi.modification.link.impl.LinkNatureImpl.*;

import java.util.*;
import java.util.function.Predicate;

import org.junit.jupiter.api.Test;

import io.codelaser.maddi.modification.link.impl.LinkNatureImpl;
import io.codelaser.maddi.modification.prepwork.variable.LinkNature;

/*
 The effective table of compositions: what the engine itself says about 'a' and 'c' after 'a l1 b' and 'b l2 c',
 rather than what LinkNature.combine says. The two differ, because '∩' is computed outside the table
 (ImplicitOverlap) and because PRODUCTION leaves the object-graph natures (∩ ≤ ≥) out of the closure.
 See convergence-in-call-cycles.adoc. It reports, it does not assert.

 Per mode (TEST: every nature, implicit '∩'; PRODUCTION: without ∩ ≤ ≥):
 1. the effective table, and the cells where it differs from combine
 2. monotone in the implication order: when s implies w, the cell of s holds at least what the cell of w holds
 3. order: for a chain a-b-c-d, the fact between a and d for each of the six orders of adding the three edges
 */
public class TestEffectiveTable {
    private static final LinkNature ID = LinkNatureImpl.makeIdenticalTo(null);
    private static final List<LinkNature> ALL = List.of(
            OBJECT_GRAPH_OVERLAPS, IS_IN_OBJECT_GRAPH, OBJECT_GRAPH_CONTAINS,
            SHARES_FIELDS, IS_FIELD_OF, CONTAINS_AS_FIELD,
            SHARES_ELEMENTS, IS_SUBSET_OF, IS_SUPERSET_OF,
            COULD_BE_ELEMENT_OF, COULD_CONTAIN_AS_MEMBER, IS_ELEMENT_OF, CONTAINS_AS_MEMBER,
            IS_DECORATED_WITH, CONTAINS_DECORATION,
            ID, IS_ASSIGNED_FROM, IS_ASSIGNED_TO);
    private static final Set<LinkNature> OBJECT_GRAPH = Set.of(OBJECT_GRAPH_OVERLAPS, IS_IN_OBJECT_GRAPH,
            OBJECT_GRAPH_CONTAINS);

    private static IncrementalFixpointEngine<String, LinkNature> engine(boolean production) {
        Predicate<LinkNature> valid = production ? ln -> ln.valid() && !OBJECT_GRAPH.contains(ln) : LinkNature::valid;
        IncrementalFixpointEngine<String, LinkNature> engine = new IncrementalFixpointEngine<>(LinkNature::combine,
                LinkNature::best, valid, LinkNature::score, LinkNature::reverse,
                Object::toString, String::compareTo, _ -> true);
        if (!production) engine.setImplicitLabel(OBJECT_GRAPH_OVERLAPS, _ -> true, (_, _) -> true);
        return engine;
    }

    private static String read(IncrementalFixpointEngine<String, LinkNature> engine, String from, String to) {
        engine.refreshImplicit();
        Optional<LinkNature> stored = engine.successorStream(from).filter(e -> e.getKey().equals(to))
                .map(Map.Entry::getValue).findFirst();
        if (stored.isPresent()) return stored.get().toString();
        LinkNature implicit = engine.implicitSuccessors(from).get(to);
        return implicit == null ? "." : implicit.toString();
    }

    private record Edge(String from, String to, LinkNature label) {
    }

    private static String chain(boolean production, List<Edge> edges, String from, String to) {
        IncrementalFixpointEngine<String, LinkNature> engine = engine(production);
        for (Edge e : edges) engine.addSymmetricEdge(e.from, e.to, e.label, "0");
        return read(engine, from, to);
    }

    private static Map<String, Set<String>> implication() {
        Map<String, Set<String>> direct = new LinkedHashMap<>();
        String[][] pairs = {{"≡", "⊆"}, {"≡", "⊇"}, {"≡", "≈"}, {"⊆", "~"}, {"⊇", "~"}, {"~", "∩"}, {"≈", "∩"},
                {"∈", "∈?"}, {"∋", "∋?"}, {"∈", "≤"}, {"∋", "≥"}, {"≺", "≤"}, {"≻", "≥"}, {"≤", "∩"}, {"≥", "∩"},
                {"∈?", "∩"}, {"∋?", "∩"}};
        for (String[] p : pairs) direct.computeIfAbsent(p[0], _ -> new LinkedHashSet<>()).add(p[1]);
        Map<String, Set<String>> closed = new LinkedHashMap<>();
        for (LinkNature s : ALL) {
            Set<String> reach = new LinkedHashSet<>();
            Deque<String> todo = new ArrayDeque<>(direct.getOrDefault(s.toString(), Set.of()));
            while (!todo.isEmpty()) {
                String w = todo.pop();
                if (reach.add(w)) todo.addAll(direct.getOrDefault(w, Set.of()));
            }
            closed.put(s.toString(), reach);
        }
        return closed;
    }

    /*
     Two facts on one pair: 'a p b' and 'a q b', added in both orders. The closure keeps one nature per pair, so
     1. which one is kept, and does that depend on the order;
     2. is what 'b' holds about 'a' the reverse of what 'a' holds about 'b';
     3. with a third edge 'c l a': does the fact between c and b depend on the order of p and q.
     */
    @Test
    public void parallel() {
        StringBuilder sb = new StringBuilder();
        for (boolean production : new boolean[]{false, true}) {
            String mode = production ? "PRODUCTION" : "TEST";
            List<LinkNature> natures = ALL.stream().filter(ln -> !production || !OBJECT_GRAPH.contains(ln)).toList();
            int pairs = 0;
            int orderOfTwo = 0;
            int notReverse = 0;
            int orderOfThree = 0;
            for (int i = 0; i < natures.size(); i++) {
                for (int j = i + 1; j < natures.size(); j++) {
                    LinkNature p = natures.get(i);
                    LinkNature q = natures.get(j);
                    ++pairs;
                    Edge ep = new Edge("a", "b", p);
                    Edge eq = new Edge("a", "b", q);
                    String pq = chain(production, List.of(ep, eq), "a", "b");
                    String qp = chain(production, List.of(eq, ep), "a", "b");
                    String back = chain(production, List.of(ep, eq), "b", "a");
                    if (!pq.equals(qp)) {
                        ++orderOfTwo;
                        sb.append("PAR-ORDER   ").append(mode).append(" a").append(p).append("b, a").append(q)
                                .append("b: a-b is ").append(pq).append(" or ").append(qp).append('\n');
                    }
                    String expectedBack = natures.stream().filter(n -> n.toString().equals(pq)).findFirst()
                            .map(n -> n.reverse().toString()).orElse(".");
                    if (!back.equals(expectedBack)) {
                        ++notReverse;
                        sb.append("PAR-REVERSE ").append(mode).append(" a").append(p).append("b, a").append(q)
                                .append("b: a-b is ").append(pq).append(", b-a is ").append(back).append('\n');
                    }
                    for (LinkNature l : natures) {
                        Edge ca = new Edge("c", "a", l);
                        Set<String> results = new TreeSet<>();
                        for (List<Edge> order : List.of(List.of(ca, ep, eq), List.of(ca, eq, ep), List.of(ep, eq, ca),
                                List.of(eq, ep, ca), List.of(ep, ca, eq), List.of(eq, ca, ep))) {
                            results.add(chain(production, order, "c", "b"));
                        }
                        if (results.size() > 1) {
                            ++orderOfThree;
                            sb.append("PAR-THIRD   ").append(mode).append(" c").append(l).append("a, a").append(p)
                                    .append("b, a").append(q).append("b: c-b is ").append(results).append('\n');
                        }
                    }
                }
            }
            sb.append("PARALLEL ").append(mode).append(" pairs=").append(pairs).append(" order of the two=")
                    .append(orderOfTwo).append(" not the reverse=").append(notReverse)
                    .append(" third edge depends on order=").append(orderOfThree).append('\n');
        }
        System.out.print(sb);
    }

    @Test
    public void test() {
        StringBuilder sb = new StringBuilder();
        for (boolean production : new boolean[]{false, true}) {
            String mode = production ? "PRODUCTION" : "TEST";
            List<LinkNature> natures = ALL.stream().filter(ln -> !production || !OBJECT_GRAPH.contains(ln)).toList();
            Map<String, String> cell = new HashMap<>();
            int differs = 0;
            int orderOfTwo = 0;
            sb.append("EFFECTIVE ").append(mode).append(" 1 table, row∘column\n      ");
            for (LinkNature c : natures) sb.append(String.format("%-4s", c));
            sb.append('\n');
            StringBuilder diff = new StringBuilder();
            for (LinkNature l1 : natures) {
                sb.append(String.format("%-4s| ", l1));
                for (LinkNature l2 : natures) {
                    Edge ab = new Edge("a", "b", l1);
                    Edge bc = new Edge("b", "c", l2);
                    String eff = chain(production, List.of(ab, bc), "a", "c");
                    String other = chain(production, List.of(bc, ab), "a", "c");
                    if (!eff.equals(other)) {
                        ++orderOfTwo;
                        diff.append("EFF-ORDER2 ").append(mode).append(' ').append(l1).append('∘').append(l2)
                                .append(": ").append(eff).append(" or ").append(other).append('\n');
                    }
                    cell.put(l1 + " " + l2, eff);
                    LinkNature raw = l1.combine(l2);
                    String rawString = raw.valid() && (!production || !OBJECT_GRAPH.contains(raw)) ? raw.toString() : ".";
                    if (!rawString.equals(eff)) {
                        ++differs;
                        diff.append("EFF-DIFF   ").append(mode).append(' ').append(l1).append('∘').append(l2)
                                .append(": combine ").append(rawString).append(", engine ").append(eff).append('\n');
                    }
                    sb.append(String.format("%-4s", eff));
                }
                sb.append('\n');
            }
            sb.append(diff);
            sb.append("EFFECTIVE ").append(mode).append(" 1 cells that differ from combine=").append(differs)
                    .append(" cells that depend on the order of the two edges=").append(orderOfTwo).append('\n');

            Map<String, Set<String>> implies = implication();
            int lost = 0;
            int other = 0;
            int total = 0;
            for (LinkNature s : natures) {
                for (String w : implies.get(s.toString())) {
                    if (natures.stream().noneMatch(n -> n.toString().equals(w))) continue;
                    for (LinkNature m : natures) {
                        for (boolean left : new boolean[]{true, false}) {
                            ++total;
                            String cw = left ? cell.get(w + " " + m) : cell.get(m + " " + w);
                            String cs = left ? cell.get(s + " " + m) : cell.get(m + " " + s);
                            if (".".equals(cw) || cw.equals(cs)) continue;
                            if (!".".equals(cs) && implies.getOrDefault(cs, Set.of()).contains(cw)) continue;
                            if (".".equals(cs)) ++lost;
                            else ++other;
                            sb.append(".".equals(cs) ? "EFF-LOST   " : "EFF-OTHER  ").append(mode).append(' ')
                                    .append(String.format(left ? "%s∘%s=%s, %s∘%s=%s" : "%2$s∘%1$s=%3$s, %5$s∘%4$s=%6$s",
                                            w, m, cw, s, m, cs))
                                    .append("   ").append(s).append(" implies ").append(w).append('\n');
                        }
                    }
                }
            }
            sb.append("EFFECTIVE ").append(mode).append(" 2 monotone in implication: lost=").append(lost)
                    .append(" not implied=").append(other).append(" of ").append(total).append('\n');

            int triples = 0;
            int orderDependent = 0;
            int presence = 0;
            Map<String, Integer> byMiddle = new TreeMap<>();
            int[][] orders = {{0, 1, 2}, {0, 2, 1}, {1, 0, 2}, {1, 2, 0}, {2, 0, 1}, {2, 1, 0}};
            for (LinkNature l1 : natures) {
                for (LinkNature l2 : natures) {
                    for (LinkNature l3 : natures) {
                        ++triples;
                        List<Edge> edges = List.of(new Edge("a", "b", l1), new Edge("b", "c", l2), new Edge("c", "d", l3));
                        Set<String> results = new TreeSet<>();
                        for (int[] order : orders) {
                            results.add(chain(production,
                                    List.of(edges.get(order[0]), edges.get(order[1]), edges.get(order[2])), "a", "d"));
                        }
                        if (results.size() > 1) {
                            ++orderDependent;
                            if (results.contains(".")) ++presence;
                            byMiddle.merge(l2.toString(), 1, Integer::sum);
                            sb.append("EFF-ORDER3 ").append(mode).append(' ').append(l1).append(' ').append(l2).append(' ')
                                    .append(l3).append(": ").append(results).append('\n');
                        }
                    }
                }
            }
            sb.append("EFFECTIVE ").append(mode).append(" 3 chains whose result depends on the order of the edges=")
                    .append(orderDependent).append(" (present or absent=").append(presence).append(") of ")
                    .append(triples).append(" by middle ").append(byMiddle).append('\n');
        }
        System.out.print(sb);
    }
}
