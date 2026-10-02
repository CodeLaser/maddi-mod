/*
 * maddi: a modification analyzer for duplication detection and immutability.
 * Copyright 2020-2025, Bart Naudts, https://github.com/CodeLaser/maddi
 *
 * This program is free software: you can redistribute it and/or modify it under the
 * terms of the GNU Lesser General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later version.
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE.  See the GNU Lesser General Public License for
 * more details. You should have received a copy of the GNU Lesser General Public
 * License along with this program.  If not, see <http://www.gnu.org/licenses/>.
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
