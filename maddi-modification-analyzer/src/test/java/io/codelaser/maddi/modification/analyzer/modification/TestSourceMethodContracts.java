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

package io.codelaser.maddi.modification.analyzer.modification;

import io.codelaser.maddi.cst.api.info.Info;
import io.codelaser.maddi.cst.api.info.TypeInfo;
import io.codelaser.maddi.modification.analyzer.CommonTest;
import org.intellij.lang.annotations.Language;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code @Finalizer} and {@code @IgnoreModifications} on a method are pure contracts: nothing computes them. On a
 * source method {@code SourceContractMaterializer} writes them into {@code analysis()}, where
 * {@code MethodInfo.isFinalizer()} and {@code isIgnoreModification()} read them (CodeLaser/maddi#167: the Rust
 * printer's {@code self} by value, and {@code &self} over a {@code RefCell}).
 */
public class TestSourceMethodContracts extends CommonTest {

    @Language("java")
    private static final String INPUT = """
            package a.b;
            import io.codelaser.maddi.annotation.rare.Finalizer;
            public class X {
                private final StringBuilder sb = new StringBuilder();
                public X add(String s) { sb.append(s); return this; }
                @Finalizer
                public String build() { return sb.toString(); }
            }
            """;

    @DisplayName("a source method's @Finalizer is materialized")
    @Test
    public void test() {
        TypeInfo X = javaInspector.parse("a.b.X", INPUT);
        List<Info> ao = prepWork(X);
        analyzer.go(ao);
        assertTrue(X.findUniqueMethod("build", 0).isFinalizer());
        assertFalse(X.findUniqueMethod("add", 1).isFinalizer());
    }

    @Language("java")
    private static final String LOGS = """
            package a.b;
            import io.codelaser.maddi.annotation.rare.IgnoreModifications;
            import java.util.ArrayList;
            import java.util.List;
            public class Logs {
                private final List<String> buffer = new ArrayList<>();
                private int total;
                @IgnoreModifications
                public void log(String s) { buffer.add(s); }
                public int twice(int x) { log("twice"); return 2 * x; }
                public void add(int x) { log("add"); total += x; }
            }
            """;

    /**
     * Materialized, the contract is read by the receiver rules (MethodModification, and the reachability pass's
     * mirror): with the pass, a caller that only logs is non-modifying. This test runs the fixpoint engine alone, where
     * {@code twice()} stays modifying: {@code log}'s summary carries the modification of {@code this.buffer} to its
     * callers, a channel that does not look at the callee's contract.
     */
    @DisplayName("a source method's @IgnoreModifications is materialized")
    @Test
    public void ignoreModifications() {
        TypeInfo logs = javaInspector.parse("a.b.Logs", LOGS);
        List<Info> ao = prepWork(logs);
        analyzer.go(ao);
        assertTrue(logs.findUniqueMethod("log", 1).isIgnoreModification());
        assertFalse(logs.findUniqueMethod("add", 1).isNonModifying());
    }
}
