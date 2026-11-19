/*
 * maddi: a modification analyzer for duplication detection and immutability.
 * Copyright 2020-2026, CodeLaser BV, https://github.com/CodeLaser/maddi
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.codelaser.maddi.modification.analyzer.roadtoimmutability;

import org.intellij.lang.annotations.Language;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Map;

/**
 * The examples of road-to-immutability §050, "Static side effects, unmodifiable views".
 */
public class TestBook050StaticAndViews extends BookTest {

    @Language("java")
    private static final String INPUT = """
            package a.b;
            import io.codelaser.maddi.annotation.rare.IgnoreModifications;
            import java.util.*;
            import java.util.concurrent.atomic.AtomicInteger;
            public class X {
                public static class CountAccess<K> {
                    private final K k;
                    private static final AtomicInteger counter = new AtomicInteger();
                    public CountAccess(K k) { this.k = k; }
                    public K getK() {
                        counter.getAndIncrement();
                        return k;
                    }
                    public static int countAccessToK() { return counter.get(); }
                }
                public static class CountAccessIgnored<K> {
                    private final K k;
                    @IgnoreModifications
                    private static final AtomicInteger counter = new AtomicInteger();
                    public CountAccessIgnored(K k) { this.k = k; }
                    public K getK() {
                        counter.getAndIncrement();
                        return k;
                    }
                    public static int countAccessToK() { return counter.get(); }
                }
                public static class HasUniqueIdentifier<K> {
                    public final K k;
                    public final int identifier;
                    private static final AtomicInteger generator = new AtomicInteger();
                    public HasUniqueIdentifier(K k) {
                        this.k = k;
                        identifier = generator.getAndIncrement();
                    }
                }
                public static class HasUniqueIdentifierIgnored<K> {
                    public final K k;
                    public final int identifier;
                    @IgnoreModifications
                    private static final AtomicInteger generator = new AtomicInteger();
                    public HasUniqueIdentifierIgnored(K k) {
                        this.k = k;
                        identifier = generator.getAndIncrement();
                    }
                    @Override
                    public boolean equals(Object other) {
                        if (this == other) return true;
                        if (other instanceof HasUniqueIdentifierIgnored<?> hasUniqueIdentifier) {
                            return identifier == hasUniqueIdentifier.identifier;
                        }
                        return false;
                    }
                }
                public static class Printing<K> {
                    private final K k;
                    public Printing(K k) { this.k = k; }
                    public K getK() {
                        System.out.println("Getting " + k);
                        return k;
                    }
                }
                static class View {
                    private final List<String> list;
                    View(List<String> list) { this.list = list; }
                    String get(int i) { return list.get(i); }
                    int size() { return list.size(); }
                }
                static class Bag {
                    private final List<String> list = new ArrayList<>();
                    void add(String s) { list.add(s); }
                    int size() { return list.size(); }
                }
                static class Wraps {
                    private final List<String> list = new ArrayList<>();
                    List<String> view() { return Collections.unmodifiableList(list); }
                    List<String> copy() { return new ArrayList<>(list); }
                    List<String> both(boolean b) { return b ? Collections.unmodifiableList(list) : null; }
                    View asView() { return new View(list); }
                    List<String> chained() { return view(); }
                    List<String> frozen() { return List.copyOf(list); }
                }
            }
            """;

    @DisplayName("§050, Static side effects, unmodifiable views")
    @Test
    public void test() throws IOException {
        Map<String, String> actual = verdicts("a.b.X", INPUT);
        assertVerdicts(actual,
                "CountAccess = @FinalFields @Container",
                "CountAccess.k = @NotModified",
                "CountAccess.counter = @Modified",
                "CountAccess.getK() = @Modified",
                "CountAccess.countAccessToK() = @NotModified",
                "CountAccessIgnored = @Immutable(hc=true) @Container",
                "CountAccessIgnored.k = @NotModified",
                "CountAccessIgnored.getK() = @NotModified",
                "CountAccessIgnored.countAccessToK() = @NotModified",
                "HasUniqueIdentifier = @FinalFields @Container",
                "HasUniqueIdentifier.generator = @Modified",
                "HasUniqueIdentifierIgnored = @Immutable(hc=true) @Container",
                // System.out is @IgnoreModifications in the annotated JDK, so printing is no static side effect
                "Printing.getK() = @NotModified !@StaticSideEffects",
                "View = @FinalFields",
                "Bag = @FinalFields",
                "Bag.add() = @Modified",
                "Wraps = @FinalFields",
                "Wraps.view() = @UnmodifiableView",
                "Wraps.copy() = !@UnmodifiableView",
                "Wraps.both() = @UnmodifiableView",
                "Wraps.asView() = @UnmodifiableView",
                "Wraps.chained() = @UnmodifiableView");
    }
}
