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
 * The examples of road-to-immutability §020, "Final fields".
 */
public class TestBook020FinalFields extends BookTest {

    @Language("java")
    private static final String INPUT = """
            package a.b;
            import java.util.Random;
            public class X {
                static class EffectivelyFinal1 {
                    private Random random;
                    public EffectivelyFinal1() { initialize(3L); }
                    private void initialize(long seed) { random = new Random(seed); }
                    public int nextInt() { return random.nextInt(); }
                }
                static class EffectivelyFinal2 {
                    private Random random;
                    public EffectivelyFinal2() { reset(); }
                    public void reset() { initialize(3L); }
                    private void initialize(long seed) { random = new Random(seed); }
                    public int nextInt() { return random.nextInt(); }
                }
                static class DeeplyImmutable1 {
                    public final int x;
                    public final int y;
                    public final String message;
                    public DeeplyImmutable1(int x, int y, String message) {
                        this.message = message;
                        this.x = x;
                        this.y = y;
                    }
                }
                static class DeeplyImmutable2 {
                    private int x;
                    private int y;
                    private String message;
                    public DeeplyImmutable2(int x, int y, String message) {
                        this.message = message;
                        this.x = x;
                        this.y = y;
                    }
                    public String getMessage() { return message; }
                    public int getX() { return x; }
                    public int getY() { return y; }
                }
                static class StringsInArray {
                    private final String[] data;
                    public StringsInArray(String[] strings) { this.data = strings; }
                    public String getFirst() { return data[0]; }
                }
            }
            """;

    @DisplayName("§020, Final fields")
    @Test
    public void test() throws IOException {
        Map<String, String> actual = verdicts("a.b.X", INPUT);
        assertVerdicts(actual,
                "EffectivelyFinal1.random = @Final",
                "EffectivelyFinal2.random = !@Final",
                "DeeplyImmutable2.x = @Final",
                "DeeplyImmutable2.y = @Final",
                "DeeplyImmutable2.message = @Final",
                "StringsInArray = @FinalFields");
    }
}
