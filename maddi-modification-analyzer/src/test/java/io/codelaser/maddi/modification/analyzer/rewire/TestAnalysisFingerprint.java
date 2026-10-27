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

package io.codelaser.maddi.modification.analyzer.rewire;

import io.codelaser.maddi.modification.analyzer.CommonTest;
import io.codelaser.maddi.modification.analyzer.impl.IteratingAnalyzerImpl;
import io.codelaser.maddi.modification.prepwork.io.AnalysisFingerprint;
import io.codelaser.maddi.cst.api.element.FingerPrint;
import io.codelaser.maddi.cst.api.element.SourceSet;
import io.codelaser.maddi.cst.api.info.Info;
import io.codelaser.maddi.cst.api.info.TypeInfo;
import io.codelaser.maddi.cst.api.runtime.Runtime;
import org.intellij.lang.annotations.Language;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * First cut of the analysisFingerprint (docs/design/analysis-rewiring.md): compute it over REAL analyzer output and check the
 * mechanism — the dump is deterministic across two independent analyses of the same source, it carries the analyzer
 * verdicts, it excludes the prepwork-internal {@code VARIABLE_DATA}, and a semantic change flips it.
 */
public class TestAnalysisFingerprint extends CommonTest {

    private record Analyzed(TypeInfo typeInfo, Runtime runtime) {
    }

    private Analyzed analyze(String fqn, String source) throws IOException {
        AnalyzerBundle bundle = buildAnalyzerBundle();
        TypeInfo typeInfo = bundle.javaInspector().parse(fqn, source);
        List<Info> order = bundle.prepAnalyzer().doPrimaryType(typeInfo);
        new IteratingAnalyzerImpl(bundle.javaInspector(),
                new IteratingAnalyzerImpl.ConfigurationBuilder().setMaxIterations(10).build()).analyze(order);
        return new Analyzed(typeInfo, bundle.javaInspector().runtime());
    }

    @Language("java")
    private static final String IMMUTABLE = """
            package a.b;
            public class X {
                private final int x;
                public X(int x) { this.x = x; }
                public int x() { return x; }
            }
            """;

    @DisplayName("real analyzer output: carries the verdicts and link summaries, no prepwork VARIABLE_DATA")
    @Test
    public void testContent() throws IOException {
        Analyzed a = analyze("a.b.X", IMMUTABLE);
        String filtered = AnalysisFingerprint.dump(a.runtime(), a.typeInfo(), AnalysisFingerprint.ANALYZER_OUTPUT_ONLY);
        System.out.println("=== analyzer-output-only dump of a.b.X ===\n" + filtered);

        assertFalse(filtered.isBlank(), "there must be real analyzer output to fingerprint");
        // the verdicts and link summaries are present...
        assertTrue(filtered.contains("immutableType"), filtered);
        assertTrue(filtered.contains("nonModifyingMethod"), filtered);
        assertTrue(filtered.contains("methodLinks"), filtered);
        // ... and the prepwork-internal per-variable data is not (the codec does not even serialise VARIABLE_DATA
        // today; ANALYZER_OUTPUT_ONLY additionally guards VARIABLES_OF_ENCLOSING_METHOD).
        assertFalse(filtered.contains("variableData"), filtered);
        assertFalse(filtered.contains("localVariablesOfEnclosingMethod"), filtered);
    }

    @DisplayName("deterministic: two independent analyses of the same source hash equal")
    @Test
    public void testDeterministic() throws IOException {
        Analyzed a1 = analyze("a.b.X", IMMUTABLE);
        Analyzed a2 = analyze("a.b.X", IMMUTABLE);
        FingerPrint f1 = AnalysisFingerprint.of(a1.runtime(), a1.typeInfo());
        FingerPrint f2 = AnalysisFingerprint.of(a2.runtime(), a2.typeInfo());
        assertEquals(f1, f2, "same source, same analyzer output, so same fingerprint");
        assertFalse(f1.isNoFingerPrint());
    }

    @Language("java")
    private static final String MUTABLE = """
            package a.b;
            public class X {
                private int x;
                public X(int x) { this.x = x; }
                public int x() { return x; }
                public void setX(int x) { this.x = x; }
            }
            """;

    @DisplayName("semantic change (immutable -> mutable) flips the fingerprint")
    @Test
    public void testSemanticChangeFlips() throws IOException {
        Analyzed imm = analyze("a.b.X", IMMUTABLE);
        Analyzed mut = analyze("a.b.X", MUTABLE);
        FingerPrint fImm = AnalysisFingerprint.of(imm.runtime(), imm.typeInfo());
        FingerPrint fMut = AnalysisFingerprint.of(mut.runtime(), mut.typeInfo());
        assertNotEquals(fImm, fMut, "the verdicts differ, so the fingerprints must differ");
    }

    @Language("java")
    private static final String IMMUTABLE_SHIFTED = """
            package a.b;
            // a leading comment that shifts every line down: the analysis is identical, only positions move
            public class X {
                private final int x;
                public X(int x) { this.x = x; }
                public int x() { return x; }
            }
            """;

    @DisplayName("a line-shifting edit does not change the position-normalized fingerprint")
    @Test
    public void testLineShiftInvariant() throws IOException {
        Analyzed base = analyze("a.b.X", IMMUTABLE);
        Analyzed shifted = analyze("a.b.X", IMMUTABLE_SHIFTED);
        // raw dumps differ only in the embedded source positions...
        assertNotEquals(AnalysisFingerprint.of(base.runtime(), base.typeInfo(), AnalysisFingerprint.RAW),
                AnalysisFingerprint.of(shifted.runtime(), shifted.typeInfo(), AnalysisFingerprint.RAW),
                "raw (un-normalized) fingerprints differ because link encodings embed source positions");
        // ... which the default (position-normalized) fingerprint erases.
        assertEquals(AnalysisFingerprint.of(base.runtime(), base.typeInfo()),
                AnalysisFingerprint.of(shifted.runtime(), shifted.typeInfo()),
                "the analysis is identical; only positions moved, so the normalized fingerprint must match");
    }

    @DisplayName("per-source-set rollup: deterministic, and storePerSourceSet activates the dormant hook")
    @Test
    public void testSourceSetRollupAndStore() throws IOException {
        Analyzed a = analyze("a.b.X", IMMUTABLE);
        SourceSet ss = a.typeInfo().compilationUnit().sourceSet();
        FingerPrint direct = AnalysisFingerprint.ofSourceSet(a.runtime(), List.of(a.typeInfo()));
        assertEquals(direct, AnalysisFingerprint.ofSourceSet(a.runtime(), List.of(a.typeInfo())), "deterministic");

        assertNull(ss.analysisFingerPrintOrNull(), "not set before storePerSourceSet");
        Map<SourceSet, FingerPrint> stored = AnalysisFingerprint.storePerSourceSet(a.runtime(), List.of(a.typeInfo()));
        assertEquals(1, stored.size());
        assertEquals(direct, stored.get(ss));
        assertEquals(direct, ss.analysisFingerPrintOrNull(), "the hook is now populated (and persists via JSON)");
    }
}
