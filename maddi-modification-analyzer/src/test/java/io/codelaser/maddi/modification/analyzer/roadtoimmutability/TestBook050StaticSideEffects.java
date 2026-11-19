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
 * The examples of road-to-immutability §050, "Static side effects seen from another type".
 */
public class TestBook050StaticSideEffects extends BookTest {

    @Language("java")
    private static final String INPUT = """
            package a.b;
            public class X {
                static int twice() { return Counter.next() + Counter.next(); }
            }
            class Counter {
                private static int count;
                static int next() { return ++count; }
            }
            """;

    @DisplayName("§050, Static side effects seen from another type")
    @Test
    public void test() throws IOException {
        Map<String, String> actual = verdicts("a.b.X", INPUT);
        assertVerdicts(actual,
                "X.twice() = @StaticSideEffects",
                "Counter.next() = !@StaticSideEffects");
    }
}
