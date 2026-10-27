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

package io.codelaser.maddi.modification.analyzer.integration;

import io.codelaser.maddi.modification.analyzer.CommonTest;
import io.codelaser.maddi.modification.analyzer.IteratingAnalyzer;
import io.codelaser.maddi.modification.analyzer.impl.IteratingAnalyzerImpl;
import io.codelaser.maddi.cst.api.analysis.Value;
import io.codelaser.maddi.cst.api.info.Info;
import io.codelaser.maddi.cst.api.info.TypeInfo;
import io.codelaser.maddi.cst.impl.analysis.PropertyImpl;
import io.codelaser.maddi.cst.impl.analysis.ValueImpl;
import org.intellij.lang.annotations.Language;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The 51% type-null cluster (elasticsearch first contact): nullness roots in verdicts that never
 * arrive (here: an abstract method without hints keeps its interface undecided) and CASCADES
 * through non-private field types. The breaking pass must convert "a verdict that will never
 * arrive" into the pessimistic FINAL_FIELDS instead of leaving the whole closure null.
 */
public class TestTypeNullBreaking extends CommonTest {

    @Language("java")
    private static final String INPUT = """
            package a.b;
            import java.util.List;
            class X {
                interface I {
                    void m(List<String> in);
                }
                static class Holder {
                    final I callback;
                    Holder(I callback) { this.callback = callback; }
                    I callback() { return callback; }
                }
            }
            """;

    @DisplayName("undecided-forever types are broken to FINAL_FIELDS, and the cascade with them")
    @Test
    public void test() {
        TypeInfo X = javaInspector.parse("a.b.X", INPUT);
        List<Info> analysisOrder = prepWork(X);
        IteratingAnalyzer iterating = new IteratingAnalyzerImpl(javaInspector,
                new IteratingAnalyzerImpl.ConfigurationBuilder().setMaxIterations(20).build());
        iterating.analyze(analysisOrder);

        TypeInfo I = X.findSubType("I");
        TypeInfo holder = X.findSubType("Holder");
        Value.Immutable immI = I.analysis().getOrNull(PropertyImpl.IMMUTABLE_TYPE, ValueImpl.ImmutableImpl.class);
        Value.Immutable immHolder = holder.analysis().getOrNull(PropertyImpl.IMMUTABLE_TYPE,
                ValueImpl.ImmutableImpl.class);
        assertNotNull(immI, "the interface must not stay immutability-undecided after breaking");
        assertNotNull(immHolder, "the cascade must be broken with it");
        // pessimistic, never optimistic: neither may claim immutability from missing information
        assertFalse(immI.isAtLeastImmutableHC(), "broken verdict must be pessimistic: " + immI);
        assertFalse(immHolder.isAtLeastImmutableHC(), "broken verdict must be pessimistic: " + immHolder);
    }
}
