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

package io.codelaser.maddi.modification.link.impl;

import io.codelaser.maddi.cst.api.info.MethodInfo;
import io.codelaser.maddi.cst.api.info.TypeInfo;
import io.codelaser.maddi.modification.link.CommonTest;
import io.codelaser.maddi.modification.link.LinkComputer;
import io.codelaser.maddi.modification.prepwork.PrepAnalyzer;
import org.intellij.lang.annotations.Language;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Linking a method again, with nothing it reads changed, must give the same summary. The first statement of a
 * branch reads the variables of the statement that holds the branch at its EVALUATION stage. A read of a
 * functional-interface variable copied that variable's links from the MERGE stage instead: the state AFTER the
 * if/else, which in the second round still held what the first round had merged -- the branch's own output.
 * Every round fed the previous one back in. Timefold's DefaultSolverJobBuilder.run() (five Consumer fields passed
 * on in both branches of an if/else) grew 23 KB, 290 KB, 570 KB, 740 KB, ... and took a 12 GB analysis down.
 */
public class TestBranchReadsEvaluationStage extends CommonTest {

    @Language("java")
    private static final String INPUT = """
            package a.b;
            import java.util.concurrent.ConcurrentHashMap;
            import java.util.concurrent.ConcurrentMap;
            import java.util.function.Consumer;
            class M {
                static class Manager<S> {
                    private final ConcurrentMap<Object, Job<S>> jobs = new ConcurrentHashMap<>();
                    Job<S> solveAndListen(Object id, Consumer<S> best, Consumer<S> fin, Consumer<S> started) {
                        return solve(id, best, fin, started);
                    }
                    Job<S> solve(Object id, Consumer<S> best, Consumer<S> fin, Consumer<S> started) {
                        return jobs.compute(id, (key, old) -> {
                            if (old != null) {
                                throw new IllegalStateException("already solving");
                            } else {
                                return new Job<>(this, id, best, fin, started);
                            }
                        });
                    }
                }
                static class Job<S> {
                    private final Manager<S> manager;
                    private final Object id;
                    private final Consumer<S> best;
                    private final Consumer<S> fin;
                    private final Consumer<S> started;
                    Job(Manager<S> manager, Object id, Consumer<S> best, Consumer<S> fin, Consumer<S> started) {
                        this.manager = manager;
                        this.id = id;
                        this.best = best;
                        this.fin = fin;
                        this.started = started;
                    }
                }
                static class Builder<S> {
                    private final Manager<S> manager;
                    private Object id;
                    private Consumer<S> best;
                    private Consumer<S> fin;
                    private Consumer<S> started;
                    Builder(Manager<S> manager) { this.manager = manager; }
                    Job<S> run() {
                        if (this.best == null) {
                            return manager.solve(id, null, fin, started);
                        } else {
                            return manager.solveAndListen(id, best, fin, started);
                        }
                    }
                }
            }
            """;

    @Test
    public void test() {
        TypeInfo M = javaInspector.parse("a.b.M", INPUT);
        new PrepAnalyzer(runtime, new PrepAnalyzer.Options.Builder().build()).doPrimaryType(M);
        LinkComputer tlc = new LinkComputerImpl(javaInspector);
        MethodInfo run = M.findSubType("Builder").findUniqueMethod("run", 0);

        tlc.doPrimaryType(M);
        String round1 = summary(run);
        tlc.doPrimaryType(M);
        assertEquals(round1, summary(run));
        tlc.doPrimaryType(M);
        assertEquals(round1, summary(run));
    }

    private static String summary(MethodInfo methodInfo) {
        return String.valueOf(methodInfo.analysis().getOrNull(MethodLinkedVariablesImpl.METHOD_LINKS,
                MethodLinkedVariablesImpl.class));
    }
}
