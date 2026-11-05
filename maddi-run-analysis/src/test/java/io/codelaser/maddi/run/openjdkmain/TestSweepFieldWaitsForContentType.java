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

package io.codelaser.maddi.run.openjdkmain;

import static io.codelaser.maddi.cst.impl.analysis.PropertyImpl.IMMUTABLE_TYPE;
import static io.codelaser.maddi.cst.impl.analysis.PropertyImpl.INDEPENDENT_FIELD;
import static io.codelaser.maddi.cst.impl.analysis.ValueImpl.ImmutableImpl.MUTABLE;
import static io.codelaser.maddi.cst.impl.analysis.ValueImpl.IndependentImpl.DEPENDENT;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.codelaser.maddi.cst.api.element.SourceSet;
import io.codelaser.maddi.cst.api.info.Info;
import io.codelaser.maddi.cst.api.info.TypeInfo;
import io.codelaser.maddi.inspection.api.integration.JavaInspector;
import io.codelaser.maddi.inspection.api.parser.ParseResult;
import io.codelaser.maddi.inspection.openjdk.JavaInspectorImpl;
import io.codelaser.maddi.inspection.resource.InputConfigurationImpl;
import io.codelaser.maddi.inspection.resource.SourceSetImpl;
import io.codelaser.maddi.modification.analyzer.CycleBreakingStrategy;
import io.codelaser.maddi.modification.analyzer.impl.IteratingAnalyzerImpl;
import io.codelaser.maddi.modification.prepwork.PrepAnalyzer;

/**
 * Under the cycle-breaking sweep (CBSWEEP, maddi-mod#82) the fields are swept before the types. A field whose
 * independence waits only for its content type's immutability took the optimistic default, INDEPENDENT, in the
 * field pass; the type pass of the same round then decided the content type @Immutable(hc=true), which grades the
 * field INDEPENDENT_HC, but a field once INDEPENDENT is not revisited. Without the sweep the type came first.
 * <p>
 * The shape: {@code Step.outcome} holds an {@code Outcome}, a method-less interface (so @Immutable(hc=true)) whose
 * implementations carry an Object and a Throwable, handed out by {@code Step}'s accessors. That content is shared
 * with the caller: INDEPENDENT_HC, not INDEPENDENT.
 */
public class TestSweepFieldWaitsForContentType {

    private static final String FLOW = """
            package x;
            import java.util.Iterator;
            public class Flow {
                public interface Outcome { }
                public static class Stop implements Outcome {
                    private final int depth;
                    public Stop(int depth) { this.depth = depth; }
                }
                public static class Result implements Outcome {
                    private final Object value;
                    public Result(Object value) { this.value = value; }
                    public Object value() { return value; }
                }
                public static class Failure implements Outcome {
                    private final Throwable cause;
                    public Failure(Throwable cause) { this.cause = cause; }
                    public Throwable cause() { return cause; }
                }
                public interface State {
                    Throwable failure();
                    Object result();
                    boolean hasNext();
                    State withFailure(Throwable t);
                    State withResult(Object o);
                    State next();
                }
                public static class Step implements State {
                    private final Iterator<?> source;
                    private final Outcome outcome;
                    public Step(Iterator<?> source, Outcome outcome) {
                        this.source = source;
                        this.outcome = outcome;
                    }
                    @Override public Throwable failure() {
                        if (outcome instanceof Failure f) return f.cause();
                        return null;
                    }
                    @Override public Object result() { return ((Result) outcome).value(); }
                    @Override public boolean hasNext() { return outcome == null && source.hasNext(); }
                    @Override public State withFailure(Throwable t) { return new Step(source, new Failure(t)); }
                    @Override public State withResult(Object o) {
                        if (outcome instanceof Failure) return this;
                        return new Step(source, new Result(o));
                    }
                    @Override public State next() {
                        if (outcome instanceof Stop) return this;
                        source.next();
                        return new Step(source, outcome);
                    }
                }
                public static State run(State initial) {
                    State s = initial;
                    while (s.hasNext()) s = s.next();
                    return s;
                }
            }
            """;

    @TempDir
    Path sourceDir;

    @DisplayName("a field of an @Immutable(hc=true) interface type that hands out its content: INDEPENDENT_HC")
    @Test
    public void test() throws IOException {
        Files.writeString(Files.createDirectories(sourceDir.resolve("x")).resolve("Flow.java"), FLOW);
        SourceSet sourceSet = new SourceSetImpl.Builder().setName("main").setSourceDirectories(List.of(sourceDir))
                .setUri(sourceDir.toUri()).build();
        JavaInspector javaInspector = new JavaInspectorImpl(true, false);
        javaInspector.initialize(new InputConfigurationImpl.Builder().addSourceSets(sourceSet)
                .addClassPath(InputConfigurationImpl.DEFAULT_MODULES).build());
        ParseResult pr = javaInspector.parse(Map.of(), JavaInspectorImpl.DETAILED_SOURCES).parseResult();
        List<Info> order = new PrepAnalyzer(javaInspector.runtime()).doPrimaryTypes(Set.copyOf(pr.primaryTypes()));
        new IteratingAnalyzerImpl(javaInspector, new IteratingAnalyzerImpl.ConfigurationBuilder().setMaxIterations(12)
                .setCycleBreakingStrategy(CycleBreakingStrategy.NO_INFORMATION_IS_NON_MODIFYING).build()).analyze(order);

        TypeInfo flow = pr.primaryTypes().stream().filter(t -> "x.Flow".equals(t.fullyQualifiedName())).findFirst()
                .orElseThrow();
        assertEquals("@Immutable(hc=true)",
                flow.findSubType("Outcome").analysis().getOrDefault(IMMUTABLE_TYPE, MUTABLE).toString());
        assertEquals("@Independent(hc=true)", flow.findSubType("Step").getFieldByName("outcome", true)
                .analysis().getOrDefault(INDEPENDENT_FIELD, DEPENDENT).toString());
    }
}
