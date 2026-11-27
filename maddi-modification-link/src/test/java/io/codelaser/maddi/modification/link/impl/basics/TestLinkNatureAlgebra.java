package io.codelaser.maddi.modification.link.impl.basics;

import static io.codelaser.maddi.modification.link.impl.LinkNatureImpl.*;

import java.util.*;

import org.junit.jupiter.api.Test;

import io.codelaser.maddi.modification.link.impl.LinkNatureImpl;
import io.codelaser.maddi.modification.prepwork.variable.LinkNature;

/*
 The algebraic laws that make a closure over the table of compositions well defined, see
 convergence-in-call-cycles.adoc. It reports, it does not assert.

 1. the table itself
 2. reverse: rev(a∘b) = rev(b)∘rev(a)
 3. associativity: (a∘b)∘c = a∘(b∘c); without it a derived fact depends on the derivation
 4. the rank order agrees with the implication order (a nature that implies another has the higher rank)
 5. monotone in the implication order: when s implies w, s∘m implies w∘m (or w∘m is nothing), on both sides
 */
public class TestLinkNatureAlgebra {
    private final LinkNature ID = LinkNatureImpl.makeIdenticalTo(null);
    private final List<LinkNature> all = List.of(
            OBJECT_GRAPH_OVERLAPS, IS_IN_OBJECT_GRAPH, OBJECT_GRAPH_CONTAINS,
            SHARES_FIELDS, IS_FIELD_OF, CONTAINS_AS_FIELD,
            SHARES_ELEMENTS, IS_SUBSET_OF, IS_SUPERSET_OF,
            COULD_BE_ELEMENT_OF, COULD_CONTAIN_AS_MEMBER, IS_ELEMENT_OF, CONTAINS_AS_MEMBER,
            IS_DECORATED_WITH, CONTAINS_DECORATION,
            ID, IS_ASSIGNED_FROM, IS_ASSIGNED_TO);

    // strong -> the natures it implies directly; a proposal, the point of the exercise is to get it agreed
    private Map<LinkNature, Set<LinkNature>> implication() {
        Map<LinkNature, Set<LinkNature>> direct = new LinkedHashMap<>();
        BiConsumerLN add = (s, w) -> direct.computeIfAbsent(s, _ -> new LinkedHashSet<>()).add(w);
        add.accept(IS_ASSIGNED_FROM, ID);
        add.accept(IS_ASSIGNED_TO, ID);
        add.accept(ID, IS_SUBSET_OF);
        add.accept(ID, IS_SUPERSET_OF);
        add.accept(ID, SHARES_FIELDS);
        add.accept(IS_SUBSET_OF, SHARES_ELEMENTS);
        add.accept(IS_SUPERSET_OF, SHARES_ELEMENTS);
        add.accept(SHARES_ELEMENTS, OBJECT_GRAPH_OVERLAPS);
        add.accept(SHARES_FIELDS, OBJECT_GRAPH_OVERLAPS);
        add.accept(IS_ELEMENT_OF, COULD_BE_ELEMENT_OF);
        add.accept(CONTAINS_AS_MEMBER, COULD_CONTAIN_AS_MEMBER);
        add.accept(IS_ELEMENT_OF, IS_IN_OBJECT_GRAPH);
        add.accept(CONTAINS_AS_MEMBER, OBJECT_GRAPH_CONTAINS);
        add.accept(IS_FIELD_OF, IS_IN_OBJECT_GRAPH);
        add.accept(CONTAINS_AS_FIELD, OBJECT_GRAPH_CONTAINS);
        add.accept(IS_IN_OBJECT_GRAPH, OBJECT_GRAPH_OVERLAPS);
        add.accept(OBJECT_GRAPH_CONTAINS, OBJECT_GRAPH_OVERLAPS);
        add.accept(COULD_BE_ELEMENT_OF, OBJECT_GRAPH_OVERLAPS);
        add.accept(COULD_CONTAIN_AS_MEMBER, OBJECT_GRAPH_OVERLAPS);
        // transitive closure
        Map<LinkNature, Set<LinkNature>> closed = new LinkedHashMap<>();
        for (LinkNature s : all) {
            Set<LinkNature> reach = new LinkedHashSet<>();
            Deque<LinkNature> todo = new ArrayDeque<>(direct.getOrDefault(s, Set.of()));
            while (!todo.isEmpty()) {
                LinkNature w = todo.pop();
                if (reach.add(w)) todo.addAll(direct.getOrDefault(w, Set.of()));
            }
            closed.put(s, reach);
        }
        return closed;
    }

    private interface BiConsumerLN {
        void accept(LinkNature s, LinkNature w);
    }

    @Test
    public void test() {
        StringBuilder sb = new StringBuilder();

        sb.append("ALGEBRA 1 table, row∘column\n      ");
        for (LinkNature c : all) sb.append(String.format("%-4s", c));
        sb.append('\n');
        for (LinkNature r : all) {
            sb.append(String.format("%-4s| ", r));
            for (LinkNature c : all) {
                LinkNature x = r.combine(c);
                sb.append(String.format("%-4s", x.valid() ? x.toString() : "."));
            }
            sb.append('\n');
        }

        int rev = 0;
        for (LinkNature a : all) {
            for (LinkNature b : all) {
                LinkNature l = a.combine(b);
                LinkNature r = b.reverse().combine(a.reverse());
                LinkNature lr = l.valid() ? l.reverse() : l;
                if (!show(lr).equals(show(r))) {
                    ++rev;
                    sb.append("REV   rev(").append(a).append('∘').append(b).append(")=").append(show(lr))
                            .append("  rev(").append(b).append(")∘rev(").append(a).append(")=").append(show(r)).append('\n');
                }
            }
        }
        sb.append("ALGEBRA 2 reverse violations=").append(rev).append(" of ").append(all.size() * all.size()).append('\n');

        int assocLost = 0;
        int assocDiff = 0;
        Map<String, Integer> byMiddle = new TreeMap<>();
        for (LinkNature a : all) {
            for (LinkNature b : all) {
                for (LinkNature c : all) {
                    LinkNature ab = a.combine(b);
                    LinkNature bc = b.combine(c);
                    LinkNature l = ab.valid() ? ab.combine(c) : ab;
                    LinkNature r = bc.valid() ? a.combine(bc) : bc;
                    if (show(l).equals(show(r))) continue;
                    boolean lost = !l.valid() || !r.valid();
                    if (lost) ++assocLost;
                    else ++assocDiff;
                    byMiddle.merge(b.toString(), 1, Integer::sum);
                    sb.append(lost ? "ASSOC-LOST " : "ASSOC-DIFF ").append('(').append(a).append('∘').append(b)
                            .append(")∘").append(c).append('=').append(show(l)).append("  ").append(a).append("∘(")
                            .append(b).append('∘').append(c).append(")=").append(show(r)).append('\n');
                }
            }
        }
        sb.append("ALGEBRA 3 associativity: one side nothing=").append(assocLost).append(" both differ=")
                .append(assocDiff).append(" of ").append(all.size() * all.size() * all.size())
                .append(" by middle ").append(byMiddle).append('\n');

        Map<LinkNature, Set<LinkNature>> implies = implication();
        int rank = 0;
        for (LinkNature s : all) {
            for (LinkNature w : implies.get(s)) {
                if (s.rank() <= w.rank()) {
                    ++rank;
                    sb.append("RANK  ").append(s).append(" implies ").append(w).append(" but has the lower rank\n");
                }
            }
        }
        sb.append("ALGEBRA 4 rank against implication violations=").append(rank).append('\n');

        int monoLost = 0;
        int monoOther = 0;
        int monoTotal = 0;
        for (LinkNature s : all) {
            for (LinkNature w : implies.get(s)) {
                for (LinkNature m : all) {
                    for (boolean left : new boolean[]{true, false}) {
                        ++monoTotal;
                        LinkNature cw = left ? w.combine(m) : m.combine(w);
                        LinkNature cs = left ? s.combine(m) : m.combine(s);
                        if (!cw.valid()) continue;
                        if (cs.valid() && (show(cs).equals(show(cw)) || implies.get(cs).contains(cw))) continue;
                        if (cs.valid()) ++monoOther;
                        else ++monoLost;
                        sb.append(cs.valid() ? "MONO-OTHER " : "MONO-LOST  ")
                                .append(String.format(left ? "%s∘%s=%s, %s∘%s=%s" : "%2$s∘%1$s=%3$s, %5$s∘%4$s=%6$s",
                                        w, m, show(cw), s, m, show(cs)))
                                .append("   ").append(s).append(" implies ").append(w).append('\n');
                    }
                }
            }
        }
        sb.append("ALGEBRA 5 monotone in implication: lost=").append(monoLost).append(" not implied=")
                .append(monoOther).append(" of ").append(monoTotal).append('\n');
        saturated(implies, sb);
        System.out.print(sb);
    }

    /*
     6. the saturated table: a nature stands for itself and everything it implies, so s∘m is the best of w∘v over
     every w implied by s and v implied by m. Monotone by construction. Listed: the cells that change, the cells
     where the candidates have no single best one, and associativity of the result.
     */
    private void saturated(Map<LinkNature, Set<LinkNature>> implies, StringBuilder sb) {
        Map<String, LinkNature> byName = new HashMap<>();
        for (LinkNature ln : all) byName.put(ln.toString(), ln);
        Map<String, LinkNature> sat = new HashMap<>();
        int changed = 0;
        int fromNothing = 0;
        int antichains = 0;
        for (LinkNature a : all) {
            for (LinkNature b : all) {
                Set<LinkNature> candidates = new LinkedHashSet<>();
                List<LinkNature> upA = new ArrayList<>(implies.get(a));
                upA.addFirst(a);
                List<LinkNature> upB = new ArrayList<>(implies.get(b));
                upB.addFirst(b);
                for (LinkNature w : upA) {
                    for (LinkNature v : upB) {
                        LinkNature c = w.combine(v);
                        if (c.valid()) candidates.add(byName.getOrDefault(c.toString(), c));
                    }
                }
                List<LinkNature> maximal = candidates.stream()
                        .filter(c -> candidates.stream().noneMatch(d -> d != c && implies.get(d).contains(c)))
                        .sorted(Comparator.comparingInt(LinkNature::rank).reversed()).toList();
                LinkNature now = a.combine(b);
                LinkNature best = maximal.isEmpty() ? now : maximal.getFirst();
                sat.put(a + " " + b, best);
                if (maximal.size() > 1) {
                    ++antichains;
                    sb.append("SAT-SEVERAL ").append(a).append('∘').append(b).append(" candidates ").append(maximal)
                            .append(" now ").append(show(now)).append('\n');
                }
                if (!show(best).equals(show(now))) {
                    ++changed;
                    if (!now.valid()) ++fromNothing;
                    sb.append("SAT-CHANGE  ").append(a).append('∘').append(b).append(": ").append(show(now))
                            .append(" becomes ").append(show(best)).append('\n');
                }
            }
        }
        int assoc = 0;
        for (LinkNature a : all) {
            for (LinkNature b : all) {
                for (LinkNature c : all) {
                    LinkNature ab = sat.get(a + " " + b);
                    LinkNature bc = sat.get(b + " " + c);
                    LinkNature l = ab.valid() ? sat.get(byName.getOrDefault(ab.toString(), ab) + " " + c) : ab;
                    LinkNature r = bc.valid() ? sat.get(a + " " + byName.getOrDefault(bc.toString(), bc)) : bc;
                    if (!show(l).equals(show(r))) {
                        ++assoc;
                        sb.append("SAT-ASSOC   (").append(a).append('∘').append(b).append(")∘").append(c).append('=')
                                .append(show(l)).append("  ").append(a).append("∘(").append(b).append('∘').append(c)
                                .append(")=").append(show(r)).append('\n');
                    }
                }
            }
        }
        sb.append("ALGEBRA 6 saturated: cells changed=").append(changed).append(" (from nothing=").append(fromNothing)
                .append(") several candidates=").append(antichains).append(" associativity violations=").append(assoc)
                .append('\n');
        sb.append("ALGEBRA 6 table, row∘column\n      ");
        for (LinkNature c : all) sb.append(String.format("%-4s", c));
        sb.append('\n');
        for (LinkNature r : all) {
            sb.append(String.format("%-4s| ", r));
            for (LinkNature c : all) sb.append(String.format("%-4s", show(sat.get(r + " " + c))));
            sb.append('\n');
        }
    }

    private static String show(LinkNature ln) {
        return ln.valid() ? ln.toString() : ".";
    }
}
