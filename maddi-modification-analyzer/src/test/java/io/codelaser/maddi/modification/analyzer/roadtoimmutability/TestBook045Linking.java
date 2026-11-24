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
 * The examples of road-to-immutability §045, "Linking, dependence".
 */
public class TestBook045Linking extends BookTest {

    @Language("java")
    private static final String INPUT = """
            package a.b;
            import io.codelaser.maddi.annotation.NotModified;
            import java.util.*;
            import java.util.stream.Collectors;
            public class X {
                static class LinkExample1<X> {
                    private final Set<X> set;
                    public LinkExample1(Set<X> xs) { this.set = xs; }
                    public void add(X x) { set.add(x); }
                }
                static class LinkExample2<X> {
                    private final Set<X> set;
                    public LinkExample2(Set<X> xs) { this.set = xs; }
                    public void add(X x) {
                        Set<X> theSet = this.set;
                        X theX = x;
                        theSet.add(theX);
                    }
                }
                interface Counter {
                    void increment();
                    @NotModified
                    int getValue();
                    @NotModified
                    String getName();
                }
                static class Counters {
                    private final Map<String, Counter> counters;
                    public Counters(Collection<Counter> counterCollection) {
                        this.counters = counterCollection.stream().collect
                            (Collectors.toUnmodifiableMap(Counter::getName, c -> c));
                    }
                    public Counter getCounter(String name) { return counters.get(name); }
                    public int getValue(String name) { return getCounter(name).getValue(); }
                }
                interface Level {
                    @NotModified
                    int getValue();
                    @NotModified
                    String getName();
                }
                static class Levels {
                    private final Map<String, Level> levels;
                    public Levels(Collection<Level> levelCollection) {
                        this.levels = levelCollection.stream().collect
                            (Collectors.toUnmodifiableMap(Level::getName, c -> c));
                    }
                    public Level getLevel(String name) { return levels.get(name); }
                    public int getValue(String name) { return getLevel(name).getValue(); }
                }
            }
            """;

    @DisplayName("§045, Linking, dependence")
    @Test
    public void test() throws IOException {
        Map<String, String> actual = verdicts("a.b.X", INPUT);
        assertVerdicts(actual,
                "LinkExample1 = !@Container",
                "LinkExample1.set = @Modified",
                "LinkExample1.<init>(xs) = @Modified",
                "LinkExample1.add() = @Modified",
                "LinkExample1.add(x) = @NotModified",
                // the alternative add method: nothing has changed
                "LinkExample2.set = @Modified",
                "LinkExample2.<init>(xs) = @Modified",
                "LinkExample2.add() = @Modified",
                "LinkExample2.add(x) = @NotModified",
                // DEVIATION: the book says @FinalFields @Container, as Counter is accessible content of Counters and
                // the constructor's parameter is therefore dependent. Computed immutable because the interface Counter
                // has no implementation here: its increment() folds over none and is @NotModified, so Counter itself
                // is @Immutable(hc=true). With a mutable class in its place Counters is @FinalFields (maddi-mod#159)
                "Counters = @Immutable(hc=true) @Container",
                "Counters.getCounter() = @NotModified",
                "Counters.getValue() = @NotModified");
    }
}
