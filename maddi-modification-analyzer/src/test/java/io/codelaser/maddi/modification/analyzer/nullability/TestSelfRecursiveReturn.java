package io.codelaser.maddi.modification.analyzer.nullability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.TreeMap;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.codelaser.maddi.cst.api.info.MethodInfo;
import io.codelaser.maddi.modification.link.impl.MethodLinkedVariablesImpl;

/**
 * CodeLaser/maddi-mod#127's second round for a method that calls itself, under a guarded null: the shape of timefold
 * ValueRangeState.getFromEntity. {@code pick} returns null; {@code get} returns its result only past
 * {@code if (valueRange != null)}, and calls itself, unguarded, before that ({@code return get(..)}) and inside the
 * placeholder lambda. The first round's summary, carried to the self call, held {@code pick}'s null
 * ({@code get <- $_ce}), which came first in walk order with no guard: {@code get} was typed nullable, and the C#
 * translation of timefold-facts had 157 nullable warnings instead of 152. The self call now sees the first round
 * without its returned nulls (LinkComputerImpl.withoutNullReturns, gate SELFNULLRETURNS).
 */
public class TestSelfRecursiveReturn extends NullabilityFixtures {

    @DisplayName("Kotlin: a guarded null does not reach a self-recursive method's return through its own call")
    @Test
    public void guardedNullThroughSelfCall() {
        Report report = run("a.b.VRS", """
                package a.b;
                import java.util.HashMap;
                import java.util.Map;
                import java.util.Objects;
                import java.util.function.BiFunction;
                import io.codelaser.maddi.annotation.Nullable;
                class VRS<E, V> {
                    interface Range<V> { }
                    record Item<E, T>(@Nullable E entity, @Nullable T left, @Nullable String leftSorter,
                                      @Nullable T right, @Nullable String rightSorter) { }
                    private final Map<E, Item<E, Range<V>>> entityMap = new HashMap<>();

                    @Nullable private <T> T pick(Item<E, T> item, @Nullable String sorter,
                                                 @Nullable BiFunction<E, String, T> placeholder) {
                        if (item.left() == null && item.right() == null && placeholder != null) {
                            var p = item.entity();
                            if (p == null) throw new IllegalStateException();
                            return placeholder.apply(p, sorter);
                        } else if (item.left() != null && (sorter == null || Objects.equals(item.leftSorter(), sorter))) {
                            return item.left();
                        } else if (item.right() != null && Objects.equals(item.rightSorter(), sorter)) {
                            return item.right();
                        }
                        return null;
                    }

                    Range<V> build(E entity) { throw new UnsupportedOperationException(); }

                    public Range<V> get(E entity, @Nullable String sorter) {
                        var item = entityMap.get(entity);
                        if (item == null) {
                            var newItem = new Item<E, Range<V>>(null, build(entity), sorter, null, null);
                            entityMap.put(entity, newItem);
                            if (newItem.entity() != null && newItem.left() == null && newItem.right() == null) {
                                return get(Objects.requireNonNull(newItem.entity()), sorter);
                            }
                            return Objects.requireNonNull(newItem.left());
                        }
                        var valueRange = pick(item, sorter, (p, s) -> get(p, s));
                        if (valueRange != null) {
                            return valueRange;
                        }
                        if (item.right() == null) {
                            return Objects.requireNonNull(item.left());
                        }
                        throw new IllegalStateException();
                    }

                    void use(E e) { System.out.println(get(e, null).hashCode()); }
                }
                """, Policy.KOTLIN);
        Map<String, String> s = new TreeMap<>();
        report.verdicts().forEach((info, pt) -> s.put(info.fullyQualifiedName(), k(pt)));
        assertEquals("T?", s.get("a.b.VRS.pick(a.b.VRS.Item,String,java.util.function.BiFunction)"));
        assertEquals("Range<V!>", s.get("a.b.VRS.get(Object,String)"), explain(report));
        // the second round itself stands: the self call's argument is linked to the map's keys, which the first
        // round, without a summary for the call, did not see
        MethodInfo get = parsed.findUniqueMethod("get", 2);
        String summary = String.valueOf(get.analysis().getOrNull(MethodLinkedVariablesImpl.METHOD_LINKS,
                MethodLinkedVariablesImpl.class));
        assertTrue(summary.contains("0:entity∩this.entityMap*.§e$s[-2]"), summary);
    }
}
