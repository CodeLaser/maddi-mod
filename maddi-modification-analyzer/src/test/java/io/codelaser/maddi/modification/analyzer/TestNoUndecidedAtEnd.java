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

package io.codelaser.maddi.modification.analyzer;

import io.codelaser.maddi.cst.api.info.Info;
import io.codelaser.maddi.cst.api.info.MethodInfo;
import io.codelaser.maddi.cst.api.info.TypeInfo;
import io.codelaser.maddi.cst.impl.analysis.PropertyImpl;
import io.codelaser.maddi.modification.analyzer.impl.IteratingAnalyzerImpl;
import org.intellij.lang.annotations.Language;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * When the analyzer is done, nothing is left undecided — and that is what makes an ABSENT property in the
 * written analysis results readable.
 * <p>
 * Undecided is a first-class state <em>during</em> iteration: the folds in {@code AbstractMethodAnalyzerImpl}
 * branch on {@code getOrNull(...) == null} ("undecided is not DEPENDENT — wait"). But
 * {@code WriteAnalysisResults} streams {@code analysis().propertyValueStream()} and then drops every value for
 * which {@code isDefault()} holds, so a property that is undecided and one that is decided at its default
 * (modifying, DEPENDENT, MUTABLE) are INDISTINGUISHABLE in the JSON. Reading an absent property as "the
 * analyzer could not decide" is therefore wrong, and it is a mistake that is easy to make when comparing runs.
 * <p>
 * This pins the invariant that makes the reading safe: after a complete analysis every method carries a
 * written value for both modification properties, so an absent one in the results means DEFAULT, never
 * undecided. The sample deliberately mixes the shapes that reach a default by different routes — an abstract
 * method whose implementations disagree (one modifying, one not), an accessor that exposes a field
 * (DEPENDENT), and one that returns a copy.
 */
public class TestNoUndecidedAtEnd extends CommonTest {

    @Language("java")
    private static final String SRC = """
            package a.b;
            import java.util.ArrayList;
            import java.util.List;

            public class P {
                interface Source {
                    List<String> items();
                    int count();
                }
                static class A implements Source {
                    private final List<String> list = new ArrayList<>();
                    @Override public List<String> items() { return list; }
                    @Override public int count() { return list.size(); }
                }
                static class B implements Source {
                    private int n;
                    @Override public List<String> items() { return List.of(); }
                    @Override public int count() { return ++n; }
                }
            }
            """;

    @DisplayName("after a complete analysis no modification property is left unwritten")
    @Test
    public void nothingIsUndecidedWhenTheAnalyzerIsDone() throws IOException {
        AnalyzerBundle bundle = buildAnalyzerBundle();
        TypeInfo typeInfo = bundle.javaInspector().parse("a.b.P", SRC);
        List<Info> analysisOrder = bundle.prepAnalyzer().doPrimaryType(typeInfo);
        IteratingAnalyzer analyzer = new IteratingAnalyzerImpl(bundle.javaInspector(),
                new IteratingAnalyzerImpl.ConfigurationBuilder().setMaxIterations(10).build());
        analyzer.analyze(analysisOrder);

        List<String> unwritten = new ArrayList<>();
        int methods = 0;
        for (Info info : analysisOrder) {
            if (!(info instanceof MethodInfo mi)) continue;
            methods++;
            if (!mi.analysis().haveAnalyzedValueFor(PropertyImpl.NON_MODIFYING_METHOD)) {
                unwritten.add(mi.fullyQualifiedName() + ":nonModifyingMethod");
            }
            if (!mi.analysis().haveAnalyzedValueFor(PropertyImpl.INDEPENDENT_METHOD)) {
                unwritten.add(mi.fullyQualifiedName() + ":independentMethod");
            }
        }
        assertTrue(methods >= 9, "expected the whole sample to be analysed, have " + methods + " methods");
        assertTrue(unwritten.isEmpty(), "undecided survived to the end, so an absent property in the written"
                                        + " results would be ambiguous: " + unwritten);
    }
}
