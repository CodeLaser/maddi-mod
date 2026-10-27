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

import io.codelaser.maddi.cst.api.info.Info;
import io.codelaser.maddi.cst.api.info.TypeInfo;
import io.codelaser.maddi.graph.G;
import io.codelaser.maddi.graph.V;
import io.codelaser.maddi.modification.prepwork.CommonTest;
import org.intellij.lang.annotations.Language;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * <b>A javadoc link obeys the same {@code accept()} as every other edge.</b> {@code doJavadoc} used to merge an edge
 * for any resolved tag, so {@code {@link java.util.List}} in a comment put a vertex for an out-of-parse type into a
 * graph whose every other producer goes through {@code accept()} — and a self-link put in an edge from a type to
 * itself, which {@code addType} refuses for a reason ("a member naming its own type says nothing about what must
 * exist first").
 * <p>
 * This test walks the graph's vertices rather than its edges, because that is where the difference shows: the doc
 * edge itself is below every consumer's threshold, but the VERTEX it creates is not.
 */
public class TestJavadocEdgeIsFiltered extends CommonTest {

    @Language("java")
    private static final String INPUT = """
            package a.b;
            /**
             * Holds a {@link java.util.List} of things, and is itself an {@link X}.
             */
            class X {
                /** Calls {@link #helper()}. */
                void go() {
                    helper();
                }
                private void helper() { }
            }
            """;

    @DisplayName("a javadoc link to an out-of-parse type adds no vertex, and a self-link no edge")
    @Test
    public void test() {
        TypeInfo X = javaInspector.parse(ABX, INPUT);
        G<Info> graph = prepAnalyzer().doPrimaryTypesReturnGraph(java.util.Set.of(X));

        List<String> outOfParse = graph.vertices().stream().map(V::t)
                .filter(i -> i instanceof TypeInfo ti && !ABX.equals(ti.primaryType().fullyQualifiedName()))
                .map(Info::fullyQualifiedName).sorted().toList();
        assertEquals(List.of(), outOfParse, "the only types in the graph are the parsed one and its own");

        assertEquals(List.of(), graph.edges(new V<>((Info) X)) == null ? List.of()
                        : graph.edges(new V<>((Info) X)).keySet().stream().map(V::t)
                        .filter(X::equals).map(Info::fullyQualifiedName).toList(),
                "the class comment's {@link X} is a self-link, not an edge");
    }

    private io.codelaser.maddi.modification.prepwork.PrepAnalyzer prepAnalyzer() {
        return new io.codelaser.maddi.modification.prepwork.PrepAnalyzer(runtime);
    }
}
