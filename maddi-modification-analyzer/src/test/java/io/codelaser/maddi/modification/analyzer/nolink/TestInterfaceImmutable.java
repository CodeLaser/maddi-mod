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

package io.codelaser.maddi.modification.analyzer.nolink;

import io.codelaser.maddi.modification.analyzer.CommonTest;
import io.codelaser.maddi.cst.api.info.Info;
import io.codelaser.maddi.cst.api.info.TypeInfo;
import org.intellij.lang.annotations.Language;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression tests for a class implementing an interface whose implementation turns out to be modifying. Both shapes
 * below used to crash the analyzer with
 * {@code UnsupportedOperationException: Trying to overwrite @FinalFields/@Immutable(hc=true) with @Mutable}: the type's
 * immutability was committed to a higher value while its abstract supertype was still undecided (the supertype's
 * immutability was estimated from its non-abstract members only, ignoring its abstract methods), and once the supertype
 * was correctly decided @Mutable the subtype's immutability had to be downgraded, which the monotonic-overwrite guard
 * rejected. Fixed by having {@code immutableSuper} wait for an undecided abstract supertype rather than over-estimate.
 */
public class TestInterfaceImmutable extends CommonTest {

    // one implementation, whose method modifies a final field of mutable type
    @Language("java")
    private static final String FINAL_MUTABLE_FIELD = """
            package a.b;
            import java.util.ArrayList;
            import java.util.List;
            class X {
                interface Sink { void add(String s); }
                static class ListSink implements Sink {
                    private final List<String> list = new ArrayList<>();
                    @Override public void add(String s) { list.add(s); }
                }
            }
            """;

    @DisplayName("an interface impl that modifies a final collection field analyzes to @Mutable, without crashing")
    @Test
    public void testFinalMutableField() {
        TypeInfo X = javaInspector.parse("a.b.X", FINAL_MUTABLE_FIELD);
        List<Info> ao = prepWork(X);
        analyzer.go(ao);

        TypeInfo listSink = X.findSubType("ListSink");
        assertTrue(listSink.findUniqueMethod("add", 1).isModifying());
        assertTrue(listSink.getFieldByName("list", true).isModified());
    }

    // two implementations of the same interface with different modification profiles
    @Language("java")
    private static final String CONFLICTING_IMPLS = """
            package a.b;
            class X {
                interface Sink { void add(int s); }
                static class Accumulator implements Sink {
                    private int total;
                    @Override public void add(int s) { this.total += s; }
                }
                static class NoOpSink implements Sink {
                    @Override public void add(int s) { }
                }
            }
            """;

    @DisplayName("an interface with a modifying and a non-modifying implementation analyzes without crashing")
    @Test
    public void testConflictingImplementations() {
        TypeInfo X = javaInspector.parse("a.b.X", CONFLICTING_IMPLS);
        List<Info> ao = prepWork(X);
        analyzer.go(ao);

        assertTrue(X.findSubType("Accumulator").findUniqueMethod("add", 1).isModifying());
        assertFalse(X.findSubType("NoOpSink").findUniqueMethod("add", 1).isModifying());
    }
}
