package io.codelaser.maddi.modification.analyzer.nullability;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.intellij.lang.annotations.Language;
import org.junit.jupiter.api.Test;

/**
 * A field updater stores what it is given in the object it is handed, not in itself: its methods do not modify
 * it (CodeLaser/maddi#217). A null passed for its value type still decides that type argument: guava's
 * {@code AtomicReferenceFieldUpdater<Waiter, @Nullable Waiter>}, written by {@code compareAndSet(future, null, w)}.
 * Without it, guava's nullability oracle counted five such fields as unsafe (2026-11-27).
 */
public class TestFieldUpdaterSlots extends NullabilityFixtures {

    @Language("java")
    private static final String INPUT = """
            package a.b;
            import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;
            public class X {
                static class Node { volatile Node next; volatile Node other; }
                private static final AtomicReferenceFieldUpdater<Node, Node> NEXT =
                        AtomicReferenceFieldUpdater.newUpdater(Node.class, Node.class, "next");
                private static final AtomicReferenceFieldUpdater<Node, Node> OTHER =
                        AtomicReferenceFieldUpdater.newUpdater(Node.class, Node.class, "other");
                boolean clear(Node n, Node expect) { return NEXT.compareAndSet(n, expect, null); }
                void put(Node n) { OTHER.lazySet(n, new Node()); }
                // a consumer that stores nothing: the null decides nothing about the comparator
                private final java.util.Comparator<Node> order = (a, b) -> 0;
                int first(Node n) { return order.compare(null, n); }
            }
            """;

    @Test
    public void test() {
        Report report = run("a.b.X", INPUT);
        String verdicts = verdicts(report);
        System.out.println(verdicts);
        assertEquals("AtomicReferenceFieldUpdater<Node, Node?>", verdict(verdicts, "NEXT"));
        assertEquals("AtomicReferenceFieldUpdater<Node, Node>", verdict(verdicts, "OTHER"));
        assertEquals("Comparator<Node>", verdict(verdicts, "order"));
    }

    private static String verdict(String verdicts, String name) {
        return verdicts.lines().filter(l -> l.startsWith(name + ": ")).findFirst().orElseThrow()
                .substring(name.length() + 2);
    }
}
