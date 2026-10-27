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

package io.codelaser.maddi.run.kotlinmain;

import io.codelaser.maddi.cst.api.info.Info;
import io.codelaser.maddi.cst.api.info.MethodInfo;
import io.codelaser.maddi.cst.api.info.TypeInfo;
import io.codelaser.maddi.graph.V;
import io.codelaser.maddi.callgraph.ComputeCallGraph;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>A KDoc link must not cost the member its reference edge.</b> {@code ComputeCallGraph.doRecordedReferences} adds
 * a front-end-recorded reference to a TYPE only when the member has no edge to it yet, and its own comment gives the
 * reason: "a type named in a signature or a supertype list already has its D or H edge and naming it is not a use".
 * A DOC edge is neither. {@code doJavadoc} runs first, so a member that <em>documents</em> {@code [Task]} and then
 * uses it where the desugared CST keeps no element — inside a lambda passed to a library function — ended with a doc
 * edge and no reference edge at all: it named Task, and "who refers to Task" could not say so.
 * <p>
 * Both functions here use {@code Task} in the same dropped position and neither names it in its signature; only the
 * KDoc differs. The undocumented one is the control: it was always green, which is what makes the other one's
 * emptiness a suppression rather than a missing record.
 */
public class TestDocLinkDoesNotSuppressARecordedReference {

    private static final String SOURCE = """
            package a

            class Task

            /** Makes a [Task] for each element. */
            fun documented(ns: List<Int>): List<Any> = ns.map { Task() }

            fun undocumented(ns: List<Int>): List<Any> = ns.map { Task() }
            """;

    @DisplayName("a member documenting [Task] and using it only inside a lambda keeps its reference edge to Task")
    @Test
    public void test(@TempDir Path tmp) throws Exception {
        KotlinFixture fixture = KotlinFixture.of(tmp, Map.of("a/Task.kt", SOURCE), Map.of());

        TypeInfo task = fixture.type("a.Task");
        MethodInfo documented = fixture.method("a.TaskKt", "documented");
        MethodInfo undocumented = fixture.method("a.TaskKt", "undocumented");

        // the control: the reference is recorded, and reaches the graph as a reference
        Long control = edge(fixture, undocumented, task);
        assertNotNull(control, "the control has no edge at all to Task: the reference was not recorded, so this "
                               + "test no longer isolates the suppression");
        assertTrue(ComputeCallGraph.isReference(control),
                "control edge is " + ComputeCallGraph.edgeValuePrinter(control));

        Long weight = edge(fixture, documented, task);
        assertNotNull(weight, "no edge at all from the documented function to Task");
        assertEquals(1, ComputeCallGraph.docReferenceCount(weight), "the KDoc link is one doc reference");
        assertTrue(ComputeCallGraph.isReference(weight),
                "the KDoc link suppressed the recorded reference: edge is "
                + ComputeCallGraph.edgeValuePrinter(weight) + ", control is "
                + ComputeCallGraph.edgeValuePrinter(control));
    }

    private static Long edge(KotlinFixture fixture, Info from, Info to) {
        Map<V<Info>, Long> edges = fixture.callGraph().edges(new V<>(from));
        return edges == null ? null : edges.get(new V<>(to));
    }
}
