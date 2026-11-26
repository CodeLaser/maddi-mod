package io.codelaser.maddi.modification.link.impl.basics;

import static io.codelaser.maddi.modification.link.impl.LinkNatureImpl.*;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import io.codelaser.maddi.modification.link.impl.LinkNatureImpl;
import io.codelaser.maddi.modification.prepwork.variable.LinkNature;

/*
 The closure keeps one nature per pair of variables, the one of the highest rank (LinkNature.best). That is a
 monotone data structure only when composition is monotone in the rank order: if l1 has a lower rank than l2, then
 for every m the composition of l1 with m must not have a higher rank than that of l2 with m. This test lists
 the triples where that fails, see convergence-in-call-cycles.adoc. It reports, it does not assert.
 */
public class TestLinkNatureMonotone {

    @Test
    public void test() {
        List<LinkNature> all = new ArrayList<>(List.of(
                OBJECT_GRAPH_OVERLAPS, IS_IN_OBJECT_GRAPH, OBJECT_GRAPH_CONTAINS,
                SHARES_FIELDS, IS_FIELD_OF, CONTAINS_AS_FIELD,
                SHARES_ELEMENTS, IS_SUBSET_OF, IS_SUPERSET_OF,
                COULD_BE_ELEMENT_OF, COULD_CONTAIN_AS_MEMBER, IS_ELEMENT_OF, CONTAINS_AS_MEMBER,
                IS_DECORATED_WITH, CONTAINS_DECORATION,
                LinkNatureImpl.makeIdenticalTo(null), IS_ASSIGNED_FROM, IS_ASSIGNED_TO));
        int triples = 0;
        int lost = 0;
        int weaker = 0;
        StringBuilder sb = new StringBuilder();
        for (LinkNature l1 : all) {
            for (LinkNature l2 : all) {
                if (l1.rank() >= l2.rank()) continue;
                for (LinkNature m : all) {
                    for (boolean left : new boolean[]{true, false}) {
                        ++triples;
                        LinkNature c1 = left ? l1.combine(m) : m.combine(l1);
                        LinkNature c2 = left ? l2.combine(m) : m.combine(l2);
                        if (c1.rank() <= c2.rank()) continue;
                        boolean none = !c2.valid();
                        if (none) ++lost;
                        else ++weaker;
                        sb.append(none ? "LOST   " : "WEAKER ")
                                .append(String.format(left ? "%s∘%s=%s, %s∘%s=%s" : "%2$s∘%1$s=%3$s, %5$s∘%4$s=%6$s",
                                        l1, m, c1, l2, m, c2))
                                .append("   pair holds ").append(l1).append(" and ").append(l2).append('\n');
                    }
                }
            }
        }
        System.out.println("MONOTONE triples=" + triples + " lost=" + lost + " weaker=" + weaker);
        System.out.print(sb);
    }
}
