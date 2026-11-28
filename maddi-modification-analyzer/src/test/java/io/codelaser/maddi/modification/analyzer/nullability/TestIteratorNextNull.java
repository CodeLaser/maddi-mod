package io.codelaser.maddi.modification.analyzer.nullability;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.intellij.lang.annotations.Language;
import org.junit.jupiter.api.Test;

/**
 * CodeLaser/maddi-mod#106: fernflower's {@code FastFixedSetFactory.FastFixedSetIterator.next()} ends in
 * {@code return pointer < size ? values.get(pointer) : null;}, yet its result came out non-null. The control is the
 * same body in a method that overrides nothing. Under the stored-verdict policy both are nullable; the non-null came
 * from the Kotlin policy's override rule ({@code Contracts.overridesKotlinNonNullReturn}), whose facts the Rust
 * translation reads.
 */
public class TestIteratorNextNull extends NullabilityFixtures {

    @Language("java")
    private static final String INPUT = """
            package a.b;
            import java.util.Iterator;
            import java.util.List;
            public class X<E> implements Iterable<E> {
                private final List<E> values;
                X(List<E> values) { this.values = values; }
                @Override public Iterator<E> iterator() { return new It<>(values); }

                static class It<E> implements Iterator<E> {
                    private final List<E> values;
                    private int pointer = -1;
                    It(List<E> values) { this.values = values; }
                    @Override public boolean hasNext() { return pointer + 1 < values.size(); }
                    @Override public E next() {
                        pointer++;
                        return pointer < values.size() ? values.get(pointer) : null;
                    }
                    public E plain() {
                        return pointer < values.size() ? values.get(pointer) : null;
                    }
                }

                // fernflower's VBStyleCollection: keys beside the values, read by index
                static class VB<V, K> extends java.util.ArrayList<V> {
                    private java.util.ArrayList<K> lstKeys = new java.util.ArrayList<>();
                    public void addWithKey(V element, K key) { super.add(element); lstKeys.add(key); }
                    public K getKey(int index) { return lstKeys.get(index); }
                }

                // fernflower's FastFixedSetIterator: reads the keys of a VB<int[], E>
                static final class VBIt<E> implements Iterator<E> {
                    private final VB<int[], E> col;
                    private final int size;
                    private int pointer = -1;
                    VBIt(VB<int[], E> col) { this.col = col; this.size = col.size(); }
                    @Override public boolean hasNext() { return pointer + 1 < size; }
                    @Override public E next() {
                        pointer++;
                        return pointer < size ? col.getKey(pointer) : null;
                    }
                }
            }
            """;

    @Test
    public void test() {
        Report report = run("a.b.X", INPUT);
        String verdicts = verdicts(report);
        assertEquals("E?", verdict(verdicts, "plain()"));
        assertEquals("E?", verdict(verdicts, "next()"));
        // two next() methods: both must be nullable
        assertEquals(2, verdicts.lines().filter(l -> l.equals("next(): E?")).count(), verdicts);
    }

    private static String verdict(String verdicts, String name) {
        return verdicts.lines().filter(l -> l.startsWith(name + ": ")).findFirst().orElseThrow()
                .substring(name.length() + 2);
    }
}
