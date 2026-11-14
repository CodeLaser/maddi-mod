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

package io.codelaser.maddi.modification.analyzer.ownership;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.intellij.lang.annotations.Language;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.codelaser.maddi.cst.api.info.Info;
import io.codelaser.maddi.cst.api.info.TypeInfo;
import io.codelaser.maddi.modification.analyzer.CommonTest;
import io.codelaser.maddi.modification.analyzer.impl.IteratingAnalyzerImpl;
import io.codelaser.maddi.modification.prepwork.io.DecoratorImpl;

/**
 * The ownership verdicts in the decorated output: {@code @Fresh}, {@code @Borrowed}, {@code @Owned}, and a computed
 * {@code @Finalizer}.
 */
public class TestOwnershipDecorated extends CommonTest {

    @Language("java")
    private static final String INPUT = """
            package a.b;
            import java.util.*;
            public class X {
                static class N { int v; void inc() { v++; } }
                private final List<N> nodes = new ArrayList<>();
                private final List<String> names = new ArrayList<>();
                void grow() { nodes.add(new N()); }
                int count(List<String> other) { return other.size() + nodes.size(); }
                void name(String s) { names.add(s); }
                static List<N> make() { return new ArrayList<>(); }
                static class B {
                    private final List<String> list = new ArrayList<>();
                    B add(String s) { list.add(s); return this; }
                    List<String> build() { return list; }
                    static List<String> use() { return new B().add("a").build(); }
                }
            }
            """;

    @DisplayName("the ownership verdicts are printed as annotations")
    @Test
    public void test() throws java.io.IOException {
        AnalyzerBundle bundle = buildAnalyzerBundle();
        TypeInfo x = bundle.javaInspector().parse("a.b.X", INPUT);
        List<Info> analysisOrder = bundle.prepAnalyzer().doPrimaryType(x);
        new IteratingAnalyzerImpl(bundle.javaInspector(), new IteratingAnalyzerImpl.ConfigurationBuilder()
                .setMaxIterations(10).setModificationViaReachability(true).setTrackObjectCreations(true).build())
                .analyze(analysisOrder);
        OwnershipPass.write(new OwnershipPass(false).go(analysisOrder));
        var sourceSet = bundle.javaInspector().mainSources();
        String printed = bundle.javaInspector().print2(x.compilationUnit(),
                new DecoratorImpl(bundle.javaInspector().runtime(), sourceSet),
                bundle.javaInspector().importComputer(4, sourceSet));
        System.out.println(printed);
        assertTrue(printed.contains("@Owned(elements = true) @Independent private final List<X.N> nodes"), printed);
        // String elements: nothing to own
        assertTrue(printed.contains("@Owned @Independent private final List<String> names"), printed);
        assertTrue(printed.contains("int count(@Borrowed"), printed);
        assertTrue(printed.contains("@Fresh @Independent @NotModified static List<X.N> make()"), printed);
        assertTrue(printed.contains("@Finalizer @GetSet(\"list\") List<String> build()"), printed);
    }
}
