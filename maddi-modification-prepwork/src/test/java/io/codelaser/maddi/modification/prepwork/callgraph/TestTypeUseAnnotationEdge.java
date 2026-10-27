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

package io.codelaser.maddi.modification.prepwork.callgraph;

import io.codelaser.maddi.callgraph.ComputeCallGraph;

import io.codelaser.maddi.cst.api.info.Info;
import io.codelaser.maddi.graph.G;
import io.codelaser.maddi.inspection.api.integration.JavaInspector;
import io.codelaser.maddi.inspection.api.parser.Summary;
import io.codelaser.maddi.modification.prepwork.CommonTest;
import io.codelaser.maddi.modification.prepwork.PrepAnalyzer;
import org.intellij.lang.annotations.Language;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URISyntaxException;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A TYPE_USE-only annotation (the jspecify shape) is a dependency of the member whose signature carries it.
 * <p>
 * Since maddi 6d189c44e such an annotation lives on the {@code ParameterizedType}, not on the declaration, so
 * {@code doAnnotations(pi/mi/fi)} no longer sees it and the edge disappeared. {@code typesReferenced} folds it
 * in (the import must be computed), and so must the call graph: a type whose methods all say {@code @NonNull}
 * shares that dependency across all of them. Only the openjdk front-end routes by {@code @Target}, so it is
 * forced here.
 */
public class TestTypeUseAnnotationEdge extends CommonTest {

    @Override
    @BeforeEach
    public void beforeEach() throws IOException, URISyntaxException {
        openJdkParser();
        runtime = javaInspector.runtime();
    }

    @Language("java")
    private static final String NON_NULL = """
            package b;
            import java.lang.annotation.ElementType;
            import java.lang.annotation.Target;
            @Target(ElementType.TYPE_USE)
            public @interface NonNull {}
            """;

    @Language("java")
    private static final String X = """
            package a;
            import b.NonNull;
            import java.util.List;
            public class X {
                public void take(@NonNull String s) { }
                public @NonNull String give() { return ""; }
                public List<@NonNull String> list() { return null; }
                @NonNull String field = "";
            }
            """;

    @Test
    public void typeUseAnnotationIsADependency() {
        JavaInspector.ParseOptions parseOptions = new JavaInspector.ParseOptions.Builder()
                .setFailFast(true).setDetailedSources(true).setIgnoreModule(true).build();
        Summary summary = javaInspector.parse(Map.of("b.NonNull", NON_NULL, "a.X", X), parseOptions);
        G<Info> graph = new PrepAnalyzer(runtime).doPrimaryTypesReturnGraph(Set.copyOf(summary.types()));
        String printed = graph.toString("\n", ComputeCallGraph::edgeValuePrinter);
        for (String from : new String[]{"a.X.take(String)", "a.X.give()", "a.X.list()", "a.X.field"}) {
            assertTrue(printed.contains(from + "->D->b.NonNull"),
                    from + " has @NonNull in its signature, so it depends on b.NonNull\n" + printed);
        }
    }
}
