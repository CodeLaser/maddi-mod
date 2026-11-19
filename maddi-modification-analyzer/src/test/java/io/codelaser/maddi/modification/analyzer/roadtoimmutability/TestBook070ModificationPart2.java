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
 * The examples of road-to-immutability §070, "Modification, part 2".
 */
public class TestBook070ModificationPart2 extends BookTest {

    @Language("java")
    private static final String INPUT = """
            package a.b;
            import java.util.function.Supplier;
            public class X {
                static class C1 {
                    private int i;
                    public int incrementAndGet() { return ++i; }
                    public int useC2(C2 c2) { return i + c2.incrementAndGetWithI(); }
                }
                static class C2 {
                    private final int j;
                    private final C1 c1;
                    public C2(int j, C1 c1) {
                        this.c1 = c1;
                        this.j = j;
                    }
                    public int incrementAndGetWithI() { return c1.incrementAndGet() + j; }
                }
                static class ApplyLocalFunctions {
                    static class Counter {
                        private int counter;
                        public int increment() { return ++counter; }
                    }
                    private final Counter myCounter = new Counter();
                    private final Supplier<Integer> getAndIncrement = myCounter::increment;
                    private final Supplier<Integer> explicitGetAndIncrement = new Supplier<Integer>() {
                        @Override
                        public Integer get() { return myCounter.increment(); }
                    };
                    public int myIncrementer() { return getAndIncrement.get(); }
                    public int myExplicitIncrementer() { return explicitGetAndIncrement.get(); }
                }
            }
            """;

    @DisplayName("§070, Modification, part 2")
    @Test
    public void test() throws IOException {
        Map<String, String> actual = verdicts("a.b.X", INPUT);
        assertVerdicts(actual,
                "C1 = @Mutable !@Container",
                "C1.incrementAndGet() = @Modified",
                // DEVIATION: the book says @Modified: incrementAndGetWithI modifies, through the field c1 of its
                // receiver, what may be the object useC2 is called on
                "C1.useC2() = @NotModified",
                "C1.useC2(c2) = @Modified",
                "C2 = @FinalFields !@Container",
                "C2.c1 = @Modified",
                "C2.<init>(c1) = @Modified",
                "C2.incrementAndGetWithI() = @Modified",
                "ApplyLocalFunctions = @FinalFields @Container",
                "Counter = @Container",
                "Counter.increment() = @Modified",
                "ApplyLocalFunctions.myCounter = @Modified",
                "ApplyLocalFunctions.getAndIncrement = @Modified",
                "ApplyLocalFunctions.explicitGetAndIncrement = @Modified",
                "ApplyLocalFunctions.myIncrementer() = @Modified",
                "ApplyLocalFunctions.myExplicitIncrementer() = @Modified");
    }
}
